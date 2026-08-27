package dev.shreyaspatil.debroid.jdi.eval

import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.models.ErrorCode

/**
 * Recursive-descent expression parser for Debroid.
 *
 * Implements operator precedence matching standard Kotlin and Java semantics:
 * 1. Ternary / Elvis (`?:`, `? :`)
 * 2. Logical OR (`||`)
 * 3. Logical AND (`&&`)
 * 4. Bitwise OR (`|`)
 * 5. Bitwise XOR (`^`)
 * 6. Bitwise AND (`&`)
 * 7. Equality (`==`, `!=`)
 * 8. Relational & Type Checks (`<`, `<=`, `>`, `>=`, `is`, `!is`, `instanceof`)
 * 9. Bit shifts (`<<`, `>>`, `>>>`)
 * 10. Additive (`+`, `-`)
 * 11. Multiplicative (`*`, `/`, `%`)
 * 12. Unary prefix (`!`, `-`, `+`, `~`)
 * 13. Postfix / Primary (member access `.` / `?.`, indexing `[]`, method invocation `()`, literals, identifiers)
 */
class ExprParser(private val tokens: List<TokenPos>) {
    private var current = 0

    companion object {
        /**
         * Parses the given [expr] string into an [ExprNode] AST root.
         *
         * @throws DebugException with [ErrorCode.EVALUATION_FAILED] on syntax or token errors.
         */
        fun parse(expr: String): ExprNode {
            val lexer = ExprLexer(expr)
            val tokenList = lexer.tokenize()
            val parser = ExprParser(tokenList)
            val ast = parser.parseExpression()
            if (!parser.isAtEnd()) {
                val remaining = parser.peek()
                throw DebugException(
                    ErrorCode.EVALUATION_FAILED,
                    "Unexpected token '${remaining.token}' at position ${remaining.position}"
                )
            }
            return ast
        }
    }

    /**
     * Parses the full expression starting from top-level precedence (ternary / Elvis).
     */
    fun parseExpression(): ExprNode = parseTernary()

    private fun parseTernary(): ExprNode {
        val expr = parseLogicalOr()

        if (match(Token.Question)) {
            val thenBranch = parseExpression()
            consume(Token.Colon, "Expected ':' in ternary expression")
            val elseBranch = parseExpression()
            return TernaryOpNode(expr, thenBranch, elseBranch)
        }

        if (match(Token.QuestionColon)) {
            val right = parseExpression()
            return BinaryOpNode(BinaryOp.ELVIS, expr, right)
        }

        return expr
    }

    private fun parseLogicalOr(): ExprNode {
        var left = parseLogicalAnd()
        while (match(Token.OrOr)) {
            val right = parseLogicalAnd()
            left = BinaryOpNode(BinaryOp.LOGICAL_OR, left, right)
        }
        return left
    }

    private fun parseLogicalAnd(): ExprNode {
        var left = parseBitwiseOr()
        while (match(Token.AndAnd)) {
            val right = parseBitwiseOr()
            left = BinaryOpNode(BinaryOp.LOGICAL_AND, left, right)
        }
        return left
    }

    private fun parseBitwiseOr(): ExprNode {
        var left = parseBitwiseXor()
        while (match(Token.Pipe)) {
            val right = parseBitwiseXor()
            left = BinaryOpNode(BinaryOp.BITWISE_OR, left, right)
        }
        return left
    }

    private fun parseBitwiseXor(): ExprNode {
        var left = parseBitwiseAnd()
        while (match(Token.Caret)) {
            val right = parseBitwiseAnd()
            left = BinaryOpNode(BinaryOp.BITWISE_XOR, left, right)
        }
        return left
    }

    private fun parseBitwiseAnd(): ExprNode {
        var left = parseEquality()
        while (match(Token.Amp)) {
            val right = parseEquality()
            left = BinaryOpNode(BinaryOp.BITWISE_AND, left, right)
        }
        return left
    }

    private fun parseEquality(): ExprNode {
        var left = parseRelational()
        while (true) {
            val op = when {
                match(Token.EqEq) -> BinaryOp.EQUALS
                match(Token.BangEq) -> BinaryOp.NOT_EQUALS
                else -> break
            }
            val right = parseRelational()
            left = BinaryOpNode(op, left, right)
        }
        return left
    }

    @Suppress("ComplexCondition")
    private fun parseRelational(): ExprNode {
        var left = parseShift()
        while (true) {
            val op = when {
                match(Token.Lt) -> BinaryOp.LESS_THAN
                match(Token.LtEq) -> BinaryOp.LESS_EQUAL
                match(Token.Gt) -> BinaryOp.GREATER_THAN
                match(Token.GtEq) -> BinaryOp.GREATER_EQUAL
                match(Token.Is) -> BinaryOp.IS
                match(Token.NotIs) -> BinaryOp.NOT_IS
                match(Token.InstanceOf) -> BinaryOp.INSTANCE_OF
                else -> break
            }

            val right = if (op == BinaryOp.IS || op == BinaryOp.NOT_IS || op == BinaryOp.INSTANCE_OF) {
                parseTypeName()
            } else {
                parseShift()
            }
            left = BinaryOpNode(op, left, right)
        }
        return left
    }

    private fun parseTypeName(): ExprNode {
        val sb = StringBuilder()
        val first = consumeIdent("Expected type name after type-check operator")
        sb.append(first)
        while (match(Token.Dot)) {
            sb.append(".")
            sb.append(consumeIdent("Expected identifier after '.' in type name"))
        }
        return IdentifierNode(sb.toString())
    }

