package dev.shreyaspatil.debroid.cli.update

import dev.shreyaspatil.debroid.cli.VERSION
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Handles locating the installed debroid executable binary and performing atomic in-place file replacement.
 */
class BinaryUpdater(
    private val httpClient: HttpClient = defaultHttpClient()
) {

    fun resolveCurrentBinaryLocation(): File? {
        // Option 1: Command path from ProcessHandle if running standalone
        val processCmd = try {
            ProcessHandle.current().info().command().orElse(null)
        } catch (_: Throwable) {
            null
        }

        if (processCmd != null && processCmd.endsWith("debroid")) {
            val file = File(processCmd)
            if (file.exists() && file.isFile) return file
        }

        // Option 2: Default installation path (~/.local/bin/debroid)
        val homeDir = System.getenv("HOME") ?: System.getProperty("user.home")
        val userLocalBin = File(homeDir, ".local/bin/debroid")
        if (userLocalBin.exists() && userLocalBin.isFile) return userLocalBin

        // Option 3: System path (/usr/local/bin/debroid)
        val usrLocalBin = File("/usr/local/bin/debroid")
        if (usrLocalBin.exists() && usrLocalBin.isFile) return usrLocalBin

        return null
    }

    fun downloadAndReplaceBinary(downloadUrl: String, targetBinary: File): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "debroid-cli/$VERSION")
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .GET()
                .build()

            val tempFile = File.createTempFile("debroid-update", ".tmp")
            val response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofFile(tempFile.toPath())
            )

            if (response.statusCode() != HTTP_OK) {
                tempFile.delete()
                return false
            }

            tempFile.setExecutable(true, false)

            // Atomic in-place POSIX file replacement
            val backupFile = File(targetBinary.parentFile, "${targetBinary.name}.bak")
            if (backupFile.exists()) backupFile.delete()

            if (targetBinary.renameTo(backupFile)) {
                if (tempFile.renameTo(targetBinary)) {
                    backupFile.delete()
                    true
                } else {
                    backupFile.renameTo(targetBinary)
                    tempFile.delete()
                    false
                }
            } else {
                tempFile.delete()
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private const val DOWNLOAD_TIMEOUT_SECONDS = 30L

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
        }
    }
}
