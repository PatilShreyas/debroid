package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceChangeTrackerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `detects modified and newly added Kotlin and XML files across modules while ignoring touch-only edits`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\", \":feature:home\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")

        val homeDir = File(tempDir, "feature/home").apply { mkdirs() }
        File(homeDir, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")

        File(appDir, "src/main/java/com/example/MainActivity.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nclass MainActivity")
        }
        val homeKt = File(homeDir, "src/main/kotlin/com/example/HomeScreen.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nfun HomeScreen() {}")
        }

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout)
        tracker.initializeBaseline()

        // Initial poll with no changes
        assertTrue(tracker.pollChanges().isEmpty())

        // Touch file with identical content -> should NOT trigger change
        homeKt.setLastModified(homeKt.lastModified() + 5000L)
        assertTrue(tracker.pollChanges().isEmpty())

        // Modify HomeScreen.kt content
        homeKt.writeText("package com.example\nfun HomeScreen() { println(\"updated\") }")
        homeKt.setLastModified(homeKt.lastModified() + 10_000L)

        val detected = tracker.pollChanges()
        assertFalse(detected.isEmpty())
        assertEquals(1, detected.codeChangesByModule.size)
        val changedModule = detected.codeChangesByModule.keys.first()
        assertEquals(":feature:home", changedModule.gradlePath)
        assertEquals(listOf(homeKt.canonicalFile), detected.codeChangesByModule[changedModule])

        // Before commitChanges, polling again still reports the uncommitted change
        assertFalse(tracker.pollChanges().isEmpty())

        // Commit changes -> next poll is empty
        tracker.commitChanges(detected)
        assertTrue(tracker.pollChanges().isEmpty())

        // Add a resource XML file
        val stringsXml = File(appDir, "src/main/res/values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources></resources>")
        }
        val resChanges = tracker.pollChanges()
        assertEquals(listOf(stringsXml.canonicalFile), resChanges.resourceFiles)
        assertTrue(resChanges.codeChangesByModule.isEmpty())
    }

    @Test
    fun `ignores test source sets and non-code non-resource files`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")

        val unitTest = File(appDir, "src/test/java/com/example/ExampleTest.kt").apply {
            parentFile.mkdirs()
            writeText("class ExampleTest")
        }
        val androidTest = File(appDir, "src/androidTest/kotlin/com/example/AndroidTest.kt").apply {
            parentFile.mkdirs()
            writeText("class AndroidTest")
        }
        val readme = File(appDir, "src/main/resources/notes.txt").apply {
            parentFile.mkdirs()
            writeText("Some notes")
        }

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout)
        tracker.initializeBaseline()

        // Modify test files and text file
        unitTest.writeText("class ExampleTestModified")
        androidTest.writeText("class AndroidTestModified")
        readme.writeText("Some updated notes")

        val detected = tracker.pollChanges()
        assertTrue(detected.isEmpty())
    }

    @Test
    fun `detects AndroidManifest xml changes as resource changes`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")

        val manifest = File(appDir, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText("<manifest package=\"com.example\" />")
        }

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout)
        tracker.initializeBaseline()

        manifest.writeText("<manifest package=\"com.example\"><application /></manifest>")
        val detected = tracker.pollChanges()

        assertFalse(detected.isEmpty())
        assertTrue(detected.codeChangesByModule.isEmpty())
        assertEquals(listOf(manifest.canonicalFile), detected.resourceFiles)
    }
}
