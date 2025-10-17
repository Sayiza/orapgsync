package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a unary expression.
 *
 * <p>Grammar rule: unary_expression
 * <pre>
 * unary_expression
 *     : ('-' | '+') unary_expression
 *     | PRIOR unary_expression
 *     | CONNECT_BY_ROOT unary_expression
 *     | NEW unary_expression
 *     | DISTINCT unary_expression
 *     | ALL unary_expression
 *     | case_expression
 *     | unary_expression '.' (...)
 *     | quantified_expression
 *     | standard_function
 *     | atom
 *     | implicit_cursor_expression
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Simple atom (delegates)
 * - ⏳ Unary operators (+, -, PRIOR, CONNECT_BY_ROOT, NEW, DISTINCT, ALL - not yet implemented)
 * - ⏳ Dot navigation (not yet implemented)
 * - ⏳ case_expression (not yet implemented)
 * - ⏳ quantified_expression (not yet implemented)
 * - ⏳ standard_function (not yet implemented)
 * - ⏳ implicit_cursor_expression (not yet implemented)
 */
public class UnaryExpression implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to atom.
     * Future: Will handle unary operators, dot navigation, case expressions, functions, etc.
     */
    public UnaryExpression(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("UnaryExpression delegate cannot be null");
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
        return "UnaryExpression{delegate=" + delegate + "}";
    }
}
