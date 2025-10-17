package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents an atom (atomic expression element).
 *
 * <p>Grammar rule: atom
 * <pre>
 * atom
 *     : bind_variable
 *     | constant
 *     | inquiry_directive
 *     | general_element outer_join_sign?
 *     | '(' subquery ')' subquery_operation_part*
 *     | '(' expressions_ ')'
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ general_element (delegates)
 * - ⏳ bind_variable (not yet implemented)
 * - ⏳ constant (literals - not yet implemented)
 * - ⏳ inquiry_directive (not yet implemented)
 * - ⏳ outer_join_sign (+) (not yet implemented)
 * - ⏳ Parenthesized subquery (not yet implemented)
 * - ⏳ Parenthesized expressions (not yet implemented)
 */
public class Atom implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to general_element.
     * Future: Will handle bind variables, constants, subqueries, etc.
     */
    public Atom(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Atom delegate cannot be null");
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
        return "Atom{delegate=" + delegate + "}";
    }
}
