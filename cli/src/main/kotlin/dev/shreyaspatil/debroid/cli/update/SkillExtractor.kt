package dev.shreyaspatil.debroid.cli.update

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Handles extracting the embedded AI Agent skill instructions to a master path.
 */
class SkillExtractor(
    private val masterSkillDir: File = File(
        System.getenv("HOME") ?: System.getProperty("user.home"),
        ".debroid/skills/debroid-cli"
    )
) {

    fun extractSkillsToMaster() {
        try {
            if (!masterSkillDir.exists()) {
                masterSkillDir.mkdirs()
            }

            // Extract the main SKILL.md and reference guides
            skillResources.forEach { relativePath ->
                val targetFile = File(masterSkillDir, relativePath)
                targetFile.parentFile?.mkdirs()
                extractResource("/$relativePath", targetFile)
            }
        } catch (e: Exception) {
            // Silently fail if extraction fails; don't break the CLI execution
            System.err.println(
                "Warning: Failed to extract debroid skills to ${masterSkillDir.absolutePath}: ${e.message}"
            )
        }
    }

    private fun extractResource(resourcePath: String, targetFile: File) {
        val inputStream: InputStream? = SkillExtractor::class.java.getResourceAsStream(resourcePath)
        if (inputStream != null) {
            inputStream.use { input ->
                Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            System.err.println("Warning: Embedded resource $resourcePath was not found in JAR.")
        }
    }

    companion object {
        private val skillResources = listOf(
            "SKILL.md",
            "references/hot-reload.md",
            "references/reporting-feedback.md"
        )
    }
}
