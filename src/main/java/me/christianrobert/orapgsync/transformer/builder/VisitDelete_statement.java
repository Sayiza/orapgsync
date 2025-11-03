package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.tablereference.TableReferenceHelper;

/**
 * Static helper for visiting DELETE statements.
 *
 * <p>Transforms Oracle DELETE statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle DELETE Statement:</h3>
 * <pre>
 * DELETE FROM table_name WHERE condition;
 * DELETE table_name WHERE condition;  (FROM is optional in Oracle)
 * DELETE FROM schema.table_name WHERE condition;
 * </pre>
 *
 * <h3>PostgreSQL DELETE Statement:</h3>
 * <pre>
 * DELETE FROM schema.table_name WHERE condition;  (FROM required)
 * </pre>
 *
 * <h3>Key Transformations:</h3>
 * <ul>
 *   <li>Always include FROM keyword (PostgreSQL best practice)</li>
 *   <li>Schema qualification for table names</li>
 *   <li>Expression transformation for WHERE clause</li>
 *   <li>Pass-through for most syntax (nearly identical)</li>
 * </ul>
 *
 * <h3>Grammar:</h3>
 * <pre>
 * delete_statement
 *     : DELETE FROM? general_table_ref where_clause? static_returning_clause? error_logging_clause?
 *     ;
 * </pre>
 *
 * <h3>Phase 1 Limitations:</h3>
 * <ul>
 *   <li>RETURNING clause not yet supported (deferred to Phase 2)</li>
 *   <li>error_logging_clause ignored (Oracle-specific)</li>
 * </ul>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL DELETE syntax is nearly identical to Oracle</li>
 *   <li>FROM keyword is mandatory in PostgreSQL (optional in Oracle)</li>
 *   <li>All table references delegated to existing visitor (handles schema qualification)</li>
 *   <li>WHERE clause delegated to existing visitor (handles all expressions)</li>
 * </ul>
 */
public class VisitDelete_statement {

    /**
     * Transforms DELETE statement to PostgreSQL syntax.
     *
     * @param ctx Delete statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting table reference and WHERE clause)
     * @return PostgreSQL DELETE statement
     */
    public static String v(PlSqlParser.Delete_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Delete_statementContext cannot be null");
        }

        StringBuilder result = new StringBuilder("DELETE FROM ");

        // Table reference (with schema qualification)
        // Use TableReferenceHelper for consistent table resolution
        if (ctx.general_table_ref() == null) {
            throw new IllegalArgumentException("DELETE statement missing table reference");
        }
        String tableRef = TableReferenceHelper.resolveGeneralTableRef(ctx.general_table_ref(), b);
        result.append(tableRef);

        // Optional WHERE clause
        if (ctx.where_clause() != null) {
            String whereClause = b.visit(ctx.where_clause());
            result.append(" ").append(whereClause);
        }

        // Phase 1: Ignore RETURNING clause (deferred to Phase 2)
        if (ctx.static_returning_clause() != null) {
            // For now, just add a comment indicating it was ignored
            // In Phase 2, we'll implement proper RETURNING transformation
            result.append(" /* RETURNING clause not yet supported */");
        }

        // Ignore error_logging_clause (Oracle-specific feature not in PostgreSQL)
        // No need to comment this out as it's rarely used

        return result.toString();
    }
}
