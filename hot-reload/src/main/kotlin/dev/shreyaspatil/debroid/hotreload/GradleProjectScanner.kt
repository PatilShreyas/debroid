package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.File
import java.nio.file.Files
import java.util.Locale

data class GradleProjectLayout(
    val rootDir: File,
    val modules: List<GradleModule>
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
        """project\(\s*["']([^"']+)["']\s*\)\.projectDir\s*=\s*file\(\s*["']([^"']+)["']\s*\)"""
    )

    private val includeBuildRegex = Regex(
        """includeBuild\(\s*["']([^"']+)["']\s*\)"""
    )

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
        commandRunner: CommandRunner? = null
    ): GradleProjectLayout {
        val rootDir = findGradleRoot(startDir.canonicalFile) ?: startDir.canonicalFile
        val gradleWrapper = findGradleWrapper(rootDir)

        val rawModules = if (commandRunner != null || gradleWrapper != null) {
            val runner = commandRunner ?: DefaultCommandRunner()
            val gradleBin = gradleWrapper?.absolutePath ?: "gradle"
            queryModulesFromGradle(rootDir, gradleBin, runner) ?: scanModulesStatically(rootDir)
        } else {
            scanModulesStatically(rootDir)
        }

        val enrichedModules = populateModuleDependencies(rawModules)
        return GradleProjectLayout(
            rootDir = rootDir,
            modules = enrichedModules.sortedBy { it.gradlePath }
        )
    }

    private fun findGradleWrapper(rootDir: File): File? {
        val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
        val wrapper = File(rootDir, if (isWin) "gradlew.bat" else "gradlew")
        return wrapper.takeIf { it.isFile }
    }

    private fun queryModulesFromGradle(
        rootDir: File,
        gradleBin: String,
        commandRunner: CommandRunner
    ): List<GradleModule>? {
        val initScript = runCatching {
            Files.createTempFile("debroid_scan_", ".init.gradle").toFile()
        }.getOrNull() ?: return null

        try {
            initScript.writeText(buildGradleInitScript())
            val cmd = listOf(
                gradleBin,
                "help",
                "-I",
                initScript.absolutePath,
                "-q",
                "--console=plain",
                "--no-configuration-cache",
                "-p",
                rootDir.absolutePath
            )
            val output = commandRunner.runCommand(cmd, GRADLE_QUERY_TIMEOUT_SECONDS).getOrNull() ?: return null
            return parseGradleQueryOutput(rootDir, output)
        } finally {
            initScript.delete()
        }
    }

    private fun buildGradleInitScript(): String = """
        gradle.projectsEvaluated { g ->
            if (g.parent == null) {
                g.rootProject.allprojects { p ->
                    def isPluginBuild = p.plugins.hasPlugin("org.gradle.kotlin.kotlin-dsl") ||
                        p.plugins.hasPlugin("java-gradle-plugin") ||
                        p.plugins.hasPlugin("com.android.test")
                    if (!isPluginBuild && p.name != "buildSrc" && p.name != "build-logic") {
                        def isRoot = (p == g.rootProject)
                        def hasSubprojects = !g.rootProject.subprojects.isEmpty()
                        if (!isRoot || !hasSubprojects || p.file("src").isDirectory()) {
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
                                            deps.add(dep.dependencyProject.path.toString())
                                        } else if (dep.hasProperty("path") && dep.path != null && dep.path.toString().startsWith(":")) {
                                            deps.add(dep.path.toString())
                                        } else if (dep.name != null) {
                                            deps.add("ARTIFACT:" + dep.name.toString())
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                            def pathStr = p.hasProperty("buildTreePath") ? p.buildTreePath.toString() : p.path.toString()
                            println("$MODULE_LINE_PREFIX" + pathStr + "|" + p.projectDir.canonicalPath + "|" + type + "|" + deps.join(","))
                        }
                    }
                }
                g.includedBuilds.each { ib ->
                    if (ib.name != "build-logic" && ib.name != "buildSrc") {
                        println("$INCLUDED_BUILD_LINE_PREFIX" + ib.name + "|" + ib.projectDir.canonicalPath)
                    }
                }
            }
        }
    """.trimIndent()

    internal fun parseGradleQueryOutput(rootDir: File, output: String): List<GradleModule>? {
        val modulesByPath = LinkedHashMap<String, GradleModule>()
        val rawDepsByPath = mutableMapOf<String, Set<String>>()
        val includedBuilds = mutableListOf<Pair<String, File>>()

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
                    modulesByPath[gradlePath] = GradleModule(gradlePath, dir, type)
                    rawDepsByPath[gradlePath] = deps
                }
            } else if (line.startsWith(INCLUDED_BUILD_LINE_PREFIX)) {
                val parts = line.removePrefix(INCLUDED_BUILD_LINE_PREFIX).split('|')
                if (parts.size >= 2) {
                    val ibName = parts[0].trim()
                    val ibDir = File(parts[1].trim()).canonicalFile
                    if (ibName !in ignoredDirNames && ibDir.isDirectory) {
                        includedBuilds.add(ibName to ibDir)
                    }
                }
            }
        }

        // Also check settings.gradle(.kts) for any includeBuild declarations
        for ((ibName, ibDir) in parseIncludedBuilds(rootDir)) {
            if (includedBuilds.none { it.second == ibDir }) {
                includedBuilds.add(ibName to ibDir)
            }
        }

        val includedBuildPaths = mutableSetOf<String>()
        for ((ibName, ibDir) in includedBuilds) {
            val ibModules = scanIncludedBuildModules(ibName, ibDir)
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
                if (token.startsWith("ARTIFACT:")) {
                    val artifactName = token.removePrefix("ARTIFACT:")
                    artifactNameMap[artifactName]?.takeIf { it != mod.gradlePath }
                } else if (token in modulesByPath && token != mod.gradlePath) {
                    token
                } else {
                    null
                }
            }.toSet()
            mod.copy(dependencies = resolvedDeps)
        }
    }

    private fun scanModulesStatically(rootDir: File): List<GradleModule> {
        val customPathByDir = parseCustomProjectDirs(rootDir)
        val discoveredModules = mutableListOf<GradleModule>()
        scanDirectoryRecursively(
            rootDir = rootDir,
            currentDir = rootDir,
            pathPrefix = "",
            customPathByDir = customPathByDir,
            outModules = discoveredModules
        )

        val seenDirs = discoveredModules.map { it.moduleDir }.toMutableSet()
        val includedBuildDirs = mutableSetOf<File>()
        for ((ibName, ibDir) in parseIncludedBuilds(rootDir)) {
            for (ibMod in scanIncludedBuildModules(ibName, ibDir)) {
                if (seenDirs.add(ibMod.moduleDir)) {
                    discoveredModules.add(ibMod)
                    includedBuildDirs.add(ibMod.moduleDir)
                }
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
                val referencesArtifact = scriptText.contains(":$artifactName\"") ||
                    scriptText.contains(":$artifactName'")
                targetPath.takeIf { targetPath != mod.gradlePath && referencesArtifact }
            }
            if (extraDeps.isEmpty()) mod else mod.copy(dependencies = mod.dependencies + extraDeps)
        }
    }

    private fun scanIncludedBuildModules(ibName: String, ibDir: File): List<GradleModule> {
        if (!ibDir.isDirectory || ibName in ignoredDirNames) return emptyList()
        val customPathByDir = parseCustomProjectDirs(ibDir)
        val subModules = mutableListOf<GradleModule>()
        scanDirectoryRecursively(
            rootDir = ibDir,
            currentDir = ibDir,
            pathPrefix = ":$ibName",
            customPathByDir = customPathByDir,
            outModules = subModules
        )
        if (subModules.isNotEmpty()) {
            return subModules
        }

        val rootBuildScript = findBuildScript(ibDir)
        if (rootBuildScript != null && !isPluginBuildScript(rootBuildScript)) {
            val type = classifyModuleType(rootBuildScript, ibDir)
            return listOf(
                GradleModule(
                    gradlePath = ":$ibName",
                    moduleDir = ibDir,
                    type = type
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

            for (match in projectDepRegex.findAll(scriptText)) {
                val raw = match.groupValues[1].let { if (it.startsWith(":")) it else ":$it" }
                if (raw in pathSet && raw != mod.gradlePath) {
                    parsedDeps.add(raw)
                }
            }

            for (match in typesafeProjectDepRegex.findAll(scriptText)) {
                val rawAccessor = match.groupValues[1]
                val normKey = normalizeProjectKey(rawAccessor)
                val targetPath = normalizedKeyToPath[normKey]
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
        var outermostRoot: File? = null
        while (current != null) {
            if (hasSettingsScript(current)) {
                outermostRoot = current
            }
            current = current.parentFile
        }
        return outermostRoot
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
        val settingsFile = File(rootDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(rootDir, "settings.gradle").takeIf { it.exists() }
            ?: return emptyMap()

        val text = settingsFile.readText()
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
        val settingsFile = File(rootDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(rootDir, "settings.gradle").takeIf { it.exists() }
            ?: return emptyList()

        val rawText = settingsFile.readText()
        val strippedText = stripNamedBlock(rawText, "pluginManagement")
        val result = mutableListOf<Pair<String, File>>()
        for (match in includeBuildRegex.findAll(strippedText)) {
            val relPath = match.groupValues[1].trim()
            val targetDir = File(rootDir, relPath).canonicalFile
            val buildName = targetDir.name
            if (buildName !in ignoredDirNames && targetDir.isDirectory) {
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

    private fun scanDirectoryRecursively(
        rootDir: File,
        currentDir: File,
        pathPrefix: String,
        customPathByDir: Map<File, String>,
        outModules: MutableList<GradleModule>
    ) {
        if (currentDir != rootDir) {
            val canonicalDir = currentDir.canonicalFile
            if (currentDir.name in ignoredDirNames ||
                (hasSettingsScript(currentDir) && canonicalDir !in customPathByDir)
            ) {
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
                        type = moduleType
                    )
                )
            }
        }

        val children = currentDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && child.name !in ignoredDirNames) {
                scanDirectoryRecursively(rootDir, child, pathPrefix, customPathByDir, outModules)
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
