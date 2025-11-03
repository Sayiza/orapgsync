package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.tablereference.TableReferenceHelper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Static helper for visiting UPDATE statements.
 *
 * <p>Transforms Oracle UPDATE statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle UPDATE Statement:</h3>
 * <pre>
 * UPDATE table_name SET col1 = val1, col2 = val2 WHERE condition;
 * UPDATE schema.table_name SET col1 = val1 WHERE condition;
 * UPDATE table_name SET col1 = (SELECT ...) WHERE condition;
 * UPDATE table_name SET (col1, col2) = (SELECT ...) WHERE condition;
 * </pre>
 *
 * <h3>PostgreSQL UPDATE Statement:</h3>
 * <pre>
 * UPDATE schema.table_name SET col1 = val1, col2 = val2 WHERE condition;
 * </pre>
 *
 * <h3>Key Transformations:</h3>
 * <ul>
 *   <li>Schema qualification for table names</li>
 *   <li>Expression transformation for SET and WHERE clauses</li>
 *   <li>Subquery transformation in SET clause</li>
 *   <li>Pass-through for most syntax (nearly identical)</li>
 * </ul>
 *
 * <h3>Grammar:</h3>
 * <pre>
 * update_statement
 *     : UPDATE general_table_ref update_set_clause where_clause? static_returning_clause? error_logging_clause?
 *     ;
 *
 * update_set_clause
 *     : SET (
 *         column_based_update_set_clause (',' column_based_update_set_clause)*
 *         | VALUE '(' identifier ')' '=' expression
 *     )
 *     ;
 *
 * column_based_update_set_clause
 *     : column_name '=' expression
 *     | paren_column_list '=' subquery
 *     ;
 * </pre>
 *
 * <h3>Phase 1 Limitations:</h3>
 * <ul>
 *   <li>RETURNING clause not yet supported - throws UnsupportedOperationException (deferred to Phase 2)</li>
 *   <li>error_logging_clause ignored (Oracle-specific)</li>
 *   <li>VALUE clause for object types not yet supported - throws UnsupportedOperationException (rare usage)</li>
 * </ul>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL UPDATE syntax is nearly identical to Oracle</li>
 *   <li>All table references delegated to existing visitor (handles schema qualification)</li>
 *   <li>SET clause expressions delegated to existing visitor (handles all expression types)</li>
 *   <li>WHERE clause delegated to existing visitor (handles all conditions)</li>
 * </ul>
 */
public class VisitUpdate_statement {

    /**
     * Transforms UPDATE statement to PostgreSQL syntax.
     *
     * @param ctx Update statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting table reference, SET clause, and WHERE clause)
     * @return PostgreSQL UPDATE statement
     */
    public static String v(PlSqlParser.Update_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Update_statementContext cannot be null");
        }

        StringBuilder result = new StringBuilder("UPDATE ");

        // Table reference (with schema qualification)
        // Use TableReferenceHelper for consistent table resolution
        if (ctx.general_table_ref() == null) {
            throw new IllegalArgumentException("UPDATE statement missing table reference");
        }
        String tableRef = TableReferenceHelper.resolveGeneralTableRef(ctx.general_table_ref(), b);
        result.append(tableRef);

        // SET clause (required)
        if (ctx.update_set_clause() == null) {
            throw new IllegalArgumentException("UPDATE statement missing SET clause");
        }
        String setClause = visitUpdateSetClause(ctx.update_set_clause(), b);
        result.append(" ").append(setClause);

        // Optional WHERE clause
        if (ctx.where_clause() != null) {
            String whereClause = b.visit(ctx.where_clause());
            result.append(" ").append(whereClause);
        }

        // Phase 1: RETURNING clause not supported - throw explicit error
        if (ctx.static_returning_clause() != null) {
            throw new UnsupportedOperationException(
                "UPDATE with RETURNING clause is not yet supported. " +
                "The RETURNING clause requires special handling to capture returned values into variables. " +
                "Workaround: Use a separate SELECT statement after UPDATE to retrieve the updated values, " +
                "or wait for Phase 2 implementation of RETURNING clause support.");
        }

        // Ignore error_logging_clause (Oracle-specific feature not in PostgreSQL)

        return result.toString();
    }

    /**
     * Transforms UPDATE SET clause.
     *
     * Grammar:
     * <pre>
     * update_set_clause
     *     : SET (
     *         column_based_update_set_clause (',' column_based_update_set_clause)*
     *         | VALUE '(' identifier ')' '=' expression
     *     )
     *     ;
     * </pre>
     *
     * @param ctx Update SET clause context
     * @param b PostgresCodeBuilder instance
     * @return PostgreSQL SET clause
     */
    private static String visitUpdateSetClause(PlSqlParser.Update_set_clauseContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Update_set_clauseContext cannot be null");
        }

        StringBuilder result = new StringBuilder("SET ");

        // Check for VALUE clause (object type update - rare, not yet supported)
        if (ctx.VALUE() != null) {
            throw new UnsupportedOperationException(
                "UPDATE with VALUE clause (object type updates) not yet supported. " +
                "Use column-based UPDATE instead.");
        }

        // Handle column-based UPDATE (common case)
        List<PlSqlParser.Column_based_update_set_clauseContext> setClauses = ctx.column_based_update_set_clause();
        if (setClauses == null || setClauses.isEmpty()) {
            throw new IllegalArgumentException("UPDATE SET clause has no column assignments");
        }

        // Process each SET clause: col = expr or (col1, col2) = subquery
        String setClauseList = setClauses.stream()
            .map(setClauseCtx -> visitColumnBasedUpdateSetClause(setClauseCtx, b))
            .collect(Collectors.joining(", "));

        result.append(setClauseList);

        return result.toString();
    }

    /**
     * Transforms a single column-based UPDATE SET clause.
     *
     * Grammar:
     * <pre>
     * column_based_update_set_clause
     *     : column_name '=' expression
     *     | paren_column_list '=' subquery
     *     ;
     * </pre>
     *
     * Examples:
     * - salary = 60000
     * - salary = salary * 1.1
     * - salary = (SELECT AVG(salary) FROM emp)
     * - (col1, col2) = (SELECT val1, val2 FROM table)
     *
     * @param ctx Column-based UPDATE SET clause context
     * @param b PostgresCodeBuilder instance
     * @return PostgreSQL SET clause fragment (e.g., "salary = 60000")
     */
    private static String visitColumnBasedUpdateSetClause(
        PlSqlParser.Column_based_update_set_clauseContext ctx,
        PostgresCodeBuilder b) {

        if (ctx == null) {
            throw new IllegalArgumentException("Column_based_update_set_clauseContext cannot be null");
        }

        // Case 1: column_name = expression
        if (ctx.column_name() != null && ctx.expression() != null) {
            // Column names are simple identifiers - use getText() directly (no transformation needed)
            String columnName = ctx.column_name().getText();
            String expression = b.visit(ctx.expression());
            return columnName + " = " + expression;
        }

        // Case 2: (col1, col2, ...) = subquery
        if (ctx.paren_column_list() != null && ctx.subquery() != null) {
            // paren_column_list already includes parentheses - pass through as-is
            String columnList = ctx.paren_column_list().getText();
            String subquery = b.visit(ctx.subquery());
            return columnList + " = " + subquery;
        }

        throw new IllegalArgumentException(
            "Invalid column_based_update_set_clause: expected 'column = expression' or '(columns) = subquery'");
    }
}
