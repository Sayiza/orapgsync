package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Visitor helper for numeric_function_wrapper grammar rule.
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * numeric_function_wrapper
 *     : numeric_function (single_column_for_loop | multi_column_for_loop)?
 * </pre>
 *
 * <p>This wrapper exists to handle optional FOR loop constructs (Oracle-specific).
 * For basic aggregate functions, we just delegate to numeric_function.
 */
public class VisitNumericFunctionWrapper {

    public static String v(PlSqlParser.Numeric_function_wrapperContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Numeric_function_wrapperContext cannot be null");
        }

        // Delegate to numeric_function
        PlSqlParser.Numeric_functionContext numericFunction = ctx.numeric_function();
        if (numericFunction == null) {
            throw new TransformationException(
                "Numeric_function_wrapper missing numeric_function child");
        }

        String functionResult = b.visit(numericFunction);

        // Check for FOR loop constructs (not yet supported)
        if (ctx.single_column_for_loop() != null || ctx.multi_column_for_loop() != null) {
            throw new TransformationException(
                "FOR loop constructs in numeric functions not yet supported");
        }

        return functionResult;
    }
}
