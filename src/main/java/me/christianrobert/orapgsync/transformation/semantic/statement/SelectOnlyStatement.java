package me.christianrobert.orapgsync.transformation.semantic.statement;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.Subquery;

/**
 * Represents a SELECT statement without set operations at statement level.
 *
 * <p>Grammar rule: select_only_statement
 * <pre>
 * select_only_statement:
 *     subquery for_update_clause?
 * </pre>
 *
 * <p>This node appears in multiple contexts:
 * - Top-level SELECT statements (via select_statement)
 * - Subqueries in WHERE/FROM clauses (future)
 * - Common Table Expressions (CTEs) (future)
 * - Derived tables (future)
 *
 * <p>Current implementation status:
 * - ✅ subquery (delegates to Subquery node)
 * - ⏳ for_update_clause (FOR UPDATE [OF ...] [NOWAIT|WAIT n|SKIP LOCKED] - not yet implemented)
 *
 * <p>The distinction from SelectStatement is important:
 * - SelectStatement can have WITH clause and statement-level operations
 * - SelectOnlyStatement is the actual query part (possibly with FOR UPDATE)
 *
 * <p>Examples:
 * <pre>
 * -- Simple (current support)
 * SELECT empno FROM emp
 *
 * -- With FOR UPDATE (future)
 * SELECT empno FROM emp WHERE deptno = 10 FOR UPDATE
 *
 * -- With set operations (handled by Subquery)
 * SELECT empno FROM emp WHERE deptno = 10
 * UNION
 * SELECT empno FROM emp WHERE deptno = 20
 * </pre>
 */
public class SelectOnlyStatement implements SemanticNode {

    private final Subquery subquery;
    // Future: ForUpdateClause forUpdate;

    public SelectOnlyStatement(Subquery subquery) {
        if (subquery == null) {
            throw new IllegalArgumentException("Subquery cannot be null");
        }
        this.subquery = subquery;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        StringBuilder sql = new StringBuilder();

        // Main subquery
        sql.append(subquery.toPostgres(context));

        // Future: append FOR UPDATE clause if present
        // if (forUpdate != null) {
        //     sql.append(" ");
        //     sql.append(forUpdate.toPostgres(context));
        // }

        return sql.toString();
    }

    public Subquery getSubquery() {
        return subquery;
    }

    @Override
    public String toString() {
        return "SelectOnlyStatement{subquery=" + subquery + "}";
    }
}
