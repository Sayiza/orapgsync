package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents the basic elements of a subquery (a query block or nested subquery).
 *
 * <p>Grammar rule: subquery_basic_elements
 * <pre>
 * subquery_basic_elements:
 *     query_block
 *     | LEFT_PAREN subquery RIGHT_PAREN
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ query_block (simple query)
 * - ⏳ Parenthesized subquery (nested subqueries - not yet implemented)
 *
 * <p>This node can contain either:
 * - A QueryBlock (the normal case)
 * - A nested Subquery in parentheses (for complex queries)
 *
 * <p>Examples:
 * <pre>
 * -- Query block (current support)
 * SELECT empno FROM emp
 *
 * -- Parenthesized subquery (future)
 * (SELECT empno FROM emp UNION SELECT empno FROM bonus)
 * </pre>
 */
public class SubqueryBasicElements implements SemanticNode {

    private final QueryBlock queryBlock;
    // Future: private final Subquery nestedSubquery;  // For parenthesized subqueries
    // Future: private final boolean isParenthesized;

    public SubqueryBasicElements(QueryBlock queryBlock) {
        if (queryBlock == null) {
            throw new IllegalArgumentException("QueryBlock cannot be null");
        }
        this.queryBlock = queryBlock;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // In current implementation, just delegate to query block
        // Future: handle parenthesized nested subqueries
        return queryBlock.toPostgres(context);
    }

    public QueryBlock getQueryBlock() {
        return queryBlock;
    }

    @Override
    public String toString() {
        return "SubqueryBasicElements{queryBlock=" + queryBlock + "}";
    }
}
