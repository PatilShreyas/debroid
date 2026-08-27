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
    /**
     * Evaluates the given raw expression string against the specified [vm] and [frame].
     *
     * @param expr The expression string (e.g. `amount >= 100.0 && isExpress`, `user?.address?.city`).
     * @param vm The active JDI [VirtualMachine] instance.
     * @param frame The currently suspended [StackFrame] providing local scope and thread context.
     * @return The evaluated [Value] mirror, or `null` if the expression evaluates to null.
     * @throws dev.shreyaspatil.debroid.adb.DebugException with
     * [dev.shreyaspatil.debroid.models.ErrorCode.EVALUATION_FAILED] if parsing or evaluation fails.
     */
    @JvmStatic
    fun evaluate(expr: String, vm: VirtualMachine, frame: StackFrame): Value? {
        val ast = ExprParser.parse(expr)
        return JdiAstEvaluator.evaluate(ast, vm, frame)
    }
}
