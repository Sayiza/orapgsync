package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a unary logical expression.
 *
 * <p>Grammar rule: unary_logical_expression
 * <pre>
 * unary_logical_expression
 *     : NOT? multiset_expression unary_logical_operation?
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple case (no NOT, no unary_logical_operation) - delegates to Identifier
 * - ⏳ NOT prefix (not yet implemented)
 * - ⏳ unary_logical_operation (IS NULL, IS NOT NULL, etc. - not yet implemented)
 * - ⏳ multiset_expression (currently jumps directly to Identifier via getText())
 *
 * <p>This is where the current simplified implementation lives.
 * For now, this node uses ctx.getText() to create an Identifier as a placeholder.
 * Future phases will properly traverse multiset_expression and handle NOT/IS operators.
 */
public class UnaryLogicalExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation.
     * Currently delegates to Identifier created from getText().
     * Future: Will handle NOT prefix and unary_logical_operation suffix.
     */
    public UnaryLogicalExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("UnaryLogicalExpression delegate cannot be null");
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
        return "UnaryLogicalExpression{delegate=" + delegate + "}";
    }
}
