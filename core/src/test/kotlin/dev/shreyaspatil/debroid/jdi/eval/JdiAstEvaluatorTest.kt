package dev.shreyaspatil.debroid.jdi.eval

import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.InterfaceType
import com.sun.jdi.LocalVariable
import com.sun.jdi.LongValue
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.jdi.JdiExpressionEvaluator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class JdiAstEvaluatorTest {

    private lateinit var vm: VirtualMachine
    private lateinit var frame: StackFrame
    private lateinit var thread: ThreadReference

    @BeforeEach
    fun setUp() {
        vm = mockk(relaxed = true)
        frame = mockk(relaxed = true)
        thread = mockk(relaxed = true)

        every { frame.thread() } returns thread
        every { frame.thisObject() } returns null
        every { frame.visibleVariables() } returns emptyList()

        // Mock primitive mirrors
        every { vm.mirrorOf(any<Boolean>()) } answers {
            val v = firstArg<Boolean>()
            mockk<BooleanValue> {
                every { value() } returns v
                every { booleanValue() } returns v
            }
        }
        every { vm.mirrorOf(any<Int>()) } answers {
            val v = firstArg<Int>()
            mockk<IntegerValue> {
                every { value() } returns v
                every { intValue() } returns v
                every { doubleValue() } returns v.toDouble()
                every { floatValue() } returns v.toFloat()
                every { longValue() } returns v.toLong()
            }
        }
        every { vm.mirrorOf(any<Long>()) } answers {
            val v = firstArg<Long>()
            mockk<LongValue> {
                every { value() } returns v
                every { longValue() } returns v
                every { doubleValue() } returns v.toDouble()
                every { floatValue() } returns v.toFloat()
                every { intValue() } returns v.toInt()
            }
        }
        every { vm.mirrorOf(any<Float>()) } answers {
            val v = firstArg<Float>()
            mockk<FloatValue> {
                every { value() } returns v
                every { floatValue() } returns v
                every { doubleValue() } returns v.toDouble()
                every { longValue() } returns v.toLong()
                every { intValue() } returns v.toInt()
            }
        }
        every { vm.mirrorOf(any<Double>()) } answers {
            val v = firstArg<Double>()
            mockk<DoubleValue> {
                every { value() } returns v
                every { doubleValue() } returns v
                every { floatValue() } returns v.toFloat()
                every { longValue() } returns v.toLong()
                every { intValue() } returns v.toInt()
            }
        }
        every { vm.mirrorOf(any<String>()) } answers {
            val v = firstArg<String>()
            mockk<StringReference> {
                every { value() } returns v
            }
        }
    }

    @Test
    fun `evaluates Issue #72 compound boolean expression successfully`() {
        // Reproduction of: amount >= 600.0 && isExpress
        val amountVar = mockk<LocalVariable> { every { name() } returns "amount" }
        val isExpressVar = mockk<LocalVariable> { every { name() } returns "isExpress" }

        val amountVal = mockk<DoubleValue> {
            every { value() } returns 650.0
            every { doubleValue() } returns 650.0
        }
        val isExpressVal = mockk<BooleanValue> {
            every { value() } returns true
            every { booleanValue() } returns true
        }

        every { frame.visibleVariables() } returns listOf(amountVar, isExpressVar)
        every { frame.getValue(amountVar) } returns amountVal
        every { frame.getValue(isExpressVar) } returns isExpressVal

        val result = JdiExpressionEvaluator.evaluate("amount >= 600.0 && isExpress", vm, frame)
        assertTrue(result is BooleanValue)
        assertTrue((result as BooleanValue).value())
    }

    @Test
    fun `short-circuiting prevents evaluation of right side when left is false`() {
        // false && (1 / 0 == 0) should evaluate to false without dividing by zero
        val result = JdiExpressionEvaluator.evaluate("false && (1 / 0 == 0)", vm, frame)
        assertTrue(result is BooleanValue)
        assertEquals(false, (result as BooleanValue).value())
    }

    @Test
    fun `short-circuiting prevents evaluation of right side when left is true`() {
        // true || (1 / 0 == 0) should evaluate to true without dividing by zero
        val result = JdiExpressionEvaluator.evaluate("true || (1 / 0 == 0)", vm, frame)
        assertTrue(result is BooleanValue)
        assertEquals(true, (result as BooleanValue).value())
    }

    @Test
    fun `evaluates equality between boolean and non-boolean primitive cleanly`() {
        val flagVar = mockk<LocalVariable> { every { name() } returns "flag" }
        val flagVal = mockk<BooleanValue> {
            every { value() } returns true
            every { booleanValue() } returns true
        }

        every { frame.visibleVariables() } returns listOf(flagVar)
        every { frame.getValue(flagVar) } returns flagVal

        val eqResult = JdiExpressionEvaluator.evaluate("flag == 1", vm, frame)
        assertTrue(eqResult is BooleanValue)
        assertFalse((eqResult as BooleanValue).value())

        val neqResult = JdiExpressionEvaluator.evaluate("flag != 1", vm, frame)
        assertTrue(neqResult is BooleanValue)
        assertTrue((neqResult as BooleanValue).value())
    }

    @Test
    fun `evaluates relational operators with mixed primitive types`() {
        val intVal = mockk<IntegerValue> {
            every { value() } returns 10
            every { intValue() } returns 10
            every { doubleValue() } returns 10.0
            every { longValue() } returns 10L
        }
        val xVar = mockk<LocalVariable> { every { name() } returns "x" }
        every { frame.visibleVariables() } returns listOf(xVar)
        every { frame.getValue(xVar) } returns intVal

        val ltResult = JdiExpressionEvaluator.evaluate("x < 20.5", vm, frame)
        assertTrue(ltResult is BooleanValue)
        assertTrue((ltResult as BooleanValue).value())

        val gtResult = JdiExpressionEvaluator.evaluate("x > 20.5", vm, frame)
        assertTrue(gtResult is BooleanValue)
        assertFalse((gtResult as BooleanValue).value())
    }

    @Test
    fun `evaluates bitwise and shift operators`() {
        val andResult = JdiExpressionEvaluator.evaluate("6 & 3", vm, frame)
        assertTrue(andResult is IntegerValue)
        assertEquals(2, (andResult as IntegerValue).value())

        val orResult = JdiExpressionEvaluator.evaluate("6 | 3", vm, frame)
        assertTrue(orResult is IntegerValue)
        assertEquals(7, (orResult as IntegerValue).value())

        val xorResult = JdiExpressionEvaluator.evaluate("6 ^ 3", vm, frame)
        assertTrue(xorResult is IntegerValue)
        assertEquals(5, (xorResult as IntegerValue).value())

        val shlResult = JdiExpressionEvaluator.evaluate("1 << 3", vm, frame)
        assertTrue(shlResult is IntegerValue)
        assertEquals(8, (shlResult as IntegerValue).value())

        val shrResult = JdiExpressionEvaluator.evaluate("16 >> 2", vm, frame)
        assertTrue(shrResult is IntegerValue)
        assertEquals(4, (shrResult as IntegerValue).value())
    }

    @Test
    fun `evaluates type checks with is and !is`() {
        val objVar = mockk<LocalVariable> { every { name() } returns "obj" }
        val objRef = mockk<ObjectReference>()
        val classType = mockk<ClassType>()
        val interfaceType = mockk<InterfaceType>()

        every { objRef.referenceType() } returns classType
        every { classType.name() } returns "com.example.Order"
        every { classType.superclass() } returns null
        every { classType.allInterfaces() } returns listOf(interfaceType)
        every { interfaceType.name() } returns "java.io.Serializable"

        every { frame.visibleVariables() } returns listOf(objVar)
        every { frame.getValue(objVar) } returns objRef

        val isOrder = JdiExpressionEvaluator.evaluate("obj is com.example.Order", vm, frame)
        assertTrue(isOrder is BooleanValue)
        assertTrue((isOrder as BooleanValue).value())

        val isSerializable = JdiExpressionEvaluator.evaluate("obj is java.io.Serializable", vm, frame)
        assertTrue(isSerializable is BooleanValue)
        assertTrue((isSerializable as BooleanValue).value())

        val notIsString = JdiExpressionEvaluator.evaluate("obj !is String", vm, frame)
        assertTrue(notIsString is BooleanValue)
        assertTrue((notIsString as BooleanValue).value())
    }

    @Test
    fun `evaluates unary logical NOT`() {
        val isValidVar = mockk<LocalVariable> { every { name() } returns "isValid" }
        val isValidVal = mockk<BooleanValue> {
            every { value() } returns true
            every { booleanValue() } returns true
        }

        every { frame.visibleVariables() } returns listOf(isValidVar)
        every { frame.getValue(isValidVar) } returns isValidVal

        val result = JdiExpressionEvaluator.evaluate("!isValid", vm, frame)
        assertTrue(result is BooleanValue)
        assertEquals(false, (result as BooleanValue).value())
    }

    @Test
    fun `evaluates Kotlin property getter access on object`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        val userObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType>()
        val getNameMethod = mockk<Method> {
            every { argumentTypeNames() } returns emptyList()
        }
        val nameVal = mockk<StringReference> {
            every { value() } returns "Alice"
        }

        every { userObj.referenceType() } returns refType
        every { refType.fieldByName("name") } returns null
        every { refType.methodsByName("getName") } returns listOf(getNameMethod)
        every {
            userObj.invokeMethod(thread, getNameMethod, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED)
        } returns nameVal

        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns userObj

        val result = JdiExpressionEvaluator.evaluate("user.name", vm, frame)
        assertTrue(result is StringReference)
        assertEquals("Alice", (result as StringReference).value())
    }

    @Test
    fun `evaluates Kotlin safe navigation operator on null object`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns null

        val result = JdiExpressionEvaluator.evaluate("user?.name", vm, frame)
        assertNull(result)
    }

    @Test
    fun `evaluates Elvis operator with null fallback`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns null

        val result = JdiExpressionEvaluator.evaluate("user?.name ?: \"Guest\"", vm, frame)
        assertTrue(result is StringReference)
        assertEquals("Guest", (result as StringReference).value())
    }

    @Test
    fun `evaluates string concatenation and arithmetic`() {
        val result = JdiExpressionEvaluator.evaluate("\"Total: \" + (10 + 20)", vm, frame)
        assertTrue(result is StringReference)
        assertEquals("Total: 30", (result as StringReference).value())
    }

    @Test
    fun `evaluates array access`() {
        val itemsVar = mockk<LocalVariable> { every { name() } returns "items" }
        val arrayRef = mockk<ArrayReference>()
        val elemVal = mockk<StringReference> { every { value() } returns "item_1" }

        every { arrayRef.length() } returns 3
        every { arrayRef.getValue(1) } returns elemVal
        every { frame.visibleVariables() } returns listOf(itemsVar)
        every { frame.getValue(itemsVar) } returns arrayRef

        val result = JdiExpressionEvaluator.evaluate("items[1]", vm, frame)
        assertTrue(result is StringReference)
        assertEquals("item_1", (result as StringReference).value())
    }

    @Test
    fun `throws DebugException when indexing out of bounds`() {
        val itemsVar = mockk<LocalVariable> { every { name() } returns "items" }
        val arrayRef = mockk<ArrayReference>()

        every { arrayRef.length() } returns 2
        every { frame.visibleVariables() } returns listOf(itemsVar)
        every { frame.getValue(itemsVar) } returns arrayRef

        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("items[5]", vm, frame)
        }
    }

    @Test
    fun `evaluates ternary operator`() {
        val result = JdiExpressionEvaluator.evaluate("true ? 100 : 200", vm, frame)
        assertTrue(result is IntegerValue)
        assertEquals(100, (result as IntegerValue).value())
    }

    @Test
    fun `throws on division by zero`() {
        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("10 / 0", vm, frame)
        }
    }
}
