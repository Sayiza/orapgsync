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

            return "COALESCE( " +
                    b.visit(expressions.get(0)) +
                    " , " +
                    b.visit(expressions.get(1)) +
                    " )";
        }

        // DECODE function: DECODE '(' expressions_ ')'
        // Oracle: DECODE(expr, search1, result1, search2, result2, ..., default)
        // PostgreSQL: CASE expr WHEN search1 THEN result1 WHEN search2 THEN result2 ... ELSE default END
        if (ctx.DECODE() != null) {
            PlSqlParser.Expressions_Context exprsCtx = ctx.expressions_();
            if (exprsCtx == null) {
                throw new TransformationException("DECODE function missing expressions");
            }

            List<PlSqlParser.ExpressionContext> expressions = exprsCtx.expression();
            if (expressions == null || expressions.size() < 3) {
                throw new TransformationException(
                    "DECODE function requires at least 3 arguments (expr, search, result), found: " +
                    (expressions == null ? 0 : expressions.size()));
            }

            // First expression is the expression to evaluate
            String expr = b.visit(expressions.get(0));

            // Build CASE WHEN statement
            StringBuilder result = new StringBuilder("CASE ");
            result.append(expr);

            // Process search/result pairs
            // Format: expr, search1, result1, search2, result2, ..., [default]
            // Pairs start at index 1
            int argCount = expressions.size();
            boolean hasDefault = (argCount % 2 == 0); // Even count means we have a default value

            // Process WHEN/THEN pairs
            int pairCount = (argCount - 1) / 2; // Number of search/result pairs
            for (int i = 0; i < pairCount; i++) {
                int searchIdx = 1 + (i * 2);  // 1, 3, 5, 7, ...
                int resultIdx = searchIdx + 1; // 2, 4, 6, 8, ...

                String searchValue = b.visit(expressions.get(searchIdx));
                String resultValue = b.visit(expressions.get(resultIdx));

                result.append(" WHEN ");
                result.append(searchValue);
                result.append(" THEN ");
                result.append(resultValue);
            }

            // Add ELSE clause if we have a default value
            if (hasDefault) {
                int defaultIdx = argCount - 1;
                String defaultValue = b.visit(expressions.get(defaultIdx));
                result.append(" ELSE ");
                result.append(defaultValue);
            }

            result.append(" END");
            return result.toString();
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
