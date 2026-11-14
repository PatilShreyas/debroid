package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Adler32
import java.util.zip.ZipFile

@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class IncrementalDexCompiler(
    private val projectRoot: File,
    private val variant: String = "debug",
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val d8PathProvider: () -> String = { findD8Executable() },
    gradleConfig: GradleExecutionConfig = GradleExecutionConfig(),
    private val deviceApkFallbackProvider: (() -> File?)? = null
) {
    private var activeGradleConfig: GradleExecutionConfig = gradleConfig
    private data class ClassFileMeta(
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    private val classMetaByModule = mutableMapOf<String, MutableMap<String, ClassFileMeta>>()
    private val classBytesByModule = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val apkBaselineMetaByModule = mutableMapOf<String, Map<String, ClassFileMeta>>()
    private val apkBaselineBytesByModule = mutableMapOf<String, Map<String, ByteArray>>()
    private val dexBytesByModule = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val baselineDexFilesByModule = mutableMapOf<String, Map<String, File>>()
    private val baselineDexMembersByClassName = mutableMapOf<String, AdditiveClassMerger.DexClassMembers>()
    private val appExtDexFiles = mutableListOf<File>()
    private val localApkFiles = mutableListOf<File>()
    private val negativeBaselineLookups = mutableSetOf<String>()
    private var deviceBaseApkIndexed = false
    private val moduleDirsByPath = mutableMapOf<String, File>()
    private val knownModulesByPath = LinkedHashMap<String, GradleModule>()
    private val cumulativeStubsByModule =
        mutableMapOf<String, MutableMap<String, AdditiveClassMerger.DexClassMembers>>()
    private val redirectedInterfacesByModule =
        mutableMapOf<String, MutableMap<String, List<Pair<String, String>>>>()
    private val activeClassRenamesByModule =
        mutableMapOf<String, MutableMap<String, String>>()
    private val kspChangeDetector = KspChangeDetector()
    private var desugarD8Config: CoreLibraryDesugarResolver.DesugarD8Config? = null
    private var appMergedDexIndexed = false
    private var reloadGeneration = 0
    private var classRenameCounter = 0

    companion object {
        private const val COMPILE_TIMEOUT_SECONDS = 120L
        private const val D8_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_MIN_API = 26
        private const val MIN_VERBOSE_SYNTHETIC_D8_MAJOR = 37
        private const val DEX_HEADER_MIN_BYTES = 32
        private const val DEX_CHECKSUM_OFFSET = 8
        private const val DEX_SIGNATURE_OFFSET = 12
        private const val DEX_SIGNATURE_LENGTH = 20
        private const val TOKEN_SHIFT_BITS = 8
        private const val POSITIVE_LONG_MASK = 0x7FFFFFFFL
        private const val BASE36_RADIX = 36
        private const val MARKER_TOKEN_LENGTH = 5
        private const val DEFAULT_SYNTHETIC_MARKER = "\$\$ExternalSyntheticLambda"
        private const val DEFAULT_BACKPORT_MARKER = "\$\$ExternalSyntheticBackport"
        private const val R8_SYNTHESIZED_ANNOTATION = "Lcom/android/tools/r8/annotations/SynthesizedClass"
        private const val R8_SYNTHESIZED_NEUTRALIZED = "Lcom/android/tools/r8/annotations/SynthesizedClast"
        private val kotlincErrorRegex = Regex("""^\s*e:\s*(?:file://)?([^\s:]+):(\d+):(\d+)\s*(.+)""")
        private val taskNotFoundRegex = Regex(
            """Task\s+'([^']+)'\s+not found in (?:project|root project)\s+'([^']+)'""",
            RegexOption.IGNORE_CASE
        )
        private val candidateTaskRegex = Regex("""'((?:compile)[A-Za-z0-9_]*)'""")

        fun findD8Executable(): String {
            val sdkRoot = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.exists() }?.absolutePath
                ?: File(System.getProperty("user.home"), "Android/Sdk").takeIf { it.exists() }?.absolutePath

            if (sdkRoot != null) {
                val buildToolsDir = File(sdkRoot, "build-tools")
                val latestD8 = buildToolsDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.firstNotNullOfOrNull { dir ->
                        val isWin = System.getProperty("os.name").lowercase().contains("win")
                        val d8File = File(dir, if (isWin) "d8.bat" else "d8")
                        d8File.takeIf { it.exists() }
                    }
                if (latestD8 != null) return latestD8.absolutePath
            }
            return "d8"
        }
    }

    fun close() {
        if (commandRunner is DefaultCommandRunner) {
            InProcessGradleRunner.closeProject(projectRoot)
        }
    }

    fun snapshotModuleClasses(modules: Collection<GradleModule>) {
        classMetaByModule.clear()
        classBytesByModule.clear()
        apkBaselineMetaByModule.clear()
        apkBaselineBytesByModule.clear()
        dexBytesByModule.clear()
        baselineDexFilesByModule.clear()
        baselineDexMembersByClassName.clear()
        appExtDexFiles.clear()
        localApkFiles.clear()
        negativeBaselineLookups.clear()
        deviceBaseApkIndexed = false
        cumulativeStubsByModule.clear()
        knownModulesByPath.clear()
        redirectedInterfacesByModule.clear()
        activeClassRenamesByModule.clear()
        appMergedDexIndexed = false

        for (module in modules) {
            knownModulesByPath[module.gradlePath] = module
            moduleDirsByPath[module.gradlePath] = module.moduleDir
        }
        if (activeGradleConfig.javaHome == null) {
            activeGradleConfig = GradleJvmResolver.resolveForProject(
                projectRoot = projectRoot,
                explicitJavaHome = null,
                extraGradleArgs = activeGradleConfig.extraGradleArgs
            )
        }
        desugarD8Config = CoreLibraryDesugarResolver.resolve(projectRoot, modules, ::resolveModuleVariant)
        kspChangeDetector.snapshotModules(modules, ::resolveModuleVariant)

        val d8Path = d8PathProvider()
        InProcessD8Runner.warmUpAsync(d8Path, d8BaseFlags(d8Path))
        if (commandRunner is DefaultCommandRunner) {
            InProcessGradleRunner.warmUpAsync(
                projectRoot = projectRoot,
                javaHome = activeGradleConfig.javaHome,
                initialArgs = listOf("--console=plain") + activeGradleConfig.buildJvmAndExtraFlags()
            )
        }
    }

    private fun snapshotSingleModule(module: GradleModule) {
        knownModulesByPath[module.gradlePath] = module
        moduleDirsByPath[module.gradlePath] = module.moduleDir
        val modVariant = resolveModuleVariant(module)
        val files = scanModuleClassFiles(module)
        val metaMap = mutableMapOf<String, ClassFileMeta>()
        val bytesMap = mutableMapOf<String, ByteArray>()
        for ((className, file) in files) {
            val bytes = file.readBytes()
            metaMap[className] = ClassFileMeta(
                lastModified = file.lastModified(),
                length = bytes.size.toLong(),
                sha256 = sha256(bytes)
            )
            bytesMap[className] = bytes
        }
        classMetaByModule[module.gradlePath] = metaMap
        classBytesByModule[module.gradlePath] = bytesMap
        if (module.gradlePath !in apkBaselineMetaByModule) {
            apkBaselineMetaByModule[module.gradlePath] = metaMap.toMap()
            apkBaselineBytesByModule[module.gradlePath] = bytesMap.toMap()
        }

        val rawDexFiles = scanModuleDexFiles(module)
        baselineDexFilesByModule[module.gradlePath] = rawDexFiles

        val persistedDexMap = mutableMapOf<String, ByteArray>()
        val baselineTimestamp = rawDexFiles.values.maxOfOrNull { it.lastModified() } ?: 0L
        val persistedDir = File(module.moduleDir, "build/tmp/debroid-hotreload/$modVariant")
        if (persistedDir.isDirectory) {
            persistedDir.walkTopDown()
                .filter { it.isFile && it.extension == "dex" }
                .forEach { dexFile ->
                    if (dexFile.lastModified() >= baselineTimestamp) {
                        val className = dexFile.relativeTo(persistedDir)
                            .invariantSeparatorsPath
                            .removeSuffix(".dex")
                            .replace('/', '.')
                        val bytes = dexFile.readBytes()
                        persistedDexMap[className] = bytes
                        AdditiveClassMerger.parseDexMembers(bytes, className)?.let { parsed ->
                            recordCumulativeStubs(module.gradlePath, className, parsed)
                        }
                    }
                }
        }
        dexBytesByModule[module.gradlePath] = persistedDexMap
    }

    fun resetToApkBaseline() {
        classMetaByModule.clear()
        for ((mod, meta) in apkBaselineMetaByModule) {
            classMetaByModule[mod] = meta.toMutableMap()
        }
        classBytesByModule.clear()
        for ((mod, bytes) in apkBaselineBytesByModule) {
            classBytesByModule[mod] = bytes.toMutableMap()
        }
        dexBytesByModule.clear()
        cumulativeStubsByModule.clear()
        redirectedInterfacesByModule.clear()
        activeClassRenamesByModule.clear()
        for ((_, moduleDir) in moduleDirsByPath) {
            val modVariant = resolveVariantForDir(moduleDir)
            File(moduleDir, "build/tmp/debroid-hotreload/$modVariant").deleteRecursively()
        }
    }

    fun reapplyFromApkBaseline(
        editedModulesAndFiles: Map<GradleModule, List<File>>,
        stagingDir: File
    ): CompilationOutcome {
        for (module in editedModulesAndFiles.keys) {
            if (module.gradlePath !in apkBaselineMetaByModule) {
                snapshotSingleModule(module)
            }
        }
        resetToApkBaseline()

        val allTargetModules = LinkedHashMap<GradleModule, List<File>>()
        allTargetModules.putAll(editedModulesAndFiles)
        for (modulePath in apkBaselineMetaByModule.keys) {
            val mod = knownModulesByPath[modulePath] ?: continue
            if (mod !in allTargetModules) {
                allTargetModules[mod] = emptyList()
            }
        }

        val collected = when (val res = collectModifiedClasses(allTargetModules)) {
            is ClassCollectionResult.Incompatible -> return CompilationOutcome.StructuralIncompatible(res.reason)
            is ClassCollectionResult.Collected -> res.entries
        }
        if (collected.isEmpty()) {
            return CompilationOutcome.Success(emptyList(), null)
        }
        return packageDexPayloads(collected, stagingDir)
    }

    private fun ensureAppMergedDexIndexed() {
        if (appMergedDexIndexed) return
        appMergedDexIndexed = true
        for (module in knownModulesByPath.values) {
            if (module.type == ModuleType.ANDROID_APP) {
                indexAppMergedDexFiles(module, resolveModuleVariant(module))
            }
        }
    }

    private fun indexAppMergedDexFiles(appModule: GradleModule, modVariant: String) {
        val apkRoots = listOf(
            File(appModule.moduleDir, "build/outputs/apk"),
            File(appModule.moduleDir, "build/intermediates/apk")
        )
        for (apkRoot in apkRoots) {
            if (!apkRoot.isDirectory) continue
            apkRoot.walkTopDown()
                .filter { it.isFile && it.extension == "apk" }
                .sortedByDescending { it.lastModified() }
                .forEach { localApkFiles.add(it) }
        }

        val dexRoot = File(appModule.moduleDir, "build/intermediates/dex/$modVariant")
        if (!dexRoot.isDirectory) return
        val allDirs = dexRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in allDirs) {
            val isExtDex = dir.name.startsWith("mergeExtDex")
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "dex" }
                .forEach { dexFile ->
                    if (isExtDex) {
                        appExtDexFiles.add(dexFile)
                    } else {
                        val parsedMap = runCatching {
                            AdditiveClassMerger.parseAllDexClasses(dexFile.readBytes())
                        }.getOrNull().orEmpty()
                        for ((className, members) in parsedMap) {
                            val existing = baselineDexMembersByClassName[className]
                            baselineDexMembersByClassName[className] = if (existing == null) {
                                members
                            } else {
                                mergeDexMembers(existing, members)
                            }
                        }
                    }
                }
        }
    }

    private fun lookupInstalledDexMembers(className: String): AdditiveClassMerger.DexClassMembers? {
        ensureAppMergedDexIndexed()
        baselineDexMembersByClassName[className]?.let { return it }
        if (className in negativeBaselineLookups) return null

        val found = appExtDexFiles.firstNotNullOfOrNull { extDex ->
            runCatching { AdditiveClassMerger.parseSingleDexClass(extDex.readBytes(), className) }.getOrNull()
        } ?: localApkFiles.firstNotNullOfOrNull { apkFile ->
            searchSingleClassInApkZip(apkFile, className)
        } ?: lookupFromDeviceBaseApkIfNeeded(className)

        if (found != null) {
            baselineDexMembersByClassName[className] = found
        } else {
            negativeBaselineLookups.add(className)
        }
        return found
    }

    private fun lookupFromDeviceBaseApkIfNeeded(className: String): AdditiveClassMerger.DexClassMembers? {
        if (deviceBaseApkIndexed || deviceApkFallbackProvider == null) return null
        deviceBaseApkIndexed = true
        val pulledApk = runCatching { deviceApkFallbackProvider?.invoke() }.getOrNull()
        if (pulledApk != null && pulledApk.isFile) {
            indexAllDexClassesFromApkZip(pulledApk)
        }
        return baselineDexMembersByClassName[className]
    }

    private fun searchSingleClassInApkZip(apkFile: File, className: String): AdditiveClassMerger.DexClassMembers? =
        runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                        val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                        AdditiveClassMerger.parseSingleDexClass(dexBytes, className)?.let { return@runCatching it }
                    }
                }
                null
            }
        }.getOrNull()

    private fun indexAllDexClassesFromApkZip(apkFile: File) {
        runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                        val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                        val parsedMap = AdditiveClassMerger.parseAllDexClasses(dexBytes)
                        for ((clsName, members) in parsedMap) {
                            val existing = baselineDexMembersByClassName[clsName]
                            baselineDexMembersByClassName[clsName] = if (existing == null) {
                                members
                            } else {
                                mergeDexMembers(existing, members)
                            }
                        }
                    }
                }
            }
        }
    }

    fun buildGradleTasksForModules(changesByModule: Map<GradleModule, List<File>>): List<String> {
        val tasks = mutableListOf<String>()

        for ((module, files) in changesByModule) {
            val modVariant = resolveModuleVariant(module)
            val capVariant = modVariant.replaceFirstChar { it.uppercaseChar() }
            val hasJava = files.any { it.extension.equals("java", ignoreCase = true) }
            val kotlinTask = resolveKotlinCompileTask(module, modVariant, capVariant)
            val prefix = if (module.gradlePath == ":") "" else module.gradlePath
            tasks.add("$prefix:$kotlinTask")
            if (hasJava) {
                val javaTask = if (kotlinTask == "compileKotlin") {
                    "compileJava"
                } else {
                    "compile${capVariant}JavaWithJavac"
                }
                tasks.add("$prefix:$javaTask")
            }
        }
        return tasks.distinct()
    }

    private fun resolveKotlinCompileTask(
        module: GradleModule,
        modVariant: String,
        capVariant: String
    ): String {
        val dir = module.moduleDir
        if (File(dir, "build/classes/kotlin/android/main").isDirectory) {
            return "compileAndroidMain"
        }
        if (File(dir, "build/classes/kotlin/android/$modVariant").isDirectory) {
            return "compile${capVariant}KotlinAndroid"
        }
        return when (module.type) {
            ModuleType.ANDROID_APP, ModuleType.ANDROID_LIBRARY -> "compile${capVariant}Kotlin"
            ModuleType.KOTLIN_JVM -> {
                if (hasAndroidVariantIntermediates(dir, modVariant)) {
                    "compile${capVariant}Kotlin"
                } else {
                    "compileKotlin"
                }
            }
        }
    }

    private fun hasAndroidVariantIntermediates(moduleDir: File, modVariant: String): Boolean {
        return File(moduleDir, "build/intermediates/built_in_kotlinc/$modVariant").isDirectory ||
            File(moduleDir, "build/tmp/kotlin-classes/$modVariant").isDirectory ||
            File(moduleDir, "build/intermediates/kotlin-classes/$modVariant").isDirectory
    }

    fun compileAndDex(
        changesByModule: Map<GradleModule, List<File>>,
        stagingDir: File
    ): CompilationOutcome {
        for (module in changesByModule.keys) {
            if (module.gradlePath !in classMetaByModule) {
                snapshotSingleModule(module)
            }
        }

        val tasks = buildGradleTasksForModules(changesByModule)
        val gradlew = resolveGradleWrapper()
        val gradleFlags = listOf("--console=plain", "-p", projectRoot.absolutePath) +
            activeGradleConfig.buildJvmAndExtraFlags()
        val kspFlags = kspChangeDetector.buildKspExclusionFlags(changesByModule, ::resolveModuleVariant)

        var compileResult = executeGradleCompile(gradlew, tasks, gradleFlags + kspFlags, changesByModule)
        if (compileResult.isFailure && kspFlags.isNotEmpty()) {
            val errText = compileResult.exceptionOrNull()?.message.orEmpty()
            if (shouldRetryWithoutKspSkip(errText)) {
                val refreshedFlags = listOf("--console=plain", "-p", projectRoot.absolutePath) +
                    activeGradleConfig.buildJvmAndExtraFlags()
                compileResult = executeGradleCompile(gradlew, tasks, refreshedFlags, changesByModule)
            }
        }
        if (compileResult.isFailure) {
            val rawError = compileResult.exceptionOrNull()?.message.orEmpty()
            val firstModule = changesByModule.keys.firstOrNull()?.gradlePath ?: ":app"
            return CompilationOutcome.Failure(
                module = firstModule,
                diagnostic = extractDiagnostic(rawError)
            )
        }
        kspChangeDetector.recordSuccessfulCompile(changesByModule.values.flatten())

        val primaryClasses = when (val res = collectModifiedClasses(changesByModule)) {
            is ClassCollectionResult.Incompatible -> return CompilationOutcome.StructuralIncompatible(res.reason)
            is ClassCollectionResult.Collected -> res.entries
        }
        val combinedClasses = LinkedHashMap<String, ModifiedClassEntry>()
        for (entry in primaryClasses) {
            combinedClasses[entry.className] = entry
        }

        val crossModuleChangedModules = primaryClasses.asSequence()
            .filter { it.hasCrossModuleChange }
            .mapNotNull { knownModulesByPath[it.modulePath] }
            .toSet()

        if (crossModuleChangedModules.isNotEmpty() && knownModulesByPath.size > 1) {
            val dependentModules = GradleProjectLayout.findTransitiveDependents(
                changedModules = crossModuleChangedModules,
                allModules = knownModulesByPath.values
            ).filter { it !in changesByModule.keys }

            if (dependentModules.isNotEmpty()) {
                for (depModule in dependentModules) {
                    if (depModule.gradlePath !in classMetaByModule) {
                        snapshotSingleModule(depModule)
                    }
                }
                val activeDependents = dependentModules.filter { dep ->
                    classMetaByModule[dep.gradlePath]?.isNotEmpty() == true
                }
                if (activeDependents.isNotEmpty()) {
                    val depChanges = activeDependents.associateWith { emptyList<File>() }
                    val depTasks = buildGradleTasksForModules(depChanges)
                    val depKspFlags = activeDependents.mapNotNull { dep ->
                        val modVar = resolveModuleVariant(dep)
                        val capVar = modVar.replaceFirstChar { it.uppercaseChar() }
                        val kspDir = File(dep.moduleDir, "build/generated/ksp/$modVar")
                        if (kspDir.isDirectory) listOf("-x", "${dep.gradlePath}:ksp${capVar}Kotlin") else null
                    }.flatten()
                    val depFlags = listOf("--console=plain", "-p", projectRoot.absolutePath) +
                        activeGradleConfig.buildJvmAndExtraFlags() + depKspFlags
                    val depResult = executeGradleCompile(gradlew, depTasks, depFlags, depChanges)
                    if (depResult.isSuccess) {
                        val depCollected = when (val depRes = collectModifiedClasses(depChanges)) {
                            is ClassCollectionResult.Incompatible -> {
                                return CompilationOutcome.StructuralIncompatible(depRes.reason)
                            }
                            is ClassCollectionResult.Collected -> depRes.entries
                        }
                        for (depEntry in depCollected) {
                            combinedClasses[depEntry.className] = depEntry
                        }
                    }
                }
            }
        }

        val modifiedClassFiles = combinedClasses.values.toList()
        if (modifiedClassFiles.isEmpty()) {
            return CompilationOutcome.Success(emptyList(), null)
        }

        return packageDexPayloads(modifiedClassFiles, stagingDir)
    }

    private fun executeGradleCompile(
        gradlew: String,
        tasks: List<String>,
        gradleFlags: List<String>,
        changesByModule: Map<GradleModule, List<File>> = emptyMap()
    ): Result<String> {
        var compileResult = runGradleTasks(projectRoot, gradlew, tasks, gradleFlags)
        if (compileResult.isFailure) {
            val firstErr = compileResult.exceptionOrNull()?.message.orEmpty()
            val fallbackJdk = GradleJvmResolver.findFallbackJavaHomeForError(firstErr, activeGradleConfig.javaHome)
            if (fallbackJdk != null) {
                activeGradleConfig = activeGradleConfig.copy(javaHome = fallbackJdk)
                val updatedFlags = gradleFlags.filterNot { it.startsWith("-Dorg.gradle.java.home=") } +
                    activeGradleConfig.buildJvmAndExtraFlags().filter { it.startsWith("-Dorg.gradle.java.home=") }
                compileResult = runGradleTasks(projectRoot, gradlew, tasks, updatedFlags)
            }
        }
        if (compileResult.isFailure) {
            val rawError = compileResult.exceptionOrNull()?.message.orEmpty()
            val retryTasks = resolveFallbackTasksOnTaskNotFound(tasks, rawError)
            if (retryTasks != null) {
                compileResult = runGradleTasks(projectRoot, gradlew, retryTasks, gradleFlags)
            } else if (shouldRetryPerIncludedBuild(rawError, changesByModule)) {
                compileResult = compilePerBuildRoot(gradlew, changesByModule)
            }
        }
        return compileResult
    }

    private fun shouldRetryPerIncludedBuild(
        rawError: String,
        changesByModule: Map<GradleModule, List<File>>
    ): Boolean {
        if (changesByModule.keys.none { it.includedBuildRoot != null }) return false
        return rawError.contains("not found in root project", ignoreCase = true) ||
            rawError.contains("not found in project", ignoreCase = true) ||
            rawError.contains("Included build", ignoreCase = true)
    }

    private fun compilePerBuildRoot(
        defaultGradlew: String,
        changesByModule: Map<GradleModule, List<File>>
    ): Result<String> {
        val grouped = changesByModule.entries.groupBy { it.key.includedBuildRoot ?: projectRoot }
        for ((targetRoot, entries) in grouped) {
            val localMap = entries.associate { (mod, files) ->
                val effectivePath = if (targetRoot != projectRoot) mod.localGradlePath else mod.gradlePath
                mod.copy(gradlePath = effectivePath) to files
            }
            val rawTasks = buildGradleTasksForModules(localMap).map { spec ->
                if (spec.startsWith("::")) spec.removePrefix(":") else spec
            }
            val bin = resolveGradleWrapperForDir(targetRoot) ?: defaultGradlew
            val flags = listOf("--console=plain", "-p", targetRoot.absolutePath) +
                activeGradleConfig.buildJvmAndExtraFlags()
            val res = runGradleTasks(targetRoot, bin, rawTasks, flags)
            if (res.isFailure) return res
        }
        return Result.success("")
    }

    private fun runGradleTasks(
        targetProjectRoot: File,
        gradlew: String,
        tasks: List<String>,
        gradleFlags: List<String>
    ): Result<String> {
        if (commandRunner is DefaultCommandRunner) {
            val inProcessRes = InProcessGradleRunner.tryRunTasks(
                projectRoot = targetProjectRoot,
                tasks = tasks,
                gradleFlags = gradleFlags,
                javaHome = activeGradleConfig.javaHome
            )
            if (inProcessRes != null) {
                return inProcessRes
            }
        }
        val env = activeGradleConfig.buildEnvironment()
        val cmd = listOf(gradlew) + tasks + gradleFlags
        return if (env.isEmpty()) {
            commandRunner.runCommand(cmd, COMPILE_TIMEOUT_SECONDS)
        } else {
            commandRunner.runCommand(cmd, COMPILE_TIMEOUT_SECONDS, env)
        }
    }

    private fun shouldRetryWithoutKspSkip(rawError: String): Boolean {
        if (rawError.contains("not found in project", ignoreCase = true)) return true
        val hasDirectKotlincError = rawError.lineSequence().any { kotlincErrorRegex.containsMatchIn(it) }
        if (!hasDirectKotlincError) return true
        return rawError.contains("Hilt_") || rawError.contains("_Factory") || rawError.contains("_Impl")
    }

    internal fun resolveFallbackTasksOnTaskNotFound(
        originalTasks: List<String>,
        rawError: String
    ): List<String>? {
        val match = taskNotFoundRegex.find(rawError) ?: return null
        val missingTask = match.groupValues[1]
        val projectPath = match.groupValues[2]
        val candidatesLine = rawError.lines().firstOrNull { it.contains("Some candidates are:") }.orEmpty()
        val candidateTasks = candidateTaskRegex.findAll(candidatesLine)
            .map { it.groupValues[1] }
            .filter { it != missingTask && (it.contains("Kotlin") || it == "compileAndroidMain") }
            .toList()
        val replacementTask = candidateTasks.firstOrNull { it.endsWith("DebugKotlin") }
            ?: candidateTasks.firstOrNull()
            ?: if (missingTask != "compileDebugKotlin" && missingTask.endsWith("DebugKotlin")) {
                "compileDebugKotlin"
            } else {
                null
            } ?: return null

        val targetSpec = "$projectPath:$missingTask"
        val updated = originalTasks.map { if (it == targetSpec) "$projectPath:$replacementTask" else it }
        return updated.takeIf { it != originalTasks }
    }

    private data class ModifiedClassEntry(
        val modulePath: String,
        val className: String,
        val originalFile: File,
        val previousClassBytes: ByteArray?,
        val unpatchedCurrentBytes: ByteArray,
        val mergedBytes: ByteArray,
        val hasCrossModuleChange: Boolean = false,
        val reinitClinit: Boolean = false,
        val resetScopeKeys: Set<Int> = emptySet(),
        val resetScope: Boolean = false,
        val changedScopeKeys: Set<Int> = emptySet(),
        val invalidateAllClassScopes: Boolean = true,
        val staticFieldUpdates: List<StaticFieldUpdate> = emptyList()
    )

    private data class D8BatchContext(
        val d8Path: String,
        val baseFlags: List<String>,
        val stagingDir: File,
        val mergedInputsDir: File,
        val perClassOutDir: File,
        val syntheticMarker: String,
        val backportMarker: String
    )

    private sealed class ClassCollectionResult {
        data class Collected(val entries: List<ModifiedClassEntry>) : ClassCollectionResult()
        data class Incompatible(val reason: String) : ClassCollectionResult()
    }

    private inner class ModuleCollectState(
        val module: GradleModule,
        val previousMeta: Map<String, ClassFileMeta>,
        val previousBytesMap: Map<String, ByteArray>,
        val currentClasses: Map<String, File>,
        val editedBaseNames: Set<String>,
        val redirectedIfaces: MutableMap<String, List<Pair<String, String>>>
    ) {
        val updatedMeta = mutableMapOf<String, ClassFileMeta>()
        val updatedBytesMap = previousBytesMap.toMutableMap()
        val activeRenames = activeClassRenamesByModule[module.gradlePath]
            ?.let { LinkedHashMap(it) }
            ?: LinkedHashMap()
        val newlyRenamedClasses = LinkedHashMap<String, String>()
        val newlyRedirectedIfaces = LinkedHashMap<String, List<Pair<String, String>>>()
        val synthesizedDefaultImplsByClass = LinkedHashMap<String, Pair<File, ByteArray>>()
        val interfaceStaticFieldUpdates = mutableListOf<StaticFieldUpdate>()
        var anyInterfaceCrossModuleChange = false
        var structuralIncompatibleReason: String? = null
        val modifiedRawBytesByClass = LinkedHashMap<String, Pair<File, ByteArray>>()

        fun resolveClassBytes(internalName: String): ByteArray? {
            val dotName = internalName.replace('/', '.')
            updatedBytesMap[dotName]?.let { return it }
            val file = currentClasses[dotName] ?: return null
            if (!file.exists()) return null
            return file.readBytes().also { updatedBytesMap[dotName] = it }
        }

        fun scanModifiedCandidates() {
            for ((className, classFile) in currentClasses) {
                val prevMeta = previousMeta[className]
                val curLastModified = classFile.lastModified()
                val curLength = classFile.length()

                if (isUnchangedClassFile(prevMeta, curLastModified, curLength)) {
                    updatedMeta[className] = prevMeta!!
                    continue
                }

                val currentBytes = classFile.readBytes()
                val hash = sha256(currentBytes)
                updatedMeta[className] = ClassFileMeta(curLastModified, currentBytes.size.toLong(), hash)

                val simpleName = className.substringAfterLast('.')
                val matchesEditedSource = editedBaseNames.any { base -> matchesEditedSourceName(simpleName, base) }
                val isModified = isClassModified(
                    prevHash = prevMeta?.sha256,
                    currentHash = hash,
                    hasNoPreviousHashes = previousMeta.isEmpty(),
                    matchesEditedSource = matchesEditedSource
                )
                if (!isModified) continue

                if (!tryHandleNonStructuralInterfaceChange(className, simpleName, classFile, currentBytes)) {
                    modifiedRawBytesByClass[className] = classFile to currentBytes
                }
            }
            ensureRedirectedDefaultImplsSynthesized()
        }

        private fun tryHandleNonStructuralInterfaceChange(
            className: String,
            simpleName: String,
            classFile: File,
            currentBytes: ByteArray
        ): Boolean {
            val isKnownInterface = if (ClassBytecodeAnalyzer.isInterfaceClass(currentBytes)) {
                className in previousMeta || lookupInstalledDexMembers(className) != null
            } else {
                false
            }
            if (!isKnownInterface) return false

            val internalIface = className.replace('.', '/')
            val prevBytes = previousBytesMap[className]
            if (prevBytes != null && !ClassBytecodeAnalyzer.hasExecutableBytecodeChange(prevBytes, currentBytes)) {
                updatedBytesMap[className] = currentBytes
                if (internalIface in redirectedIfaces) {
                    recordSynthesizedDefaultImpls(className, simpleName, classFile, currentBytes)
                }
                return true
            }
            if (ClassBytecodeAnalyzer.hasInterfaceStructureChange(prevBytes, currentBytes)) {
                structuralIncompatibleReason = "INTERFACE_STRUCTURE_CHANGED:$simpleName"
                return true
            }

            updatedBytesMap[className] = currentBytes
            if (previousMeta.isNotEmpty() &&
                ClassBytecodeAnalyzer.hasCrossModuleVisibleChange(prevBytes, currentBytes)
            ) {
                anyInterfaceCrossModuleChange = true
            }
            val staticDiff = ClassBytecodeAnalyzer.prepareStaticFieldUpdates(prevBytes, currentBytes)
            interfaceStaticFieldUpdates.addAll(staticDiff.constantFieldUpdates)
            if (recordSynthesizedDefaultImpls(className, simpleName, classFile, currentBytes)) {
                val defaults = ClassBytecodeAnalyzer.extractInterfaceDefaultMethods(currentBytes)
                redirectedIfaces[internalIface] = defaults
                newlyRedirectedIfaces[internalIface] = defaults
            }
            return true
        }

        private fun recordSynthesizedDefaultImpls(
            className: String,
            simpleName: String,
            classFile: File,
            interfaceBytes: ByteArray
        ): Boolean {
            val synthesized = ClassBytecodeAnalyzer.synthesizeDefaultImplsFromInterface(interfaceBytes) ?: return false
            val defaultImplsName = "$className\$DefaultImpls"
            val defaultImplsFile = currentClasses[defaultImplsName]
                ?: File(classFile.parentFile, "$simpleName\$DefaultImpls.class")
            synthesizedDefaultImplsByClass[defaultImplsName] = defaultImplsFile to synthesized
            return true
        }

        private fun ensureRedirectedDefaultImplsSynthesized() {
            for (className in modifiedRawBytesByClass.keys.toList()) {
                if (!className.endsWith("\$DefaultImpls") || className in synthesizedDefaultImplsByClass) continue
                val ifaceName = className.removeSuffix("\$DefaultImpls")
                if (ifaceName.replace('.', '/') !in redirectedIfaces) continue
                val ifaceFile = currentClasses[ifaceName] ?: continue
                val ifaceBytes = updatedBytesMap[ifaceName] ?: ifaceFile.takeIf { it.exists() }?.readBytes() ?: continue
                recordSynthesizedDefaultImpls(ifaceName, ifaceName.substringAfterLast('.'), ifaceFile, ifaceBytes)
            }
        }

        fun collectNewlyRedirectedInterfaceCallers() {
            if (newlyRedirectedIfaces.isEmpty()) return
            for ((className, classFile) in currentClasses) {
                if (className in modifiedRawBytesByClass || className in synthesizedDefaultImplsByClass) {
                    continue
                }
                val rawBytes = classFile.readBytes()
                val bridged = ClassBytecodeAnalyzer.injectMissingInterfaceDefaultBridges(
                    classBytes = rawBytes,
                    defaultMethodsByIface = newlyRedirectedIfaces,
                    classBytesLookup = ::resolveClassBytes
                )
                val rewritten = ClassBytecodeAnalyzer.rewriteInterfaceSpecialCallsToDefaultImpls(
                    classBytes = bridged,
                    interfaceInternalNames = newlyRedirectedIfaces.keys
                )
                if (!rewritten.contentEquals(rawBytes)) {
                    modifiedRawBytesByClass[className] = classFile to rawBytes
                }
            }
        }

        fun detectAndRegisterAnonymousClassRenames() {
            val orderedCandidates = modifiedRawBytesByClass.entries.sortedBy { it.key.length }
            for ((diskClassName, fileAndBytes) in orderedCandidates) {
                val rawBytes = fileAndBytes.second
                if (!ClassBytecodeAnalyzer.isRenamableSyntheticOrAnonymousClass(rawBytes)) continue
                val diskInternalName = diskClassName.replace('.', '/')
                val mappedInternalName = ClassBytecodeAnalyzer.remapSingleInternalClassName(
                    diskInternalName,
                    activeRenames
                )
                val prevLoadedBytes = previousBytesMap[mappedInternalName.replace('/', '.')] ?: continue
                val provisionalBytes = ClassBytecodeAnalyzer.remapInternalClassNames(rawBytes, activeRenames)
                if (ClassBytecodeAnalyzer.hasClassHierarchyChange(prevLoadedBytes, provisionalBytes)) {
                    val renamedInternal = "${diskInternalName}\$hr${++classRenameCounter}"
                    activeRenames[diskInternalName] = renamedInternal
                    newlyRenamedClasses[diskInternalName] = renamedInternal
                }
            }
        }

        fun collectNewlyRenamedClassCallers() {
            if (newlyRenamedClasses.isEmpty()) return
            for ((className, classFile) in currentClasses) {
                if (className in modifiedRawBytesByClass || className in synthesizedDefaultImplsByClass) {
                    continue
                }
                val rawBytes = classFile.readBytes()
                val rewritten = ClassBytecodeAnalyzer.remapInternalClassNames(rawBytes, newlyRenamedClasses)
                if (rewritten !== rawBytes && !rewritten.contentEquals(rawBytes)) {
                    modifiedRawBytesByClass[className] = classFile to rawBytes
                }
            }
        }
    }

    private fun collectModifiedClasses(
        changesByModule: Map<GradleModule, List<File>>
    ): ClassCollectionResult {
        val changedClasses = LinkedHashMap<String, ModifiedClassEntry>()
        val completedStates = mutableListOf<ModuleCollectState>()
        for ((module, sourceFiles) in changesByModule) {
            val state = ModuleCollectState(
                module = module,
                previousMeta = classMetaByModule[module.gradlePath].orEmpty(),
                previousBytesMap = classBytesByModule[module.gradlePath].orEmpty(),
                currentClasses = scanModuleClassFiles(module),
                editedBaseNames = sourceFiles.map { it.nameWithoutExtension }.toSet(),
                redirectedIfaces = redirectedInterfacesByModule[module.gradlePath]
                    ?.let { LinkedHashMap(it) }
                    ?: LinkedHashMap()
            )

            state.scanModifiedCandidates()
            state.structuralIncompatibleReason?.let { return ClassCollectionResult.Incompatible(it) }
            state.collectNewlyRedirectedInterfaceCallers()
            state.detectAndRegisterAnonymousClassRenames()
            state.collectNewlyRenamedClassCallers()
            if (state.activeRenames.isNotEmpty() && state.synthesizedDefaultImplsByClass.isNotEmpty()) {
                for ((defaultImplsName, fileAndBytes) in state.synthesizedDefaultImplsByClass.entries.toList()) {
                    val remapped = ClassBytecodeAnalyzer.remapInternalClassNames(
                        fileAndBytes.second,
                        state.activeRenames
                    )
                    state.synthesizedDefaultImplsByClass[defaultImplsName] = fileAndBytes.first to remapped
                }
            }
            state.modifiedRawBytesByClass.putAll(state.synthesizedDefaultImplsByClass)

            for ((diskClassName, fileAndBytes) in state.modifiedRawBytesByClass) {
                val remappedBytes = ClassBytecodeAnalyzer.remapInternalClassNames(
                    fileAndBytes.second,
                    state.activeRenames
                )
                val effectiveClassName = ClassBytecodeAnalyzer.remapSingleInternalClassName(
                    diskClassName.replace('.', '/'),
                    state.activeRenames
                ).replace('/', '.')
                val effectiveFile = if (effectiveClassName == diskClassName) {
                    fileAndBytes.first
                } else {
                    File(fileAndBytes.first.parentFile, "${effectiveClassName.substringAfterLast('.')}.class")
                }
                val entry = buildModifiedClassEntry(state, effectiveClassName, effectiveFile, remappedBytes)
                state.structuralIncompatibleReason?.let { return ClassCollectionResult.Incompatible(it) }
                if (entry != null) {
                    changedClasses[effectiveClassName] = entry
                }
            }
            completedStates.add(state)
        }
        for (state in completedStates) {
            val modPath = state.module.gradlePath
            classMetaByModule[modPath] = state.updatedMeta
            classBytesByModule[modPath] = state.updatedBytesMap
            redirectedInterfacesByModule[modPath] = state.redirectedIfaces
            activeClassRenamesByModule[modPath] = state.activeRenames
        }
        return ClassCollectionResult.Collected(changedClasses.values.toList())
    }

    private fun buildModifiedClassEntry(
        state: ModuleCollectState,
        className: String,
        classFile: File,
        rawOrSynthesizedBytes: ByteArray
    ): ModifiedClassEntry? {
        val modulePath = state.module.gradlePath
        val prevBytes = state.previousBytesMap[className]
        val baselineSuper = resolveBaselineSuperClass(modulePath, className)
        val hiltSibling = hasHiltSibling(className, state.currentClasses, state.previousMeta)
        val isSynthesizedDefaultImpls = className in state.synthesizedDefaultImplsByClass
        val hiltCurrentBytes = if (isSynthesizedDefaultImpls) {
            rawOrSynthesizedBytes
        } else {
            val transformed = ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(
                classBytes = rawOrSynthesizedBytes,
                baselineSuperClass = baselineSuper,
                hasHiltSiblingClass = hiltSibling
            )
            val bridged = ClassBytecodeAnalyzer.injectMissingInterfaceDefaultBridges(
                classBytes = transformed,
                defaultMethodsByIface = state.redirectedIfaces,
                classBytesLookup = state::resolveClassBytes
            )
            ClassBytecodeAnalyzer.rewriteInterfaceSpecialCallsToDefaultImpls(bridged, state.redirectedIfaces.keys)
        }
        val hiltPrevBytes = prevBytes?.let {
            ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(
                classBytes = it,
                baselineSuperClass = baselineSuper,
                hasHiltSiblingClass = hiltSibling
            )
        }
        if (!isSynthesizedDefaultImpls) {
            val dexHierarchyFallback = if (hiltPrevBytes == null) {
                resolveBaselineDexMembers(modulePath, className)
            } else {
                null
            }
            val unsupportedReason = ClassBytecodeAnalyzer.detectUnsupportedRedefinitionReason(
                previousBytes = hiltPrevBytes,
                currentBytes = hiltCurrentBytes,
                baselineDexMembers = dexHierarchyFallback
            )
            if (unsupportedReason != null) {
                state.structuralIncompatibleReason = unsupportedReason
                return null
            }
        }
        state.updatedBytesMap[className] = hiltCurrentBytes

        if (hiltPrevBytes != null &&
            !ClassBytecodeAnalyzer.hasExecutableBytecodeChange(hiltPrevBytes, hiltCurrentBytes)
        ) {
            return null
        }

        val crossModuleChange = state.previousMeta.isNotEmpty() &&
            (
                state.anyInterfaceCrossModuleChange ||
                    ClassBytecodeAnalyzer.hasCrossModuleVisibleChange(hiltPrevBytes, hiltCurrentBytes)
                )
        val rememberDiff = if (hiltPrevBytes != null) {
            ClassBytecodeAnalyzer.analyzeComposeRememberChanges(hiltPrevBytes, hiltCurrentBytes)
        } else {
            ClassBytecodeAnalyzer.ComposeRememberDiff()
        }
        val staticDiff = ClassBytecodeAnalyzer.prepareStaticFieldUpdates(hiltPrevBytes, hiltCurrentBytes)
        val knownStubs = cumulativeStubsByModule[modulePath]?.get(className)
        val preMergedBytes = if (knownStubs != null) {
            AdditiveClassMerger.mergeWithKnownStubs(staticDiff.patchedClassBytes, knownStubs)
        } else {
            staticDiff.patchedClassBytes
        }
        val combinedStaticUpdates = if (state.interfaceStaticFieldUpdates.isNotEmpty()) {
            val list = staticDiff.constantFieldUpdates + state.interfaceStaticFieldUpdates
            state.interfaceStaticFieldUpdates.clear()
            list
        } else {
            staticDiff.constantFieldUpdates
        }
        return ModifiedClassEntry(
            modulePath = modulePath,
            className = className,
            originalFile = classFile,
            previousClassBytes = hiltPrevBytes,
            unpatchedCurrentBytes = hiltCurrentBytes,
            mergedBytes = preMergedBytes,
            hasCrossModuleChange = crossModuleChange,
            reinitClinit = staticDiff.reinitClinit,
            resetScopeKeys = rememberDiff.resetScopeKeys,
            resetScope = rememberDiff.resetAllClassScopes,
            changedScopeKeys = rememberDiff.changedScopeKeys,
            invalidateAllClassScopes = if (hiltPrevBytes == null) false else rememberDiff.invalidateAllClassScopes,
            staticFieldUpdates = combinedStaticUpdates
        )
    }

    private fun resolveBaselineDexMembers(
        modulePath: String,
        className: String
    ): AdditiveClassMerger.DexClassMembers? {
        lookupInstalledDexMembers(className)?.let { return it }
        return baselineDexFilesByModule[modulePath]?.get(className)
            ?.takeIf { it.exists() }
            ?.readBytes()
            ?.let { AdditiveClassMerger.parseDexMembers(it, className) }
    }

    private fun resolveBaselineSuperClass(modulePath: String, className: String): String? {
        cumulativeStubsByModule[modulePath]?.get(className)?.superClassName?.let { return it }
        lookupInstalledDexMembers(className)?.superClassName?.let { return it }
        dexBytesByModule[modulePath]?.get(className)
            ?.let { AdditiveClassMerger.parseDexMembers(it, className)?.superClassName }
            ?.let { return it }
        return baselineDexFilesByModule[modulePath]?.get(className)
            ?.takeIf { it.exists() }
            ?.readBytes()
            ?.let { AdditiveClassMerger.parseDexMembers(it, className)?.superClassName }
    }

    private fun hasHiltSibling(
        className: String,
        currentClasses: Map<String, File>,
        previousMeta: Map<String, ClassFileMeta>
    ): Boolean {
        val pkg = className.substringBeforeLast('.', "")
        val simpleWithDollars = if (pkg.isEmpty()) className else className.substring(pkg.length + 1)
        val hiltSimple = "Hilt_${simpleWithDollars.replace('$', '_')}"
        val hiltClassName = if (pkg.isEmpty()) hiltSimple else "$pkg.$hiltSimple"
        return hiltClassName in currentClasses ||
            hiltClassName in previousMeta ||
            lookupInstalledDexMembers(hiltClassName) != null
    }

    private fun isUnchangedClassFile(
        prevMeta: ClassFileMeta?,
        curLastModified: Long,
        curLength: Long
    ): Boolean {
        return prevMeta != null && prevMeta.lastModified == curLastModified && prevMeta.length == curLength
    }

    private fun isClassModified(
        prevHash: String?,
        currentHash: String,
        hasNoPreviousHashes: Boolean,
        matchesEditedSource: Boolean
    ): Boolean {
        if (prevHash == null || prevHash != currentHash) return true
        return hasNoPreviousHashes && matchesEditedSource
    }

    private fun matchesEditedSourceName(simpleName: String, base: String): Boolean {
        val exactMatches = listOf(base, "${base}Kt")
        if (simpleName in exactMatches) return true
        val prefixes = listOf("$base$", "${base}Kt$", "ComposableSingletons\$$base")
        return prefixes.any { simpleName.startsWith(it) }
    }

    private fun packageDexPayloads(
        modifiedClasses: List<ModifiedClassEntry>,
        stagingDir: File
    ): CompilationOutcome {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val d8 = d8PathProvider()
        val baseFlags = d8BaseFlags(d8)
        val generation = ++reloadGeneration
        val token = ((System.nanoTime() ushr TOKEN_SHIFT_BITS) xor generation.toLong()) and POSITIVE_LONG_MASK
        val base36 = token.toString(BASE36_RADIX).uppercase(Locale.US).padStart(MARKER_TOKEN_LENGTH, '0')
            .takeLast(MARKER_TOKEN_LENGTH)
        val syntheticMarker = "\$\$ExternalSynthetic${base36}_"
        val backportMarker = "\$\$ExternalSyntheticBp${base36}_"
        val payloads = mutableListOf<ClassDexPayload>()
        val mergedInputsDir = File(stagingDir, "merged_inputs").apply { mkdirs() }
        val perClassOutDir = File(stagingDir, "per_class_out").apply { mkdirs() }
        val ctx = D8BatchContext(
            d8Path = d8,
            baseFlags = baseFlags,
            stagingDir = stagingDir,
            mergedInputsDir = mergedInputsDir,
            perClassOutDir = perClassOutDir,
            syntheticMarker = syntheticMarker,
            backportMarker = backportMarker
        )

        val initialInputFiles = modifiedClasses.mapIndexed { index, entry ->
            prepareInputClassFile(entry, mergedInputsDir, index)
        }

        val batchArgs = baseFlags + listOf("--file-per-class", "--output", perClassOutDir.absolutePath) +
            initialInputFiles.map { it.absolutePath }
        val batchRes = executeD8(d8, batchArgs)
        if (batchRes.isFailure) {
            val firstClass = modifiedClasses.first().className
            return CompilationOutcome.Failure(
                module = firstClass,
                diagnostic = "d8 failed for $firstClass: ${batchRes.exceptionOrNull()?.message}"
            )
        }

        val (finalInputFiles, prevMembersByClass) = reconcileBatchWithPreviousDexIfNeeded(
            entries = modifiedClasses,
            initialFiles = initialInputFiles,
            ctx = ctx
        )

        val perClassDexFiles = perClassOutDir.walkTopDown()
            .filter { it.isFile && it.extension == "dex" && it.name != "classes.dex" }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

        val entryByClassName = modifiedClasses.associateBy { it.className }
        if (perClassDexFiles.isNotEmpty()) {
            for (dexFile in perClassDexFiles) {
                val rawDexClassName = dexFile.relativeTo(perClassOutDir)
                    .invariantSeparatorsPath
                    .removeSuffix(".dex")
                    .replace('/', '.')
                val matchingEntry = entryByClassName[rawDexClassName]
                var rawBytes = dexFile.readBytes()
                if (matchingEntry != null) {
                    prevMembersByClass[rawDexClassName]?.let { prevDex ->
                        rawBytes = AdditiveClassMerger.alignDexMethodAccessFlags(rawBytes, rawDexClassName, prevDex)
                    }
                    dexBytesByModule.getOrPut(matchingEntry.modulePath) { mutableMapOf() }[rawDexClassName] = rawBytes
                    persistRedefinedDex(matchingEntry.modulePath, rawDexClassName, rawBytes)
                }
                val dexClassName = rawDexClassName
                    .replace(DEFAULT_SYNTHETIC_MARKER, syntheticMarker)
                    .replace(DEFAULT_BACKPORT_MARKER, backportMarker)
                val targetDex = File(stagingDir, "redefine_${payloads.size}.dex")
                targetDex.writeBytes(
                    transformDexBytesWithSyntheticMarker(rawBytes, syntheticMarker, backportMarker)
                )
                payloads.add(
                    ClassDexPayload(
                        className = dexClassName,
                        dexFile = targetDex,
                        reinitClinit = matchingEntry?.reinitClinit ?: false,
                        resetScopeKeys = matchingEntry?.resetScopeKeys.orEmpty(),
                        resetScope = matchingEntry?.resetScope ?: false,
                        changedScopeKeys = matchingEntry?.changedScopeKeys.orEmpty(),
                        invalidateAllClassScopes = matchingEntry?.invalidateAllClassScopes ?: false,
                        staticFieldUpdates = matchingEntry?.staticFieldUpdates.orEmpty()
                    )
                )
            }
        } else {
            val fallbackDex = File(perClassOutDir, "classes.dex")
            if (fallbackDex.exists()) {
                val rewrittenFallback = transformDexBytesWithSyntheticMarker(
                    fallbackDex.readBytes(),
                    syntheticMarker,
                    backportMarker
                )
                for (entry in modifiedClasses) {
                    val targetDex = File(stagingDir, "redefine_${payloads.size}.dex")
                    targetDex.writeBytes(rewrittenFallback)
                    payloads.add(
                        ClassDexPayload(
                            className = entry.className,
                            dexFile = targetDex,
                            reinitClinit = entry.reinitClinit,
                            resetScopeKeys = entry.resetScopeKeys,
                            resetScope = entry.resetScope,
                            changedScopeKeys = entry.changedScopeKeys,
                            invalidateAllClassScopes = entry.invalidateAllClassScopes,
                            staticFieldUpdates = entry.staticFieldUpdates
                        )
                    )
                }
            }
        }

        val combinedDex = buildCombinedDex(
            ctx = ctx,
            finalInputFiles = finalInputFiles,
            payloads = payloads,
            hasPerClassDexFiles = perClassDexFiles.isNotEmpty()
        ) ?: return CompilationOutcome.Failure(
            module = "d8",
            diagnostic = "d8 combined dex failed"
        )

        val orderedPayloads = payloads.sortedBy { payload ->
            if (payload.className.contains("ComposableSingletons\$") || payload.reinitClinit) 0 else 1
        }

        return CompilationOutcome.Success(
            classPayloads = orderedPayloads,
            combinedDexFile = combinedDex
        )
    }

    private fun buildCombinedDex(
        ctx: D8BatchContext,
        finalInputFiles: List<File>,
        payloads: List<ClassDexPayload>,
        hasPerClassDexFiles: Boolean
    ): File? {
        if (payloads.size == 1 || (!hasPerClassDexFiles && payloads.isNotEmpty())) {
            val target = File(ctx.stagingDir, "new_classes.dex")
            payloads.first().dexFile.copyTo(target, overwrite = true)
            return target
        }

        val combinedOutDir = File(ctx.stagingDir, "combined").apply { mkdirs() }
        val combinedFile = File(combinedOutDir, "classes.dex")
        if (hasPerClassDexFiles && payloads.isNotEmpty()) {
            val minApi = (desugarD8Config?.minApi ?: DEFAULT_MIN_API).toString()
            val dexMergeArgs = listOf("--min-api", minApi, "--output", combinedOutDir.absolutePath) +
                payloads.map { it.dexFile.absolutePath }
            if (executeD8(ctx.d8Path, dexMergeArgs).isSuccess && combinedFile.exists()) {
                val target = File(ctx.stagingDir, "new_classes.dex")
                combinedFile.copyTo(target, overwrite = true)
                return target
            }
        }

        val combinedArgs = ctx.baseFlags + listOf("--output", combinedOutDir.absolutePath) +
            finalInputFiles.map { it.absolutePath }
        val combinedRes = executeD8(ctx.d8Path, combinedArgs)
        if (combinedRes.isFailure) return null

        if (!combinedFile.exists()) return null
        val target = File(ctx.stagingDir, "new_classes.dex")
        combinedFile.copyTo(target, overwrite = true)
        rewriteSyntheticLambdaMarker(target, ctx.syntheticMarker, ctx.backportMarker)
        return target
    }

    private fun prepareInputClassFile(entry: ModifiedClassEntry, mergedInputsDir: File, index: Int): File {
        val rawBytes = entry.originalFile.takeIf { it.exists() }?.readBytes()
        if (rawBytes != null && entry.mergedBytes.contentEquals(rawBytes)) {
            return entry.originalFile
        }
        val out = File(mergedInputsDir, "${index}_${entry.originalFile.name}")
        out.writeBytes(entry.mergedBytes)
        return out
    }

    private fun reconcileBatchWithPreviousDexIfNeeded(
        entries: List<ModifiedClassEntry>,
        initialFiles: List<File>,
        ctx: D8BatchContext
    ): Pair<List<File>, Map<String, AdditiveClassMerger.DexClassMembers>> {
        val updatedFiles = initialFiles.toMutableList()
        val reconciledFiles = mutableListOf<File>()
        val prevMembersByClass = LinkedHashMap<String, AdditiveClassMerger.DexClassMembers>()

        for ((index, entry) in entries.withIndex()) {
            val mainDexFile = File(ctx.perClassOutDir, "${entry.className.replace('.', '/')}.dex")
            if (!mainDexFile.exists()) {
                val fallbackFile = fallbackMergeClassOnly(entry, ctx.mergedInputsDir, index)
                if (fallbackFile != null) {
                    updatedFiles[index] = fallbackFile
                }
                continue
            }

            val prevMembers = resolvePreviousDexMembers(entry, index, ctx) ?: continue
            prevMembersByClass[entry.className] = prevMembers
            val currentDexBytes = mainDexFile.readBytes()
            val currMembers = AdditiveClassMerger.parseDexMembers(currentDexBytes, entry.className) ?: continue
            val reconciledClassBytes = AdditiveClassMerger.mergeWithPreviousDexMembers(
                prevDex = prevMembers,
                currDex = currMembers,
                currentClassBytes = entry.mergedBytes
            )
            if (reconciledClassBytes.contentEquals(entry.mergedBytes)) continue

            recordCumulativeStubs(entry.modulePath, entry.className, prevMembers)
            val outClassFile = File(ctx.mergedInputsDir, "reconciled_${index}_${entry.originalFile.name}")
            outClassFile.writeBytes(reconciledClassBytes)
            updatedFiles[index] = outClassFile
            reconciledFiles.add(outClassFile)
        }

        if (reconciledFiles.isNotEmpty()) {
            ctx.perClassOutDir.deleteRecursively()
            ctx.perClassOutDir.mkdirs()
            val rerunArgs = ctx.baseFlags + listOf("--file-per-class", "--output", ctx.perClassOutDir.absolutePath) +
                updatedFiles.map { it.absolutePath }
            executeD8(ctx.d8Path, rerunArgs)
        }

        return updatedFiles to prevMembersByClass
    }

    private fun mergeDexMembers(
        existing: AdditiveClassMerger.DexClassMembers,
        incoming: AdditiveClassMerger.DexClassMembers
    ): AdditiveClassMerger.DexClassMembers {
        val fieldMap = (existing.fields + incoming.fields).associateBy { it.name to it.descriptor }
        val methodMap = (existing.methods + incoming.methods).associateBy { it.name to it.descriptor }
        return AdditiveClassMerger.DexClassMembers(
            fields = fieldMap.values.toList(),
            methods = methodMap.values.toList(),
            superClassName = incoming.superClassName ?: existing.superClassName,
            interfaces = incoming.interfaces.ifEmpty { existing.interfaces },
            accessFlags = if (incoming.accessFlags != 0) incoming.accessFlags else existing.accessFlags
        )
    }

    private fun recordCumulativeStubs(
        modulePath: String,
        className: String,
        prevMembers: AdditiveClassMerger.DexClassMembers
    ) {
        val moduleStubs = cumulativeStubsByModule.getOrPut(modulePath) { mutableMapOf() }
        val existing = moduleStubs[className]
        moduleStubs[className] = if (existing == null) prevMembers else mergeDexMembers(existing, prevMembers)
    }

    private fun persistRedefinedDex(modulePath: String, className: String, dexBytes: ByteArray) {
        val moduleDir = moduleDirsByPath[modulePath] ?: return
        val moduleVariant = resolveVariantForDir(moduleDir)
        runCatching {
            val outFile = File(
                moduleDir,
                "build/tmp/debroid-hotreload/$moduleVariant/${className.replace('.', '/')}.dex"
            )
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(dexBytes)
        }
    }

    private fun fallbackMergeClassOnly(entry: ModifiedClassEntry, mergedInputsDir: File, index: Int): File? {
        val prevClassBytes = entry.previousClassBytes ?: return null
        val merged = AdditiveClassMerger.mergePreservingRemovedMembers(prevClassBytes, entry.mergedBytes)
        return if (merged.contentEquals(entry.mergedBytes)) {
            null
        } else {
            AdditiveClassMerger.extractClassMembers(prevClassBytes)?.let {
                recordCumulativeStubs(entry.modulePath, entry.className, it)
            }
            File(mergedInputsDir, "${index}_${entry.originalFile.name}").apply { writeBytes(merged) }
        }
    }

    private fun resolvePreviousDexMembers(
        entry: ModifiedClassEntry,
        index: Int,
        ctx: D8BatchContext
    ): AdditiveClassMerger.DexClassMembers? {
        var combined: AdditiveClassMerger.DexClassMembers? =
            cumulativeStubsByModule[entry.modulePath]?.get(entry.className)

        dexBytesByModule[entry.modulePath]?.get(entry.className)?.let { bytes ->
            AdditiveClassMerger.parseDexMembers(bytes, entry.className)?.let { parsed ->
                combined = if (combined == null) parsed else mergeDexMembers(combined!!, parsed)
            }
        }

        baselineDexFilesByModule[entry.modulePath]?.get(entry.className)
            ?.takeIf { it.exists() }
            ?.readBytes()
            ?.let { bytes ->
                dexBytesByModule.getOrPut(entry.modulePath) { mutableMapOf() }[entry.className] = bytes
                AdditiveClassMerger.parseDexMembers(bytes, entry.className)?.let { parsed ->
                    combined = if (combined == null) parsed else mergeDexMembers(combined!!, parsed)
                }
            }

        lookupInstalledDexMembers(entry.className)?.let { parsed ->
            combined = if (combined == null) parsed else mergeDexMembers(combined!!, parsed)
        }

        if (combined != null) return combined

        val prevClassBytes = entry.previousClassBytes ?: return null
        val prevClassFile = File(ctx.mergedInputsDir, "prev_${index}_${entry.originalFile.name}")
        prevClassFile.writeBytes(prevClassBytes)
        val prevDexOutDir = File(ctx.mergedInputsDir, "prev_dex_$index").apply { mkdirs() }
        val args = ctx.baseFlags + listOf(
            "--file-per-class",
            "--output",
            prevDexOutDir.absolutePath,
            prevClassFile.absolutePath
        )
        if (executeD8(ctx.d8Path, args).isFailure) return null
        val generatedDex = File(prevDexOutDir, "${entry.className.replace('.', '/')}.dex")
        val bytes = generatedDex.takeIf { it.exists() }?.readBytes() ?: return null
        dexBytesByModule.getOrPut(entry.modulePath) { mutableMapOf() }[entry.className] = bytes
        return AdditiveClassMerger.parseDexMembers(bytes, entry.className)
    }

    private fun executeD8(d8Path: String, args: List<String>): Result<String> {
        val inProcessResult = InProcessD8Runner.tryRunInProcess(d8Path, args)
        if (inProcessResult != null) {
            return inProcessResult
        }
        return commandRunner.runCommand(listOf(d8Path) + args, D8_TIMEOUT_SECONDS)
    }

    private fun d8BaseFlags(d8Path: String): List<String> {
        val majorVersion = File(d8Path).parentFile?.name
            ?.substringBefore('.')
            ?.toIntOrNull() ?: 0
        val desugar = desugarD8Config
        val minApi = (desugar?.minApi ?: DEFAULT_MIN_API).toString()
        val flags = mutableListOf("--min-api", minApi)
        if (majorVersion >= MIN_VERBOSE_SYNTHETIC_D8_MAJOR) {
            flags.add("--verbose-synthetic-names")
        }
        if (desugar != null) {
            flags.addAll(
                listOf(
                    "--lib",
                    desugar.androidJar.absolutePath,
                    "--desugared-lib",
                    desugar.desugarConfigJson.absolutePath
                )
            )
        }
        return flags
    }

    private fun rewriteSyntheticLambdaMarker(dexFile: File, newMarker: String, backportMarker: String) {
        val raw = dexFile.readBytes()
        val transformed = transformDexBytesWithSyntheticMarker(raw, newMarker, backportMarker)
        if (transformed !== raw) {
            dexFile.writeBytes(transformed)
        }
    }

    private fun transformDexBytesWithSyntheticMarker(
        rawBytes: ByteArray,
        newMarker: String,
        backportMarker: String
    ): ByteArray {
        if (!isValidDexHeader(rawBytes)) return rawBytes
        val bytes = rawBytes.clone()
        val oldBytes = DEFAULT_SYNTHETIC_MARKER.toByteArray(Charsets.US_ASCII)
        val newBytes = newMarker.toByteArray(Charsets.US_ASCII)
        val replacedMarker = oldBytes.size == newBytes.size && replaceAsciiBytesInPlace(bytes, oldBytes, newBytes)
        val oldBpBytes = DEFAULT_BACKPORT_MARKER.toByteArray(Charsets.US_ASCII)
        val newBpBytes = backportMarker.toByteArray(Charsets.US_ASCII)
        val replacedBp = oldBpBytes.size == newBpBytes.size && replaceAsciiBytesInPlace(bytes, oldBpBytes, newBpBytes)
        val replacedSynthAnn = replaceAsciiBytesInPlace(
            bytes,
            R8_SYNTHESIZED_ANNOTATION.toByteArray(Charsets.US_ASCII),
            R8_SYNTHESIZED_NEUTRALIZED.toByteArray(Charsets.US_ASCII)
        )
        if (!replacedMarker && !replacedBp && !replacedSynthAnn) return rawBytes

        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(bytes, DEX_HEADER_MIN_BYTES, bytes.size - DEX_HEADER_MIN_BYTES)
        System.arraycopy(sha1.digest(), 0, bytes, DEX_SIGNATURE_OFFSET, DEX_SIGNATURE_LENGTH)

        val adler = Adler32()
        adler.update(bytes, DEX_SIGNATURE_OFFSET, bytes.size - DEX_SIGNATURE_OFFSET)
        ByteBuffer.wrap(bytes, DEX_CHECKSUM_OFFSET, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(adler.value.toInt())

        return bytes
    }

    private fun isValidDexHeader(bytes: ByteArray): Boolean {
        return bytes.size >= DEX_HEADER_MIN_BYTES &&
            bytes[0] == 'd'.code.toByte() &&
            bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte()
    }

    private fun replaceAsciiBytesInPlace(bytes: ByteArray, oldBytes: ByteArray, newBytes: ByteArray): Boolean {
        var replaced = false
        var idx = 0
        while (idx <= bytes.size - oldBytes.size) {
            val matches = oldBytes.indices.all { j -> bytes[idx + j] == oldBytes[j] }
            if (matches) {
                System.arraycopy(newBytes, 0, bytes, idx, newBytes.size)
                replaced = true
                idx += newBytes.size
            } else {
                idx++
            }
        }
        return replaced
    }

    internal fun resolveModuleVariant(module: GradleModule): String = resolveVariantForDir(module.moduleDir)

    private fun resolveVariantForDir(moduleDir: File): String {
        if (variant == "debug") return variant
        val probeParents = listOf(
            File(moduleDir, "build/intermediates/built_in_kotlinc"),
            File(moduleDir, "build/intermediates/javac"),
            File(moduleDir, "build/intermediates/compile_r_class_jar"),
            File(moduleDir, "build/intermediates/local_only_symbol_list"),
            File(moduleDir, "build/tmp/kotlin-classes"),
            File(moduleDir, "build/intermediates/kotlin-classes")
        )
        if (probeParents.any { File(it, variant).isDirectory }) {
            return variant
        }
        if (variant.endsWith("Debug", ignoreCase = true) && probeParents.any { File(it, "debug").isDirectory }) {
            return "debug"
        }
        return variant
    }

    private fun scanModuleClassFiles(module: GradleModule): Map<String, File> {
        val moduleVariant = resolveModuleVariant(module)
        val capVariant = moduleVariant.replaceFirstChar { it.uppercaseChar() }
        val candidateDirs = listOf(
            File(
                module.moduleDir,
                "build/intermediates/built_in_kotlinc/$moduleVariant/compile${capVariant}Kotlin/classes"
            ),
            File(module.moduleDir, "build/intermediates/built_in_kotlinc/$moduleVariant/classes"),
            File(module.moduleDir, "build/tmp/kotlin-classes/$moduleVariant"),
            File(module.moduleDir, "build/intermediates/kotlin-classes/$moduleVariant"),
            File(
                module.moduleDir,
                "build/intermediates/javac/$moduleVariant/compile${capVariant}JavaWithJavac/classes"
            ),
            File(module.moduleDir, "build/intermediates/javac/$moduleVariant/classes"),
            File(module.moduleDir, "build/classes/kotlin/android/$moduleVariant"),
            File(module.moduleDir, "build/classes/kotlin/android/main"),
            File(module.moduleDir, "build/classes/kotlin/main"),
            File(module.moduleDir, "build/classes/java/main")
        )

        val result = LinkedHashMap<String, File>()
        for (root in candidateDirs) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.contains("module-info") }
                .forEach { classFile ->
                    val relPath = classFile.relativeTo(root).invariantSeparatorsPath
                    if (!relPath.startsWith("META-INF/")) {
                        val className = relPath.removeSuffix(".class").replace('/', '.')
                        result.putIfAbsent(className, classFile)
                    }
                }
        }
        return result
    }

    private fun scanModuleDexFiles(module: GradleModule): Map<String, File> {
        val moduleVariant = resolveModuleVariant(module)
        val capVariant = moduleVariant.replaceFirstChar { it.uppercaseChar() }
        val candidateDirs = listOf(
            File(
                module.moduleDir,
                "build/intermediates/project_dex_archive/$moduleVariant/dexBuilder$capVariant/out"
            ),
            File(module.moduleDir, "build/intermediates/project_dex_archive/$moduleVariant/out")
        )

        val result = LinkedHashMap<String, File>()
        for (root in candidateDirs) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "dex" && it.name != "classes.dex" }
                .forEach { dexFile ->
                    val relPath = dexFile.relativeTo(root).invariantSeparatorsPath
                    val className = relPath.removeSuffix(".dex").replace('/', '.')
                    result.putIfAbsent(className, dexFile)
                }
        }
        return result
    }

    private fun resolveGradleWrapper(): String {
        resolveGradleWrapperForDir(projectRoot)?.let { return it }
        for (mod in knownModulesByPath.values) {
            val ibRoot = mod.includedBuildRoot ?: continue
            resolveGradleWrapperForDir(ibRoot)?.let { return it }
        }
        return "gradle"
    }

    private fun resolveGradleWrapperForDir(dir: File): String? {
        val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
        val wrapper = File(dir, if (isWin) "gradlew.bat" else "gradlew")
        return wrapper.takeIf { it.isFile }?.absolutePath
    }

    internal fun extractDiagnostic(rawError: String): String {
        val lines = rawError.lines()
        for (line in lines) {
            val match = kotlincErrorRegex.find(line)
            if (match != null) {
                val fileName = File(match.groupValues[1]).name
                val lineNum = match.groupValues[2]
                val colNum = match.groupValues[3]
                val message = match.groupValues[4].trim()
                return "$fileName:$lineNum:$colNum $message"
            }
        }
        val whatWentWrongIdx = lines.indexOfFirst { it.trim() == "* What went wrong:" }
        if (whatWentWrongIdx >= 0) {
            val block = lines.drop(whatWentWrongIdx + 1)
                .map { it.trim() }
                .takeWhile { it.isNotEmpty() && !it.startsWith("* ") && !it.startsWith("BUILD FAILED") }
            if (block.isNotEmpty()) {
                return block.joinToString(" ")
            }
        }
        val firstFailure = lines
            .map { it.trim() }
            .firstOrNull { it.startsWith("e:") || it.contains("FAILED") || it.contains("error:") }
        return firstFailure ?: lines.lastOrNull { it.isNotBlank() }?.trim() ?: "Compilation failed"
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }
}

