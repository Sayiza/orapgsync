package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a relational expression.
 *
 * <p>Grammar rule: relational_expression
 * <pre>
 * relational_expression
 *     : relational_expression relational_operator relational_expression
 *     | compound_expression
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple compound_expression (delegates)
 * - ⏳ Relational operators (=, !=, <, >, <=, >= - not yet implemented)
 */
public class RelationalExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to compound_expression.
     * Future: Will handle relational operators.
     */
    public RelationalExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("RelationalExpression delegate cannot be null");
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
        return "RelationalExpression{delegate=" + delegate + "}";
    }
}
