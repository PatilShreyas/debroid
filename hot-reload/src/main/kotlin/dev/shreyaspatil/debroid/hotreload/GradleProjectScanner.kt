package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

data class GradleProjectLayout(
    val rootDir: File,
    val modules: List<GradleModule>,
    val resolvedGradleConfig: GradleExecutionConfig = GradleExecutionConfig(),
    val scanDiagnostic: String? = null
) {
    private val sortedByDepth = modules.sortedByDescending { it.moduleDir.path.length }

    companion object {
        private val metadataAppIdRegex = Regex(""""applicationId"\s*:\s*"([^"]+)"""")
        private val metadataVariantRegex = Regex(""""variantName"\s*:\s*"([^"]+)"""")
        private val declaredAppIdRegex = Regex("""applicationId\s*=?\s*["']([^"']+)["']""")

        fun findTransitiveDependents(
            changedModules: Collection<GradleModule>,
            allModules: Collection<GradleModule>
        ): List<GradleModule> {
            val changedPaths = changedModules.map { it.gradlePath }.toSet()
            if (changedPaths.isEmpty() || allModules.isEmpty()) return emptyList()

            val moduleByPath = allModules.associateBy { it.gradlePath }
            val reverseDeps = mutableMapOf<String, MutableList<GradleModule>>()
            for (mod in allModules) {
                for (depPath in mod.dependencies) {
                    reverseDeps.getOrPut(depPath) { mutableListOf() }.add(mod)
                }
            }

            val visited = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            changedPaths.forEach { queue.addLast(it) }

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (dependent in reverseDeps[current].orEmpty()) {
                    if (dependent.gradlePath !in changedPaths && visited.add(dependent.gradlePath)) {
                        queue.addLast(dependent.gradlePath)
                    }
                }
            }

            if (visited.isEmpty()) return emptyList()

            // Topological sort of visited dependent modules so intermediate libraries compile before leaf apps
            val ordered = mutableListOf<String>()
            val tempMark = mutableSetOf<String>()
            val permMark = mutableSetOf<String>()

            fun visit(path: String) {
                if (path in permMark || path !in visited) return
                if (!tempMark.add(path)) return
                val mod = moduleByPath[path]
                for (dep in mod?.dependencies.orEmpty()) {
                    if (dep in visited) {
                        visit(dep)
                    }
                }
                tempMark.remove(path)
                permMark.add(path)
                ordered.add(path)
            }

            for (path in visited) {
                visit(path)
            }

            return ordered.mapNotNull { moduleByPath[it] }
        }
    }

    fun findDependentModules(changedModules: Collection<GradleModule>): List<GradleModule> {
        return findTransitiveDependents(changedModules, modules)
    }

    fun resolveModuleForFile(file: File): GradleModule? {
        val canonicalPath = file.canonicalFile.path
        return sortedByDepth.firstOrNull { module ->
            val modPath = module.moduleDir.path
            canonicalPath == modPath || canonicalPath.startsWith("$modPath${File.separator}")
        }
    }

    fun findAppModule(appId: String): GradleModule? {
        val appModules = modules.filter { it.type == ModuleType.ANDROID_APP }
        val metadataMatch = appModules.firstOrNull { module ->
            findMatchingVariantFromMetadata(module.moduleDir, appId) != null
        }
        if (metadataMatch != null) return metadataMatch

        val exactMatch = appModules.firstOrNull { module ->
            val script = findBuildScript(module.moduleDir)?.readText().orEmpty()
            val manifestFile = File(module.moduleDir, "src/main/AndroidManifest.xml")
            val manifestText = if (manifestFile.exists()) manifestFile.readText() else ""
            script.contains(appId) || manifestText.contains(appId)
        }
        if (exactMatch != null) return exactMatch

        val prefixMatch = appModules
            .mapNotNull { module ->
                val baseId = extractDeclaredAppId(module.moduleDir)
                if (baseId != null && (appId == baseId || appId.startsWith("$baseId."))) {
                    module to baseId.length
                } else {
                    null
                }
            }
            .maxByOrNull { it.second }
            ?.first
        return prefixMatch
            ?: appModules.firstOrNull { it.gradlePath == ":app" }
            ?: appModules.firstOrNull()
            ?: modules.firstOrNull()
    }

    fun resolveVariant(appId: String, requestedVariant: String = "debug"): String {
        if (requestedVariant != "debug") return requestedVariant

        val appModule = findAppModule(appId)
        val orderedAppModules = buildList {
            if (appModule != null) add(appModule)
            addAll(modules.filter { it.type == ModuleType.ANDROID_APP && it != appModule })
        }

        val metadataVariant = orderedAppModules.firstNotNullOfOrNull { module ->
            findMatchingVariantFromMetadata(module.moduleDir, appId)
        }
        if (metadataVariant != null) return metadataVariant

        val intermediateVariant = appModule?.let { detectVariantFromIntermediates(it.moduleDir, appId) }
        if (intermediateVariant != null) return intermediateVariant

        val suffixVariant = appModule?.let { inferVariantFromAppIdSuffix(it.moduleDir, appId) }
        return suffixVariant ?: requestedVariant
    }

    private fun findMatchingVariantFromMetadata(moduleDir: File, appId: String): String? {
        val searchRoots = listOf(
            File(moduleDir, "build/intermediates/merged_manifests"),
            File(moduleDir, "build/intermediates/packaged_manifests"),
            File(moduleDir, "build/outputs/apk"),
            File(moduleDir, "build/intermediates/apk")
        )
        return searchRoots
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.name == "output-metadata.json" }
                    .toList()
            }
            .sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { metaFile ->
                val content = runCatching { metaFile.readText() }.getOrNull().orEmpty()
                val foundAppId = metadataAppIdRegex.find(content)?.groupValues?.get(1)
                val foundVariant = metadataVariantRegex.find(content)?.groupValues?.get(1)
                if (foundAppId == appId && !foundVariant.isNullOrBlank()) foundVariant else null
            }
    }

    private fun detectVariantFromIntermediates(moduleDir: File, appId: String): String? {
        val candidateParents = listOf(
            File(moduleDir, "build/intermediates/built_in_kotlinc"),
            File(moduleDir, "build/intermediates/classes"),
            File(moduleDir, "build/tmp/kotlin-classes"),
            File(moduleDir, "build/intermediates/merged_manifests")
        )
        val variantDirs = candidateParents
            .filter { it.isDirectory }
            .flatMap { parent -> parent.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty() }
            .distinct()

        val debugVariants = variantDirs.filter { it.endsWith("Debug") }
        val appSegments = appId.lowercase(Locale.US).split('.').toSet()
        val camelBoundaryRegex = Regex("(?=[A-Z])")
        val segmentMatched = debugVariants
            .mapNotNull { candidate ->
                val flavorTokens = candidate.removeSuffix("Debug")
                    .split(camelBoundaryRegex)
                    .map { it.lowercase(Locale.US) }
                    .filter { it.isNotEmpty() }
                if (flavorTokens.isNotEmpty() && flavorTokens.all { it in appSegments }) {
                    candidate to flavorTokens.size
                } else {
                    null
                }
            }
            .maxByOrNull { it.second }
            ?.first

        return when {
            segmentMatched != null -> segmentMatched
            "debug" in variantDirs -> "debug"
            debugVariants.size == 1 -> debugVariants.first()
            else -> null
        }
    }

    private fun inferVariantFromAppIdSuffix(moduleDir: File, appId: String): String? {
        val baseId = extractDeclaredAppId(moduleDir) ?: return null
        if (!appId.startsWith("$baseId.")) return null
        val suffixParts = appId.removePrefix("$baseId.").split('.').filter { it.isNotBlank() }
        val last = suffixParts.lastOrNull()?.lowercase(Locale.US) ?: return null
        if (last != "debug" && last != "release") return null
        return suffixParts.mapIndexed { index, part ->
            val lower = part.lowercase(Locale.US)
            if (index == 0) {
                lower
            } else {
                lower.replaceFirstChar { it.uppercaseChar() }
            }
        }.joinToString("")
    }

    private fun extractDeclaredAppId(moduleDir: File): String? {
        val script = findBuildScript(moduleDir)?.readText().orEmpty()
        return declaredAppIdRegex.find(script)?.groupValues?.get(1)
    }

    private fun findBuildScript(dir: File): File? {
        return File(dir, "build.gradle.kts").takeIf { it.exists() }
            ?: File(dir, "build.gradle").takeIf { it.exists() }
    }
}

