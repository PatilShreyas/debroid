package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HotReloadSessionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `emits Watching, Reloading, and Reloaded events when Kotlin source file changes`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("fun MainScreen() = 1")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        val dexFile = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.Success(
            classPayloads = listOf(ClassDexPayload("MainScreenKt", dexFile)),
            combinedDexFile = dexFile
        )

        val installer = mockk<JvmtiAgentInstaller>()
        every { installer.applyHotReload("com.example.app", any(), any()) } returns AgentExecutionResult.Success(
            mode = ReloadMode.JVMTI_HOT_SWAP,
            redefinedCount = 1,
            newClassesCount = 0,
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }

        // Mutate file after baseline initialization
        mainKt.writeText("fun MainScreen() = 2")
        mainKt.setLastModified(mainKt.lastModified() + 5000L)

        val config = HotReloadConfig(
            appId = "com.example.app",
            projectDir = tempDir,
            once = true
        )
        val session = HotReloadSession(
            config = config,
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(3, events.size)
        assertTrue(events[0] is HotReloadEvent.Watching)
        assertTrue(events[1] is HotReloadEvent.Reloading)
        assertTrue(events[2] is HotReloadEvent.Reloaded)
    }

    @Test
    fun `triggers fallback reinstall on resource changes when allowFallback is true`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val xmlFile = File(appDir, "src/main/res/layout/main.xml").apply {
            parentFile.mkdirs()
            writeText("<LinearLayout/>")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)
        every { adbManager.restartApp("com.example.app") } returns Result.success(Unit)

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        xmlFile.writeText("<FrameLayout/>")
        xmlFile.setLastModified(xmlFile.lastModified() + 5000L)

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true),
            fallbackInstaller = { Result.success(Unit) }
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertTrue(events.any { it is HotReloadEvent.FallbackReinstall })
    }

    @Test
    fun `emits Error when app is not debuggable`() {
        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(false)

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = GradleProjectLayout(tempDir, emptyList()),
            tracker = mockk(relaxed = true),
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(1, events.size)
        val error = events[0] as HotReloadEvent.Error
        assertEquals("APP_NOT_DEBUGGABLE", error.code)
    }

    @Test
    fun `emits Error when app is not running initially`() {
        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.failure(Exception("Not running"))

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = GradleProjectLayout(tempDir, emptyList()),
            tracker = mockk(relaxed = true),
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(1, events.size)
        val error = events[0] as HotReloadEvent.Error
        assertEquals("APP_NOT_RUNNING", error.code)
    }

    @Test
    fun `emits CompileError when compiler fails`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("fun MainScreen() = 1")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.Failure(
            module = ":app",
            diagnostic = "Unresolved reference: foo"
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        mainKt.writeText("fun MainScreen() = foo()")
        mainKt.setLastModified(mainKt.lastModified() + 5000L)

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = mockk(relaxed = true)
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertTrue(events.any { it is HotReloadEvent.CompileError })
    }

    @Test
    fun `emits Error when fallback is disabled and resource change occurs`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val xmlFile = File(appDir, "src/main/res/layout/main.xml").apply {
            parentFile.mkdirs()
            writeText("<LinearLayout/>")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        xmlFile.writeText("<FrameLayout/>")
        xmlFile.setLastModified(xmlFile.lastModified() + 5000L)

        val session = HotReloadSession(
            config = HotReloadConfig(
                appId = "com.example.app",
                projectDir = tempDir,
                once = true,
                allowFallback = false
            ),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        val error = events.find { it is HotReloadEvent.Error } as HotReloadEvent.Error
        assertEquals("FALLBACK_DISABLED", error.code)
    }

    @Test
    fun `triggers fallback when agent reports StructuralIncompatible`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("class Foo { fun a() = 1 }")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)
        every { adbManager.restartApp("com.example.app") } returns Result.success(Unit)

        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        val dexFile = File(tempDir, "redefine.dex").apply { writeBytes(byteArrayOf(1)) }
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.Success(
            classPayloads = listOf(ClassDexPayload("Foo", dexFile)),
            combinedDexFile = dexFile
        )

        val installer = mockk<JvmtiAgentInstaller>()
        every { installer.applyHotReload(any(), any(), any()) } returns AgentExecutionResult.StructuralIncompatible(
            reason = "METHOD_ADDED"
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        mainKt.writeText("class Foo { fun a() = 1; fun b() = 2 }")
        mainKt.setLastModified(mainKt.lastModified() + 5000L)

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer,
            fallbackInstaller = { Result.success(Unit) }
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        val fallback = events.find { it is HotReloadEvent.FallbackReinstall } as HotReloadEvent.FallbackReinstall
        assertEquals("METHOD_ADDED", fallback.reason)
    }

    @Test
    fun `stop terminates session running in loop`() {
        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val session = HotReloadSession(
            config = HotReloadConfig(
                appId = "com.example.app",
                projectDir = tempDir,
                once = false,
                intervalMs = 50L
            ),
            adbManager = adbManager,
            layout = GradleProjectLayout(tempDir, emptyList()),
            tracker = mockk(relaxed = true),
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        session.stop()
        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(1, events.size)
        assertTrue(events[0] is HotReloadEvent.Watching)
    }

    @Test
    fun `skips repeated compilation on uncompilable raw edit and hot-reloads next compilable edit`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("fun MainScreen() = 1")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val dexFile = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }
        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        var compileCallCount = 0
        every { compiler.compileAndDex(any(), any()) } answers {
            compileCallCount++
            if (compileCallCount == 1) {
                CompilationOutcome.Failure(
                    module = ":app",
                    diagnostic = "MainScreen.kt:1:20 Unresolved reference 'brokenRawEdit'"
                )
            } else {
                CompilationOutcome.Success(
                    classPayloads = listOf(ClassDexPayload("MainScreenKt", dexFile)),
                    combinedDexFile = dexFile
                )
            }
        }

        val installer = mockk<JvmtiAgentInstaller>()
        every { installer.applyHotReload("com.example.app", any(), any()) } returns AgentExecutionResult.Success(
            mode = ReloadMode.JVMTI_STRUCTURAL_SWAP,
            redefinedCount = 1,
            newClassesCount = 0,
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }

        // Step 1: Write uncompilable raw edit before session starts
        mainKt.writeText("fun MainScreen() = brokenRawEdit(")
        mainKt.setLastModified(mainKt.lastModified() + 5000L)

        lateinit var session: HotReloadSession
        session = HotReloadSession(
            config = HotReloadConfig(
                appId = "com.example.app",
                projectDir = tempDir,
                once = false,
                intervalMs = 20L
            ),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { event ->
            events.add(event)
            if (event is HotReloadEvent.CompileError) {
                // Let one idle poll pass with the same broken file (should be skipped), then write compilable code
                Thread.sleep(30L)
                mainKt.writeText("fun MainScreen() = 2")
                mainKt.setLastModified(mainKt.lastModified() + 10_000L)
            } else if (event is HotReloadEvent.Reloaded) {
                session.stop()
            }
        }

        assertEquals(2, compileCallCount)
        assertEquals(1, events.count { it is HotReloadEvent.CompileError })
        assertEquals(1, events.count { it is HotReloadEvent.Reloaded })
    }
}
