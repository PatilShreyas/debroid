package dev.shreyaspatil.debroid.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import dev.shreyaspatil.debroid.cli.models.CliBreakpointInfo
import dev.shreyaspatil.debroid.cli.models.CliDebugError
import dev.shreyaspatil.debroid.cli.models.CliDetachedResult
import dev.shreyaspatil.debroid.cli.models.CliEventPollResult
import dev.shreyaspatil.debroid.cli.models.CliExceptionBreakpointResult
import dev.shreyaspatil.debroid.cli.models.CliObjectInspectionResult
import dev.shreyaspatil.debroid.cli.models.CliPauseStateResult
import dev.shreyaspatil.debroid.cli.models.CliSessionStatus
import dev.shreyaspatil.debroid.cli.models.CliShutdownResult
import dev.shreyaspatil.debroid.cli.models.CliStackFrameInfo
import dev.shreyaspatil.debroid.cli.models.CliStatusResult
import dev.shreyaspatil.debroid.cli.models.CliUpdateResult
import dev.shreyaspatil.debroid.cli.models.CliVariableInfo
import dev.shreyaspatil.debroid.cli.models.CliWatchpointResult
import dev.shreyaspatil.debroid.cli.models.DaemonIpcRequest
import dev.shreyaspatil.debroid.cli.models.DaemonRequest
import dev.shreyaspatil.debroid.cli.models.JsonSchemaGenerator
import dev.shreyaspatil.debroid.cli.update.AutoUpdateManager
import dev.shreyaspatil.debroid.models.StepAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import kotlin.system.exitProcess

object CliRunner {

    private val json = Json { encodeDefaults = true }

    abstract class BaseJsonCommand(
        name: String? = null,
        help: String = "",
        epilog: String = "",
        serializer: KSerializer<*>? = null
    ) : CliktCommand(name = name, help = help, epilog = epilog) {
        val pretty by option(
            "--pretty",
            help = "Format JSON output with line breaks and indentation"
        ).flag(default = false)

        init {
            if (serializer != null) {
                eagerOption("--schema", help = "Print the JSON response schema for this command") {
                    val schemaElement = JsonSchemaGenerator.generate(serializer.descriptor)
                    val json = Json { encodeDefaults = true }
                    println(json.encodeToString(JsonElement.serializer(), schemaElement))
                    throw PrintMessage("")
                }
            }
        }

        protected fun ensureDaemonAndSend(request: DaemonRequest) {
            CliRunner.ensureDaemonAndSend(request, pretty)
        }
    }

    @Suppress("MagicNumber", "MaxLineLength", "TooGenericExceptionCaught")
    private fun ensureDaemonAndSend(request: DaemonRequest, pretty: Boolean = false) {
        if (!DaemonServer.isDaemonRunning()) {
            val javaBin = System.getenv("JAVA_HOME")?.let { "$it/bin/java" } ?: "java"
            val classPath = System.getProperty("java.class.path")

            // Start the daemon process in the background
            val builder = ProcessBuilder(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "--add-exports=jdk.jdi/com.sun.tools.example.debug.expr=ALL-UNNAMED",
                "-cp",
                classPath,
                "dev.shreyaspatil.debroid.MainKt",
                "--port",
                DaemonConfig.PORT.toString(),
                "daemon"
            )
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.start()

            var started = false
            var attempts = 0
            while (!started && attempts < 50) {
                attempts++
                Thread.sleep(100)
                if (DaemonServer.isDaemonRunning()) {
                    started = true
                }
            }
            if (!started) {
                println(json.encodeToString(CliDebugError("CLI_ERROR", "Failed to start background daemon", false)))
                return
            }
        }

        try {
            Socket(DaemonConfig.HOST, DaemonConfig.PORT).use { socket ->
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                val ipcRequest = DaemonIpcRequest(pretty = pretty, request = request)
                writer.println(json.encodeToString(ipcRequest))
                println(reader.readText())
            }
        } catch (e: Exception) {
            println(
                json.encodeToString(
                    CliDebugError("CLI_ERROR", "Failed to communicate with daemon: ${e.message}", false)
                )
            )
        }
    }

    @Suppress("MagicNumber")
    class DebroidCommand : CliktCommand(
        name = "debroid",
        help = "🤖 Debroid - Autonomous Debugger for Android"
    ) {
        init {
            versionOption(VERSION)
        }
        private val port by option("--port", "-p", help = "Daemon server port").int().default(DaemonConfig.PORT)

        override fun run() {
            DaemonConfig.PORT = port
        }
    }

