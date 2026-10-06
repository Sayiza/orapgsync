package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for FETCH statement (explicit cursor operations).
 *
 * <p>Oracle syntax:
 * <pre>
 * FETCH cursor_name INTO var1, var2, ...;
 * FETCH cursor_name BULK COLLECT INTO collection1, ...;  -- Not yet supported
 * </pre>
 *
 * <p>PostgreSQL PL/pgSQL: (identical syntax for basic FETCH)
 * <pre>
 * FETCH cursor_name INTO var1, var2, ...;
 * </pre>
 *
 * <p><b>Cursor Attribute Tracking:</b>
 * If the cursor uses cursor attributes (%FOUND, %NOTFOUND, %ROWCOUNT, %ISOPEN),
 * this visitor injects state update code after the FETCH statement:
 * <pre>
 * FETCH cursor_name INTO var1, var2;
 * cursor_name__found := FOUND;  -- PostgreSQL's FOUND variable
 * IF cursor_name__found THEN
 *   cursor_name__rowcount := cursor_name__rowcount + 1;
 * END IF;
 * </pre>
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * fetch_statement
 *     : FETCH cursor_name (
 *         it1 = INTO variable_or_collection (',' variable_or_collection)*
 *         | BULK COLLECT INTO variable_or_collection (',' variable_or_collection)* (LIMIT expression)?
 *     )?
 *     ;
 * </pre>
 */
public class VisitFetch_statement {

    public static String v(PlSqlParser.Fetch_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Fetch_statementContext cannot be null");
        }

        // Check for BULK COLLECT (not yet supported)
        if (ctx.BULK() != null) {
            throw new me.christianrobert.orapgsync.transformer.context.TransformationException(
                "BULK COLLECT is not yet supported. " +
                "Use cursor FOR loop instead of explicit FETCH with BULK COLLECT.");
        }

        StringBuilder result = new StringBuilder();
        String cursorName = ctx.cursor_name().getText();

        // FETCH cursor_name INTO variables
        result.append("FETCH ");
        result.append(cursorName);
        result.append(" INTO ");

        // Get all variable_or_collection elements
        java.util.List<PlSqlParser.Variable_or_collectionContext> variables = ctx.variable_or_collection();
        if (variables == null || variables.isEmpty()) {
            throw new IllegalArgumentException(
                "FETCH statement must have INTO clause with at least one variable");
        }

        // Append variables (comma-separated)
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) {
                result.append(" , ");
            }
            result.append(b.visit(variables.get(i)));
        }

        result.append(";");

        // If cursor uses attributes, inject tracking variable updates
        if (b.cursorNeedsTracking(cursorName)) {
            // Update __found from PostgreSQL's FOUND variable
            result.append("\n  ");
            result.append(cursorName).append("__found := FOUND;");

            // Increment __rowcount if fetch succeeded
            result.append("\n  IF ");
            result.append(cursorName).append("__found THEN");
            result.append("\n    ");
            result.append(cursorName).append("__rowcount := ");
            result.append(cursorName).append("__rowcount + 1;");
            result.append("\n  END IF;");
        }

        return result.toString();
    }
}
