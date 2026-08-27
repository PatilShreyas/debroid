package dev.shreyaspatil.debroid.jdi.eval

import dev.shreyaspatil.debroid.adb.DebugException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExprLexerTest {

    @Test
    fun `tokenize integer formats`() {
        val tokens = ExprLexer("42 0xFF 0b1010").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.IntLit(42),
                Token.IntLit(255),
                Token.IntLit(10),
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize long literals`() {
        val tokens = ExprLexer("100L 0x10L 0b11L").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.LongLit(100L),
                Token.LongLit(16L),
                Token.LongLit(3L),
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize floating point numbers`() {
        val tokens = ExprLexer("3.14f .5f 2.5 1e3 1e-3 1.5e+2").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.FloatLit(3.14f),
                Token.FloatLit(0.5f),
                Token.DoubleLit(2.5),
                Token.DoubleLit(1000.0),
                Token.DoubleLit(0.001),
                Token.DoubleLit(150.0),
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize string literals with escape sequences and unicode`() {
        val input = """"hello \n \t \r \" \\ \u0041""""
        val tokens = ExprLexer(input).tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.StringLit("hello \n \t \r \" \\ A"),
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize char literals with escape sequences`() {
        val tokens = ExprLexer("'a' '\\n' '\\'' '\\\\'").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.CharLit('a'),
                Token.CharLit('\n'),
                Token.CharLit('\''),
                Token.CharLit('\\'),
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize backticked identifiers`() {
        val tokens = ExprLexer("`order-id` `special property`").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.Ident("order-id"),
                Token.Ident("special property"),
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize keywords and type check operators`() {
        val tokens = ExprLexer("this super is !is instanceof true false null").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.This,
                Token.Super,
                Token.Is,
                Token.NotIs,
                Token.InstanceOf,
                Token.BooleanLit(true),
                Token.BooleanLit(false),
                Token.NullLit,
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize all arithmetic bitwise and shift operators`() {
        val tokens = ExprLexer("+ - * / % & | ^ ~ << >> >>>").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.Plus,
                Token.Minus,
                Token.Star,
                Token.Slash,
                Token.Percent,
                Token.Amp,
                Token.Pipe,
                Token.Caret,
                Token.Tilde,
                Token.LtLt,
                Token.GtGt,
                Token.GtGtGt,
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tokenize logical relational and punctuation tokens`() {
        val tokens = ExprLexer("&& || ! == != < <= > >= ?. ?: ? : . , ( ) [ ]").tokenize().map { it.token }
        assertEquals(
            listOf(
                Token.AndAnd,
                Token.OrOr,
                Token.Bang,
                Token.EqEq,
                Token.BangEq,
                Token.Lt,
                Token.LtEq,
                Token.Gt,
                Token.GtEq,
                Token.QuestionDot,
                Token.QuestionColon,
                Token.Question,
                Token.Colon,
                Token.Dot,
                Token.Comma,
                Token.LParen,
                Token.RParen,
                Token.LBracket,
                Token.RBracket,
                Token.Eof
            ),
            tokens
        )
    }

    @Test
    fun `tracks correct token position`() {
        val tokenPositions = ExprLexer("  foo  +  bar").tokenize()
        assertEquals(Token.Ident("foo"), tokenPositions[0].token)
        assertEquals(2, tokenPositions[0].position)

        assertEquals(Token.Plus, tokenPositions[1].token)
        assertEquals(7, tokenPositions[1].position)

        assertEquals(Token.Ident("bar"), tokenPositions[2].token)
        assertEquals(10, tokenPositions[2].position)
    }

    @Test
    fun `throws on unterminated string`() {
        val ex = assertThrows(DebugException::class.java) {
            ExprLexer("\"unterminated").tokenize()
        }
        assertTrue(ex.message?.contains("Unterminated string") == true)
    }

    @Test
    fun `throws on unterminated backticked identifier`() {
        val ex = assertThrows(DebugException::class.java) {
            ExprLexer("`unterminated").tokenize()
        }
        assertTrue(ex.message?.contains("Unclosed backtick") == true)
    }

    @Test
    fun `throws on empty char literal`() {
        assertThrows(DebugException::class.java) {
            ExprLexer("''").tokenize()
        }
    }

    @Test
    fun `throws on unclosed char literal`() {
        assertThrows(DebugException::class.java) {
            ExprLexer("'a").tokenize()
        }
    }

    @Test
    fun `throws on invalid unicode escape`() {
        assertThrows(DebugException::class.java) {
            ExprLexer("\"\\u12\"").tokenize()
        }
    }

    @Test
    fun `throws on unexpected character`() {
        assertThrows(DebugException::class.java) {
            ExprLexer("@").tokenize()
        }
    }
}