@Suppress("MagicNumber", "TooGenericExceptionCaught")
private object InProcessD8Runner {
    private const val CLASS_MAGIC = 0xCAFEBABE.toInt()
    private const val JAVA_8_MAJOR = 52
    private const val ACC_PUBLIC_SUPER = 0x0021
    private val classLoaders = ConcurrentHashMap<String, URLClassLoader>()
    private val warmedJars = ConcurrentHashMap.newKeySet<String>()

    fun resolveD8Jar(d8Path: String): File? {
        val d8File = File(d8Path)
        val parent = d8File.parentFile ?: return null
        val libJar = File(parent, "lib/d8.jar")
        if (libJar.isFile) return libJar
        val directJar = File(parent, "d8.jar")
        if (directJar.isFile) return directJar
        return null
    }

    fun tryRunInProcess(d8Path: String, args: List<String>): Result<String>? {
        val jarFile = resolveD8Jar(d8Path) ?: return null
        val errBytes = ByteArrayOutputStream()
        return synchronized(this) {
            val prevErr = System.err
            System.setErr(PrintStream(errBytes, true, Charsets.UTF_8))
            try {
                val loader = classLoaders.computeIfAbsent(jarFile.canonicalPath) {
                    URLClassLoader(arrayOf(jarFile.toURI().toURL()), ClassLoader.getPlatformClassLoader())
                }
                val originCls = loader.loadClass("com.android.tools.r8.origin.Origin")
                val rootOrigin = originCls.getMethod("root").invoke(null)
                val d8CommandCls = loader.loadClass("com.android.tools.r8.D8Command")
                val parseMethod = d8CommandCls.getMethod("parse", Array<String>::class.java, originCls)
                val builder = parseMethod.invoke(null, args.toTypedArray(), rootOrigin)
                val buildMethod = builder.javaClass.getMethod("build")
                val command = buildMethod.invoke(builder)
                val d8Cls = loader.loadClass("com.android.tools.r8.D8")
                val runMethod = d8Cls.getMethod("run", d8CommandCls)
                runMethod.invoke(null, command)
                Result.success("")
            } catch (e: InvocationTargetException) {
                val cause = e.targetException ?: e
                val detail = errBytes.toString(Charsets.UTF_8).trim().ifEmpty {
                    cause.message ?: "In-process D8 failed"
                }
                Result.failure(RuntimeException(detail, cause))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                System.setErr(prevErr)
            }
        }
    }

