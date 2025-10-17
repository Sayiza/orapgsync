package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a concatenation expression.
 *
 * <p>Grammar rule: concatenation
 * <pre>
 * concatenation
 *     : model_expression (AT (LOCAL | TIME ZONE concatenation) | interval_expression)? (
 *         ON OVERFLOW_ (TRUNCATE | ERROR)
 *     )?
 *     | concatenation BAR BAR concatenation
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple model_expression (delegates)
 * - ⏳ AT LOCAL/TIME ZONE (not yet implemented)
 * - ⏳ interval_expression (not yet implemented)
 * - ⏳ String concatenation || operator (not yet implemented)
 */
public class Concatenation implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to model_expression.
     * Future: Will handle AT TIME ZONE, interval expressions, and || concatenation.
     */
    public Concatenation(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Concatenation delegate cannot be null");
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
        return "Concatenation{delegate=" + delegate + "}";
    }
}
