package me.christianrobert.orapgsync.transformer.builder.tablereference;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper utilities for table reference resolution.
 *
 * <p>Provides reusable methods for resolving table names across different SQL statement types
 * (SELECT, INSERT, UPDATE, DELETE, MERGE). Handles:
 * <ul>
 *   <li>Schema qualification (unqualified → schema.table)</li>
 *   <li>Synonym resolution (Oracle synonyms → actual table names)</li>
 *   <li>CTE detection (Common Table Expressions don't get schema-qualified)</li>
 *   <li>Subquery handling (inline views in FROM clause)</li>
 * </ul>
 *
 * <p><b>Usage Pattern:</b>
 * <pre>
 * // For general_table_ref (DML statements: INSERT, UPDATE, DELETE)
 * String tableName = TableReferenceHelper.resolveGeneralTableRef(ctx.general_table_ref(), builder);
 *
 * // For dml_table_expression_clause (various contexts)
 * String tableName = TableReferenceHelper.resolveDmlTableExpression(ctx.dml_table_expression_clause(), builder);
 *
 * // For tableview_name (direct table references)
 * String tableName = TableReferenceHelper.resolveTableviewName(ctx.tableview_name(), builder);
 * </pre>
 *
 * <p><b>Architecture:</b>
 * <ul>
 *   <li>Static utility class (no state)</li>
 *   <li>Extracted from VisitTableReference for reusability</li>
 *   <li>Used by: VisitTableReference, VisitInsert_statement, VisitUpdate_statement, VisitDelete_statement</li>
 *   <li>Follows pattern established by: cte/, connectby/, outerjoin/, rownum/ subfolders</li>
 * </ul>
 */
public class TableReferenceHelper {

    /**
     * Resolves a general_table_ref to a qualified table name.
     *
     * <p>Used by DML statements (INSERT, UPDATE, DELETE) which use the simpler
     * general_table_ref grammar rule instead of the more complex table_ref.
     *
     * <p><b>Grammar:</b>
     * <pre>
     * general_table_ref
     *     : (dml_table_expression_clause | ONLY '(' dml_table_expression_clause ')') table_alias?
     * </pre>
     *
     * <p><b>Examples:</b>
     * <pre>
     * Oracle:     UPDATE emp SET salary = 60000
     * Resolved:   UPDATE hr.emp SET salary = 60000
     *
     * Oracle:     DELETE FROM departments WHERE inactive = 'Y'
     * Resolved:   DELETE FROM hr.departments WHERE inactive = 'Y'
     * </pre>
     *
     * @param ctx general_table_ref context from ANTLR parser
     * @param b PostgresCodeBuilder instance (provides transformation context)
     * @return Qualified table name (e.g., "hr.emp") or subquery
     * @throws TransformationException if context is null or unsupported table reference type
     */
    public static String resolveGeneralTableRef(
        PlSqlParser.General_table_refContext ctx,
        PostgresCodeBuilder b) {

        if (ctx == null) {
            throw new TransformationException("general_table_ref context is null");
        }

        // Extract dml_table_expression_clause (required)
        PlSqlParser.Dml_table_expression_clauseContext dmlTableExpr = ctx.dml_table_expression_clause();
        if (dmlTableExpr == null) {
            throw new TransformationException("general_table_ref missing dml_table_expression_clause");
        }

        // Resolve the table expression (handles schema qualification, synonyms, subqueries)
        String resolvedTable = resolveDmlTableExpression(dmlTableExpr, b);

        // Note: table_alias is NOT included here because DML statements handle aliases differently
        // INSERT/UPDATE/DELETE don't support table aliases in the same way as SELECT
        // If needed in future, check ctx.table_alias() and append

        return resolvedTable;
    }

    /**
     * Resolves a dml_table_expression_clause to a qualified table name or subquery.
     *
     * <p>This is the core table resolution method used across multiple grammar rules.
     *
     * <p><b>Grammar:</b>
     * <pre>
     * dml_table_expression_clause
     *     : tableview_name
     *     | '(' select_statement ')'
     *     | table_collection_expression
     * </pre>
     *
     * <p><b>Supported Cases:</b>
     * <ul>
     *   <li><b>tableview_name:</b> Regular table reference → applies schema qualification and synonym resolution</li>
     *   <li><b>select_statement:</b> Subquery in FROM clause → recursively transforms SELECT</li>
     * </ul>
     *
     * <p><b>Not Yet Supported:</b>
     * <ul>
     *   <li>table_collection_expression (TABLE() operator for collections)</li>
     * </ul>
     *
     * @param ctx dml_table_expression_clause context from ANTLR parser
     * @param b PostgresCodeBuilder instance (provides transformation context and recursive transformation)
     * @return Qualified table name (e.g., "hr.emp") or transformed subquery (e.g., "( SELECT ... )")
     * @throws TransformationException if context is null or unsupported expression type
     */
    public static String resolveDmlTableExpression(
        PlSqlParser.Dml_table_expression_clauseContext ctx,
        PostgresCodeBuilder b) {

        if (ctx == null) {
            throw new TransformationException("dml_table_expression_clause is null");
        }

        // CASE 1: Regular table reference (tableview_name)
        PlSqlParser.Tableview_nameContext tableviewName = ctx.tableview_name();
        if (tableviewName != null) {
            return resolveTableviewName(tableviewName, b);
        }

        // CASE 2: Subquery in FROM clause: '(' select_statement ')'
        PlSqlParser.Select_statementContext selectStatement = ctx.select_statement();
        if (selectStatement != null) {
            return resolveSubquery(selectStatement, b);
        }

        // CASE 3: Other cases (table_collection_expression - not yet implemented)
        throw new TransformationException(
            "Unsupported dml_table_expression_clause type. " +
            "Only tableview_name and subqueries are currently supported. " +
            "table_collection_expression (TABLE() operator) not yet implemented.");
    }

    /**
     * Resolves a tableview_name to a qualified table name.
     *
     * <p>Applies the following transformations in order:
     * <ol>
     *   <li><b>CTE Detection:</b> If name matches a CTE, return as-is (lowercase, no schema)</li>
     *   <li><b>Synonym Resolution:</b> If name is a synonym, resolve to actual table name</li>
     *   <li><b>Schema Qualification:</b> If unqualified, add current schema prefix</li>
     * </ol>
     *
     * <p><b>Examples:</b>
     * <pre>
     * Input: "emp"              → Output: "hr.emp"           (schema qualification)
     * Input: "emp_synonym"      → Output: "hr.employees"     (synonym resolution)
     * Input: "hr.emp"           → Output: "hr.emp"           (already qualified)
     * Input: "my_cte"           → Output: "my_cte"           (CTE, no qualification)
     * </pre>
     *
     * <p><b>Why Schema Qualification?</b>
     * <ul>
     *   <li>Oracle: Implicitly uses current schema for unqualified names</li>
     *   <li>PostgreSQL: Uses search_path, which can find wrong table or raise "does not exist" error</li>
     *   <li>Solution: Explicitly qualify all table names with schema</li>
     * </ul>
     *
     * @param ctx tableview_name context from ANTLR parser
     * @param b PostgresCodeBuilder instance (provides transformation context)
     * @return Qualified table name (e.g., "hr.emp") or unqualified CTE name
     */
    public static String resolveTableviewName(
        PlSqlParser.Tableview_nameContext ctx,
        PostgresCodeBuilder b) {

        if (ctx == null) {
            throw new TransformationException("tableview_name context is null");
        }

        String tableName = ctx.getText();
        TransformationContext context = b.getContext();

        // Apply name resolution logic (only if context is available)
        if (context != null) {
            // STEP 0: Check if this is a CTE (Common Table Expression) name
            // CTEs are temporary named result sets that don't belong to any schema
            // Example: WITH my_cte AS (...) SELECT * FROM my_cte
            if (context.isCTE(tableName)) {
                // CTE name - do NOT schema-qualify
                return tableName.toLowerCase();
            }

            // STEP 1: Try to resolve as synonym first
            // Oracle synonyms don't exist in PostgreSQL, so we must resolve them during transformation
            String resolvedName = context.resolveSynonym(tableName);
            if (resolvedName != null) {
                // Synonym resolved to actual qualified table name (schema.table)
                tableName = resolvedName;
            } else {
                // STEP 2: Not a synonym - qualify unqualified names with current schema
                // Oracle implicitly uses current schema for unqualified names
                // PostgreSQL uses search_path, which can cause wrong table or "does not exist" errors
                // Solution: Explicitly qualify all unqualified table names
                if (!tableName.contains(".")) {
                    // Unqualified name → qualify with current schema
                    tableName = context.getCurrentSchema().toLowerCase() + "." + tableName.toLowerCase();
                }
            }
        }
        // If context not available, keep original name (e.g., in simple tests without metadata)

        return tableName;
    }

    /**
     * Resolves a subquery (inline view) in FROM clause.
     *
     * <p>Recursively transforms the SELECT statement inside the subquery,
     * ensuring all transformation rules apply (schema qualification, synonyms, etc.).
     *
     * <p><b>Examples:</b>
     * <pre>
     * Oracle:     (SELECT dept_id FROM departments WHERE active = 'Y')
     * PostgreSQL: ( SELECT dept_id FROM hr.departments WHERE active = 'Y' )
     *
     * Oracle:     (SELECT * FROM emp WHERE salary > 50000)
     * PostgreSQL: ( SELECT * FROM hr.emp WHERE salary > 50000 )
     * </pre>
     *
     * @param ctx select_statement context from ANTLR parser
     * @param b PostgresCodeBuilder instance (for recursive transformation)
     * @return Transformed subquery wrapped in parentheses with spaces (e.g., "( SELECT ... )")
     * @throws TransformationException if context is null
     */
    public static String resolveSubquery(
        PlSqlParser.Select_statementContext ctx,
        PostgresCodeBuilder b) {

        if (ctx == null) {
            throw new TransformationException("select_statement context is null");
        }

        // Recursively transform the subquery using the same PostgresCodeBuilder
        // This ensures all transformation rules (schema qualification, synonyms, etc.) apply
        String transformedSubquery = b.visit(ctx);

        // Wrap in parentheses (required for subqueries in FROM clause)
        // Add spaces for readability
        return "( " + transformedSubquery + " )";
    }
}
