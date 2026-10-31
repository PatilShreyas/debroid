package dev.shreyaspatil.debroid.hotreload

import java.io.File

data class GradleProjectLayout(
    val rootDir: File,
    val modules: List<GradleModule>
) {
    private val sortedByDepth = modules.sortedByDescending { it.moduleDir.path.length }

    fun resolveModuleForFile(file: File): GradleModule? {
        val canonicalPath = file.canonicalFile.path
        return sortedByDepth.firstOrNull { module ->
            val modPath = module.moduleDir.path
            canonicalPath == modPath || canonicalPath.startsWith("$modPath${File.separator}")
        }
    }

    fun findAppModule(appId: String): GradleModule? {
        val appModules = modules.filter { it.type == ModuleType.ANDROID_APP }
        val exactMatch = appModules.firstOrNull { module ->
            val script = findBuildScript(module.moduleDir)?.readText().orEmpty()
            val manifestFile = File(module.moduleDir, "src/main/AndroidManifest.xml")
            val manifestText = if (manifestFile.exists()) manifestFile.readText() else ""
            script.contains(appId) || manifestText.contains(appId)
        }
        return exactMatch ?: appModules.firstOrNull() ?: modules.firstOrNull()
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
        "buildSrc"
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
        "android.library",
        "androidLibrary",
        "android {"
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
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() || File(current, "settings.gradle").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
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
        if (currentDir != rootDir && currentDir.name in ignoredDirNames) {
            return
        }

        if (currentDir != rootDir) {
            val buildScript = File(currentDir, "build.gradle.kts").takeIf { it.exists() }
                ?: File(currentDir, "build.gradle").takeIf { it.exists() }
            if (buildScript != null) {
                val canonicalDir = currentDir.canonicalFile
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
        val content = buildScript.readText()
        if (androidAppSignatures.any { content.contains(it) }) {
            return ModuleType.ANDROID_APP
        }

        val hasManifest = File(moduleDir, "src/main/AndroidManifest.xml").exists()
        if (hasManifest || androidLibSignatures.any { content.contains(it) }) {
            return ModuleType.ANDROID_LIBRARY
        }

        return ModuleType.KOTLIN_JVM
    }
}
