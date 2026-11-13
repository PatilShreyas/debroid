package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
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
                    "w: file:///path/to/OtherFile.kt:10:5 Deprecated annotation target warning\n" +
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

    @Test
    fun `skips interface and LineNumberTable-only siblings and synthesizes DefaultImpls on default method edit`() {
        val appDir = File(tempDir, "app").apply { mkdirs() }
        val pkgDir = File(appDir, "build/tmp/kotlin-classes/debug/com/example/app").apply { mkdirs() }
        val vmFile = File(pkgDir, "MainActivityViewModel.class")
        val ifaceFile = File(pkgDir, "MainActivityUiState.class")
        val defaultImplsFile = File(pkgDir, "MainActivityUiState\$DefaultImpls.class")
        val loadingFile = File(pkgDir, "MainActivityUiState\$Loading.class")

        vmFile.writeBytes(buildClassWithLine("com/example/app/MainActivityViewModel", returnConst = 7, line = 10))
        ifaceFile.writeBytes(
            buildInterfaceWithDefault("com/example/app/MainActivityUiState", returnConst = 1, line = 20)
        )
        defaultImplsFile.writeBytes(
            buildKotlincCompatibilityDefaultImpls(
                defaultImplsInternalName = "com/example/app/MainActivityUiState\$DefaultImpls",
                ifaceInternalName = "com/example/app/MainActivityUiState",
                line = 20
            )
        )
        loadingFile.writeBytes(
            buildImplementingCaller(
                implInternalName = "com/example/app/MainActivityUiState\$Loading",
                ifaceInternalName = "com/example/app/MainActivityUiState",
                line = 30
            )
        )

        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP)
        var pass = 0
        val d8Commands = mutableListOf<List<String>>()
        val runner = mockk<CommandRunner>()
        every { runner.runCommand(any(), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                pass++
                if (pass == 1) {
                    // Pass 1: ViewModel code changes; interface, $DefaultImpls & Loading only shift LineNumberTable!
                    vmFile.writeBytes(
                        buildClassWithLine("com/example/app/MainActivityViewModel", returnConst = 42, line = 12)
                    )
                    ifaceFile.writeBytes(
                        buildInterfaceWithDefault("com/example/app/MainActivityUiState", returnConst = 1, line = 28)
                    )
                    defaultImplsFile.writeBytes(
                        buildKotlincCompatibilityDefaultImpls(
                            defaultImplsInternalName = "com/example/app/MainActivityUiState\$DefaultImpls",
                            ifaceInternalName = "com/example/app/MainActivityUiState",
                            line = 28
                        )
                    )
                    loadingFile.writeBytes(
                        buildImplementingCaller(
                            implInternalName = "com/example/app/MainActivityUiState\$Loading",
                            ifaceInternalName = "com/example/app/MainActivityUiState",
                            line = 38
                        )
                    )
                } else if (pass == 2) {
                    // Pass 2: Interface default method body changes, kotlinc also regenerates $jd DefaultImpls!
                    ifaceFile.writeBytes(
                        buildInterfaceWithDefault("com/example/app/MainActivityUiState", returnConst = 99, line = 28)
                    )
                    defaultImplsFile.writeBytes(
                        buildKotlincCompatibilityDefaultImpls(
                            defaultImplsInternalName = "com/example/app/MainActivityUiState\$DefaultImpls",
                            ifaceInternalName = "com/example/app/MainActivityUiState",
                            line = 29
                        )
                    )
                } else if (pass == 3) {
                    // Pass 3: ViewModel edited again; kotlinc regenerates interface & $jd DefaultImpls with new lines!
                    // Regression check: must NOT revert MainActivityUiState$DefaultImpls back to kotlinc's $jd bridge!
                    vmFile.writeBytes(
                        buildClassWithLine("com/example/app/MainActivityViewModel", returnConst = 77, line = 15)
                    )
                    ifaceFile.writeBytes(
                        buildInterfaceWithDefault("com/example/app/MainActivityUiState", returnConst = 99, line = 35)
                    )
                    defaultImplsFile.writeBytes(
                        buildKotlincCompatibilityDefaultImpls(
                            defaultImplsInternalName = "com/example/app/MainActivityUiState\$DefaultImpls",
                            ifaceInternalName = "com/example/app/MainActivityUiState",
                            line = 35
                        )
                    )
                    loadingFile.writeBytes(
                        buildImplementingCaller(
                            implInternalName = "com/example/app/MainActivityUiState\$Loading",
                            ifaceInternalName = "com/example/app/MainActivityUiState",
                            line = 45
                        )
                    )
                }
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                d8Commands.add(cmd)
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1]).apply { mkdirs() }
                if ("--file-per-class" in cmd) {
                    for (arg in cmd.filter { it.endsWith(".class") }) {
                        val fileName = File(arg).name.removeSuffix(".class").replace(Regex("^\\d+_"), "")
                        File(outDir, "com/example/app/$fileName.dex").apply {
                            parentFile.mkdirs()
                            writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                        }
                    }
                } else {
                    File(outDir, "classes.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                }
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
        compiler.snapshotModuleClasses(listOf(appModule))

        val sourceFile = File(appDir, "src/main/kotlin/com/example/app/MainActivityViewModel.kt")
        val pass1Outcome = compiler.compileAndDex(
            mapOf(appModule to listOf(sourceFile)),
            File(tempDir, "staging1")
        )
        assertTrue(pass1Outcome is CompilationOutcome.Success)
        val pass1Success = pass1Outcome as CompilationOutcome.Success
        assertEquals(
            listOf("com.example.app.MainActivityViewModel"),
            pass1Success.classPayloads.map { it.className }
        )

        d8Commands.clear()
        val pass2Outcome = compiler.compileAndDex(
            mapOf(appModule to listOf(sourceFile)),
            File(tempDir, "staging2")
        )
        assertTrue(pass2Outcome is CompilationOutcome.Success)
        val pass2Success = pass2Outcome as CompilationOutcome.Success
        val pass2Classes = pass2Success.classPayloads.map { it.className }.sorted()
        assertFalse("com.example.app.MainActivityUiState" in pass2Classes)
        assertEquals(
            listOf(
                "com.example.app.MainActivityUiState\$DefaultImpls",
                "com.example.app.MainActivityUiState\$Loading"
            ),
            pass2Classes
        )
        // Verify buildCombinedDex merges per-class .dex files instead of re-compiling .class files
        val combinedD8Cmd = d8Commands.last()
        assertTrue(combinedD8Cmd.filter { it.endsWith(".dex") }.size == 2)
        assertTrue(combinedD8Cmd.none { it.endsWith(".class") })

        // Pass 3: Editing ViewModel again must NOT revert $DefaultImpls to kotlinc's $jd bridge
        val pass3Outcome = compiler.compileAndDex(
            mapOf(appModule to listOf(sourceFile)),
            File(tempDir, "staging3")
        )
        assertTrue(pass3Outcome is CompilationOutcome.Success)
        val pass3Success = pass3Outcome as CompilationOutcome.Success
        assertEquals(
            listOf("com.example.app.MainActivityViewModel"),
            pass3Success.classPayloads.map { it.className }
        )
    }

    @Test
    fun `preserves removed method stubs without breaking subsequent line-shift skipping`() {
        val coreDir = File(tempDir, "core").apply { mkdirs() }
        val corePkg = File(coreDir, "build/classes/kotlin/main/com/example/core").apply { mkdirs() }
        val helperFile = File(corePkg, "Helper.class")
        helperFile.writeBytes(
            buildClassWithMethods(
                internalName = "com/example/core/Helper",
                methodNames = listOf("keep", "removedHelper"),
                line = 10
            )
        )

        val appDir = File(tempDir, "app").apply { mkdirs() }
        val appPkg = File(appDir, "build/tmp/kotlin-classes/debug/com/example/app").apply { mkdirs() }
        File(appPkg, "App.class").writeBytes(buildClassWithLine("com/example/app/App", returnConst = 1, line = 10))

        val coreModule = GradleModule(":core", coreDir, ModuleType.KOTLIN_JVM)
        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP, dependencies = setOf(":core"))

        var pass = 0
        val gradleCommands = mutableListOf<List<String>>()
        val runner = mockk<CommandRunner>()
        every { runner.runCommand(any(), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                gradleCommands.add(cmd)
                if (":core:compileKotlin" in cmd) {
                    pass++
                    if (pass == 1) {
                        // Pass 1: remove removedHelper() -> triggers cross-module compile + cumulative stub recording
                        helperFile.writeBytes(
                            buildClassWithMethods("com/example/core/Helper", listOf("keep"), line = 10)
                        )
                    } else if (pass == 2) {
                        // Pass 2: only LineNumberTable shifts on Helper -> must skip completely!
                        helperFile.writeBytes(
                            buildClassWithMethods("com/example/core/Helper", listOf("keep"), line = 24)
                        )
                    }
                }
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1]).apply { mkdirs() }
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
        compiler.snapshotModuleClasses(listOf(coreModule, appModule))

        val coreSrc = File(coreDir, "src/main/kotlin/com/example/core/Helper.kt")
        val pass1 = compiler.compileAndDex(mapOf(coreModule to listOf(coreSrc)), File(tempDir, "s1"))
        assertTrue(pass1 is CompilationOutcome.Success)
        assertEquals(2, gradleCommands.size) // :core + dependent :app

        gradleCommands.clear()
        val pass2 = compiler.compileAndDex(mapOf(coreModule to listOf(coreSrc)), File(tempDir, "s2"))
        assertTrue(pass2 is CompilationOutcome.Success)
        assertTrue((pass2 as CompilationOutcome.Success).classPayloads.isEmpty())
        assertEquals(1, gradleCommands.size) // Only :core ran; :app was NOT spuriously recompiled!
    }

    @Test
    fun `skips ksp task when DI skeleton is unchanged and runs ksp when constructor signature changes`() {
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build/generated/ksp/debug").mkdirs()
        val pkgDir = File(appDir, "build/tmp/kotlin-classes/debug/com/example/app").apply { mkdirs() }
        val vmClassFile = File(pkgDir, "SampleViewModel.class")
        vmClassFile.writeBytes(buildClassWithLine("com/example/app/SampleViewModel", returnConst = 1, line = 10))

        val vmSrcFile = File(appDir, "src/main/kotlin/com/example/app/SampleViewModel.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.app
                @HiltViewModel
                class SampleViewModel @Inject constructor(private val repo: Repo) {
                    val state = repo.items.map { it.take(1) }
                    fun refresh() { repo.reload(1) }
                }
                """.trimIndent()
            )
        }

        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP)
        val gradleCommands = mutableListOf<List<String>>()
        val runner = mockk<CommandRunner>()
        var pass = 0
        every { runner.runCommand(any(), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                gradleCommands.add(cmd)
                pass++
                vmClassFile.writeBytes(
                    buildClassWithLine("com/example/app/SampleViewModel", returnConst = 10 + pass, line = 10)
                )
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1]).apply { mkdirs() }
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
        compiler.snapshotModuleClasses(listOf(appModule))

        // Pass 1: Only initializer & method body changed -> must pass `-x :app:kspDebugKotlin`!
        vmSrcFile.writeText(
            """
            package com.example.app
            @HiltViewModel
            class SampleViewModel @Inject constructor(private val repo: Repo) {
                val state = repo.items.map { it.take(5) }
                fun refresh() { repo.reload(99) }
            }
            """.trimIndent()
        )
        val res1 = compiler.compileAndDex(mapOf(appModule to listOf(vmSrcFile)), File(tempDir, "ksp1"))
        assertTrue(res1 is CompilationOutcome.Success)
        assertTrue(":app:kspDebugKotlin" in gradleCommands.single())

        // Pass 2: Constructor dependency signature changed -> must NOT exclude `:app:kspDebugKotlin`!
        gradleCommands.clear()
        vmSrcFile.writeText(
            """
            package com.example.app
            @HiltViewModel
            class SampleViewModel @Inject constructor(private val repo: Repo, private val logger: Logger) {
                val state = repo.items.map { it.take(5) }
                fun refresh() { repo.reload(99) }
            }
            """.trimIndent()
        )
        val res2 = compiler.compileAndDex(mapOf(appModule to listOf(vmSrcFile)), File(tempDir, "ksp2"))
        assertTrue(res2 is CompilationOutcome.Success)
        assertFalse(":app:kspDebugKotlin" in gradleCommands.single())
    }

    @Test
    fun `renames anonymous inner class when hierarchy changes and reuses renamed target on follow-up edits`() {
        val appDir = File(tempDir, "app").apply { mkdirs() }
        val pkgDir = File(appDir, "build/tmp/kotlin-classes/debug/com/example/app").apply { mkdirs() }
        val outerFile = File(pkgDir, "DigestViewModel.class")
        val lambdaFile = File(pkgDir, "DigestViewModel\$uiState\$1.class")

        outerFile.writeBytes(
            buildOuterReferencingAnonymous(
                "com/example/app/DigestViewModel",
                "com/example/app/DigestViewModel\$uiState\$1"
            )
        )
        lambdaFile.writeBytes(
            buildAnonymousWithInterface(
                "com/example/app/DigestViewModel\$uiState\$1",
                "java/lang/Runnable",
                returnConst = 10
            )
        )

        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP)
        var pass = 0
        val runner = mockk<CommandRunner>()
        every { runner.runCommand(any(), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                pass++
                if (pass == 1) {
                    // Hierarchy changes from Runnable -> Cloneable while outer raw bytes stay identical!
                    lambdaFile.writeBytes(
                        buildAnonymousWithInterface(
                            "com/example/app/DigestViewModel\$uiState\$1",
                            "java/lang/Cloneable",
                            returnConst = 20
                        )
                    )
                } else if (pass == 2) {
                    // Body of Cloneable lambda changes (returnConst 20 -> 35), hierarchy unchanged!
                    lambdaFile.writeBytes(
                        buildAnonymousWithInterface(
                            "com/example/app/DigestViewModel\$uiState\$1",
                            "java/lang/Cloneable",
                            returnConst = 35
                        )
                    )
                }
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1]).apply { mkdirs() }
                if ("--file-per-class" in cmd) {
                    for (arg in cmd.filter { it.endsWith(".class") }) {
                        val fileName = File(arg).name.removeSuffix(".class").replace(Regex("^\\d+_"), "")
                        File(outDir, "com/example/app/$fileName.dex").apply {
                            parentFile.mkdirs()
                            writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                        }
                    }
                } else {
                    File(outDir, "classes.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                }
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
        compiler.snapshotModuleClasses(listOf(appModule))
        val srcFile = File(appDir, "src/main/kotlin/com/example/app/DigestFeature.kt")

        val pass1 = compiler.compileAndDex(mapOf(appModule to listOf(srcFile)), File(tempDir, "hr_s1"))
        assertTrue(pass1 is CompilationOutcome.Success)
        val pass1Classes = (pass1 as CompilationOutcome.Success).classPayloads.map { it.className }.sorted()
        assertEquals(
            listOf(
                "com.example.app.DigestViewModel",
                "com.example.app.DigestViewModel\$uiState\$1\$hr1"
            ),
            pass1Classes
        )

        val pass2 = compiler.compileAndDex(mapOf(appModule to listOf(srcFile)), File(tempDir, "hr_s2"))
        assertTrue(pass2 is CompilationOutcome.Success)
        val pass2Classes = (pass2 as CompilationOutcome.Success).classPayloads.map { it.className }
        assertEquals(
            listOf("com.example.app.DigestViewModel\$uiState\$1\$hr1"),
            pass2Classes
        )
    }

    private fun buildOuterReferencingAnonymous(outerInternal: String, anonInternal: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s =
            listOf(
                outerInternal,
                "java/lang/Object",
                anonInternal,
                "Code",
                "make",
                "()Ljava/lang/Object;",
                "<init>",
                "()V"
            )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(14)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #9 outer
        out.write(7)
        u2(2) // #10 Object
        out.write(7)
        u2(3) // #11 anon
        out.write(12)
        u2(7)
        u2(8) // #12 <init>:()V
        out.write(10)
        u2(11)
        u2(12) // #13 anon.<init>:()V
        u2(0x0021)
        u2(9)
        u2(10)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0001)
        u2(5)
        u2(6)
        u2(1)
        // new #11; dup; invokespecial #13; areturn
        val codeBytes = byteArrayOf(0xBB.toByte(), 0x00, 11, 0x59, 0xB7.toByte(), 0x00, 13, 0xB0.toByte())
        u2(4)
        u4(12 + codeBytes.size)
        u2(2)
        u2(1)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(0)
        u2(0)
        return out.toByteArray()
    }

    private fun buildAnonymousWithInterface(
        anonInternal: String,
        ifaceInternal: String,
        returnConst: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s = listOf(anonInternal, "java/lang/Object", ifaceInternal, "Code", "eval", "()I")
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(10)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #7 anon
        out.write(7)
        u2(2) // #8 Object
        out.write(7)
        u2(3) // #9 iface
        u2(0x0021)
        u2(7)
        u2(8)
        u2(1)
        u2(9)
        u2(0)
        u2(1)
        u2(0x0001)
        u2(5)
        u2(6)
        u2(1)
        val codeBytes = byteArrayOf(0x10, returnConst.toByte(), 0xAC.toByte())
        u2(4)
        u4(12 + codeBytes.size)
        u2(1)
        u2(1)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(0)
        u2(0)
        return out.toByteArray()
    }

    private fun buildClassWithLine(internalName: String, returnConst: Int, line: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s = listOf(internalName, "java/lang/Object", "Code", "LineNumberTable", "compute", "()I")
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(9)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        u2(0x0021)
        u2(7)
        u2(8)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0009)
        u2(5)
        u2(6)
        u2(1)
        val codeBytes = byteArrayOf(0x10, returnConst.toByte(), 0xAC.toByte())
        u2(3)
        u4(12 + codeBytes.size + 12)
        u2(1)
        u2(0)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(1)
        u2(4)
        u4(6)
        u2(1)
        u2(0)
        u2(line)
        u2(0)
        return out.toByteArray()
    }

    private fun buildInterfaceWithDefault(ifaceInternalName: String, returnConst: Int, line: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s = listOf(
            ifaceInternalName,
            "java/lang/Object",
            "Code",
            "LineNumberTable",
            "shouldKeepSplashScreen",
            "()I"
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(9)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        u2(0x0601)
        u2(7)
        u2(8)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0001)
        u2(5)
        u2(6)
        u2(1)
        val codeBytes = byteArrayOf(0x10, returnConst.toByte(), 0xAC.toByte())
        u2(3)
        u4(12 + codeBytes.size + 12)
        u2(1)
        u2(1)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(1)
        u2(4)
        u4(6)
        u2(1)
        u2(0)
        u2(line)
        u2(0)
        return out.toByteArray()
    }

    private fun buildImplementingCaller(
        implInternalName: String,
        ifaceInternalName: String,
        line: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s = listOf(
            implInternalName, // 1
            "java/lang/Object", // 2
            ifaceInternalName, // 3
            "Code", // 4
            "LineNumberTable", // 5
            "shouldKeepSplashScreen", // 6
            "()I" // 7
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(13)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #8 impl
        out.write(7)
        u2(2) // #9 Object
        out.write(7)
        u2(3) // #10 iface
        out.write(12)
        u2(6)
        u2(7) // #11 NameAndType
        out.write(11)
        u2(10)
        u2(11) // #12 InterfaceMethodref
        u2(0x0021)
        u2(8)
        u2(9)
        u2(1)
        u2(10)
        u2(0)
        u2(1)
        u2(0x0001)
        u2(6)
        u2(7)
        u2(1)
        val bridgeCode = byteArrayOf(0x2A, 0xB7.toByte(), 0x00, 12, 0xAC.toByte())
        u2(4)
        u4(12 + bridgeCode.size + 12)
        u2(1)
        u2(1)
        u4(bridgeCode.size)
        out.write(bridgeCode)
        u2(0)
        u2(1)
        u2(5)
        u4(6)
        u2(1)
        u2(0)
        u2(line)
        u2(0)
        return out.toByteArray()
    }

    private fun buildKotlincCompatibilityDefaultImpls(
        defaultImplsInternalName: String,
        ifaceInternalName: String,
        line: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val staticDesc = "(L$ifaceInternalName;)I"
        val utf8s = listOf(
            defaultImplsInternalName, // 1
            "java/lang/Object", // 2
            ifaceInternalName, // 3
            "Code", // 4
            "LineNumberTable", // 5
            "shouldKeepSplashScreen", // 6
            staticDesc, // 7
            "access\$shouldKeepSplashScreen\$jd" // 8
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(14)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #9 DefaultImpls
        out.write(7)
        u2(2) // #10 Object
        out.write(7)
        u2(3) // #11 iface
        out.write(12)
        u2(8)
        u2(7) // #12 NameAndType access$shouldKeepSplashScreen$jd:(Liface;)I
        out.write(11)
        u2(11)
        u2(12) // #13 InterfaceMethodref iface.access$shouldKeepSplashScreen$jd
        u2(0x0031) // ACC_PUBLIC | ACC_FINAL | ACC_SUPER
        u2(9)
        u2(10)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0009) // public static
        u2(6)
        u2(7)
        u2(1)
        val jdDelegateCode = byteArrayOf(0x2A, 0xB8.toByte(), 0x00, 13, 0xAC.toByte())
        u2(4)
        u4(12 + jdDelegateCode.size + 12)
        u2(1)
        u2(1)
        u4(jdDelegateCode.size)
        out.write(jdDelegateCode)
        u2(0)
        u2(1)
        u2(5)
        u4(6)
        u2(1)
        u2(0)
        u2(line)
        u2(0)
        return out.toByteArray()
    }

    private fun buildClassWithMethods(
        internalName: String,
        methodNames: List<String>,
        line: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s = listOf(internalName, "java/lang/Object", "Code", "LineNumberTable", "()I") + methodNames
        val thisClsSlot = utf8s.size + 1
        val superClsSlot = utf8s.size + 2
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(utf8s.size + 3)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        u2(0x0021)
        u2(thisClsSlot)
        u2(superClsSlot)
        u2(0)
        u2(0)
        u2(methodNames.size)
        for ((idx, _) in methodNames.withIndex()) {
            u2(0x0009) // public static
            u2(6 + idx)
            u2(5) // ()I
            u2(1)
            val code = byteArrayOf(0x04, 0xAC.toByte())
            u2(3)
            u4(12 + code.size + 12)
            u2(1)
            u2(0)
            u4(code.size)
            out.write(code)
            u2(0)
            u2(1)
            u2(4)
            u4(6)
            u2(1)
            u2(0)
            u2(line + idx)
        }
        u2(0)
        return out.toByteArray()
    }
}
