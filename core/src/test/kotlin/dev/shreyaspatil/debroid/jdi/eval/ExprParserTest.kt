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
    fun `parse unary operations`() {
        val notNode = ExprParser.parse("!isValid") as UnaryOpNode
        assertEquals(UnaryOp.NOT, notNode.op)
        assertEquals(IdentifierNode("isValid"), notNode.expr)

        val negNode = ExprParser.parse("-42") as UnaryOpNode
        assertEquals(UnaryOp.NEGATE, negNode.op)
        assertEquals(IntLiteralNode(42), negNode.expr)
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
    }
}