@Suppress("TooManyFunctions")
object GradleProjectScanner {
    private const val GRADLE_QUERY_TIMEOUT_SECONDS = 30L
    private const val MODULE_LINE_PREFIX = "DEBROID_MODULE|"
    private const val INCLUDED_BUILD_LINE_PREFIX = "DEBROID_INCLUDED_BUILD|"

    private val ignoredDirNames = setOf(
        ".git",
        ".gradle",
        "build",
        ".idea",
        ".kotlin",
        "buildSrc",
        "build-logic"
    )

    private val customProjectDirRegex = Regex(
        """project\(\s*["']([^"']+)["']\s*\)\.projectDir\s*=\s*""" +
            """(?:file|File)\(\s*(?:settingsDir\s*,\s*)?["']([^"']+)["']\s*\)"""
    )

    private val includeBuildRegex = Regex(
        """\bincludeBuild(?:\s*\(\s*(?:file\(\s*)?|\s+(?:file\(\s*)?)["']([^"']+)["']\s*\)?\s*\)?(?:\s*\{([^}]*)\})?"""
    )

    private val includeBuildNameBlockRegex = Regex("""\bname\s*=\s*["']([^"']+)["']""")

    private val projectDepRegex = Regex(
        """project\(\s*(?:path\s*=\s*)?["']([^"']+)["']\s*\)"""
    )

    private val typesafeProjectDepRegex = Regex(
        """\bprojects\.([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)*)"""
    )

    private val androidAppSignatures = listOf(
        "com.android.application",
        "android.application",
        "androidApplication",
        "applicationId"
    )

    private val androidLibSignatures = listOf(
        "com.android.library",
        "com.android.kotlin.multiplatform.library",
        "android.library",
        "androidLibrary",
        "android.feature",
        "androidFeature",
        "namespace =",
        "namespace=",
        "android {"
    )

    private val androidAppIntermediates = listOf(
        "build/intermediates/apk",
        "build/intermediates/merged_manifests",
        "build/intermediates/signing_config_versions",
        "build/intermediates/app_metadata"
    )

    private val androidLibIntermediates = listOf(
        "build/intermediates/aar_metadata",
        "build/intermediates/local_only_symbol_list",
        "build/intermediates/compile_r_class_jar",
        "build/intermediates/built_in_kotlinc",
        "build/intermediates/compile_library_classes_jar"
    )

