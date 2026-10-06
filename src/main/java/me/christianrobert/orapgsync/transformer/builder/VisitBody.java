package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL body (BEGIN...END block).
 *
 * Oracle structure:
 * BEGIN
 *   seq_of_statements
 * EXCEPTION
 *   exception_handler+ (optional)
 * END
 *
 * PostgreSQL PL/pgSQL:
 * BEGIN
 *   statements
 * EXCEPTION
 *   exception_handlers (optional)
 * END
 *
 * The structure is nearly identical, so this is mostly pass-through.
 */
public class VisitBody {

    public static String v(PlSqlParser.BodyContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // BEGIN keyword
        result.append("BEGIN\n");

        // Visit seq_of_statements (the main body content)
        if (ctx.seq_of_statements() != null) {
            String statements = b.visit(ctx.seq_of_statements());
            result.append(statements);
        }

        // EXCEPTION block (optional)
        if (ctx.exception_handler() != null && !ctx.exception_handler().isEmpty()) {
            result.append("EXCEPTION\n");
            for (PlSqlParser.Exception_handlerContext handler : ctx.exception_handler()) {
                String handlerCode = b.visit(handler);
                result.append(handlerCode);
            }
        }

        // END keyword (requires semicolon in PL/pgSQL)
        result.append("END;");

        // Note: Oracle allows labeled END blocks (e.g., END procedure_name;)
        // but PostgreSQL doesn't support this syntax for functions/procedures.
        // Labels are only supported in LOOP blocks in PostgreSQL.
        // Therefore, we omit the label even if present in Oracle source.

        return result.toString();
    }
}
