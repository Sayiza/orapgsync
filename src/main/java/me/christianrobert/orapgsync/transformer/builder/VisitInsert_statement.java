package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.tablereference.TableReferenceHelper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Static helper for visiting INSERT statements.
 *
 * <p>Transforms Oracle INSERT statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle INSERT Statement:</h3>
 * <pre>
 * INSERT INTO table_name (col1, col2) VALUES (val1, val2);
 * INSERT INTO table_name VALUES (val1, val2, val3);
 * INSERT INTO table_name (col1, col2) SELECT ... FROM ...;
 * INSERT INTO schema.table_name (col1) VALUES (val1);
 * INSERT INTO table_name (col1) VALUES (val1), (val2), (val3);  -- multi-row
 * </pre>
 *
 * <h3>PostgreSQL INSERT Statement:</h3>
 * <pre>
 * INSERT INTO schema.table_name (col1, col2) VALUES (val1, val2);
 * </pre>
 *
 * <h3>Key Transformations:</h3>
 * <ul>
 *   <li>Schema qualification for table names</li>
 *   <li>Expression transformation for VALUES clause</li>
 *   <li>SELECT transformation for INSERT ... SELECT</li>
 *   <li>Pass-through for most syntax (nearly identical)</li>
 * </ul>
 *
 * <h3>Grammar:</h3>
 * <pre>
 * insert_statement
 *     : INSERT (single_table_insert | multi_table_insert)
 *     ;
 *
 * single_table_insert
 *     : insert_into_clause (values_clause static_returning_clause? | select_statement) error_logging_clause?
 *     ;
 *
 * insert_into_clause
 *     : INTO general_table_ref paren_column_list?
 *     ;
 *
 * values_clause
 *     : VALUES (REGULAR_ID | '(' expressions_ ')' | collection_expression)
 *     ;
 * </pre>
 *
 * <h3>Phase 1 Limitations:</h3>
 * <ul>
 *   <li>RETURNING clause not yet supported - throws UnsupportedOperationException (deferred to Phase 2)</li>
 *   <li>error_logging_clause ignored (Oracle-specific)</li>
 *   <li>multi_table_insert not yet supported - throws UnsupportedOperationException (Oracle-specific, deferred to Phase 3)</li>
 *   <li>collection_expression in VALUES not yet supported - throws UnsupportedOperationException (rare usage)</li>
 * </ul>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL INSERT syntax is nearly identical to Oracle</li>
 *   <li>Multi-row INSERT (VALUES (a), (b), (c)) is supported in PostgreSQL</li>
 *   <li>All table references delegated to existing visitor (handles schema qualification)</li>
 *   <li>VALUES expressions delegated to existing visitor (handles all expression types)</li>
 * </ul>
 */
public class VisitInsert_statement {

    /**
     * Transforms INSERT statement to PostgreSQL syntax.
     *
     * @param ctx Insert statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting clauses)
     * @return PostgreSQL INSERT statement
     */
    public static String v(PlSqlParser.Insert_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Insert_statementContext cannot be null");
        }

        // Check for single_table_insert (common case)
        if (ctx.single_table_insert() != null) {
            return visitSingleTableInsert(ctx.single_table_insert(), b);
        }

        // Multi-table INSERT (Oracle-specific, rare)
        if (ctx.multi_table_insert() != null) {
            throw new UnsupportedOperationException(
                "Multi-table INSERT (INSERT ALL / INSERT FIRST) not yet supported. " +
                "This is an Oracle-specific feature with no direct PostgreSQL equivalent. " +
                "Consider splitting into multiple INSERT statements or using a CTE.");
        }

