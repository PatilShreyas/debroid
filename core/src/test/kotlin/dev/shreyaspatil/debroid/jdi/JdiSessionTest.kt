package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.Field
import com.sun.jdi.IntegerValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.AccessWatchpointRequest
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.ExceptionRequest
import com.sun.jdi.request.ModificationWatchpointRequest
import com.sun.jdi.request.StepRequest
import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.models.DebugEventPayload
import dev.shreyaspatil.debroid.models.ErrorCode
import dev.shreyaspatil.debroid.models.EventType
import dev.shreyaspatil.debroid.models.StackFrameInfo
import dev.shreyaspatil.debroid.models.StepAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JdiSessionTest {

    private lateinit var adbManager: AdbManager
    private lateinit var vm: VirtualMachine
    private lateinit var session: JdiSession
    private lateinit var erm: EventRequestManager

    @BeforeEach
    fun setup() {
        adbManager = mockk(relaxed = true)
        vm = mockk(relaxed = true)
        erm = mockk(relaxed = true)
        every { vm.eventRequestManager() } returns erm
        session = JdiSession(
            sessionId = "sess_1",
            appId = "com.test.app",
            localPort = 8080,
            vm = vm,
            adbManager = adbManager
        )
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `isAlive returns true if connected and allThreads succeeds`() {
        every { vm.allThreads() } returns emptyList()
        assertTrue(session.isAlive())
    }

    @Test
    fun `isAlive returns false if allThreads throws exception`() {
        every { vm.allThreads() } throws VMDisconnectedException()
        assertFalse(session.isAlive())
    }

    @Test
    fun `getStatus returns correct status`() {
        val thread1 = mockk<ThreadReference>(relaxed = true) {
            every { isSuspended } returns true
        }
        val thread2 = mockk<ThreadReference>(relaxed = true) {
            every { isSuspended } returns false
        }
        every { vm.allThreads() } returns listOf(thread1, thread2)

        val status = session.getStatus()

        assertEquals("sess_1", status.sessionId)
        assertEquals("com.test.app", status.appId)
        assertTrue(status.connected)
        assertEquals(0, status.activeBreakpointsCount)
        assertEquals(1, status.suspendedThreadsCount)
    }

    @Test
    fun `setBreakpoint binds if class is found`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { refType.name() } returns "com.test.MainActivity"
        every { refType.sourceName() } returns "MainActivity.kt"
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(refType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42, condition = null)

        assertTrue(info.verified)
        assertEquals(42, info.line)
        assertEquals("MainActivity.kt", info.file)
        verify { bpReq.enable() }
    }

    @Test
    fun `setBreakpoint skips framework classes without calling sourceName`() {
        val frameworkType = mockk<ReferenceType>(relaxed = true)
        val appType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { frameworkType.name() } returns "android.view.View"
        every { appType.name() } returns "com.test.CustomHelper"
        every { appType.sourceName() } returns "DataRepository.kt"
        every { appType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(frameworkType, appType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(file = "DataRepository.kt", line = 42, condition = null)

        assertTrue(info.verified)
        verify(exactly = 0) { frameworkType.sourceName() }
        verify(exactly = 1) { appType.sourceName() }
    }

    @Test
    fun `setBreakpoint matches by simple name heuristic without calling sourceName`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { refType.name() } returns "com.test.MainActivity"
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(refType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42, condition = null)

        assertTrue(info.verified)
        verify(exactly = 0) { refType.sourceName() }
    }

    @Test
    fun `setBreakpoint defers if class not found`() {
        every { vm.allClasses() } returns emptyList()

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42, condition = null)

        assertFalse(info.verified)
        verify { erm.createClassPrepareRequest() }
    }

    @Test
    fun `setBreakpoint with packageName uses classesByName and skips allClasses`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { vm.classesByName("com.test.MainActivity") } returns listOf(refType)
        every { vm.classesByName("com.test.MainActivityKt") } returns emptyList()
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(
            file = "MainActivity.kt",
            line = 42,
            condition = null,
            packageName = "com.test"
        )

        assertTrue(info.verified)
        verify(exactly = 0) { vm.allClasses() }
        verify { bpReq.enable() }
    }

    @Test
    fun `setBreakpoint with packageName falls back to allClasses if classesByName returns empty or throws`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { vm.classesByName("com.test.MainActivity") } throws RuntimeException("JDI error")
        every { vm.classesByName("com.test.MainActivityKt") } returns emptyList()

        every { refType.name() } returns "com.test.MainActivity"
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(refType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(
            file = "MainActivity.kt",
            line = 42,
            condition = null,
            packageName = "com.test"
        )

        assertTrue(info.verified)
        verify(exactly = 1) { vm.allClasses() }
        verify { bpReq.enable() }
    }

    @Test
    fun `setBreakpoint with packageName falls back to allClasses if classesByName returns empty`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { vm.classesByName("com.test.MainActivity") } returns emptyList()
        every { vm.classesByName("com.test.MainActivityKt") } returns emptyList()

        every { refType.name() } returns "com.test.MainActivity"
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(refType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(
            file = "MainActivity.kt",
            line = 42,
            condition = null,
            packageName = "com.test"
        )

        assertTrue(info.verified)
        verify(exactly = 1) { vm.allClasses() }
        verify { bpReq.enable() }
    }

    @Test
    fun `setBreakpoint with packageName falls back to allClasses if targeted classes have no locations for line`() {
        val targetedType = mockk<ReferenceType>(relaxed = true)
        val fallbackType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { vm.classesByName("com.test.MainActivity") } returns listOf(targetedType)
        every { vm.classesByName("com.test.MainActivityKt") } returns emptyList()
        every { targetedType.locationsOfLine(42) } returns emptyList()

        every { fallbackType.name() } returns "com.test.MainActivity\$Inner"
        every { fallbackType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(targetedType, fallbackType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(
            file = "MainActivity.kt",
            line = 42,
            condition = null,
            packageName = "com.test"
        )

        assertTrue(info.verified)
        verify(exactly = 1) { vm.allClasses() }
        verify { bpReq.enable() }
    }

    @Test
    fun `setBreakpoint with packageName deduplicates classesByName results`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { vm.classesByName("com.test.MainActivity") } returns listOf(refType)
        every { vm.classesByName("com.test.MainActivityKt") } returns listOf(refType)
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(
            file = "MainActivity.kt",
            line = 42,
            condition = null,
            packageName = "com.test"
        )

        assertTrue(info.verified)
        verify(exactly = 1) { refType.locationsOfLine(42) }
    }

    @Test
    fun `stepExecution clears old requests and creates new step request`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { vm.allThreads() } returns listOf(thread)

        val stepReqOld = mockk<StepRequest>(relaxed = true)
        every { stepReqOld.thread() } returns thread
        every { erm.stepRequests() } returns listOf(stepReqOld)

        val stepReqNew = mockk<StepRequest>(relaxed = true)
        every { erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER) } returns stepReqNew

        session.stepExecution("1", dev.shreyaspatil.debroid.models.StepAction.STEP_OVER)

        verify { erm.deleteEventRequest(stepReqOld) }
        verify { stepReqNew.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD) }
        verify { stepReqNew.enable() }
        verify { thread.resume() }
    }

    @Test
    fun `getVariables returns locals`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val local = mockk<LocalVariable>(relaxed = true)
        val value = mockk<PrimitiveValue>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { local.name() } returns "myLocal"
        every { local.isArgument } returns false
        every { frame.visibleVariables() } returns listOf(local)
        every { frame.getValue(local) } returns value
        every { value.type().name() } returns "int"
        every { value.toString() } returns "42"

        val vars = session.getVariables("1", dev.shreyaspatil.debroid.models.VariableScope.LOCAL)

        assertEquals(1, vars.size)
        assertEquals("myLocal", vars[0].name)
        assertEquals("int", vars[0].type)
        assertEquals("42", vars[0].valuePreview)
    }

    @Test
    fun `evaluateExpression evaluates simple local variable`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val local = mockk<LocalVariable>(relaxed = true)
        val value = mockk<StringReference>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { frame.thisObject() } returns null
        every { local.name() } returns "myString"
        every { frame.visibleVariables() } returns listOf(local)
        every { frame.getValue(local) } returns value
        every { value.value() } returns "hello"
        every { value.uniqueID() } returns 99L

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("myString", vm, frame) } returns value

        val result = session.evaluateExpression("1", "myString")

        assertEquals("evaluatedResult", result.name)
        assertEquals("String", result.type)
        assertEquals("\"hello\"", result.valuePreview)
        assertTrue(result.isPrimitive)

        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `evaluateExpression evaluates string concatenation`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        every { thread.frame(0) } returns frame
        every { frame.thisObject() } returns null
        every { frame.visibleVariables() } returns emptyList()

        val mirrorStr = mockk<StringReference>(relaxed = true)
        every { vm.mirrorOf("hello world") } returns mirrorStr
        every { mirrorStr.uniqueID() } returns 100L
        every { mirrorStr.value() } returns "hello world"

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("\"hello\" + \" \" + \"world\"", vm, frame) } returns mirrorStr

        val result = session.evaluateExpression("1", "\"hello\" + \" \" + \"world\"")

        assertEquals("evaluatedResult", result.name)
        assertEquals("String", result.type)
        assertEquals("\"hello world\"", result.valuePreview)
        assertTrue(result.isPrimitive)

        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `evaluateExpression falls back to local variable when evaluator fails`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val local = mockk<LocalVariable>(relaxed = true)
        val value = mockk<StringReference>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { frame.thisObject() } returns null
        every { local.name() } returns "myVar"
        every { frame.visibleVariables() } returns listOf(local)
        every { frame.getValue(local) } returns value
        every { value.value() } returns "fallbackValue"
        every { value.uniqueID() } returns 101L

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("myVar", vm, frame) } throws RuntimeException("Parser syntax error")

        val result = session.evaluateExpression("1", "myVar")

        assertEquals("myVar", result.name)
        assertEquals("String", result.type)
        assertEquals("\"fallbackValue\"", result.valuePreview)

        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `evaluateExpression falls back to instance field when evaluator fails`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val thisObj = mockk<ObjectReference>(relaxed = true)
        val refType = mockk<ReferenceType>(relaxed = true)
        val field = mockk<Field>(relaxed = true)
        val value = mockk<StringReference>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { frame.thisObject() } returns thisObj
        every { frame.visibleVariables() } returns emptyList()
        every { thisObj.referenceType() } returns refType
        every { refType.fieldByName("myField") } returns field
        every { field.name() } returns "myField"
        every { thisObj.getValue(field) } returns value
        every { value.value() } returns "fieldValue"
        every { value.uniqueID() } returns 102L

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("myField", vm, frame) } throws RuntimeException("Parser error")

        val result = session.evaluateExpression("1", "myField")

        assertEquals("myField", result.name)
        assertEquals("String", result.type)
        assertEquals("\"fieldValue\"", result.valuePreview)

        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `evaluateExpression throws DebugException when evaluation completely fails`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        every { thread.frame(0) } returns frame
        every { frame.thisObject() } returns null
        every { frame.visibleVariables() } returns emptyList()

        every {
            JdiExpressionEvaluator.evaluate("unknownExpr", vm, frame)
        } throws RuntimeException("Unknown identifier")

        val ex = assertThrows<DebugException> {
            session.evaluateExpression("1", "unknownExpr")
        }

        assertEquals(ErrorCode.EVALUATION_FAILED, ex.code)
        assertTrue(ex.message!!.contains("unknownExpr"))

        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `evaluateExpression throws DebugException when thread is not suspended`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns false
        every { vm.allThreads() } returns listOf(thread)

        val ex = assertThrows<DebugException> {
            session.evaluateExpression("1", "order.getAmount()")
        }

        assertEquals(ErrorCode.THREAD_NOT_SUSPENDED, ex.code)
    }

    @Test
    fun `detach cleans up properly`() {
        session.detach()
        verify { vm.dispose() }
        verify { adbManager.removePortForward(8080) }
        assertFalse(session.isAlive())
    }

    @Test
    fun `listThreads maps threads correctly`() {
        val thread1 = mockk<ThreadReference>(relaxed = true)
        every { thread1.uniqueID() } returns 1L
        every { thread1.name() } returns "main"
        every { thread1.status() } returns ThreadReference.THREAD_STATUS_RUNNING
        every { thread1.isSuspended } returns false

        every { vm.allThreads() } returns listOf(thread1)

        val threads = session.listThreads()
        assertEquals(1, threads.size)
        assertEquals("1", threads[0]["thread_id"])
        assertEquals("main", threads[0]["thread_name"])
        assertEquals(ThreadReference.THREAD_STATUS_RUNNING.toString(), threads[0]["status"])
        assertEquals("false", threads[0]["is_suspended"])
    }

    @Test
    fun `getStackFrames correctly maps frames`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val method = mockk<Method>(relaxed = true)
        val declaringType = mockk<ReferenceType>(relaxed = true)

        every { thread.frames() } returns listOf(frame)
        every { frame.location() } returns location
        every { location.method() } returns method
        every { method.name() } returns "myMethod"
        every { location.declaringType() } returns declaringType
        every { declaringType.name() } returns "com.test.App"
        every { location.sourceName() } returns "App.kt"
        every { location.lineNumber() } returns 10

        val frames = session.getStackFrames("1")
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].frameIndex)
        assertEquals("myMethod", frames[0].methodName)
        assertEquals("com.test.App", frames[0].declaringClass)
        assertEquals("App.kt", frames[0].sourceFile)
        assertEquals(10, frames[0].lineNumber)
    }

    @Test
    fun `setVariable mutates primitive correctly`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val local = mockk<LocalVariable>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { frame.visibleVariables() } returns listOf(local)
        every { local.name() } returns "myInt"
        every { local.typeName() } returns "int"

        val intValue = mockk<IntegerValue>(relaxed = true)
        every { vm.mirrorOf(42) } returns intValue

        val result = session.setVariable(threadId = "1", varName = "myInt", newValueStr = "42")

        verify { frame.setValue(local, intValue) }
        assertEquals("myInt", result.name)
    }

    @Test
    fun `pollEvents returns empty initially`() {
        val result = session.pollEvents("0")
        assertEquals(0, result.events.size)
        assertEquals("0", result.nextCursor)
        assertFalse(result.hasMore)
    }

    @Test
    fun `pollEvents filters stacktrace correctly based on flag`() {
        val payload = DebugEventPayload(
            eventType = EventType.BREAKPOINT_HIT,
            sessionId = "sess_1",
            threadId = "1",
            threadName = "main",
            location = "Main.kt:10",
            className = "Main",
            stacktrace = listOf(
                StackFrameInfo(0, "run", "Main", "Main.kt", 10, null)
            )
        )

        val bufferField = JdiSession::class.java.getDeclaredField("eventQueueBuffer")
        bufferField.isAccessible = true
        val buffer = bufferField.get(
            session
        ) as MutableCollection<dev.shreyaspatil.debroid.models.DebugEventPayload>
        buffer.add(payload)

        // without stacktrace
        val noTraceResult = session.pollEvents("0", withStacktrace = false)
        assertEquals(1, noTraceResult.events.size)
        assertNull(noTraceResult.events[0].stacktrace)

        // with stacktrace
        val withTraceResult = session.pollEvents("0", withStacktrace = true)
        assertEquals(1, withTraceResult.events.size)
        assertNotNull(withTraceResult.events[0].stacktrace)
        assertEquals(1, withTraceResult.events[0].stacktrace?.size)
    }

    @Test
    fun `pollEvents is thread safe and calculates cursors correctly during concurrent pushes and evictions`() {
        val pushMethod = JdiSession::class.java.getDeclaredMethod(
            "pushEvent",
            DebugEventPayload::class.java
        )
        pushMethod.isAccessible = true

        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val pushCount = 1500

        val pushTask = Runnable {
            for (i in 0 until pushCount) {
                val payload = dev.shreyaspatil.debroid.models.DebugEventPayload(
                    eventType = dev.shreyaspatil.debroid.models.EventType.BREAKPOINT_HIT,
                    sessionId = "sess_1",
                    threadId = "1",
                    threadName = "main",
                    location = "Main.kt:$i",
                    className = "Main"
                )
                pushMethod.invoke(session, payload)
            }
        }

        val pollResults = java.util.concurrent.ConcurrentLinkedQueue<dev.shreyaspatil.debroid.models.EventPollResult>()
        val pollTask = Runnable {
            for (i in 0 until 50) {
                val res = session.pollEvents("0")
                pollResults.add(res)
                Thread.sleep(1)
            }
        }

        val futures = listOf(
            executor.submit(pushTask),
            executor.submit(pollTask),
            executor.submit(pollTask)
        )

        futures.forEach { it.get() }
        executor.shutdown()

        val finalPoll = session.pollEvents("0")
        assertEquals(1000, finalPoll.events.size)
        assertEquals("1500", finalPoll.nextCursor)
    }

    // ---------------- B1: deferred breakpoints ----------------

    @Test
    fun `setBreakpoint on not-yet-loaded class defers and arms a single shared ClassPrepareRequest`() {
        every { vm.allClasses() } returns emptyList()
        // The deferred path should ask the erm for ONE ClassPrepareRequest.
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42, condition = null)

        assertFalse(info.verified)
        verify(exactly = 1) { erm.createClassPrepareRequest() }
        verify(exactly = 1) { classPrepReq.enable() }
    }

    @Test
    fun `setBreakpoint defers only one ClassPrepareRequest for two deferred breakpoints on distinct classes`() {
        every { vm.allClasses() } returns emptyList()
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        session.setBreakpoint(file = "MainActivity.kt", line = 10, condition = null)
        session.setBreakpoint(file = "OtherActivity.kt", line = 20, condition = null)

        verify(exactly = 1) { erm.createClassPrepareRequest() }
    }

    @Test
    fun `removeBreakpoint on deferred-only id disarms ClassPrepareRequest when nothing else is deferred`() {
        every { vm.allClasses() } returns emptyList()
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 10, condition = null)
        val removed = session.removeBreakpoint(info.id)

        assertTrue(removed)
        verify(exactly = 1) { erm.deleteEventRequest(classPrepReq) }
    }

    // ---------------- B2: exception breakpoint semantics ----------------

    @Test
    fun `setExceptionBreakpoint with uncaughtOnly passes notifyCaught=false notifyUncaught=true`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        every { vm.classesByName("java.lang.NullPointerException") } returns listOf(refType)
        val req = mockk<ExceptionRequest>(relaxed = true)
        every { erm.createExceptionRequest(refType, false, true) } returns req

        val id = session.setExceptionBreakpoint(
            "java.lang.NullPointerException",
            notifyCaught = false,
            notifyUncaught = true
        )

        assertTrue(id.startsWith("ex_bp_"))
        verify(exactly = 1) { erm.createExceptionRequest(refType, false, true) }
        verify { req.enable() }
    }

    @Test
    fun `setExceptionBreakpoint with caught passes notifyCaught=true`() {
        every { vm.classesByName(any()) } returns emptyList()
        val req = mockk<ExceptionRequest>(relaxed = true)
        every { erm.createExceptionRequest(null, true, true) } returns req

        session.setExceptionBreakpoint(null, notifyCaught = true, notifyUncaught = true)

        verify(exactly = 1) { erm.createExceptionRequest(null, true, true) }
    }

    // ---------------- B6: removal of exception & watchpoint ----------------

    @Test
    fun `removeExceptionBreakpoint deletes the underlying JDI request`() {
        every { vm.classesByName(any()) } returns emptyList()
        val req = mockk<ExceptionRequest>(relaxed = true)
        every { erm.createExceptionRequest(null, any(), any()) } returns req

        val id = session.setExceptionBreakpoint(null, notifyCaught = false, notifyUncaught = true)
        val removed = session.removeExceptionBreakpoint(id)

        assertTrue(removed)
        verify { erm.deleteEventRequest(req) }
    }

    @Test
    fun `removeExceptionBreakpoint returns false for unknown id`() {
        assertFalse(session.removeExceptionBreakpoint("ex_bp_999"))
    }

    @Test
    fun `setWatchpoint on loaded class tracks requests and removeWatchpoint deletes them`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        every { refType.name() } returns "com.example.Foo"
        every { vm.classesByName("com.example.Foo") } returns listOf(refType)
        val field = mockk<Field>(relaxed = true)
        every { refType.fieldByName("bar") } returns field

        val accessReq = mockk<AccessWatchpointRequest>(relaxed = true)
        val modifyReq = mockk<ModificationWatchpointRequest>(relaxed = true)
        every { erm.createAccessWatchpointRequest(field) } returns accessReq
        every { erm.createModificationWatchpointRequest(field) } returns modifyReq

        val id = session.setWatchpoint("com.example.Foo", "bar", access = true, modify = true)
        val removed = session.removeWatchpoint(id)

        assertTrue(removed)
        verify { erm.deleteEventRequest(accessReq) }
        verify { erm.deleteEventRequest(modifyReq) }
    }

    // ---------------- B5: multiple deferred watchpoints per class ---------

    @Test
    fun `setWatchpoint on deferred class arms single ClassPrepareRequest`() {
        every { vm.classesByName("com.example.Foo") } returns emptyList()
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        val id1 = session.setWatchpoint("com.example.Foo", "fieldA")
        val id2 = session.setWatchpoint("com.example.Foo", "fieldB")

        assertNotEquals(id1, id2)
        verify(exactly = 1) { erm.createClassPrepareRequest() }

        // Removing one should NOT disarm the shared ClassPrepareRequest (other deferred remains).
        assertTrue(session.removeWatchpoint(id1))
        verify(exactly = 0) { erm.deleteEventRequest(classPrepReq) }

        // Removing the second should NOW disarm it.
        assertTrue(session.removeWatchpoint(id2))
        verify(exactly = 1) { erm.deleteEventRequest(classPrepReq) }
    }

    // ---------------- B4: clearDebugApp called on detach when launched -----

    @Test
    fun `detach clears am set-debug-app when session was launched suspended`() {
        every { adbManager.clearDebugApp() } returns Result.success(Unit)
        val launchedSession = JdiSession(
            sessionId = "sess_launched",
            appId = "com.test.app",
            localPort = 8080,
            vm = vm,
            adbManager = adbManager,
            clearDebugAppOnDetach = true
        )
        launchedSession.detach()
        verify { adbManager.clearDebugApp() }
    }

    @Test
    fun `detach does not clear am set-debug-app when session was attached to running app`() {
        val attachedSession = JdiSession(
            sessionId = "sess_attached",
            appId = "com.test.app",
            localPort = 8080,
            vm = vm,
            adbManager = adbManager,
            clearDebugAppOnDetach = false
        )
        attachedSession.detach()
        verify(exactly = 0) { adbManager.clearDebugApp() }
    }
}
