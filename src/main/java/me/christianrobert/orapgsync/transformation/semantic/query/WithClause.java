package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a WITH clause (Common Table Expressions / CTEs).
 *
 * <p>Grammar rule: with_clause
 * <pre>
 * with_clause:
 *     WITH factoring_clause (COMMA factoring_clause)*
 *
 * factoring_clause:
 *     query_name paren_column_list? AS LEFT_PAREN subquery RIGHT_PAREN
 *     search_clause? cycle_clause?
 * </pre>
 *
 * <p>Current implementation status:
 * - ⏳ Not yet implemented - placeholder for future CTE support
 *
 * <p>Examples (future):
 * <pre>
 * WITH emp_dept AS (
 *     SELECT e.empno, e.ename, d.dname
 *     FROM emp e JOIN dept d ON e.deptno = d.deptno
 * )
 * SELECT * FROM emp_dept WHERE dname = 'SALES'
 * </pre>
 *
 * <p>Note: This class exists as a placeholder. CTEs will be implemented
 * in a future phase. For now, WITH clauses are not supported and will
 * throw an exception if encountered.
 */
public class WithClause implements SemanticNode {

    // Future: List<FactoringClause> factoringClauses;

    private WithClause() {
        // Private constructor - not instantiable yet
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // Future: Generate WITH clause SQL
        throw new UnsupportedOperationException("WITH clause (CTEs) not yet implemented");
    }

    @Override
    public String toString() {
        return "WithClause{not yet implemented}";
    }
}
