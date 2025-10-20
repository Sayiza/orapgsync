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

        // SUBSTR function: SUBSTR '(' expression ',' expression (',' expression)? ')'
        // Oracle: SUBSTR(string, position [, length])
        // PostgreSQL: SUBSTRING(string FROM position [FOR length])
        if (ctx.SUBSTR() != null) {
            List<PlSqlParser.ExpressionContext> expressions = ctx.expression();
            if (expressions == null || expressions.size() < 2 || expressions.size() > 3) {
                throw new TransformationException(
                    "SUBSTR function requires 2 or 3 expressions (string, position, [length]), found: " +
                    (expressions == null ? 0 : expressions.size()));
            }

            String stringExpr = b.visit(expressions.get(0));
            String positionExpr = b.visit(expressions.get(1));

            StringBuilder result = new StringBuilder("SUBSTRING( ");
            result.append(stringExpr);
            result.append(" FROM ");
            result.append(positionExpr);

            // Handle optional third argument (length)
            if (expressions.size() == 3) {
                String lengthExpr = b.visit(expressions.get(2));
                result.append(" FOR ");
                result.append(lengthExpr);
            }

            result.append(" )");
            return result.toString();
        }

        // TO_CHAR function: TO_CHAR '(' (table_element | standard_function | expression) (',' quoted_string)? (',' quoted_string)? ')'
        // Oracle: TO_CHAR(value, 'format', 'nls_params')
        // PostgreSQL: TO_CHAR(value, 'format')  -- NLS params not supported
        if (ctx.TO_CHAR() != null) {
            // Get the value expression
            // Grammar: TO_CHAR '(' (table_element | standard_function | expression) ...
            // The parser will match ONE of these alternatives
            String value = null;

            // Try table_element (e.g., simple column ref or qualified like schema.table.column)
            if (ctx.table_element() != null && ctx.table_element().getChildCount() > 0) {
                value = b.visit(ctx.table_element());
            }

            // Try standard_function (e.g., TO_CHAR(SYSDATE, ...))
            if (value == null && ctx.standard_function() != null && ctx.standard_function().getChildCount() > 0) {
                value = b.visit(ctx.standard_function());
            }

            // Try expression (e.g., TO_CHAR(empno + 1, ...))
            if (value == null && !ctx.expression().isEmpty()) {
                value = b.visit(ctx.expression().get(0));
            }

            if (value == null) {
                throw new TransformationException("TO_CHAR function missing value expression");
            }

            // Get the format string (if present)
            List<PlSqlParser.Quoted_stringContext> quotedStrings = ctx.quoted_string();
            String format = null;
            if (quotedStrings != null && !quotedStrings.isEmpty()) {
                // First quoted string is the format
                format = quotedStrings.get(0).getText();
                // Transform Oracle-specific format codes to PostgreSQL equivalents
                format = transformToCharFormat(format);
            }

            // Note: Third parameter (NLS params) is ignored - PostgreSQL doesn't support it
            // If there's a third parameter, we silently drop it (with a potential future warning)

            // Build the TO_CHAR call
            StringBuilder result = new StringBuilder("TO_CHAR( ");
            result.append(value);
            if (format != null) {
                result.append(" , ");
                result.append(format);
            }
            result.append(" )");

            return result.toString();
        }

        // TO_DATE function: TO_DATE '(' (table_element | standard_function | expression) (DEFAULT concatenation ON CONVERSION ERROR)? (',' quoted_string (',' quoted_string)?)? ')'
        // Oracle: TO_DATE(string, 'format', 'nls_params')
        // PostgreSQL: TO_TIMESTAMP(string, 'format')  -- NLS params not supported
        if (ctx.TO_DATE() != null) {
            // Get the value expression
            // Grammar: TO_DATE '(' (table_element | standard_function | expression) ...
            // The parser will match ONE of these alternatives
            String value = null;

            // Try table_element (e.g., simple column ref or qualified like schema.table.column)
            if (ctx.table_element() != null && ctx.table_element().getChildCount() > 0) {
                value = b.visit(ctx.table_element());
            }

            // Try standard_function (e.g., TO_DATE(SUBSTR(...), ...))
            if (value == null && ctx.standard_function() != null && ctx.standard_function().getChildCount() > 0) {
                value = b.visit(ctx.standard_function());
            }

            // Try expression (e.g., TO_DATE('2025-01-15', ...))
            if (value == null && !ctx.expression().isEmpty()) {
                value = b.visit(ctx.expression().get(0));
            }

            if (value == null) {
                throw new TransformationException("TO_DATE function missing value expression");
            }

            // Get the format string (if present)
            List<PlSqlParser.Quoted_stringContext> quotedStrings = ctx.quoted_string();
            String format = null;
            if (quotedStrings != null && !quotedStrings.isEmpty()) {
                // First quoted string is the format
                format = quotedStrings.get(0).getText();
                // Transform Oracle-specific format codes to PostgreSQL equivalents
                // Same transformations as TO_CHAR (date formats)
                format = transformToCharFormat(format);
            }

            // Note: DEFAULT ... ON CONVERSION ERROR clause is Oracle-specific and not supported in PostgreSQL
            // We silently drop it (error handling would need to be done differently in PostgreSQL)

            // Note: Third parameter (NLS params) is ignored - PostgreSQL doesn't support it
            // If there's a third parameter, we silently drop it

            // Build the TO_TIMESTAMP call
            StringBuilder result = new StringBuilder("TO_TIMESTAMP( ");
            result.append(value);
            if (format != null) {
                result.append(" , ");
                result.append(format);
            }
            result.append(" )");

            return result.toString();
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

    /**
     * Transforms Oracle TO_CHAR format string to PostgreSQL equivalent.
     *
     * <p>Most format codes are identical between Oracle and PostgreSQL,
     * but some Oracle-specific codes need transformation:
     *
     * <p>Date format transformations:
     * <ul>
     *   <li>RR → YY (2-digit year)</li>
     *   <li>RRRR → YYYY (4-digit year)</li>
     *   <li>IYY → IYYY (ISO year - PostgreSQL needs 4 digits)</li>
     * </ul>
     *
     * <p>Number format transformations:
     * <ul>
     *   <li>D → . (decimal point - Oracle locale-aware vs PostgreSQL literal)</li>
     *   <li>G → , (grouping separator - Oracle locale-aware vs PostgreSQL literal)</li>
     * </ul>
     *
     * <p>Format codes that work identically:
     * <ul>
     *   <li>YYYY, MM, DD, HH24, MI, SS (date/time)</li>
     *   <li>9, 0, FM, PR, S, MI, RN (numbers)</li>
     *   <li>Many others...</li>
     * </ul>
     *
     * <p>Known limitations:
     * <ul>
     *   <li>Case sensitivity: Oracle DAY→'MONDAY', PostgreSQL DAY→'MONDAY' (same), but Day→'Monday'</li>
     *   <li>Padding differences: Oracle pads day names, PostgreSQL doesn't always</li>
     *   <li>J (Julian day) - not supported in PostgreSQL, left as-is (may error)</li>
     * </ul>
     *
     * @param format Original Oracle format string (with quotes)
     * @return Transformed PostgreSQL format string (with quotes)
     */
    private static String transformToCharFormat(String format) {
        if (format == null) {
            return null;
        }

        // Format comes with quotes from the parser, e.g., 'YYYY-MM-DD'
        // We need to transform the content but preserve the quotes

        // Extract the content without quotes
        String content = format;
        boolean hasQuotes = false;
        if (format.startsWith("'") && format.endsWith("'") && format.length() >= 2) {
            content = format.substring(1, format.length() - 1);
            hasQuotes = true;
        }

        // Apply transformations (order matters - do longer patterns first)

        // Date format transformations
        content = content.replace("RRRR", "YYYY");  // 4-digit year
        content = content.replace("RR", "YY");      // 2-digit year
        // Note: IYY → IYYY transformation is tricky because we need to preserve IYYY
        // and only change IYY. Do IYYY first (no-op) then IYY
        if (!content.contains("IYYY")) {
            content = content.replace("IYY", "IYYY");  // ISO year needs 4 digits in PostgreSQL
        }

        // Number format transformations (locale-aware → literal)
        // Note: We need to be careful not to replace D in day names like 'DD' or 'DAY'
        // Strategy: Replace D only when it's likely a number format (surrounded by number format chars)
        // For now, do a simple replacement - might need refinement based on real-world usage
        content = replaceNumberFormatD(content);
        content = replaceNumberFormatG(content);

        // Restore quotes if they were present
        if (hasQuotes) {
            return "'" + content + "'";
        }
        return content;
    }

    /**
     * Replaces D (decimal point) in number formats with literal '.'.
     * Tries to avoid replacing D in date formats like DD or DAY.
     */
    private static String replaceNumberFormatD(String format) {
        // Simple heuristic: If format contains number format indicators (9, 0, $)
        // and D is surrounded by them, replace it
        // For common patterns like '999D99' or '0D00'
        // Note: We only look for 9, 0, $ (not comma, F, M) because those can appear in date formats
        if (format.matches(".*[90$].*D.*[90$].*")) {
            // Likely a number format
            // Use regex to replace D only when NOT preceded AND NOT followed by another D
            // This avoids transforming DD (day of month) to .. (two periods)
            return format.replaceAll("(?<!D)D(?!D)", ".");
        }
        // If D appears in isolation with number chars nearby, also replace
        if (format.matches(".*[90]D[90].*")) {
            // Use same regex to avoid DD transformation
            return format.replaceAll("(?<!D)D(?!D)", ".");
        }
        // Otherwise, leave it (probably a date format like DD or DAY)
        return format;
    }

    /**
     * Replaces G (grouping separator) in number formats with literal ','.
     * This is primarily used in number formats.
     */
    private static String replaceNumberFormatG(String format) {
        // G is almost always used in number formats, not date formats
        // Common patterns: '999G999' or '0G000'
        // Only look for 9, 0, $ as number format indicators
        if (format.matches(".*[90$].*")) {
            // Likely contains number format indicators
            return format.replace("G", ",");
        }
        return format;
    }
}
