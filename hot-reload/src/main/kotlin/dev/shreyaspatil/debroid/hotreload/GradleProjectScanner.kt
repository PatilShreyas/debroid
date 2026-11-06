package dev.shreyaspatil.debroid.hotreload

import java.io.File

data class GradleProjectLayout(
    val rootDir: File,
    val modules: List<GradleModule>
) {
    private val sortedByDepth = modules.sortedByDescending { it.moduleDir.path.length }

    companion object {
        private val metadataAppIdRegex = Regex(""""applicationId"\s*:\s*"([^"]+)"""")
        private val metadataVariantRegex = Regex(""""variantName"\s*:\s*"([^"]+)"""")
        private val declaredAppIdRegex = Regex("""applicationId\s*=?\s*["']([^"']+)["']""")
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
        val appSegments = appId.lowercase().split('.').toSet()
        val camelBoundaryRegex = Regex("(?=[A-Z])")
        val segmentMatched = debugVariants
            .mapNotNull { candidate ->
                val flavorTokens = candidate.removeSuffix("Debug")
                    .split(camelBoundaryRegex)
                    .map { it.lowercase() }
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
        val last = suffixParts.lastOrNull()?.lowercase() ?: return null
        if (last != "debug" && last != "release") return null
        return suffixParts.mapIndexed { index, part ->
            if (index == 0) part.lowercase() else part.lowercase().replaceFirstChar { it.uppercaseChar() }
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

object GradleProjectScanner {
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

    fun scan(startDir: File): GradleProjectLayout {
        val rootDir = findGradleRoot(startDir.canonicalFile) ?: startDir.canonicalFile
        val customPathByDir = parseCustomProjectDirs(rootDir)

        val discoveredModules = mutableListOf<GradleModule>()
        scanDirectoryRecursively(rootDir, rootDir, customPathByDir, discoveredModules)

        return GradleProjectLayout(
            rootDir = rootDir,
            modules = discoveredModules.sortedBy { it.gradlePath }
        )
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

    private fun scanDirectoryRecursively(
        rootDir: File,
        currentDir: File,
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

            val buildScript = File(currentDir, "build.gradle.kts").takeIf { it.exists() }
                ?: File(currentDir, "build.gradle").takeIf { it.exists() }
            if (buildScript != null) {
                val gradlePath = customPathByDir[canonicalDir] ?: toGradlePath(rootDir, canonicalDir)
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
                scanDirectoryRecursively(rootDir, child, customPathByDir, outModules)
            }
        }
    }

    private fun toGradlePath(rootDir: File, moduleDir: File): String {
        val relative = moduleDir.relativeTo(rootDir).invariantSeparatorsPath
        return ":" + relative.replace('/', ':')
    }

    private fun classifyModuleType(buildScript: File, moduleDir: File): ModuleType {
        if (androidAppIntermediates.any { File(moduleDir, it).isDirectory }) {
            return ModuleType.ANDROID_APP
        }
        val content = buildScript.readText()
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
