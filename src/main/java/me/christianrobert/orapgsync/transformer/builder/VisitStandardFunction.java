package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Visitor helper for standard_function grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * standard_function
 *     : string_function
 *     | numeric_function_wrapper
 *     | json_function
 *     | other_function
 * </pre>
 */
public class VisitStandardFunction {

    public static String v(PlSqlParser.Standard_functionContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Standard_functionContext cannot be null");
        }

        // Route to string_function
        if (ctx.string_function() != null) {
            return b.visit(ctx.string_function());
        }

        // Route to numeric_function_wrapper (aggregate functions: COUNT, SUM, AVG, MIN, MAX, etc.)
        if (ctx.numeric_function_wrapper() != null) {
            return b.visit(ctx.numeric_function_wrapper());
        }

        // Route to json_function
        if (ctx.json_function() != null) {
            throw new TransformationException(
                "JSON functions not yet supported in current implementation");
        }

        // Route to other_function
        if (ctx.other_function() != null) {
            return b.visit(ctx.other_function());
        }

        throw new TransformationException(
            "Unknown standard_function type - no recognized function branch found");
    }
}
