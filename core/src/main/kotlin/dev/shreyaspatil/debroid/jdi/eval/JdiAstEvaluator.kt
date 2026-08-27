package dev.shreyaspatil.debroid.jdi.eval

import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortValue
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.models.ErrorCode

@Suppress("TooManyFunctions", "LargeClass", "ThrowsCount")
class JdiAstEvaluator(
    private val vm: VirtualMachine,
    private val frame: StackFrame
) {
    companion object {
        fun evaluate(ast: ExprNode, vm: VirtualMachine, frame: StackFrame): Value? {
            return JdiAstEvaluator(vm, frame).evaluateNode(ast)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun evaluateNode(node: ExprNode): Value? {
        return when (node) {
            is IntLiteralNode -> vm.mirrorOf(node.value)
            is LongLiteralNode -> vm.mirrorOf(node.value)
            is FloatLiteralNode -> vm.mirrorOf(node.value)
            is DoubleLiteralNode -> vm.mirrorOf(node.value)
            is BooleanLiteralNode -> vm.mirrorOf(node.value)
            is StringLiteralNode -> vm.mirrorOf(node.value)
            is CharLiteralNode -> vm.mirrorOf(node.value)
            is NullLiteralNode -> null
            is ThisNode -> frame.thisObject()
            is SuperNode -> frame.thisObject()
            is IdentifierNode -> resolveIdentifier(node.name)
            is MemberAccessNode -> resolveMember(node)
            is MethodCallNode -> resolveMethodCall(node)
            is ArrayAccessNode -> resolveArrayAccess(node)
            is UnaryOpNode -> evaluateUnary(node)
            is BinaryOpNode -> evaluateBinary(node)
            is TernaryOpNode -> evaluateTernary(node)
        }
    }

    @Suppress("ReturnCount")
    private fun resolveIdentifier(name: String): Value? {
        // 1. Search local variables in the current stack frame
        val visVar = try {
            frame.visibleVariables().find { it.name() == name }
        } catch (_: com.sun.jdi.AbsentInformationException) {
            null
        }
        if (visVar != null) {
            return frame.getValue(visVar)
        }

        // 2. Search fields & property getters on `this` object
        val thisObj = frame.thisObject()
        if (thisObj != null) {
            val field = thisObj.referenceType().fieldByName(name)
            if (field != null) {
                return thisObj.getValue(field)
            }
            val getterValue = tryInvokePropertyGetter(thisObj, name)
            if (getterValue.isSuccess) {
                return getterValue.getOrNull()
            }
            val exception = getterValue.exceptionOrNull()
            if (exception != null && exception !is NoSuchMethodException) {
                throw DebugException(
                    ErrorCode.EVALUATION_FAILED,
                    "Getter for '$name' threw: ${exception.message}"
                )
            }
        }

        throw DebugException(
            ErrorCode.EVALUATION_FAILED,
            "Cannot resolve identifier '$name' in current scope."
        )
    }

    @Suppress("ReturnCount")
    private fun resolveMember(node: MemberAccessNode): Value? {
        val targetVal = evaluateNode(node.target)
        if (targetVal == null) {
            if (node.isSafe) return null
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "NullPointerException: Cannot access property '${node.memberName}' on null reference."
            )
        }

        if (targetVal is ArrayReference && node.memberName == "length") {
            return vm.mirrorOf(targetVal.length())
        }

        if (targetVal is StringReference && node.memberName == "length") {
            return vm.mirrorOf(targetVal.value().length)
        }

        if (targetVal is ObjectReference) {
            val field = targetVal.referenceType().fieldByName(node.memberName)
            if (field != null) {
                return targetVal.getValue(field)
            }

            val getterResult = tryInvokePropertyGetter(targetVal, node.memberName)
            if (getterResult.isSuccess) {
                return getterResult.getOrNull()
            }
            val exception = getterResult.exceptionOrNull()
            if (exception != null && exception !is NoSuchMethodException) {
                throw DebugException(
                    ErrorCode.EVALUATION_FAILED,
                    "Getter for '${node.memberName}' threw: ${exception.message}"
                )
            }

            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Field or property '${node.memberName}' not found on type ${targetVal.referenceType().name()}."
            )
        }

        throw DebugException(
            ErrorCode.EVALUATION_FAILED,
            "Cannot access property '${node.memberName}' on primitive value $targetVal."
        )
    }

    private fun tryInvokePropertyGetter(obj: ObjectReference, propName: String): Result<Value?> {
        val capitalized = propName.replaceFirstChar { it.uppercase() }
        val candidateNames = listOf(
            "get$capitalized",
            "is$capitalized",
            propName
        )

        for (getterName in candidateNames) {
            val methods = obj.referenceType().methodsByName(getterName)
            val getter = methods.find { it.argumentTypeNames().isEmpty() }
            if (getter != null) {
                return runCatching {
                    obj.invokeMethod(
                        frame.thread(),
                        getter,
                        emptyList(),
                        ObjectReference.INVOKE_SINGLE_THREADED
                    )
                }
            }
        }
        return Result.failure(NoSuchMethodException("No getter found for $propName"))
    }

    @Suppress("ReturnCount")
    private fun resolveMethodCall(node: MethodCallNode): Value? {
        val targetObj: ObjectReference = if (node.target != null) {
            val targetVal = evaluateNode(node.target)
            if (targetVal == null) {
                if (node.isSafe) return null
                throw DebugException(
                    ErrorCode.EVALUATION_FAILED,
                    "NullPointerException: Cannot invoke method '${node.methodName}()' on null reference."
                )
            }
            if (targetVal !is ObjectReference) {
                throw DebugException(
                    ErrorCode.EVALUATION_FAILED,
                    "Cannot invoke method '${node.methodName}()' on primitive value $targetVal."
                )
            }
            targetVal
        } else {
            frame.thisObject() ?: throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Cannot invoke method '${node.methodName}()' in static context without target."
            )
        }

        val evaluatedArgs = node.args.map { evaluateNode(it) }
        val candidateMethods = targetObj.referenceType().methodsByName(node.methodName)
            .filter { it.argumentTypeNames().size == evaluatedArgs.size }
        if (candidateMethods.isEmpty()) {
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Method '${node.methodName}' with ${evaluatedArgs.size} args not found " +
                    "on ${targetObj.referenceType().name()}."
            )
        }

        var lastException: Exception? = null
        for (method in candidateMethods) {
            try {
                return targetObj.invokeMethod(
                    frame.thread(),
                    method,
                    evaluatedArgs,
                    ObjectReference.INVOKE_SINGLE_THREADED
                )
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw DebugException(
            ErrorCode.EVALUATION_FAILED,
            "Method '${node.methodName}()' invocation threw: ${lastException?.message}"
        )
    }

    private fun resolveArrayAccess(node: ArrayAccessNode): Value? {
        val targetVal = evaluateNode(node.target)
            ?: throw DebugException(ErrorCode.EVALUATION_FAILED, "NullPointerException: Cannot index into null array.")
        if (targetVal !is ArrayReference) {
            throw DebugException(ErrorCode.EVALUATION_FAILED, "Expected array for index access but found $targetVal.")
        }
        val indexVal = evaluateNode(node.index)
        val idx = when (indexVal) {
            is IntegerValue -> indexVal.value()
            is ShortValue -> indexVal.value().toInt()
            is ByteValue -> indexVal.value().toInt()
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Array index must be integer, found $indexVal.")
        }
        if (idx < 0 || idx >= targetVal.length()) {
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "IndexOutOfBoundsException: Index $idx out of bounds for array of length ${targetVal.length()}."
            )
        }
        return targetVal.getValue(idx)
    }

    private fun evaluateUnary(node: UnaryOpNode): Value {
        val v = evaluateNode(node.expr)
        return when (node.op) {
            UnaryOp.NOT -> {
                if (v !is BooleanValue) {
                    throw DebugException(ErrorCode.EVALUATION_FAILED, "Operator '!' cannot be applied to $v.")
                }
                vm.mirrorOf(!v.value())
            }
            UnaryOp.NEGATE -> {
                when (v) {
                    is DoubleValue -> vm.mirrorOf(-v.value())
                    is FloatValue -> vm.mirrorOf(-v.value())
                    is LongValue -> vm.mirrorOf(-v.value())
                    is IntegerValue -> vm.mirrorOf(-v.value())
                    is ShortValue -> vm.mirrorOf(-v.value())
                    is ByteValue -> vm.mirrorOf(-v.value())
                    else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Unary '-' cannot be applied to $v.")
                }
            }
            UnaryOp.PLUS -> {
                if (isNumeric(v)) {
                    v as PrimitiveValue
                } else {
                    throw DebugException(ErrorCode.EVALUATION_FAILED, "Unary '+' cannot be applied to $v.")
                }
            }
            UnaryOp.BITWISE_NOT -> {
                when (v) {
                    is LongValue -> vm.mirrorOf(v.value().inv())
                    is IntegerValue -> vm.mirrorOf(v.value().inv())
                    is ShortValue -> vm.mirrorOf(v.value().toInt().inv())
                    is ByteValue -> vm.mirrorOf(v.value().toInt().inv())
                    else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Operator '~' cannot be applied to $v.")
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun evaluateBinary(node: BinaryOpNode): Value? {
        // Short-circuiting operators
        when (node.op) {
            BinaryOp.LOGICAL_AND -> {
                val left = evaluateNode(node.left)
                if (left !is BooleanValue) {
                    throw DebugException(ErrorCode.EVALUATION_FAILED, "Operator '&&' requires boolean left operand.")
                }
                if (!left.value()) return vm.mirrorOf(false)
                val right = evaluateNode(node.right)
                if (right !is BooleanValue) {
                    throw DebugException(ErrorCode.EVALUATION_FAILED, "Operator '&&' requires boolean right operand.")
                }
                return vm.mirrorOf(right.value())
            }
            BinaryOp.LOGICAL_OR -> {
                val left = evaluateNode(node.left)
                if (left !is BooleanValue) {
                    throw DebugException(ErrorCode.EVALUATION_FAILED, "Operator '||' requires boolean left operand.")
                }
                if (left.value()) return vm.mirrorOf(true)
                val right = evaluateNode(node.right)
                if (right !is BooleanValue) {
                    throw DebugException(ErrorCode.EVALUATION_FAILED, "Operator '||' requires boolean right operand.")
                }
                return vm.mirrorOf(right.value())
            }
            BinaryOp.ELVIS -> {
                val left = evaluateNode(node.left)
                return left ?: evaluateNode(node.right)
            }
            else -> { /* fallthrough to eager binary operations */ }
        }

        // Type checks: IS, NOT_IS, INSTANCE_OF
        if (node.op == BinaryOp.IS || node.op == BinaryOp.NOT_IS || node.op == BinaryOp.INSTANCE_OF) {
            val left = evaluateNode(node.left)
            val typeName = (node.right as IdentifierNode).name
            val isInstance = evaluateTypeCheck(left, typeName)
            val result = if (node.op == BinaryOp.NOT_IS) !isInstance else isInstance
            return vm.mirrorOf(result)
        }

        val left = evaluateNode(node.left)
        val right = evaluateNode(node.right)

        return when (node.op) {
            BinaryOp.EQUALS -> evaluateEquality(left, right, equal = true)
            BinaryOp.NOT_EQUALS -> evaluateEquality(left, right, equal = false)
            BinaryOp.LESS_THAN, BinaryOp.LESS_EQUAL, BinaryOp.GREATER_THAN, BinaryOp.GREATER_EQUAL ->
                evaluateRelational(node.op, left, right)
            BinaryOp.ADD -> evaluateAddition(left, right)
            BinaryOp.SUBTRACT, BinaryOp.MULTIPLY, BinaryOp.DIVIDE, BinaryOp.MODULO ->
                evaluateArithmetic(node.op, left, right)
            BinaryOp.BITWISE_AND, BinaryOp.BITWISE_OR, BinaryOp.BITWISE_XOR ->
                evaluateBitwise(node.op, left, right)
            BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR ->
                evaluateShift(node.op, left, right)
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Unsupported operator '${node.op}'.")
        }
    }

    private fun evaluateTypeCheck(value: Value?, typeName: String): Boolean {
        if (value == null || value !is ObjectReference) return false
        val refType = value.referenceType()
        return isSubtypeOf(refType, typeName)
    }

    private fun isSubtypeOf(type: ReferenceType, targetTypeName: String): Boolean {
        if (type.name() == targetTypeName || type.name().endsWith(".$targetTypeName")) return true
        if (type is com.sun.jdi.ClassType) {
            val superclass = type.superclass()
            if (superclass != null && isSubtypeOf(superclass, targetTypeName)) return true
            for (iface in type.allInterfaces()) {
                if (isSubtypeOf(iface, targetTypeName)) return true
            }
        }
        return false
    }

    @Suppress("CyclomaticComplexMethod")
    private fun evaluateEquality(left: Value?, right: Value?, equal: Boolean): Value {
        if (left == null || right == null) {
            val areEqual = (left == null && right == null)
            return vm.mirrorOf(if (equal) areEqual else !areEqual)
        }

        val areEqual = when {
            left is BooleanValue && right is BooleanValue -> left.value() == right.value()
            left is BooleanValue || right is BooleanValue -> false
            isNumeric(left) && isNumeric(right) -> {
                if (isFloatingPoint(left) || isFloatingPoint(right)) {
                    toDouble(left) == toDouble(right)
                } else {
                    toLong(left) == toLong(right)
                }
            }
            left is StringReference && right is StringReference -> left.value() == right.value()
            left is ObjectReference && right is ObjectReference -> left.uniqueID() == right.uniqueID()
            else -> left == right
        }
        return vm.mirrorOf(if (equal) areEqual else !areEqual)
    }

    private fun evaluateRelational(op: BinaryOp, left: Value?, right: Value?): Value {
        if (!isNumeric(left) || !isNumeric(right)) {
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Relational operator '$op' requires numeric operands, got $left and $right."
            )
        }

        val nonNullLeft = left as PrimitiveValue
        val nonNullRight = right as PrimitiveValue

        val cmp = if (isFloatingPoint(nonNullLeft) || isFloatingPoint(nonNullRight)) {
            toDouble(nonNullLeft).compareTo(toDouble(nonNullRight))
        } else {
            toLong(nonNullLeft).compareTo(toLong(nonNullRight))
        }

        val result = when (op) {
            BinaryOp.LESS_THAN -> cmp < 0
            BinaryOp.LESS_EQUAL -> cmp <= 0
            BinaryOp.GREATER_THAN -> cmp > 0
            BinaryOp.GREATER_EQUAL -> cmp >= 0
            else -> false
        }
        return vm.mirrorOf(result)
    }

    private fun evaluateAddition(left: Value?, right: Value?): Value {
        if (left is StringReference || right is StringReference) {
            val leftStr = valueToString(left)
            val rightStr = valueToString(right)
            return vm.mirrorOf(leftStr + rightStr)
        }
        return evaluateArithmetic(BinaryOp.ADD, left, right)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun evaluateArithmetic(op: BinaryOp, left: Value?, right: Value?): Value {
        if (!isNumeric(left) || !isNumeric(right)) {
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Arithmetic operator '$op' requires numeric operands, got $left and $right."
            )
        }

        val nonNullLeft = left as PrimitiveValue
        val nonNullRight = right as PrimitiveValue

        return when {
            isDouble(nonNullLeft) || isDouble(nonNullRight) -> evaluateDoubleArithmetic(op, nonNullLeft, nonNullRight)
            isFloat(nonNullLeft) || isFloat(nonNullRight) -> evaluateFloatArithmetic(op, nonNullLeft, nonNullRight)
            isLong(nonNullLeft) || isLong(nonNullRight) -> evaluateLongArithmetic(op, nonNullLeft, nonNullRight)
            else -> evaluateIntArithmetic(op, nonNullLeft, nonNullRight)
        }
    }

    private fun evaluateDoubleArithmetic(op: BinaryOp, left: PrimitiveValue, right: PrimitiveValue): Value {
        val l = toDouble(left)
        val r = toDouble(right)
        return when (op) {
            BinaryOp.ADD -> vm.mirrorOf(l + r)
            BinaryOp.SUBTRACT -> vm.mirrorOf(l - r)
            BinaryOp.MULTIPLY -> vm.mirrorOf(l * r)
            BinaryOp.DIVIDE -> vm.mirrorOf(l / r)
            BinaryOp.MODULO -> vm.mirrorOf(l % r)
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Unknown arithmetic op $op")
        }
    }

    private fun evaluateFloatArithmetic(op: BinaryOp, left: PrimitiveValue, right: PrimitiveValue): Value {
        val l = toFloat(left)
        val r = toFloat(right)
        return when (op) {
            BinaryOp.ADD -> vm.mirrorOf(l + r)
            BinaryOp.SUBTRACT -> vm.mirrorOf(l - r)
            BinaryOp.MULTIPLY -> vm.mirrorOf(l * r)
            BinaryOp.DIVIDE -> vm.mirrorOf(l / r)
            BinaryOp.MODULO -> vm.mirrorOf(l % r)
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Unknown arithmetic op $op")
        }
    }

    private fun evaluateLongArithmetic(op: BinaryOp, left: PrimitiveValue, right: PrimitiveValue): Value {
        val l = toLong(left)
        val r = toLong(right)
        if ((op == BinaryOp.DIVIDE || op == BinaryOp.MODULO) && r == 0L) {
            throw DebugException(ErrorCode.EVALUATION_FAILED, "/ by zero")
        }
        return when (op) {
            BinaryOp.ADD -> vm.mirrorOf(l + r)
            BinaryOp.SUBTRACT -> vm.mirrorOf(l - r)
            BinaryOp.MULTIPLY -> vm.mirrorOf(l * r)
            BinaryOp.DIVIDE -> vm.mirrorOf(l / r)
            BinaryOp.MODULO -> vm.mirrorOf(l % r)
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Unknown arithmetic op $op")
        }
    }

    private fun evaluateIntArithmetic(op: BinaryOp, left: PrimitiveValue, right: PrimitiveValue): Value {
        val l = toInt(left)
        val r = toInt(right)
        if ((op == BinaryOp.DIVIDE || op == BinaryOp.MODULO) && r == 0) {
            throw DebugException(ErrorCode.EVALUATION_FAILED, "/ by zero")
        }
        return when (op) {
            BinaryOp.ADD -> vm.mirrorOf(l + r)
            BinaryOp.SUBTRACT -> vm.mirrorOf(l - r)
            BinaryOp.MULTIPLY -> vm.mirrorOf(l * r)
            BinaryOp.DIVIDE -> vm.mirrorOf(l / r)
            BinaryOp.MODULO -> vm.mirrorOf(l % r)
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Unknown arithmetic op $op")
        }
    }

    private fun evaluateBitwise(op: BinaryOp, left: Value?, right: Value?): Value {
        if (left == null || right == null) {
            throw DebugException(ErrorCode.EVALUATION_FAILED, "Bitwise operator '$op' requires non-null operands.")
        }

        if (left is BooleanValue && right is BooleanValue) {
            val l = left.value()
            val r = right.value()
            val res = when (op) {
                BinaryOp.BITWISE_AND -> l and r
                BinaryOp.BITWISE_OR -> l or r
                BinaryOp.BITWISE_XOR -> l xor r
                else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Invalid bitwise op $op")
            }
            return vm.mirrorOf(res)
        }

        if (!isIntegral(left) || !isIntegral(right)) {
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Bitwise operator '$op' requires integral operands, got $left and $right."
            )
        }

        if (isLong(left) || isLong(right)) {
            val l = toLong(left)
            val r = toLong(right)
            val res = when (op) {
                BinaryOp.BITWISE_AND -> l and r
                BinaryOp.BITWISE_OR -> l or r
                BinaryOp.BITWISE_XOR -> l xor r
                else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Invalid bitwise op $op")
            }
            return vm.mirrorOf(res)
        }

        val l = toInt(left)
        val r = toInt(right)
        val res = when (op) {
            BinaryOp.BITWISE_AND -> l and r
            BinaryOp.BITWISE_OR -> l or r
            BinaryOp.BITWISE_XOR -> l xor r
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Invalid bitwise op $op")
        }
        return vm.mirrorOf(res)
    }

    private fun evaluateShift(op: BinaryOp, left: Value?, right: Value?): Value {
        if (left == null || right == null) {
            throw DebugException(ErrorCode.EVALUATION_FAILED, "Shift operator '$op' requires non-null operands.")
        }
        if (!isIntegral(left) || !isIntegral(right)) {
            throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Shift operator '$op' requires integral operands, got $left and $right."
            )
        }
        val r = toInt(right)
        if (isLong(left)) {
            val l = toLong(left)
            val res = when (op) {
                BinaryOp.SHL -> l shl r
                BinaryOp.SHR -> l shr r
                BinaryOp.USHR -> l ushr r
                else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Invalid shift op $op")
            }
            return vm.mirrorOf(res)
        }
        val l = toInt(left)
        val res = when (op) {
            BinaryOp.SHL -> l shl r
            BinaryOp.SHR -> l shr r
            BinaryOp.USHR -> l ushr r
            else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Invalid shift op $op")
        }
        return vm.mirrorOf(res)
    }

    private fun evaluateTernary(node: TernaryOpNode): Value? {
        val condition = evaluateNode(node.condition)
        if (condition !is BooleanValue) {
            throw DebugException(ErrorCode.EVALUATION_FAILED, "Ternary condition must evaluate to boolean.")
        }
        return if (condition.value()) {
            evaluateNode(node.thenExpr)
        } else {
            evaluateNode(node.elseExpr)
        }
    }

    private fun valueToString(value: Value?): String {
        return when (value) {
            null -> "null"
            is StringReference -> value.value()
            is BooleanValue -> value.value().toString()
            is IntegerValue -> value.value().toString()
            is LongValue -> value.value().toString()
            is DoubleValue -> value.value().toString()
            is FloatValue -> value.value().toString()
            is ShortValue -> value.value().toString()
            is ByteValue -> value.value().toString()
            is CharValue -> value.value().toString()
            is ObjectReference -> "<${value.referenceType().name()} id=${value.uniqueID()}>"
            else -> value.toString()
        }
    }

    private fun isNumeric(v: Value?): Boolean = v is PrimitiveValue && v !is BooleanValue
    private fun isIntegral(v: Value?): Boolean =
        v is IntegerValue || v is LongValue || v is ShortValue || v is ByteValue || v is CharValue
    private fun isFloatingPoint(v: Value?): Boolean = v is DoubleValue || v is FloatValue
    private fun isDouble(v: Value?): Boolean = v is DoubleValue
    private fun isFloat(v: Value?): Boolean = v is FloatValue
    private fun isLong(v: Value?): Boolean = v is LongValue

    private fun toDouble(v: Value): Double = when (v) {
        is DoubleValue -> v.value()
        is FloatValue -> v.value().toDouble()
        is LongValue -> v.value().toDouble()
        is IntegerValue -> v.value().toDouble()
        is ShortValue -> v.value().toDouble()
        is ByteValue -> v.value().toDouble()
        is CharValue -> v.value().code.toDouble()
        else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Cannot convert $v to Double")
    }

    private fun toFloat(v: Value): Float = when (v) {
        is FloatValue -> v.value()
        is DoubleValue -> v.value().toFloat()
        is LongValue -> v.value().toFloat()
        is IntegerValue -> v.value().toFloat()
        is ShortValue -> v.value().toFloat()
        is ByteValue -> v.value().toFloat()
        is CharValue -> v.value().code.toFloat()
        else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Cannot convert $v to Float")
    }

    private fun toLong(v: Value): Long = when (v) {
        is LongValue -> v.value()
        is IntegerValue -> v.value().toLong()
        is ShortValue -> v.value().toLong()
        is ByteValue -> v.value().toLong()
        is CharValue -> v.value().code.toLong()
        is DoubleValue -> v.value().toLong()
        is FloatValue -> v.value().toLong()
        else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Cannot convert $v to Long")
    }

    private fun toInt(v: Value): Int = when (v) {
        is IntegerValue -> v.value()
        is ShortValue -> v.value().toInt()
        is ByteValue -> v.value().toInt()
        is CharValue -> v.value().code
        is LongValue -> v.value().toInt()
        is DoubleValue -> v.value().toInt()
        is FloatValue -> v.value().toInt()
        else -> throw DebugException(ErrorCode.EVALUATION_FAILED, "Cannot convert $v to Int")
    }
}
