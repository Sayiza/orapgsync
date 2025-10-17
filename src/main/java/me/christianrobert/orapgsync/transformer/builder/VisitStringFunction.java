package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

/**
 * Visitor helper for string_function grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * string_function
 *     : SUBSTR '(' expression ',' expression (',' expression)? ')'
 *     | TO_CHAR '(' (table_element | standard_function | expression) (',' quoted_string)? (',' quoted_string)? ')'
 *     | DECODE '(' expressions_ ')'
 *     | CHR '(' concatenation USING NCHAR_CS ')'
 *     | NVL '(' expression ',' expression ')'
 *     | TRIM '(' ((LEADING | TRAILING | BOTH)? expression? FROM)? concatenation ')'
 *     | TO_DATE '(' (table_element | standard_function | expression) (DEFAULT concatenation ON CONVERSION ERROR)? (',' quoted_string (',' quoted_string)?)? ')'
 * </pre>
 */
public class VisitStringFunction {

    public static String v(PlSqlParser.String_functionContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("String_functionContext cannot be null");
        }

        // NVL function: NVL '(' expression ',' expression ')'
        if (ctx.NVL() != null) {
            List<PlSqlParser.ExpressionContext> expressions = ctx.expression();
            if (expressions == null || expressions.size() != 2) {
                throw new TransformationException(
                    "NVL function requires exactly 2 expressions, found: " +
                    (expressions == null ? 0 : expressions.size()));
            }

            return " COALESC( " +
                    b.visit(expressions.get(0)) +
                    " , " +
                    b.visit(expressions.get(1)) +
                    " )";
        }

        // DECODE function
        if (ctx.DECODE() != null) {
            throw new TransformationException(
                "DECODE function not yet supported in current implementation");
        }

        // SUBSTR function
        if (ctx.SUBSTR() != null) {
            throw new TransformationException(
                "SUBSTR function not yet supported in current implementation");
        }

        // TO_CHAR function
        if (ctx.TO_CHAR() != null) {
            throw new TransformationException(
                "TO_CHAR function not yet supported in current implementation");
        }

        // TO_DATE function
        if (ctx.TO_DATE() != null) {
            throw new TransformationException(
                "TO_DATE function not yet supported in current implementation");
        }

        // TRIM function
        if (ctx.TRIM() != null) {
            throw new TransformationException(
                "TRIM function not yet supported in current implementation");
        }

        // CHR function
        if (ctx.CHR() != null) {
            throw new TransformationException(
                "CHR function not yet supported in current implementation");
        }

        throw new TransformationException(
            "Unknown string_function type - no recognized function found");
    }
}
