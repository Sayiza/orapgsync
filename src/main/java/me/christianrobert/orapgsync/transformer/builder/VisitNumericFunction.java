package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Visitor helper for numeric_function grammar rule.
 *
 * <p><b>Key Insight:</b> Most numeric functions (COUNT, SUM, AVG, MIN, MAX) have identical
 * syntax in Oracle and PostgreSQL, so we can pass them through as-is.
 *
 * <p><b>Strategy:</b> Pass through aggregate functions unchanged. Transform Oracle-specific
 * functions as needed.
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * numeric_function
 *     : SUM '(' (DISTINCT | ALL)? expression ')'
 *     | COUNT '(' (ASTERISK | ((DISTINCT | UNIQUE | ALL)? concatenation)?) ')' over_clause?
 *     | ROUND '(' expression (',' UNSIGNED_INTEGER)? ')'
 *     | AVG '(' (DISTINCT | ALL)? expression ')'
 *     | MAX '(' (DISTINCT | ALL)? expression ')'
 *     | LEAST '(' expressions_ ')'
 *     | GREATEST '(' expressions_ ')'
 * </pre>
 */
public class VisitNumericFunction {

    public static String v(PlSqlParser.Numeric_functionContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Numeric_functionContext cannot be null");
        }

        // SUM(expression) or SUM(DISTINCT expression) or SUM(ALL expression)
        if (ctx.SUM() != null) {
            PlSqlParser.ExpressionContext expression = ctx.expression();
            if (expression == null) {
                throw new TransformationException("SUM function requires an expression");
            }
            return buildAggregateFunction("SUM", ctx.DISTINCT(), ctx.ALL(), expression, b);
        }

        // COUNT(*) or COUNT(expression) or COUNT(DISTINCT expression)
        if (ctx.COUNT() != null) {
            StringBuilder result = new StringBuilder("COUNT( ");

            if (ctx.ASTERISK() != null) {
                result.append("* ");
            } else {
                // COUNT(concatenation) with optional DISTINCT/UNIQUE/ALL
                if (ctx.DISTINCT() != null) {
                    result.append("DISTINCT ");
                }
                // Note: UNIQUE is Oracle-specific, treat same as DISTINCT
                if (ctx.UNIQUE() != null) {
                    result.append("DISTINCT ");
                }
                // ALL is default, can be omitted in PostgreSQL

                // Transform the concatenation
                PlSqlParser.ConcatenationContext concatenation = ctx.concatenation();
                if (concatenation != null) {
                    result.append(b.visit(concatenation));
                    result.append(" ");
                }
            }

            result.append(")");

            // Window functions (OVER clause)
            if (ctx.over_clause() != null) {
                result.append(" ");
                result.append(VisitOverClause.v(ctx.over_clause(), b));
            }

            return result.toString();
        }

        // AVG(expression) or AVG(DISTINCT expression) or AVG(ALL expression)
        if (ctx.AVG() != null) {
            PlSqlParser.ExpressionContext expression = ctx.expression();
            if (expression == null) {
                throw new TransformationException("AVG function requires an expression");
            }
            return buildAggregateFunction("AVG", ctx.DISTINCT(), ctx.ALL(), expression, b);
        }

        // MAX(expression) or MAX(DISTINCT expression) or MAX(ALL expression)
        if (ctx.MAX() != null) {
            PlSqlParser.ExpressionContext expression = ctx.expression();
            if (expression == null) {
                throw new TransformationException("MAX function requires an expression");
            }
            return buildAggregateFunction("MAX", ctx.DISTINCT(), ctx.ALL(), expression, b);
        }

        // MIN not in numeric_function, but we'll handle it if it appears
        // (MIN is likely in other_function)

