package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Visitor helper for sql_statement grammar rule.
 *
 * <p>SQL statements in PL/SQL context include:
 * <ul>
 *   <li>EXECUTE IMMEDIATE (dynamic SQL)</li>
 *   <li>Data manipulation: SELECT, INSERT, UPDATE, DELETE</li>
 *   <li>Cursor manipulation: OPEN, FETCH, CLOSE</li>
 *   <li>Transaction control: COMMIT, ROLLBACK, SAVEPOINT</li>
 *   <li>Collection methods: EXTEND, TRIM, DELETE</li>
 * </ul>
 *
 * <p><b>SQL% Cursor Tracking:</b>
 * This visitor handles SQL% implicit cursor tracking by injecting GET DIAGNOSTICS
 * after DML statements that affect the SQL cursor:
 * <ul>
 *   <li>SELECT INTO: Tracked via flag set by VisitQueryBlock</li>
 *   <li>INSERT/UPDATE/DELETE: Already handled by their respective visitors</li>
 * </ul>
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * sql_statement
 *     : execute_immediate
 *     | data_manipulation_language_statements
 *     | cursor_manipulation_statements
 *     | transaction_control_statements
 *     | collection_method_call
 *     ;
 * </pre>
 */
public class VisitSql_statement {

    public static String v(PlSqlParser.Sql_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Sql_statementContext cannot be null");
        }

        // Check if SQL cursor tracking is needed
        boolean needsSqlTracking = b.cursorNeedsTracking("sql");

        // Determine if this is a DML statement that affects SQL cursor
        boolean isDmlStatement = false;
        if (ctx.data_manipulation_language_statements() != null) {
            PlSqlParser.Data_manipulation_language_statementsContext dmlCtx =
                ctx.data_manipulation_language_statements();

            // DML statements that affect SQL% cursor: UPDATE, DELETE, INSERT, SELECT INTO
            isDmlStatement = dmlCtx.update_statement() != null ||
                           dmlCtx.delete_statement() != null ||
                           dmlCtx.insert_statement() != null ||
                           dmlCtx.select_statement() != null;
        }

        // Visit the SQL statement (delegates to appropriate sub-visitor)
        String result = b.visitChildren(ctx);

        // Inject GET DIAGNOSTICS for SQL% tracking if needed
        if (needsSqlTracking && isDmlStatement) {
            // Check if this was a SELECT INTO statement (flag set by VisitQueryBlock)
            boolean wasSelectInto = b.consumeIntoClauseFlag();

            // Only inject if:
            // 1. It's a DML statement (UPDATE/DELETE/INSERT/SELECT INTO)
            // 2. SQL cursor tracking is enabled
            // Note: The semicolon is already present from the statement itself
            if (!result.trim().endsWith(";")) {
                result = result + ";";
            }
            result = result + "\n  GET DIAGNOSTICS sql__rowcount = ROW_COUNT;";
        } else {
            // Still need to consume the flag even if we don't use it
            b.consumeIntoClauseFlag();
        }

        return result;
    }
}
