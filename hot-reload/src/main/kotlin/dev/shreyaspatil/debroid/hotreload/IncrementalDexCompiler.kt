package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Adler32

class IncrementalDexCompiler(
    private val projectRoot: File,
    private val variant: String = "debug",
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val d8PathProvider: () -> String = { findD8Executable() }
) {
    private data class ClassFileMeta(
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    private val classMetaByModule = mutableMapOf<String, MutableMap<String, ClassFileMeta>>()
    private val classBytesByModule = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val dexBytesByModule = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val baselineDexFilesByModule = mutableMapOf<String, Map<String, File>>()
    private val baselineDexMembersByClassName = mutableMapOf<String, AdditiveClassMerger.DexClassMembers>()
    private val moduleDirsByPath = mutableMapOf<String, File>()
    private val cumulativeStubsByModule =
        mutableMapOf<String, MutableMap<String, AdditiveClassMerger.DexClassMembers>>()
    private var reloadGeneration = 0

    companion object {
        private const val COMPILE_TIMEOUT_SECONDS = 120L
        private const val D8_TIMEOUT_SECONDS = 30L
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
        private val kotlincErrorRegex = Regex("""e:\s*(?:file://)?([^\s:]+):(\d+):(\d+)\s*(.+)""")
        private val taskNotFoundRegex = Regex(
            """Task\s+'([^']+)'\s+not found in project\s+'([^']+)'"""
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

    fun snapshotModuleClasses(modules: Collection<GradleModule>) {
        baselineDexMembersByClassName.clear()
        for (module in modules) {
            snapshotSingleModule(module)
        }
        val d8Path = d8PathProvider()
        InProcessD8Runner.warmUpAsync(d8Path, d8BaseFlags(d8Path))
    }

    private fun snapshotSingleModule(module: GradleModule) {
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

        val rawDexFiles = scanModuleDexFiles(module)
        baselineDexFilesByModule[module.gradlePath] = rawDexFiles
        if (module.type == ModuleType.ANDROID_APP) {
            indexAppMergedDexFiles(module, modVariant)
        }

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

    private fun indexAppMergedDexFiles(appModule: GradleModule, modVariant: String) {
        val dexRoot = File(appModule.moduleDir, "build/intermediates/dex/$modVariant")
        if (!dexRoot.isDirectory) return
        val mergeDirs = dexRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("mergeExtDex") }
            .orEmpty()
        for (dir in mergeDirs) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "dex" }
                .forEach { dexFile ->
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

    fun buildGradleTasksForModules(changesByModule: Map<GradleModule, List<File>>): List<String> {
        val tasks = mutableListOf<String>()

        for ((module, files) in changesByModule) {
            val modVariant = resolveModuleVariant(module)
            val capVariant = modVariant.replaceFirstChar { it.uppercaseChar() }
            val hasJava = files.any { it.extension.equals("java", ignoreCase = true) }
            val kotlinTask = resolveKotlinCompileTask(module, modVariant, capVariant)
            tasks.add("${module.gradlePath}:$kotlinTask")
            if (hasJava) {
                val javaTask = if (kotlinTask == "compileKotlin") {
                    "compileJava"
                } else {
                    "compile${capVariant}JavaWithJavac"
                }
                tasks.add("${module.gradlePath}:$javaTask")
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
        val gradleFlags = listOf("--console=plain", "-p", projectRoot.absolutePath)

        var compileResult = commandRunner.runCommand(listOf(gradlew) + tasks + gradleFlags, COMPILE_TIMEOUT_SECONDS)
        if (compileResult.isFailure) {
            val rawError = compileResult.exceptionOrNull()?.message.orEmpty()
            val retryTasks = resolveFallbackTasksOnTaskNotFound(tasks, rawError)
            if (retryTasks != null) {
                compileResult = commandRunner.runCommand(
                    listOf(gradlew) + retryTasks + gradleFlags,
                    COMPILE_TIMEOUT_SECONDS
                )
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

        val modifiedClassFiles = collectModifiedClasses(changesByModule)
        if (modifiedClassFiles.isEmpty()) {
            return CompilationOutcome.Success(emptyList(), null)
        }

        return packageDexPayloads(modifiedClassFiles, stagingDir)
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
        val mergedBytes: ByteArray
    )

    private data class D8BatchContext(
        val d8Path: String,
        val baseFlags: List<String>,
        val stagingDir: File,
        val mergedInputsDir: File,
        val perClassOutDir: File,
        val syntheticMarker: String
    )

    private fun collectModifiedClasses(
        changesByModule: Map<GradleModule, List<File>>
    ): List<ModifiedClassEntry> {
        val changedClasses = LinkedHashMap<String, ModifiedClassEntry>()

        for ((module, sourceFiles) in changesByModule) {
            val previousMeta = classMetaByModule[module.gradlePath].orEmpty()
            val previousBytesMap = classBytesByModule[module.gradlePath].orEmpty()
            val currentClasses = scanModuleClassFiles(module)
            val updatedMeta = mutableMapOf<String, ClassFileMeta>()
            val updatedBytesMap = previousBytesMap.toMutableMap()
            val editedBaseNames = sourceFiles.map { it.nameWithoutExtension }.toSet()

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
                val matchesEditedSource = editedBaseNames.any { base ->
                    matchesEditedSourceName(simpleName, base)
                }

                val isModified = isClassModified(
                    prevHash = prevMeta?.sha256,
                    currentHash = hash,
                    hasNoPreviousHashes = previousMeta.isEmpty(),
                    matchesEditedSource = matchesEditedSource
                )
                if (isModified) {
                    val prevBytes = previousBytesMap[className]
                    updatedBytesMap[className] = currentBytes
                    val knownStubs = cumulativeStubsByModule[module.gradlePath]?.get(className)
                    val preMergedBytes = if (knownStubs != null) {
                        AdditiveClassMerger.mergeWithKnownStubs(currentBytes, knownStubs)
                    } else {
                        currentBytes
                    }
                    changedClasses[className] = ModifiedClassEntry(
                        modulePath = module.gradlePath,
                        className = className,
                        originalFile = classFile,
                        previousClassBytes = prevBytes,
                        mergedBytes = preMergedBytes
                    )
                }
            }
            classMetaByModule[module.gradlePath] = updatedMeta
            classBytesByModule[module.gradlePath] = updatedBytesMap
        }

        return changedClasses.values.toList()
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
        val payloads = mutableListOf<ClassDexPayload>()
        val mergedInputsDir = File(stagingDir, "merged_inputs").apply { mkdirs() }
        val perClassOutDir = File(stagingDir, "per_class_out").apply { mkdirs() }
        val ctx = D8BatchContext(
            d8Path = d8,
            baseFlags = baseFlags,
            stagingDir = stagingDir,
            mergedInputsDir = mergedInputsDir,
            perClassOutDir = perClassOutDir,
            syntheticMarker = syntheticMarker
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

        val finalInputFiles = reconcileBatchWithPreviousDexIfNeeded(
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
                if (matchingEntry != null) {
                    val dexBytes = dexFile.readBytes()
                    dexBytesByModule.getOrPut(matchingEntry.modulePath) { mutableMapOf() }[rawDexClassName] = dexBytes
                    persistRedefinedDex(matchingEntry.modulePath, rawDexClassName, dexBytes)
                }
                val dexClassName = rawDexClassName.replace(DEFAULT_SYNTHETIC_MARKER, syntheticMarker)
                val targetDex = File(stagingDir, "redefine_${payloads.size}.dex")
                dexFile.copyTo(targetDex, overwrite = true)
                rewriteSyntheticLambdaMarker(targetDex, syntheticMarker)
                payloads.add(ClassDexPayload(className = dexClassName, dexFile = targetDex))
            }
        } else {
            val fallbackDex = File(perClassOutDir, "classes.dex")
            if (fallbackDex.exists()) {
                for (entry in modifiedClasses) {
                    val targetDex = File(stagingDir, "redefine_${payloads.size}.dex")
                    fallbackDex.copyTo(targetDex, overwrite = true)
                    rewriteSyntheticLambdaMarker(targetDex, syntheticMarker)
                    payloads.add(ClassDexPayload(className = entry.className, dexFile = targetDex))
                }
            }
        }

        val combinedDex = buildCombinedDex(
            ctx = ctx,
            finalInputFiles = finalInputFiles,
            payloads = payloads
        ) ?: return CompilationOutcome.Failure(
            module = "d8",
            diagnostic = "d8 combined dex failed"
        )

        val orderedPayloads = payloads.sortedBy { payload ->
            if (payload.className.contains("ComposableSingletons\$")) 0 else 1
        }

        return CompilationOutcome.Success(
            classPayloads = orderedPayloads,
            combinedDexFile = combinedDex
        )
    }

    private fun buildCombinedDex(
        ctx: D8BatchContext,
        finalInputFiles: List<File>,
        payloads: List<ClassDexPayload>
    ): File? {
        if (payloads.size == 1) {
            val target = File(ctx.stagingDir, "new_classes.dex")
            payloads.first().dexFile.copyTo(target, overwrite = true)
            return target
        }

        val combinedOutDir = File(ctx.stagingDir, "combined").apply { mkdirs() }
        val combinedArgs = ctx.baseFlags + listOf("--output", combinedOutDir.absolutePath) +
            finalInputFiles.map { it.absolutePath }
        val combinedRes = executeD8(ctx.d8Path, combinedArgs)
        if (combinedRes.isFailure) return null

        val combinedFile = File(combinedOutDir, "classes.dex")
        if (!combinedFile.exists()) return null
        val target = File(ctx.stagingDir, "new_classes.dex")
        combinedFile.copyTo(target, overwrite = true)
        rewriteSyntheticLambdaMarker(target, ctx.syntheticMarker)
        return target
    }

    private fun prepareInputClassFile(entry: ModifiedClassEntry, mergedInputsDir: File, index: Int): File {
        val rawBytes = entry.originalFile.readBytes()
        if (entry.mergedBytes.contentEquals(rawBytes)) {
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
    ): List<File> {
        val updatedFiles = initialFiles.toMutableList()
        val reconciledFiles = mutableListOf<File>()

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
            val currentDexBytes = mainDexFile.readBytes()
            val currMembers = AdditiveClassMerger.parseDexMembers(currentDexBytes, entry.className) ?: continue
            val reconciledClassBytes = AdditiveClassMerger.mergeWithPreviousDexMembers(
                prevDex = prevMembers,
                currDex = currMembers,
                currentClassBytes = entry.mergedBytes
            )
            if (reconciledClassBytes.contentEquals(entry.mergedBytes)) continue

            recordCumulativeStubs(entry.modulePath, entry.className, prevMembers)
            classBytesByModule.getOrPut(entry.modulePath) { mutableMapOf() }[entry.className] = reconciledClassBytes
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

        return updatedFiles
    }

    private fun mergeDexMembers(
        existing: AdditiveClassMerger.DexClassMembers,
        incoming: AdditiveClassMerger.DexClassMembers
    ): AdditiveClassMerger.DexClassMembers {
        val fieldMap = (existing.fields + incoming.fields).associateBy { it.name to it.descriptor }
        val methodMap = (existing.methods + incoming.methods).associateBy { it.name to it.descriptor }
        return AdditiveClassMerger.DexClassMembers(
            fields = fieldMap.values.toList(),
            methods = methodMap.values.toList()
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
            classBytesByModule.getOrPut(entry.modulePath) { mutableMapOf() }[entry.className] = merged
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

        baselineDexMembersByClassName[entry.className]?.let { parsed ->
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
        return if (majorVersion >= MIN_VERBOSE_SYNTHETIC_D8_MAJOR) {
            listOf("--min-api", "26", "--verbose-synthetic-names")
        } else {
            listOf("--min-api", "26")
        }
    }

    private fun rewriteSyntheticLambdaMarker(dexFile: File, newMarker: String) {
        val bytes = dexFile.readBytes()
        if (!isValidDexHeader(bytes)) return

        val oldBytes = DEFAULT_SYNTHETIC_MARKER.toByteArray(Charsets.US_ASCII)
        val newBytes = newMarker.toByteArray(Charsets.US_ASCII)
        if (oldBytes.size != newBytes.size || !replaceAsciiBytesInPlace(bytes, oldBytes, newBytes)) return

        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(bytes, DEX_HEADER_MIN_BYTES, bytes.size - DEX_HEADER_MIN_BYTES)
        System.arraycopy(sha1.digest(), 0, bytes, DEX_SIGNATURE_OFFSET, DEX_SIGNATURE_LENGTH)

        val adler = Adler32()
        adler.update(bytes, DEX_SIGNATURE_OFFSET, bytes.size - DEX_SIGNATURE_OFFSET)
        ByteBuffer.wrap(bytes, DEX_CHECKSUM_OFFSET, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(adler.value.toInt())

        dexFile.writeBytes(bytes)
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
        val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
        val wrapper = File(projectRoot, if (isWin) "gradlew.bat" else "gradlew")
        return if (wrapper.exists()) wrapper.absolutePath else "gradle"
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
        return try {
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
            Result.failure(RuntimeException(cause.message ?: "In-process D8 failed", cause))
        } catch (e: Exception) {
            Result.failure(e)
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
