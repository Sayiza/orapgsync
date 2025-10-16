package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.Subquery;

/**
 * Represents a cursor expression.
 *
 * <p>Grammar rule: cursor_expression
 * <pre>
 * cursor_expression
 *     : CURSOR '(' subquery ')'
 * </pre>
 *
 * <p>Current implementation status:
 * - ⏳ Not yet implemented - throws TransformationException
 * - Future: Will need to handle cursor-to-PostgreSQL transformation
 */
public class CursorExpression implements SemanticNode {

    private final Subquery subquery;

    public CursorExpression(Subquery subquery) {
        if (subquery == null) {
            throw new IllegalArgumentException("Cursor subquery cannot be null");
        }
        this.subquery = subquery;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        throw new UnsupportedOperationException("CURSOR expressions not yet supported");
    }

    public Subquery getSubquery() {
        return subquery;
    }

    @Override
    public String toString() {
        return "CursorExpression{subquery=" + subquery + "}";
    }
}