        throw new IllegalArgumentException("INSERT statement has neither single_table_insert nor multi_table_insert");
    }

    /**
     * Transforms single-table INSERT.
     *
     * Grammar:
     * <pre>
     * single_table_insert
     *     : insert_into_clause (values_clause static_returning_clause? | select_statement) error_logging_clause?
     *     ;
     * </pre>
     *
     * @param ctx Single table insert context
     * @param b PostgresCodeBuilder instance
     * @return PostgreSQL INSERT statement
     */
    private static String visitSingleTableInsert(PlSqlParser.Single_table_insertContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Single_table_insertContext cannot be null");
        }

        StringBuilder result = new StringBuilder();

        // INSERT INTO clause (table name + optional columns)
        if (ctx.insert_into_clause() == null) {
            throw new IllegalArgumentException("INSERT statement missing INTO clause");
        }
        String intoClause = visitInsertIntoClause(ctx.insert_into_clause(), b);
        result.append(intoClause);

        // VALUES clause OR SELECT statement
        if (ctx.values_clause() != null) {
            String valuesClause = visitValuesClause(ctx.values_clause(), b);
            result.append(" ").append(valuesClause);

            // Check for RETURNING clause (only applies to VALUES, not SELECT)
            if (ctx.static_returning_clause() != null) {
                throw new UnsupportedOperationException(
                    "INSERT with RETURNING clause is not yet supported. " +
                    "The RETURNING clause requires special handling to capture returned values into variables. " +
                    "Workaround: Use a separate SELECT statement after INSERT to retrieve the values, " +
                    "or wait for Phase 2 implementation of RETURNING clause support.");
            }
        } else if (ctx.select_statement() != null) {
            String selectStatement = b.visit(ctx.select_statement());
            result.append(" ").append(selectStatement);
        } else {
            throw new IllegalArgumentException("INSERT statement has neither VALUES clause nor SELECT statement");
        }

        // Ignore error_logging_clause (Oracle-specific feature not in PostgreSQL)

        return result.toString();
    }

    /**
     * Transforms INSERT INTO clause.
     *
     * Grammar:
     * <pre>
     * insert_into_clause
     *     : INTO general_table_ref paren_column_list?
     *     ;
     * </pre>
     *
     * Examples:
     * - INSERT INTO emp
     * - INSERT INTO hr.emp
     * - INSERT INTO emp (empno, ename)
     *
     * @param ctx Insert INTO clause context
     * @param b PostgresCodeBuilder instance
     * @return PostgreSQL INSERT INTO clause
     */
    private static String visitInsertIntoClause(PlSqlParser.Insert_into_clauseContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Insert_into_clauseContext cannot be null");
        }

        StringBuilder result = new StringBuilder("INSERT INTO ");

        // Table reference (with schema qualification)
        // Use TableReferenceHelper for consistent table resolution
        if (ctx.general_table_ref() == null) {
            throw new IllegalArgumentException("INSERT INTO clause missing table reference");
        }
        String tableRef = TableReferenceHelper.resolveGeneralTableRef(ctx.general_table_ref(), b);
        result.append(tableRef);

        // Optional column list: (col1, col2, ...)
        if (ctx.paren_column_list() != null) {
            // paren_column_list already includes parentheses - pass through as-is
            String columnList = ctx.paren_column_list().getText();
            result.append(" ").append(columnList);
        }

        return result.toString();
    }

    /**
     * Transforms VALUES clause.
     *
     * Grammar:
     * <pre>
     * values_clause
     *     : VALUES (REGULAR_ID | '(' expressions_ ')' | collection_expression)
     *     ;
     * </pre>
     *
     * Examples:
     * - VALUES (100, 'Alice', 50000)
     * - VALUES (100, 'Alice'), (101, 'Bob'), (102, 'Charlie')  -- multi-row
     * - VALUES my_record_variable
     *
     * @param ctx VALUES clause context
     * @param b PostgresCodeBuilder instance
     * @return PostgreSQL VALUES clause
     */
    private static String visitValuesClause(PlSqlParser.Values_clauseContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Values_clauseContext cannot be null");
        }

        StringBuilder result = new StringBuilder("VALUES ");

        // Case 1: VALUES variable_name (record/row variable)
        if (ctx.REGULAR_ID() != null) {
            // Simple identifier - could be a record variable
            String identifier = ctx.REGULAR_ID().getText();
            result.append(identifier);
            return result.toString();
        }

        // Case 2: VALUES collection_expression (rare, not yet supported)
        if (ctx.collection_expression() != null) {
            throw new UnsupportedOperationException(
                "INSERT VALUES with collection_expression not yet supported. " +
                "Use explicit VALUES tuples or INSERT ... SELECT instead.");
        }

        // Case 3: VALUES (expr1, expr2, ...) (most common)
        // The grammar shows: VALUES '(' expressions_ ')'
        // So we just have one expressions_ context with a list of expressions
        if (ctx.expressions_() != null) {
            // Get the expressions_ context
            PlSqlParser.Expressions_Context exprsCtx = ctx.expressions_();

            // Get all expressions in this tuple
            List<PlSqlParser.ExpressionContext> exprList = exprsCtx.expression();
            if (exprList == null || exprList.isEmpty()) {
                throw new IllegalArgumentException("Empty expression list in VALUES clause");
            }

            // Transform each expression and join with commas
            String expressions = exprList.stream()
                .map(b::visit)
                .collect(Collectors.joining(", "));

            result.append("(").append(expressions).append(")");
            return result.toString();
        }

        throw new IllegalArgumentException("VALUES clause has no expressions");
    }
}
