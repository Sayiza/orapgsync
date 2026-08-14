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
 *   <li>TRANSLATE (identical in Oracle and PostgreSQL)</li>
 *   <li>TO_NUMBER, TO_TIMESTAMP, TO_TIMESTAMP_TZ (cast or two-argument form)</li>
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

            // Window functions (OVER clause)
            if (ctx.over_clause() != null) {
                result.append(" ");
                result.append(VisitOverClause.v(ctx.over_clause(), b));
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

        // within_or_over_clause_keyword function_argument within_or_over_part+
        // This handles: RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST, PERCENTILE_CONT, PERCENTILE_DISC
        if (ctx.within_or_over_clause_keyword() != null) {
            PlSqlParser.Within_or_over_clause_keywordContext keyword = ctx.within_or_over_clause_keyword();
            String functionName = keyword.getText();

            StringBuilder result = new StringBuilder(functionName);
            result.append("( ");

            // Get the function argument
            PlSqlParser.Function_argumentContext argument = ctx.function_argument();
            if (argument != null) {
                result.append(b.visit(argument));
            }

            result.append(" )");

            // Handle within_or_over_part (WITHIN GROUP or OVER clause)
            // For now, we expect OVER clause for window functions
            java.util.List<PlSqlParser.Within_or_over_partContext> withinOrOverParts = ctx.within_or_over_part();
            if (withinOrOverParts != null && !withinOrOverParts.isEmpty()) {
                for (PlSqlParser.Within_or_over_partContext part : withinOrOverParts) {
                    if (part.over_clause() != null) {
                        result.append(" ");
                        result.append(VisitOverClause.v(part.over_clause(), b));
                    } else if (part.order_by_clause() != null) {
                        // WITHIN GROUP (ORDER BY ...)
                        result.append(" WITHIN GROUP ( ");
                        result.append(VisitOrderByClause.v(part.order_by_clause(), b));
                        result.append(" )");
                    }
                }
            }

            return result.toString();
        }

        // (FIRST_VALUE | LAST_VALUE) function_argument_analytic respect_or_ignore_nulls? over_clause
        if (ctx.FIRST_VALUE() != null || ctx.LAST_VALUE() != null) {
            String functionName = ctx.FIRST_VALUE() != null ? "FIRST_VALUE" : "LAST_VALUE";

            StringBuilder result = new StringBuilder(functionName);
            result.append("( ");

            // Get the function argument
            PlSqlParser.Function_argument_analyticContext argument = ctx.function_argument_analytic();
            if (argument != null) {
                result.append(b.visit(argument));
            }

            result.append(" )");

            // respect_or_ignore_nulls (optional) - e.g., RESPECT NULLS or IGNORE NULLS
            // PostgreSQL also supports this, so pass through
            PlSqlParser.Respect_or_ignore_nullsContext respectIgnore = ctx.respect_or_ignore_nulls();
            if (respectIgnore != null) {
                result.append(" ");
                result.append(respectIgnore.getText());
            }

            // OVER clause (required for FIRST_VALUE/LAST_VALUE)
            if (ctx.over_clause() != null) {
                result.append(" ");
                result.append(VisitOverClause.v(ctx.over_clause(), b));
            }

            return result.toString();
        }

        // (LEAD | LAG) function_argument_analytic respect_or_ignore_nulls? over_clause
        if (ctx.LEAD() != null || ctx.LAG() != null) {
            String functionName = ctx.LEAD() != null ? "LEAD" : "LAG";

            StringBuilder result = new StringBuilder(functionName);
            result.append("( ");

            // Get the function argument (may include offset and default value)
            PlSqlParser.Function_argument_analyticContext argument = ctx.function_argument_analytic();
            if (argument != null) {
                result.append(b.visit(argument));
            }

            result.append(" )");

            // respect_or_ignore_nulls (optional)
            PlSqlParser.Respect_or_ignore_nullsContext respectIgnore = ctx.respect_or_ignore_nulls();
            if (respectIgnore != null) {
                result.append(" ");
                result.append(respectIgnore.getText());
            }

            // OVER clause (required for LEAD/LAG)
            if (ctx.over_clause() != null) {
                result.append(" ");
                result.append(VisitOverClause.v(ctx.over_clause(), b));
            }

            return result.toString();
        }

        // (TO_BINARY_DOUBLE | TO_BINARY_FLOAT | TO_NUMBER | TO_TIMESTAMP | TO_TIMESTAMP_TZ) '(' concatenation
        //     (DEFAULT concatenation ON CONVERSION ERROR)? (',' quoted_string (',' quoted_string)?)? ')'
        //
        // Only TO_NUMBER, TO_TIMESTAMP and TO_TIMESTAMP_TZ are implemented; the TO_BINARY_* variants
        // fall through to the catch-all below until a real schema needs them.
        if (ctx.TO_NUMBER() != null || ctx.TO_TIMESTAMP() != null || ctx.TO_TIMESTAMP_TZ() != null) {
            return conversionFunction(ctx, b);
        }

        // TRANSLATE '(' expression (USING (CHAR_CS | NCHAR_CS))? (',' expression)* ')'
        // TRANSLATE(str, from, to) - character-by-character replacement
        // Identical syntax in Oracle and PostgreSQL (for basic form)
        if (ctx.TRANSLATE() != null) {
            StringBuilder result = new StringBuilder("TRANSLATE( ");

            // Get expressions (should be: str, from, to)
            java.util.List<PlSqlParser.ExpressionContext> expressions = ctx.expression();
            if (expressions != null && !expressions.isEmpty()) {
                for (int i = 0; i < expressions.size(); i++) {
                    if (i > 0) {
                        result.append(" , ");
                    }
                    result.append(b.visit(expressions.get(i)));
                }
            }

            result.append(" )");
            return result.toString();
        }

        // cursor_name (PERCENT_ISOPEN | PERCENT_FOUND | PERCENT_NOTFOUND | PERCENT_ROWCOUNT)
        // Oracle cursor attributes → PostgreSQL tracking variables or expressions
        //
        // Two types of cursors:
        // 1. Explicit cursors (named cursors with OPEN/FETCH/CLOSE):
        //    %FOUND → cursor__found
        //    %NOTFOUND → NOT cursor__found
        //    %ROWCOUNT → cursor__rowcount
        //    %ISOPEN → cursor__isopen
        //
        // 2. Implicit cursor (SQL%):
        //    SQL%FOUND → (sql__rowcount > 0)
        //    SQL%NOTFOUND → (sql__rowcount = 0)
        //    SQL%ROWCOUNT → sql__rowcount
        //    SQL%ISOPEN → FALSE (implicit cursor always closed in Oracle)
        if (ctx.cursor_name() != null) {
            String cursorName = ctx.cursor_name().getText();

            // Special handling for SQL% implicit cursor attributes
            if (cursorName.equalsIgnoreCase("SQL")) {
                // Register SQL cursor usage (triggers sql__rowcount variable generation)
                b.registerCursorAttributeUsage("sql");

                // Transform SQL% attributes
                // Note: sql__rowcount will be updated via GET DIAGNOSTICS after DML/SELECT INTO
                if (ctx.PERCENT_FOUND() != null) {
                    return "(sql__rowcount > 0)";
                } else if (ctx.PERCENT_NOTFOUND() != null) {
                    return "(sql__rowcount = 0)";
                } else if (ctx.PERCENT_ROWCOUNT() != null) {
                    return "sql__rowcount";
                } else if (ctx.PERCENT_ISOPEN() != null) {
                    // Oracle SQL%ISOPEN always returns FALSE (implicit cursor always closed)
                    return "FALSE";
                }
            }

            // Explicit cursor attributes (existing code)
            b.registerCursorAttributeUsage(cursorName);

            // Transform based on attribute type
            if (ctx.PERCENT_FOUND() != null) {
                return cursorName + "__found";
            } else if (ctx.PERCENT_NOTFOUND() != null) {
                return "NOT " + cursorName + "__found";
            } else if (ctx.PERCENT_ROWCOUNT() != null) {
                return cursorName + "__rowcount";
            } else if (ctx.PERCENT_ISOPEN() != null) {
                return cursorName + "__isopen";
            }
        }

        // For any other function in other_function, throw descriptive error
        // This helps identify which functions need to be implemented next
        throw new TransformationException(
            "Other function not yet supported in current implementation. " +
            "Function context: " + ctx.getText().substring(0, Math.min(50, ctx.getText().length())));
    }

    /**
     * Transforms the TO_NUMBER / TO_TIMESTAMP / TO_TIMESTAMP_TZ alternative of other_function.
     *
     * <p>Oracle allows these conversion functions with a single argument, PostgreSQL does not —
     * {@code to_number} and {@code to_timestamp} only exist in their two-argument (value, format)
     * form. The single-argument case therefore becomes a cast:</p>
     *
     * <table>
     *   <tr><th>Oracle</th><th>PostgreSQL</th></tr>
     *   <tr><td>{@code TO_NUMBER(x)}</td><td>{@code ( x )::numeric}</td></tr>
     *   <tr><td>{@code TO_NUMBER(x, 'fmt')}</td><td>{@code TO_NUMBER( ( x )::text , 'fmt' )}</td></tr>
     *   <tr><td>{@code TO_TIMESTAMP(x)}</td><td>{@code ( x )::timestamp}</td></tr>
     *   <tr><td>{@code TO_TIMESTAMP(x, 'fmt')}</td><td>{@code TO_TIMESTAMP( ( x )::text , 'fmt' )}</td></tr>
     * </table>
     *
     * <p>The {@code ::text} cast on the value is not optional — PostgreSQL's two-argument forms
     * take {@code text}, while the Oracle argument is frequently a {@code VARCHAR2} column that
     * maps to {@code text} but may also be numeric or a date.</p>
     *
     * <p><b>Number format models are passed through unchanged.</b> Oracle's {@code 9 0 . , G D S
     * PR MI FM} are compatible with PostgreSQL; {@code L}, {@code C}, {@code V}, {@code EEEE} and
     * {@code NLS_NUMERIC_CHARACTERS} are not, and are left to fail loudly at {@code CREATE VIEW}
     * with PostgreSQL's own message rather than being silently mistranslated. Date format models
     * go through the same translation as TO_DATE.</p>
     *
     * <p>The third argument (NLS parameters) is dropped, as it is for TO_CHAR and TO_DATE.
     * {@code DEFAULT ... ON CONVERSION ERROR} has no PostgreSQL equivalent and is rejected.</p>
     */
    private static String conversionFunction(PlSqlParser.Other_functionContext ctx, PostgresCodeBuilder b) {
        boolean isNumber = ctx.TO_NUMBER() != null;
        String functionName = isNumber ? "TO_NUMBER" : (ctx.TO_TIMESTAMP() != null ? "TO_TIMESTAMP" : "TO_TIMESTAMP_TZ");

        java.util.List<PlSqlParser.ConcatenationContext> concatenations = ctx.concatenation();
        if (concatenations == null || concatenations.isEmpty()) {
            throw new TransformationException(functionName + " function missing value expression");
        }

        // DEFAULT <expr> ON CONVERSION ERROR - Oracle-specific, no PostgreSQL equivalent.
        // Dropping it would silently change semantics from "substitute" to "raise", so reject.
        if (ctx.DEFAULT() != null) {
            throw new TransformationException(
                functionName + " with DEFAULT ... ON CONVERSION ERROR is not supported - "
                + "PostgreSQL has no equivalent for conversion error substitution");
        }

        String value = "( " + b.visit(concatenations.get(0)) + " )";

        java.util.List<PlSqlParser.Quoted_stringContext> quotedStrings = ctx.quoted_string();
        if (quotedStrings == null || quotedStrings.isEmpty()) {
            // Single-argument form: PostgreSQL has no such function, use a cast
            String targetType = isNumber ? "numeric"
                : (ctx.TO_TIMESTAMP() != null ? "timestamp" : "timestamptz");
            return value + "::" + targetType;
        }

        // Number format models are passed through; date format models get the TO_DATE treatment
        String format = quotedStrings.get(0).getText();
        if (!isNumber) {
            format = VisitStringFunction.transformToCharFormat(format);
        }

        // PostgreSQL has no TO_TIMESTAMP_TZ; its two-argument to_timestamp already returns
        // timestamptz, so both Oracle timestamp variants map onto it - as TO_DATE does.
        String pgFunction = isNumber ? "TO_NUMBER" : "TO_TIMESTAMP";

        // Note: a third argument (NLS params) is ignored - PostgreSQL doesn't support it
        return pgFunction + "( " + value + "::text , " + format + " )";
    }
}
