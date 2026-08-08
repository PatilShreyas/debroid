package dev.shreyaspatil.debroid.models

data class DebugError(
    val errorCode: String,
    val message: String,
    val retryable: Boolean
)

enum class ErrorCode {
    APP_NOT_DEBUGGABLE,
    SESSION_NOT_FOUND,
    THREAD_NOT_SUSPENDED,
    BREAKPOINT_NOT_FOUND,
    BREAKPOINT_NOT_HIT,
    EVALUATION_FAILED,
    COMPILE_FAILED,
    DEX_FAILED,
    ADB_ERROR,
    INTERNAL_ERROR
}

data class SessionStatus(
    val sessionId: String,
    val appId: String,
    val connected: Boolean,
    val activeBreakpointsCount: Int,
    val suspendedThreadsCount: Int
)

data class BreakpointInfo(
    val id: String,
    val sessionId: String,
    val file: String,
    val line: Int,
    val verified: Boolean
)

data class StackFrameInfo(
    val frameIndex: Int,
    val methodName: String,
    val declaringClass: String,
    val sourceFile: String?,
    val lineNumber: Int,
    val coroutineContinuationObjectId: String? = null
)

enum class VariableScope {
    LOCAL,
    ARGS,
    INSTANCE,
    STATIC
}

enum class StepAction {
    STEP_OVER,
    STEP_INTO,
    STEP_OUT,
    RESUME_THREAD,
    RESUME_ALL
}

enum class EventType {
    BREAKPOINT_HIT,
    STEP_HIT,
    EXCEPTION_HIT,
    WATCHPOINT_ACCESS_HIT,
    WATCHPOINT_MODIFY_HIT,
    DISCONNECT
}

data class VariableInfo(
    val name: String,
    val type: String,
    val valuePreview: String,
    val isPrimitive: Boolean,
    val objectId: String? = null
)

data class ObjectInspectionResult(
    val objectId: String,
    val type: String,
    val fields: Map<String, VariableInfo>,
    val nested: Map<String, ObjectInspectionResult>? = null
)

data class PauseStateResult(
    val threadId: String,
    val threadName: String,
    val frames: List<StackFrameInfo>,
    val locals: List<VariableInfo>,
    val instanceVariables: List<VariableInfo>
)

data class DebugEventPayload(
    val eventType: EventType,
    val sessionId: String,
    val threadId: String?,
    val threadName: String?,
    val location: String?,
    val className: String?,
    val exceptionMessage: String? = null,
    val stacktrace: List<StackFrameInfo>? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class EventPollResult(
    val events: List<DebugEventPayload>,
    val nextCursor: String,
    val hasMore: Boolean
)

data class ExceptionBreakpointInfo(
    val id: String,
    val className: String?,
    val notifyCaught: Boolean,
    val notifyUncaught: Boolean
)

data class WatchpointInfo(
    val id: String,
    val className: String,
    val fieldName: String,
    val access: Boolean,
    val modify: Boolean
)

data class PointsResult(
    val breakpoints: List<BreakpointInfo>,
    val exceptionBreakpoints: List<ExceptionBreakpointInfo>,
    val watchpoints: List<WatchpointInfo>
)
