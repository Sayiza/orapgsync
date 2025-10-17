package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a multiset expression.
 *
 * <p>Grammar rule: multiset_expression
 * <pre>
 * multiset_expression
 *     : relational_expression (multiset_type = NOT? (MEMBER | SUBMULTISET) OF? concatenation)?
 *     | multiset_expression MULTISET multiset_operator = (EXCEPT | INTERSECT | UNION) (ALL | DISTINCT)? relational_expression
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple relational_expression (delegates)
 * - ⏳ MEMBER/SUBMULTISET operations (not yet implemented)
 * - ⏳ MULTISET operations (EXCEPT/INTERSECT/UNION - not yet implemented)
 */
public class MultisetExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to relational_expression.
     * Future: Will handle multiset operations.
     */
    public MultisetExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("MultisetExpression delegate cannot be null");
        }
        this.delegate = delegate;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        return delegate.toPostgres(context);
    }

    public SemanticNode getDelegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "MultisetExpression{delegate=" + delegate + "}";
    }
}
