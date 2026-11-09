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

    @Test
    fun `extracts Gradle What went wrong block and resolves per-module debug fallback for unflavored libraries`() {
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build/intermediates/built_in_kotlinc/demoDebug").mkdirs()
        val designDir = File(tempDir, "core/designsystem").apply { mkdirs() }
        File(designDir, "build/intermediates/built_in_kotlinc/debug").mkdirs()

        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP)
        val designModule = GradleModule(":core:designsystem", designDir, ModuleType.ANDROID_LIBRARY)

        val compiler = IncrementalDexCompiler(projectRoot = tempDir, variant = "demoDebug")
        assertEquals("demoDebug", compiler.resolveModuleVariant(appModule))
        assertEquals("debug", compiler.resolveModuleVariant(designModule))

        val tasks = compiler.buildGradleTasksForModules(
            mapOf(
                appModule to listOf(File("MainActivity.kt")),
                designModule to listOf(File("Button.kt"))
            )
        )
        assertEquals(
            listOf(":app:compileDemoDebugKotlin", ":core:designsystem:compileDebugKotlin"),
            tasks
        )

        val gradleError = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Task 'compileDebugKotlin' not found in project ':feature:foryou:impl'.

            * Try:
            > Run gradlew tasks
        """.trimIndent()
        assertEquals(
            "Task 'compileDebugKotlin' not found in project ':feature:foryou:impl'.",
            compiler.extractDiagnostic(gradleError)
        )

        val candidateError = """
            Task 'compileDemoDebugKotlin' not found in project ':core:designsystem'. Some candidates are: 'compileDebugKotlin', 'compileReleaseKotlin'.
        """.trimIndent()
        assertEquals(
            listOf(":core:designsystem:compileDebugKotlin"),
            compiler.resolveFallbackTasksOnTaskNotFound(
                listOf(":core:designsystem:compileDemoDebugKotlin"),
                candidateError
            )
        )
    }

    @Test
    fun `recompiles transitive dependent modules when cross-module signature or inline function changes`() {
        val coreDir = File(tempDir, "core").apply { mkdirs() }
        val coreClassesDir = File(coreDir, "build/classes/kotlin/main/com/example/core").apply { mkdirs() }
        val coreClassFile = File(coreClassesDir, "CoreKt.class")
        coreClassFile.writeBytes(byteArrayOf(1, 2, 3))

        val featureDir = File(tempDir, "feature").apply { mkdirs() }
        val featureClassesDir = File(
            featureDir,
            "build/tmp/kotlin-classes/debug/com/example/feature"
        ).apply { mkdirs() }
        val featureClassFile = File(featureClassesDir, "FeatureKt.class")
        featureClassFile.writeBytes(byteArrayOf(10, 20, 30))

        val appDir = File(tempDir, "app").apply { mkdirs() }
        val appClassesDir = File(appDir, "build/tmp/kotlin-classes/debug/com/example/app").apply { mkdirs() }
        val appClassFile = File(appClassesDir, "AppKt.class")
        appClassFile.writeBytes(byteArrayOf(40, 50, 60))

        val coreModule = GradleModule(":core", coreDir, ModuleType.KOTLIN_JVM)
        val featureModule = GradleModule(
            gradlePath = ":feature",
            moduleDir = featureDir,
            type = ModuleType.ANDROID_LIBRARY,
            dependencies = setOf(":core")
        )
        val appModule = GradleModule(
            gradlePath = ":app",
            moduleDir = appDir,
            type = ModuleType.ANDROID_APP,
            dependencies = setOf(":feature")
        )

        val runner = mockk<CommandRunner>()
        val gradleCommands = mutableListOf<List<String>>()
        every { runner.runCommand(any(), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                gradleCommands.add(cmd)
                if (cmd.contains(":core:compileKotlin")) {
                    // Simulate signature change in CoreKt.class (invalid bytes fallback triggers crossModuleChange=true)
                    coreClassFile.writeBytes(byteArrayOf(1, 2, 3, 4))
                }
                if (cmd.contains(":feature:compileDebugKotlin")) {
                    // Simulate inlined callsite update in FeatureKt.class
                    featureClassFile.writeBytes(byteArrayOf(10, 20, 30, 40))
                }
                if (cmd.contains(":app:compileDebugKotlin")) {
                    // AppKt.class was not affected by the change
                }
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
        compiler.snapshotModuleClasses(listOf(coreModule, featureModule, appModule))

        val outcome = compiler.compileAndDex(
            mapOf(coreModule to listOf(File(coreDir, "src/main/kotlin/com/example/core/Core.kt"))),
            File(tempDir, "staging")
        )

        assertTrue(outcome is CompilationOutcome.Success)
        val success = outcome as CompilationOutcome.Success
        // First Gradle invocation compiled :core; second compiled dependents :feature and :app
        assertEquals(2, gradleCommands.size)
        assertTrue(gradleCommands[0].contains(":core:compileKotlin"))
        assertTrue(gradleCommands[1].contains(":feature:compileDebugKotlin"))
        assertTrue(gradleCommands[1].contains(":app:compileDebugKotlin"))

        val reloadedClasses = success.classPayloads.map { it.className }.sorted()
        assertEquals(
            listOf("com.example.core.CoreKt", "com.example.feature.FeatureKt"),
            reloadedClasses
        )
    }
}
