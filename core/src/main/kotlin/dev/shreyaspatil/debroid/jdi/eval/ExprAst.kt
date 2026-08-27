package dev.shreyaspatil.debroid.jdi.eval

/**
 * Represents a node in the expression Abstract Syntax Tree (AST).
 */
sealed interface ExprNode

/**
 * Literal AST nodes.
 */
data class IntLiteralNode(val value: Int) : ExprNode
data class LongLiteralNode(val value: Long) : ExprNode
data class FloatLiteralNode(val value: Float) : ExprNode
data class DoubleLiteralNode(val value: Double) : ExprNode
data class BooleanLiteralNode(val value: Boolean) : ExprNode
data class StringLiteralNode(val value: String) : ExprNode
data class CharLiteralNode(val value: Char) : ExprNode
data object NullLiteralNode : ExprNode

/**
 * Reference AST nodes.
 */
data class IdentifierNode(val name: String) : ExprNode
data object ThisNode : ExprNode
data object SuperNode : ExprNode

/**
 * Member and array access nodes.
 */
data class MemberAccessNode(
    val target: ExprNode,
    val memberName: String,
    val isSafe: Boolean = false
) : ExprNode

data class ArrayAccessNode(
    val target: ExprNode,
    val index: ExprNode
) : ExprNode

data class MethodCallNode(
    val target: ExprNode?,
    val methodName: String,
    val args: List<ExprNode>,
    val isSafe: Boolean = false
) : ExprNode

/**
 * Unary operations.
 */
enum class UnaryOp {
    NOT,
    NEGATE,
    PLUS,
    BITWISE_NOT
}

data class UnaryOpNode(
    val op: UnaryOp,
    val expr: ExprNode
) : ExprNode

/**
 * Binary operations.
 */
enum class BinaryOp {
    LOGICAL_OR,
    LOGICAL_AND,
    BITWISE_OR,
    BITWISE_XOR,
    BITWISE_AND,
    EQUALS,
    NOT_EQUALS,
    LESS_THAN,
    LESS_EQUAL,
    GREATER_THAN,
    GREATER_EQUAL,
    IS,
    NOT_IS,
    INSTANCE_OF,
    SHL,
    SHR,
    USHR,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MODULO,
    ELVIS
}

data class BinaryOpNode(
    val op: BinaryOp,
    val left: ExprNode,
    val right: ExprNode
) : ExprNode

/**
 * Ternary conditional operator (cond ? thenExpr : elseExpr).
 */
data class TernaryOpNode(
    val condition: ExprNode,
    val thenExpr: ExprNode,
    val elseExpr: ExprNode
) : ExprNode
