package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting sequence of PL/SQL statements.
 *
 * Oracle structure:
 * statement1;
 * statement2;
 * statement3;
 *
 * PostgreSQL PL/pgSQL: (same)
 * statement1;
 * statement2;
 * statement3;
 *
 * This visitor processes each statement and ensures proper formatting.
 */
public class VisitSeq_of_statements {

    public static String v(PlSqlParser.Seq_of_statementsContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // Process each statement in sequence
        for (PlSqlParser.StatementContext statement : ctx.statement()) {
            if (statement != null) {
                String stmtCode = b.visit(statement);

                // Add statement with proper indentation
                result.append("  ").append(stmtCode);

                // Semicolon is part of the seq_of_statements grammar
                // but we need to ensure it's present
                if (!stmtCode.trim().endsWith(";")) {
                    result.append(";");
                }

                result.append("\n");
            }
        }

        return result.toString();
    }
}
