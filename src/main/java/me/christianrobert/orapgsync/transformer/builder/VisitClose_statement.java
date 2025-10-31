package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for CLOSE statement (explicit cursor operations).
 *
 * <p>Oracle syntax:
 * <pre>
 * CLOSE cursor_name;
 * </pre>
 *
 * <p>PostgreSQL PL/pgSQL: (identical syntax)
 * <pre>
 * CLOSE cursor_name;
 * </pre>
 *
 * <p><b>Cursor Attribute Tracking:</b>
 * If the cursor uses cursor attributes (%FOUND, %NOTFOUND, %ROWCOUNT, %ISOPEN),
 * this visitor injects state update code after the CLOSE statement:
 * <pre>
 * CLOSE cursor_name;
 * cursor_name__isopen := FALSE;  -- Auto-injected tracking code
 * </pre>
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * close_statement
 *     : CLOSE cursor_name
 *     ;
 * </pre>
 */
public class VisitClose_statement {

    public static String v(PlSqlParser.Close_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Close_statementContext cannot be null");
        }

        StringBuilder result = new StringBuilder();
        String cursorName = ctx.cursor_name().getText();

        // CLOSE cursor_name
        result.append("CLOSE ");
        result.append(cursorName);
        result.append(";");

        // If cursor uses attributes, inject tracking variable update
        if (b.cursorNeedsTracking(cursorName)) {
            result.append("\n  ");
            result.append(cursorName).append("__isopen := FALSE;");
        }

        return result.toString();
    }
}
