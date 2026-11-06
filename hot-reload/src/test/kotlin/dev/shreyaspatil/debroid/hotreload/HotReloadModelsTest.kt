package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HotReloadModelsTest {

    @Test
    fun `HotReloadEvent formats deterministic plain text lines`() {
        val watching = HotReloadEvent.Watching(
            appId = "com.example.app",
            pid = 14230,
            moduleCount = 42,
            intervalMs = 1000L
        )
        assertEquals(
            "[WATCHING] app=com.example.app pid=14230 modules=42 interval=1000ms",
            watching.formatPlainText()
        )

        val reloading = HotReloadEvent.Reloading(
            modules = listOf(":feature:home"),
            files = listOf("HomeScreen.kt")
        )
        assertEquals(
            "[RELOADING] modules=[:feature:home] files=[HomeScreen.kt]",
            reloading.formatPlainText()
        )

        val reloaded = HotReloadEvent.Reloaded(
            mode = ReloadMode.JVMTI_HOT_SWAP,
            redefinedCount = 2,
            newClassesCount = 1,
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD,
            durationMs = 840L
        )
        assertEquals(
            "[RELOADED] mode=JVMTI_HOT_SWAP classes=2 new_classes=1 ui=COMPOSE_HOT_RELOAD duration=840ms",
            reloaded.formatPlainText()
        )

        val compileError = HotReloadEvent.CompileError(
            module = ":feature:home",
            diagnostic = "HomeScreen.kt:42:17 Unresolved reference 'foo'"
        )
        assertEquals(
            "[COMPILE_ERROR] module=:feature:home HomeScreen.kt:42:17 Unresolved reference 'foo'",
            compileError.formatPlainText()
        )

        val runtimeError = HotReloadEvent.RuntimeError(
            error = "IllegalStateException: Boom (HomeScreen.kt:45)"
        )
        assertEquals(
            "[RUNTIME_ERROR] IllegalStateException: Boom (HomeScreen.kt:45)",
            runtimeError.formatPlainText()
        )

        val fallback = HotReloadEvent.FallbackReinstall(
            reason = "RESOURCE_CHANGE",
            files = listOf("activity_main.xml"),
            durationMs = 4120L
        )
        assertEquals(
            "[FALLBACK_REINSTALL] reason=RESOURCE_CHANGE files=[activity_main.xml] duration=4120ms",
            fallback.formatPlainText()
        )

        val error = HotReloadEvent.Error(
            code = "APP_NOT_RUNNING",
            message = "Process for com.example.app is not running"
        )
        assertEquals(
            "[ERROR] code=APP_NOT_RUNNING message=Process for com.example.app is not running",
            error.formatPlainText()
        )

        val stopped = HotReloadEvent.Stopped(appId = "com.example.app")
        assertEquals(
            "[STOPPED] app=com.example.app",
            stopped.formatPlainText()
        )
    }

    @Test
    fun `DetectedChanges reports empty and non-empty states accurately`() {
        val empty = DetectedChanges(emptyMap(), emptyList())
        assertTrue(empty.isEmpty())

        val module = GradleModule(":app", File("/tmp/app"), ModuleType.ANDROID_APP)
        val nonEmpty = DetectedChanges(mapOf(module to listOf(File("/tmp/app/Main.kt"))), emptyList())
        assertFalse(nonEmpty.isEmpty())
    }
}
