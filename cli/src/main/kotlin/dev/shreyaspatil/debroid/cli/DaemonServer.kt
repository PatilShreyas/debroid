package dev.shreyaspatil.debroid.cli

import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.cli.models.CliDebugError
import dev.shreyaspatil.debroid.cli.models.CliDetachedResult
import dev.shreyaspatil.debroid.cli.models.CliExceptionBreakpointResult
import dev.shreyaspatil.debroid.cli.models.CliShutdownResult
import dev.shreyaspatil.debroid.cli.models.CliStatusResult
import dev.shreyaspatil.debroid.cli.models.CliWatchpointResult
import dev.shreyaspatil.debroid.cli.models.DaemonRequest
import dev.shreyaspatil.debroid.cli.models.toCli
import dev.shreyaspatil.debroid.jdi.JdiSessionManager
import dev.shreyaspatil.debroid.models.StepAction
import dev.shreyaspatil.debroid.models.VariableScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

object DaemonServer {
    private val sessionManager = JdiSessionManager()
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun isDaemonRunning(): Boolean {
        return try {
            Socket(DaemonConfig.HOST, DaemonConfig.PORT).use { true }
        } catch (_: Exception) {
            false
        }
    }

    fun startDaemon() {
        if (isDaemonRunning()) {
            val result = CliStatusResult("Debroid daemon is already running on port ${DaemonConfig.PORT}.")
            println(Json.encodeToString(result))
            return
        }

        val serverSocket = ServerSocket(DaemonConfig.PORT, DaemonConfig.BACKLOG, InetAddress.getByName(DaemonConfig.HOST))
        val startResult = CliStatusResult("🤖 Debroid Daemon started on ${DaemonConfig.HOST}:${DaemonConfig.PORT}...")
        println(Json.encodeToString(startResult))

        val executor = Executors.newCachedThreadPool()
        while (true) {
            val socket = serverSocket.accept()
            executor.submit { handleClient(socket) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val line = reader.readLine() ?: return

            val response = try {
                val request = json.decodeFromString<DaemonRequest>(line)
                processCommand(request)
            } catch (e: Exception) {
                json.encodeToString(CliDebugError("CLI_ERROR", "Invalid command format: ${e.message}", false))
            }
            writer.println(response)
        } catch (e: Exception) {
            // Socket handling error
        } finally {
            socket.close()
        }
    }

    @Suppress("TooGenericExceptionCaught", "MagicNumber", "LongMethod", "CyclomaticComplexMethod")
    private fun processCommand(request: DaemonRequest): String {
        return try {
            when (request) {
                is DaemonRequest.Launch -> {
                    val session = sessionManager.launchAndAttach(request.appId)
                    json.encodeToString(session.getStatus().toCli())
                }
                is DaemonRequest.Attach -> {
                    val session = sessionManager.attachToRunningApp(request.appId)
                    json.encodeToString(session.getStatus().toCli())
                }
                is DaemonRequest.Detach -> {
                    val ok = sessionManager.detachSession(request.sessionId)
                    json.encodeToString(CliDetachedResult(ok))
                }
                is DaemonRequest.Shutdown -> {
                    sessionManager.detachAllSessions()
                    val result = json.encodeToString(CliShutdownResult(true, "Daemon shut down successfully"))
                    Thread {
                        try {
                            Thread.sleep(100)
                        } catch (_: Throwable) {}
                        kotlin.system.exitProcess(0)
                    }.start()
                    result
                }
                is DaemonRequest.Break -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val bp = session.setBreakpoint(
                        file = request.file,
                        line = request.line,
                        condition = null,
                        packageName = request.packageName
                    )
                    json.encodeToString(bp.toCli())
                }
                is DaemonRequest.RemoveBreak -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val ok = session.removeBreakpoint(request.breakpointId)
                    json.encodeToString(CliStatusResult(if (ok) "removed" else "not_found"))
                }
                is DaemonRequest.CatchException -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val bpId = session.setExceptionBreakpoint(
                        className = request.className,
                        notifyCaught = request.notifyCaught,
                        notifyUncaught = request.notifyUncaught
                    )
                    json.encodeToString(CliExceptionBreakpointResult(bpId))
                }
                is DaemonRequest.RemoveCatchException -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val ok = session.removeExceptionBreakpoint(request.exceptionBreakpointId)
                    json.encodeToString(CliStatusResult(if (ok) "removed" else "not_found"))
                }
                is DaemonRequest.Watch -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val wpId = session.setWatchpoint(
                        request.className,
                        request.fieldName,
                        access = request.access,
                        modify = request.modify
                    )
                    json.encodeToString(CliWatchpointResult(wpId))
                }
                is DaemonRequest.RemoveWatch -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val ok = session.removeWatchpoint(request.watchpointId)
                    json.encodeToString(CliStatusResult(if (ok) "removed" else "not_found"))
                }
                is DaemonRequest.Threads -> {
                    val session = sessionManager.getSession(request.sessionId)
                    json.encodeToString(session.listThreads())
                }
                is DaemonRequest.Locals -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val vars = session.getVariables(request.threadId, VariableScope.LOCAL)
                    json.encodeToString(vars.map { it.toCli() })
                }
                is DaemonRequest.PauseState -> {
                    val session = sessionManager.getSession(request.sessionId)
                    json.encodeToString(session.getPauseState(request.threadId).toCli())
                }
                is DaemonRequest.SetVar -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val mutated = session.setVariable(
                        threadId = request.threadId,
                        varName = request.varName,
                        newValueStr = request.newValue
                    )
                    json.encodeToString(mutated.toCli())
                }
                is DaemonRequest.Eval -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val evalRes = session.evaluateExpression(request.threadId, request.expression)
                    json.encodeToString(evalRes.toCli())
                }
                is DaemonRequest.Resume -> {
                    val session = sessionManager.getSession(request.sessionId)
                    session.stepExecution(request.threadId, StepAction.RESUME_ALL)
                    json.encodeToString(CliStatusResult("resumed"))
                }
                is DaemonRequest.Poll -> {
                    val session = sessionManager.getSession(request.sessionId)
                    json.encodeToString(session.pollEvents(request.cursor, request.withStacktrace).toCli())
                }
                is DaemonRequest.Frames -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val frames = session.getStackFrames(request.threadId)
                    json.encodeToString(frames.map { it.toCli() })
                }
                is DaemonRequest.Coroutine -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val vars = session.getCoroutineFrame(request.continuationObjectId)
                    json.encodeToString(vars.mapValues { it.value.toCli() })
                }
                is DaemonRequest.Inspect -> {
                    val session = sessionManager.getSession(request.sessionId)
                    val result = session.inspectObject(
                        objectId = request.objectId,
                        fieldsFilter = null,
                        maxDepth = request.maxDepth
                    )
                    json.encodeToString(result.toCli())
                }
                is DaemonRequest.Step -> {
                    val session = sessionManager.getSession(request.sessionId)
                    session.stepExecution(request.threadId, request.action)
                    json.encodeToString(CliStatusResult("step_${request.action.name.lowercase()}"))
                }
            }
        } catch (e: DebugException) {
            json.encodeToString(e.toDebugError().toCli())
        } catch (e: Exception) {
            json.encodeToString(CliDebugError("INTERNAL_ERROR", e.message ?: "Unknown error", false))
        }
    }
}