    fun scan(
        startDir: File,
        commandRunner: CommandRunner? = null,
        gradleConfig: GradleExecutionConfig = GradleExecutionConfig()
    ): GradleProjectLayout {
        val rootDir = findGradleRoot(startDir.canonicalFile) ?: startDir.canonicalFile
        var effectiveConfig = if (gradleConfig.javaHome != null) {
            gradleConfig
        } else {
            GradleJvmResolver.resolveForProject(
                projectRoot = rootDir,
                explicitJavaHome = null,
                extraGradleArgs = gradleConfig.extraGradleArgs
            )
        }
        val gradleWrapper = findGradleWrapper(rootDir)
        val useDiskCache = commandRunner == null && gradleWrapper != null
        if (useDiskCache) {
            GradleLayoutDiskCache.tryLoad(rootDir, effectiveConfig)?.let { return it }
        }

        var scanDiagnostic: String? = null
        var queriedSuccessfully = false
        val rawModules = if (commandRunner != null || gradleWrapper != null) {
            val runner = commandRunner ?: DefaultCommandRunner()
            val gradleBin = gradleWrapper?.absolutePath ?: "gradle"
            val queryOutcome = queryModulesFromGradleWithFallbackJdk(rootDir, gradleBin, runner, effectiveConfig)
            effectiveConfig = queryOutcome.effectiveConfig
            scanDiagnostic = queryOutcome.diagnostic
            if (queryOutcome.modules != null) {
                queriedSuccessfully = true
                queryOutcome.modules
            } else {
                scanModulesStatically(rootDir)
            }
        } else {
            scanModulesStatically(rootDir)
        }

        val enrichedModules = populateModuleDependencies(rawModules)
        val layout = GradleProjectLayout(
            rootDir = rootDir,
            modules = enrichedModules.sortedBy { it.gradlePath },
            resolvedGradleConfig = effectiveConfig,
            scanDiagnostic = if (queriedSuccessfully) null else scanDiagnostic
        )
        if (useDiskCache && queriedSuccessfully && layout.modules.isNotEmpty()) {
            GradleLayoutDiskCache.save(layout)
        }
        return layout
    }

    private data class GradleQueryOutcome(
        val modules: List<GradleModule>?,
        val effectiveConfig: GradleExecutionConfig,
        val diagnostic: String?
    )

    private fun queryModulesFromGradleWithFallbackJdk(
        rootDir: File,
        gradleBin: String,
        commandRunner: CommandRunner,
        initialConfig: GradleExecutionConfig
    ): GradleQueryOutcome {
        val firstAttempt = queryModulesFromGradle(rootDir, gradleBin, commandRunner, initialConfig)
        if (firstAttempt.isSuccess) {
            return GradleQueryOutcome(firstAttempt.getOrNull(), initialConfig, null)
        }
        val firstErr = firstAttempt.exceptionOrNull()?.message.orEmpty()
        val fallbackJdk = GradleJvmResolver.findFallbackJavaHomeForError(firstErr, initialConfig.javaHome)
        if (fallbackJdk != null) {
            val retryConfig = initialConfig.copy(javaHome = fallbackJdk)
            val retryAttempt = queryModulesFromGradle(rootDir, gradleBin, commandRunner, retryConfig)
            if (retryAttempt.isSuccess) {
                return GradleQueryOutcome(retryAttempt.getOrNull(), retryConfig, null)
            }
        }
        val conciseDiag = extractConciseGradleFailure(firstErr)
        return GradleQueryOutcome(null, initialConfig, conciseDiag)
    }

