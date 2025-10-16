package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a set operation part (UNION/INTERSECT/MINUS with following query).
 *
 * <p>Grammar rule: subquery_operation_part
 * <pre>
 * subquery_operation_part:
 *     (UNION ALL? | INTERSECT | MINUS) subquery_basic_elements
 * </pre>
 *
 * <p>Current implementation status:
 * - ⏳ Not yet implemented - placeholder for future set operations
 *
 * <p>Set operations combine multiple queries:
 * - UNION: Combines results, removing duplicates
 * - UNION ALL: Combines results, keeping duplicates
 * - INTERSECT: Returns only rows in both queries
 * - MINUS: Returns rows in first query but not in second
 *
 * <p>Examples (future):
 * <pre>
 * SELECT empno FROM emp WHERE deptno = 10
 * UNION               -- SubqueryOperationPart
 * SELECT empno FROM emp WHERE deptno = 20
 *
 * SELECT empno FROM emp
 * INTERSECT           -- SubqueryOperationPart
 * SELECT empno FROM bonus
 * </pre>
 */
public class SubqueryOperationPart implements SemanticNode {

    public enum SetOperationType {
        UNION,
        UNION_ALL,
        INTERSECT,
        MINUS
    }

    private final SetOperationType operationType;
    private final SubqueryBasicElements basicElements;

    public SubqueryOperationPart(SetOperationType operationType, SubqueryBasicElements basicElements) {
        if (operationType == null) {
            throw new IllegalArgumentException("Operation type cannot be null");
        }
        if (basicElements == null) {
            throw new IllegalArgumentException("SubqueryBasicElements cannot be null");
        }
        this.operationType = operationType;
        this.basicElements = basicElements;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        StringBuilder sql = new StringBuilder();

        // Add set operation keyword
        switch (operationType) {
            case UNION:
                sql.append("UNION");
                break;
            case UNION_ALL:
                sql.append("UNION ALL");
                break;
            case INTERSECT:
                sql.append("INTERSECT");
                break;
            case MINUS:
                // Oracle MINUS becomes PostgreSQL EXCEPT
                sql.append("EXCEPT");
                break;
        }

        sql.append(" ");
        sql.append(basicElements.toPostgres(context));

        return sql.toString();
    }

    public SetOperationType getOperationType() {
        return operationType;
    }

    public SubqueryBasicElements getBasicElements() {
        return basicElements;
    }

    @Override
    public String toString() {
        return "SubqueryOperationPart{operation=" + operationType + ", basicElements=" + basicElements + "}";
    }
}
