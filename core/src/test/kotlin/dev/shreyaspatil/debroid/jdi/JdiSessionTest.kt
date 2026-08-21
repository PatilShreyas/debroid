package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatValue
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
import com.sun.jdi.event.EventQueue
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
import dev.shreyaspatil.debroid.models.ThreadInfo
import dev.shreyaspatil.debroid.models.ThreadStatus
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

@Suppress("LargeClass")
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
        val eventQueue = mockk<EventQueue>(relaxed = true)
        every { vm.eventQueue() } returns eventQueue
        every { eventQueue.remove(any()) } answers {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                // Thread interrupted on detach
            }
            null
        }
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
        if (::session.isInitialized) {
            try {
                session.detach()
            } catch (_: Throwable) {
                // Ignore cleanup errors during teardown
            }
        }
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

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42)

        assertTrue(info.verified)
        assertEquals(42, info.line)
        assertEquals("MainActivity.kt", info.file)
        verify { bpReq.enable() }
    }

    @Test
    fun `setBreakpoint returns existing breakpoint if duplicate is requested`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { refType.name() } returns "com.test.MainActivity"
        every { refType.sourceName() } returns "MainActivity.kt"
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(refType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info1 = session.setBreakpoint(file = "MainActivity.kt", line = 42)
        val info2 = session.setBreakpoint(file = "MainActivity.kt", line = 42)

        assertTrue(info1.verified)
        assertEquals(info1.id, info2.id)
        verify(exactly = 1) { erm.createBreakpointRequest(location) }
    }

    @Test
    fun `setBreakpoint returns existing deferred breakpoint if duplicate is requested`() {
        every { vm.allClasses() } returns emptyList()

        val info1 = session.setBreakpoint(file = "MainActivity.kt", line = 42)
        val info2 = session.setBreakpoint(file = "MainActivity.kt", line = 42)

        assertFalse(info1.verified)
        assertEquals(info1.id, info2.id)
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

        val info = session.setBreakpoint(file = "DataRepository.kt", line = 42)

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

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42)

        assertTrue(info.verified)
        verify(exactly = 0) { refType.sourceName() }
    }

    @Test
    fun `setBreakpoint defers if class not found`() {
        every { vm.allClasses() } returns emptyList()

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42)

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
            packageName = "com.test"
        )

        assertTrue(info.verified)
        verify(exactly = 1) { refType.locationsOfLine(42) }
    }

    @Test
    fun `setBreakpoint with packageName skips sourceName call on non-matching package classes during fallback`() {
        val matchingType = mockk<ReferenceType>(relaxed = true)
        val nonMatchingType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        every { vm.classesByName("com.example.data.DataRepository") } returns emptyList()
        every { vm.classesByName("com.example.data.DataRepositoryKt") } returns emptyList()

        every { matchingType.name() } returns "com.example.data.DefaultDataRepository"
        every { matchingType.sourceName() } returns "DataRepository.kt"
        every { matchingType.locationsOfLine(41) } returns listOf(location)

        every { nonMatchingType.name() } returns "com.other.UnrelatedClass"
        every { nonMatchingType.sourceName() } returns "UnrelatedClass.java"

        every { vm.allClasses() } returns listOf(nonMatchingType, matchingType)
        every { erm.createBreakpointRequest(location) } returns bpReq

        val info = session.setBreakpoint(
            file = "DataRepository.kt",
            line = 41,
            packageName = "com.example.data"
        )

        assertTrue(info.verified)
        verify(exactly = 0) { nonMatchingType.sourceName() }
        verify(exactly = 1) { matchingType.sourceName() }
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
        verify { stepReqNew.setSuspendPolicy(EventRequest.SUSPEND_ALL) }
        verify { stepReqNew.enable() }
        verify { vm.resume() }
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
    fun `getPoints returns all active and deferred points`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val bpReq = mockk<BreakpointRequest>(relaxed = true)

        // Set an active breakpoint
        every { refType.name() } returns "com.test.MainActivity"
        every { refType.locationsOfLine(42) } returns listOf(location)
        every { vm.allClasses() } returns listOf(refType)
        every { erm.createBreakpointRequest(location) } returns bpReq
        session.setBreakpoint(file = "MainActivity.kt", line = 42)

        // Set a deferred watchpoint (class not found)
        every { vm.classesByName("com.test.DataRepo") } returns emptyList()
        val wpId = session.setWatchpoint(
            className = "com.test.DataRepo",
            fieldName = "count",
            access = true,
            modify = true
        )

        // Set an active exception breakpoint
        val exReq = mockk<ExceptionRequest>(relaxed = true)
        every { exReq.getProperty("className") } returns "java.lang.NullPointerException"
        every { exReq.notifyCaught() } returns false
        every { exReq.notifyUncaught() } returns true
        every { vm.classesByName("java.lang.NullPointerException") } returns listOf(mockk(relaxed = true))
        every { erm.createExceptionRequest(any(), any(), any()) } returns exReq
        val exBpId = session.setExceptionBreakpoint(
            className = "java.lang.NullPointerException",
            notifyCaught = false,
            notifyUncaught = true
        )

        val points = session.getPoints()

        assertEquals(1, points.breakpoints.size)
        assertEquals(42, points.breakpoints[0].line)
        assertEquals("MainActivity.kt", points.breakpoints[0].file)

        assertEquals(1, points.exceptionBreakpoints.size)
        assertEquals(exBpId, points.exceptionBreakpoints[0].id)
        assertEquals("java.lang.NullPointerException", points.exceptionBreakpoints[0].className)

        assertEquals(1, points.watchpoints.size)
        assertEquals(wpId, points.watchpoints[0].id)
        assertEquals("com.test.DataRepo", points.watchpoints[0].className)
        assertEquals("count", points.watchpoints[0].fieldName)
    }

    @Test
    fun `setExceptionBreakpoint replaces existing breakpoint if className matches`() {
        val exReq1 = mockk<ExceptionRequest>(relaxed = true)
        val exReq2 = mockk<ExceptionRequest>(relaxed = true)
        every { exReq1.getProperty("className") } returns "java.lang.NullPointerException"
        every { exReq2.getProperty("className") } returns "java.lang.NullPointerException"

        every { exReq1.notifyCaught() } returns false
        every { exReq1.notifyUncaught() } returns true

        every { exReq2.notifyCaught() } returns true
        every { exReq2.notifyUncaught() } returns false

        every { vm.classesByName("java.lang.NullPointerException") } returns listOf(mockk(relaxed = true))

        // Mock returning req1 first, then req2
        every { erm.createExceptionRequest(any(), any(), any()) } returnsMany listOf(exReq1, exReq2)

        val id1 = session.setExceptionBreakpoint(
            className = "java.lang.NullPointerException",
            notifyCaught = false,
            notifyUncaught = true
        )
        val id2 = session.setExceptionBreakpoint(
            className = "java.lang.NullPointerException",
            notifyCaught = true,
            notifyUncaught = false
        )

        assertNotEquals(id1, id2)
        verify(exactly = 2) { erm.createExceptionRequest(any(), any(), any()) }
        verify { erm.deleteEventRequest(exReq1) } // Make sure the first one was removed
    }

    @Test
    fun `setWatchpoint replaces existing watchpoint if className and fieldName match`() {
        val refType = mockk<ReferenceType>(relaxed = true)
        val field = mockk<Field>(relaxed = true)
        every { field.name() } returns "count"
        every { field.declaringType().name() } returns "com.test.DataRepo"
        every { refType.fieldByName("count") } returns field
        every { vm.classesByName("com.test.DataRepo") } returns listOf(refType)

        val accessReq1 = mockk<AccessWatchpointRequest>(relaxed = true)
        val modReq2 = mockk<ModificationWatchpointRequest>(relaxed = true)

        every { accessReq1.field() } returns field
        every { modReq2.field() } returns field

        every { erm.createAccessWatchpointRequest(field) } returns accessReq1
        every { erm.createModificationWatchpointRequest(field) } returns modReq2

        // First call sets access=true, modify=false
        val id1 = session.setWatchpoint(
            className = "com.test.DataRepo",
            fieldName = "count",
            access = true,
            modify = false
        )

        // Second call sets access=false, modify=true
        val id2 = session.setWatchpoint(
            className = "com.test.DataRepo",
            fieldName = "count",
            access = false,
            modify = true
        )

        assertNotEquals(id1, id2)
        verify(exactly = 1) { erm.createAccessWatchpointRequest(field) }
        verify(exactly = 1) { erm.createModificationWatchpointRequest(field) }
        verify { erm.deleteEventRequest(accessReq1) } // Ensure old event request was deleted
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

        val thread2 = mockk<ThreadReference>(relaxed = true)
        every { thread2.uniqueID() } returns 2L
        every { thread2.name() } returns "worker"
        every { thread2.status() } returns ThreadReference.THREAD_STATUS_WAIT
        every { thread2.isSuspended } returns true

        every { vm.allThreads() } returns listOf(thread1, thread2)

        val threads = session.listThreads()
        assertEquals(2, threads.size)
        assertEquals(
            ThreadInfo(
                threadId = "1",
                threadName = "main",
                status = ThreadStatus.RUNNING,
                isSuspended = false
            ),
            threads[0]
        )
        assertEquals(
            ThreadInfo(
                threadId = "2",
                threadName = "worker",
                status = ThreadStatus.WAIT,
                isSuspended = true
            ),
            threads[1]
        )
    }

    @Test
    fun `listThreads maps all thread status codes correctly`() {
        val statuses = listOf(
            ThreadReference.THREAD_STATUS_RUNNING to ThreadStatus.RUNNING,
            ThreadReference.THREAD_STATUS_SLEEPING to ThreadStatus.SLEEPING,
            ThreadReference.THREAD_STATUS_WAIT to ThreadStatus.WAIT,
            ThreadReference.THREAD_STATUS_MONITOR to ThreadStatus.MONITOR,
            ThreadReference.THREAD_STATUS_NOT_STARTED to ThreadStatus.NOT_STARTED,
            ThreadReference.THREAD_STATUS_ZOMBIE to ThreadStatus.ZOMBIE,
            ThreadReference.THREAD_STATUS_UNKNOWN to ThreadStatus.UNKNOWN,
            999 to ThreadStatus.UNKNOWN
        )

        val mockThreads = statuses.mapIndexed { index, (statusCode, _) ->
            val t = mockk<ThreadReference>(relaxed = true)
            every { t.uniqueID() } returns (index + 1).toLong()
            every { t.name() } returns "thread-$index"
            every { t.status() } returns statusCode
            every { t.isSuspended } returns false
            t
        }

        every { vm.allThreads() } returns mockThreads

        val threads = session.listThreads()
        assertEquals(statuses.size, threads.size)
        statuses.forEachIndexed { index, (_, expectedStatus) ->
            assertEquals(expectedStatus, threads[index].status)
        }
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

        val intType = mockk<com.sun.jdi.IntegerType>(relaxed = true)
        every { intType.name() } returns "int"
        every { local.type() } returns intType

        val intValue = mockk<IntegerValue>(relaxed = true)
        every { intValue.type() } returns intType

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("42", vm, frame) } returns intValue

        val result = session.setVariable(threadId = "1", varName = "myInt", newValueStr = "42")

        verify { frame.setValue(local, intValue) }
        assertEquals("myInt", result.name)
        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `setVariable coerces primitives when types mismatch but are compatible`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val local = mockk<LocalVariable>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { frame.visibleVariables() } returns listOf(local)
        every { local.name() } returns "myDouble"

        val doubleType = mockk<com.sun.jdi.DoubleType>(relaxed = true)
        every { doubleType.name() } returns "double"
        every { local.type() } returns doubleType

        val floatType = mockk<com.sun.jdi.FloatType>(relaxed = true)
        val evaluatedFloatValue = mockk<FloatValue>(relaxed = true)
        every { evaluatedFloatValue.type() } returns floatType
        every { evaluatedFloatValue.doubleValue() } returns 88.88

        val coercedDoubleValue = mockk<DoubleValue>(relaxed = true)
        every { vm.mirrorOf(88.88) } returns coercedDoubleValue

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("88.88", vm, frame) } returns evaluatedFloatValue

        val result = session.setVariable(threadId = "1", varName = "myDouble", newValueStr = "88.88")

        verify { frame.setValue(local, coercedDoubleValue) }
        assertEquals("myDouble", result.name)
        unmockkStatic(JdiExpressionEvaluator::class)
    }

    @Test
    fun `setVariable throws INTERNAL_ERROR when primitive coercion is not possible due to incompatibility`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { vm.allThreads() } returns listOf(thread)

        val frame = mockk<StackFrame>(relaxed = true)
        val local = mockk<LocalVariable>(relaxed = true)

        every { thread.frame(0) } returns frame
        every { frame.visibleVariables() } returns listOf(local)
        every { local.name() } returns "myString"

        val stringType = mockk<com.sun.jdi.ReferenceType>(relaxed = true)
        every { stringType.name() } returns "java.lang.String"
        every { local.type() } returns stringType

        val floatType = mockk<com.sun.jdi.FloatType>(relaxed = true)
        val evaluatedFloatValue = mockk<FloatValue>(relaxed = true)
        every { evaluatedFloatValue.type() } returns floatType

        mockkStatic(JdiExpressionEvaluator::class)
        every { JdiExpressionEvaluator.evaluate("88.88", vm, frame) } returns evaluatedFloatValue

        val exception = assertThrows<DebugException> {
            session.setVariable(threadId = "1", varName = "myString", newValueStr = "88.88")
        }

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.code)
        assertTrue(exception.message!!.contains("Type mismatch"))
        unmockkStatic(JdiExpressionEvaluator::class)
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

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 42)

        assertFalse(info.verified)
        verify(exactly = 1) { erm.createClassPrepareRequest() }
        verify(exactly = 1) { classPrepReq.enable() }
    }

    @Test
    fun `setBreakpoint defers only one ClassPrepareRequest for two deferred breakpoints on distinct classes`() {
        every { vm.allClasses() } returns emptyList()
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        session.setBreakpoint(file = "MainActivity.kt", line = 10)
        session.setBreakpoint(file = "OtherActivity.kt", line = 20)

        verify(exactly = 1) { erm.createClassPrepareRequest() }
    }

    @Test
    fun `removeBreakpoint on deferred-only id disarms ClassPrepareRequest when nothing else is deferred`() {
        every { vm.allClasses() } returns emptyList()
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        val info = session.setBreakpoint(file = "MainActivity.kt", line = 10)
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

    @Test
    fun `setExceptionBreakpoint on deferred class arms single ClassPrepareRequest`() {
        every { vm.classesByName("com.example.FooException") } returns emptyList()
        every { vm.classesByName("com.example.BarException") } returns emptyList()
        val classPrepReq = mockk<ClassPrepareRequest>(relaxed = true)
        every { erm.createClassPrepareRequest() } returns classPrepReq

        val id1 = session.setExceptionBreakpoint("com.example.FooException", true, true)
        val id2 = session.setExceptionBreakpoint("com.example.BarException", false, true)

        assertNotEquals(id1, id2)
        verify(exactly = 1) { erm.createClassPrepareRequest() }

        // Removing one should NOT disarm the shared ClassPrepareRequest (other deferred remains).
        assertTrue(session.removeExceptionBreakpoint(id1))
        verify(exactly = 0) { erm.deleteEventRequest(classPrepReq) }

        // Removing the second should NOW disarm it.
        assertTrue(session.removeExceptionBreakpoint(id2))
        verify(exactly = 1) { erm.deleteEventRequest(classPrepReq) }
    }

    @Test
    fun `client side exception filter ignores unrelated exceptions`() {
        val req = mockk<ExceptionRequest>(relaxed = true)
        every { req.getProperty("className") } returns "com.example.ExpectedException"

        val thrownClass = mockk<com.sun.jdi.ClassType>(relaxed = true)
        every { thrownClass.name() } returns "com.example.UnrelatedException"
        every { thrownClass.superclass() } returns null
        every { thrownClass.allInterfaces() } returns emptyList()

        val exceptionObj = mockk<com.sun.jdi.ObjectReference>(relaxed = true)
        every { exceptionObj.referenceType() } returns thrownClass

        val event = mockk<com.sun.jdi.event.ExceptionEvent>(relaxed = true)
        every { event.request() } returns req
        every { event.exception() } returns exceptionObj
        every { event.virtualMachine() } returns vm

        // Use reflection to invoke private handleExceptionEvent method
        val handleMethod = JdiSession::class.java.getDeclaredMethod(
            "handleExceptionEvent",
            com.sun.jdi.event.ExceptionEvent::class.java
        )
        handleMethod.isAccessible = true
        handleMethod.invoke(session, event)

        // Event should have been ignored and VM resumed
        verify { vm.resume() }
        assertEquals(0, session.pollEvents("0").events.size)
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

    // ---------------- First Resume Re-arming ----------------

    @Test
    fun `first resumeAll re-arms all JDI requests`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.suspendCount() } returns 1
        every { vm.allThreads() } returns listOf(thread)

        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        val exReq = mockk<ExceptionRequest>(relaxed = true)

        every { bpReq.isEnabled } returns true
        every { exReq.isEnabled } returns false // should not re-arm if was not enabled

        every { erm.breakpointRequests() } returns listOf(bpReq)
        every { erm.exceptionRequests() } returns listOf(exReq)
        every { erm.accessWatchpointRequests() } returns emptyList()
        every { erm.modificationWatchpointRequests() } returns emptyList()

        session.resumeAll()

        // Verify thread resumed
        verify(exactly = 1) { vm.resume() }

        // Verify re-arming for enabled requests
        verify(exactly = 1) { bpReq.disable() }
        verify(exactly = 1) { bpReq.enable() }

        // Verify NO re-arming for disabled requests
        verify(exactly = 0) { exReq.disable() }
        verify(exactly = 0) { exReq.enable() }
    }

    @Test
    fun `subsequent resumeAll does not re-arm JDI requests`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.suspendCount() } returns 1
        every { vm.allThreads() } returns listOf(thread)

        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        every { bpReq.isEnabled } returns true
        every { erm.breakpointRequests() } returns listOf(bpReq)

        // First resume
        session.resumeAll()
        verify(exactly = 1) { bpReq.disable() }
        verify(exactly = 1) { bpReq.enable() }

        // Second resume
        session.resumeAll()
        // Should not be called again
        verify(exactly = 1) { bpReq.disable() }
        verify(exactly = 1) { bpReq.enable() }
    }

    @Test
    fun `stepExecution RESUME_THREAD re-arms JDI requests on first call`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        var suspendCount = 1
        every { thread.suspendCount() } answers { suspendCount }
        every { thread.resume() } answers { suspendCount-- }
        every { vm.allThreads() } returns listOf(thread)

        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        every { bpReq.isEnabled } returns true
        every { erm.breakpointRequests() } returns listOf(bpReq)

        session.stepExecution("1", StepAction.RESUME_THREAD)

        verify(exactly = 1) { bpReq.disable() }
        verify(exactly = 1) { bpReq.enable() }
    }

    @Test
    fun `re-arming ignores transient JDI exceptions`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.suspendCount() } returns 1
        every { vm.allThreads() } returns listOf(thread)

        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        every { bpReq.isEnabled } returns true
        // Throw an exception during disable to simulate a transient JDI error
        every { bpReq.disable() } throws VMDisconnectedException()

        every { erm.breakpointRequests() } returns listOf(bpReq)

        // Should not throw
        session.resumeAll()

        verify(exactly = 1) { bpReq.disable() }
    }

    // ---------------- Object Caching ----------------

    @Test
    fun `inspectObject on cached nested object succeeds`() {
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"

        val childRef = mockk<ObjectReference>(relaxed = true)
        val childType = mockk<ReferenceType>(relaxed = true)
        every { childRef.uniqueID() } returns 200L
        every { childRef.referenceType() } returns childType
        every { childType.name() } returns "ChildType"

        val field = mockk<com.sun.jdi.Field>(relaxed = true)
        every { field.name() } returns "child"
        every { parentType.allFields() } returns listOf(field)
        every { parentRef.getValues(any()) } returns mapOf(field to childRef)

        // Mock threads so the parent can be found if not cached
        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        // First inspect on parent should cache the child
        val parentResult = session.inspectObject("100", null, maxDepth = 2)
        assertEquals("100", parentResult.objectId)
        assertEquals("ParentType", parentResult.type)
        assertTrue(parentResult.nested!!.containsKey("child"))
        assertEquals("200", parentResult.nested!!["child"]!!.objectId)

        // Now inspect the child independently. It should be found via the cache
        // If not cached, it would fail because findObjectReference only checks thisObject and visibleVariables.
        val childResult = session.inspectObject("200", null, maxDepth = 1)
        assertEquals("200", childResult.objectId)
        assertEquals("ChildType", childResult.type)
    }

    @Test
    fun `inspectObject filters static and synthetic fields by default`() {
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"

        val normalField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { normalField.name() } returns "normal"
        every { normalField.isStatic } returns false
        every { normalField.isSynthetic } returns false

        val staticField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { staticField.name() } returns "staticField"
        every { staticField.isStatic } returns true
        every { staticField.isSynthetic } returns false

        val syntheticField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { syntheticField.name() } returns "shadow\$_klass_"
        every { syntheticField.isStatic } returns false
        every { syntheticField.isSynthetic } returns true

        val shadowField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { shadowField.name() } returns "shadow\$_monitor_"
        every { shadowField.isStatic } returns false
        every { shadowField.isSynthetic } returns false

        every { parentType.allFields() } returns listOf(normalField, staticField, syntheticField, shadowField)

        val valRef = mockk<PrimitiveValue>(relaxed = true)
        every { parentRef.getValues(any()) } answers {
            val requestedFields = firstArg<List<com.sun.jdi.Field>>()
            requestedFields.associateWith { valRef }
        }

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        val resultDefault = session.inspectObject("100", null, maxDepth = 1)
        assertTrue(resultDefault.fields.containsKey("normal"))
        assertFalse(resultDefault.fields.containsKey("staticField"))
        assertFalse(resultDefault.fields.containsKey("shadow\$_klass_"))
        assertFalse(resultDefault.fields.containsKey("shadow\$_monitor_"))

        val resultIncludeAll = session.inspectObject(
            "100",
            null,
            maxDepth = 1,
            includeStatic = true,
            includeInternal = true
        )
        assertTrue(resultIncludeAll.fields.containsKey("normal"))
        assertTrue(resultIncludeAll.fields.containsKey("staticField"))
        assertTrue(resultIncludeAll.fields.containsKey("shadow\$_klass_"))
        assertTrue(resultIncludeAll.fields.containsKey("shadow\$_monitor_"))
    }

    @Test
    fun `inspectObject does not recurse into terminal types`() {
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"

        val stringRef = mockk<ObjectReference>(relaxed = true)
        val stringType = mockk<ReferenceType>(relaxed = true)
        every { stringRef.uniqueID() } returns 200L
        every { stringRef.referenceType() } returns stringType
        every { stringType.name() } returns "java.lang.String"

        val enumRef = mockk<ObjectReference>(relaxed = true)
        val enumType = mockk<com.sun.jdi.ClassType>(relaxed = true)
        every { enumRef.uniqueID() } returns 300L
        every { enumRef.referenceType() } returns enumType
        every { enumType.name() } returns "com.example.MyEnum"
        every { enumType.isEnum } returns true

        val strField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { strField.name() } returns "myString"
        every { strField.isStatic } returns false
        every { strField.isSynthetic } returns false

        val enumField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { enumField.name() } returns "myEnum"
        every { enumField.isStatic } returns false
        every { enumField.isSynthetic } returns false

        every { parentType.allFields() } returns listOf(strField, enumField)
        every { parentRef.getValues(any()) } returns mapOf(strField to stringRef, enumField to enumRef)

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        val result = session.inspectObject("100", null, maxDepth = 2)

        // It should contain the fields, but `nested` should NOT contain them because they are terminal
        assertTrue(result.fields.containsKey("myString"))
        assertTrue(result.fields.containsKey("myEnum"))
        assertNull(result.nested) // no nested recursions occurred
    }

    @Test
    fun `inspectObject guards against cyclic references`() {
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"

        val childRef = mockk<ObjectReference>(relaxed = true)
        val childType = mockk<ReferenceType>(relaxed = true)
        every { childRef.uniqueID() } returns 200L
        every { childRef.referenceType() } returns childType
        every { childType.name() } returns "ChildType"

        val parentField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { parentField.name() } returns "child"
        every { parentField.isStatic } returns false
        every { parentField.isSynthetic } returns false

        val childField = mockk<com.sun.jdi.Field>(relaxed = true)
        every { childField.name() } returns "parent"
        every { childField.isStatic } returns false
        every { childField.isSynthetic } returns false

        every { parentType.allFields() } returns listOf(parentField)
        every { parentRef.getValues(any()) } answers {
            mapOf(parentField to childRef)
        }

        every { childType.allFields() } returns listOf(childField)
        every { childRef.getValues(any()) } answers {
            mapOf(childField to parentRef)
        }

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        val result = session.inspectObject("100", null, maxDepth = 3)

        // Assert parent has child
        assertTrue(result.fields.containsKey("child"))
        assertNotNull(result.nested)
        assertTrue(result.nested!!.containsKey("child"))

        val nestedChild = result.nested!!["child"]!!
        // Assert child has parent field, but NO nested parent map because of cycle guard
        assertTrue(nestedChild.fields.containsKey("parent"))
        assertNull(nestedChild.nested)
    }

    @Test
    fun `inspectObject properly handles maxDepth boundaries`() {
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"

        val childRef = mockk<ObjectReference>(relaxed = true)
        val childType = mockk<ReferenceType>(relaxed = true)
        every { childRef.uniqueID() } returns 200L
        every { childRef.referenceType() } returns childType
        every { childType.name() } returns "ChildType"

        val grandChildRef = mockk<ObjectReference>(relaxed = true)
        val grandChildType = mockk<ReferenceType>(relaxed = true)
        every { grandChildRef.uniqueID() } returns 300L
        every { grandChildRef.referenceType() } returns grandChildType
        every { grandChildType.name() } returns "GrandChildType"

        val field1 = mockk<com.sun.jdi.Field>(relaxed = true)
        every { field1.name() } returns "f1"
        every { field1.isStatic } returns false
        every { field1.isSynthetic } returns false

        val field2 = mockk<com.sun.jdi.Field>(relaxed = true)
        every { field2.name() } returns "f2"
        every { field2.isStatic } returns false
        every { field2.isSynthetic } returns false

        every { parentType.allFields() } returns listOf(field1)
        every { parentRef.getValues(any()) } returns mapOf(field1 to childRef)

        every { childType.allFields() } returns listOf(field2)
        every { childRef.getValues(any()) } returns mapOf(field2 to grandChildRef)

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        // Depth 1: No recursion
        val r1 = session.inspectObject("100", null, maxDepth = 1)
        assertNull(r1.nested)

        // Depth 2: Recurse one level
        val r2 = session.inspectObject("100", null, maxDepth = 2)
        assertNotNull(r2.nested)
        assertTrue(r2.nested!!.containsKey("f1"))
        assertNull(r2.nested!!["f1"]!!.nested) // Child should not recurse into grandchild

        // Depth 3: Recurse two levels
        val r3 = session.inspectObject("100", null, maxDepth = 3)
        assertNotNull(r3.nested)
        assertTrue(r3.nested!!.containsKey("f1"))
        assertNotNull(r3.nested!!["f1"]!!.nested) // Child should have nested map
        assertTrue(r3.nested!!["f1"]!!.nested!!.containsKey("f2")) // Grandchild is there
    }

    @Test
    fun `resumeAll clears object reference cache`() {
        // Setup cache by inspecting a mock object
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"
        every { parentType.allFields() } returns emptyList()
        every { parentRef.getValues(any()) } returns emptyMap()

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        // Cache the object
        session.inspectObject("100", null, maxDepth = 1)

        // Resume should clear the cache
        session.resumeAll()

        // Since cache is cleared, and we change the frame mock to no longer return parentRef,
        // the next inspect should fail.
        every { frame.thisObject() } returns null

        assertThrows<DebugException> {
            session.inspectObject("100", null, maxDepth = 1)
        }
    }

    @Test
    fun `stepExecution clears object reference cache`() {
        // Setup cache
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"
        every { parentType.allFields() } returns emptyList()
        every { parentRef.getValues(any()) } returns emptyMap()

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        // Cache the object
        session.inspectObject("100", null, maxDepth = 1)

        // Step should clear the cache
        session.stepExecution("1", StepAction.STEP_OVER)

        // Change frame to not return object, proving cache is gone
        every { frame.thisObject() } returns null

        assertThrows<DebugException> {
            session.inspectObject("100", null, maxDepth = 1)
        }
    }

    @Test
    fun `detach clears object reference cache`() {
        val parentRef = mockk<ObjectReference>(relaxed = true)
        val parentType = mockk<ReferenceType>(relaxed = true)
        every { parentRef.uniqueID() } returns 100L
        every { parentRef.referenceType() } returns parentType
        every { parentType.name() } returns "ParentType"
        every { parentType.allFields() } returns emptyList()
        every { parentRef.getValues(any()) } returns emptyMap()

        val thread = mockk<ThreadReference>(relaxed = true)
        val frame = mockk<com.sun.jdi.StackFrame>(relaxed = true)
        every { thread.isSuspended } returns true
        every { thread.frames() } returns listOf(frame)
        every { frame.thisObject() } returns parentRef
        every { vm.allThreads() } returns listOf(thread)

        // Cache the object
        session.inspectObject("100", null, maxDepth = 1)

        // Detach should clear the cache
        session.detach()

        every { frame.thisObject() } returns null

        assertThrows<DebugException> {
            session.inspectObject("100", null, maxDepth = 1)
        }
    }

    @Test
    fun `detach clears in-memory EventRequests without sending JDWP delete requests`() {
        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        val stepReq = mockk<StepRequest>(relaxed = true)
        every { erm.breakpointRequests() } returns listOf(bpReq)
        every { erm.stepRequests() } returns listOf(stepReq)

        session.detach()

        verify(exactly = 0) { erm.deleteEventRequest(bpReq) }
        verify(exactly = 0) { erm.deleteEventRequest(stepReq) }
        verify { vm.dispose() }
    }

    @Test
    fun `VMDisconnectedException triggers disconnect event and cleans up session`() {
        val customVm = mockk<VirtualMachine>(relaxed = true)
        val eventQueue = mockk<com.sun.jdi.event.EventQueue>()
        every { customVm.eventQueue() } returns eventQueue
        every { eventQueue.remove(any()) } throws VMDisconnectedException()

        val customErm = mockk<EventRequestManager>(relaxed = true)
        every { customVm.eventRequestManager() } returns customErm

        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        every { customErm.breakpointRequests() } returns listOf(bpReq)

        val customSession = JdiSession(
            sessionId = "sess_custom",
            appId = "com.test.app",
            localPort = 8080,
            vm = customVm,
            adbManager = adbManager
        )

        // Verify the background event loop caught the exception and processed the disconnect.
        // Using timeout since the event loop runs on a separate thread.
        verify(timeout = 3000) { adbManager.removePortForward(8080) }
        verify(exactly = 0) { customErm.deleteEventRequest(any()) }

        assertFalse(customSession.isAlive())
    }

    @Test
    fun `detach is idempotent and does not clear twice`() {
        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        every { erm.breakpointRequests() } returns listOf(bpReq)

        session.detach()
        session.detach() // Second call

        // vm.dispose and removePortForward should only be called once because of getAndSet(false)
        verify(exactly = 1) { vm.dispose() }
        verify(exactly = 1) { adbManager.removePortForward(8080) }
    }

    @Test
    fun `detach completes successfully even if clearAllEventRequests throws`() {
        every { vm.eventRequestManager() } throws RuntimeException("Erm failure")

        // Should not throw
        session.detach()

        assertFalse(session.isAlive())
    }

    @Test
    fun `detach after unexpected disconnect still clears debug app and forward`() {
        val isolatedAdbManager = mockk<AdbManager>(relaxed = true)
        val isolatedVm = mockk<VirtualMachine>(relaxed = true)
        val customErm = mockk<EventRequestManager>(relaxed = true)
        every { isolatedVm.eventRequestManager() } returns customErm

        // Simulating the disconnect through the thread exception BEFORE thread starts
        val eventQueue = mockk<com.sun.jdi.event.EventQueue>()
        every { isolatedVm.eventQueue() } returns eventQueue
        every { eventQueue.remove(any()) } throws VMDisconnectedException()

        val sessionWithClear = JdiSession(
            sessionId = "sess_clear",
            appId = "com.test.app",
            localPort = 8080,
            vm = isolatedVm,
            adbManager = isolatedAdbManager,
            clearDebugAppOnDetach = true
        )

        // Wait for background event loop to catch it and perform cleanup EXACTLY ONCE
        verify(timeout = 2000, exactly = 1) { isolatedAdbManager.removePortForward(8080) }
        verify(timeout = 2000, exactly = 1) { isolatedAdbManager.clearDebugApp() }

        // Now explicit detach should be a complete no-op (idempotent)
        sessionWithClear.detach()

        // Assert that they were still only called exactly once
        verify(exactly = 1) { isolatedAdbManager.removePortForward(8080) }
        verify(exactly = 1) { isolatedAdbManager.clearDebugApp() }
    }

    @Test
    fun `shutdown semantics for DISCONNECT event`() {
        // 1. Explicit detach -> NO disconnect event
        session.detach()

        val events1 = session.pollEvents("0")
        assertFalse(events1.events.any { it.eventType == EventType.DISCONNECT })

        // 2. Unexpected disconnect -> DISCONNECT event
        val isolatedAdbManager = mockk<AdbManager>(relaxed = true)
        val isolatedVm = mockk<VirtualMachine>(relaxed = true)
        val customErm = mockk<EventRequestManager>(relaxed = true)
        every { isolatedVm.eventRequestManager() } returns customErm

        val eventQueue = mockk<com.sun.jdi.event.EventQueue>()
        every { isolatedVm.eventQueue() } returns eventQueue
        every { eventQueue.remove(any()) } throws VMDisconnectedException()

        val sessionUnexpected = JdiSession("sess_unexp", "app", 8080, isolatedVm, isolatedAdbManager)

        // Wait for shutdown to complete via adbManager mock
        verify(timeout = 2000) { isolatedAdbManager.removePortForward(8080) }

        val events2 = sessionUnexpected.pollEvents("0")
        assertTrue(events2.events.any { it.eventType == EventType.DISCONNECT })
    }

    @Test
    fun `deleteAllEventRequests clears tracked maps`() {
        // Add a real breakpoint which populates maps
        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        every { location.lineNumber() } returns 10
        val method = mockk<Method>(relaxed = true)
        val declType = mockk<ReferenceType>(relaxed = true)
        every { declType.name() } returns "com.test.Target"
        every { declType.sourceName() } returns "Target.kt"
        every { method.declaringType() } returns declType
        every { location.method() } returns method
        every { declType.locationsOfLine(any()) } returns listOf(location)
        every { erm.createBreakpointRequest(any()) } returns bpReq
        every { vm.classesByName("com.test.Target") } returns listOf(declType)

        session.setBreakpoint("Target.kt", 10, "com.test.Target")

        val pointsBefore = session.getPoints()
        assertEquals(1, pointsBefore.breakpoints.size)

        // Detach should call deleteAllEventRequests
        session.detach()

        val pointsAfter = session.getPoints()
        assertEquals(0, pointsAfter.breakpoints.size)
    }

    @Test
    fun `stepExecution STEP_INTO configures SUSPEND_ALL and resumes vm`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { vm.allThreads() } returns listOf(thread)

        val stepReqNew = mockk<StepRequest>(relaxed = true)
        every { erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO) } returns stepReqNew

        session.stepExecution("1", dev.shreyaspatil.debroid.models.StepAction.STEP_INTO)

        verify { stepReqNew.setSuspendPolicy(EventRequest.SUSPEND_ALL) }
        verify { stepReqNew.enable() }
        verify { vm.resume() }
    }

    @Test
    fun `stepExecution STEP_OUT configures SUSPEND_ALL and resumes vm`() {
        val thread = mockk<ThreadReference>(relaxed = true)
        every { thread.uniqueID() } returns 1L
        every { vm.allThreads() } returns listOf(thread)

        val stepReqNew = mockk<StepRequest>(relaxed = true)
        every { erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT) } returns stepReqNew

        session.stepExecution("1", dev.shreyaspatil.debroid.models.StepAction.STEP_OUT)

        verify { stepReqNew.setSuspendPolicy(EventRequest.SUSPEND_ALL) }
        verify { stepReqNew.enable() }
        verify { vm.resume() }
    }

    @Test
    fun `detach resumes all suspended threads and VM`() {
        val suspendedThread1 = mockk<ThreadReference>(relaxed = true)
        val suspendedThread2 = mockk<ThreadReference>(relaxed = true)
        val runningThread = mockk<ThreadReference>(relaxed = true)

        every { suspendedThread1.isSuspended } returnsMany listOf(true, true, false)
        every { suspendedThread1.suspendCount() } returnsMany listOf(2, 1, 0)
        every { suspendedThread2.isSuspended } returnsMany listOf(true, false)
        every { suspendedThread2.suspendCount() } returnsMany listOf(1, 0)
        every { runningThread.isSuspended } returns false
        every { runningThread.suspendCount() } returns 0
        every { vm.allThreads() } returns listOf(suspendedThread1, suspendedThread2, runningThread)

        session.detach()

        verify(atLeast = 1) { suspendedThread1.resume() }
        verify(atLeast = 1) { suspendedThread2.resume() }
        verify(exactly = 0) { runningThread.resume() }
        verify(atLeast = 1) { vm.resume() }
        verify { vm.dispose() }
    }

    @Test
    fun `detach does not delete or disable event requests over JDWP`() {
        val bpReq = mockk<BreakpointRequest>(relaxed = true)
        val stepReq = mockk<StepRequest>(relaxed = true)
        every { erm.breakpointRequests() } returns listOf(bpReq)
        every { erm.stepRequests() } returns listOf(stepReq)

        session.detach()

        verify(exactly = 0) { bpReq.disable() }
        verify(exactly = 0) { erm.deleteEventRequest(bpReq) }
        verify(exactly = 0) { stepReq.disable() }
        verify(exactly = 0) { erm.deleteEventRequest(stepReq) }
    }

    @Test
    fun `detach completes even if vm dispose or teardown hangs`() {
        val hangVm = mockk<VirtualMachine>(relaxed = true)
        val hangErm = mockk<EventRequestManager>(relaxed = true)
        every { hangVm.eventRequestManager() } returns hangErm
        every { hangVm.dispose() } answers {
            runCatching { Thread.sleep(3_000) }
        }

        val hangSession = JdiSession(
            sessionId = "sess_hang",
            appId = "com.test.app",
            localPort = 8081,
            vm = hangVm,
            adbManager = adbManager
        )

        val startTime = System.currentTimeMillis()
        hangSession.detach()
        val duration = System.currentTimeMillis() - startTime

        // Should complete within ~2-4 seconds due to timeout and not hang for 10s
        assertTrue(duration < 5_000, "Detach took too long: ${duration}ms")
        verify { adbManager.removePortForward(8081) }
        assertFalse(hangSession.isAlive())
    }
}
