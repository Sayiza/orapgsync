package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a standard Oracle function call.
 *
 * <p>Grammar rule: standard_function
 * <pre>
 * standard_function
 *     : string_function
 *     | numeric_function_wrapper
 *     | json_function
 *     | other_function
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ string_function (delegates)
 * - ⏳ numeric_function_wrapper (not yet implemented)
 * - ⏳ json_function (not yet implemented)
 * - ⏳ other_function (not yet implemented)
 */
public class StandardFunction implements SemanticNode {

    private final SemanticNode delegate;

    /**
     * Constructor for delegation to specific function type.
     *
     * @param delegate The specific function implementation (StringFunction, NumericFunction, etc.)
     */
    public StandardFunction(SemanticNode delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("StandardFunction delegate cannot be null");
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
        return "StandardFunction{delegate=" + delegate + "}";
    }
}
