package me.christianrobert.orapgsync.transformation.semantic.statement;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.QueryBlock;

/**
 * Represents a complete SELECT statement (top-level query).
 *
 * <p>Grammar rule: select_statement
 * <pre>
 * select_statement:
 *     select_only_statement
 *     | select_statement (UNION | INTERSECT | MINUS) select_statement
 *     | LEFT_PAREN select_statement RIGHT_PAREN
 * </pre>
 *
 * <p>Grammar rule: select_only_statement
 * <pre>
 * select_only_statement:
 *     subquery
 *     | subquery for_update_clause?
 * </pre>
 *
 * <p>Grammar rule: subquery (simplified for minimal implementation)
 * <pre>
 * subquery:
 *     subquery_basic_elements
 *     | subquery_basic_elements (UNION | INTERSECT | MINUS) subquery_basic_elements
 * </pre>
 *
 * <p>Grammar rule: subquery_basic_elements
 * <pre>
 * subquery_basic_elements:
 *     query_block
 *     | LEFT_PAREN subquery RIGHT_PAREN
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ query_block (simple SELECT ... FROM ...)
 * - ⏳ UNION/INTERSECT/MINUS (set operations - not yet implemented)
 * - ⏳ for_update_clause (FOR UPDATE - not yet implemented)
 * - ⏳ Parenthesized subqueries (not yet implemented)
 *
 * <p>In the current minimal implementation, SelectStatement directly contains
 * a QueryBlock. Future phases will add set operations (UNION, etc.).
 */
public class SelectStatement implements SemanticNode {

    private final QueryBlock queryBlock;

    public SelectStatement(QueryBlock queryBlock) {
        if (queryBlock == null) {
            throw new IllegalArgumentException("QueryBlock cannot be null");
        }
        this.queryBlock = queryBlock;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // In minimal implementation, just delegate to QueryBlock
        // Future: handle UNION/INTERSECT/MINUS, FOR UPDATE, etc.
        return queryBlock.toPostgres(context);
    }

    public QueryBlock getQueryBlock() {
        return queryBlock;
    }

    @Override
    public String toString() {
        return "SelectStatement{queryBlock=" + queryBlock + "}";
    }
}
