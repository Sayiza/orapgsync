package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a logical expression with AND/OR operations.
 *
 * <p>Grammar rule: logical_expression
 * <pre>
 * logical_expression
 *     : unary_logical_expression
 *     | logical_expression AND logical_expression
 *     | logical_expression OR logical_expression
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ unary_logical_expression (delegates to child)
 * - ⏳ AND/OR operations (not yet implemented)
 */
public class LogicalExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to unary_logical_expression.
     * Future: Will be extended to handle AND/OR binary operations.
     */
    public LogicalExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("LogicalExpression delegate cannot be null");
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
        return "LogicalExpression{delegate=" + delegate + "}";
    }
}