    private fun extractConciseGradleFailure(rawError: String): String? {
        if (rawError.isBlank()) return null
        val lines = rawError.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val whatWentWrongIdx = lines.indexOfFirst { it == "* What went wrong:" }
        if (whatWentWrongIdx >= 0) {
            val summary = lines.drop(whatWentWrongIdx + 1)
                .takeWhile { !it.startsWith("* ") && !it.startsWith("BUILD FAILED") }
                .take(3)
                .joinToString(" ")
            if (summary.isNotEmpty()) return summary
        }
        return lines.firstOrNull {
            it.contains("Unsupported class file major version", ignoreCase = true) ||
                it.contains("requires Java", ignoreCase = true) ||
                it.startsWith("FAILURE:") ||
                it.contains("Exception") ||
                it.contains("error:", ignoreCase = true)
        } ?: lines.take(2).joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun findGradleWrapper(rootDir: File): File? {
        val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
        val wrapperName = if (isWin) "gradlew.bat" else "gradlew"
        File(rootDir, wrapperName).takeIf { it.isFile }?.let { return it }
        // In umbrella composite builds where root only has settings.gradle(.kts), check included builds for gradlew
        return parseIncludedBuilds(rootDir).firstNotNullOfOrNull { (_, ibDir) ->
            File(ibDir, wrapperName).takeIf { it.isFile }
        }
    }

    private fun queryModulesFromGradle(
        rootDir: File,
        gradleBin: String,
        commandRunner: CommandRunner,
        gradleConfig: GradleExecutionConfig
    ): Result<List<GradleModule>> {
        val initScript = runCatching {
            Files.createTempFile("debroid_scan_", ".init.gradle").toFile()
        }.getOrElse { return Result.failure(it) }

        try {
            initScript.writeText(buildGradleInitScript())
            val cmd = buildList {
                add(gradleBin)
                add("help")
                add("-I")
                add(initScript.absolutePath)
                add("-q")
                add("--console=plain")
                add("--no-configuration-cache")
                add("-p")
                add(rootDir.absolutePath)
                addAll(gradleConfig.buildJvmAndExtraFlags())
            }
            val env = gradleConfig.buildEnvironment()
            val runRes = if (env.isEmpty()) {
                commandRunner.runCommand(cmd, GRADLE_QUERY_TIMEOUT_SECONDS)
            } else {
                commandRunner.runCommand(cmd, GRADLE_QUERY_TIMEOUT_SECONDS, env)
            }
            return runRes.mapCatching { output ->
                parseGradleQueryOutput(rootDir, output)
                    ?: error("Gradle query produced no discoverable modules")
            }
        } finally {
            initScript.delete()
        }
    }

    private fun buildGradleInitScript(): String = """
        gradle.projectsEvaluated { g ->
            def isIncluded = (g.parent != null)
            def buildRootPath = isIncluded ? g.rootProject.projectDir.canonicalPath : ""
            g.rootProject.allprojects { p ->
                def isPluginBuild = p.plugins.hasPlugin("org.gradle.kotlin.kotlin-dsl") ||
                    p.plugins.hasPlugin("java-gradle-plugin") ||
                    p.plugins.hasPlugin("com.android.test")
                if (!isPluginBuild && p.name != "buildSrc" && p.name != "build-logic") {
                    def isRoot = (p == g.rootProject)
                    def hasChildren = !g.rootProject.subprojects.isEmpty() || !g.includedBuilds.isEmpty()
                    if (!isRoot || !hasChildren || p.file("src").isDirectory()) {
                        def type = "UNKNOWN"
                        if (p.plugins.hasPlugin("com.android.application")) {
                            type = "ANDROID_APP"
                        } else if (p.plugins.hasPlugin("com.android.library") ||
                                   p.plugins.hasPlugin("com.android.kotlin.multiplatform.library") ||
                                   p.plugins.hasPlugin("com.android.dynamic-feature")) {
                            type = "ANDROID_LIBRARY"
                        } else if (p.plugins.hasPlugin("org.jetbrains.kotlin.jvm") ||
                                   p.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
                                   p.plugins.hasPlugin("java-library") ||
                                   p.plugins.hasPlugin("java")) {
                            type = "KOTLIN_JVM"
                        }
                        def deps = new LinkedHashSet<String>()
                        p.configurations.each { conf ->
                            try {
                                conf.dependencies.each { dep ->
                                    if (dep.hasProperty("dependencyProject") && dep.dependencyProject != null) {
                                        def dp = dep.dependencyProject
                                        def dpPath = dp.hasProperty("buildTreePath") ? dp.buildTreePath.toString() : dp.path.toString()
                                        deps.add(dpPath)
                                    } else if (dep.hasProperty("path") && dep.path != null && dep.path.toString().startsWith(":")) {
                                        deps.add(dep.path.toString())
                                    } else if (dep.name != null) {
                                        def grp = (dep.group != null) ? dep.group.toString() : ""
                                        if (!grp.isEmpty()) {
                                            deps.add("COORD:" + grp + ":" + dep.name.toString())
                                        }
                                        deps.add("ARTIFACT:" + dep.name.toString())
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        def treePath = p.hasProperty("buildTreePath") ? p.buildTreePath.toString() : p.path.toString()
                        def localPath = p.path.toString()
                        def coord = ((p.group != null) ? p.group.toString() : "") + ":" + p.name.toString()
                        println("$MODULE_LINE_PREFIX" + treePath + "|" + p.projectDir.canonicalPath + "|" + type + "|" + deps.join(",") + "|" + buildRootPath + "|" + localPath + "|" + coord)
                    }
                }
            }
            g.includedBuilds.each { ib ->
                if (ib.name != "build-logic" && ib.name != "buildSrc") {
                    println("$INCLUDED_BUILD_LINE_PREFIX" + ib.name + "|" + ib.projectDir.canonicalPath)
                }
            }
        }
    """.trimIndent()

    @Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber")
    internal fun parseGradleQueryOutput(rootDir: File, output: String): List<GradleModule>? {
        val modulesByPath = LinkedHashMap<String, GradleModule>()
        val rawDepsByPath = mutableMapOf<String, Set<String>>()
        val coordToPath = mutableMapOf<String, String>()
        val directIncludedBuilds = mutableListOf<Pair<String, File>>()

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith(MODULE_LINE_PREFIX)) {
                val parts = line.removePrefix(MODULE_LINE_PREFIX).split('|')
                if (parts.size >= 3) {
                    val rawPath = parts[0].trim()
                    val gradlePath = if (rawPath.startsWith(":")) rawPath else ":$rawPath"
                    val dir = File(parts[1].trim()).canonicalFile
                    val reportedType = parts[2].trim()
                    val buildScript = findBuildScript(dir)
                    val type = when (reportedType) {
                        "ANDROID_APP" -> ModuleType.ANDROID_APP
                        "ANDROID_LIBRARY" -> ModuleType.ANDROID_LIBRARY
                        "KOTLIN_JVM" -> ModuleType.KOTLIN_JVM
                        else -> classifyModuleType(buildScript, dir)
                    }
                    val deps = if (parts.size >= 4 && parts[3].isNotBlank()) {
                        parts[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    } else {
                        emptySet()
                    }
                    val ibRoot = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).canonicalFile }
                    val localPath = parts.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() } ?: gradlePath
                    val coord = parts.getOrNull(6)?.trim().orEmpty()
                    if (coord.isNotEmpty() && !coord.startsWith(":")) {
                        coordToPath[coord] = gradlePath
                    }
                    modulesByPath[gradlePath] = GradleModule(
                        gradlePath = gradlePath,
                        moduleDir = dir,
                        type = type,
                        includedBuildRoot = ibRoot,
                        localGradlePath = localPath
                    )
                    rawDepsByPath[gradlePath] = deps
                }
            } else if (line.startsWith(INCLUDED_BUILD_LINE_PREFIX)) {
                val parts = line.removePrefix(INCLUDED_BUILD_LINE_PREFIX).split('|')
                if (parts.size >= 2) {
                    val ibName = parts[0].trim()
                    val ibDir = File(parts[1].trim()).canonicalFile
                    if (ibName !in ignoredDirNames && ibDir.isDirectory &&
                        directIncludedBuilds.none { it.second == ibDir }
                    ) {
                        directIncludedBuilds.add(ibName to ibDir)
                    }
                }
            }
        }

        for ((ibName, ibDir) in parseIncludedBuilds(rootDir)) {
            if (directIncludedBuilds.none { it.second == ibDir }) {
                directIncludedBuilds.add(ibName to ibDir)
            }
        }

        val allIncludedBuilds = collectAllIncludedBuildsRecursively(rootDir, directIncludedBuilds)
        val includedBuildPaths = mutableSetOf<String>()
        for ((_, module) in modulesByPath) {
            if (module.includedBuildRoot != null) {
                includedBuildPaths.add(module.gradlePath)
            }
        }
        for ((ibPrefix, ibDir) in allIncludedBuilds) {
            val ibModules = scanIncludedBuildModules(ibPrefix, ibDir)
            for (ibMod in ibModules) {
                if (modulesByPath.values.none { it.moduleDir == ibMod.moduleDir }) {
                    modulesByPath[ibMod.gradlePath] = ibMod
                    includedBuildPaths.add(ibMod.gradlePath)
                }
            }
        }

        if (modulesByPath.isEmpty()) return null

        val artifactNameMap = modulesByPath.values
            .filter { it.gradlePath in includedBuildPaths }
            .associate { mod -> mod.moduleDir.name to mod.gradlePath }
        return modulesByPath.values.map { mod ->
            val rawDeps = rawDepsByPath[mod.gradlePath].orEmpty()
            val resolvedDeps = rawDeps.mapNotNull { token ->
                when {
                    token.startsWith("COORD:") -> {
                        coordToPath[token.removePrefix("COORD:")]?.takeIf { it != mod.gradlePath }
                    }
                    token.startsWith("ARTIFACT:") -> {
                        val artifactName = token.removePrefix("ARTIFACT:")
                        artifactNameMap[artifactName]?.takeIf { it != mod.gradlePath }
                    }
                    token in modulesByPath && token != mod.gradlePath -> token
                    else -> null
                }
            }.toSet()
            mod.copy(dependencies = resolvedDeps)
        }
    }

    private fun collectAllIncludedBuildsRecursively(
        rootDir: File,
        initial: List<Pair<String, File>>
    ): List<Pair<String, File>> {
        val visitedDirs = mutableSetOf(rootDir.canonicalFile)
        val result = mutableListOf<Pair<String, File>>()
        val queue = ArrayDeque<Pair<String, File>>()
        for (pair in initial) {
            if (visitedDirs.add(pair.second)) {
                queue.addLast(pair)
            }
        }
        while (queue.isNotEmpty()) {
            val (prefixName, ibDir) = queue.removeFirst()
            result.add(prefixName to ibDir)
            for ((nestedName, nestedDir) in parseIncludedBuilds(ibDir)) {
                if (visitedDirs.add(nestedDir)) {
                    queue.addLast("$prefixName:$nestedName" to nestedDir)
                }
            }
        }
        return result
    }

    private fun scanModulesStatically(rootDir: File): List<GradleModule> {
        val customPathByDir = parseCustomProjectDirs(rootDir)
        val discoveredModules = mutableListOf<GradleModule>()
        val dynamicChildBuilds = mutableListOf<Pair<String, File>>()
        val rootSettingsText = readSettingsText(rootDir)
        val hasDynamicIncludeBuild = rootSettingsText.contains("includeBuild")

        scanDirectoryRecursively(
            rootDir = rootDir,
            currentDir = rootDir,
            pathPrefix = "",
            includedBuildRoot = null,
            customPathByDir = customPathByDir,
            outModules = discoveredModules,
            dynamicChildBuilds = if (hasDynamicIncludeBuild) dynamicChildBuilds else null
        )

        val seenDirs = discoveredModules.map { it.moduleDir }.toMutableSet()
        val initialIncluded = buildList {
            addAll(parseIncludedBuilds(rootDir))
            for (dyn in dynamicChildBuilds) {
                if (none { it.second == dyn.second }) {
                    add(dyn)
                }
            }
        }
        val allIncluded = collectAllIncludedBuildsRecursively(rootDir, initialIncluded)
        val includedBuildDirs = mutableSetOf<File>()
        for ((ibPrefix, ibDir) in allIncluded) {
            for (ibMod in scanIncludedBuildModules(ibPrefix, ibDir)) {
                if (seenDirs.add(ibMod.moduleDir)) {
                    discoveredModules.add(ibMod)
                    includedBuildDirs.add(ibMod.moduleDir)
                }
            }
        }

        if (discoveredModules.isEmpty()) {
            val rootBuildScript = findBuildScript(rootDir)
            if (rootBuildScript != null && !isPluginBuildScript(rootBuildScript) && File(rootDir, "src").isDirectory) {
                discoveredModules.add(
                    GradleModule(
                        gradlePath = ":",
                        moduleDir = rootDir,
                        type = classifyModuleType(rootBuildScript, rootDir)
                    )
                )
            }
        }

        return populateStaticIncludedBuildDeps(discoveredModules, includedBuildDirs)
    }

    private fun populateStaticIncludedBuildDeps(
        modules: List<GradleModule>,
        includedBuildDirs: Set<File>
    ): List<GradleModule> {
        if (includedBuildDirs.isEmpty()) return modules
        val artifactToPath = modules
            .filter { it.moduleDir in includedBuildDirs }
            .associateBy({ it.moduleDir.name }, { it.gradlePath })
        return modules.map { mod ->
            val scriptText = findBuildScript(mod.moduleDir)?.readText().orEmpty()
            val extraDeps = artifactToPath.entries.mapNotNull { (artifactName, targetPath) ->
                val normKey = normalizeProjectKey(artifactName)
                val referencesArtifact = scriptText.contains(":$artifactName\"") ||
                    scriptText.contains(":$artifactName'") ||
                    scriptText.contains(".$normKey")
                targetPath.takeIf { targetPath != mod.gradlePath && referencesArtifact }
            }
            if (extraDeps.isEmpty()) mod else mod.copy(dependencies = mod.dependencies + extraDeps)
        }
    }

    private fun scanIncludedBuildModules(ibName: String, ibDir: File): List<GradleModule> {
        val cleanName = ibName.trimStart(':')
        if (!ibDir.isDirectory || cleanName in ignoredDirNames || ibDir.name in ignoredDirNames) {
            return emptyList()
        }
        val customPathByDir = parseCustomProjectDirs(ibDir)
        val subModules = mutableListOf<GradleModule>()
        scanDirectoryRecursively(
            rootDir = ibDir,
            currentDir = ibDir,
            pathPrefix = ":$cleanName",
            includedBuildRoot = ibDir,
            customPathByDir = customPathByDir,
            outModules = subModules,
            dynamicChildBuilds = null
        )
        if (subModules.isNotEmpty()) {
            return subModules
        }

        val rootBuildScript = findBuildScript(ibDir)
        if (rootBuildScript != null && !isPluginBuildScript(rootBuildScript)) {
            val type = classifyModuleType(rootBuildScript, ibDir)
            return listOf(
                GradleModule(
                    gradlePath = ":$cleanName",
                    moduleDir = ibDir,
                    type = type,
                    includedBuildRoot = ibDir,
                    localGradlePath = ":"
                )
            )
        }
        return emptyList()
    }

    private fun populateModuleDependencies(modules: List<GradleModule>): List<GradleModule> {
        if (modules.size <= 1) return modules
        val pathSet = modules.map { it.gradlePath }.toSet()
        val normalizedKeyToPath = modules.associate { mod ->
            normalizeProjectKey(mod.gradlePath) to mod.gradlePath
        }

        return modules.map { mod ->
            val scriptText = findBuildScript(mod.moduleDir)?.readText().orEmpty()
            val parsedDeps = LinkedHashSet<String>(mod.dependencies)
            val buildPrefix = if (mod.includedBuildRoot != null && mod.localGradlePath != mod.gradlePath) {
                mod.gradlePath.removeSuffix(mod.localGradlePath.takeIf { it != ":" }.orEmpty())
            } else {
                ""
            }

            for (match in projectDepRegex.findAll(scriptText)) {
                val raw = match.groupValues[1].let { if (it.startsWith(":")) it else ":$it" }
                val candidate = if (buildPrefix.isNotEmpty() && "$buildPrefix$raw" in pathSet) {
                    "$buildPrefix$raw"
                } else {
                    raw
                }
                if (candidate in pathSet && candidate != mod.gradlePath) {
                    parsedDeps.add(candidate)
                }
            }

            for (match in typesafeProjectDepRegex.findAll(scriptText)) {
                val rawAccessor = match.groupValues[1]
                val prefixedKey = if (buildPrefix.isNotEmpty()) {
                    normalizeProjectKey("$buildPrefix:$rawAccessor")
                } else {
                    ""
                }
                val normKey = normalizeProjectKey(rawAccessor)
                val targetPath = normalizedKeyToPath[prefixedKey] ?: normalizedKeyToPath[normKey]
                if (targetPath != null && targetPath != mod.gradlePath) {
                    parsedDeps.add(targetPath)
                }
            }

            mod.copy(dependencies = parsedDeps)
        }
    }

    private fun normalizeProjectKey(pathOrAccessor: String): String {
        return pathOrAccessor
            .trimStart(':')
            .replace(':', '.')
            .split('.')
            .filter { it.isNotEmpty() }
            .joinToString(".") { segment ->
                segment.lowercase(Locale.US).replace("-", "").replace("_", "")
            }
    }

    private fun findGradleRoot(startDir: File): File? {
        var current: File? = if (startDir.isDirectory) startDir else startDir.parentFile
        var nearestRoot: File? = null
        while (current != null && nearestRoot == null) {
            if (hasSettingsScript(current)) {
                nearestRoot = current
            } else {
                current = current.parentFile
            }
        }
        var activeRoot = nearestRoot ?: return null
        var ancestor = activeRoot.parentFile
        while (ancestor != null) {
            if (hasSettingsScript(ancestor) && parentReferencesChildBuild(ancestor, activeRoot)) {
                activeRoot = ancestor
            }
            ancestor = ancestor.parentFile
        }
        return activeRoot
    }

    private fun parentReferencesChildBuild(parentDir: File, childDir: File): Boolean {
        val included = parseIncludedBuilds(parentDir)
        if (included.any { it.second == childDir }) return true
        val settingsText = readSettingsText(parentDir)
        val relPath = runCatching { childDir.relativeTo(parentDir).invariantSeparatorsPath }.getOrNull() ?: return false
        return settingsText.contains(relPath) || settingsText.contains(childDir.name)
    }

    private fun readSettingsText(dir: File): String {
        val settingsFile = File(dir, "settings.gradle.kts").takeIf { it.isFile }
            ?: File(dir, "settings.gradle").takeIf { it.isFile }
            ?: return ""
        return runCatching { settingsFile.readText() }.getOrDefault("")
    }

    private fun hasSettingsScript(dir: File): Boolean {
        return File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()
    }

    private fun findBuildScript(dir: File): File? {
        return File(dir, "build.gradle.kts").takeIf { it.exists() }
            ?: File(dir, "build.gradle").takeIf { it.exists() }
    }

    private fun isPluginBuildScript(buildScript: File): Boolean {
        val text = runCatching { buildScript.readText() }.getOrNull().orEmpty()
        return text.contains("`kotlin-dsl`") ||
            text.contains("kotlin-dsl") ||
            text.contains("java-gradle-plugin") ||
            text.contains("gradlePlugin {") ||
            text.contains("com.android.test") ||
            text.contains(".android.test)")
    }

    private fun parseCustomProjectDirs(rootDir: File): Map<File, String> {
        val text = readSettingsText(rootDir)
        if (text.isEmpty()) return emptyMap()
        val result = mutableMapOf<File, String>()
        for (match in customProjectDirRegex.findAll(text)) {
            val gradlePath = match.groupValues[1].let { if (it.startsWith(":")) it else ":$it" }
            val relDir = match.groupValues[2]
            val targetDir = File(rootDir, relDir).canonicalFile
            result[targetDir] = gradlePath
        }
        return result
    }

    private fun parseIncludedBuilds(rootDir: File): List<Pair<String, File>> {
        val rawText = readSettingsText(rootDir)
        if (rawText.isEmpty()) return emptyList()
        val strippedText = stripNamedBlock(rawText, "pluginManagement")
        val result = mutableListOf<Pair<String, File>>()
        for (match in includeBuildRegex.findAll(strippedText)) {
            val relPath = match.groupValues[1].trim()
            val blockBody = match.groupValues.getOrNull(2).orEmpty()
            val explicitName = includeBuildNameBlockRegex.find(blockBody)?.groupValues?.get(1)?.trim()
            val targetDir = File(rootDir, relPath).canonicalFile
            val buildName = explicitName?.takeIf { it.isNotEmpty() } ?: targetDir.name
            val allowedNames = buildName !in ignoredDirNames && targetDir.name !in ignoredDirNames
            if (allowedNames && targetDir.isDirectory && result.none { it.second == targetDir }) {
                result.add(buildName to targetDir)
            }
        }
        return result
    }

    private fun stripNamedBlock(text: String, blockName: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val regex = Regex("""\b$blockName\s*\{""")
        while (i < text.length) {
            val match = regex.find(text, i)
            if (match == null) {
                sb.append(text, i, text.length)
                break
            }
            sb.append(text, i, match.range.first)
            var depth = 1
            var j = match.range.last + 1
            while (j < text.length && depth > 0) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            i = j
        }
        return sb.toString()
    }

    @Suppress("LongParameterList")
    private fun scanDirectoryRecursively(
        rootDir: File,
        currentDir: File,
        pathPrefix: String,
        includedBuildRoot: File?,
        customPathByDir: Map<File, String>,
        outModules: MutableList<GradleModule>,
        dynamicChildBuilds: MutableList<Pair<String, File>>?
    ) {
        if (currentDir != rootDir) {
            val canonicalDir = currentDir.canonicalFile
            if (currentDir.name in ignoredDirNames) return
            if (hasSettingsScript(currentDir) && canonicalDir !in customPathByDir) {
                dynamicChildBuilds?.add(currentDir.name to canonicalDir)
                return
            }

            val buildScript = findBuildScript(currentDir)
            if (buildScript != null && !isPluginBuildScript(buildScript)) {
                val basePath = customPathByDir[canonicalDir] ?: toGradlePath(rootDir, canonicalDir)
                val gradlePath = "$pathPrefix$basePath"
                val moduleType = classifyModuleType(buildScript, canonicalDir)
                outModules.add(
                    GradleModule(
                        gradlePath = gradlePath,
                        moduleDir = canonicalDir,
                        type = moduleType,
                        includedBuildRoot = includedBuildRoot,
                        localGradlePath = basePath
                    )
                )
            }
        }

        val children = currentDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && child.name !in ignoredDirNames) {
                scanDirectoryRecursively(
                    rootDir = rootDir,
                    currentDir = child,
                    pathPrefix = pathPrefix,
                    includedBuildRoot = includedBuildRoot,
                    customPathByDir = customPathByDir,
                    outModules = outModules,
                    dynamicChildBuilds = dynamicChildBuilds
                )
            }
        }
    }

    private fun toGradlePath(rootDir: File, moduleDir: File): String {
        val relative = moduleDir.relativeTo(rootDir).invariantSeparatorsPath
        return ":" + relative.replace('/', ':')
    }

    private fun classifyModuleType(buildScript: File?, moduleDir: File): ModuleType {
        if (androidAppIntermediates.any { File(moduleDir, it).isDirectory }) {
            return ModuleType.ANDROID_APP
        }
        val content = buildScript?.takeIf { it.isFile }?.readText().orEmpty()
        if (androidAppSignatures.any { content.contains(it) }) {
            return ModuleType.ANDROID_APP
        }

        if (androidLibIntermediates.any { File(moduleDir, it).isDirectory }) {
            return ModuleType.ANDROID_LIBRARY
        }
        val hasAndroidSourceLayout = File(moduleDir, "src/main/AndroidManifest.xml").exists() ||
            File(moduleDir, "src/main/res").isDirectory ||
            File(moduleDir, "src/androidMain").isDirectory
        if (hasAndroidSourceLayout || androidLibSignatures.any { content.contains(it) }) {
            return ModuleType.ANDROID_LIBRARY
        }

        return ModuleType.KOTLIN_JVM
    }
}

