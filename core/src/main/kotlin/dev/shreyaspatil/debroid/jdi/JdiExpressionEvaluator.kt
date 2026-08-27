package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.StackFrame
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import dev.shreyaspatil.debroid.jdi.eval.ExprParser
import dev.shreyaspatil.debroid.jdi.eval.JdiAstEvaluator

/**
 * Expression evaluation entry point for Debroid JDI sessions.
 *
 * Parses expressions into an AST and evaluates them in the context of the suspended
 * thread's active [StackFrame] and target [VirtualMachine].
 */
object JdiExpressionEvaluator {
    @JvmStatic
    fun evaluate(expr: String, vm: VirtualMachine, frame: StackFrame): Value? {
        val ast = ExprParser.parse(expr)
        return JdiAstEvaluator.evaluate(ast, vm, frame)
    }
}
