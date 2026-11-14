package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class HotReloadConfig(
    val appId: String,
    val projectDir: File,
    val variant: String = "debug",
    val intervalMs: Long = 300L,
    val once: Boolean = false,
    val allowFallback: Boolean = true,
    val javaHome: File? = null,
    val gradleArgs: List<String> = emptyList()
) {
    fun toGradleExecutionConfig(): GradleExecutionConfig = GradleExecutionConfig(
        javaHome = javaHome?.let(GradleJvmResolver::normalizeJdkHome),
        extraGradleArgs = gradleArgs
    )
}

@Suppress("LongParameterList", "TooManyFunctions")
class HotReloadSession(
    private val config: HotReloadConfig,
    private val adbManager: AdbManager = AdbManager(),
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val layout: GradleProjectLayout = GradleProjectScanner.scan(
        startDir = config.projectDir,
        gradleConfig = config.toGradleExecutionConfig()
    ),
    private val tracker: SourceChangeTracker = SourceChangeTracker(layout).apply { initializeBaseline() },
    private val compiler: IncrementalDexCompiler = IncrementalDexCompiler(
        projectRoot = layout.rootDir,
        variant = layout.resolveVariant(config.appId, config.variant),
        commandRunner = commandRunner,
        gradleConfig = layout.resolvedGradleConfig
    ).apply { snapshotModuleClasses(layout.modules) },
    private val agentInstaller: JvmtiAgentInstaller = JvmtiAgentInstaller(adbManager),
    private val fallbackInstaller: (GradleModule) -> Result<Unit> = { appModule ->
        runFallbackGradleInstall(
            layout.rootDir,
            appModule,
            layout.resolveVariant(config.appId, config.variant),
            commandRunner,
            layout.resolvedGradleConfig
        )
    },
    private val syncRegistryDir: File = HotReloadSyncCoordinator.defaultRegistryDir()
) {
    private val isRunning = AtomicBoolean(true)
    private var lastReportedCrashBlock: String? = null
    private var activePid: Int = -1
    private var appWasStopped: Boolean = false
    private var lastIdlePidCheckMs: Long = 0L
    private val sessionModifiedFilesByModule = LinkedHashMap<GradleModule, LinkedHashSet<File>>()
    private val syncMonitor = Object()
    private var isPassInProgress = false
    private var pendingSyncCount = 0
    private var completedSyncGeneration = 0L
    private var lastPassLines: List<String> = emptyList()
    private var lastPassEndMs: Long = 0L

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 180L
        private const val CRASH_LOG_TAIL_LINES = "150"
        private const val RECENT_SYNC_WINDOW_MS = 5_000L
        private const val SYNC_WAIT_SLICE_MS = 200L
        private const val PID_CHECK_INTERVAL_MS = 1_000L
        private const val COMPOSE_ERROR_PREFIX = "Error was captured in composition"
        private val stackFrameRegex = Regex("""^at\s+([\w.$]+)\.[\w$<>]+\(([^():]+):(\d+)\)$""")
        private val frameworkPrefixes = listOf(
            "androidx.",
            "android.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "dalvik.",
            "com.android.",
            "sun."
        )

        private fun isIncompleteStaticFallback(layout: GradleProjectLayout): Boolean {
            if (layout.scanDiagnostic == null) return false
            val hasAppModule = layout.modules.any { it.type == ModuleType.ANDROID_APP }
            if (!hasAppModule) return true
            if (layout.modules.size <= 1) {
                val settingsFile = File(layout.rootDir, "settings.gradle.kts").takeIf { it.isFile }
                    ?: File(layout.rootDir, "settings.gradle").takeIf { it.isFile }
                val text = settingsFile?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
                if (text.contains("includeBuild") || Regex("""\binclude\b""").findAll(text).count() > 1) {
                    return true
                }
            }
            return false
        }

        private fun runFallbackGradleInstall(
            rootDir: File,
            appModule: GradleModule,
            variant: String,
            commandRunner: CommandRunner,
            gradleConfig: GradleExecutionConfig = GradleExecutionConfig()
        ): Result<Unit> {
            val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
            val wrapperName = if (isWin) "gradlew.bat" else "gradlew"
            val wrapper = File(rootDir, wrapperName).takeIf { it.isFile }
                ?: appModule.includedBuildRoot?.let { File(it, wrapperName).takeIf { f -> f.isFile } }
            val gradleBin = wrapper?.absolutePath ?: "gradle"
            val capVariant = variant.replaceFirstChar { it.uppercaseChar() }
            val prefix = if (appModule.gradlePath == ":") "" else appModule.gradlePath
            val task = "$prefix:install$capVariant"
            val cmd = listOf(gradleBin, task, "--console=plain", "-p", rootDir.absolutePath) +
                gradleConfig.buildJvmAndExtraFlags()
            val env = gradleConfig.buildEnvironment()
            val primaryRes = if (env.isEmpty()) {
                commandRunner.runCommand(cmd, INSTALL_TIMEOUT_SECONDS)
            } else {
                commandRunner.runCommand(cmd, INSTALL_TIMEOUT_SECONDS, env)
            }
            if (primaryRes.isSuccess || appModule.includedBuildRoot == null) {
                return primaryRes.map { }
            }
            // Fallback for nested composite builds where appModule lives inside an includedBuildRoot
            val localPrefix = if (appModule.localGradlePath == ":") "" else appModule.localGradlePath
            val localCmd = listOf(
                gradleBin,
                "$localPrefix:install$capVariant",
                "--console=plain",
                "-p",
                appModule.includedBuildRoot.absolutePath
            ) + gradleConfig.buildJvmAndExtraFlags()
            return if (env.isEmpty()) {
                commandRunner.runCommand(localCmd, INSTALL_TIMEOUT_SECONDS).map { }
            } else {
                commandRunner.runCommand(localCmd, INSTALL_TIMEOUT_SECONDS, env).map { }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        tracker.wakeUp()
        synchronized(syncMonitor) {
            syncMonitor.notifyAll()
        }
    }

    fun run(onEvent: (HotReloadEvent) -> Unit) {
        if (config.javaHome != null && GradleJvmResolver.normalizeJdkHome(config.javaHome) == null) {
            onEvent(
                HotReloadEvent.Error(
                    code = "INVALID_JAVA_HOME",
                    message = "Invalid --java-home directory: ${config.javaHome.path} (no bin/java executable found)"
                )
            )
            return
        }

        if (isIncompleteStaticFallback(layout)) {
            onEvent(
                HotReloadEvent.Error(
                    code = "PROJECT_SCAN_FAILED",
                    message = "Gradle project discovery failed (${layout.scanDiagnostic}). " +
                        "Pass --java-home or --gradle-args if required by this project."
                )
            )
            return
        }
        val debuggableRes = adbManager.isAppDebuggable(config.appId)
        if (debuggableRes.isFailure || debuggableRes.getOrNull() == false) {
            onEvent(
                HotReloadEvent.Error(
                    code = "APP_NOT_DEBUGGABLE",
                    message = debuggableRes.exceptionOrNull()?.message
                        ?: "App ${config.appId} is not debuggable"
                )
            )
            return
        }

        val initialPidRes = adbManager.findPid(config.appId)
        if (initialPidRes.isFailure) {
            onEvent(
                HotReloadEvent.Error(
                    code = "APP_NOT_RUNNING",
                    message = "App ${config.appId} is not currently running"
                )
            )
            return
        }
        val initialPid = initialPidRes.getOrThrow()
        activePid = initialPid
        lastIdlePidCheckMs = System.currentTimeMillis()
        seedExistingCrashBaseline(initialPid)

        onEvent(
            HotReloadEvent.Watching(
                appId = config.appId,
                pid = initialPid,
                moduleCount = layout.modules.size,
                intervalMs = config.intervalMs
            )
        )

        val stagingDir = Files.createTempDirectory("debroid_dex_stage").toFile()
        val syncServer = if (!config.once) {
            HotReloadSyncCoordinator.Server.startOrNull(
                appId = config.appId,
                projectDir = config.projectDir,
                registryDir = syncRegistryDir,
                onSyncRequest = ::awaitNextWatcherSync
            )
        } else {
            null
        }
        try {
            var isFirstPass = true
            while (isRunning.get()) {
                val forcePoll = synchronized(syncMonitor) {
                    val forced = pendingSyncCount > 0
                    pendingSyncCount = 0
                    isPassInProgress = true
                    forced
                }
                val changes = if (isFirstPass || config.once || forcePoll) {
                    isFirstPass = false
                    tracker.pollChanges()
                } else {
                    tracker.awaitChanges(config.intervalMs)
                }
                if (!changes.isEmpty()) {
                    runPassWithCapture(onEvent) { emit ->
                        processDetectedChanges(changes, stagingDir, emit)
                    }
                } else {
                    checkAndHandlePidRestartWhenIdle(forcePoll, stagingDir, onEvent)
                }
                synchronized(syncMonitor) {
                    isPassInProgress = false
                    completedSyncGeneration++
                    syncMonitor.notifyAll()
                }
                if (config.once) {
                    break
                }
            }
        } catch (_: InterruptedException) {
            onEvent(HotReloadEvent.Stopped(config.appId))
        } finally {
            runCatching { syncServer?.close() }
            synchronized(syncMonitor) {
                isPassInProgress = false
                syncMonitor.notifyAll()
            }
            runCatching { tracker.close() }
            runCatching { compiler.close() }
            stagingDir.deleteRecursively()
        }
    }

    private fun awaitNextWatcherSync(): List<String> {
        val targetGen: Long
        synchronized(syncMonitor) {
            targetGen = if (isPassInProgress) completedSyncGeneration + 2L else completedSyncGeneration + 1L
            pendingSyncCount++
        }
        tracker.wakeUp()
        synchronized(syncMonitor) {
            while (isRunning.get() && completedSyncGeneration < targetGen) {
                syncMonitor.wait(SYNC_WAIT_SLICE_MS)
            }
            val recentWindow = System.currentTimeMillis() - lastPassEndMs <= RECENT_SYNC_WINDOW_MS
            val hasUnresolvedFailure = lastPassLines.any {
                it.startsWith("[COMPILE_ERROR]") || it.startsWith("[ERROR]")
            }
            if (lastPassLines.isNotEmpty() && (recentWindow || hasUnresolvedFailure)) {
                return lastPassLines
            }
            return listOf(
                HotReloadEvent.Reloaded(
                    mode = ReloadMode.JVMTI_HOT_SWAP,
                    redefinedCount = 0,
                    newClassesCount = 0,
                    uiRefresh = UiRefreshMode.NONE,
                    durationMs = 0L
                ).formatPlainText()
            )
        }
    }

    private fun runPassWithCapture(
        onEvent: (HotReloadEvent) -> Unit,
        block: ((HotReloadEvent) -> Unit) -> Unit
    ) {
        val passCaptured = mutableListOf<String>()
        block { event ->
            passCaptured.add(event.formatPlainText())
            onEvent(event)
        }
        synchronized(syncMonitor) {
            lastPassLines = passCaptured
            lastPassEndMs = System.currentTimeMillis()
        }
    }

    private fun shouldProbeIdlePid(forceCheck: Boolean): Boolean {
        if (config.once) return false
        if (!forceCheck && sessionModifiedFilesByModule.isEmpty() && !appWasStopped) return false
        val now = System.currentTimeMillis()
        if (!forceCheck && now - lastIdlePidCheckMs < PID_CHECK_INTERVAL_MS) return false
        lastIdlePidCheckMs = now
        return true
    }

    private fun checkAndHandlePidRestartWhenIdle(
        forceCheck: Boolean,
        stagingDir: File,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        if (!shouldProbeIdlePid(forceCheck)) return

        val pidRes = adbManager.findPid(config.appId)
        if (pidRes.isFailure) {
            appWasStopped = true
            return
        }
        val currentPid = pidRes.getOrThrow()
        val wasStopped = appWasStopped
        appWasStopped = false

        val unapplied = if (wasStopped) {
            tracker.clearFailedAttempts()
            tracker.pollChanges()
        } else {
            DetectedChanges(emptyMap(), emptyList())
        }
        if (!unapplied.isEmpty()) {
            runPassWithCapture(onEvent) { emit ->
                processDetectedChanges(unapplied, stagingDir, emit)
            }
            return
        }

        if (currentPid != activePid) {
            onProcessRestarted(currentPid, onEvent)
            if (sessionModifiedFilesByModule.isNotEmpty()) {
                runPassWithCapture(onEvent) { emit ->
                    reapplySessionEdits(currentPid, stagingDir, emit)
                }
            }
        }
    }

    private fun onProcessRestarted(newPid: Int, onEvent: (HotReloadEvent) -> Unit) {
        activePid = newPid
        runCatching { agentInstaller.invalidateInstalledAgent(config.appId) }
        seedExistingCrashBaseline(newPid)
        onEvent(
            HotReloadEvent.Watching(
                appId = config.appId,
                pid = newPid,
                moduleCount = layout.modules.size,
                intervalMs = config.intervalMs
            )
        )
    }

    private fun reapplySessionEdits(
        pid: Int,
        stagingDir: File,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        val editedMap = sessionModifiedFilesByModule.mapValues { it.value.toList() }
        val modulePaths = editedMap.keys.map { it.gradlePath }
        val fileNames = editedMap.values.flatten().map { it.name }
        onEvent(HotReloadEvent.Reloading(modules = modulePaths, files = fileNames))

        val startMs = System.currentTimeMillis()
        when (val outcome = compiler.reapplyFromApkBaseline(editedMap, stagingDir)) {
            is CompilationOutcome.Failure -> {
                onEvent(HotReloadEvent.CompileError(outcome.module, outcome.diagnostic))
            }
            is CompilationOutcome.StructuralIncompatible -> {
                handleFallback(
                    reason = outcome.reason,
                    files = fileNames,
                    changes = DetectedChanges(editedMap, emptyList()),
                    onEvent = onEvent
                )
            }
            is CompilationOutcome.Success -> {
                if (outcome.classPayloads.isEmpty()) {
                    onEvent(
                        HotReloadEvent.Reloaded(
                            mode = ReloadMode.JVMTI_HOT_SWAP,
                            redefinedCount = 0,
                            newClassesCount = 0,
                            uiRefresh = UiRefreshMode.NONE,
                            durationMs = System.currentTimeMillis() - startMs
                        )
                    )
                    return
                }
                applyDexToDevice(
                    compileOutcome = outcome,
                    changes = DetectedChanges(emptyMap(), emptyList()),
                    fileNames = fileNames,
                    pid = pid,
                    startMs = startMs,
                    onEvent = onEvent
                )
            }
        }
    }

    private fun recordSessionModifiedFiles(changes: DetectedChanges) {
        for ((module, files) in changes.codeChangesByModule) {
            sessionModifiedFilesByModule.getOrPut(module) { LinkedHashSet() }.addAll(files)
        }
    }

    private fun mergeWithSessionModifiedFiles(changes: DetectedChanges): Map<GradleModule, List<File>> {
        if (sessionModifiedFilesByModule.isEmpty()) return changes.codeChangesByModule
        val merged = LinkedHashMap<GradleModule, LinkedHashSet<File>>()
        for ((mod, files) in sessionModifiedFilesByModule) {
            merged.getOrPut(mod) { LinkedHashSet() }.addAll(files)
        }
        for ((mod, files) in changes.codeChangesByModule) {
            merged.getOrPut(mod) { LinkedHashSet() }.addAll(files)
        }
        return merged.mapValues { it.value.toList() }
    }

    private fun processDetectedChanges(
        changes: DetectedChanges,
        stagingDir: File,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        if (changes.resourceFiles.isNotEmpty()) {
            handleFallback(
                reason = "RESOURCE_CHANGE",
                files = changes.resourceFiles.map { it.name },
                changes = changes,
                onEvent = onEvent
            )
            return
        }

        val pidRes = adbManager.findPid(config.appId)
        if (pidRes.isFailure) {
            appWasStopped = true
            onEvent(HotReloadEvent.Error("APP_NOT_RUNNING", "App ${config.appId} is not running"))
            return
        }
        val pid = pidRes.getOrThrow()
        appWasStopped = false
        val restartedBeforePass = activePid != -1 && pid != activePid
        if (restartedBeforePass) {
            onProcessRestarted(pid, onEvent)
            compiler.resetToApkBaseline()
        }

        val effectiveCodeChanges = if (restartedBeforePass) {
            mergeWithSessionModifiedFiles(changes)
        } else {
            changes.codeChangesByModule
        }
        val modulePaths = effectiveCodeChanges.keys.map { it.gradlePath }
        val fileNames = effectiveCodeChanges.values.flatten().map { it.name }
        onEvent(HotReloadEvent.Reloading(modules = modulePaths, files = fileNames))

        val startMs = System.currentTimeMillis()
        when (val compileOutcome = compiler.compileAndDex(effectiveCodeChanges, stagingDir)) {
            is CompilationOutcome.Failure -> {
                onEvent(HotReloadEvent.CompileError(compileOutcome.module, compileOutcome.diagnostic))
            }
            is CompilationOutcome.StructuralIncompatible -> {
                handleFallback(
                    reason = compileOutcome.reason,
                    files = fileNames,
                    changes = changes,
                    onEvent = onEvent
                )
            }
            is CompilationOutcome.Success -> {
                if (compileOutcome.classPayloads.isEmpty()) {
                    tracker.commitChanges(changes)
                    recordSessionModifiedFiles(changes)
                    onEvent(
                        HotReloadEvent.Reloaded(
                            mode = ReloadMode.JVMTI_HOT_SWAP,
                            redefinedCount = 0,
                            newClassesCount = 0,
                            uiRefresh = UiRefreshMode.NONE,
                            durationMs = System.currentTimeMillis() - startMs
                        )
                    )
                    return
                }
                applyDexToDevice(compileOutcome, changes, fileNames, pid, startMs, onEvent)
            }
        }
    }

    private fun applyDexToDevice(
        compileOutcome: CompilationOutcome.Success,
        changes: DetectedChanges,
        fileNames: List<String>,
        pid: Int,
        startMs: Long,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        val agentRes = agentInstaller.applyHotReload(
            appId = config.appId,
            classPayloads = compileOutcome.classPayloads,
            combinedDexFile = compileOutcome.combinedDexFile
        )
        val durationMs = System.currentTimeMillis() - startMs

        when (agentRes) {
            is AgentExecutionResult.Success -> {
                if (!changes.isEmpty()) {
                    tracker.commitChanges(changes)
                    recordSessionModifiedFiles(changes)
                }
                onEvent(
                    HotReloadEvent.Reloaded(
                        mode = agentRes.mode,
                        redefinedCount = agentRes.redefinedCount,
                        newClassesCount = agentRes.newClassesCount,
                        uiRefresh = agentRes.uiRefresh,
                        durationMs = durationMs
                    )
                )
                val runtimeError = agentRes.runtimeError ?: checkUncaughtProcessCrash(pid, fileNames.toSet())
                if (runtimeError != null) {
                    onEvent(HotReloadEvent.RuntimeError(runtimeError))
                }
            }
            is AgentExecutionResult.StructuralIncompatible -> {
                handleFallback(
                    reason = agentRes.reason,
                    files = fileNames,
                    changes = changes,
                    onEvent = onEvent
                )
            }
            is AgentExecutionResult.Error -> {
                val crashError = checkUncaughtProcessCrash(pid, fileNames.toSet())
                if (crashError != null) {
                    tracker.commitChanges(changes)
                    onEvent(HotReloadEvent.RuntimeError(crashError))
                } else {
                    onEvent(HotReloadEvent.Error(agentRes.code, agentRes.message))
                }
            }
        }
    }

    private fun seedExistingCrashBaseline(pid: Int) {
        lastReportedCrashBlock = extractLatestCrashBlockForPid(readCrashLogTail(), pid)
    }

    private fun checkUncaughtProcessCrash(pid: Int, changedFileNames: Set<String>): String? {
        return parseCrashLogForPid(readCrashLogTail(), pid, changedFileNames)
    }

    private fun readCrashLogTail(): String = runCatching {
        val rawResult: Any? = adbManager.runShell("logcat", "-b", "main,crash", "-d", "-t", CRASH_LOG_TAIL_LINES)
            .getOrNull()
        rawResult as? String ?: ""
    }.getOrDefault("")

    internal fun parseCrashLogForPid(
        logcatOutput: String,
        pid: Int,
        changedFileNames: Set<String>
    ): String? {
        val block = extractLatestCrashBlockForPid(logcatOutput, pid) ?: return null
        if (block == lastReportedCrashBlock) return null
        lastReportedCrashBlock = block

        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sectionLines = splitIntoCausalSections(lines).lastOrNull() ?: lines

        val headerLine = sectionLines.firstOrNull { line ->
            !line.startsWith("FATAL EXCEPTION:") &&
                !line.startsWith(COMPOSE_ERROR_PREFIX) &&
                !line.startsWith("Process:") &&
                !line.startsWith("at ") &&
                !line.startsWith("...")
        }?.removePrefix("Caused by:")?.trim() ?: return null

        val formattedHeader = simplifyExceptionHeader(headerLine)
        val location = selectBestFrameLocation(sectionLines, changedFileNames)
            ?: selectBestFrameLocation(lines, changedFileNames)

        return if (location != null) {
            "$formattedHeader ($location)"
        } else {
            formattedHeader
        }
    }

    private fun extractLatestCrashBlockForPid(logcatOutput: String, pid: Int): String? {
        if (logcatOutput.isBlank()) return null
        return extractLatestComposeErrorBlockForPid(logcatOutput, pid)
            ?: extractLatestAndroidRuntimeBlockForPid(logcatOutput, pid)
    }

    private fun extractLatestComposeErrorBlockForPid(logcatOutput: String, pid: Int): String? {
        val pidRegex = Regex("""\b$pid\b""")
        val composeLines = logcatOutput.lineSequence()
            .mapNotNull { line ->
                val markerIdx = line.indexOf("ComposeInternal:")
                if (markerIdx >= 0) {
                    val prefix = line.substring(0, markerIdx)
                    if (prefix.isBlank() || pidRegex.containsMatchIn(prefix)) {
                        line.substring(markerIdx + "ComposeInternal:".length).trim()
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            .toList()
        if (composeLines.isEmpty()) return null

        val blocks = mutableListOf<MutableList<String>>()
        for (line in composeLines) {
            if (line.startsWith(COMPOSE_ERROR_PREFIX)) {
                blocks.add(mutableListOf(line))
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(line)
            }
        }
        return blocks.lastOrNull()?.joinToString("\n")
    }

    private fun extractLatestAndroidRuntimeBlockForPid(logcatOutput: String, pid: Int): String? {
        val runtimeLines = logcatOutput.lineSequence()
            .mapNotNull { line ->
                val markerIdx = line.indexOf("AndroidRuntime:")
                if (markerIdx >= 0) {
                    line.substring(markerIdx + "AndroidRuntime:".length).trim()
                } else {
                    null
                }
            }
            .toList()
        if (runtimeLines.isEmpty()) return null

        val blocks = mutableListOf<MutableList<String>>()
        for (line in runtimeLines) {
            if (line.startsWith("FATAL EXCEPTION:")) {
                blocks.add(mutableListOf(line))
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(line)
            }
        }

        val pidToken = "PID: $pid"
        return blocks.lastOrNull { block ->
            block.any { it.contains(pidToken) }
        }?.joinToString("\n")
    }

    private fun splitIntoCausalSections(lines: List<String>): List<List<String>> {
        val sections = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (line.startsWith("Caused by:") || sections.isEmpty()) {
                sections.add(mutableListOf(line))
            } else {
                sections.last().add(line)
            }
        }
        return sections
    }

    private fun simplifyExceptionHeader(headerLine: String): String {
        val colonIdx = headerLine.indexOf(':')
        val fqcn = if (colonIdx > 0) headerLine.substring(0, colonIdx).trim() else headerLine.trim()
        val message = if (colonIdx > 0) headerLine.substring(colonIdx + 1).trim() else ""
        val simpleName = fqcn.substringAfterLast('.').substringAfterLast('$').ifEmpty { fqcn }
        return if (message.isNotEmpty()) "$simpleName: $message" else simpleName
    }

    private fun selectBestFrameLocation(lines: List<String>, changedFileNames: Set<String>): String? {
        val parsedFrames = lines.mapNotNull { line ->
            val match = stackFrameRegex.matchEntire(line) ?: return@mapNotNull null
            val className = match.groupValues[1]
            val fileName = match.groupValues[2]
            val lineNum = match.groupValues[3]
            Triple(className, fileName, "$fileName:$lineNum")
        }
        if (parsedFrames.isEmpty()) return null

        val changedMatch = parsedFrames.firstOrNull { (_, fileName, _) -> fileName in changedFileNames }
        if (changedMatch != null) return changedMatch.third

        val appMatch = parsedFrames.firstOrNull { (className, _, _) ->
            frameworkPrefixes.none { className.startsWith(it) }
        }
        return (appMatch ?: parsedFrames.first()).third
    }

    private fun handleFallback(
        reason: String,
        files: List<String>,
        changes: DetectedChanges,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        if (!config.allowFallback) {
            onEvent(HotReloadEvent.Error("FALLBACK_DISABLED", "Cannot hot-swap $reason and --no-fallback is set"))
            return
        }
        val appModule = layout.findAppModule(config.appId)
        if (appModule == null) {
            onEvent(HotReloadEvent.Error("NO_APP_MODULE", "Could not locate Android application module for fallback"))
            return
        }

        val startMs = System.currentTimeMillis()
        val installRes = fallbackInstaller(appModule)
        if (installRes.isFailure) {
            onEvent(
                HotReloadEvent.Error(
                    "FALLBACK_FAILED",
                    installRes.exceptionOrNull()?.message ?: "Fallback install failed"
                )
            )
            return
        }

        adbManager.restartApp(config.appId)
        runCatching { agentInstaller.invalidateInstalledAgent(config.appId) }
        sessionModifiedFilesByModule.clear()
        appWasStopped = false
        adbManager.findPid(config.appId).getOrNull()?.let { newPid ->
            activePid = newPid
            seedExistingCrashBaseline(newPid)
        }
        compiler.snapshotModuleClasses(layout.modules)
        tracker.commitChanges(changes)
        val durationMs = System.currentTimeMillis() - startMs
        onEvent(HotReloadEvent.FallbackReinstall(reason = reason, files = files, durationMs = durationMs))
    }
}
