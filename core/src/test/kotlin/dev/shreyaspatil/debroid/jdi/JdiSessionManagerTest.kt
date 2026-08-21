package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.EventQueue
import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.DebugException
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JdiSessionManagerTest {

    private lateinit var adbManager: AdbManager
    private lateinit var jdiConnector: JdiConnector
    private lateinit var sessionManager: JdiSessionManager

    @BeforeEach
    fun setup() {
        adbManager = mockk(relaxed = true)
        jdiConnector = mockk(relaxed = true)
        val mockVm = mockk<VirtualMachine>(relaxed = true)
        val mockEventQueue = mockk<EventQueue>(relaxed = true)
        every { mockVm.eventQueue() } returns mockEventQueue
        every { mockEventQueue.remove(any()) } answers {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                // Thread interrupted on detach
            }
            null
        }
        every { jdiConnector.attach(any(), any()) } returns mockVm
        sessionManager = JdiSessionManager(adbManager, jdiConnector)
    }

    @AfterEach
    fun teardown() {
        sessionManager.detachAllSessions()
        unmockkAll()
    }

    @Test
    fun `launchAndAttach throws if app is not debuggable`() {
        every { adbManager.isAppDebuggable(any()) } returns Result.success(false)

        val exception = assertThrows<DebugException> {
            sessionManager.launchAndAttach("com.test.app")
        }

        assertEquals("APP_NOT_DEBUGGABLE", exception.code.name)
        verify { adbManager.isAppDebuggable("com.test.app") }
    }

    @Test
    fun `attachToRunningApp throws if app is not running`() {
        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every {
            adbManager.findPid(any())
        } returns Result.failure(
            DebugException(dev.shreyaspatil.debroid.models.ErrorCode.APP_NOT_DEBUGGABLE, "not currently running")
        )

        val exception = assertThrows<DebugException> {
            sessionManager.attachToRunningApp("com.test.app")
        }

        assertEquals("APP_NOT_DEBUGGABLE", exception.code.name)
        assertTrue(exception.message!!.contains("not currently running"))
    }

    @Test
    fun `getSession throws if session not found`() {
        val exception = assertThrows<DebugException> {
            sessionManager.getSession("invalid_session")
        }
        assertEquals("SESSION_NOT_FOUND", exception.code.name)
    }

    @Test
    fun `launchAndAttach successfully attaches and suspends VM by default`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.launchAndAttach("com.test.app")
        assertNotNull(session)
        assertEquals("com.test.app", session.appId)

        // Verify VM is suspended by default
        verify(exactly = 1) { vm.suspend() }

        // Test getSession
        every { vm.process() } returns mockk(relaxed = true)
        val retrieved = sessionManager.getSession(session.sessionId)
        assertEquals(session, retrieved)
    }

    @Test
    fun `launchAndAttach with suspend false does not suspend VM`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.launchAndAttach("com.test.app", suspend = false)
        assertNotNull(session)
        verify(exactly = 0) { vm.suspend() }
    }

    @Test
    fun `attachToRunningApp with suspend true suspends VM`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.attachToRunningApp("com.test.app", suspend = true)
        assertNotNull(session)
        verify(exactly = 1) { vm.suspend() }
    }

    @Test
    fun `launchAndAttach cleans up port forward, disposes vm, and clears debug app if vm suspend fails`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm
        every { vm.suspend() } throws com.sun.jdi.InternalException("Suspend failed")

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val exception = assertThrows<DebugException> {
            sessionManager.launchAndAttach("com.test.app")
        }

        assertEquals("INTERNAL_ERROR", exception.code.name)
        verify { vm.dispose() }
        verify { adbManager.removePortForward(any()) }
        verify { adbManager.clearDebugApp() }
    }

    @Test
    fun `attachToRunningApp cleans up port forward and disposes vm but does not clear debug app if vm suspend fails`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm
        every { vm.suspend() } throws com.sun.jdi.InternalException("Suspend failed")

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val exception = assertThrows<DebugException> {
            sessionManager.attachToRunningApp("com.test.app", suspend = true)
        }

        assertEquals("INTERNAL_ERROR", exception.code.name)
        verify { vm.dispose() }
        verify { adbManager.removePortForward(any()) }
        verify(exactly = 0) { adbManager.clearDebugApp() }
    }

    @Test
    fun `launchAndAttach cleans up port forward and clears debug app if attach fails`() {
        every { jdiConnector.attach(any(), any()) } throws RuntimeException("Attach failed")

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val exception = assertThrows<DebugException> {
            sessionManager.launchAndAttach("com.test.app")
        }

        assertEquals("ADB_ERROR", exception.code.name)
        verify { adbManager.removePortForward(any()) }
        verify { adbManager.clearDebugApp() }
    }

    @Test
    fun `detachSession returns true if session exists`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.attachToRunningApp("com.test.app")

        val detached = sessionManager.detachSession(session.sessionId)
        assertTrue(detached)

        // Session should be removed
        assertThrows<DebugException> {
            sessionManager.getSession(session.sessionId)
        }
    }

    @Test
    fun `detachSession returns false if session not found`() {
        assertFalse(sessionManager.detachSession("nonexistent"))
    }

    @Test
    fun `launchAndAttach creates session that clears set-debug-app on detach`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm
        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.launchAndAttach("com.test.app")
        // The launch session should request clearDebugApp on detach (B4).
        every { adbManager.clearDebugApp() } returns Result.success(Unit)
        session.detach()
        verify { adbManager.clearDebugApp() }
    }

    @Test
    fun `attachToRunningApp creates session that does NOT clear set-debug-app on detach`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm
        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.attachToRunningApp("com.test.app")
        session.detach()
        verify(exactly = 0) { adbManager.clearDebugApp() }
    }

    @Test
    fun `launchAndAttach sweeps dead sessions`() {
        val vm1 = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        val vm2 = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm1 andThen vm2
        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)

        // Create first session
        val session1 = sessionManager.launchAndAttach("com.test.app1")

        // Mock it as disconnected so it becomes a "dead" session
        every { vm1.allThreads() } throws com.sun.jdi.VMDisconnectedException()

        // Assert it throws SESSION_NOT_FOUND when queried, but wait, the sweep is what we are testing.
        // If we launch a new session, the first one should be detached and swept.
        val session2 = sessionManager.launchAndAttach("com.test.app2")

        // Now if we try to get session1, it should be removed.
        assertThrows<DebugException> {
            sessionManager.getSession(session1.sessionId)
        }

        // Assert session2 is alive and valid
        assertTrue(session2.isAlive())
    }

    @Test
    fun `attachToRunningApp sweeps dead sessions`() {
        val vm1 = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        val vm2 = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm1 andThen vm2
        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        // Create first session
        val session1 = sessionManager.attachToRunningApp("com.test.app1")

        // Mock it as disconnected so it becomes a "dead" session
        every { vm1.allThreads() } throws com.sun.jdi.VMDisconnectedException()

        // If we attach a new session, the first one should be detached and swept.
        val session2 = sessionManager.attachToRunningApp("com.test.app2")

        // Now if we try to get session1, it should be removed.
        assertThrows<DebugException> {
            sessionManager.getSession(session1.sessionId)
        }

        // Assert session2 is alive and valid
        assertTrue(session2.isAlive())
    }

    @Test
    fun `attachToPid dedups existing active sessions for same appId`() {
        val vm1 = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        val vm2 = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm1 andThen vm2
        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.findPid(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        // Create first session for "com.test.app"
        val session1 = sessionManager.attachToRunningApp("com.test.app")

        // Create a second session for the SAME app
        val session2 = sessionManager.attachToRunningApp("com.test.app")

        // The first session should be detached and removed
        assertThrows<DebugException> {
            sessionManager.getSession(session1.sessionId)
        }

        // Assert old session's detach() side-effects ran (vm.dispose)
        verify { vm1.dispose() }

        // Assert session2 is the only remaining session and is alive
        assertTrue(session2.isAlive())
        assertEquals(session2.sessionId, sessionManager.getSession(session2.sessionId).sessionId)
    }

    @Test
    fun `DefaultJdiConnector default timeout is 15 seconds`() {
        assertEquals(15_000L, DefaultJdiConnector.DEFAULT_ATTACH_TIMEOUT_MS)
    }

    @Test
    fun `DefaultJdiConnector attempts connection with configured parameters`() {
        val connector = DefaultJdiConnector(timeoutMs = 15_000L)
        // Attempting to attach to an unbound local port should fail cleanly with a connection exception
        // (verifying that SocketAttach connector is successfully found and executed with arguments)
        assertThrows<Exception> {
            connector.attach("localhost", 59999)
        }
    }
}
