package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor helper for function_argument_analytic grammar rule.
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * function_argument_analytic
 *     : '(' (argument respect_or_ignore_nulls? (',' argument respect_or_ignore_nulls?)*)? ')' keep_clause?
 *
 * argument
 *     : (identifier '=' '>')? expression
 * </pre>
 *
 * <p>This handles function arguments for analytic/aggregate functions.
 * For basic GROUP BY support, we just transform the arguments and ignore advanced clauses.
 */
public class VisitFunctionArgumentAnalytic {

    public static String v(PlSqlParser.Function_argument_analyticContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Function_argument_analyticContext cannot be null");
        }

        // Get all arguments
        List<PlSqlParser.ArgumentContext> arguments = ctx.argument();
        if (arguments == null || arguments.isEmpty()) {
            return ""; // No arguments (e.g., COUNT(*))
        }

        // Transform each argument
        List<String> transformedArgs = new ArrayList<>();
        for (PlSqlParser.ArgumentContext arg : arguments) {
            transformedArgs.add(transformArgument(arg, b));
        }

        // Check for advanced clauses not yet supported
        if (ctx.respect_or_ignore_nulls() != null && !ctx.respect_or_ignore_nulls().isEmpty()) {
            throw new TransformationException(
                "RESPECT/IGNORE NULLS clauses not yet supported");
        }

        if (ctx.keep_clause() != null) {
            throw new TransformationException(
                "KEEP clause not yet supported");
        }

        return String.join(" , ", transformedArgs);
    }

    /**
     * Transforms a single argument.
     *
     * @param arg Argument context
     * @param b Builder for recursive transformation
     * @return Transformed argument
     */
    private static String transformArgument(PlSqlParser.ArgumentContext arg, PostgresCodeBuilder b) {
        // Check for named argument (identifier '=>' expression)
        if (arg.identifier() != null) {
            throw new TransformationException(
                "Named arguments (identifier => expression) not yet supported");
        }

        // Transform the expression
        PlSqlParser.ExpressionContext expression = arg.expression();
        if (expression == null) {
            throw new TransformationException("Argument missing expression");
        }

        return b.visit(expression);
    }
}
