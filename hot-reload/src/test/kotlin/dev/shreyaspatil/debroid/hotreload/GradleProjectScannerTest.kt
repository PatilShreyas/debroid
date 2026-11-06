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

    @Test
    fun `resolves flavored variant and app module from output-metadata json and appId suffix`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            plugins {
                alias(libs.plugins.nowinandroid.android.application)
            }
            android {
                defaultConfig {
                    applicationId = "com.google.samples.apps.nowinandroid"
                }
            }
            """.trimIndent()
        )
        val metaFile = File(
            appDir,
            "build/intermediates/merged_manifests/demoDebug/processDemoDebugManifest/output-metadata.json"
        ).apply { parentFile.mkdirs() }
        metaFile.writeText(
            """
            {
              "applicationId": "com.google.samples.apps.nowinandroid.demo.debug",
              "variantName": "demoDebug"
            }
            """.trimIndent()
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val appModule = layout.findAppModule("com.google.samples.apps.nowinandroid.demo.debug")
        assertNotNull(appModule)
        assertEquals(":app", appModule?.gradlePath)
        assertEquals(
            "demoDebug",
            layout.resolveVariant("com.google.samples.apps.nowinandroid.demo.debug", "debug")
        )
        assertEquals(
            "prodDebug",
            layout.resolveVariant("com.google.samples.apps.nowinandroid.demo.debug", "prodDebug")
        )
    }

    @Test
    fun `skips standalone composite builds and classifies convention-plugin modules and multi-dimension flavors`() {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            pluginManagement { includeBuild("build-logic") }
            include(":app", ":feature:checkout")
            """.trimIndent()
        )
        val buildLogicDir = File(tempDir, "build-logic").apply { mkdirs() }
        File(buildLogicDir, "settings.gradle.kts").writeText("rootProject.name = \"build-logic\"")
        val conventionDir = File(buildLogicDir, "convention").apply { mkdirs() }
        File(conventionDir, "build.gradle.kts").writeText("plugins { `kotlin-dsl` }")

        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { alias(libs.plugins.acme.app) }")
        File(appDir, "build/intermediates/built_in_kotlinc/freeStagingDebug").mkdirs()
        File(appDir, "build/intermediates/merged_manifests/freeStagingDebug").mkdirs()

        val checkoutDir = File(tempDir, "feature/checkout").apply { mkdirs() }
        File(checkoutDir, "build.gradle.kts").writeText("plugins { alias(libs.plugins.acme.feature) }")
        File(checkoutDir, "build/intermediates/aar_metadata").mkdirs()

        // Scan starting from inside build-logic to also verify outermost root resolution
        val layout = GradleProjectScanner.scan(conventionDir)
        assertEquals(tempDir.canonicalFile, layout.rootDir)
        val modulePaths = layout.modules.map { it.gradlePath }
        assertEquals(listOf(":app", ":feature:checkout"), modulePaths)

        val byPath = layout.modules.associateBy { it.gradlePath }
        assertEquals(ModuleType.ANDROID_APP, byPath[":app"]?.type)
        assertEquals(ModuleType.ANDROID_LIBRARY, byPath[":feature:checkout"]?.type)
        assertEquals(
            "freeStagingDebug",
            layout.resolveVariant("com.acme.shop.free.staging.debug", "debug")
        )
    }
}
