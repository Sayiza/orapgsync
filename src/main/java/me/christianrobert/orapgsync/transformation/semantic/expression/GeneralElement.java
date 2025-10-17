package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a general_element - the key transformation decision point.
 *
 * <p>Grammar rule: general_element
 * <pre>
 * general_element
 *     : general_element_part
 *     | general_element ('.' general_element_part)+
 *     | '(' general_element ')'
 * </pre>
 *
 * <p>This is THE critical node for transformation logic! At this level we can see:
 * - Single identifier vs dotted path (table.column, package.function, type.method)
 * - Function calls (via function_argument* in general_element_part)
 * - Full context for metadata-driven disambiguation
 *
 * <p>Current implementation status:
 * - ✅ Simple identifier (single general_element_part, uses getText() → Identifier)
 * - ⏳ Dot navigation (not yet implemented - needs metadata disambiguation)
 * - ⏳ Function calls (not yet implemented)
 * - ⏳ Parenthesized general_element (not yet implemented)
 *
 * <p>Future transformation logic will happen here:
 * - NVL(x, y) → COALESCE(x, y)
 * - pkg_synonym.func(args) → actual_pkg__func(args)
 * - table.composite.method() → (table.composite).method()
 * - table.column → table.column (no change, but needs validation)
 */
public class GeneralElement implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for simple delegation to Identifier (via getText()).
     * Future: Will analyze structure and create appropriate node types:
     * - Identifier for simple names
     * - DotExpression for dotted access
     * - FunctionCall for function invocations
     */
    public GeneralElement(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("GeneralElement delegate cannot be null");
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
        return "GeneralElement{delegate=" + delegate + "}";
    }
}
