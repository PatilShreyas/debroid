package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.*
import com.sun.jdi.event.*
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.ExceptionRequest
import com.sun.jdi.request.StepRequest
import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.models.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JdiSession(
    val sessionId: String,
    val appId: String,
    val localPort: Int,
    private val vm: VirtualMachine,
    private val adbManager: AdbManager
) {
    private val isConnected = AtomicBoolean(true)
    private val breakpointIdCounter = AtomicInteger(1)

    // Breakpoint & Watchpoint tracking
    private val activeBreakpoints = ConcurrentHashMap<String, BreakpointInfo>()
    private val jdiBreakpointRequests = ConcurrentHashMap<String, BreakpointRequest>()
    private val deferredWatchpoints = ConcurrentHashMap<String, Pair<String, Pair<Boolean, Boolean>>>()

    // Event buffer for polling
    private val eventQueueBuffer = CopyOnWriteArrayList<DebugEventPayload>()
    private var eventQueueOffset = 0
    private val MAX_EVENT_BUFFER_SIZE = 1000

    private fun pushEvent(payload: DebugEventPayload) {
        eventQueueBuffer.add(payload)
        while (eventQueueBuffer.size > MAX_EVENT_BUFFER_SIZE) {
            eventQueueBuffer.removeAt(0)
            eventQueueOffset++
        }
    }

    private val eventThread = Thread { runEventListener() }.apply {
        isDaemon = true
        name = "JDI-EventListener-$sessionId"
    }

    init {
        eventThread.start()
    }

    fun isAlive(): Boolean = isConnected.get() && try {
        vm.allThreads()
        true
    } catch (e: com.sun.jdi.VMDisconnectedException) { false }

    fun getStatus(): SessionStatus {
        val threads = try { vm.allThreads() } catch (e: com.sun.jdi.VMDisconnectedException) { emptyList<ThreadReference>() }
        val suspendedCount = threads.count { it.isSuspended }
        return SessionStatus(
            sessionId = sessionId,
            appId = appId,
            connected = isConnected.get(),
            activeBreakpointsCount = activeBreakpoints.size,
            suspendedThreadsCount = suspendedCount
        )
    }

    // --- Breakpoints & Watchpoints ---

    fun setBreakpoint(file: String, line: Int, condition: String?): BreakpointInfo {
        val id = "bp_${breakpointIdCounter.getAndIncrement()}"
        val info = BreakpointInfo(
            id = id,
            sessionId = sessionId,
            file = file,
            line = line,
            condition = condition,
            verified = false
        )
        activeBreakpoints[id] = info

        val verified = bindBreakpointLocation(id = id, file = file, line = line)
        if (verified) {
            activeBreakpoints[id] = info.copy(verified = true)
        } else {
            val req = vm.eventRequestManager().createClassPrepareRequest()
            req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            req.enable()
        }

        return activeBreakpoints[id]!!
    }

    private fun bindBreakpointLocation(id: String, file: String, line: Int): Boolean {
        val classBasename = file.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val classBasenameKt = "${classBasename}Kt"
        val matchingClasses = vm.allClasses().filter { ref ->
            try {
                ref.sourceName() == file
            } catch (e: com.sun.jdi.AbsentInformationException) {
                // Fallback to name heuristics if sourceName is absent
                val name = ref.name()
                val simpleName = name.substringAfterLast('.')
                simpleName == classBasename || 
                simpleName == classBasenameKt || 
                simpleName.startsWith("$classBasename$") || 
                simpleName.startsWith("$classBasenameKt$")
            }
        }

        var bound = false
        for (ref in matchingClasses) {
            try {
                val locations = ref.locationsOfLine(line)
                for (loc in locations) {
                    val bpReq = vm.eventRequestManager().createBreakpointRequest(loc)
                    bpReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                    bpReq.enable()
                    jdiBreakpointRequests[id] = bpReq
                    bound = true
                }
            } catch (e: com.sun.jdi.AbsentInformationException) {
                // Class line info not available yet
            }
        }
        return bound
    }

    fun setExceptionBreakpoint(className: String?, uncaughtOnly: Boolean): String {
        val erm = vm.eventRequestManager()
        val refType = className?.let { name -> vm.classesByName(name).firstOrNull() }
        val req: ExceptionRequest = erm.createExceptionRequest(refType, true, uncaughtOnly)
        req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
        req.enable()
        return "ex_bp_${System.currentTimeMillis()}"
    }

    fun setWatchpoint(className: String, fieldName: String, access: Boolean = true, modify: Boolean = true): String {
        val erm = vm.eventRequestManager()
        val refTypes = vm.classesByName(className)
        val id = "wp_${System.currentTimeMillis()}"

        if (refTypes.isEmpty()) {
            val classPrepReq = erm.createClassPrepareRequest()
            classPrepReq.addClassFilter(className)
            classPrepReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            classPrepReq.enable()
            deferredWatchpoints[className] = Pair(fieldName, Pair(access, modify))
            return id
        }

        val refType = refTypes.first()
        bindWatchpointForRefType(refType = refType, fieldName = fieldName, access = access, modify = modify)
        return id
    }

    private fun bindWatchpointForRefType(refType: ReferenceType, fieldName: String, access: Boolean, modify: Boolean) {
        val erm = vm.eventRequestManager()
        val field = refType.fieldByName(fieldName) ?: return

        if (access) {
            val req = erm.createAccessWatchpointRequest(field)
            req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            req.enable()
        }
        if (modify) {
            val req = erm.createModificationWatchpointRequest(field)
            req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            req.enable()
        }
    }

    fun removeBreakpoint(id: String): Boolean {
        val info = activeBreakpoints.remove(id)
        val jdiReq = jdiBreakpointRequests.remove(id)
        if (jdiReq != null) {
            try { vm.eventRequestManager().deleteEventRequest(jdiReq) } catch (e: Exception) {}
        }
        return info != null
    }

    fun listBreakpoints(): List<BreakpointInfo> = activeBreakpoints.values.toList()

    // --- Execution Control ---

    fun listThreads(): List<Map<String, String>> {
        return vm.allThreads().map { thread ->
            mapOf(
                "thread_id" to thread.uniqueID().toString(),
                "thread_name" to thread.name(),
                "status" to thread.status().toString(),
                "is_suspended" to thread.isSuspended.toString()
            )
        }
    }

    fun stepExecution(threadId: String, action: StepAction) {
        val thread = findThread(threadId)
        val erm = vm.eventRequestManager()

        erm.stepRequests().filter { it.thread() == thread }.forEach { erm.deleteEventRequest(it) }

        when (action) {
            StepAction.STEP_OVER -> {
                val stepReq = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER)
                stepReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                stepReq.addCountFilter(1)
                stepReq.enable()
                thread.resume()
            }
            StepAction.STEP_INTO -> {
                val stepReq = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO)
                stepReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                stepReq.addCountFilter(1)
                stepReq.enable()
                thread.resume()
            }
            StepAction.STEP_OUT -> {
                val stepReq = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT)
                stepReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                stepReq.addCountFilter(1)
                stepReq.enable()
                thread.resume()
            }
            StepAction.RESUME_THREAD -> {
                thread.resume()
            }
            StepAction.RESUME_ALL -> {
                vm.resume()
            }
        }
    }

    // --- Inspection, Evaluation & Coroutine Extraction ---

    /**
     * Evaluates a given expression in the context of the suspended thread.
     *
     * Due to limitations in JDI (Java Debug Interface) regarding dynamic expression evaluation
     * without a full AST/compiler backend, this implements custom evaluation logic:
     *
     * 1. **String Concatenation**: Parses compound `+` operations and resolves local variables
     *    or string literals to synthesize a new String object in the target VM.
     * 2. **Method Invocation**: Supports invoking zero-argument methods on the `this` object.
     * 3. **Field/Variable Lookup**: Resolves simple visible local variables and instance fields.
     *
     * @param threadId The thread ID where evaluation happens.
     * @param expr The expression to evaluate.
     * @return VariableInfo representing the evaluated result.
     */
    fun evaluateExpression(threadId: String, expr: String): VariableInfo {
        val thread = findThread(threadId)
        if (!thread.isSuspended) {
            throw DebugException(ErrorCode.THREAD_NOT_SUSPENDED, "Thread $threadId is not suspended.")
        }
        val frame = thread.frame(0)
        val thisObj = frame.thisObject()

        // 1. Compound String concatenation evaluation
        if (expr.contains("+") || expr.contains("\"")) {
            val tokens = expr.split("+").map { it.trim() }
            val sb = StringBuilder()
            val visVars = try { frame.visibleVariables() } catch (e: com.sun.jdi.AbsentInformationException) { emptyList() }
            val fields = thisObj?.referenceType()?.fields() ?: emptyList()
            val fieldValues = if (thisObj != null) thisObj.getValues(fields) else emptyMap()

            for (token in tokens) {
                if (token.startsWith("\"") && token.endsWith("\"")) {
                    sb.append(token.substring(1, token.length - 1))
                } else {
                    val v = visVars.find { it.name() == token }
                    if (v != null) {
                        val valRef = frame.getValue(v)
                        sb.append(valRef?.toString()?.removeSurrounding("\"") ?: "null")
                    } else {
                        val field = fields.find { it.name() == token }
                        if (field != null) {
                            val valRef = fieldValues[field]
                            sb.append(valRef?.toString()?.removeSurrounding("\"") ?: "null")
                        } else {
                            sb.append(token)
                        }
                    }
                }
            }
            val evaluatedString = sb.toString()
            val stringRef = vm.mirrorOf(evaluatedString)
            return VariableInfo(
                "evaluatedResult",
                "String",
                "\"$evaluatedString\"",
                true,
                stringRef.uniqueID().toString()
            )
        }

        // 2. Method invocation on 'this' if zero-arg method exists
        if (thisObj != null) {
            val methodName = if (expr.endsWith("()")) expr.substringBefore("()") else expr
            val method = thisObj.referenceType().methodsByName(
                methodName
            ).firstOrNull { it.argumentTypeNames().isEmpty() }
            if (method != null) {
                val result = thisObj.invokeMethod(thread, method, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED)
                return formatValue(methodName, result)
            }
        }

        // 3. Visible local variable or instance field lookup
        val visVar = try { frame.visibleVariables() } catch (e: com.sun.jdi.AbsentInformationException) { emptyList() }.find { it.name() == expr }
        if (visVar != null) {
            val value = frame.getValue(visVar)
            return formatValue(visVar.name(), value)
        }

        if (thisObj != null) {
            val field = thisObj.referenceType().fieldByName(expr)
            if (field != null) {
                val value = thisObj.getValue(field)
                return formatValue(field.name(), value)
            }
        }

        throw DebugException(ErrorCode.EVALUATION_FAILED, "Expression '$expr' could not be evaluated.")
    }

    /**
     * Extracts state variables from a Kotlin Coroutine Continuation object.
     *
     * Reasoning:
     * When a Kotlin Coroutine suspends, the local variables are hoisted out of the stack
     * and into a generated `ContinuationImpl` state machine class as instance fields.
     * To inspect the "local variables" of a suspended coroutine, we cannot just look at
     * the stack frame's visible variables (which will only contain the state machine object).
     * Instead, we must reflectively inspect the fields of the state machine `this` object
     * (`continuationObjectId`) which holds the persistent coroutine state.
     *
     * @param continuationObjectId The object ID of the suspended Continuation instance.
     * @return A map of variable names to their extracted values.
     */
    fun getCoroutineFrame(continuationObjectId: String): Map<String, VariableInfo> {
        val objRef =
            findObjectReference(
                continuationObjectId.toLongOrNull() ?: throw DebugException(ErrorCode.INTERNAL_ERROR, "Invalid continuation object ID")
            )
        val refType = objRef.referenceType()

        val fields = refType.allFields().filter { !it.isStatic }
        val fieldValues = objRef.getValues(fields)

        val result = mutableMapOf<String, VariableInfo>()
        for ((f, valRef) in fieldValues) {
            result[f.name()] = formatValue(f.name(), valRef)
        }
        return result
    }

    fun setVariable(threadId: String, varName: String, newValueStr: String): VariableInfo {
        val thread = findThread(threadId)
        if (!thread.isSuspended) {
            throw DebugException(ErrorCode.THREAD_NOT_SUSPENDED, "Thread $threadId is not suspended.")
        }
        val frame = thread.frame(0)
        val visVar = frame.visibleVariables().find { it.name() == varName }
            ?: throw DebugException(ErrorCode.INTERNAL_ERROR, "Variable $varName not found in current local scope.")

        val newJdiVal: Value = when (visVar.typeName()) {
            "double" -> vm.mirrorOf(newValueStr.toDouble())
            "float" -> vm.mirrorOf(newValueStr.toFloat())
            "int" -> vm.mirrorOf(newValueStr.toInt())
            "long" -> vm.mirrorOf(newValueStr.toLong())
            "boolean" -> vm.mirrorOf(newValueStr.toBoolean())
            "java.lang.String" -> vm.mirrorOf(newValueStr)
            else -> throw DebugException(
                ErrorCode.INTERNAL_ERROR,
                "Unsupported type for mutation: ${visVar.typeName()}"
            )
        }

        frame.setValue(visVar, newJdiVal)
        return formatValue(varName, newJdiVal)
    }

    fun getStackFrames(threadId: String): List<StackFrameInfo> {
        val thread = findThread(threadId)
        if (!thread.isSuspended) {
            throw DebugException(ErrorCode.THREAD_NOT_SUSPENDED, "Thread $threadId is not suspended.")
        }
        return extractFrames(thread)
    }

    private fun extractFrames(thread: ThreadReference): List<StackFrameInfo> {
        val frames = thread.frames()
        return frames.mapIndexed { index, frame ->
            val location = frame.location()
            var continuationObjId: String? = null

            val thisObj = try { frame.thisObject() } catch (e: Exception) { null }
            if (thisObj != null) {
                val refType = thisObj.referenceType() as? com.sun.jdi.ClassType
                val isContinuation = try {
                    refType?.allInterfaces()?.any { it.name() == "kotlin.coroutines.Continuation" } == true
                } catch (e: Exception) { false }

                if (isContinuation) {
                    continuationObjId = thisObj.uniqueID().toString()
                }
            }

            StackFrameInfo(
                frameIndex = index,
                methodName = location.method().name(),
                declaringClass = location.declaringType().name(),
                sourceFile = try { location.sourceName() } catch (e: com.sun.jdi.AbsentInformationException) { null },
                lineNumber = location.lineNumber(),
                coroutineContinuationObjectId = continuationObjId
            )
        }
    }

    fun getVariables(threadId: String, scope: VariableScope): List<VariableInfo> {
        val thread = findThread(threadId)
        if (!thread.isSuspended) {
            throw DebugException(ErrorCode.THREAD_NOT_SUSPENDED, "Thread $threadId is not suspended.")
        }

        val frame = thread.frame(0)
        val result = mutableListOf<VariableInfo>()

        when (scope) {
            VariableScope.LOCAL, VariableScope.ARGS -> {
                val visVars = try { frame.visibleVariables() } catch (e: com.sun.jdi.AbsentInformationException) { emptyList() }
                for (v in visVars) {
                    if (scope == VariableScope.ARGS && !v.isArgument) continue
                    if (scope == VariableScope.LOCAL && v.isArgument) continue

                    val value = frame.getValue(v)
                    result.add(formatValue(v.name(), value))
                }
            }
            VariableScope.INSTANCE -> {
                val thisObj = try { frame.thisObject() } catch (e: Exception) { null }
                if (thisObj != null) {
                    val fields = thisObj.referenceType().fields()
                    val fieldValues = thisObj.getValues(fields)
                    for ((f, valRef) in fieldValues) {
                        if (!f.isStatic) {
                            result.add(formatValue(f.name(), valRef))
                        }
                    }
                }
            }
            VariableScope.STATIC -> {
                val location = frame.location()
                val refType = location.declaringType()
                val fields = refType.fields().filter { it.isStatic }
                val fieldValues = refType.getValues(fields)
                for ((f, valRef) in fieldValues) {
                    result.add(formatValue(f.name(), valRef))
                }
            }
        }

        return result
    }

    fun getPauseState(threadId: String): PauseStateResult {
        val thread = findThread(threadId)
        val frames = getStackFrames(threadId)
        val locals = getVariables(threadId, VariableScope.LOCAL)
        val instances = getVariables(threadId, VariableScope.INSTANCE)

        return PauseStateResult(
            threadId = threadId,
            threadName = thread.name(),
            frames = frames,
            locals = locals,
            instanceVariables = instances
        )
    }

    fun inspectObject(objectId: String, fieldsFilter: List<String>?, maxDepth: Int = 1): ObjectInspectionResult {
        val objRef =
            findObjectReference(
                objectId.toLongOrNull() ?: throw DebugException(ErrorCode.INTERNAL_ERROR, "Invalid object ID format")
            )
        val refType = objRef.referenceType()

        val fields = refType.allFields()
            .filter { f -> fieldsFilter == null || fieldsFilter.contains(f.name()) }
            .take(50)

        val fieldValues = objRef.getValues(fields)
        val resultFields = mutableMapOf<String, VariableInfo>()

        for ((f, valRef) in fieldValues) {
            resultFields[f.name()] = formatValue(f.name(), valRef)
        }

        return ObjectInspectionResult(
            objectId = objectId,
            type = refType.name(),
            fields = resultFields
        )
    }

    // --- Helpers ---

    private fun formatValue(name: String, value: Value?): VariableInfo {
        if (value == null) {
            return VariableInfo(name = name, type = "null", valuePreview = "null", isPrimitive = true)
        }

        return when (value) {
            is PrimitiveValue -> VariableInfo(
                name = name,
                type = value.type().name(),
                valuePreview = value.toString(),
                isPrimitive = true
            )
            is StringReference -> VariableInfo(
                name = name,
                type = "String",
                valuePreview = "\"${value.value()}\"",
                isPrimitive = true,
                objectId = value.uniqueID().toString()
            )
            is ArrayReference -> VariableInfo(
                name = name,
                type = value.type().name(),
                valuePreview = "Array(size=${value.length()})",
                isPrimitive = false,
                objectId = value.uniqueID().toString()
            )
            is ObjectReference -> VariableInfo(
                name = name,
                type = value.referenceType().name(),
                valuePreview = "<${value.referenceType().name()} id=${value.uniqueID()}>",
                isPrimitive = false,
                objectId = value.uniqueID().toString()
            )
            else -> VariableInfo(
                name = name,
                type = value.type().name(),
                valuePreview = value.toString(),
                isPrimitive = true
            )
        }
    }

    private fun findThread(threadId: String): ThreadReference {
        val tid = threadId.toLongOrNull()
        return vm.allThreads().find { it.uniqueID() == tid || it.name() == threadId }
            ?: throw DebugException(ErrorCode.INTERNAL_ERROR, "Thread not found: $threadId")
    }

    private fun findObjectReference(objectId: Long): ObjectReference {
        for (thread in vm.allThreads()) {
            if (thread.isSuspended) {
                try {
                    for (frame in thread.frames()) {
                        val thisObj = frame.thisObject()
                        if (thisObj != null && thisObj.uniqueID() == objectId) return thisObj

                        for (localVar in frame.visibleVariables()) {
                            val value = frame.getValue(localVar)
                            if (value is ObjectReference && value.uniqueID() == objectId) {
                                return value
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        throw DebugException(
            ErrorCode.INTERNAL_ERROR,
            "Object reference $objectId not found in current suspended context"
        )
    }

    private fun getFramesSafely(thread: ThreadReference): List<StackFrameInfo>? {
        return try {
            if (thread.isSuspended) {
                extractFrames(thread)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun pollEvents(sinceCursor: String, withStacktrace: Boolean = false): EventPollResult {
        val cursorIndex = sinceCursor.toIntOrNull() ?: 0
        val actualStartIndex = maxOf(0, cursorIndex - eventQueueOffset)

        val subList = if (actualStartIndex < eventQueueBuffer.size) {
            eventQueueBuffer.subList(actualStartIndex, eventQueueBuffer.size)
        } else {
            emptyList()
        }
        val nextCursor = (eventQueueOffset + eventQueueBuffer.size).toString()
        
        val events = if (withStacktrace) {
            subList.toList()
        } else {
            subList.map { it.copy(stacktrace = null) }
        }

        return EventPollResult(
            events = events,
            nextCursor = nextCursor,
            hasMore = false
        )
    }

    private fun runEventListener() {
        val eventQueue = vm.eventQueue()
        while (isConnected.get()) {
            try {
                val eventSet = eventQueue.remove(1000) ?: continue
                for (event in eventSet) {
                    when (event) {
                        is ClassPrepareEvent -> {
                            val preparedClass = event.referenceType()
                            val deferred = deferredWatchpoints.remove(preparedClass.name())
                            if (deferred != null) {
                                val (fieldName, flags) = deferred
                                bindWatchpointForRefType(preparedClass, fieldName, flags.first, flags.second)
                            }
                            eventSet.resume()
                        }
                        is BreakpointEvent -> {
                            val loc = event.location()
                            pushEvent(
                                DebugEventPayload(
                                    eventType = EventType.BREAKPOINT_HIT,
                                    sessionId = sessionId,
                                    threadId = event.thread().uniqueID().toString(),
                                    threadName = event.thread().name(),
                                    location = "${loc.sourceName()}:${loc.lineNumber()}",
                                    className = loc.declaringType().name(),
                                    stacktrace = getFramesSafely(event.thread())
                                )
                            )
                        }
                        is StepEvent -> {
                            val loc = event.location()
                            pushEvent(
                                DebugEventPayload(
                                    eventType = EventType.STEP_HIT,
                                    sessionId = sessionId,
                                    threadId = event.thread().uniqueID().toString(),
                                    threadName = event.thread().name(),
                                    location = "${loc.sourceName()}:${loc.lineNumber()}",
                                    className = loc.declaringType().name(),
                                    stacktrace = getFramesSafely(event.thread())
                                )
                            )
                        }
                        is ExceptionEvent -> {
                            val loc = event.location()
                            pushEvent(
                                DebugEventPayload(
                                    eventType = EventType.EXCEPTION_HIT,
                                    sessionId = sessionId,
                                    threadId = event.thread().uniqueID().toString(),
                                    threadName = event.thread().name(),
                                    location = "${loc.sourceName()}:${loc.lineNumber()}",
                                    className = loc.declaringType().name(),
                                    exceptionMessage = event.exception().referenceType().name(),
                                    stacktrace = getFramesSafely(event.thread())
                                )
                            )
                        }
                        is AccessWatchpointEvent -> {
                            val loc = event.location()
                            pushEvent(
                                DebugEventPayload(
                                    eventType = EventType.WATCHPOINT_ACCESS_HIT,
                                    sessionId = sessionId,
                                    threadId = event.thread().uniqueID().toString(),
                                    threadName = event.thread().name(),
                                    location = "${loc.sourceName()}:${loc.lineNumber()}",
                                    className = loc.declaringType().name(),
                                    exceptionMessage = "Field ${event.field().name()} accessed"
                                )
                            )
                        }
                        is ModificationWatchpointEvent -> {
                            val loc = event.location()
                            pushEvent(
                                DebugEventPayload(
                                    eventType = EventType.WATCHPOINT_MODIFY_HIT,
                                    sessionId = sessionId,
                                    threadId = event.thread().uniqueID().toString(),
                                    threadName = event.thread().name(),
                                    location = "${loc.sourceName()}:${loc.lineNumber()}",
                                    className = loc.declaringType().name(),
                                    exceptionMessage = "Field ${event.field().name()} modified to ${event.valueToBe()}"
                                )
                            )
                        }
                        is VMDeathEvent, is VMDisconnectEvent -> {
                            isConnected.set(false)
                            adbManager.removePortForward(localPort)
                            pushEvent(
                                DebugEventPayload(
                                    eventType = EventType.DISCONNECT,
                                    sessionId = sessionId,
                                    threadId = null,
                                    threadName = null,
                                    location = null,
                                    className = null
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isConnected.get()) break
            }
        }
    }

    fun detach() {
        isConnected.set(false)
        try { vm.dispose() } catch (e: Exception) {}
        adbManager.removePortForward(localPort)
    }
}