    fun warmUpAsync(d8Path: String, baseFlags: List<String>) {
        val jarFile = resolveD8Jar(d8Path) ?: return
        val canonical = jarFile.canonicalPath
        if (!warmedJars.add(canonical)) return

        Thread {
            runCatching {
                val tempDir = Files.createTempDirectory("debroid_d8_warmup").toFile()
                try {
                    val dummyClass = File(tempDir, "Warmup.class")
                    dummyClass.writeBytes(buildMinimalClassBytes())
                    val outDir = File(tempDir, "out").apply { mkdirs() }
                    tryRunInProcess(
                        d8Path,
                        baseFlags + listOf("--output", outDir.absolutePath, dummyClass.absolutePath)
                    )
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }.apply {
            name = "debroid-d8-warmup"
            isDaemon = true
            start()
        }
    }

    private fun buildMinimalClassBytes(): ByteArray {
        val baos = ByteArrayOutputStream(64)
        DataOutputStream(baos).use { dos ->
            dos.writeInt(CLASS_MAGIC)
            dos.writeShort(0)
            dos.writeShort(JAVA_8_MAJOR)
            dos.writeShort(5) // constant_pool_count
            dos.writeByte(7) // CONSTANT_Class (#1 -> #2)
            dos.writeShort(2)
            dos.writeByte(1) // CONSTANT_Utf8 (#2 "Warmup")
            dos.writeUTF("Warmup")
            dos.writeByte(7) // CONSTANT_Class (#3 -> #4)
            dos.writeShort(4)
            dos.writeByte(1) // CONSTANT_Utf8 (#4 "java/lang/Object")
            dos.writeUTF("java/lang/Object")
            dos.writeShort(ACC_PUBLIC_SUPER)
            dos.writeShort(1) // this_class
            dos.writeShort(3) // super_class
            dos.writeShort(0) // interfaces_count
            dos.writeShort(0) // fields_count
            dos.writeShort(0) // methods_count
            dos.writeShort(0) // attributes_count
        }
        return baos.toByteArray()
    }
}
