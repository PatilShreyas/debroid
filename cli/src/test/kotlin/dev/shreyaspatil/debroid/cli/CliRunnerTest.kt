package dev.shreyaspatil.debroid.cli

import com.github.ajalt.clikt.core.context
import dev.shreyaspatil.debroid.cli.models.CliDebugError
import dev.shreyaspatil.debroid.cli.models.CliErrorCode
import dev.shreyaspatil.debroid.cli.models.DaemonRequest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class CliRunnerTest {

    private val originalPort = DaemonConfig.PORT

    @BeforeEach
    fun setUp() {
        DaemonConfig.PORT = 9876
    }

    @AfterEach
    fun tearDown() {
        DaemonConfig.PORT = originalPort
    }

    @Test
    fun `default port is 9876 when port flag and env var are not specified`() {
        val cli = CliRunner.DebroidCli()
        cli.parse(emptyList())

        assertEquals(9876, DaemonConfig.PORT)
    }

    @Test
    fun `port flag updates DaemonConfig PORT`() {
        val cli = CliRunner.DebroidCli()
        cli.parse(listOf("--port", "9999"))

        assertEquals(9999, DaemonConfig.PORT)
    }

    @Test
    fun `short port flag updates DaemonConfig PORT`() {
        val cli = CliRunner.DebroidCli()
        cli.parse(listOf("-p", "8888"))

        assertEquals(8888, DaemonConfig.PORT)
    }

    @Test
    fun `DEBROID_PORT env var updates DaemonConfig PORT`() {
        val cli = CliRunner.DebroidCli()
        cli.context {
            envvarReader = { if (it == "DEBROID_PORT") "7777" else null }
        }
        cli.parse(emptyList())

        assertEquals(7777, DaemonConfig.PORT)
    }

    @Test
    fun `explicit port flag takes precedence over DEBROID_PORT env var`() {
        val cli = CliRunner.DebroidCli()
        cli.context {
            envvarReader = { if (it == "DEBROID_PORT") "7777" else null }
        }
        cli.parse(listOf("--port", "9999"))

        assertEquals(9999, DaemonConfig.PORT)
    }

    @Test
    fun `subcommand with port flag updates DaemonConfig PORT`() {
        val cli = CliRunner.createCli()
        cli.parse(listOf("--port", "9999", "stop"))

        assertEquals(9999, DaemonConfig.PORT)
    }

    @Test
    fun `subcommand with DEBROID_PORT env var updates DaemonConfig PORT`() {
        val cli = CliRunner.createCli()
        cli.context {
            envvarReader = { if (it == "DEBROID_PORT") "7777" else null }
        }
        cli.parse(listOf("stop"))

        assertEquals(7777, DaemonConfig.PORT)
    }

    @Test
    fun `help text includes debroid description and port option details`() {
        val cli = CliRunner.createCli()
        val help = cli.getFormattedHelp().orEmpty()

        assertTrue(help.contains("🤖 Debroid - Autonomous Debugger for Android"))
        assertTrue(help.contains("--port"))
        assertTrue(help.contains("-p"))
        assertTrue(help.contains("DEBROID_PORT"))
    }

    @Test
    fun `daemon running check respects configured custom port`() {
        val customPort = 9988
        DaemonConfig.PORT = customPort

        assertFalse(DaemonServer.isDaemonRunning(), "Daemon should not be running before socket opens")

        val serverSocket = ServerSocket(customPort, 1, InetAddress.getByName(DaemonConfig.HOST))
        try {
            assertTrue(DaemonServer.isDaemonRunning(), "Daemon should be detected running on custom port")

            DaemonConfig.PORT = 9876
            assertFalse(DaemonServer.isDaemonRunning(), "Default port should not be detected running")
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `execute with empty args does not invoke exit and prints formatted help`() {
        var exitCode: Int? = null
        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.execute(emptyArray(), exit = { exitCode = it })
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(null, exitCode)
        assertTrue(outContent.toString().contains("🤖 Debroid - Autonomous Debugger for Android"))
    }

    @Test
    fun `execute with --help flag exits with status code 0`() {
        var exitCode: Int? = null
        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.execute(arrayOf("--help"), exit = { exitCode = it })
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(0, exitCode)
        assertTrue(outContent.toString().contains("🤖 Debroid - Autonomous Debugger for Android"))
    }

    @Test
    fun `execute with subcommand --help flag exits with status code 0`() {
        var exitCode: Int? = null
        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.execute(arrayOf("attach", "--help"), exit = { exitCode = it })
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(0, exitCode)
        assertTrue(outContent.toString().contains("Attaches the debugger to an already running application process"))
    }

    @Test
    fun `execute with --version flag exits with status code 0`() {
        var exitCode: Int? = null
        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.execute(arrayOf("--version"), exit = { exitCode = it })
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(0, exitCode)
        assertTrue(outContent.toString().contains(VERSION))
    }

    @Test
    fun `execute with subcommand --schema flag exits with status code 0`() {
        var exitCode: Int? = null
        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.execute(arrayOf("attach", "--schema"), exit = { exitCode = it })
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(0, exitCode)
        assertTrue(outContent.toString().contains("sessionId"))
        assertTrue(outContent.toString().contains("properties"))
    }

    @Test
    fun `execute with invalid option exits with status code 1`() {
        var exitCode: Int? = null
        val originalErr = System.err
        val errContent = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(errContent))

        try {
            CliRunner.execute(arrayOf("--non-existent-option"), exit = { exitCode = it })
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(1, exitCode)
        assertTrue(errContent.toString().contains("no such option"))
    }

    @Test
    fun `execute with unknown command exits with status code 1`() {
        var exitCode: Int? = null
        val originalErr = System.err
        val errContent = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(errContent))

        try {
            CliRunner.execute(arrayOf("unknown-cmd"), exit = { exitCode = it })
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(1, exitCode)
        assertTrue(errContent.toString().contains("no such subcommand"))
    }

    @Test
    fun `default log file is daemon dot log in dot debroid directory`() {
        val defaultFile = DaemonConfig.defaultLogFile()
        assertTrue(defaultFile.path.endsWith(".debroid/daemon.log"))
        assertEquals(defaultFile, DaemonConfig.logFile)
    }

    @Test
    fun `readStartupDiagnostics returns empty string when file does not exist`(@TempDir tempDir: File) {
        val nonExistent = File(tempDir, "daemon.log")
        val result = CliRunner.readStartupDiagnostics(nonExistent, 0L)
        assertEquals("", result)
    }

    @Test
    fun `readStartupDiagnostics returns empty string when file is empty`(@TempDir tempDir: File) {
        val emptyLog = File(tempDir, "daemon.log").apply { createNewFile() }
        val result = CliRunner.readStartupDiagnostics(emptyLog, 0L)
        assertEquals("", result)
    }

    @Test
    fun `readStartupDiagnostics reads only newly appended content after start offset`(@TempDir tempDir: File) {
        val logFile = File(tempDir, "daemon.log")
        logFile.writeText("Old line 1\nOld line 2\n")
        val offset = logFile.length()
        logFile.appendText("New error line: BindException\n")

        val result = CliRunner.readStartupDiagnostics(logFile, offset)
        assertEquals("New error line: BindException", result)
    }

    @Test
    fun `readStartupDiagnostics filters blank lines and caps at maxLines`(@TempDir tempDir: File) {
        val logFile = File(tempDir, "daemon.log")
        val lines = (1..30).joinToString("\n") { "Log line $it" } + "\n\n"
        logFile.writeText(lines)

        val result = CliRunner.readStartupDiagnostics(logFile, 0L, maxLines = 5)
        val expected = (26..30).joinToString("\n") { "Log line $it" }
        assertEquals(expected, result)
    }

    @Test
    fun `readStartupDiagnostics handles file length less than start offset`(@TempDir tempDir: File) {
        val logFile = File(tempDir, "daemon.log")
        logFile.writeText("Short line")

        val result = CliRunner.readStartupDiagnostics(logFile, 500L)
        assertEquals("Short line", result)
    }

    @Test
    fun `readStartupDiagnostics reads tail when appended bytes exceed max limit`(@TempDir tempDir: File) {
        val logFile = File(tempDir, "daemon.log")
        val largePadding = "A".repeat(70 * 1024) + "\n"
        val errorSuffix = "Important error at the end"
        logFile.writeText(largePadding + errorSuffix)

        val result = CliRunner.readStartupDiagnostics(logFile, 0L, maxLines = 1)
        assertEquals(errorSuffix, result)
    }

    @Test
    fun `ensureDaemonAndSend surfaces diagnostics and logs on startup failure`(@TempDir tempDir: File) {
        val testLogFile = File(tempDir, "daemon.log")
        DaemonConfig.PORT = 65431

        val javaBin = System.getenv("JAVA_HOME")?.let { "$it/bin/java" } ?: "java"
        val mockProcessBuilder: (Int) -> ProcessBuilder = {
            ProcessBuilder(javaBin, "-cp", "invalid-classpath", "dev.shreyaspatil.debroid.NonExistentMainClass")
        }

        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        val startTime = System.currentTimeMillis()
        try {
            CliRunner.ensureDaemonAndSend(
                request = DaemonRequest.Detach("s_test"),
                logFile = testLogFile,
                processBuilder = mockProcessBuilder
            )
        } finally {
            System.setOut(originalOut)
        }
        val duration = System.currentTimeMillis() - startTime

        assertTrue(duration < 4000, "Should fast-fail rather than waiting full 5000ms timeout (took ${duration}ms)")
        assertTrue(testLogFile.exists(), "Log file should be created")
        val logContent = testLogFile.readText()
        assertTrue(
            logContent.contains("Could not find or load main class") ||
                logContent.contains("ClassNotFoundException"),
            "Log file should contain failure diagnostics: $logContent"
        )

        val errorJson = outContent.toString().trim()
        val error = Json.decodeFromString<CliDebugError>(errorJson)
        assertEquals(CliErrorCode.CLI_ERROR.name, error.errorCode)
        assertFalse(error.retryable)
        assertTrue(
            error.message.startsWith("Failed to start background daemon:"),
            "Error message should indicate daemon startup failure: ${error.message}"
        )
        assertTrue(
            error.message.contains("Could not find or load main class") ||
                error.message.contains("ClassNotFoundException"),
            "Error message should contain diagnostic output: ${error.message}"
        )
    }

    @Test
    fun `ensureDaemonAndSend falls back to log path when process exits without output`(@TempDir tempDir: File) {
        val testLogFile = File(tempDir, "daemon.log")
        DaemonConfig.PORT = 65431

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val mockProcessBuilder: (Int) -> ProcessBuilder = {
            if (isWindows) {
                ProcessBuilder("cmd", "/c", "exit 1")
            } else {
                ProcessBuilder("sh", "-c", "exit 1")
            }
        }

        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.ensureDaemonAndSend(
                request = DaemonRequest.Detach("s_test"),
                logFile = testLogFile,
                processBuilder = mockProcessBuilder
            )
        } finally {
            System.setOut(originalOut)
        }

        val errorJson = outContent.toString().trim()
        val error = Json.decodeFromString<CliDebugError>(errorJson)
        assertEquals(CliErrorCode.CLI_ERROR.name, error.errorCode)
        assertFalse(error.retryable)
        assertEquals(
            "Failed to start background daemon (log: ${testLogFile.absolutePath})",
            error.message
        )
    }

    @Test
    fun `ensureDaemonAndSend catches spawn exceptions and returns CliDebugError`(@TempDir tempDir: File) {
        val testLogFile = File(tempDir, "daemon.log")
        DaemonConfig.PORT = 65431

        val mockProcessBuilder: (Int) -> ProcessBuilder = {
            ProcessBuilder("/non/existent/path/to/java_binary")
        }

        val originalOut = System.out
        val outContent = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outContent))

        try {
            CliRunner.ensureDaemonAndSend(
                request = DaemonRequest.Detach("s_test"),
                logFile = testLogFile,
                processBuilder = mockProcessBuilder
            )
        } finally {
            System.setOut(originalOut)
        }

        val errorJson = outContent.toString().trim()
        val error = Json.decodeFromString<CliDebugError>(errorJson)
        assertEquals(CliErrorCode.CLI_ERROR.name, error.errorCode)
        assertFalse(error.retryable)
        assertTrue(error.message.startsWith("Failed to start background daemon:"))
        assertTrue(
            error.message.contains("/non/existent/path/to/java_binary") ||
                error.message.contains("Cannot run program")
        )
    }
}