private object GradleLayoutDiskCache {
    private const val CACHE_VERSION = "v2"
    private const val HASH_BYTES = 8
    private const val MODULE_PREFIX = "MODULE|"
    private const val EXPECTED_MODULE_PARTS = 6

    fun tryLoad(rootDir: File, gradleConfig: GradleExecutionConfig): GradleProjectLayout? = runCatching {
        val cacheFile = cacheFileFor(rootDir, gradleConfig)
        if (!cacheFile.isFile) return null
        val lines = cacheFile.readLines(StandardCharsets.UTF_8)
        val header = lines.firstOrNull().orEmpty()
        if (!header.startsWith("fingerprint=")) return null
        val expectedFp = header.removePrefix("fingerprint=")

        val modules = ArrayList<GradleModule>(lines.size)
        for (line in lines.drop(1)) {
            if (!line.startsWith(MODULE_PREFIX)) continue
            val parts = line.removePrefix(MODULE_PREFIX).split('|')
            if (parts.size < EXPECTED_MODULE_PARTS) return null
            val path = parts[0]
            val dir = File(parts[1])
            if (!dir.isDirectory) return null
            val type = ModuleType.valueOf(parts[2])
            val deps = if (parts[3].isBlank()) emptySet() else parts[3].split(',').toSet()
            val ibRoot = parts[4].takeIf { it.isNotEmpty() }?.let(::File)
            val localPath = parts[5].ifEmpty { path }
            modules.add(GradleModule(path, dir, type, deps, ibRoot, localPath))
        }
        if (modules.isEmpty()) return null

        val actualFp = computeBuildScriptsFingerprint(rootDir, modules, gradleConfig)
        if (actualFp != expectedFp) return null
        GradleProjectLayout(
            rootDir = rootDir,
            modules = modules,
            resolvedGradleConfig = gradleConfig
        )
    }.getOrNull()

