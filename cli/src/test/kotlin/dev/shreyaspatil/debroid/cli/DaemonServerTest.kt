package dev.shreyaspatil.debroid.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class DaemonServerTest {

    @Test
    fun `registerShutdownHook registers named hook with JVM Runtime`() {
        val hook = DaemonServer.registerShutdownHook(
            runtime = Runtime.getRuntime(),
            onShutdown = {}
        )
        try {
            assertEquals("debroid-shutdown-hook", hook.name)
            val removed = Runtime.getRuntime().removeShutdownHook(hook)
            assertTrue(removed, "Expected shutdown hook to be registered in JVM Runtime")
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }

    @Test
    fun `registerShutdownHook executes custom onShutdown action`() {
        val executed = AtomicBoolean(false)
        val hook = DaemonServer.registerShutdownHook(
            runtime = Runtime.getRuntime(),
            onShutdown = { executed.set(true) }
        )
        try {
            hook.run()
            assertTrue(executed.get(), "Expected shutdown hook action to be executed")
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }
}
