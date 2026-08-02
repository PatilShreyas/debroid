package dev.shreyaspatil.debroid.cli.models

import dev.shreyaspatil.debroid.models.StepAction
import kotlinx.serialization.Serializable

@Serializable
sealed class DaemonRequest {
    @Serializable data class Launch(val appId: String) : DaemonRequest()

    @Serializable data class Attach(val appId: String) : DaemonRequest()

    @Serializable data class Detach(val sessionId: String) : DaemonRequest()

    @Serializable data class Break(val sessionId: String, val file: String, val line: Int) : DaemonRequest()

    @Serializable data class CatchException(val sessionId: String, val className: String?) : DaemonRequest()

    @Serializable data class Watch(
        val sessionId: String,
        val className: String,
        val fieldName: String
    ) : DaemonRequest()

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

    @Serializable data class Resume(val sessionId: String, val threadId: String) : DaemonRequest()

    @Serializable data class Poll(val sessionId: String, val cursor: String, val withStacktrace: Boolean = false) : DaemonRequest()

    @Serializable data class Frames(val sessionId: String, val threadId: String) : DaemonRequest()

    @Serializable data class Coroutine(val sessionId: String, val continuationObjectId: String) : DaemonRequest()

    @Serializable data class Inspect(val sessionId: String, val objectId: String, val maxDepth: Int = 1) : DaemonRequest()

    @Serializable data class Step(val sessionId: String, val threadId: String, val action: StepAction) : DaemonRequest()
}
