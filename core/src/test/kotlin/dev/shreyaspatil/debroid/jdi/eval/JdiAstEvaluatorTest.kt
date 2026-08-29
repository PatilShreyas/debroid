package dev.shreyaspatil.debroid.jdi.eval

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.ArrayType
import com.sun.jdi.BooleanValue
import com.sun.jdi.ClassObjectReference
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.InterfaceType
import com.sun.jdi.InvocationException
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

@Suppress("LargeClass", "LongMethod")
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
        every { thread.frame(0) } returns frame
        every { thread.isSuspended } returns true
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
            val stringType = mockk<ClassType> {
                every { name() } returns "java.lang.String"
                every { superclass() } returns null
                every { allInterfaces() } returns emptyList()
            }
            mockk<StringReference> {
                every { value() } returns v
                every { referenceType() } returns stringType
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

        val ushrResult = JdiExpressionEvaluator.evaluate("16 >>> 2", vm, frame)
        assertTrue(ushrResult is IntegerValue)
        assertEquals(4, (ushrResult as IntegerValue).value())

        // Bitwise operations on booleans
        val boolAnd = JdiExpressionEvaluator.evaluate("true & false", vm, frame)
        assertTrue(boolAnd is BooleanValue)
        assertFalse((boolAnd as BooleanValue).value())

        val boolOr = JdiExpressionEvaluator.evaluate("true | false", vm, frame)
        assertTrue(boolOr is BooleanValue)
        assertTrue((boolOr as BooleanValue).value())

        val boolXor = JdiExpressionEvaluator.evaluate("true ^ false", vm, frame)
        assertTrue(boolXor is BooleanValue)
        assertTrue((boolXor as BooleanValue).value())
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

        // null is Type should evaluate to false
        val nullIsString = JdiExpressionEvaluator.evaluate("null is String", vm, frame)
        assertTrue(nullIsString is BooleanValue)
        assertFalse((nullIsString as BooleanValue).value())

        // null !is Type should evaluate to true
        val nullNotIsString = JdiExpressionEvaluator.evaluate("null !is String", vm, frame)
        assertTrue(nullNotIsString is BooleanValue)
        assertTrue((nullNotIsString as BooleanValue).value())
    }

    @Test
    fun `evaluates unary operations`() {
        val isValidVar = mockk<LocalVariable> { every { name() } returns "isValid" }
        val isValidVal = mockk<BooleanValue> {
            every { value() } returns true
            every { booleanValue() } returns true
        }

        every { frame.visibleVariables() } returns listOf(isValidVar)
        every { frame.getValue(isValidVar) } returns isValidVal

        val notResult = JdiExpressionEvaluator.evaluate("!isValid", vm, frame)
        assertTrue(notResult is BooleanValue)
        assertFalse((notResult as BooleanValue).value())

        val negResult = JdiExpressionEvaluator.evaluate("-42", vm, frame)
        assertTrue(negResult is IntegerValue)
        assertEquals(-42, (negResult as IntegerValue).value())

        val bitNotResult = JdiExpressionEvaluator.evaluate("~1", vm, frame)
        assertTrue(bitNotResult is IntegerValue)
        assertEquals(-2, (bitNotResult as IntegerValue).value())

        val plusResult = JdiExpressionEvaluator.evaluate("+50", vm, frame)
        assertTrue(plusResult is IntegerValue)
        assertEquals(50, (plusResult as IntegerValue).value())

        val castNegResult = JdiExpressionEvaluator.evaluate("-42 as Double", vm, frame)
        assertTrue(castNegResult is DoubleValue)
        assertEquals(-42.0, (castNegResult as DoubleValue).value())

        val castNotResult = JdiExpressionEvaluator.evaluate("!isValid as Boolean", vm, frame)
        assertTrue(castNotResult is BooleanValue)
        assertFalse((castNotResult as BooleanValue).value())
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
    fun `evaluates Kotlin boolean property getter using is prefix`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        val userObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType>()
        val isActiveMethod = mockk<Method> {
            every { argumentTypeNames() } returns emptyList()
        }
        val activeVal = mockk<BooleanValue> {
            every { value() } returns true
            every { booleanValue() } returns true
        }

        every { userObj.referenceType() } returns refType
        every { refType.fieldByName("active") } returns null
        every { refType.methodsByName("getActive") } returns emptyList()
        every { refType.methodsByName("isActive") } returns listOf(isActiveMethod)
        every {
            userObj.invokeMethod(thread, isActiveMethod, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED)
        } returns activeVal

        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns userObj

        val result = JdiExpressionEvaluator.evaluate("user.active", vm, frame)
        assertTrue(result is BooleanValue)
        assertTrue((result as BooleanValue).value())
    }

    @Test
    fun `evaluates this and implicit this scopes`() {
        val thisObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType>()
        val field = mockk<Field>()
        val countVal = mockk<IntegerValue> {
            every { value() } returns 42
            every { intValue() } returns 42
        }

        every { frame.thisObject() } returns thisObj
        every { thisObj.referenceType() } returns refType
        every { refType.fieldByName("count") } returns field
        every { thisObj.getValue(field) } returns countVal

        // Explicit `this.count`
        val thisResult = JdiExpressionEvaluator.evaluate("this.count", vm, frame)
        assertTrue(thisResult is IntegerValue)
        assertEquals(42, (thisResult as IntegerValue).value())

        // Implicit `count` resolution on `this` when absent in local variables
        val implicitResult = JdiExpressionEvaluator.evaluate("count", vm, frame)
        assertTrue(implicitResult is IntegerValue)
        assertEquals(42, (implicitResult as IntegerValue).value())
    }

    @Test
    fun `handles AbsentInformationException when looking up local variables`() {
        val thisObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType>()
        val field = mockk<Field>()
        val countVal = mockk<IntegerValue> {
            every { value() } returns 99
            every { intValue() } returns 99
        }

        every { frame.visibleVariables() } throws AbsentInformationException()
        every { frame.thisObject() } returns thisObj
        every { thisObj.referenceType() } returns refType
        every { refType.fieldByName("count") } returns field
        every { thisObj.getValue(field) } returns countVal

        val result = JdiExpressionEvaluator.evaluate("count", vm, frame)
        assertTrue(result is IntegerValue)
        assertEquals(99, (result as IntegerValue).value())
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
    fun `throws NullPointerException when accessing property on null without safe navigation`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns null

        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("user.name", vm, frame)
        }
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

        val nullConcat = JdiExpressionEvaluator.evaluate("\"Val: \" + null", vm, frame)
        assertTrue(nullConcat is StringReference)
        assertEquals("Val: null", (nullConcat as StringReference).value())
    }

    @Test
    fun `evaluates array and string length properties`() {
        val arrayRef = mockk<ArrayReference>()
        every { arrayRef.length() } returns 5

        val strRef = mockk<StringReference>()
        every { strRef.value() } returns "Hello"

        val arrVar = mockk<LocalVariable> { every { name() } returns "arr" }
        val strVar = mockk<LocalVariable> { every { name() } returns "str" }

        every { frame.visibleVariables() } returns listOf(arrVar, strVar)
        every { frame.getValue(arrVar) } returns arrayRef
        every { frame.getValue(strVar) } returns strRef

        val arrLen = JdiExpressionEvaluator.evaluate("arr.length", vm, frame)
        assertTrue(arrLen is IntegerValue)
        assertEquals(5, (arrLen as IntegerValue).value())

        val strLen = JdiExpressionEvaluator.evaluate("str.length", vm, frame)
        assertTrue(strLen is IntegerValue)
        assertEquals(5, (strLen as IntegerValue).value())
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
    fun `throws DebugException when indexing out of bounds or invalid array index`() {
        val itemsVar = mockk<LocalVariable> { every { name() } returns "items" }
        val arrayRef = mockk<ArrayReference>()

        every { arrayRef.length() } returns 2
        every { frame.visibleVariables() } returns listOf(itemsVar)
        every { frame.getValue(itemsVar) } returns arrayRef

        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("items[5]", vm, frame)
        }

        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("items[-1]", vm, frame)
        }
    }

    @Test
    fun `evaluates method invocation on object`() {
        val calcObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType>()
        val method = mockk<Method> {
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val returnVal = mockk<IntegerValue> {
            every { value() } returns 15
            every { intValue() } returns 15
        }

        every { calcObj.referenceType() } returns refType
        every { refType.methodsByName("add") } returns listOf(method)
        every {
            calcObj.invokeMethod(thread, method, any(), ObjectReference.INVOKE_SINGLE_THREADED)
        } returns returnVal

        val calcVar = mockk<LocalVariable> { every { name() } returns "calc" }
        every { frame.visibleVariables() } returns listOf(calcVar)
        every { frame.getValue(calcVar) } returns calcObj

        val result = JdiExpressionEvaluator.evaluate("calc.add(5, 10)", vm, frame)
        assertTrue(result is IntegerValue)
        assertEquals(15, (result as IntegerValue).value())
    }

    @Test
    fun `evaluates ternary operator`() {
        val trueResult = JdiExpressionEvaluator.evaluate("true ? 100 : 200", vm, frame)
        assertTrue(trueResult is IntegerValue)
        assertEquals(100, (trueResult as IntegerValue).value())

        val falseResult = JdiExpressionEvaluator.evaluate("false ? 100 : 200", vm, frame)
        assertTrue(falseResult is IntegerValue)
        assertEquals(200, (falseResult as IntegerValue).value())
    }

    @Test
    fun `throws on division by zero`() {
        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("10 / 0", vm, frame)
        }
        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("10L % 0L", vm, frame)
        }
    }

    @Test
    fun `throws on unresolved identifier or invalid operation`() {
        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("unknownVariable", vm, frame)
        }
        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("!123", vm, frame)
        }
        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("~\"hello\"", vm, frame)
        }
    }

    @Test
    fun `evaluates successful unsafe and safe type casts on object reference`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        val userObj = mockk<ObjectReference>()
        val adminType = mockk<ClassType>()
        val accountInterface = mockk<InterfaceType>()

        every { userObj.referenceType() } returns adminType
        every { adminType.name() } returns "com.example.AdminAccount"
        every { adminType.superclass() } returns null
        every { adminType.allInterfaces() } returns listOf(accountInterface)
        every { accountInterface.name() } returns "com.example.Account"

        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns userObj

        // Cast to exact class
        val castAdmin = JdiExpressionEvaluator.evaluate("user as com.example.AdminAccount", vm, frame)
        assertEquals(userObj, castAdmin)

        // Cast to simple class name
        val castSimpleAdmin = JdiExpressionEvaluator.evaluate("user as AdminAccount", vm, frame)
        assertEquals(userObj, castSimpleAdmin)

        // Safe cast to implemented interface
        val castAccount = JdiExpressionEvaluator.evaluate("user as? com.example.Account", vm, frame)
        assertEquals(userObj, castAccount)

        // Safe cast to simple interface name
        val castSimpleAccount = JdiExpressionEvaluator.evaluate("user as? Account", vm, frame)
        assertEquals(userObj, castSimpleAccount)

        // Cast to Any
        val castAny = JdiExpressionEvaluator.evaluate("user as Any", vm, frame)
        assertEquals(userObj, castAny)
    }

    @Test
    fun `evaluates member access on cast object`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        val userObj = mockk<ObjectReference>()
        val adminType = mockk<ClassType>()
        val permsField = mockk<Field>()
        val permsVal = mockk<StringReference> { every { value() } returns "ALL" }

        every { userObj.referenceType() } returns adminType
        every { adminType.name() } returns "com.example.Admin"
        every { adminType.superclass() } returns null
        every { adminType.allInterfaces() } returns emptyList()
        every { adminType.fieldByName("permissions") } returns permsField
        every { userObj.getValue(permsField) } returns permsVal

        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns userObj

        val result = JdiExpressionEvaluator.evaluate("(user as Admin).permissions", vm, frame)
        assertEquals(permsVal, result)
    }

    @Test
    fun `evaluates failed unsafe cast throws ClassCastException`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        val userObj = mockk<ObjectReference>()
        val guestType = mockk<ClassType>()

        every { userObj.referenceType() } returns guestType
        every { guestType.name() } returns "com.example.GuestAccount"
        every { guestType.superclass() } returns null
        every { guestType.allInterfaces() } returns emptyList()

        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns userObj

        val ex = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("user as AdminAccount", vm, frame)
        }
        assertTrue(ex.message?.contains("ClassCastException") == true)
        assertTrue(ex.message?.contains("com.example.GuestAccount cannot be cast to AdminAccount") == true)
    }

    @Test
    fun `evaluates failed safe cast returns null and works with elvis`() {
        val userVar = mockk<LocalVariable> { every { name() } returns "user" }
        val userObj = mockk<ObjectReference>()
        val guestType = mockk<ClassType>()

        every { userObj.referenceType() } returns guestType
        every { guestType.name() } returns "com.example.GuestAccount"
        every { guestType.superclass() } returns null
        every { guestType.allInterfaces() } returns emptyList()

        every { frame.visibleVariables() } returns listOf(userVar)
        every { frame.getValue(userVar) } returns userObj

        val safeResult = JdiExpressionEvaluator.evaluate("user as? AdminAccount", vm, frame)
        assertNull(safeResult)

        val elvisResult = JdiExpressionEvaluator.evaluate("(user as? AdminAccount) ?: \"fallback\"", vm, frame)
        assertTrue(elvisResult is StringReference)
        assertEquals("fallback", (elvisResult as StringReference).value())
    }

    @Test
    fun `evaluates null casting rules`() {
        // Safe cast of null returns null
        val safeNull = JdiExpressionEvaluator.evaluate("null as? String", vm, frame)
        assertNull(safeNull)

        // Nullable type cast of null returns null
        val nullableCast = JdiExpressionEvaluator.evaluate("null as String?", vm, frame)
        assertNull(nullableCast)

        // Unsafe cast of null to non-null type throws NullPointerException
        val ex = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("null as String", vm, frame)
        }
        assertTrue(ex.message?.contains("NullPointerException") == true)
    }

    @Test
    fun `evaluates primitive numeric and string type casting`() {
        val intResult = JdiExpressionEvaluator.evaluate("100L as Int", vm, frame)
        assertTrue(intResult is IntegerValue)
        assertEquals(100, (intResult as IntegerValue).value())

        val longResult = JdiExpressionEvaluator.evaluate("42 as Long", vm, frame)
        assertTrue(longResult is LongValue)
        assertEquals(42L, (longResult as LongValue).value())

        val doubleResult = JdiExpressionEvaluator.evaluate("50 as Double", vm, frame)
        assertTrue(doubleResult is DoubleValue)
        assertEquals(50.0, (doubleResult as DoubleValue).value())

        val floatResult = JdiExpressionEvaluator.evaluate("25.5 as Float", vm, frame)
        assertTrue(floatResult is FloatValue)
        assertEquals(25.5f, (floatResult as FloatValue).value())

        val stringResult = JdiExpressionEvaluator.evaluate("123 as String", vm, frame)
        assertTrue(stringResult is StringReference)
        assertEquals("123", (stringResult as StringReference).value())
    }

    @Test
    fun `evaluates cast on boxed primitive object reference`() {
        val numVar = mockk<LocalVariable> { every { name() } returns "num" }
        val boxedIntObj = mockk<ObjectReference>()
        val integerType = mockk<ClassType>()

        every { boxedIntObj.referenceType() } returns integerType
        every { integerType.name() } returns "java.lang.Integer"
        every { integerType.superclass() } returns null
        every { integerType.allInterfaces() } returns emptyList()

        every { frame.visibleVariables() } returns listOf(numVar)
        every { frame.getValue(numVar) } returns boxedIntObj

        val castInt = JdiExpressionEvaluator.evaluate("num as Int", vm, frame)
        assertEquals(boxedIntObj, castInt)

        val castBoxedInteger = JdiExpressionEvaluator.evaluate("num as java.lang.Integer", vm, frame)
        assertEquals(boxedIntObj, castBoxedInteger)

        val castAny = JdiExpressionEvaluator.evaluate("num as Any", vm, frame)
        assertEquals(boxedIntObj, castAny)
    }

    @Test
    fun `evaluates primitive array type casting`() {
        val arrVar = mockk<LocalVariable> { every { name() } returns "arr" }
        val arrayObj = mockk<ArrayReference>()
        val arrayType = mockk<ArrayType>()

        every { arrayObj.referenceType() } returns arrayType
        every { arrayType.name() } returns "int[]"
        every { arrayType.componentTypeName() } returns "int"

        every { frame.visibleVariables() } returns listOf(arrVar)
        every { frame.getValue(arrVar) } returns arrayObj

        val castIntArray = JdiExpressionEvaluator.evaluate("arr as IntArray", vm, frame)
        assertEquals(arrayObj, castIntArray)

        val castIntBracket = JdiExpressionEvaluator.evaluate("arr as Int[]", vm, frame)
        assertEquals(arrayObj, castIntBracket)

        val castPrimitiveBracket = JdiExpressionEvaluator.evaluate("arr as int[]", vm, frame)
        assertEquals(arrayObj, castPrimitiveBracket)
    }

    @Test
    fun `evaluates void and nothing null type casting`() {
        val voidResult = JdiExpressionEvaluator.evaluate("null as Void", vm, frame)
        assertNull(voidResult)

        val nothingResult = JdiExpressionEvaluator.evaluate("null as Nothing", vm, frame)
        assertNull(nothingResult)

        val unitResult = JdiExpressionEvaluator.evaluate("null as kotlin.Unit", vm, frame)
        assertNull(unitResult)
    }

    @Test
    fun `evaluates multi-level class and interface hierarchy casting`() {
        val devVar = mockk<LocalVariable> { every { name() } returns "dev" }
        val devObj = mockk<ObjectReference>()
        val leadDeveloperType = mockk<ClassType>()
        val developerSuperType = mockk<ClassType>()
        val employeeInterface = mockk<InterfaceType>()

        every { devObj.referenceType() } returns leadDeveloperType
        every { leadDeveloperType.name() } returns "com.example.LeadDeveloper"
        every { leadDeveloperType.superclass() } returns developerSuperType
        every { leadDeveloperType.allInterfaces() } returns emptyList()

        every { developerSuperType.name() } returns "com.example.Developer"
        every { developerSuperType.superclass() } returns null
        every { developerSuperType.allInterfaces() } returns listOf(employeeInterface)

        every { employeeInterface.name() } returns "com.example.Employee"

        every { frame.visibleVariables() } returns listOf(devVar)
        every { frame.getValue(devVar) } returns devObj

        // Cast to superclass
        val superCast = JdiExpressionEvaluator.evaluate("dev as Developer", vm, frame)
        assertEquals(devObj, superCast)

        // Cast to interface from superclass
        val ifaceCast = JdiExpressionEvaluator.evaluate("dev as? Employee", vm, frame)
        assertEquals(devObj, ifaceCast)
    }

    @Test
    fun `evaluates incompatible primitive type cast error handling`() {
        val ex = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("\"hello\" as Int", vm, frame)
        }
        assertTrue(ex.message?.contains("ClassCastException") == true)

        val safeResult = JdiExpressionEvaluator.evaluate("\"hello\" as? Int", vm, frame)
        assertNull(safeResult)

        val boolEx = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("123 as Boolean", vm, frame)
        }
        assertTrue(boolEx.message?.contains("ClassCastException") == true)

        val safeBool = JdiExpressionEvaluator.evaluate("123 as? Boolean", vm, frame)
        assertNull(safeBool)
    }

    @Test
    fun `evaluates null type check with nullable types`() {
        // null is String? should be true in Kotlin
        val nullSafeCheck = JdiExpressionEvaluator.evaluate("null is String?", vm, frame)
        assertTrue((nullSafeCheck as BooleanValue).value())

        // null is String should be false
        val nullUnsafeCheck = JdiExpressionEvaluator.evaluate("null is String", vm, frame)
        assertFalse((nullUnsafeCheck as BooleanValue).value())

        // null !is String should be true
        val nullNotIsCheck = JdiExpressionEvaluator.evaluate("null !is String", vm, frame)
        assertTrue((nullNotIsCheck as BooleanValue).value())

        // null is Any? should be true
        val nullAnySafeCheck = JdiExpressionEvaluator.evaluate("null is Any?", vm, frame)
        assertTrue((nullAnySafeCheck as BooleanValue).value())
    }

    @Test
    fun `evaluates primitive casting and checking with fully-qualified package names`() {
        val intVal = JdiExpressionEvaluator.evaluate("42 as java.lang.Integer", vm, frame)
        assertTrue(intVal is IntegerValue)
        assertEquals(42, (intVal as IntegerValue).value())

        val longVal = JdiExpressionEvaluator.evaluate("42 as kotlin.Long", vm, frame)
        assertTrue(longVal is LongValue)
        assertEquals(42L, (longVal as LongValue).value())

        val isKotlinInt = JdiExpressionEvaluator.evaluate("42 is kotlin.Int", vm, frame)
        assertTrue((isKotlinInt as BooleanValue).value())

        val isJavaNumber = JdiExpressionEvaluator.evaluate("42 is java.lang.Number", vm, frame)
        assertTrue((isJavaNumber as BooleanValue).value())

        // Custom wrapper types should not be treated as built-in primitives
        val isCustomInt = JdiExpressionEvaluator.evaluate("42 is dev.shreyaspatil.number.Int", vm, frame)
        assertFalse((isCustomInt as BooleanValue).value())

        val customCastEx = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("42 as dev.shreyaspatil.number.Int", vm, frame)
        }
        assertTrue(customCastEx.message?.contains("ClassCastException") == true)

        val safeCustomCast = JdiExpressionEvaluator.evaluate("42 as? dev.shreyaspatil.number.Int", vm, frame)
        assertNull(safeCustomCast)
    }

    @Test
    fun `evaluates void and nothing supertypes casting and checking`() {
        assertNull(JdiExpressionEvaluator.evaluate("null as Void", vm, frame))
        assertNull(JdiExpressionEvaluator.evaluate("null as java.lang.Void", vm, frame))
        assertNull(JdiExpressionEvaluator.evaluate("null as void", vm, frame))
        assertNull(JdiExpressionEvaluator.evaluate("null as Nothing", vm, frame))
        assertNull(JdiExpressionEvaluator.evaluate("null as kotlin.Unit", vm, frame))
        assertNull(JdiExpressionEvaluator.evaluate("null as Unit", vm, frame))

        val isVoid = JdiExpressionEvaluator.evaluate("null is Void", vm, frame)
        assertTrue((isVoid as BooleanValue).value())

        val isNothing = JdiExpressionEvaluator.evaluate("null is Nothing", vm, frame)
        assertTrue((isNothing as BooleanValue).value())

        val isUnit = JdiExpressionEvaluator.evaluate("null is Unit", vm, frame)
        assertTrue((isUnit as BooleanValue).value())

        val primCastEx = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("42 as Void", vm, frame)
        }
        assertTrue(primCastEx.message?.contains("ClassCastException") == true)

        val safePrimCast = JdiExpressionEvaluator.evaluate("42 as? Void", vm, frame)
        assertNull(safePrimCast)

        val isPrimVoid = JdiExpressionEvaluator.evaluate("42 is Void", vm, frame)
        assertFalse((isPrimVoid as BooleanValue).value())
    }

    @Test
    fun `evaluates fully-qualified static method calls`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val maxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val expectedResult = mockk<IntegerValue> {
            every { value() } returns 20
        }

        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("max") } returns listOf(maxMethod)
        every {
            mathClass.invokeMethod(
                thread,
                maxMethod,
                match {
                    it.size == 2 &&
                        (it[0] as IntegerValue).value() == 10 &&
                        (it[1] as IntegerValue).value() == 20
                },
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedResult

        val result = JdiExpressionEvaluator.evaluate("java.lang.Math.max(10, 20)", vm, frame)
        assertEquals(expectedResult, result)
    }

    @Test
    fun `evaluates unqualified static method calls with default package imports`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val minMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val expectedResult = mockk<IntegerValue> {
            every { value() } returns 10
        }

        every { vm.classesByName("Math") } returns emptyList()
        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("min") } returns listOf(minMethod)
        every {
            mathClass.invokeMethod(
                thread,
                minMethod,
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedResult

        val result = JdiExpressionEvaluator.evaluate("Math.min(10, 20)", vm, frame)
        assertEquals(expectedResult, result)
    }

    @Test
    fun `evaluates static fields and constants`() {
        val integerClass = mockk<ClassType> {
            every { name() } returns "java.lang.Integer"
        }
        val maxValField = mockk<Field> {
            every { isStatic } returns true
            every { name() } returns "MAX_VALUE"
        }
        val expectedMaxVal = mockk<IntegerValue> {
            every { value() } returns Int.MAX_VALUE
        }

        every { vm.classesByName("Integer") } returns emptyList()
        every { vm.classesByName("java.lang.Integer") } returns listOf(integerClass)
        every { integerClass.fieldByName("MAX_VALUE") } returns maxValField
        every { integerClass.getValue(maxValField) } returns expectedMaxVal

        val result = JdiExpressionEvaluator.evaluate("Integer.MAX_VALUE", vm, frame)
        assertEquals(expectedMaxVal, result)
    }

    @Test
    fun `evaluates class literals dot class`() {
        val stringClass = mockk<ClassType> {
            every { name() } returns "java.lang.String"
        }
        val classObjRef = mockk<ClassObjectReference>()

        every { vm.classesByName("String") } returns emptyList()
        every { vm.classesByName("java.lang.String") } returns listOf(stringClass)
        every { stringClass.classObject() } returns classObjRef

        val result = JdiExpressionEvaluator.evaluate("String.class", vm, frame)
        assertEquals(classObjRef, result)
    }

    @Test
    fun `evaluates Kotlin singleton object invocation via INSTANCE`() {
        val singletonClass = mockk<ClassType> {
            every { name() } returns "com.example.AppConfig"
        }
        val instanceField = mockk<Field> {
            every { isStatic } returns true
            every { name() } returns "INSTANCE"
        }
        val singletonInstance = mockk<ObjectReference> {
            every { referenceType() } returns singletonClass
        }
        val getVersionMethod = mockk<Method> {
            every { isStatic } returns false
            every { argumentTypeNames() } returns emptyList()
        }
        val expectedVersion = mockk<StringReference> {
            every { value() } returns "1.0.0"
        }

        every { vm.classesByName("com.example.AppConfig") } returns listOf(singletonClass)
        every { singletonClass.fieldByName("INSTANCE") } returns instanceField
        every { singletonClass.getValue(instanceField) } returns singletonInstance
        every { singletonClass.methodsByName("getVersion") } returns listOf(getVersionMethod)
        every {
            singletonInstance.invokeMethod(
                thread,
                getVersionMethod,
                emptyList(),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns expectedVersion

        val result = JdiExpressionEvaluator.evaluate("com.example.AppConfig.getVersion()", vm, frame)
        assertEquals(expectedVersion, result)
    }

    @Test
    fun `evaluates Kotlin companion object static access`() {
        val hostClass = mockk<ClassType> {
            every { name() } returns "com.example.User"
        }
        val companionClass = mockk<ClassType> {
            every { name() } returns "com.example.User\$Companion"
        }
        val companionField = mockk<Field> {
            every { isStatic } returns true
            every { name() } returns "Companion"
        }
        val companionInstance = mockk<ObjectReference> {
            every { referenceType() } returns companionClass
        }
        val createMethod = mockk<Method> {
            every { isStatic } returns false
            every { argumentTypeNames() } returns listOf("java.lang.String")
        }
        val createdUser = mockk<ObjectReference>()

        every { vm.classesByName("com.example.User") } returns listOf(hostClass)
        every { hostClass.methodsByName("create") } returns emptyList()
        every { hostClass.fieldByName("INSTANCE") } returns null
        every { hostClass.fieldByName("Companion") } returns companionField
        every { hostClass.getValue(companionField) } returns companionInstance
        every { companionClass.methodsByName("create") } returns listOf(createMethod)
        every {
            companionInstance.invokeMethod(
                thread,
                createMethod,
                any(),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns createdUser

        val result = JdiExpressionEvaluator.evaluate("com.example.User.create(\"Alice\")", vm, frame)
        assertEquals(createdUser, result)
    }

    @Test
    fun `evaluates stdlib kotlin static functions via Kt file facade resolution`() {
        val mathKtClass = mockk<ClassType> {
            every { name() } returns "kotlin.math.MathKt"
        }
        val maxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val expectedVal = mockk<IntegerValue> {
            every { value() } returns 20
        }

        every { vm.classesByName("kotlin.math") } returns emptyList()
        every { vm.classesByName("kotlin.math.MathKt") } returns listOf(mathKtClass)
        every { vm.allClasses() } returns listOf(mathKtClass)
        every { mathKtClass.methodsByName("max") } returns listOf(maxMethod)
        every {
            mathKtClass.invokeMethod(
                thread,
                maxMethod,
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedVal

        val result = JdiExpressionEvaluator.evaluate("kotlin.math.max(10, 20)", vm, frame)
        assertEquals(expectedVal, result)
    }

    @Test
    fun `evaluates Kotlin companion JvmStatic method directly on host class`() {
        val orderValidatorClass = mockk<ClassType> {
            every { name() } returns "com.example.OrderValidator"
        }
        val jvmStaticTaxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns emptyList()
        }
        val expectedRate = mockk<DoubleValue> {
            every { value() } returns 0.08
        }

        every { vm.classesByName("com.example.OrderValidator") } returns listOf(orderValidatorClass)
        every { orderValidatorClass.methodsByName("getTaxRate") } returns listOf(jvmStaticTaxMethod)
        every {
            orderValidatorClass.invokeMethod(
                thread,
                jvmStaticTaxMethod,
                emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedRate

        val result = JdiExpressionEvaluator.evaluate("com.example.OrderValidator.getTaxRate()", vm, frame)
        assertEquals(expectedRate, result)
    }

    @Test
    fun `evaluates chained method calls on static method invocation result`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val maxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val intObjType = mockk<ClassType> {
            every { name() } returns "java.lang.Integer"
        }
        val toStringMethod = mockk<Method> {
            every { isStatic } returns false
            every { argumentTypeNames() } returns emptyList()
        }
        val intObjRef = mockk<ObjectReference> {
            every { referenceType() } returns intObjType
        }
        val resultString = mockk<StringReference> {
            every { value() } returns "20"
        }

        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("max") } returns listOf(maxMethod)
        every {
            mathClass.invokeMethod(
                thread,
                maxMethod,
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns intObjRef

        every { intObjType.methodsByName("toString") } returns listOf(toStringMethod)
        every {
            intObjRef.invokeMethod(
                thread,
                toStringMethod,
                emptyList(),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns resultString

        val result = JdiExpressionEvaluator.evaluate("java.lang.Math.max(10, 20).toString()", vm, frame)
        assertEquals(resultString, result)
    }

    @Test
    fun `throws evaluation error when static method or class is not found`() {
        every { vm.allClasses() } returns emptyList()
        every { vm.classesByName(any()) } returns emptyList()

        val classNotFoundEx = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("NonExistentClass.foo()", vm, frame)
        }
        assertTrue(classNotFoundEx.message?.contains("Cannot resolve identifier or class") == true)

        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
            every { methodsByName("nonExistentMethod") } returns emptyList()
            every { fieldByName("INSTANCE") } returns null
            every { fieldByName("Companion") } returns null
        }
        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)

        val methodNotFoundEx = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("java.lang.Math.nonExistentMethod()", vm, frame)
        }
        assertTrue(methodNotFoundEx.message?.contains("Static method 'nonExistentMethod'") == true)
    }

    @Test
    fun `local variable shadows class name with same identifier`() {
        val mathVar = mockk<LocalVariable> { every { name() } returns "Math" }
        val customObjType = mockk<ClassType> {
            every { name() } returns "com.example.CustomMath"
        }
        val customObjRef = mockk<ObjectReference> {
            every { referenceType() } returns customObjType
        }
        val customMethod = mockk<Method> {
            every { isStatic } returns false
            every { argumentTypeNames() } returns emptyList()
        }
        val expectedVal = mockk<IntegerValue> {
            every { value() } returns 99
        }

        every { frame.visibleVariables() } returns listOf(mathVar)
        every { frame.getValue(mathVar) } returns customObjRef
        every { customObjType.methodsByName("customFunc") } returns listOf(customMethod)
        every {
            customObjRef.invokeMethod(
                thread,
                customMethod,
                emptyList(),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns expectedVal

        val result = JdiExpressionEvaluator.evaluate("Math.customFunc()", vm, frame)
        assertEquals(expectedVal, result)
    }

    @Test
    fun `evaluates overloaded static methods with correct argument type match`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val intMaxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val doubleMaxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("double", "double")
        }
        val expectedDoubleResult = mockk<DoubleValue> {
            every { value() } returns 20.5
        }

        every { vm.classesByName("Math") } returns emptyList()
        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("max") } returns listOf(intMaxMethod, doubleMaxMethod)
        every {
            mathClass.invokeMethod(
                thread,
                doubleMaxMethod,
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedDoubleResult

        val result = JdiExpressionEvaluator.evaluate("Math.max(10.5, 20.5)", vm, frame)
        assertEquals(expectedDoubleResult, result)
    }

    @Test
    fun `evaluates static method with nested arithmetic expressions as arguments`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val maxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val expectedResult = mockk<IntegerValue> {
            every { value() } returns 10
        }

        every { vm.classesByName("Math") } returns emptyList()
        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("max") } returns listOf(maxMethod)
        every {
            mathClass.invokeMethod(
                thread,
                maxMethod,
                match {
                    it.size == 2 &&
                        (it[0] as IntegerValue).value() == 8 &&
                        (it[1] as IntegerValue).value() == 10
                },
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedResult

        val result = JdiExpressionEvaluator.evaluate("Math.max(3 + 5, 2 * 5)", vm, frame)
        assertEquals(expectedResult, result)
    }

    @Test
    fun `evaluates static method with zero arguments`() {
        val systemClass = mockk<ClassType> {
            every { name() } returns "java.lang.System"
        }
        val currentTimeMillisMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns emptyList()
        }
        val expectedTime = mockk<LongValue> {
            every { value() } returns 1700000000000L
        }

        every { vm.classesByName("System") } returns emptyList()
        every { vm.classesByName("java.lang.System") } returns listOf(systemClass)
        every { systemClass.methodsByName("currentTimeMillis") } returns listOf(currentTimeMillisMethod)
        every {
            systemClass.invokeMethod(
                thread,
                currentTimeMillisMethod,
                emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedTime

        val result = JdiExpressionEvaluator.evaluate("System.currentTimeMillis()", vm, frame)
        assertEquals(expectedTime, result)
    }

    @Test
    fun `evaluates static method returning null`() {
        val utilClass = mockk<ClassType> {
            every { name() } returns "com.example.Util"
        }
        val getNullMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns emptyList()
        }

        every { vm.classesByName("com.example.Util") } returns listOf(utilClass)
        every { utilClass.methodsByName("getNull") } returns listOf(getNullMethod)
        every {
            utilClass.invokeMethod(
                thread,
                getNullMethod,
                emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns null

        val result = JdiExpressionEvaluator.evaluate("com.example.Util.getNull()", vm, frame)
        assertNull(result)
    }

    @Test
    fun `handles exception thrown inside target VM during static method invocation`() {
        val exceptionClass = mockk<ClassType> {
            every { name() } returns "java.lang.IllegalArgumentException"
        }
        val exceptionRef = mockk<ObjectReference> {
            every { referenceType() } returns exceptionClass
        }
        val invocationException = InvocationException(exceptionRef)

        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val failMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns emptyList()
        }

        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("fail") } returns listOf(failMethod)
        every {
            mathClass.invokeMethod(
                thread,
                failMethod,
                emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } throws invocationException

        val thrown = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("java.lang.Math.fail()", vm, frame)
        }
        assertTrue(thrown.message?.contains("threw an exception: java.lang.IllegalArgumentException") == true)
    }

    @Test
    fun `evaluates static field on interface`() {
        val ifaceType = mockk<InterfaceType> {
            every { name() } returns "com.example.Constants"
        }
        val timeoutField = mockk<Field> {
            every { isStatic } returns true
            every { name() } returns "TIMEOUT"
        }
        val timeoutVal = mockk<IntegerValue> {
            every { value() } returns 5000
        }

        every { vm.classesByName("com.example.Constants") } returns listOf(ifaceType)
        every { ifaceType.fieldByName("TIMEOUT") } returns timeoutField
        every { ifaceType.getValue(timeoutField) } returns timeoutVal

        val result = JdiExpressionEvaluator.evaluate("com.example.Constants.TIMEOUT", vm, frame)
        assertEquals(timeoutVal, result)
    }

    @Test
    fun `throws when attempting to invoke non-static method on class without instance`() {
        val customClass = mockk<ClassType> {
            every { name() } returns "com.example.Service"
            val instanceMethod = mockk<Method> {
                every { isStatic } returns false
                every { argumentTypeNames() } returns emptyList()
            }
            every { methodsByName("doWork") } returns listOf(instanceMethod)
            every { fieldByName("INSTANCE") } returns null
            every { fieldByName("Companion") } returns null
        }

        every { vm.classesByName("com.example.Service") } returns listOf(customClass)

        val thrown = assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("com.example.Service.doWork()", vm, frame)
        }
        assertTrue(thrown.message?.contains("Static method 'doWork' with 0 args not found") == true)
    }

    @Test
    fun `evaluates nested static class invocation using dollar notation resolution`() {
        val nestedClass = mockk<ClassType> {
            every { name() } returns "com.example.Outer\$Nested"
        }
        val helperMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns emptyList()
        }
        val expectedResult = mockk<StringReference> {
            every { value() } returns "nested_result"
        }

        every { vm.classesByName("com.example.Outer.Nested") } returns emptyList()
        every { vm.classesByName("com.example.Outer\$Nested") } returns listOf(nestedClass)
        every { nestedClass.methodsByName("helper") } returns listOf(helperMethod)
        every {
            nestedClass.invokeMethod(
                thread,
                helperMethod,
                emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedResult

        val result = JdiExpressionEvaluator.evaluate("com.example.Outer.Nested.helper()", vm, frame)
        assertEquals(expectedResult, result)
    }

    @Test
    fun `evaluates static getter property access`() {
        val configClass = mockk<ClassType> {
            every { name() } returns "com.example.Config"
        }
        val getDebugModeMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns emptyList()
        }
        val debugModeVal = mockk<BooleanValue> {
            every { value() } returns true
        }

        every { vm.classesByName("com.example.Config") } returns listOf(configClass)
        every { configClass.fieldByName("debugMode") } returns null
        every { configClass.methodsByName("getDebugMode") } returns listOf(getDebugModeMethod)
        every {
            configClass.invokeMethod(
                thread,
                getDebugModeMethod,
                emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns debugModeVal

        val result = JdiExpressionEvaluator.evaluate("com.example.Config.debugMode", vm, frame)
        assertEquals(debugModeVal, result)
    }

    @Test
    fun `evaluates implicit top-level package function without target`() {
        val declaringClass = mockk<ClassType> {
            every { name() } returns "com.example.data.DefaultRepository"
            every { methodsByName("formatOrder") } returns emptyList()
        }
        val location = mockk<com.sun.jdi.Location> {
            every { declaringType() } returns declaringClass
        }
        val fileFacadeClass = mockk<ClassType> {
            every { name() } returns "com.example.data.DataKt"
        }
        val formatOrderMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("java.lang.String")
        }
        val formattedResult = mockk<StringReference> {
            every { value() } returns "ORDER-99"
        }

        every { frame.thisObject() } returns null
        every { frame.location() } returns location
        every { vm.allClasses() } returns listOf(fileFacadeClass)
        every { fileFacadeClass.methodsByName("formatOrder") } returns listOf(formatOrderMethod)
        every {
            fileFacadeClass.invokeMethod(
                thread,
                formatOrderMethod,
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns formattedResult

        val result = JdiExpressionEvaluator.evaluate("formatOrder(\"99\")", vm, frame)
        assertEquals(formattedResult, result)
    }

    @Test
    fun `prioritizes exact matching method overload over compatible overloads`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "com.example.MathService"
        }
        val doubleMaxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("double", "double")
        }
        val intMaxMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("int", "int")
        }
        val intResult = mockk<IntegerValue> {
            every { value() } returns 20
        }

        every { vm.classesByName("com.example.MathService") } returns listOf(mathClass)
        every { mathClass.methodsByName("computeMax") } returns listOf(doubleMaxMethod, intMaxMethod)
        every {
            mathClass.invokeMethod(
                thread,
                intMaxMethod,
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns intResult

        val result = JdiExpressionEvaluator.evaluate("com.example.MathService.computeMax(10, 20)", vm, frame)
        assertEquals(intResult, result)
    }

    @Test
    fun `evaluates class literals with dot class notation`() {
        val stringClass = mockk<ClassType> {
            every { name() } returns "java.lang.String"
        }
        val classObjRef = mockk<ClassObjectReference>()
        every { stringClass.classObject() } returns classObjRef
        every { vm.classesByName("java.lang.String") } returns listOf(stringClass)

        val result = JdiExpressionEvaluator.evaluate("java.lang.String.class", vm, frame)
        assertEquals(classObjRef, result)
    }

    @Test
    fun `evaluates static method invocation with null literal argument`() {
        val validatorClass = mockk<ClassType> {
            every { name() } returns "com.example.Validator"
        }
        val checkMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("java.lang.String")
        }
        val expectedBool = mockk<BooleanValue> {
            every { value() } returns false
        }

        every { vm.classesByName("com.example.Validator") } returns listOf(validatorClass)
        every { validatorClass.methodsByName("isValid") } returns listOf(checkMethod)
        every {
            validatorClass.invokeMethod(
                thread,
                checkMethod,
                listOf(null),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns expectedBool

        val result = JdiExpressionEvaluator.evaluate("com.example.Validator.isValid(null)", vm, frame)
        assertEquals(expectedBool, result)
    }

    @Test
    fun `coerces primitive integer argument to double when calling static method`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        val sqrtMethod = mockk<Method> {
            every { isStatic } returns true
            every { argumentTypeNames() } returns listOf("double")
        }
        val double4 = mockk<DoubleValue>()
        val sqrtResult = mockk<DoubleValue> {
            every { value() } returns 2.0
        }

        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)
        every { mathClass.methodsByName("sqrt") } returns listOf(sqrtMethod)
        every { vm.mirrorOf(4.0) } returns double4
        every {
            mathClass.invokeMethod(
                thread,
                sqrtMethod,
                listOf(double4),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } returns sqrtResult

        val result = JdiExpressionEvaluator.evaluate("java.lang.Math.sqrt(4)", vm, frame)
        assertEquals(sqrtResult, result)
    }

    @Test
    fun `invokes companion object method when host class also defines an unrelated INSTANCE field`() {
        val hostClass = mockk<ClassType> {
            every { name() } returns "com.example.OrderManager"
            every { methodsByName("getTaxRate") } returns emptyList()
        }
        val instanceField = mockk<com.sun.jdi.Field> {
            every { isStatic } returns true
        }
        val companionField = mockk<com.sun.jdi.Field> {
            every { isStatic } returns true
        }
        val instanceObj = mockk<ObjectReference> {
            val refType = mockk<ReferenceType> {
                every { name() } returns "com.example.OrderManager\$Instance"
                every { visibleMethods() } returns emptyList()
                every { methodsByName("getTaxRate") } returns emptyList()
            }
            every { referenceType() } returns refType
        }
        val companionObj = mockk<ObjectReference> {
            val refType = mockk<ReferenceType> {
                every { name() } returns "com.example.OrderManager\$Companion"
                val getTaxRateMethod = mockk<Method> {
                    every { name() } returns "getTaxRate"
                    every { argumentTypeNames() } returns emptyList()
                }
                every { visibleMethods() } returns listOf(getTaxRateMethod)
                every { methodsByName("getTaxRate") } returns listOf(getTaxRateMethod)
            }
            every { referenceType() } returns refType
        }
        val companionMethod = companionObj.referenceType().visibleMethods().first()
        val taxRateResult = mockk<DoubleValue> {
            every { value() } returns 0.15
        }

        every { vm.classesByName("com.example.OrderManager") } returns listOf(hostClass)
        every { hostClass.fieldByName("INSTANCE") } returns instanceField
        every { hostClass.getValue(instanceField) } returns instanceObj
        every { hostClass.fieldByName("Companion") } returns companionField
        every { hostClass.getValue(companionField) } returns companionObj
        every {
            companionObj.invokeMethod(
                thread,
                companionMethod,
                emptyList(),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns taxRateResult

        val result = JdiExpressionEvaluator.evaluate("com.example.OrderManager.getTaxRate()", vm, frame)
        assertEquals(taxRateResult, result)
    }

    @Test
    fun `invokes inherited method from superclass on object reference via visibleMethods`() {
        val localObj = mockk<ObjectReference>()
        val subClassType = mockk<ClassType> {
            every { name() } returns "com.example.CustomOrder"
            val toStringMethod = mockk<Method> {
                every { name() } returns "toString"
                every { argumentTypeNames() } returns emptyList()
            }
            // methodsByName declared directly returns empty (inherited from Object)
            every { methodsByName("toString") } returns emptyList()
            // visibleMethods includes inherited toString()
            every { visibleMethods() } returns listOf(toStringMethod)
        }
        every { localObj.referenceType() } returns subClassType

        val stringResult = mockk<StringReference> {
            every { value() } returns "Order#123"
        }
        val toStringMethod = subClassType.visibleMethods().first()

        every { frame.thisObject() } returns null
        val localVar = mockk<com.sun.jdi.LocalVariable> {
            every { name() } returns "order"
        }
        every { frame.visibleVariables() } returns listOf(localVar)
        every { frame.getValue(localVar) } returns localObj
        every {
            localObj.invokeMethod(
                thread,
                toStringMethod,
                emptyList(),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns stringResult

        val result = JdiExpressionEvaluator.evaluate("order.toString()", vm, frame)
        assertEquals(stringResult, result)
    }

    @Test
    fun `fails evaluation when passing incompatible object to primitive parameter overload`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "com.example.PrimitiveHelper"
            val intMethod = mockk<Method> {
                every { isStatic } returns true
                every { argumentTypeNames() } returns listOf("int")
            }
            every { methodsByName("process") } returns listOf(intMethod)
            every { fieldByName("process") } returns null
        }
        every { vm.classesByName("com.example.PrimitiveHelper") } returns listOf(mathClass)

        val strObj = mockk<StringReference> {
            every { value() } returns "invalid"
            every { referenceType() } returns mockk { every { name() } returns "java.lang.String" }
        }
        every { vm.mirrorOf("invalid") } returns strObj
        every {
            mathClass.invokeMethod(
                thread,
                any(),
                any(),
                ClassType.INVOKE_SINGLE_THREADED
            )
        } throws IllegalArgumentException("Invalid argument type")

        assertThrows(DebugException::class.java) {
            JdiExpressionEvaluator.evaluate("com.example.PrimitiveHelper.process(\"invalid\")", vm, frame)
        }
    }

    @Test
    fun `evaluates inherited property getter via visibleMethods`() {
        val orderObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType> {
            every { name() } returns "com.example.Order"
        }
        val getIdMethod = mockk<Method> {
            every { name() } returns "getId"
            every { argumentTypeNames() } returns emptyList()
        }
        val idVal = mockk<IntegerValue> {
            every { value() } returns 42
        }

        every { orderObj.referenceType() } returns refType
        // methodsByName returns empty (declared only on superclass)
        every { refType.methodsByName("getId") } returns emptyList()
        every { refType.methodsByName("isId") } returns emptyList()
        every { refType.methodsByName("id") } returns emptyList()
        every { refType.fieldByName("id") } returns null
        // visibleMethods returns inherited getter
        every { refType.visibleMethods() } returns listOf(getIdMethod)
        every {
            orderObj.invokeMethod(thread, getIdMethod, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED)
        } returns idVal

        val orderVar = mockk<LocalVariable> { every { name() } returns "order" }
        every { frame.visibleVariables() } returns listOf(orderVar)
        every { frame.getValue(orderVar) } returns orderObj

        val result = JdiExpressionEvaluator.evaluate("order.id", vm, frame)
        assertEquals(idVal, result)
    }

    @Test
    fun `evaluates inherited implicit method invocation on this via visibleMethods`() {
        val thisObj = mockk<ObjectReference>()
        val refType = mockk<ReferenceType> {
            every { name() } returns "com.example.MainActivity"
        }
        val finishMethod = mockk<Method> {
            every { name() } returns "finish"
            every { argumentTypeNames() } returns emptyList()
        }
        val nullReturn = null

        every { frame.thisObject() } returns thisObj
        every { thisObj.referenceType() } returns refType
        every { refType.methodsByName("finish") } returns emptyList()
        every { refType.visibleMethods() } returns listOf(finishMethod)
        every {
            thisObj.invokeMethod(thread, finishMethod, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED)
        } returns nullReturn

        val result = JdiExpressionEvaluator.evaluate("finish()", vm, frame)
        assertNull(result)
    }
}
