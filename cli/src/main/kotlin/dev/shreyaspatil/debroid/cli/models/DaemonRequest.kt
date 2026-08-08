package dev.shreyaspatil.debroid.cli.models

import dev.shreyaspatil.debroid.models.StepAction
import kotlinx.serialization.Serializable

@Serializable
data class DaemonIpcRequest(
    val pretty: Boolean = false,
    val request: DaemonRequest
)

@Serializable
sealed class DaemonRequest {
    @Serializable data class Launch(val appId: String) : DaemonRequest()

    @Serializable data class Attach(val appId: String) : DaemonRequest()

    @Serializable data class Detach(val sessionId: String) : DaemonRequest()

    @Serializable data class Shutdown(val force: Boolean = false) : DaemonRequest()

    @Serializable data class Break(
        val sessionId: String,
        val file: String,
        val line: Int,
        val packageName: String? = null
    ) : DaemonRequest()

    @Serializable data class RemoveBreak(val sessionId: String, val breakpointId: String) : DaemonRequest()

    @Serializable data class CatchException(
        val sessionId: String,
        val className: String?,
        val notifyCaught: Boolean = false,
        val notifyUncaught: Boolean = true
    ) : DaemonRequest()

    @Serializable
    data class RemoveCatchException(
        val sessionId: String,
        val exceptionBreakpointId: String
    ) : DaemonRequest()

    @Serializable data class Watch(
        val sessionId: String,
        val className: String,
        val fieldName: String,
        val access: Boolean = true,
        val modify: Boolean = true
    ) : DaemonRequest()

    @Serializable data class RemoveWatch(val sessionId: String, val watchpointId: String) : DaemonRequest()

    @Serializable data class Threads(val sessionId: String) : DaemonRequest()

    @Serializable data class Locals(val sessionId: String, val threadId: String) : DaemonRequest()

    @Serializable data class PauseState(val sessionId: String, val threadId: String) : DaemonRequest()

    @Serializable data class SetVar(
        val sessionId: String,
        val threadId: String,
        val varName: String,
        val newValue: String
    ) : DaemonRequest()

    @Serializable data class Eval(val sessionId: String, val threadId: String, val expression: String) : DaemonRequest()

    @Serializable data class Resume(val sessionId: String) : DaemonRequest()

    @Serializable
    data class Poll(
        val sessionId: String,
        val cursor: String,
        val withStacktrace: Boolean = false
    ) : DaemonRequest()

    @Serializable data class Frames(val sessionId: String, val threadId: String) : DaemonRequest()

    @Serializable data class Coroutine(val sessionId: String, val continuationObjectId: String) : DaemonRequest()

    @Serializable
    data class Inspect(
        val sessionId: String,
        val objectId: String,
        val maxDepth: Int = 1,
        val includeStatic: Boolean = false,
        val includeInternal: Boolean = false
    ) : DaemonRequest()

    @Serializable data class Step(val sessionId: String, val threadId: String, val action: StepAction) : DaemonRequest()
}