    class DaemonCommand : CliktCommand(
        name = "daemon",
        help = "Starts the Debroid persistent background daemon",
        epilog = "Runs indefinitely to maintain the JDWP debugger socket state."
    ) {
        override fun run() {
            DaemonServer.startDaemon()
        }
    }

    class ShutdownCommand : BaseJsonCommand(
        name = "stop",
        help = "Shuts down the Debroid persistent background daemon and detaches all active sessions.",
        epilog = "Safely disposes all active debug sessions and closes ADB port forwards.",
        serializer = CliShutdownResult.serializer()
    ) {
        override fun run() {
            if (!DaemonServer.isDaemonRunning()) {
                val jsonRes = Json { prettyPrint = pretty }
                println(jsonRes.encodeToString(CliShutdownResult(shutdown = true, message = "Daemon is not running")))
                return
            }
            ensureDaemonAndSend(DaemonRequest.Shutdown())
        }
    }

    class LaunchCommand : BaseJsonCommand(
        name = "launch",
        help = "Launches an application in suspended mode and attaches the debugger.",
        epilog = "This is the safest way to debug initialization code. " +
            "It forces the app to wait for the debugger before executing.",
        serializer = CliSessionStatus.serializer()
    ) {
        private val appId by argument("app_id", help = "The Android Application ID (e.g., com.example.app) to launch.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Launch(appId))
    }

    class AttachCommand : BaseJsonCommand(
        name = "attach",
        help = "Attaches the debugger to an already running application process.",
        serializer = CliSessionStatus.serializer()
    ) {
        private val appId by argument("app_id", help = "The Android Application ID of the running app.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Attach(appId))
    }

    class DetachCommand : BaseJsonCommand(
        name = "detach",
        help = "Detaches the debugger and safely terminates the debug session.",
        epilog = "The application will continue to run normally after detachment.",
        serializer = CliDetachedResult.serializer()
    ) {
        private val sessionId by argument(
            "session_id",
            help = "The unique session ID returned when launching or attaching."
        )
        override fun run() = ensureDaemonAndSend(DaemonRequest.Detach(sessionId))
    }

    class BreakCommand : BaseJsonCommand(
        name = "break",
        help = "Sets a line breakpoint in a specific source file.",
        serializer = CliBreakpointInfo.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val file by argument("file", help = "The source file name (e.g., MainActivity.kt).")
        private val line by argument("line", help = "The 1-indexed line number in the source file.").int()
        private val packageName by option(
            "-p",
            "--package",
            help = "Fully qualified package name of the source file (e.g. com.example.app.search), if known. " +
                "Lets the daemon resolve the class with a single targeted lookup instead of scanning every loaded " +
                "class, which is much faster for large apps."
        )

        override fun run() = ensureDaemonAndSend(
            DaemonRequest.Break(
                sessionId = sessionId,
                file = file,
                line = line,
                packageName = packageName
            )
        )
    }

    class RemoveBreakCommand : BaseJsonCommand(
        name = "remove-break",
        help = "Removes a previously set line breakpoint.",
        serializer = CliStatusResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val breakpointId by argument("breakpoint_id", help = "The breakpoint ID returned by break.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.RemoveBreak(sessionId, breakpointId))
    }

    class CatchExceptionCommand : BaseJsonCommand(
        name = "catch-exception",
        help = "Sets a breakpoint that triggers when an exception is thrown.",
        epilog = "By default only UNCAUGHT exceptions are trapped. (Note: On Android, fatal app " +
            "crashes are usually caught by the system's UncaughtExceptionHandler. To trap real app crashes, " +
            "use --caught and provide a specific exception class like java.lang.RuntimeException).",
        serializer = CliExceptionBreakpointResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val className by argument(
            "class_name",
            help = "Optional fully qualified exception class name (e.g., java.lang.NullPointerException). " +
                "If omitted, traps all matching exceptions."
        ).optional()
        private val caught by option("--caught").flag("--no-caught", default = false)
        private val uncaught by option("--uncaught").flag("--no-uncaught", default = true)
        override fun run() = ensureDaemonAndSend(
            DaemonRequest.CatchException(sessionId, className, notifyCaught = caught, notifyUncaught = uncaught)
        )
    }

    class RemoveCatchExceptionCommand : BaseJsonCommand(
        name = "remove-catch-exception",
        help = "Removes a previously set exception breakpoint.",
        serializer = CliStatusResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val bpId by argument(
            "exception_breakpoint_id",
            help = "The exception breakpoint ID returned by catch-exception."
        )
        override fun run() = ensureDaemonAndSend(DaemonRequest.RemoveCatchException(sessionId, bpId))
    }

    class WatchCommand : BaseJsonCommand(
        name = "watch",
        help = "Sets a watchpoint on a specific field to monitor access and/or modifications.",
        epilog = "Defaults to BOTH access and modify. Pass --no-access or --no-modify to disable either.",
        serializer = CliWatchpointResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val className by argument("class_name", help = "The fully qualified class name containing the field.")
        private val fieldName by argument("field_name", help = "The exact name of the field to watch.")
        private val access by option(
            "--access",
            help = "Trap field reads (default: on)."
        ).flag("--no-access", default = true)
        private val modify by option(
            "--modify",
            help = "Trap field writes (default: on)."
        ).flag("--no-modify", default = true)
        override fun run() = ensureDaemonAndSend(
            DaemonRequest.Watch(
                sessionId = sessionId,
                className = className,
                fieldName = fieldName,
                access = access,
                modify = modify
            )
        )
    }

    class RemoveWatchCommand : BaseJsonCommand(
        name = "remove-watch",
        help = "Removes a previously set watchpoint.",
        serializer = CliStatusResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val watchpointId by argument("watchpoint_id", help = "The watchpoint ID returned by watch.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.RemoveWatch(sessionId, watchpointId))
    }

    class ThreadsCommand : BaseJsonCommand(
        name = "threads",
        help = "Lists all active threads in the target application.",
        epilog = "Useful for finding the thread ID (e.g., '1' for main thread) to inspect locals or pause state.",
        serializer = ListSerializer(CliStatusResult.serializer())
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Threads(sessionId))
    }

    class LocalsCommand : BaseJsonCommand(
        name = "locals",
        help = "Retrieves shallow local variables for the top stack frame of a suspended thread.",
        serializer = ListSerializer(CliVariableInfo.serializer())
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val threadId by argument("thread_id", help = "The ID of the suspended thread to inspect.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Locals(sessionId, threadId))
    }

    class PauseStateCommand : BaseJsonCommand(
        name = "pause-state",
        help = "Retrieves the current execution state (stack trace, current file, line) of a suspended thread.",
        serializer = CliPauseStateResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val threadId by argument("thread_id", help = "The ID of the suspended thread.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.PauseState(sessionId, threadId))
    }

    class SetVarCommand : BaseJsonCommand(
        name = "set-var",
        help = "Mutates the value of a local variable in the currently suspended frame memory.",
        serializer = CliVariableInfo.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val threadId by argument("thread_id", help = "The ID of the suspended thread.")
        private val varName by argument("variable_name", help = "The name of the local variable to mutate.")
        private val newValue by argument(
            "new_value",
            help = "The new value to assign, parsed as a Java expression. " +
                "Examples: " +
                "Int/Long: `10`, `100L` | " +
                "Float/Double: `10.5f`, `20.5`, `20.5d` | " +
                "Boolean: `true`, `false` | " +
                "String: `\"my string\"` (ensure quotes are escaped in shell, e.g., '\"value\"' or \\\"value\\\")"
        )
        override fun run() = ensureDaemonAndSend(
            DaemonRequest.SetVar(sessionId = sessionId, threadId = threadId, varName = varName, newValue = newValue)
        )
    }

    class EvalCommand : BaseJsonCommand(
        name = "eval",
        help = "Evaluates a raw string expression or performs string concatenation within the debugged process.",
        serializer = CliVariableInfo.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val threadId by argument("thread_id", help = "The ID of the suspended thread.")
        private val exprTokens by argument("expression", help = "The expression to evaluate.").multiple()
        override fun run() = ensureDaemonAndSend(
            DaemonRequest.Eval(sessionId = sessionId, threadId = threadId, expression = exprTokens.joinToString(" "))
        )
    }

    class ResumeCommand : BaseJsonCommand(
        name = "resume",
        help = "Resumes execution of all threads.",
        serializer = CliStatusResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Resume(sessionId))
    }

    class PollCommand : BaseJsonCommand(
        name = "poll",
        help = "Polls the JDWP event queue for new debugger events (like breakpoints hit).",
        epilog = "Returns events sequentially. Provide the cursor returned by the last poll to get newer events.",
        serializer = CliEventPollResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val cursor by argument(
            "cursor",
            help = "The cursor token from the previous poll response."
        ).default("0")
        private val withStacktrace by option(
            "--with-stacktrace",
            help = "Include the stacktrace for each event"
        ).flag(default = false)
        override fun run() = ensureDaemonAndSend(DaemonRequest.Poll(sessionId, cursor, withStacktrace))
    }

    class FramesCommand : BaseJsonCommand(
        name = "frames",
        help = "Retrieves the stack frames for a suspended thread.",
        serializer = ListSerializer(CliStackFrameInfo.serializer())
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val threadId by argument("thread_id", help = "The ID of the suspended thread.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Frames(sessionId, threadId))
    }

    class CoroutineCommand : BaseJsonCommand(
        name = "coroutine",
        help = "Retrieves local variables from a suspended coroutine continuation object.",
        serializer = MapSerializer(String.serializer(), CliVariableInfo.serializer())
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val continuationId by argument("continuation_id", help = "The object ID of the Coroutine Continuation.")
        override fun run() = ensureDaemonAndSend(DaemonRequest.Coroutine(sessionId, continuationId))
    }

    class InspectCommand : BaseJsonCommand(
        name = "inspect",
        help = "Inspects an object's fields up to a specified depth.",
        serializer = CliObjectInspectionResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val objectId by argument("object_id", help = "The ID of the object to inspect.")
        private val maxDepth by option("--max-depth", "-d", help = "Maximum depth to inspect").int().default(1)
        override fun run() = ensureDaemonAndSend(
            DaemonRequest.Inspect(sessionId = sessionId, objectId = objectId, maxDepth = maxDepth)
        )
    }

    class StepCommand : BaseJsonCommand(
        name = "step",
        help = "Performs a stepping action (over, into, out, resume, resume-all) on a suspended thread.",
        serializer = CliStatusResult.serializer()
    ) {
        private val sessionId by argument("session_id", help = "The active debug session ID.")
        private val threadId by argument("thread_id", help = "The ID of the suspended thread.")
        private val action by argument("action", help = "The step action to perform.").enum<StepAction>()
        override fun run() = ensureDaemonAndSend(DaemonRequest.Step(sessionId, threadId, action))
    }

    class SkillCommand : CliktCommand(
        name = "skill",
        help = "Prints embedded AI Agent skill instructions (SKILL.md) to stdout " +
            "(e.g. debroid skill > .cursor/rules/debroid.md)."
    ) {
        override fun run() {
            val skillContent = object {}.javaClass.getResourceAsStream("/SKILL.md")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Embedded SKILL.md resource not found in CLI binary.")
            print(skillContent)
        }
    }

    class DebroidCli : CliktCommand(name = "debroid") {
        override fun run() = Unit
    }

    private fun createCli(): CliktCommand {
        return DebroidCli()
            .subcommands(
                DaemonCommand(),
                ShutdownCommand(),
                LaunchCommand(),
                AttachCommand(),
                DetachCommand(),
                BreakCommand(),
                RemoveBreakCommand(),
                CatchExceptionCommand(),
                RemoveCatchExceptionCommand(),
                WatchCommand(),
                RemoveWatchCommand(),
                ThreadsCommand(),
                LocalsCommand(),
                PauseStateCommand(),
                SetVarCommand(),
                EvalCommand(),
                ResumeCommand(),
                PollCommand(),
                FramesCommand(),
                CoroutineCommand(),
                InspectCommand(),
                StepCommand(),
                SkillCommand(),
                UpdateCommand()
            )
    }

    class UpdateCommand : BaseJsonCommand(
        name = "update",
        help = "Checks for CLI updates or performs an in-place self-update to the latest release",
        serializer = CliUpdateResult.serializer()
    ) {
        private val checkOnly by option(
            "--check-only",
            help = "Check for available updates without downloading or updating"
        ).flag(default = false)

        override fun run() {
            val result = AutoUpdateManager.DEFAULT.checkOrUpdate(checkOnly)
            val jsonFormatter = if (pretty) {
                Json {
                    prettyPrint = true
                    encodeDefaults = true
                }
            } else {
                Json {
                    encodeDefaults = true
                }
            }
            println(jsonFormatter.encodeToString(CliUpdateResult.serializer(), result))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun execute(args: Array<String>) {
        AutoUpdateManager.DEFAULT.checkAndPerformSilentAutoUpdateAsync()

        val cli = createCli()
        if (args.isEmpty()) {
            println(cli.getFormattedHelp())
            return
        }

        try {
            cli.parse(args.toList())
        } catch (e: CliktError) {
            when (e) {
                is PrintHelpMessage -> println(e.context?.command?.getFormattedHelp() ?: e.message)
                is PrintMessage -> println(e.message)
                is UsageError -> {
                    println(e.message)
                    e.context?.command?.getFormattedHelp()?.let { println(it) }
                }
                else -> println(json.encodeToString(CliDebugError("CLI_ERROR", e.message ?: "Unknown error", false)))
            }
            exitProcess(1)
        } catch (e: Exception) {
            println(json.encodeToString(CliDebugError("CLI_ERROR", e.message ?: "Unknown error", false)))
            exitProcess(1)
        }
    }
}
