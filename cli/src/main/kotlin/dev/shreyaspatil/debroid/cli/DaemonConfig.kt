package dev.shreyaspatil.debroid.cli

import java.io.File

@Suppress("MagicNumber")
object DaemonConfig {
    const val HOST = "127.0.0.1"
    var PORT = 9876
    const val BACKLOG = 50
    val logFile: File = defaultLogFile()

    fun defaultLogFile(): File {
        val homeDir = System.getenv("HOME") ?: System.getProperty("user.home")
        val debroidDir = File(homeDir, ".debroid")
        return File(debroidDir, "daemon.log")
    }
}