    fun save(layout: GradleProjectLayout) {
        runCatching {
            val cacheFile = cacheFileFor(layout.rootDir, layout.resolvedGradleConfig)
            cacheFile.parentFile?.mkdirs()
            val fp = computeBuildScriptsFingerprint(layout.rootDir, layout.modules, layout.resolvedGradleConfig)
            val sb = StringBuilder()
            sb.append("fingerprint=").append(fp).append('\n')
            for (mod in layout.modules) {
                sb.append(MODULE_PREFIX)
                    .append(mod.gradlePath).append('|')
                    .append(mod.moduleDir.canonicalPath).append('|')
                    .append(mod.type.name).append('|')
                    .append(mod.dependencies.sorted().joinToString(",")).append('|')
                    .append(mod.includedBuildRoot?.canonicalPath.orEmpty()).append('|')
                    .append(mod.localGradlePath)
                    .append('\n')
            }
            cacheFile.writeText(sb.toString(), StandardCharsets.UTF_8)
        }
    }

    private fun cacheFileFor(rootDir: File, gradleConfig: GradleExecutionConfig): File {
        val key = "${rootDir.canonicalPath}|${gradleConfig.cacheKeySuffix()}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(Locale.US, it) }
        return File(HotReloadSyncCoordinator.defaultRegistryDir(), "layout-${rootDir.name}-$hash.cache")
    }

