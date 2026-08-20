package dev.shreyaspatil.debroid.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

class DefaultCommandRunnerTest {

    private val runner = DefaultCommandRunner()

    @Test
    fun `runCommand returns trimmed output for successful command`() {
        val result = runner.runCommand(listOf("sh", "-c", "echo 'hello debroid'"))

        assertTrue(result.isSuccess)
        assertEquals("hello debroid", result.getOrNull())
    }

    @Test
    fun `runCommand returns failure on non-zero exit code`() {
        val result = runner.runCommand(listOf("sh", "-c", "echo 'something went wrong' >&2; exit 42"))

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception?.message?.contains("Command exited with code 42") == true)
        assertTrue(exception?.message?.contains("something went wrong") == true)
    }

    @Test
    fun `runCommand enforces timeout on long-running process without hanging`() {
        var result: Result<String>? = null
        val durationMs = measureTimeMillis {
            result = runner.runCommand(listOf("sh", "-c", "sleep 5"), timeoutSeconds = 1)
        }

        assertTrue(durationMs < 3000, "Command should time out in ~1s instead of hanging, took $durationMs ms")
        assertTrue(result?.isFailure == true)
        assertTrue(result?.exceptionOrNull()?.message?.contains("Command timed out after 1 seconds") == true)
    }

    @Test
    fun `runCommand handles large output stream without pipe deadlock`() {
        val script = "for i in \$(seq 1 1000); do echo \"line \$i large payload data block\"; done"
        val result = runner.runCommand(listOf("sh", "-c", script), timeoutSeconds = 5)

        assertTrue(result.isSuccess)
        val output = result.getOrNull() ?: ""
        assertTrue(output.contains("line 1 large payload data block"))
        assertTrue(output.contains("line 1000 large payload data block"))
    }

    @Test
    fun `runCommand returns failure when binary does not exist`() {
        val result = runner.runCommand(listOf("non_existent_binary_xyz_123"))

        assertTrue(result.isFailure)
    }
}
