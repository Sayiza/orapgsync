package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Visitor helper for other_function grammar rule.
 *
 * <p><b>Note:</b> The other_function rule contains many Oracle-specific functions.
 * This visitor currently implements only the most common functions needed for basic
 * SQL transformation. Additional functions will be added as needed.
 *
 * <p><b>Currently supported:</b>
 * <ul>
 *   <li>MIN (aggregate function - may appear here in addition to numeric_function)</li>
 *   <li>COALESCE (identical in Oracle and PostgreSQL)</li>
 *   <li>EXTRACT (identical in Oracle and PostgreSQL)</li>
 * </ul>
 *
 * <p><b>Grammar rule excerpt:</b>
 * <pre>
 * other_function
 *     : over_clause_keyword function_argument_analytic over_clause?
 *     | regular_id function_argument_modeling using_clause?
 *     | COUNT '(' (ASTERISK | (DISTINCT | UNIQUE | ALL)? concatenation) ')' over_clause?
 *     | COALESCE '(' table_element (',' (numeric | quoted_string))? ')'
 *     | EXTRACT '(' regular_id FROM concatenation ')'
 *     | ... (many more Oracle-specific functions)
 * </pre>
 */
public class VisitOtherFunction {

    public static String v(PlSqlParser.Other_functionContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Other_functionContext cannot be null");
        }

        // over_clause_keyword function_argument_analytic over_clause?
        // This handles aggregate functions like MIN, MAX, AVG, SUM when used without window functions
        if (ctx.over_clause_keyword() != null) {
            PlSqlParser.Over_clause_keywordContext keyword = ctx.over_clause_keyword();
            String functionName = keyword.getText();

            StringBuilder result = new StringBuilder(functionName);
            result.append("( ");

            // Get the function argument
            PlSqlParser.Function_argument_analyticContext argument = ctx.function_argument_analytic();
            if (argument != null) {
                result.append(b.visit(argument));
            }

            result.append(" )");

            // Window functions (OVER clause) not yet supported
            if (ctx.over_clause() != null) {
                throw new TransformationException(
                    "Window functions (OVER clause) not yet supported");
            }

            return result.toString();
        }

        // COALESCE(expression, default_value) - identical in Oracle and PostgreSQL
        if (ctx.COALESCE() != null) {
            StringBuilder result = new StringBuilder("COALESCE( ");

            PlSqlParser.Table_elementContext tableElement = ctx.table_element();
            if (tableElement != null) {
                result.append(b.visit(tableElement));
            }

            // Optional second argument (default value)
            PlSqlParser.NumericContext numeric = ctx.numeric();
            if (numeric != null) {
                result.append(" , ");
                result.append(b.visit(numeric));
            } else {
                java.util.List<PlSqlParser.Quoted_stringContext> quotedStrings = ctx.quoted_string();
                if (quotedStrings != null && !quotedStrings.isEmpty()) {
                    result.append(" , ");
                    result.append(b.visit(quotedStrings.get(0)));
                }
            }

            result.append(" )");
            return result.toString();
        }

        // EXTRACT(field FROM source) - identical in Oracle and PostgreSQL
        if (ctx.EXTRACT() != null) {
            StringBuilder result = new StringBuilder("EXTRACT( ");

            PlSqlParser.Regular_idContext regularId = ctx.regular_id();
            if (regularId != null) {
                result.append(regularId.getText());
                result.append(" FROM ");
            }

            java.util.List<PlSqlParser.ConcatenationContext> concatenations = ctx.concatenation();
            if (concatenations != null && !concatenations.isEmpty()) {
                result.append(b.visit(concatenations.get(0)));
            }

            result.append(" )");
            return result.toString();
        }

        // For any other function in other_function, throw descriptive error
        // This helps identify which functions need to be implemented next
        throw new TransformationException(
            "Other function not yet supported in current implementation. " +
            "Function context: " + ctx.getText().substring(0, Math.min(50, ctx.getText().length())));
    }
}
