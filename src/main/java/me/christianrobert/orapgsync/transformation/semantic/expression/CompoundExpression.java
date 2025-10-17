package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a compound expression.
 *
 * <p>Grammar rule: compound_expression
 * <pre>
 * compound_expression
 *     : concatenation (
 *         NOT? (
 *             IN in_elements
 *             | BETWEEN between_elements
 *             | like_type = (LIKE | LIKEC | LIKE2 | LIKE4) concatenation (ESCAPE concatenation)?
 *         )
 *         | is_type = (IS NOT? | NOT) (
 *             NAN
 *             | PRESENT
 *             | INFINITE
 *             | NULL_
 *             | A SET
 *             | EMPTY
 *             | OF TYPE? '(' ONLY? type_spec (',' type_spec)* ')'
 *         )
 *     )?
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple concatenation (delegates)
 * - ⏳ IN operations (not yet implemented)
 * - ⏳ BETWEEN operations (not yet implemented)
 * - ⏳ LIKE operations (not yet implemented)
 * - ⏳ IS NULL/IS NOT NULL operations (not yet implemented)
 */
public class CompoundExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to concatenation.
     * Future: Will handle IN, BETWEEN, LIKE, IS NULL, etc.
     */
    public CompoundExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("CompoundExpression delegate cannot be null");
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
        return "CompoundExpression{delegate=" + delegate + "}";
    }
}
