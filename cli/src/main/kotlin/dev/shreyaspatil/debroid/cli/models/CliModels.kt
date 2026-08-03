package dev.shreyaspatil.debroid.cli.models

import dev.shreyaspatil.debroid.models.*
import kotlinx.serialization.Serializable

@Serializable
data class CliDebugError(
    val errorCode: String,
    val message: String,
    val retryable: Boolean
)

fun DebugError.toCli() = CliDebugError(errorCode = errorCode, message = message, retryable = retryable)

@Serializable
data class CliStatusResult(val status: String)

@Serializable
data class CliDetachedResult(val detached: Boolean)

@Serializable
data class CliShutdownResult(val shutdown: Boolean, val message: String = "Daemon shut down successfully")

@Serializable
data class CliExceptionBreakpointResult(val exceptionBreakpointId: String)

@Serializable
data class CliWatchpointResult(val watchpointId: String)

@Serializable
data class CliSessionStatus(
    val sessionId: String,
    val appId: String,
    val connected: Boolean,
    val activeBreakpointsCount: Int,
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
data class CliBreakpointInfo(
    val id: String,
    val sessionId: String,
    val file: String,
    val line: Int,
    val condition: String? = null,
    val verified: Boolean
)

fun BreakpointInfo.toCli() = CliBreakpointInfo(
    id = id,
    sessionId = sessionId,
    file = file,
    line = line,
    condition = condition,
    verified = verified
)

@Serializable
data class CliStackFrameInfo(
    val frameIndex: Int,
    val methodName: String,
    val declaringClass: String,
    val sourceFile: String?,
    val lineNumber: Int,
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
data class CliVariableInfo(
    val name: String,
    val type: String,
    val valuePreview: String,
    val isPrimitive: Boolean,
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
data class CliObjectInspectionResult(
    val objectId: String,
    val type: String,
    val fields: Map<String, CliVariableInfo>,
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
data class CliPauseStateResult(
    val threadId: String,
    val threadName: String,
    val frames: List<CliStackFrameInfo>,
    val locals: List<CliVariableInfo>,
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
data class CliDebugEventPayload(
    val eventType: String,
    val sessionId: String,
    val threadId: String?,
    val threadName: String?,
    val location: String?,
    val className: String?,
    val exceptionMessage: String? = null,
    val stacktrace: List<CliStackFrameInfo>? = null,
    val timestamp: Long
)

fun DebugEventPayload.toCli() = CliDebugEventPayload(
    eventType.name,
    sessionId,
    threadId,
    threadName,
    location,
    className,
    exceptionMessage,
    stacktrace?.map { it.toCli() },
    timestamp
)

@Serializable
data class CliEventPollResult(
    val events: List<CliDebugEventPayload>,
    val nextCursor: String,
    val hasMore: Boolean
)

fun EventPollResult.toCli() = CliEventPollResult(
    events.map { it.toCli() },
    nextCursor,
    hasMore
)