    private fun computeBuildScriptsFingerprint(
        rootDir: File,
        modules: List<GradleModule>,
        gradleConfig: GradleExecutionConfig
    ): String {
        val includedRoots = modules.mapNotNull { it.includedBuildRoot }.distinct()
        val trackedFiles = buildList {
            add(File(rootDir, "settings.gradle.kts"))
            add(File(rootDir, "settings.gradle"))
            add(File(rootDir, "gradle.properties"))
            add(File(rootDir, "build.gradle.kts"))
            add(File(rootDir, "build.gradle"))
            add(File(rootDir, "gradle/libs.versions.toml"))
            for (ibRoot in includedRoots) {
                add(File(ibRoot, "settings.gradle.kts"))
                add(File(ibRoot, "settings.gradle"))
                add(File(ibRoot, "gradle.properties"))
            }
            for (mod in modules) {
                add(File(mod.moduleDir, "build.gradle.kts"))
                add(File(mod.moduleDir, "build.gradle"))
            }
        }
        val sb = StringBuilder()
        sb.append(CACHE_VERSION).append('|')
            .append(resolveBinaryCodeSourceStamp()).append('|')
            .append(gradleConfig.cacheKeySuffix()).append('|')
        for (file in trackedFiles) {
            if (file.isFile) {
                sb.append(file.path).append(':')
                    .append(file.lastModified()).append(':')
                    .append(file.length()).append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun resolveBinaryCodeSourceStamp(): String = runCatching {
        val url = GradleProjectScanner::class.java.protectionDomain?.codeSource?.location ?: return@runCatching ""
        val file = File(url.toURI())
        if (file.exists()) "${file.canonicalPath}:${file.lastModified()}:${file.length()}" else ""
    }.getOrDefault("")
}
