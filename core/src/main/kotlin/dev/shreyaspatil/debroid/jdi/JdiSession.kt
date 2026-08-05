package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.Location
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.AccessWatchpointEvent
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.ExceptionEvent
import com.sun.jdi.event.ModificationWatchpointEvent
import com.sun.jdi.event.StepEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.ExceptionRequest
import com.sun.jdi.request.StepRequest
import com.sun.jdi.request.WatchpointRequest
import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.models.BreakpointInfo
import dev.shreyaspatil.debroid.models.DebugEventPayload
import dev.shreyaspatil.debroid.models.ErrorCode
import dev.shreyaspatil.debroid.models.EventPollResult
import dev.shreyaspatil.debroid.models.EventType
import dev.shreyaspatil.debroid.models.ObjectInspectionResult
import dev.shreyaspatil.debroid.models.PauseStateResult
import dev.shreyaspatil.debroid.models.SessionStatus
import dev.shreyaspatil.debroid.models.StackFrameInfo
import dev.shreyaspatil.debroid.models.StepAction
import dev.shreyaspatil.debroid.models.VariableInfo
import dev.shreyaspatil.debroid.models.VariableScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JdiSession(
    val sessionId: String,
    val appId: String,
    val localPort: Int,
    private val vm: VirtualMachine,
    private val adbManager: AdbManager,
    private val clearDebugAppOnDetach: Boolean = false
) {
    private val isConnected = AtomicBoolean(true)
    private val breakpointIdCounter = AtomicInteger(1)

    // Breakpoint & Watchpoint tracking
    private val activeBreakpoints = ConcurrentHashMap<String, BreakpointInfo>()
    private val jdiBreakpointRequests = ConcurrentHashMap<String, MutableList<BreakpointRequest>>()
    private val deferredBreakpoints = ConcurrentHashMap<String, DeferredBreakpoint>()
    private val deferredWatchpoints = ConcurrentHashMap<String, MutableList<DeferredWatchpoint>>()
    private val exceptionRequests = ConcurrentHashMap<String, ExceptionRequest>()
    private val watchpointRequests = ConcurrentHashMap<String, MutableList<WatchpointRequest>>()
    private val classPrepareRequest = AtomicReference<ClassPrepareRequest?>(null)

    private data class DeferredBreakpoint(val id: String, val file: String, val line: Int)
    private data class DeferredWatchpoint(
        val id: String,
        val fieldName: String,
        val access: Boolean,
        val modify: Boolean
    )

    private val exceptionIdCounter = AtomicInteger(1)
    private val watchpointIdCounter = AtomicInteger(1)

    // Event buffer for polling
    private val eventQueueLock = Any()
    private val eventQueueBuffer = ArrayDeque<DebugEventPayload>()

    @Volatile private var eventQueueOffset = 0

    private fun pushEvent(payload: DebugEventPayload) {
        synchronized(eventQueueLock) {
            eventQueueBuffer.add(payload)
            if (eventQueueBuffer.size > MAX_EVENT_BUFFER_SIZE) {
                eventQueueBuffer.removeFirst()
                eventQueueOffset++
            }
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
        val threads = try { vm.allThreads() } catch (
            e: com.sun.jdi.VMDisconnectedException
        ) { emptyList<ThreadReference>() }
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

    fun setBreakpoint(file: String, line: Int, condition: String?, packageName: String? = null): BreakpointInfo {
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

        val verified = bindBreakpointLocation(id = id, file = file, line = line, packageName = packageName)
        if (verified) {
            activeBreakpoints[id] = info.copy(verified = true)
        } else {
            // Defer: arm a ClassPrepareRequest (once) and remember this breakpoint
            // so that when the class is later loaded we can bind it (B1).
            deferredBreakpoints[id] = DeferredBreakpoint(id, file, line)
            ensureClassPrepareRequest()
        }

        return activeBreakpoints[id]!!
    }

    private fun bindBreakpointLocation(id: String, file: String, line: Int, packageName: String? = null): Boolean {
        val classBasename = file.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val classBasenameKt = "${classBasename}Kt"

        val fastPathClasses = packageName?.let {
            try {
                val classMatches = vm.classesByName("$packageName.$classBasename")
                val kotlinFacadeMatches = vm.classesByName("$packageName.$classBasenameKt")
                (classMatches + kotlinFacadeMatches).distinct()
            } catch (_: Throwable) {
                emptyList()
            }
        }.orEmpty()

        var requests = bindLocationsForClasses(fastPathClasses, line)

        if (requests.isEmpty()) {
            val matchingClasses = vm.allClasses().filter { ref ->
                val name = try { ref.name() } catch (_: Throwable) { return@filter false }
                val simpleName = name.substringAfterLast('.')
                val nameMatchesHeuristic = simpleName == classBasename ||
                    simpleName == classBasenameKt ||
                    simpleName.startsWith("$classBasename$") ||
                    simpleName.startsWith("$classBasenameKt$")

                if (nameMatchesHeuristic) {
                    return@filter true
                }

                if (isFrameworkClass(name)) {
                    return@filter false
                }

                try {
                    ref.sourceName() == file
                } catch (_: Throwable) {
                    false
                }
            }
            requests = bindLocationsForClasses(matchingClasses, line)
        }

        if (requests.isNotEmpty()) {
            jdiBreakpointRequests[id] = requests
            return true
        }
        return false
    }

    private fun bindLocationsForClasses(classes: List<ReferenceType>, line: Int): MutableList<BreakpointRequest> {
        val requests = mutableListOf<BreakpointRequest>()
        for (ref in classes) {
            try {
                val locations = ref.locationsOfLine(line)
                for (loc in locations) {
                    val bpReq = vm.eventRequestManager().createBreakpointRequest(loc)
                    bpReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                    bpReq.enable()
                    requests.add(bpReq)
                }
            } catch (_: Throwable) {
                // Class line info not available yet, or locationsOfLine threw unexpectedly.
            }
        }
        return requests
    }

    fun setExceptionBreakpoint(className: String?, notifyCaught: Boolean, notifyUncaught: Boolean): String {
        val erm = vm.eventRequestManager()
        val refType = className?.let { name -> vm.classesByName(name).firstOrNull() }
        val req: ExceptionRequest = erm.createExceptionRequest(refType, notifyCaught, notifyUncaught)
        req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
        req.enable()
        val id = "ex_bp_${exceptionIdCounter.getAndIncrement()}"
        exceptionRequests[id] = req
        return id
    }

    fun setWatchpoint(className: String, fieldName: String, access: Boolean = true, modify: Boolean = true): String {
        val erm = vm.eventRequestManager()
        val refTypes = vm.classesByName(className)
        val id = "wp_${watchpointIdCounter.getAndIncrement()}"

        if (refTypes.isEmpty()) {
            // Defer until class is loaded. Multiple watchpoints per class are supported (B5).
            deferredWatchpoints.computeIfAbsent(className) { mutableListOf() }.add(
                DeferredWatchpoint(id, fieldName, access, modify)
            )
            ensureClassPrepareRequest()
            return id
        }

        bindWatchpointForRefType(
            id = id,
            refType = refTypes.first(),
            fieldName = fieldName,
            access = access,
            modify = modify
        )
        return id
    }

    private fun bindWatchpointForRefType(
        id: String,
        refType: ReferenceType,
        fieldName: String,
        access: Boolean,
        modify: Boolean
    ) {
        val erm = vm.eventRequestManager()
        val field = refType.fieldByName(fieldName) ?: return
        val requests = watchpointRequests.computeIfAbsent(id) { mutableListOf() }

        if (access) {
            val req = erm.createAccessWatchpointRequest(field)
            req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            req.enable()
            requests.add(req)
        }
        if (modify) {
            val req = erm.createModificationWatchpointRequest(field)
            req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            req.enable()
            requests.add(req)
        }
    }

    /**
     * Lazily creates and enables a single shared [ClassPrepareRequest]
     * used to resolve deferred breakpoints and watchpoints. Avoid creating one per deferred
     * item (which would leak requests and cause duplicate events).
     */
    private fun ensureClassPrepareRequest() {
        if (classPrepareRequest.get() != null) return
        val req = vm.eventRequestManager().createClassPrepareRequest()
        req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
        req.enable()
        classPrepareRequest.set(req)
    }

    fun removeBreakpoint(id: String): Boolean {
        val info = activeBreakpoints.remove(id)
        deferredBreakpoints.remove(id)
        val requests = jdiBreakpointRequests.remove(id)
        requests?.forEach { req ->
            try { vm.eventRequestManager().deleteEventRequest(req) } catch (_: Exception) {}
        }
        maybeDisableClassPrepareRequest()
        return info != null
    }

    fun removeExceptionBreakpoint(id: String): Boolean {
        val req = exceptionRequests.remove(id) ?: return false
        try { vm.eventRequestManager().deleteEventRequest(req) } catch (_: Exception) {}
        return true
    }

    fun removeWatchpoint(id: String): Boolean {
        val requests = watchpointRequests.remove(id)
        if (requests.isNullOrEmpty()) {
            // Might still be deferred
            val removed = removeFromDeferredWatchpoints(id)
            maybeDisableClassPrepareRequest()
            return removed
        }
        requests.forEach { req ->
            try { vm.eventRequestManager().deleteEventRequest(req) } catch (_: Exception) {}
        }
        return true
    }

    private fun removeFromDeferredWatchpoints(id: String): Boolean {
        var removed = false
        for ((_, list) in deferredWatchpoints) {
            val it = list.iterator()
            while (it.hasNext()) {
                if (it.next().id == id) {
                    it.remove()
                    removed = true
                }
            }
        }
        // Drop classes whose deferred list is now empty
        deferredWatchpoints.entries.removeIf { it.value.isEmpty() }
        return removed
    }

    /**
     * Disables and deletes the shared ClassPrepareRequest if there are no remaining
     * deferred breakpoints or watchpoints. Callers must invoke this after any removal
     * that could leave the deferral queue empty (B6).
     */
    private fun maybeDisableClassPrepareRequest() {
        if (deferredBreakpoints.isEmpty() && deferredWatchpoints.isEmpty()) {
            classPrepareRequest.getAndSet(null)?.let { req ->
                try { vm.eventRequestManager().deleteEventRequest(req) } catch (_: Exception) {}
            }
        }
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
    @Suppress("ReturnCount")
    fun evaluateExpression(threadId: String, expr: String): VariableInfo {
        val thread = findThread(threadId)
        if (!thread.isSuspended) {
            throw DebugException(ErrorCode.THREAD_NOT_SUSPENDED, "Thread $threadId is not suspended.")
        }

        return try {
            val value = JdiExpressionEvaluator.evaluate(expr, vm, thread.frame(0))
            formatValue("evaluatedResult", value)
        } catch (e: Exception) {
            // Fallback to local variable or field lookup if ExpressionParser fails
            val frame = thread.frame(0)
            val thisObj = frame.thisObject()
            val visVar = try {
                frame.visibleVariables()
            } catch (_: com.sun.jdi.AbsentInformationException) { emptyList() }.find { it.name() == expr }
            if (visVar != null) {
                return formatValue(visVar.name(), frame.getValue(visVar))
            }
            if (thisObj != null) {
                val field = thisObj.referenceType().fieldByName(expr)
                if (field != null) {
                    return formatValue(field.name(), thisObj.getValue(field))
                }
            }
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Expression '$expr' could not be evaluated: ${e.message}"
            )
        }
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
        val id = continuationObjectId.toLongOrNull()
            ?: throw DebugException(ErrorCode.INTERNAL_ERROR, "Invalid continuation object ID")
        val objRef = findObjectReference(id)
        val refType = objRef.referenceType()

        val fields = refType.allFields().filter { !it.isStatic }
        val fieldValues = objRef.getValues(fields)

        val result = mutableMapOf<String, VariableInfo>()
        for ((f, valRef) in fieldValues) {
            result[f.name()] = formatValue(f.name(), valRef)
        }
        return result
    }

    @Suppress("ThrowsCount")
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
                sourceFile = try { location.sourceName() } catch (e: Throwable) { null },
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
                val visVars = try { frame.visibleVariables() } catch (
                    e: com.sun.jdi.AbsentInformationException
                ) { emptyList() }
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
        val visited = HashSet<String>()
        visited.add(objectId)
        return inspectRecursive(objRef, fieldsFilter, maxDepth, visited)
    }

    private fun inspectRecursive(
        objRef: ObjectReference,
        fieldsFilter: List<String>?,
        maxDepth: Int,
        visited: MutableSet<String>
    ): ObjectInspectionResult {
        val refType = objRef.referenceType()
        val fields = refType.allFields()
            .filter { f -> fieldsFilter == null || fieldsFilter.contains(f.name()) }
            .take(50)

        val fieldValues = objRef.getValues(fields)
        val resultFields = mutableMapOf<String, VariableInfo>()
        val nested = mutableMapOf<String, ObjectInspectionResult>()

        for ((f, valRef) in fieldValues) {
            resultFields[f.name()] = formatValue(f.name(), valRef)
            if (maxDepth <= 1) continue
            if (valRef !is ObjectReference) continue
            val oid = valRef.uniqueID().toString()
            if (oid in visited) continue // cycle guard
            visited.add(oid)
            try {
                nested[f.name()] = inspectRecursive(valRef, null, maxDepth - 1, visited)
            } catch (e: Exception) {
                // Best-effort: nested resolvers (e.g. findObjectReference) can recurse over
                // suspended frames and may throw on transient state; silently skip such
                // children so the top-level inspection still succeeds.
            }
        }

        return ObjectInspectionResult(
            objectId = objRef.uniqueID().toString(),
            type = refType.name(),
            fields = resultFields,
            nested = if (nested.isEmpty()) null else nested
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
                } catch (_: Exception) {}
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

    private fun safeSourceName(loc: Location): String {
        return try {
            loc.sourceName()
        } catch (e: Throwable) {
            loc.declaringType().name()
        }
    }

    private fun isFrameworkClass(name: String): Boolean {
        return name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("android.") ||
            name.startsWith("androidx.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("sun.") ||
            name.startsWith("com.sun.") ||
            name.startsWith("dalvik.") ||
            name.startsWith("libcore.") ||
            name.startsWith("com.google.") ||
            name.startsWith("org.apache.") ||
            name.startsWith("org.json.")
    }

    fun pollEvents(sinceCursor: String, withStacktrace: Boolean = false): EventPollResult {
        val cursorIndex = sinceCursor.toIntOrNull() ?: 0
        val eventsToReturn: List<DebugEventPayload>
        val nextCursorStr: String

        synchronized(eventQueueLock) {
            val actualStartIndex = maxOf(0, cursorIndex - eventQueueOffset)
            val rawList = eventQueueBuffer.toList()
            val rawSubList = if (actualStartIndex < rawList.size) {
                rawList.subList(actualStartIndex, rawList.size)
            } else {
                emptyList()
            }
            eventsToReturn = if (withStacktrace) {
                rawSubList
            } else {
                rawSubList.map { it.copy(stacktrace = null) }
            }
            nextCursorStr = (eventQueueOffset + eventQueueBuffer.size).toString()
        }

        return EventPollResult(
            events = eventsToReturn,
            nextCursor = nextCursorStr,
            hasMore = false
        )
    }

    @Suppress("NestedBlockDepth")
    private fun runEventListener() {
        val eventQueue = vm.eventQueue()
        while (isConnected.get()) {
            try {
                val eventSet = eventQueue.remove(1000) ?: continue
                for (event in eventSet) {
                    when (event) {
                        is ClassPrepareEvent -> {
                            val preparedClass = event.referenceType()
                            // Resolve deferred watchpoints (B5: list-based, multiple per class).
                            val deferredWps = deferredWatchpoints.remove(preparedClass.name())
                            deferredWps?.forEach { dw ->
                                bindWatchpointForRefType(
                                    id = dw.id,
                                    refType = preparedClass,
                                    fieldName = dw.fieldName,
                                    access = dw.access,
                                    modify = dw.modify
                                )
                            }

                            // Resolve deferred line breakpoints. A single Kotlin file can contain
                            // multiple top-level classes (e.g. OrderProcessor + DefaultDataRepository in
                            // DataRepository.kt), so we match via sourceName AND the simple-name heuristic,
                            // then fall back to trying locationsOfLine directly (B1).
                            val preparedSimpleName = preparedClass.name().substringAfterLast('.')
                            val iter = deferredBreakpoints.entries.iterator()
                            while (iter.hasNext()) {
                                val (bpId, deferred) = iter.next()
                                val basename = deferred.file.substringAfterLast(
                                    '/'
                                ).substringAfterLast('\\').substringBeforeLast('.')
                                val basenameKt = "${basename}Kt"
                                val nameMatches = run {
                                    // Try sourceName first (most accurate for Kotlin multi-class files).
                                    val srcMatches = try {
                                        preparedClass.sourceName() == deferred.file
                                    } catch (e: Throwable) {
                                        // AbsentInformationException: no source info.
                                        // InternalError: bad SourceDebugExtension on some ART classes;
                                        // either way fall back to the simple-name heuristic below.
                                        false
                                    }
                                    if (srcMatches) return@run true
                                    // Fall back to simple-name heuristic.
                                    preparedSimpleName == basename ||
                                        preparedSimpleName == basenameKt ||
                                        preparedSimpleName.startsWith("$basename$") ||
                                        preparedSimpleName.startsWith("$basenameKt$")
                                }
                                if (!nameMatches) continue
                                try {
                                    val locations = preparedClass.locationsOfLine(deferred.line)
                                    if (locations.isNotEmpty()) {
                                        val reqs = mutableListOf<BreakpointRequest>()
                                        for (loc in locations) {
                                            val bpReq = vm.eventRequestManager().createBreakpointRequest(loc)
                                            bpReq.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                                            bpReq.enable()
                                            reqs.add(bpReq)
                                        }
                                        jdiBreakpointRequests[bpId] = reqs
                                        val existing = activeBreakpoints[bpId]
                                        if (existing != null) {
                                            activeBreakpoints[bpId] = existing.copy(verified = true)
                                        }
                                        iter.remove()
                                    }
                                } catch (e: Throwable) {
                                    // Class loaded without line info, or locationsOfLine threw;
                                    // keep deferred and try on next class load.
                                }
                            }
                            maybeDisableClassPrepareRequest()
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
                                    location = "${safeSourceName(loc)}:${loc.lineNumber()}",
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
                                    location = "${safeSourceName(loc)}:${loc.lineNumber()}",
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
                                    location = "${safeSourceName(loc)}:${loc.lineNumber()}",
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
                                    location = "${safeSourceName(loc)}:${loc.lineNumber()}",
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
                                    location = "${safeSourceName(loc)}:${loc.lineNumber()}",
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
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) because JDI can throw InternalError /
                // VMOutOfMemoryError from ART's SourceDebugExtension parsing. Killing this
                // thread would leave the app suspended forever; log and keep looping.
                if (!isConnected.get()) break
                System.err.println("[JdiSession:$sessionId] event listener caught: ${e::class.java.name}: ${e.message}")
            }
        }
    }

    fun detach() {
        isConnected.set(false)
        try { vm.dispose() } catch (_: Exception) {}
        adbManager.removePortForward(localPort)
        // If this session was launched suspended via `am set-debug-app -w`, the wait-for-debugger
        // flag persists on the device. Clear it so the next normal launch doesn't hang (B4).
        if (clearDebugAppOnDetach) {
            try { adbManager.clearDebugApp() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val MAX_EVENT_BUFFER_SIZE = 1000
    }
}
