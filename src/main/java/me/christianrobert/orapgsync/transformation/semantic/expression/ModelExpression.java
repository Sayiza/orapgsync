package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a model expression.
 *
 * <p>Grammar rule: model_expression
 * <pre>
 * model_expression
 *     : unary_expression ('[' model_expression_element ']')?
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple unary_expression (delegates)
 * - ⏳ Model clause array indexing (not yet implemented)
 */
public class ModelExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to unary_expression.
     * Future: Will handle model clause array indexing.
     */
    public ModelExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("ModelExpression delegate cannot be null");
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
        return "ModelExpression{delegate=" + delegate + "}";
    }
}
