package dev.shreyaspatil.debroid.jdi

import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.DebugException
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
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
        sessionManager = JdiSessionManager(adbManager, jdiConnector)
    }

    @AfterEach
    fun teardown() {
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
        } returns Result.failure(DebugException(dev.shreyaspatil.debroid.models.ErrorCode.APP_NOT_DEBUGGABLE, "not currently running"))

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
    fun `launchAndAttach successfully attaches`() {
        val vm = mockk<com.sun.jdi.VirtualMachine>(relaxed = true)
        every { jdiConnector.attach(any(), any()) } returns vm

        every { adbManager.isAppDebuggable(any()) } returns Result.success(true)
        every { adbManager.launchAppSuspended(any()) } returns Result.success(1234)
        every { adbManager.forwardJdwpPort(any(), any()) } returns Result.success(Unit)

        val session = sessionManager.launchAndAttach("com.test.app")
        assertNotNull(session)
        assertEquals("com.test.app", session.appId)

        // Test getSession
        every { vm.process() } returns mockk(relaxed = true)
        val retrieved = sessionManager.getSession(session.sessionId)
        assertEquals(session, retrieved)
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
}
