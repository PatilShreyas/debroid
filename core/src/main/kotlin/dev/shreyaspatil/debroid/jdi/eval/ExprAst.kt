package dev.shreyaspatil.debroid.jdi.eval

/**
 * Represents a node in the expression Abstract Syntax Tree (AST).
 */
sealed interface ExprNode

/**
 * Literal AST nodes representing primitive constants and strings.
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
 * Reference AST nodes referencing identifiers or context receivers in scope.
 */
data class IdentifierNode(val name: String) : ExprNode
data object ThisNode : ExprNode
data object SuperNode : ExprNode

/**
 * Member access AST node (e.g. `obj.property` or `obj?.property`).
 *
 * @property target The receiver expression.
 * @property memberName The property or field identifier name.
 * @property isSafe Whether safe navigation (`?.`) is used, short-circuiting on null receivers.
 */
data class MemberAccessNode(
    val target: ExprNode,
    val memberName: String,
    val isSafe: Boolean = false
) : ExprNode

/**
 * Array access AST node (e.g. `arr[index]`).
 *
 * @property target The array expression.
 * @property index The index expression to evaluate.
 */
data class ArrayAccessNode(
    val target: ExprNode,
    val index: ExprNode
) : ExprNode

/**
 * Method invocation AST node (e.g. `target.method(arg1, arg2)` or `method(arg1)`).
 *
 * @property target The receiver expression, or `null` if invoked implicitly on `this`.
 * @property methodName The name of the method to invoke.
 * @property args Evaluated argument expressions.
 * @property isSafe Whether safe navigation (`?.`) is used.
 */
data class MethodCallNode(
    val target: ExprNode?,
    val methodName: String,
    val args: List<ExprNode>,
    val isSafe: Boolean = false
) : ExprNode

/**
 * Unary operations supported in expressions.
 */
enum class UnaryOp {
    NOT,
    NEGATE,
    PLUS,
    BITWISE_NOT
}

/**
 * Unary operation AST node (e.g. `!flag`, `-count`, `~mask`).
 */
data class UnaryOpNode(
    val op: UnaryOp,
    val expr: ExprNode
) : ExprNode

/**
 * Binary operators supported across logical, bitwise, relational, and arithmetic operations.
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

/**
 * Binary operation AST node evaluating two operand expressions.
 */
data class BinaryOpNode(
    val op: BinaryOp,
    val left: ExprNode,
    val right: ExprNode
) : ExprNode

/**
 * Ternary conditional operator AST node (`condition ? thenExpr : elseExpr`).
 */
data class TernaryOpNode(
    val condition: ExprNode,
    val thenExpr: ExprNode,
    val elseExpr: ExprNode
) : ExprNode
