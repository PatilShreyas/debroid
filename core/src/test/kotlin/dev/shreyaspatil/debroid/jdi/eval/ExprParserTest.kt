package dev.shreyaspatil.debroid.jdi.eval

import dev.shreyaspatil.debroid.adb.DebugException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExprParserTest {

    @Test
    fun `parse numeric literals`() {
        assertEquals(IntLiteralNode(42), ExprParser.parse("42"))
        assertEquals(IntLiteralNode(0xFF), ExprParser.parse("0xFF"))
        assertEquals(IntLiteralNode(5), ExprParser.parse("0b101"))
        assertEquals(LongLiteralNode(100L), ExprParser.parse("100L"))
        assertEquals(FloatLiteralNode(3.14f), ExprParser.parse("3.14f"))
        assertEquals(FloatLiteralNode(0.5f), ExprParser.parse(".5f"))
        assertEquals(DoubleLiteralNode(600.0), ExprParser.parse("600.0"))
        assertEquals(DoubleLiteralNode(1e5), ExprParser.parse("1e5"))
    }

    @Test
    fun `parse boolean, string, char, null literals`() {
        assertEquals(BooleanLiteralNode(true), ExprParser.parse("true"))
        assertEquals(BooleanLiteralNode(false), ExprParser.parse("false"))
        assertEquals(StringLiteralNode("hello world"), ExprParser.parse("\"hello world\""))
        assertEquals(StringLiteralNode("escaped \n \t \""), ExprParser.parse("\"escaped \\n \\t \\\"\""))
        assertEquals(CharLiteralNode('c'), ExprParser.parse("'c'"))
        assertEquals(NullLiteralNode, ExprParser.parse("null"))
    }

    @Test
    fun `parse identifiers and special references`() {
        assertEquals(IdentifierNode("amount"), ExprParser.parse("amount"))
        assertEquals(IdentifierNode("isExpress"), ExprParser.parse("`isExpress`"))
        assertEquals(ThisNode, ExprParser.parse("this"))
        assertEquals(SuperNode, ExprParser.parse("super"))
    }

    @Test
    fun `parse compound boolean and logical expressions`() {
        val ast = ExprParser.parse("amount >= 600.0 && isExpress")
        assertInstanceOf(BinaryOpNode::class.java, ast)
        val binNode = ast as BinaryOpNode
        assertEquals(BinaryOp.LOGICAL_AND, binNode.op)

        val left = binNode.left as BinaryOpNode
        assertEquals(BinaryOp.GREATER_EQUAL, left.op)
        assertEquals(IdentifierNode("amount"), left.left)
        assertEquals(DoubleLiteralNode(600.0), left.right)

        assertEquals(IdentifierNode("isExpress"), binNode.right)
    }

    @Test
    fun `parse operator precedence with logical AND and OR`() {
        // a || b && c should parse as a || (b && c)
        val ast = ExprParser.parse("a || b && c") as BinaryOpNode
        assertEquals(BinaryOp.LOGICAL_OR, ast.op)
        assertEquals(IdentifierNode("a"), ast.left)

        val right = ast.right as BinaryOpNode
        assertEquals(BinaryOp.LOGICAL_AND, right.op)
        assertEquals(IdentifierNode("b"), right.left)
        assertEquals(IdentifierNode("c"), right.right)
    }

    @Test
    fun `parse full precedence hierarchy across bitwise, shifts, relational, and arithmetic`() {
        // 1 << 2 + 3 -> 1 << (2 + 3)
        val shiftAst = ExprParser.parse("1 << 2 + 3") as BinaryOpNode
        assertEquals(BinaryOp.SHL, shiftAst.op)
        assertEquals(IntLiteralNode(1), shiftAst.left)
        val shiftRight = shiftAst.right as BinaryOpNode
        assertEquals(BinaryOp.ADD, shiftRight.op)
        assertEquals(IntLiteralNode(2), shiftRight.left)
        assertEquals(IntLiteralNode(3), shiftRight.right)

        // a | b ^ c & d -> a | (b ^ (c & d))
        val bitAst = ExprParser.parse("a | b ^ c & d") as BinaryOpNode
        assertEquals(BinaryOp.BITWISE_OR, bitAst.op)
        assertEquals(IdentifierNode("a"), bitAst.left)
        val xorNode = bitAst.right as BinaryOpNode
        assertEquals(BinaryOp.BITWISE_XOR, xorNode.op)
        assertEquals(IdentifierNode("b"), xorNode.left)
        val andNode = xorNode.right as BinaryOpNode
        assertEquals(BinaryOp.BITWISE_AND, andNode.op)
        assertEquals(IdentifierNode("c"), andNode.left)
        assertEquals(IdentifierNode("d"), andNode.right)

        // a < b == c > d -> (a < b) == (c > d)
        val eqAst = ExprParser.parse("a < b == c > d") as BinaryOpNode
        assertEquals(BinaryOp.EQUALS, eqAst.op)
        val eqLeft = eqAst.left as BinaryOpNode
        assertEquals(BinaryOp.LESS_THAN, eqLeft.op)
        val eqRight = eqAst.right as BinaryOpNode
        assertEquals(BinaryOp.GREATER_THAN, eqRight.op)
    }

    @Test
    fun `parse unary operations`() {
        val notNode = ExprParser.parse("!isValid") as UnaryOpNode
        assertEquals(UnaryOp.NOT, notNode.op)
        assertEquals(IdentifierNode("isValid"), notNode.expr)

        val negNode = ExprParser.parse("-42") as UnaryOpNode
        assertEquals(UnaryOp.NEGATE, negNode.op)
        assertEquals(IntLiteralNode(42), negNode.expr)

        val bitNotNode = ExprParser.parse("~mask") as UnaryOpNode
        assertEquals(UnaryOp.BITWISE_NOT, bitNotNode.op)
        assertEquals(IdentifierNode("mask"), bitNotNode.expr)

        val plusNode = ExprParser.parse("+5") as UnaryOpNode
        assertEquals(UnaryOp.PLUS, plusNode.op)
        assertEquals(IntLiteralNode(5), plusNode.expr)
    }

    @Test
    fun `parse member access and safe navigation`() {
        val member = ExprParser.parse("user.profile.name") as MemberAccessNode
        assertEquals("name", member.memberName)
        assertFalse(member.isSafe)

        val target = member.target as MemberAccessNode
        assertEquals("profile", target.memberName)
        assertEquals(IdentifierNode("user"), target.target)

        val safeMember = ExprParser.parse("user?.profile?.name") as MemberAccessNode
        assertEquals("name", safeMember.memberName)
        assertTrue(safeMember.isSafe)
    }

    @Test
    fun `parse method calls and argument lists`() {
        val call = ExprParser.parse("calculate(1, 2 + 3, \"test\")") as MethodCallNode
        assertEquals(null, call.target)
        assertEquals("calculate", call.methodName)
        assertEquals(3, call.args.size)

        val memberCall = ExprParser.parse("user.getName()") as MethodCallNode
        assertEquals(IdentifierNode("user"), memberCall.target)
        assertEquals("getName", memberCall.methodName)
        assertEquals(0, memberCall.args.size)
        assertFalse(memberCall.isSafe)

        val safeMemberCall = ExprParser.parse("user?.getName()") as MethodCallNode
        assertEquals(IdentifierNode("user"), safeMemberCall.target)
        assertEquals("getName", safeMemberCall.methodName)
        assertTrue(safeMemberCall.isSafe)
    }

    @Test
    fun `parse nested method calls and chained array access`() {
        val nestedCall = ExprParser.parse("foo(bar(1), baz(2, 3))") as MethodCallNode
        assertEquals("foo", nestedCall.methodName)
        assertEquals(2, nestedCall.args.size)
        assertEquals("bar", (nestedCall.args[0] as MethodCallNode).methodName)
        assertEquals("baz", (nestedCall.args[1] as MethodCallNode).methodName)

        val matrixAccess = ExprParser.parse("matrix[0][1]") as ArrayAccessNode
        val outerTarget = matrixAccess.target as ArrayAccessNode
        assertEquals(IdentifierNode("matrix"), outerTarget.target)
        assertEquals(IntLiteralNode(0), outerTarget.index)
        assertEquals(IntLiteralNode(1), matrixAccess.index)
    }

    @Test
    fun `parse array access`() {
        val arrayAccess = ExprParser.parse("items[0]") as ArrayAccessNode
        assertEquals(IdentifierNode("items"), arrayAccess.target)
        assertEquals(IntLiteralNode(0), arrayAccess.index)
    }

    @Test
    fun `parse elvis operator and ternary`() {
        val elvis = ExprParser.parse("user?.name ?: \"Unknown\"") as BinaryOpNode
        assertEquals(BinaryOp.ELVIS, elvis.op)

        val ternary = ExprParser.parse("isValid ? 1 : 0") as TernaryOpNode
        assertEquals(IdentifierNode("isValid"), ternary.condition)
        assertEquals(IntLiteralNode(1), ternary.thenExpr)
        assertEquals(IntLiteralNode(0), ternary.elseExpr)
    }

    @Test
    fun `parse type check operators`() {
        val isOp = ExprParser.parse("obj is String") as BinaryOpNode
        assertEquals(BinaryOp.IS, isOp.op)
        assertEquals(IdentifierNode("obj"), isOp.left)
        assertEquals(IdentifierNode("String"), isOp.right)

        val notIsOp = ExprParser.parse("obj !is java.util.List") as BinaryOpNode
        assertEquals(BinaryOp.NOT_IS, notIsOp.op)
        assertEquals(IdentifierNode("obj"), notIsOp.left)
        assertEquals(IdentifierNode("java.util.List"), notIsOp.right)

        val instanceOfOp = ExprParser.parse("obj instanceof Number") as BinaryOpNode
        assertEquals(BinaryOp.INSTANCE_OF, instanceOfOp.op)
    }

    @Test
    fun `parse typecast expressions`() {
        val unsafeCast = ExprParser.parse("user as Admin") as TypeCastNode
        assertEquals(IdentifierNode("user"), unsafeCast.expr)
        assertEquals("Admin", unsafeCast.targetType)
        assertFalse(unsafeCast.isSafe)

        val safeCast = ExprParser.parse("user as? com.example.Admin") as TypeCastNode
        assertEquals(IdentifierNode("user"), safeCast.expr)
        assertEquals("com.example.Admin", safeCast.targetType)
        assertTrue(safeCast.isSafe)

        val memberAccessCast = ExprParser.parse("(user as Admin).permissions") as MemberAccessNode
        val innerCast = memberAccessCast.target as TypeCastNode
        assertEquals("Admin", innerCast.targetType)
        assertFalse(innerCast.isSafe)
        assertEquals("permissions", memberAccessCast.memberName)

        val safeMemberAccessCast = ExprParser.parse("(user as? Admin)?.permissions") as MemberAccessNode
        val safeInnerCast = safeMemberAccessCast.target as TypeCastNode
        assertTrue(safeInnerCast.isSafe)
        assertTrue(safeMemberAccessCast.isSafe)
        assertEquals("permissions", safeMemberAccessCast.memberName)

        val genericCast = ExprParser.parse("items as List<String>") as TypeCastNode
        assertEquals("List<String>", genericCast.targetType)

        val arrayCast = ExprParser.parse("data as String[]") as TypeCastNode
        assertEquals("String[]", arrayCast.targetType)

        val elvisCast = ExprParser.parse("user as? Admin ?: defaultUser") as BinaryOpNode
        assertEquals(BinaryOp.ELVIS, elvisCast.op)
        val leftCast = elvisCast.left as TypeCastNode
        assertEquals("Admin", leftCast.targetType)
        assertTrue(leftCast.isSafe)

        val chainedCast = ExprParser.parse("x as Any as String") as TypeCastNode
        assertEquals("String", chainedCast.targetType)
        val innerAnyCast = chainedCast.expr as TypeCastNode
        assertEquals("Any", innerAnyCast.targetType)

        val nestedGenericCast = ExprParser.parse("map as Map<String, List<Int>>") as TypeCastNode
        assertEquals("Map<String, List<Int>>", nestedGenericCast.targetType)

        val multiDimArrayCast = ExprParser.parse("grid as Int[][]") as TypeCastNode
        assertEquals("Int[][]", multiDimArrayCast.targetType)

        val nullableTypeCast = ExprParser.parse("obj as String?") as TypeCastNode
        assertEquals("String?", nullableTypeCast.targetType)
        assertFalse(nullableTypeCast.isSafe)

        val safeNullableTypeCast = ExprParser.parse("obj as? String?") as TypeCastNode
        assertEquals("String?", safeNullableTypeCast.targetType)
        assertTrue(safeNullableTypeCast.isSafe)
    }

    @Test
    fun `parse typecast syntax errors throw DebugException`() {
        assertThrows(DebugException::class.java) {
            ExprParser.parse("user as")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("user as?")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("user as List<String")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("user as Int[")
        }
    }

    @Test
    fun `parse syntax error throws DebugException`() {
        assertThrows(DebugException::class.java) {
            ExprParser.parse("amount >=")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("\"unclosed string")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("a + ")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("(1 + 2")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("items[0")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("a ? b")
        }
        assertThrows(DebugException::class.java) {
            ExprParser.parse("1 + 2 3")
        }
    }

    @Test
    fun `parse unary operations combined with type casting and arithmetic`() {
        val castNeg = ExprParser.parse("-x as Double") as TypeCastNode
        assertEquals("Double", castNeg.targetType)
        assertTrue(castNeg.expr is UnaryOpNode)
        assertEquals(UnaryOp.NEGATE, (castNeg.expr as UnaryOpNode).op)

        val castNot = ExprParser.parse("!flag as Boolean") as TypeCastNode
        assertEquals("Boolean", castNot.targetType)
        assertTrue(castNot.expr is UnaryOpNode)
        assertEquals(UnaryOp.NOT, (castNot.expr as UnaryOpNode).op)

        val multNeg = ExprParser.parse("-x * 2") as BinaryOpNode
        assertEquals(BinaryOp.MULTIPLY, multNeg.op)
        assertTrue(multNeg.left is UnaryOpNode)
    }
}
