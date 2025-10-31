package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for OPEN statement (explicit cursor operations).
 *
 * <p>Oracle syntax:
 * <pre>
 * OPEN cursor_name;
 * OPEN cursor_name(param1, param2, ...);
 * </pre>
 *
 * <p>PostgreSQL PL/pgSQL: (identical syntax)
 * <pre>
 * OPEN cursor_name;
 * OPEN cursor_name(param1, param2, ...);
 * </pre>
 *
 * <p><b>Cursor Attribute Tracking:</b>
 * If the cursor uses cursor attributes (%FOUND, %NOTFOUND, %ROWCOUNT, %ISOPEN),
 * this visitor injects state update code after the OPEN statement:
 * <pre>
 * OPEN cursor_name;
 * cursor_name__isopen := TRUE;  -- Auto-injected tracking code
 * </pre>
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * open_statement
 *     : OPEN cursor_name ('(' expressions_? ')')?
 *     ;
 * </pre>
 */
public class VisitOpen_statement {

    public static String v(PlSqlParser.Open_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Open_statementContext cannot be null");
        }

        StringBuilder result = new StringBuilder();

        // OPEN cursor_name
        result.append("OPEN ");
        result.append(ctx.cursor_name().getText());

        // Optional parameters: ('(' expressions_? ')')?
        if (ctx.expressions_() != null) {
            result.append("( ");
            result.append(b.visit(ctx.expressions_()));
            result.append(" )");
        }

        result.append(";");

        // If cursor uses attributes, inject tracking variable update
        String cursorName = ctx.cursor_name().getText();
        if (b.cursorNeedsTracking(cursorName)) {
            result.append("\n  ");
            result.append(cursorName).append("__isopen := TRUE;");
        }

        return result.toString();
    }
}
