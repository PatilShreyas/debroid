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
    val allowFallback: Boolean = true
)

@Suppress("LongParameterList")
class HotReloadSession(
    private val config: HotReloadConfig,
    private val adbManager: AdbManager = AdbManager(),
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val layout: GradleProjectLayout = GradleProjectScanner.scan(config.projectDir),
    private val tracker: SourceChangeTracker = SourceChangeTracker(layout).apply { initializeBaseline() },
    private val compiler: IncrementalDexCompiler = IncrementalDexCompiler(
        projectRoot = layout.rootDir,
        variant = layout.resolveVariant(config.appId, config.variant),
        commandRunner = commandRunner
    ).apply { snapshotModuleClasses(layout.modules) },
    private val agentInstaller: JvmtiAgentInstaller = JvmtiAgentInstaller(adbManager),
    private val fallbackInstaller: (GradleModule) -> Result<Unit> = { appModule ->
        runFallbackGradleInstall(
            layout.rootDir,
            appModule,
            layout.resolveVariant(config.appId, config.variant),
            commandRunner
        )
    }
) {
    private val isRunning = AtomicBoolean(true)

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 180L

        private fun runFallbackGradleInstall(
            rootDir: File,
            appModule: GradleModule,
            variant: String,
            commandRunner: CommandRunner
        ): Result<Unit> {
            val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
            val wrapper = File(rootDir, if (isWin) "gradlew.bat" else "gradlew")
            val gradleBin = if (wrapper.exists()) wrapper.absolutePath else "gradle"
            val capVariant = variant.replaceFirstChar { it.uppercaseChar() }
            val task = "${appModule.gradlePath}:install$capVariant"
            return commandRunner.runCommand(
                listOf(gradleBin, task, "--console=plain", "-p", rootDir.absolutePath),
                INSTALL_TIMEOUT_SECONDS
            ).map { }
        }
    }

    fun stop() {
        isRunning.set(false)
    }

    fun run(onEvent: (HotReloadEvent) -> Unit) {
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

        onEvent(
            HotReloadEvent.Watching(
                appId = config.appId,
                pid = initialPidRes.getOrThrow(),
                moduleCount = layout.modules.size,
                intervalMs = config.intervalMs
            )
        )

        val stagingDir = Files.createTempDirectory("debroid_dex_stage").toFile()
        try {
            while (isRunning.get()) {
                val changes = tracker.pollChanges()
                if (!changes.isEmpty()) {
                    processDetectedChanges(changes, stagingDir, onEvent)
                }
                if (config.once) {
                    break
                }
                Thread.sleep(config.intervalMs)
            }
        } catch (_: InterruptedException) {
            onEvent(HotReloadEvent.Stopped(config.appId))
        } finally {
            stagingDir.deleteRecursively()
        }
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
            onEvent(HotReloadEvent.Error("APP_NOT_RUNNING", "App ${config.appId} is not running"))
            return
        }

        val modulePaths = changes.codeChangesByModule.keys.map { it.gradlePath }
        val fileNames = changes.codeChangesByModule.values.flatten().map { it.name }
        onEvent(HotReloadEvent.Reloading(modules = modulePaths, files = fileNames))

        val startMs = System.currentTimeMillis()
        when (val compileOutcome = compiler.compileAndDex(changes.codeChangesByModule, stagingDir)) {
            is CompilationOutcome.Failure -> {
                onEvent(HotReloadEvent.CompileError(compileOutcome.module, compileOutcome.diagnostic))
            }
            is CompilationOutcome.Success -> {
                if (compileOutcome.classPayloads.isEmpty()) {
                    tracker.commitChanges(changes)
                    return
                }
                applyDexToDevice(compileOutcome, changes, fileNames, startMs, onEvent)
            }
        }
    }

    private fun applyDexToDevice(
        compileOutcome: CompilationOutcome.Success,
        changes: DetectedChanges,
        fileNames: List<String>,
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
                tracker.commitChanges(changes)
                onEvent(
                    HotReloadEvent.Reloaded(
                        mode = agentRes.mode,
                        redefinedCount = agentRes.redefinedCount,
                        newClassesCount = agentRes.newClassesCount,
                        uiRefresh = agentRes.uiRefresh,
                        durationMs = durationMs
                    )
                )
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
                onEvent(HotReloadEvent.Error(agentRes.code, agentRes.message))
            }
        }
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
        compiler.snapshotModuleClasses(layout.modules)
        tracker.commitChanges(changes)
        val durationMs = System.currentTimeMillis() - startMs
        onEvent(HotReloadEvent.FallbackReinstall(reason = reason, files = files, durationMs = durationMs))
    }
}
