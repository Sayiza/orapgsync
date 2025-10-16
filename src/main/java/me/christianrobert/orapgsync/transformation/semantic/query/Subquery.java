package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a subquery (can be a single query or combined with UNION/INTERSECT/MINUS).
 *
 * <p>Grammar rule: subquery
 * <pre>
 * subquery:
 *     subquery_basic_elements subquery_operation_part*
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ subquery_basic_elements (single query block)
 * - ⏳ subquery_operation_part (UNION/INTERSECT/MINUS - not yet implemented)
 *
 * <p>A subquery can be:
 * - Simple: just a query_block
 * - Combined: query_block UNION query_block INTERSECT query_block ...
 *
 * <p>Examples:
 * <pre>
 * -- Simple (current support)
 * SELECT empno FROM emp
 *
 * -- With UNION (future)
 * SELECT empno FROM emp WHERE deptno = 10
 * UNION
 * SELECT empno FROM emp WHERE deptno = 20
 *
 * -- Multiple operations (future)
 * SELECT empno FROM emp
 * UNION
 * SELECT empno FROM bonus
 * INTERSECT
 * SELECT empno FROM current_employees
 * </pre>
 */
public class Subquery implements SemanticNode {

    private final SubqueryBasicElements basicElements;
    private final List<SubqueryOperationPart> operations;

    public Subquery(SubqueryBasicElements basicElements) {
        this(basicElements, Collections.emptyList());
    }

    public Subquery(SubqueryBasicElements basicElements, List<SubqueryOperationPart> operations) {
        if (basicElements == null) {
            throw new IllegalArgumentException("SubqueryBasicElements cannot be null");
        }
        this.basicElements = basicElements;
        this.operations = operations != null ? new ArrayList<>(operations) : new ArrayList<>();
    }

    @Override
    public String toPostgres(TransformationContext context) {
        StringBuilder sql = new StringBuilder();

        // Start with basic elements
        sql.append(basicElements.toPostgres(context));

        // Add set operations (UNION, INTERSECT, MINUS)
        for (SubqueryOperationPart operation : operations) {
            sql.append(" ");
            sql.append(operation.toPostgres(context));
        }

        return sql.toString();
    }

    public SubqueryBasicElements getBasicElements() {
        return basicElements;
    }

    public List<SubqueryOperationPart> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public boolean hasSetOperations() {
        return !operations.isEmpty();
    }

    @Override
    public String toString() {
        return "Subquery{basicElements=" + basicElements + ", operations=" + operations.size() + "}";
    }
}
