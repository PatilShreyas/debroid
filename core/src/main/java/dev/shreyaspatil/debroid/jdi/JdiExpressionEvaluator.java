package dev.shreyaspatil.debroid.jdi;

import com.sun.jdi.StackFrame;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.tools.example.debug.expr.ExpressionParser;

public class JdiExpressionEvaluator {
    public static Value evaluate(String expr, VirtualMachine vm, StackFrame frame) throws Exception {
        return ExpressionParser.evaluate(expr, vm, () -> frame);
    }
}