    private fun parseShift(): ExprNode {
        var left = parseAdditive()
        while (true) {
            val op = when {
                match(Token.LtLt) -> BinaryOp.SHL
                match(Token.GtGt) -> BinaryOp.SHR
                match(Token.GtGtGt) -> BinaryOp.USHR
                else -> break
            }
            val right = parseAdditive()
            left = BinaryOpNode(op, left, right)
        }
        return left
    }

    private fun parseAdditive(): ExprNode {
        var left = parseMultiplicative()
        while (true) {
            val op = when {
                match(Token.Plus) -> BinaryOp.ADD
                match(Token.Minus) -> BinaryOp.SUBTRACT
                else -> break
            }
            val right = parseMultiplicative()
            left = BinaryOpNode(op, left, right)
        }
        return left
    }

    private fun parseMultiplicative(): ExprNode {
        var left = parseUnary()
        while (true) {
            val op = when {
                match(Token.Star) -> BinaryOp.MULTIPLY
                match(Token.Slash) -> BinaryOp.DIVIDE
                match(Token.Percent) -> BinaryOp.MODULO
                else -> break
            }
            val right = parseUnary()
            left = BinaryOpNode(op, left, right)
        }
        return left
    }

    private fun parseUnary(): ExprNode {
        return when {
            match(Token.Bang) -> UnaryOpNode(UnaryOp.NOT, parseUnary())
            match(Token.Minus) -> UnaryOpNode(UnaryOp.NEGATE, parseUnary())
            match(Token.Plus) -> UnaryOpNode(UnaryOp.PLUS, parseUnary())
            match(Token.Tilde) -> UnaryOpNode(UnaryOp.BITWISE_NOT, parseUnary())
            else -> parsePostfix()
        }
    }

    private fun parsePostfix(): ExprNode {
        var node = parsePrimary()

        while (true) {
            when {
                match(Token.Dot) -> {
                    val memberName = consumeIdent("Expected member name after '.'")
                    node = if (match(Token.LParen)) {
                        val args = parseArgumentList()
                        MethodCallNode(target = node, methodName = memberName, args = args, isSafe = false)
                    } else {
                        MemberAccessNode(target = node, memberName = memberName, isSafe = false)
                    }
                }
                match(Token.QuestionDot) -> {
                    val memberName = consumeIdent("Expected member name after '?.'")
                    node = if (match(Token.LParen)) {
                        val args = parseArgumentList()
                        MethodCallNode(target = node, methodName = memberName, args = args, isSafe = true)
                    } else {
                        MemberAccessNode(target = node, memberName = memberName, isSafe = true)
                    }
                }
                match(Token.LBracket) -> {
                    val index = parseExpression()
                    consume(Token.RBracket, "Expected ']' after array index")
                    node = ArrayAccessNode(target = node, index = index)
                }
                else -> break
            }
        }
        return node
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parsePrimary(): ExprNode {
        val tokenPos = advance()
        return when (val token = tokenPos.token) {
            is Token.IntLit -> IntLiteralNode(token.value)
            is Token.LongLit -> LongLiteralNode(token.value)
            is Token.FloatLit -> FloatLiteralNode(token.value)
            is Token.DoubleLit -> DoubleLiteralNode(token.value)
            is Token.BooleanLit -> BooleanLiteralNode(token.value)
            is Token.StringLit -> StringLiteralNode(token.value)
            is Token.CharLit -> CharLiteralNode(token.value)
            is Token.NullLit -> NullLiteralNode
            is Token.This -> ThisNode
            is Token.Super -> SuperNode
            is Token.Ident -> {
                if (match(Token.LParen)) {
                    val args = parseArgumentList()
                    MethodCallNode(target = null, methodName = token.name, args = args, isSafe = false)
                } else {
                    IdentifierNode(token.name)
                }
            }
            is Token.LParen -> {
                val expr = parseExpression()
                consume(Token.RParen, "Expected ')' after expression")
                expr
            }
            else -> throw DebugException(
                ErrorCode.EVALUATION_FAILED,
                "Unexpected token '$token' at position ${tokenPos.position}"
            )
        }
    }

    private fun parseArgumentList(): List<ExprNode> {
        val args = mutableListOf<ExprNode>()
        if (!check(Token.RParen)) {
            do {
                args.add(parseExpression())
            } while (match(Token.Comma))
        }
        consume(Token.RParen, "Expected ')' after argument list")
        return args
    }

    private fun consumeIdent(errorMessage: String): String {
        if (!isAtEnd()) {
            val tokenPos = peek()
            if (tokenPos.token is Token.Ident) {
                advance()
                return tokenPos.token.name
            }
        }
        val pos = if (isAtEnd()) tokens.last().position else peek().position
        throw DebugException(ErrorCode.EVALUATION_FAILED, "$errorMessage at position $pos")
    }

    private fun match(expected: Token): Boolean {
        if (check(expected)) {
            advance()
            return true
        }
        return false
    }

    private fun check(expected: Token): Boolean {
        if (isAtEnd()) return false
        return peek().token == expected
    }

    private fun consume(expected: Token, errorMessage: String): TokenPos {
        if (check(expected)) return advance()
        val pos = if (isAtEnd()) tokens.last().position else peek().position
        throw DebugException(ErrorCode.EVALUATION_FAILED, "$errorMessage at position $pos")
    }

    private fun advance(): TokenPos {
        if (!isAtEnd()) current++
        return tokens[current - 1]
    }

    private fun peek(): TokenPos = tokens[current]

    private fun isAtEnd(): Boolean = peek().token is Token.Eof
}
