package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GradleProjectScannerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `scans multi-module project and classifies Android App, Library, and JVM modules`() {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "mega-app"
            include(":app")
            include(":feature:home:ui")
            include(":core:model")
            """.trimIndent()
        )

        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                kotlin("android")
            }
            android {
                namespace = "com.example.mega"
                defaultConfig {
                    applicationId = "com.example.mega"
                }
            }
            """.trimIndent()
        )

        val homeUiDir = File(tempDir, "feature/home/ui").apply { mkdirs() }
        File(homeUiDir, "build.gradle.kts").writeText(
            """
            plugins {
                alias(libs.plugins.android.library)
            }
            """.trimIndent()
        )

        val coreModelDir = File(tempDir, "core/model").apply { mkdirs() }
        File(coreModelDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
            }
            """.trimIndent()
        )

        // Ignored build dir with a fake build.gradle.kts
        val ignoredBuildDir = File(tempDir, "app/build/intermediates").apply { mkdirs() }
        File(ignoredBuildDir, "build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")

        // Scan starting from a nested subdirectory to test upward root discovery
        val layout = GradleProjectScanner.scan(homeUiDir)

        assertEquals(tempDir.canonicalFile, layout.rootDir)
        assertEquals(3, layout.modules.size)

        val modulesByPath = layout.modules.associateBy { it.gradlePath }
        assertEquals(ModuleType.ANDROID_APP, modulesByPath[":app"]?.type)
        assertEquals(ModuleType.ANDROID_LIBRARY, modulesByPath[":feature:home:ui"]?.type)
        assertEquals(ModuleType.KOTLIN_JVM, modulesByPath[":core:model"]?.type)

        val homeSourceFile = File(homeUiDir, "src/main/kotlin/com/example/HomeScreen.kt")
        assertEquals(":feature:home:ui", layout.resolveModuleForFile(homeSourceFile)?.gradlePath)

        val outsideFile = File("/tmp/unrelated/File.kt")
        assertNull(layout.resolveModuleForFile(outsideFile))

        val matchedApp = layout.findAppModule("com.example.mega")
        assertNotNull(matchedApp)
        assertEquals(":app", matchedApp?.gradlePath)
    }

    @Test
    fun `respects custom projectDir mapping in settings gradle`() {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "custom-layout"
            include(":custom-feature")
            project(":custom-feature").projectDir = file("modules/feature-x")
            """.trimIndent()
        )

        val customDir = File(tempDir, "modules/feature-x").apply { mkdirs() }
        File(customDir, "build.gradle").writeText(
            """
            plugins {
                id 'com.android.library'
            }
            """.trimIndent()
        )

        val layout = GradleProjectScanner.scan(tempDir)
        assertEquals(1, layout.modules.size)
        val mod = layout.modules.first()
        assertEquals(":custom-feature", mod.gradlePath)
        assertEquals(customDir.canonicalFile, mod.moduleDir)
        assertEquals(ModuleType.ANDROID_LIBRARY, mod.type)
    }
}
