package dev.shreyaspatil.debroid.adb

import dev.shreyaspatil.debroid.models.ErrorCode
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdbManagerTest {

    private lateinit var commandRunner: CommandRunner
    private lateinit var adbManager: AdbManager

    @BeforeEach
    fun setup() {
        commandRunner = mockk()
        adbManager = AdbManager("adb", commandRunner)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `isAppDebuggable returns true if output contains DEBUGGABLE`() {
        val output = "flags=[ DEBUGGABLE ]"
        every { commandRunner.runCommand(any(), any()) } returns Result.success(output)

        val result = adbManager.isAppDebuggable("com.test.app")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
    }

    @Test
    fun `isAppDebuggable returns error if app not installed`() {
        val output = "Unable to find package com.test.app"
        every { commandRunner.runCommand(any(), any()) } returns Result.success(output)

        val result = adbManager.isAppDebuggable("com.test.app")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as DebugException
        assertEquals(ErrorCode.APP_NOT_DEBUGGABLE, exception.code)
    }

    @Test
    fun `findPid returns PID from pidof`() {
        every {
            commandRunner.runCommand(listOf("adb", "shell", "pidof", "com.test.app"), any())
        } returns Result.success("12345")

        val result = adbManager.findPid("com.test.app")

        assertTrue(result.isSuccess)
        assertEquals(12345, result.getOrNull())
    }

    @Test
    fun `findPid fallback to ps if pidof fails`() {
        every {
            commandRunner.runCommand(listOf("adb", "shell", "pidof", "com.test.app"), any())
        } returns Result.failure(Exception("Not found"))
        val psOutput = """
            USER           PID  PPID     VSZ    RSS WCHAN  ADDR S NAME
            u0_a123      12345   123 1234567 123456 0         0 S com.test.app
        """.trimIndent()
        every { commandRunner.runCommand(listOf("adb", "shell", "ps", "-A"), any()) } returns Result.success(psOutput)

        val result = adbManager.findPid("com.test.app")

        assertTrue(result.isSuccess)
        assertEquals(12345, result.getOrNull())
    }

    @Test
    fun `forwardJdwpPort successfully executes`() {
        every { commandRunner.runCommand(listOf("adb", "forward", "tcp:8080", "jdwp:12345"), any()) } returns Result.success("")

        val result = adbManager.forwardJdwpPort(8080, 12345)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `removePortForward executes correctly`() {
        every { commandRunner.runCommand(listOf("adb", "forward", "--remove", "tcp:8080"), any()) } returns Result.success("")

        adbManager.removePortForward(8080)

        verify { commandRunner.runCommand(listOf("adb", "forward", "--remove", "tcp:8080"), any()) }
    }

    @Test
    fun `clearDebugApp executes am clear-debug-app`() {
        every {
            commandRunner.runCommand(listOf("adb", "shell", "am", "clear-debug-app"), any())
        } returns Result.success("")

        val result = adbManager.clearDebugApp()

        assertTrue(result.isSuccess)
        verify { commandRunner.runCommand(listOf("adb", "shell", "am", "clear-debug-app"), any()) }
    }

    @Test
    fun `launchAppSuspended finds main activity and launches`() {
        // mock set-debug-app
        every {
            commandRunner.runCommand(listOf("adb", "shell", "am", "set-debug-app", "-w", "com.test.app"), any())
        } returns Result.success("")

        // mock getMainActivity (resolve-activity)
        val resolveOutput = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            Action: "android.intent.action.MAIN"
            Category: "android.intent.category.LAUNCHER"
            com.test.app/com.test.app.MainActivity
        """.trimIndent()
        every {
            commandRunner.runCommand(listOf("adb", "shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "com.test.app"), any())
        } returns Result.success(resolveOutput)

        // mock am start
        every {
            commandRunner.runCommand(listOf("adb", "shell", "am", "start", "-D", "-n", "com.test.app/com.test.app.MainActivity"), any())
        } returns Result.success("Starting: Intent { cmp=com.test.app/.MainActivity }")

        // mock findPid
        every {
            commandRunner.runCommand(listOf("adb", "shell", "pidof", "com.test.app"), any())
        } returns Result.success("12345")

        val result = adbManager.launchAppSuspended("com.test.app")

        assertTrue(result.isSuccess)
        assertEquals(12345, result.getOrNull())
    }

    @Test
    fun `launchAppSuspended falls back to monkey if activity not resolved`() {
        // mock set-debug-app
        every {
            commandRunner.runCommand(listOf("adb", "shell", "am", "set-debug-app", "-w", "com.test.app"), any())
        } returns Result.success("")

        // mock getMainActivity (resolve-activity returns nothing, pm dump returns nothing)
        every {
            commandRunner.runCommand(listOf("adb", "shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "com.test.app"), any())
        } returns Result.success("")
        every {
            commandRunner.runCommand(listOf("adb", "shell", "pm", "dump", "com.test.app"), any())
        } returns Result.success("")

        // mock monkey
        every {
            commandRunner.runCommand(listOf("adb", "shell", "monkey", "-p", "com.test.app", "-c", "android.intent.category.LAUNCHER", "1"), any())
        } returns Result.success("")

        // mock findPid
        every {
            commandRunner.runCommand(listOf("adb", "shell", "pidof", "com.test.app"), any())
        } returns Result.success("12345")

        val result = adbManager.launchAppSuspended("com.test.app")

        assertTrue(result.isSuccess)
        assertEquals(12345, result.getOrNull())
    }
}
