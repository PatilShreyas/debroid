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

    @Test
    fun `emits RuntimeError event when agent reports Compose recomposition exception`() {
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
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD,
            runtimeError = "IllegalStateException: Boom during recomposition (MainScreen.kt:12)"
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        mainKt.writeText("fun MainScreen() { error(\"Boom during recomposition\") }")
        mainKt.setLastModified(mainKt.lastModified() + 5000L)

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(4, events.size)
        assertTrue(events[0] is HotReloadEvent.Watching)
        assertTrue(events[1] is HotReloadEvent.Reloading)
        assertTrue(events[2] is HotReloadEvent.Reloaded)
        assertEquals(
            HotReloadEvent.RuntimeError("IllegalStateException: Boom during recomposition (MainScreen.kt:12)"),
            events[3]
        )
    }

    @Test
    fun `parseCrashLogForPid extracts main-thread uncaught exception and changed file line`() {
        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = mockk(relaxed = true),
            layout = GradleProjectLayout(tempDir, emptyList()),
            tracker = mockk(relaxed = true),
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        val logcat = """
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: FATAL EXCEPTION: main
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: Process: com.example.app, PID: 9999
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: java.lang.RuntimeException: Unable to start activity
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: 	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:4000)
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: Caused by: java.lang.IllegalArgumentException: Invalid bookmark id
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: 	at kotlin.PreconditionsKt.require(Preconditions.kt:50)
            11-06 15:10:00.100 9999 9999 E AndroidRuntime: 	at com.example.app.BookmarksScreenKt.BookmarksScreen(BookmarksScreen.kt:88)
        """.trimIndent()

        val parsed = session.parseCrashLogForPid(logcat, pid = 9999, changedFileNames = setOf("BookmarksScreen.kt"))
        assertEquals("IllegalArgumentException: Invalid bookmark id (BookmarksScreen.kt:88)", parsed)

        // Calling again with identical crash log returns null (deduplicated)
        assertEquals(
            null,
            session.parseCrashLogForPid(logcat, pid = 9999, changedFileNames = setOf("BookmarksScreen.kt"))
        )
    }

    @Test
    fun `parseCrashLogForPid prioritizes ComposeInternal composition error over downstream Scaffold crash`() {
        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = mockk(relaxed = true),
            layout = GradleProjectLayout(tempDir, emptyList()),
            tracker = mockk(relaxed = true),
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        val logcat = """
            11-06 16:24:55.830  3485  3485 E ComposeInternal: Error was captured in composition while live edit was enabled.
            11-06 16:24:55.830  3485  3485 E ComposeInternal: java.lang.IllegalStateException: Saved vault sync failed: invalid streak=3
            11-06 16:24:55.830  3485  3485 E ComposeInternal: 	at com.google.samples.apps.nowinandroid.feature.bookmarks.impl.BookmarksScreenKt.EmptyState(BookmarksScreen.kt:222)
            11-06 16:24:55.835  3485  3485 E AndroidRuntime: FATAL EXCEPTION: main
            11-06 16:24:55.835  3485  3485 E AndroidRuntime: Process: com.example.app, PID: 3485
            11-06 16:24:55.835  3485  3485 E AndroidRuntime: java.util.NoSuchElementException: List is empty.
            11-06 16:24:55.835  3485  3485 E AndroidRuntime: 	at androidx.compose.material3.ScaffoldKt.ScaffoldLayout(Scaffold.kt:275)
        """.trimIndent()

        val parsed = session.parseCrashLogForPid(logcat, pid = 3485, changedFileNames = setOf("BookmarksScreen.kt"))
        assertEquals(
            "IllegalStateException: Saved vault sync failed: invalid streak=3 (BookmarksScreen.kt:222)",
            parsed
        )
    }

    @Test
    fun `emits zero-class Reloaded completion event when compile produces empty payloads`() {
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
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.Success(
            classPayloads = emptyList(),
            combinedDexFile = null
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        mainKt.writeText("// comment only\nfun MainScreen() = 1")
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

        assertEquals(3, events.size)
        assertTrue(events[0] is HotReloadEvent.Watching)
        assertTrue(events[1] is HotReloadEvent.Reloading)
        val reloaded = events[2] as HotReloadEvent.Reloaded
        assertEquals(0, reloaded.redefinedCount)
        assertEquals(UiRefreshMode.NONE, reloaded.uiRefresh)
    }

    @Test
    fun `trySyncWithRunningWatcher flushes pending edit on active watcher and returns completion lines`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("fun MainScreen() = 1")
        }
        val registryDir = File(tempDir, "registry").apply { mkdirs() }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val dexFile = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }
        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
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
        val session = HotReloadSession(
            config = HotReloadConfig(
                appId = "com.example.app",
                projectDir = tempDir,
                once = false,
                intervalMs = 5_000L // Long interval proves --once wakes watcher immediately!
            ),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer,
            syncRegistryDir = registryDir
        )

        val watchingLatch = java.util.concurrent.CountDownLatch(1)
        val watcherThread = Thread {
            session.run { event ->
                if (event is HotReloadEvent.Watching) {
                    watchingLatch.countDown()
                }
            }
        }.apply { start() }

        try {
            assertTrue(watchingLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Wait briefly for server socket port file to be written
            val portFile = HotReloadSyncCoordinator.portFileFor("com.example.app", tempDir, registryDir)
            val deadline = System.currentTimeMillis() + 2_000L
            while (!portFile.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            assertTrue(portFile.exists())

            mainKt.writeText("fun MainScreen() = 42")
            mainKt.setLastModified(mainKt.lastModified() + 10_000L)

            val syncedLines = mutableListOf<String>()
            val synced = HotReloadSyncCoordinator.trySyncWithRunningWatcher(
                config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
                registryDir = registryDir,
                onLine = { syncedLines.add(it) }
            )
            assertTrue(synced)
            assertTrue(syncedLines.any { it.startsWith("[RELOADING]") })
            assertTrue(syncedLines.any { it.startsWith("[RELOADED] mode=JVMTI_HOT_SWAP classes=1") })
        } finally {
            session.stop()
            watcherThread.join(3_000L)
        }
    }

    @Test
    fun `handles pre-flight StructuralIncompatible from compiler without invoking device agent`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val stateKt = File(appDir, "src/main/kotlin/NiaAppState.kt").apply {
            parentFile.mkdirs()
            writeText("class NiaAppState")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)
        every { adbManager.restartApp("com.example.app") } returns Result.success(Unit)

        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.StructuralIncompatible(
            reason = "INTERFACE_HIERARCHY_CHANGED:NiaAppState"
        )

        var agentCalled = false
        val installer = mockk<JvmtiAgentInstaller>()
        every { installer.invalidateInstalledAgent(any()) } returns Unit
        every { installer.applyHotReload(any(), any(), any()) } answers {
            agentCalled = true
            AgentExecutionResult.Error("UNEXPECTED", "Agent must not be called")
        }

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        stateKt.writeText("class NiaAppState : java.io.Serializable")
        stateKt.setLastModified(stateKt.lastModified() + 5_000L)

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

        assertTrue(!agentCalled)
        val fallback = events.filterIsInstance<HotReloadEvent.FallbackReinstall>().single()
        assertEquals("INTERFACE_HIERARCHY_CHANGED:NiaAppState", fallback.reason)
    }

    @Test
    fun `automatically reapplies session edits when target app process restarts with new PID`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("fun MainScreen() = 1")
        }
        val registryDir = File(tempDir, "reapply_registry").apply { mkdirs() }

        var currentPid = 1001
        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } answers { Result.success(currentPid) }

        val dexFile = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }
        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.Success(
            classPayloads = listOf(ClassDexPayload("MainScreenKt", dexFile)),
            combinedDexFile = dexFile
        )
        every { compiler.reapplyFromApkBaseline(any(), any()) } returns CompilationOutcome.Success(
            classPayloads = listOf(ClassDexPayload("MainScreenKt", dexFile)),
            combinedDexFile = dexFile
        )

        val installer = mockk<JvmtiAgentInstaller>(relaxed = true)
        every { installer.applyHotReload("com.example.app", any(), any()) } returns AgentExecutionResult.Success(
            mode = ReloadMode.JVMTI_HOT_SWAP,
            redefinedCount = 1,
            newClassesCount = 0,
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        mainKt.writeText("fun MainScreen() = 2")
        mainKt.setLastModified(mainKt.lastModified() + 5_000L)

        val events = java.util.concurrent.CopyOnWriteArrayList<HotReloadEvent>()
        lateinit var session: HotReloadSession
        session = HotReloadSession(
            config = HotReloadConfig(
                appId = "com.example.app",
                projectDir = tempDir,
                once = false,
                intervalMs = 50L
            ),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer,
            syncRegistryDir = registryDir
        )

        session.run { event ->
            events.add(event)
            val reloadedCount = events.count { it is HotReloadEvent.Reloaded }
            if (reloadedCount == 1 && event is HotReloadEvent.Reloaded) {
                // Simulate app restart to PID 2002 and wake watcher via sync
                currentPid = 2002
                Thread {
                    val portFile = HotReloadSyncCoordinator.portFileFor("com.example.app", tempDir, registryDir)
                    val deadline = System.currentTimeMillis() + 2_000L
                    while (!portFile.exists() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(10L)
                    }
                    HotReloadSyncCoordinator.trySyncWithRunningWatcher(
                        config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
                        registryDir = registryDir,
                        onLine = {}
                    )
                }.start()
            } else if (reloadedCount >= 2 && event is HotReloadEvent.Reloaded) {
                session.stop()
            }
        }

        val watchingPids = events.filterIsInstance<HotReloadEvent.Watching>().map { it.pid }
        assertEquals(listOf(1001, 2002), watchingPids)
        assertEquals(2, events.count { it is HotReloadEvent.Reloaded })
    }
}
