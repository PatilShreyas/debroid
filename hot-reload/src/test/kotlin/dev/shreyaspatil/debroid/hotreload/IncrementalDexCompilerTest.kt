package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IncrementalDexCompilerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `builds targeted Gradle tasks for Android and JVM modules`() {
        val compiler = IncrementalDexCompiler(projectRoot = tempDir, variant = "freeDebug")
        val appModule = GradleModule(":app", File(tempDir, "app"), ModuleType.ANDROID_APP)
        val libModule = GradleModule(":feature:home", File(tempDir, "feature/home"), ModuleType.ANDROID_LIBRARY)
        val jvmModule = GradleModule(":core:model", File(tempDir, "core/model"), ModuleType.KOTLIN_JVM)

        val tasks = compiler.buildGradleTasksForModules(
            mapOf(
                appModule to listOf(File("MainActivity.kt")),
                libModule to listOf(File("HomeScreen.kt"), File("LegacyHelper.java")),
                jvmModule to listOf(File("User.kt"))
            )
        )

        assertEquals(
            listOf(
                ":app:compileFreeDebugKotlin",
                ":feature:home:compileFreeDebugKotlin",
                ":feature:home:compileFreeDebugJavaWithJavac",
                ":core:model:compileKotlin"
            ),
            tasks
        )
    }

    @Test
    fun `diffs modified class files and invokes d8 to produce single-class and combined dex payloads`() {
        val homeDir = File(tempDir, "feature/home").apply { mkdirs() }
        val classesDir = File(homeDir, "build/tmp/kotlin-classes/debug/com/example/home").apply { mkdirs() }
        File(classesDir, "Unchanged.class").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val modifiedClass = File(classesDir, "HomeScreenKt.class").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val module = GradleModule(":feature:home", homeDir, ModuleType.ANDROID_LIBRARY)
        val runner = mockk<CommandRunner>()
        val commands = mutableListOf<List<String>>()

        every { runner.runCommand(capture(commands), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                // Simulate Kotlin compiler updating HomeScreenKt.class and adding a new lambda class
                modifiedClass.writeBytes(byteArrayOf(4, 5, 6, 7))
                File(classesDir, "HomeScreenKt\$HomeScreen\$1.class").writeBytes(byteArrayOf(9, 9, 9))
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1])
                outDir.mkdirs()
                File(outDir, "classes.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                Result.success("")
            } else {
                Result.failure(RuntimeException("Unexpected command: $cmd"))
            }
        }

        val compiler = IncrementalDexCompiler(
            projectRoot = tempDir,
            variant = "debug",
            commandRunner = runner,
            d8PathProvider = { "/mock/d8" }
        )
        compiler.snapshotModuleClasses(listOf(module))

        val stagingDir = File(tempDir, "staging")
        val outcome = compiler.compileAndDex(
            mapOf(module to listOf(File(homeDir, "src/main/kotlin/com/example/home/HomeScreen.kt"))),
            stagingDir
        )

        assertTrue(outcome is CompilationOutcome.Success)
        val success = outcome as CompilationOutcome.Success
        val classNames = success.classPayloads.map { it.className }.sorted()
        assertEquals(
            listOf(
                "com.example.home.HomeScreenKt",
                "com.example.home.HomeScreenKt\$HomeScreen\$1"
            ),
            classNames
        )
        assertTrue(success.combinedDexFile?.exists() == true)
        assertFalse(classNames.contains("com.example.home.Unchanged"))
    }

    @Test
    fun `parses compiler error diagnostic when gradlew compile fails`() {
        val homeDir = File(tempDir, "feature/home").apply { mkdirs() }
        val module = GradleModule(":feature:home", homeDir, ModuleType.ANDROID_LIBRARY)
        val runner = mockk<CommandRunner>()

        every { runner.runCommand(any(), any()) } returns Result.failure(
            RuntimeException(
                "Task :feature:home:compileDebugKotlin FAILED\n" +
                    "e: file:///path/to/feature/home/src/main/kotlin/HomeScreen.kt:42:17 Unresolved reference 'foo'\n" +
                    "FAILURE: Build failed with an exception."
            )
        )

        val compiler = IncrementalDexCompiler(
            projectRoot = tempDir,
            variant = "debug",
            commandRunner = runner,
            d8PathProvider = { "/mock/d8" }
        )

        val outcome = compiler.compileAndDex(
            mapOf(module to listOf(File(homeDir, "HomeScreen.kt"))),
            File(tempDir, "staging")
        )

        assertTrue(outcome is CompilationOutcome.Failure)
        val failure = outcome as CompilationOutcome.Failure
        assertEquals(":feature:home", failure.module)
        assertEquals("HomeScreen.kt:42:17 Unresolved reference 'foo'", failure.diagnostic)
    }
}
