package dev.shreyaspatil.debroid.cli.models

import dev.shreyaspatil.debroid.models.BreakpointInfo
import dev.shreyaspatil.debroid.models.DebugError
import dev.shreyaspatil.debroid.models.DebugEventPayload
import dev.shreyaspatil.debroid.models.EventPollResult
import dev.shreyaspatil.debroid.models.ObjectInspectionResult
import dev.shreyaspatil.debroid.models.PauseStateResult
import dev.shreyaspatil.debroid.models.SessionStatus
import dev.shreyaspatil.debroid.models.StackFrameInfo
import dev.shreyaspatil.debroid.models.ThreadInfo
import dev.shreyaspatil.debroid.models.ThreadStatus
import dev.shreyaspatil.debroid.models.VariableInfo
import kotlinx.serialization.Serializable

enum class CliErrorCode {
    CLI_UPDATED,
    VERSION_MISMATCH,
    CLI_ERROR
}

@Serializable
@SerialDescription("Error details when a CLI command or debug operation fails")
data class CliDebugError(
    @SerialDescription("Machine-readable error code")
    val errorCode: String,
    @SerialDescription("Human-readable error explanation")
    val message: String,
    @SerialDescription("Whether retrying the command with the same parameters may succeed")
    val retryable: Boolean
)

fun DebugError.toCli() = CliDebugError(errorCode = errorCode, message = message, retryable = retryable)

@Serializable
@SerialDescription("Generic operation status result")
data class CliStatusResult(
    @SerialDescription("Status message of the operation")
    val status: String
)

@Serializable
@SerialDescription("Result when a debug session is detached")
data class CliDetachedResult(
    @SerialDescription("Whether the session was successfully detached")
    val detached: Boolean
)

@Serializable
@SerialDescription("Result when the background daemon is shut down")
data class CliShutdownResult(
    @SerialDescription("Whether daemon shutdown succeeded")
    val shutdown: Boolean,
    @SerialDescription("Shutdown status message")
    val message: String = "Daemon shut down successfully"
)

@Serializable
@SerialDescription("Result when setting an exception breakpoint")
data class CliExceptionBreakpointResult(
    @SerialDescription("Unique ID of the created exception breakpoint")
    val exceptionBreakpointId: String
)

@Serializable
@SerialDescription("Result when setting a field watchpoint")
data class CliWatchpointResult(
    @SerialDescription("Unique ID of the created watchpoint")
    val watchpointId: String
)

@Serializable
@SerialDescription("Current status of an active debug session")
data class CliSessionStatus(
    @SerialDescription("Unique debug session ID")
    val sessionId: String,
    @SerialDescription("Android application ID")
    val appId: String,
    @SerialDescription("Whether the debugger is actively attached")
    val connected: Boolean,
    @SerialDescription("Count of active breakpoints")
    val activeBreakpointsCount: Int,
    @SerialDescription("Count of suspended threads")
    val suspendedThreadsCount: Int
)

fun SessionStatus.toCli() = CliSessionStatus(
    sessionId,
    appId,
    connected,
    activeBreakpointsCount,
    suspendedThreadsCount
)

@Serializable
@SerialDescription("Information about a line breakpoint")
data class CliBreakpointInfo(
    @SerialDescription("Unique breakpoint ID")
    val id: String,
    @SerialDescription("Session ID owning this breakpoint")
    val sessionId: String,
    @SerialDescription("Source file name")
    val file: String,
    @SerialDescription("1-indexed line number")
    val line: Int,
    @SerialDescription("Whether the breakpoint is bound to a loaded VM class")
    val verified: Boolean
)

fun BreakpointInfo.toCli() = CliBreakpointInfo(
    id = id,
    sessionId = sessionId,
    file = file,
    line = line,
    verified = verified
)

@Serializable
@SerialDescription("Information about an exception breakpoint")
data class CliExceptionBreakpointInfo(
    @SerialDescription("Unique exception breakpoint ID")
    val id: String,
    @SerialDescription("Fully qualified exception class name, if restricted")
    val className: String?,
    @SerialDescription("Whether this triggers on caught exceptions")
    val notifyCaught: Boolean,
    @SerialDescription("Whether this triggers on uncaught exceptions")
    val notifyUncaught: Boolean
)

fun dev.shreyaspatil.debroid.models.ExceptionBreakpointInfo.toCli() = CliExceptionBreakpointInfo(
    id = id,
    className = className,
    notifyCaught = notifyCaught,
    notifyUncaught = notifyUncaught
)

