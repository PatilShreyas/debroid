package dev.shreyaspatil.debroid.adb

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface CommandRunner {
    fun runCommand(command: List<String>, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Result<String>

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10L
    }
}

class DefaultCommandRunner : CommandRunner {
    override fun runCommand(command: List<String>, timeoutSeconds: Long): Result<String> {
        var process: Process? = null
        var outputFuture: CompletableFuture<String>? = null
        return try {
            val proc = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process = proc

            outputFuture = CompletableFuture.supplyAsync {
                proc.inputStream.bufferedReader().use { it.readText() }
            }

            val completed = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                proc.destroyForcibly()
                outputFuture.cancel(true)
                Result.failure(
                    Exception("Command timed out after $timeoutSeconds seconds: ${command.joinToString(" ")}")
                )
            } else {
                val output = try {
                    outputFuture.get(timeoutSeconds, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    ""
                }

                if (proc.exitValue() != 0) {
                    Result.failure(Exception("Command exited with code ${proc.exitValue()}: $output"))
                } else {
                    Result.success(output.trim())
                }
            }
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            outputFuture?.cancel(true)
            Thread.currentThread().interrupt()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
