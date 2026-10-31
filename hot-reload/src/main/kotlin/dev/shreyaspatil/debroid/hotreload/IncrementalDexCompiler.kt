package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class IncrementalDexCompiler(
    private val projectRoot: File,
    private val variant: String = "debug",
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val d8PathProvider: () -> String = { findD8Executable() }
) {
    private val classHashesByModule = mutableMapOf<String, MutableMap<String, String>>()

    companion object {
        private const val COMPILE_TIMEOUT_SECONDS = 120L
        private const val D8_TIMEOUT_SECONDS = 30L
        private val kotlincErrorRegex = Regex("""e:\s*(?:file://)?([^\s:]+):(\d+):(\d+)\s*(.+)""")

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

    fun snapshotModuleClasses(modules: Collection<GradleModule>) {
        for (module in modules) {
            classHashesByModule[module.gradlePath] = scanModuleClassHashes(module).toMutableMap()
        }
    }

    fun buildGradleTasksForModules(changesByModule: Map<GradleModule, List<File>>): List<String> {
        val capVariant = variant.replaceFirstChar { it.uppercaseChar() }
        val tasks = mutableListOf<String>()

        for ((module, files) in changesByModule) {
            val hasJava = files.any { it.extension.equals("java", ignoreCase = true) }
            when (module.type) {
                ModuleType.ANDROID_APP, ModuleType.ANDROID_LIBRARY -> {
                    tasks.add("${module.gradlePath}:compile${capVariant}Kotlin")
                    if (hasJava) {
                        tasks.add("${module.gradlePath}:compile${capVariant}JavaWithJavac")
                    }
                }
                ModuleType.KOTLIN_JVM -> {
                    tasks.add("${module.gradlePath}:compileKotlin")
                    if (hasJava) {
                        tasks.add("${module.gradlePath}:compileJava")
                    }
                }
            }
        }
        return tasks.distinct()
    }

    fun compileAndDex(
        changesByModule: Map<GradleModule, List<File>>,
        stagingDir: File
    ): CompilationOutcome {
        // Ensure we have a pre-compile snapshot for any module not snapshotted yet
        for (module in changesByModule.keys) {
            if (module.gradlePath !in classHashesByModule) {
                classHashesByModule[module.gradlePath] = scanModuleClassHashes(module).toMutableMap()
            }
        }

        val tasks = buildGradleTasksForModules(changesByModule)
        val gradlew = resolveGradleWrapper()
        val gradleCmd = listOf(gradlew) + tasks + listOf("--console=plain", "-p", projectRoot.absolutePath)

        val compileResult = commandRunner.runCommand(gradleCmd, COMPILE_TIMEOUT_SECONDS)
        if (compileResult.isFailure) {
            val rawError = compileResult.exceptionOrNull()?.message.orEmpty()
            val firstModule = changesByModule.keys.firstOrNull()?.gradlePath ?: ":app"
            return CompilationOutcome.Failure(
                module = firstModule,
                diagnostic = extractDiagnostic(rawError)
            )
        }

        val modifiedClassFiles = collectModifiedClasses(changesByModule)
        if (modifiedClassFiles.isEmpty()) {
            return CompilationOutcome.Success(emptyList(), null)
        }

        return packageDexPayloads(modifiedClassFiles, stagingDir)
    }

    private fun collectModifiedClasses(
        changesByModule: Map<GradleModule, List<File>>
    ): List<Pair<String, File>> {
        val changedClasses = LinkedHashMap<String, File>()

        for ((module, sourceFiles) in changesByModule) {
            val previousHashes = classHashesByModule[module.gradlePath].orEmpty()
            val currentClasses = scanModuleClassFiles(module)
            val updatedHashes = mutableMapOf<String, String>()
            val editedBaseNames = sourceFiles.map { it.nameWithoutExtension }.toSet()

            for ((className, classFile) in currentClasses) {
                val hash = sha256(classFile)
                updatedHashes[className] = hash
                val prevHash = previousHashes[className]
                val simpleName = className.substringAfterLast('.')
                val matchesEditedSource = editedBaseNames.any { base ->
                    matchesEditedSourceName(simpleName, base)
                }

                val isModified = isClassModified(
                    prevHash = prevHash,
                    currentHash = hash,
                    hasNoPreviousHashes = previousHashes.isEmpty(),
                    matchesEditedSource = matchesEditedSource
                )
                if (isModified) {
                    changedClasses[className] = classFile
                }
            }
            classHashesByModule[module.gradlePath] = updatedHashes
        }

        return changedClasses.entries.map { it.key to it.value }
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
        modifiedClasses: List<Pair<String, File>>,
        stagingDir: File
    ): CompilationOutcome {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val d8 = d8PathProvider()
        val payloads = mutableListOf<ClassDexPayload>()

        for ((index, entry) in modifiedClasses.withIndex()) {
            val (className, classFile) = entry
            val singleOutDir = File(stagingDir, "cls_$index").apply { mkdirs() }
            val cmd = listOf(d8, "--output", singleOutDir.absolutePath, classFile.absolutePath)
            val res = commandRunner.runCommand(cmd, D8_TIMEOUT_SECONDS)
            if (res.isFailure) {
                return CompilationOutcome.Failure(
                    module = className,
                    diagnostic = "d8 failed for $className: ${res.exceptionOrNull()?.message}"
                )
            }
            val dexFile = File(singleOutDir, "classes.dex")
            if (dexFile.exists()) {
                val targetDex = File(stagingDir, "redefine_$index.dex")
                dexFile.copyTo(targetDex, overwrite = true)
                payloads.add(ClassDexPayload(className = className, dexFile = targetDex))
            }
        }

        val combinedOutDir = File(stagingDir, "combined").apply { mkdirs() }
        val allClassPaths = modifiedClasses.map { it.second.absolutePath }
        val combinedCmd = listOf(d8, "--output", combinedOutDir.absolutePath) + allClassPaths
        val combinedRes = commandRunner.runCommand(combinedCmd, D8_TIMEOUT_SECONDS)
        if (combinedRes.isFailure) {
            return CompilationOutcome.Failure(
                module = "d8",
                diagnostic = "d8 combined dex failed: ${combinedRes.exceptionOrNull()?.message}"
            )
        }
        val combinedDex = File(combinedOutDir, "classes.dex").takeIf { it.exists() }?.let { dex ->
            val target = File(stagingDir, "new_classes.dex")
            dex.copyTo(target, overwrite = true)
            target
        }

        return CompilationOutcome.Success(
            classPayloads = payloads,
            combinedDexFile = combinedDex
        )
    }

    private fun scanModuleClassHashes(module: GradleModule): Map<String, String> {
        return scanModuleClassFiles(module).mapValues { (_, file) -> sha256(file) }
    }

    private fun scanModuleClassFiles(module: GradleModule): Map<String, File> {
        val capVariant = variant.replaceFirstChar { it.uppercaseChar() }
        val candidateDirs = listOf(
            File(module.moduleDir, "build/tmp/kotlin-classes/$variant"),
            File(module.moduleDir, "build/intermediates/kotlin-classes/$variant"),
            File(module.moduleDir, "build/intermediates/javac/$variant/compile${capVariant}JavaWithJavac/classes"),
            File(module.moduleDir, "build/intermediates/javac/$variant/classes"),
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

    private fun resolveGradleWrapper(): String {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = File(projectRoot, if (isWin) "gradlew.bat" else "gradlew")
        return wrapper.absolutePath
    }

    private fun extractDiagnostic(rawError: String): String {
        for (line in rawError.lines()) {
            val match = kotlincErrorRegex.find(line)
            if (match != null) {
                val fileName = File(match.groupValues[1]).name
                val lineNum = match.groupValues[2]
                val colNum = match.groupValues[3]
                val message = match.groupValues[4].trim()
                return "$fileName:$lineNum:$colNum $message"
            }
        }
        val firstFailure = rawError.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("e:") || it.contains("FAILED") || it.contains("error:") }
        return firstFailure ?: rawError.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Compilation failed"
    }

    private fun sha256(file: File): String {
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }
}