@Serializable
@SerialDescription("Information about a watchpoint")
data class CliWatchpointInfo(
    @SerialDescription("Unique watchpoint ID")
    val id: String,
    @SerialDescription("Fully qualified class name containing the field")
    val className: String,
    @SerialDescription("Name of the watched field")
    val fieldName: String,
    @SerialDescription("Whether this triggers on field access")
    val access: Boolean,
    @SerialDescription("Whether this triggers on field modification")
    val modify: Boolean
)

fun dev.shreyaspatil.debroid.models.WatchpointInfo.toCli() = CliWatchpointInfo(
    id = id,
    className = className,
    fieldName = fieldName,
    access = access,
    modify = modify
)

@Serializable
@SerialDescription("Result containing all active debug points for a session")
data class CliPointsResult(
    @SerialDescription("List of line breakpoints")
    val breakpoints: List<CliBreakpointInfo>,
    @SerialDescription("List of exception breakpoints")
    val exceptionBreakpoints: List<CliExceptionBreakpointInfo>,
    @SerialDescription("List of watchpoints")
    val watchpoints: List<CliWatchpointInfo>
)

fun dev.shreyaspatil.debroid.models.PointsResult.toCli() = CliPointsResult(
    breakpoints = breakpoints.map { it.toCli() },
    exceptionBreakpoints = exceptionBreakpoints.map { it.toCli() },
    watchpoints = watchpoints.map { it.toCli() }
)

@Serializable
@SerialDescription("Information about a single stack frame")
data class CliStackFrameInfo(
    @SerialDescription("0-indexed frame depth index")
    val frameIndex: Int,
    @SerialDescription("Name of the method executing in this frame")
    val methodName: String,
    @SerialDescription("Fully qualified class name declaring the method")
    val declaringClass: String,
    @SerialDescription("Source file name if available")
    val sourceFile: String?,
    @SerialDescription("1-indexed line number in source file")
    val lineNumber: Int,
    @SerialDescription("Object ID of Kotlin Continuation if this frame is a coroutine")
    val coroutineContinuationObjectId: String? = null
)

fun StackFrameInfo.toCli() = CliStackFrameInfo(
    frameIndex,
    methodName,
    declaringClass,
    sourceFile,
    lineNumber,
    coroutineContinuationObjectId
)

@Serializable
@SerialDescription("Representation of a local variable or instance field")
data class CliVariableInfo(
    @SerialDescription("Variable or field name")
    val name: String,
    @SerialDescription("Fully qualified data type name")
    val type: String,
    @SerialDescription("Preview string of the variable value")
    val valuePreview: String,
    @SerialDescription("Whether the variable is a primitive")
    val isPrimitive: Boolean,
    @SerialDescription("Heap object ID for non-primitive reference objects")
    val objectId: String? = null
)

fun VariableInfo.toCli() = CliVariableInfo(
    name = name,
    type = type,
    valuePreview = valuePreview,
    isPrimitive = isPrimitive,
    objectId = objectId
)

@Serializable
@SerialDescription("Result of inspecting object fields in heap memory")
data class CliObjectInspectionResult(
    @SerialDescription("Heap object ID of the inspected instance")
    val objectId: String,
    @SerialDescription("Fully qualified class type of the object")
    val type: String,
    @SerialDescription("Map of field names to their variable info")
    val fields: Map<String, CliVariableInfo>,
    @SerialDescription("Map of field names to nested object inspection results when max-depth > 1")
    val nested: Map<String, CliObjectInspectionResult>? = null
)

fun ObjectInspectionResult.toCli(): CliObjectInspectionResult {
    val cliNested: Map<String, CliObjectInspectionResult>? = nested?.mapValues { it.value.toCli() }
    return CliObjectInspectionResult(
        objectId = objectId,
        type = type,
        fields = fields.mapValues { it.value.toCli() },
        nested = cliNested
    )
}

@Serializable
@SerialDescription("Current execution pause state for a suspended thread")
data class CliPauseStateResult(
    @SerialDescription("ID of the suspended thread")
    val threadId: String,
    @SerialDescription("Name of the suspended thread")
    val threadName: String,
    @SerialDescription("Call stack frames of the thread")
    val frames: List<CliStackFrameInfo>,
    @SerialDescription("Shallow local variables in top stack frame")
    val locals: List<CliVariableInfo>,
    @SerialDescription("Instance variables of 'this' object in top stack frame")
    val instanceVariables: List<CliVariableInfo>
)

