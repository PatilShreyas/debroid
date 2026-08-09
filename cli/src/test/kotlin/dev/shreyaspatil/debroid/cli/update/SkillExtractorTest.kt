package dev.shreyaspatil.debroid.cli.update

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class SkillExtractorTest {

    private val originalErr = System.err
    private val errContent = ByteArrayOutputStream()

    @BeforeEach
    fun setUp() {
        System.setErr(PrintStream(errContent))
    }

    @AfterEach
    fun tearDown() {
        System.setErr(originalErr)
    }

    @Test
    fun `extractSkillsToMaster creates directory if it does not exist`(@TempDir tempDir: File) {
        val targetDir = File(tempDir, "skills/debroid-cli")
        val extractor = SkillExtractor(targetDir)

        extractor.extractSkillsToMaster()

        assertTrue(targetDir.exists())
        assertTrue(targetDir.isDirectory)
    }

    @Test
    fun `extractSkillsToMaster gracefully handles missing resource`(@TempDir tempDir: File) {
        // If /SKILL.md doesn't exist in the test classpath, it should print a warning.
        // If it DOES exist, it shouldn't print a warning but the file should be copied.
        val targetDir = File(tempDir, "skills/debroid-cli")
        val extractor = SkillExtractor(targetDir)

        extractor.extractSkillsToMaster()

        val targetFile = File(targetDir, "SKILL.md")
        val resourceStream = SkillExtractor::class.java.getResourceAsStream("/SKILL.md")

        if (resourceStream == null) {
            assertTrue(errContent.toString().contains("Warning: Embedded resource /SKILL.md was not found in JAR."))
        } else {
            assertTrue(targetFile.exists())
            assertTrue(targetFile.length() > 0)
        }
    }

    @Test
    fun `extractSkillsToMaster gracefully handles filesystem exceptions without crashing`() {
        // Pass a file as the target directory to force an exception when trying to create/write inside it
        val invalidDir = File.createTempFile("invalid_dir", ".tmp")
        try {
            val extractor = SkillExtractor(invalidDir)
            extractor.extractSkillsToMaster()

            // Should catch the exception and print to standard error
            assertTrue(errContent.toString().contains("Warning: Failed to extract debroid skills to"))
        } finally {
            invalidDir.delete()
        }
    }
}
