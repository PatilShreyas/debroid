package dev.shreyaspatil.debroid.cli

import com.github.ajalt.clikt.core.context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
}
