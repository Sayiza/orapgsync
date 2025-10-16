package me.christianrobert.orapgsync.transformation.semantic.statement;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.WithClause;

/**
 * Represents a complete SELECT statement (top-level query).
 *
 * <p>Grammar rule: select_statement
 * <pre>
 * select_statement:
 *     with_clause? select_only_statement
 *     | select_statement (UNION | INTERSECT | MINUS) select_statement
 *     | LEFT_PAREN select_statement RIGHT_PAREN
 * </pre>
 *
 * <p>Current implementation status:
 * - ⏳ with_clause (CTEs - not yet implemented, placeholder exists)
 * - ✅ select_only_statement (simple SELECT without set operations at statement level)
 * - ⏳ Statement-level UNION/INTERSECT/MINUS (not yet implemented - handled at subquery level)
 * - ⏳ Parenthesized select statements (not yet implemented)
 *
 * <p>Note on set operations:
 * Oracle grammar allows UNION/INTERSECT/MINUS at both select_statement level
 * and subquery level. We handle them at the subquery level (via SubqueryOperationPart),
 * which covers all practical cases.
 *
 * <p>Examples:
 * <pre>
 * -- Simple (current support)
 * SELECT empno FROM emp
 *
 * -- With CTE (future)
 * WITH emp_dept AS (
 *     SELECT e.empno, d.dname FROM emp e JOIN dept d ON e.deptno = d.deptno
 * )
 * SELECT * FROM emp_dept
 *
 * -- Set operations (handled at subquery level)
 * SELECT empno FROM emp WHERE deptno = 10
 * UNION
 * SELECT empno FROM emp WHERE deptno = 20
 * </pre>
 */
public class SelectStatement implements SemanticNode {

    private final WithClause withClause;  // Optional, null if not present
    private final SelectOnlyStatement selectOnlyStatement;

    public SelectStatement(SelectOnlyStatement selectOnlyStatement) {
        this(null, selectOnlyStatement);
    }

    public SelectStatement(WithClause withClause, SelectOnlyStatement selectOnlyStatement) {
        if (selectOnlyStatement == null) {
            throw new IllegalArgumentException("SelectOnlyStatement cannot be null");
        }
        this.withClause = withClause;  // null is OK - WITH is optional
        this.selectOnlyStatement = selectOnlyStatement;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        StringBuilder sql = new StringBuilder();

        // WITH clause (if present)
        if (withClause != null) {
            sql.append(withClause.toPostgres(context));
            sql.append(" ");
        }

        // Main query
        sql.append(selectOnlyStatement.toPostgres(context));

        return sql.toString();
    }

    public WithClause getWithClause() {
        return withClause;
    }

    public boolean hasWithClause() {
        return withClause != null;
    }

    public SelectOnlyStatement getSelectOnlyStatement() {
        return selectOnlyStatement;
    }

    @Override
    public String toString() {
        return "SelectStatement{" +
                (withClause != null ? "withClause=" + withClause + ", " : "") +
                "selectOnlyStatement=" + selectOnlyStatement + "}";
    }
}
