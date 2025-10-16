package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents the top-level expression node.
 *
 * <p>Grammar rule: expression
 * <pre>
 * expression
 *     : cursor_expression
 *     | logical_expression
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ logical_expression (basic support)
 * - ⏳ cursor_expression (not yet implemented)
 */
public class Expression implements SemanticNode {

    private final SemanticNode delegate;

    public Expression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Expression delegate cannot be null");
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
        return "Expression{delegate=" + delegate + "}";
    }
}
