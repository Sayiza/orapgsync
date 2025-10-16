package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a single element in the SELECT list.
 *
 * <p>Grammar rule: select_list_elements
 * <pre>
 * select_list_elements:
 *     tableview_name PERIOD ASTERISK
 *     | expression column_alias?
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ expression (simple identifier expressions)
 * - ⏳ column_alias (AS alias - not yet implemented)
 * - ⏳ tableview_name.* (table.* syntax - not yet implemented)
 * - ⏳ Complex expressions (functions, operators, etc. - not yet implemented)
 */
public class SelectListElement implements SemanticNode {

    private final SemanticNode expression;
    private final String alias;  // Future: column alias support

    public SelectListElement(SemanticNode expression) {
        this(expression, null);
    }

    public SelectListElement(SemanticNode expression, String alias) {
        if (expression == null) {
            throw new IllegalArgumentException("Expression cannot be null");
        }
        this.expression = expression;
        this.alias = alias;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        String result = expression.toPostgres(context);

        // Future: handle alias
        // if (alias != null) {
        //     result += " AS " + alias;
        // }

        return result;
    }

    public SemanticNode getExpression() {
        return expression;
    }

    public String getAlias() {
        return alias;
    }

    @Override
    public String toString() {
        return "SelectListElement{expression=" + expression +
               (alias != null ? ", alias='" + alias + "'" : "") + "}";
    }
}