        // ROUND(expression) or ROUND(expression, precision)
        if (ctx.ROUND() != null) {
            PlSqlParser.ExpressionContext expression = ctx.expression();
            if (expression == null) {
                throw new TransformationException("ROUND function requires an expression");
            }

            // Use type evaluator to determine if this is date or numeric ROUND
            // Note: getContext() may be null in tests without full setup
            me.christianrobert.orapgsync.transformer.type.TypeInfo exprType =
                (b.getContext() != null)
                    ? b.getContext().getTypeEvaluator().getType(expression)
                    : me.christianrobert.orapgsync.transformer.type.TypeInfo.UNKNOWN;

            // If type is DATE, delegate to DateFunctionTransformer
            if (exprType.isDate()) {
                // Transform the expression first
                String transformedExpr = b.visit(expression);

                // Determine format string (default: DD for day rounding)
                String format = "DD";
                if (ctx.UNSIGNED_INTEGER() != null) {
                    // If there's a second argument, it's a format string or precision
                    // For dates, we expect format strings like 'MM', 'YYYY', etc.
                    // But in numeric_function grammar, the second arg is UNSIGNED_INTEGER
                    // This means format-based date ROUND won't come through here
                    // So we use default 'DD' (day rounding)
                }

                // Delegate to DateFunctionTransformer for date ROUND logic
                return me.christianrobert.orapgsync.transformer.builder.functions.DateFunctionTransformer
                    .buildDateRoundExpression(transformedExpr, format);
            }

            // Numeric ROUND: transform with type-aware cast
            String transformedExpr = b.visit(expression);

            // If type is known and numeric, no cast needed
            // If type is unknown or non-numeric, add defensive cast
            String argWithCast;
            if (exprType.isNumeric()) {
                argWithCast = transformedExpr;  // No cast needed
            } else {
                argWithCast = transformedExpr + "::numeric";  // Defensive cast
            }

            StringBuilder result = new StringBuilder("ROUND( ");
            result.append(argWithCast);

            // Optional precision parameter
            if (ctx.UNSIGNED_INTEGER() != null) {
                result.append(" , ");
                result.append(ctx.UNSIGNED_INTEGER().getText());
            }

            result.append(" )");
            return result.toString();
        }

        // LEAST(expressions) - identical in Oracle and PostgreSQL
        if (ctx.LEAST() != null) {
            return buildMultiArgumentFunction("LEAST", ctx.expressions_(), b);
        }

        // GREATEST(expressions) - identical in Oracle and PostgreSQL
        if (ctx.GREATEST() != null) {
            return buildMultiArgumentFunction("GREATEST", ctx.expressions_(), b);
        }

        throw new TransformationException(
            "Unknown numeric_function type - no recognized function branch found");
    }

    /**
     * Builds aggregate function with optional DISTINCT/ALL modifier.
     *
     * @param functionName Name of the function (SUM, AVG, MAX, etc.)
     * @param distinct DISTINCT token (may be null)
     * @param all ALL token (may be null)
     * @param expression Expression to aggregate
     * @param b Builder for recursive transformation
     * @return Transformed function call
     */
    private static String buildAggregateFunction(
            String functionName,
            org.antlr.v4.runtime.tree.TerminalNode distinct,
            org.antlr.v4.runtime.tree.TerminalNode all,
            PlSqlParser.ExpressionContext expression,
            PostgresCodeBuilder b) {

        StringBuilder result = new StringBuilder(functionName);
        result.append("( ");

        if (distinct != null) {
            result.append("DISTINCT ");
        }
        // ALL is default, can be omitted in PostgreSQL

        result.append(b.visit(expression));
        result.append(" )");

        return result.toString();
    }

    /**
     * Builds function with multiple arguments (LEAST, GREATEST).
     *
     * @param functionName Name of the function
     * @param expressions List of expressions
     * @param b Builder for recursive transformation
     * @return Transformed function call
     */
    private static String buildMultiArgumentFunction(
            String functionName,
            PlSqlParser.Expressions_Context expressions,
            PostgresCodeBuilder b) {

        StringBuilder result = new StringBuilder(functionName);
        result.append("( ");

        if (expressions != null) {
            result.append(b.visit(expressions));
        }

        result.append(" )");
        return result.toString();
    }
}
