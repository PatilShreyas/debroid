package dev.shreyaspatil.debroid.adb

import dev.shreyaspatil.debroid.models.DebugError
import dev.shreyaspatil.debroid.models.ErrorCode
import java.io.File

class AdbManager(
    private val adbPath: String = findAdbExecutable(),
    private val commandRunner: CommandRunner = DefaultCommandRunner()
) {
    companion object {
        private fun findAdbExecutable(): String {
            val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            val adbFromHome = androidHome?.let { File(it, "platform-tools/adb") }?.takeIf { it.exists() }?.absolutePath

            val defaultMacSdk = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
            val adbFromMac = defaultMacSdk.takeIf { it.exists() }?.absolutePath

            return adbFromHome ?: adbFromMac ?: "adb"
        }
    }

    internal fun runAdb(vararg args: String, timeoutSeconds: Long = 10): Result<String> {
        val command = listOf(adbPath) + args
        return commandRunner.runCommand(command, timeoutSeconds)
    }

    fun isAppDebuggable(appId: String): Result<Boolean> {
        // 1. Primary check: `run-as <appId> true` (Android's native debuggability check)
        val runAsRes = runAdb("shell", "run-as", appId, "true")
        if (runAsRes.isSuccess) {
            val output = runAsRes.getOrThrow().trim()
            if (output.isEmpty() || output == "true") {
                return Result.success(true)
            }
            if (output.contains("unknown package") || output.contains("Unable to find package")) {
                return Result.failure(
                    DebugException(ErrorCode.APP_NOT_DEBUGGABLE, "Package $appId is not installed on the device.")
                )
            }
        }

        // 2. Fallback check: `dumpsys package <appId>` checking package flags
        return runAdb("shell", "dumpsys", "package", appId).fold(
            onSuccess = { output ->
                if (output.contains("Unable to find package") || output.isBlank()) {
                    Result.failure(
                        DebugException(ErrorCode.APP_NOT_DEBUGGABLE, "Package $appId is not installed on the device.")
                    )
                } else {
                    val isDebuggable = (output.contains("flags=[") || output.contains("pkgFlags=[")) &&
                        output.contains("DEBUGGABLE")
                    Result.success(isDebuggable)
                }
            },
            onFailure = {
                Result.failure(
                    DebugException(ErrorCode.ADB_ERROR, "Failed to check debuggability for $appId: ${it.message}")
                )
            }
        )
    }

    /**
     * Attempts to find the Process ID (PID) of the given application.
     *
     * Mechanism:
     * 1. First, we try `pidof <appId>`. This is the most direct and reliable
     *    method on modern Android devices to get a package's PID.
     * 2. If `pidof` fails (often missing or restricted on older Android versions),
     *    we fallback to parsing the output of `ps -A`.
     *
     * @param appId The package name of the application.
     * @return Result containing the PID or a DebugException if not found.
     */
    fun findPid(appId: String): Result<Int> {
        val pidOutput = runAdb("shell", "pidof", appId)
        val directPid = pidOutput.getOrNull()?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toIntOrNull()

        if (directPid != null && directPid > 0) {
            return Result.success(directPid)
        }

        // Fallback to ps
        val psOutput = runAdb("shell", "ps", "-A").getOrNull() ?: ""
        val fallbackPid = psOutput.lines()
            .firstOrNull { it.contains(appId) }
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.getOrNull(1)
            ?.toIntOrNull()

        return fallbackPid?.let { Result.success(it) }
            ?: Result.failure(DebugException(ErrorCode.APP_NOT_DEBUGGABLE, "App $appId is not currently running."))
    }

    /**
     * Launches the application and suspends it immediately to wait for the debugger.
     *
     * Mechanism:
     * 1. `am set-debug-app -w <appId>` tells the Android ActivityManager to wait (`-w`)
     *    for a JDWP debugger connection as soon as the specified app process starts.
     * 2. `monkey -p <appId> -c android.intent.category.LAUNCHER 1` is a robust way to
     *    launch the main default activity of an app without needing to know its exact
     *    ComponentName or Activity class name.
     * 3. Finally, we poll for the PID since the launch is asynchronous.
     *
     * @param appId The package name to launch.
     * @return Result containing the PID once the app has successfully started.
     */
    private fun getMainActivity(appId: String): String? {
        val result = runAdb(
            "shell", "cmd", "package", "resolve-activity",
            "--brief", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", appId
        )
        val defaultActivity = result.getOrNull()?.lines()?.lastOrNull { it.contains("/") }?.trim()

        val isMain = defaultActivity != null &&
            !defaultActivity.contains("leakcanary", ignoreCase = true) &&
            defaultActivity.startsWith(appId)
        if (isMain) {
            return defaultActivity
        }

        val dumpResult = runAdb("shell", "pm", "dump", appId)
        val dump = dumpResult.getOrNull() ?: return null

        val componentRegex = Regex("""$appId/([a-zA-Z0-9_.]+)""")
        val matches = componentRegex.findAll(dump).map { it.value }.distinct().toList()

        val valid = matches.filter { !it.contains("leakcanary", ignoreCase = true) }
        return valid.firstOrNull { it.contains("Main", ignoreCase = true) } ?: valid.firstOrNull()
    }

    fun launchAppSuspended(appId: String): Result<Int> {
        val setDebugAppResult = runAdb("shell", "am", "set-debug-app", "-w", appId)
        if (setDebugAppResult.isFailure) {
            return Result.failure(
                DebugException(
                    ErrorCode.ADB_ERROR,
                    "Failed to set-debug-app for $appId: ${setDebugAppResult.exceptionOrNull()?.message}"
                )
            )
        }

        val component = getMainActivity(appId)
        val launchResult = if (component != null) {
            runAdb("shell", "am", "start", "-D", "-n", component)
        } else {
            runAdb("shell", "monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1")
        }

        if (launchResult.isFailure) {
            return Result.failure(
                DebugException(
                    ErrorCode.ADB_ERROR,
                    "Failed to launch app $appId: ${launchResult.exceptionOrNull()?.message}"
                )
            )
        }

        val maxRetries = 10
        val sleepMillis = 500L
        var attempt = 0
        while (attempt < maxRetries) {
            attempt++
            Thread.sleep(sleepMillis)
            val pidRes = findPid(appId)
            if (pidRes.isSuccess) return pidRes
        }

        return Result.failure(
            DebugException(ErrorCode.ADB_ERROR, "App $appId was launched but PID could not be found.")
        )
    }

    fun forwardJdwpPort(localPort: Int, pid: Int): Result<Unit> {
        val result = runAdb("forward", "tcp:$localPort", "jdwp:$pid")
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                DebugException(
                    ErrorCode.ADB_ERROR,
                    "Failed to forward port $localPort to jdwp:$pid: ${result.exceptionOrNull()?.message}"
                )
            )
        }
    }

    fun removePortForward(localPort: Int) {
        runAdb("forward", "--remove", "tcp:$localPort")
    }

    /**
     * Clears the persistent "wait for debugger" flag set by `am set-debug-app -w`.
     *
     * Reasoning: `am set-debug-app -w` persists across launches. If we don't clear it,
     * the next normal (non-debug) launch of the same app will hang silently waiting
     * for a debugger connection that never comes. This must be invoked when a session
     * that launched the app in suspended mode is detached.
     */
    fun clearDebugApp(): Result<Unit> {
        return runAdb("shell", "am", "clear-debug-app").mapCatching { }
    }
}

class DebugException(val code: ErrorCode, override val message: String) : Exception(message) {
    fun toDebugError(retryable: Boolean = false) = DebugError(
        errorCode = code.name,
        message = message,
        retryable = retryable
    )
}