fun PauseStateResult.toCli() = CliPauseStateResult(
    threadId,
    threadName,
    frames.map { it.toCli() },
    locals.map { it.toCli() },
    instanceVariables.map { it.toCli() }
)

@Serializable
@SerialDescription("Payload describing an asynchronous debugger event")
data class CliDebugEventPayload(
    @SerialDescription("Type of event (BREAKPOINT_HIT, EXCEPTION_HIT, WATCHPOINT_HIT, DISCONNECT)")
    val eventType: String,
    @SerialDescription("Session ID where the event occurred")
    val sessionId: String,
    @SerialDescription("ID of thread that triggered the event")
    val threadId: String?,
    @SerialDescription("Name of thread that triggered the event")
    val threadName: String?,
    @SerialDescription("Source location where event occurred (File.kt:line)")
    val location: String?,
    @SerialDescription("Fully qualified class name where event occurred")
    val className: String?,
    @SerialDescription("ID of the breakpoint that was hit, if event is BREAKPOINT_HIT")
    val breakpointId: String? = null,
    @SerialDescription("Message string if event is EXCEPTION_HIT")
    val exceptionMessage: String? = null,
    @SerialDescription("Stack trace frames if requested during poll")
    val stacktrace: List<CliStackFrameInfo>? = null,
    @SerialDescription("Epoch timestamp in milliseconds when event occurred")
    val timestamp: Long
)

fun DebugEventPayload.toCli() = CliDebugEventPayload(
    eventType.name,
    sessionId,
    threadId,
    threadName,
    location,
    className,
    breakpointId,
    exceptionMessage,
    stacktrace?.map { it.toCli() },
    timestamp
)

@Serializable
@SerialDescription("Result of polling the asynchronous debugger event queue")
data class CliEventPollResult(
    @SerialDescription("List of debugger events received since last poll")
    val events: List<CliDebugEventPayload>,
    @SerialDescription("Opaque cursor token to pass to next poll call")
    val nextCursor: String,
    @SerialDescription("Whether more events remain in queue")
    val hasMore: Boolean
)

fun EventPollResult.toCli() = CliEventPollResult(
    events.map { it.toCli() },
    nextCursor,
    hasMore
)

@Serializable
@SerialDescription("Result of checking or performing CLI auto-update")
data class CliUpdateResult(
    @SerialDescription("Current installed CLI version")
    val currentVersion: String,
    @SerialDescription("Latest available CLI version on GitHub")
    val latestVersion: String,
    @SerialDescription("Whether a newer version is available")
    val updateAvailable: Boolean,
    @SerialDescription("Whether the update was downloaded and applied")
    val updated: Boolean,
    @SerialDescription("Status message")
    val message: String
)

@Serializable
@SerialDescription("Result containing the background daemon's version")
data class CliVersionResult(
    @SerialDescription("The CLI version of the running daemon")
    val version: String
)

@Serializable
enum class CliThreadStatus {
    RUNNING,
    SLEEPING,
    WAIT,
    MONITOR,
    NOT_STARTED,
    ZOMBIE,
    UNKNOWN
}

fun ThreadStatus.toCli() = when (this) {
    ThreadStatus.RUNNING -> CliThreadStatus.RUNNING
    ThreadStatus.SLEEPING -> CliThreadStatus.SLEEPING
    ThreadStatus.WAIT -> CliThreadStatus.WAIT
    ThreadStatus.MONITOR -> CliThreadStatus.MONITOR
    ThreadStatus.NOT_STARTED -> CliThreadStatus.NOT_STARTED
    ThreadStatus.ZOMBIE -> CliThreadStatus.ZOMBIE
    ThreadStatus.UNKNOWN -> CliThreadStatus.UNKNOWN
}

@Serializable
@SerialDescription("Information about a thread in the target application")
data class CliThreadInfo(
    @SerialDescription("Unique thread ID")
    val threadId: String,
    @SerialDescription("Name of the thread")
    val threadName: String,
    @SerialDescription("Thread execution status (RUNNING, SLEEPING, WAIT, MONITOR, ZOMBIE, NOT_STARTED, UNKNOWN)")
    val status: CliThreadStatus,
    @SerialDescription("Whether the thread is currently suspended")
    val isSuspended: Boolean
)

fun ThreadInfo.toCli() = CliThreadInfo(
    threadId = threadId,
    threadName = threadName,
    status = status.toCli(),
    isSuspended = isSuspended
)
