package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Visitor helper for table_element grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * table_element
 *     : (INTRODUCER char_set_name)? id_expression ('.' id_expression)*
 * </pre>
 *
 * <p>Handles:
 * <ul>
 *   <li>Simple column references: {@code column_name}</li>
 *   <li>Qualified column references: {@code table.column}</li>
 *   <li>Fully qualified: {@code schema.table.column}</li>
 * </ul>
 *
 * <p>Note: Character set introducers (INTRODUCER) are not yet supported.
 */
public class VisitTableElement {

    public static String v(PlSqlParser.Table_elementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Table_elementContext cannot be null");
        }

        // Check for character set introducer (e.g., _utf8'text')
        if (ctx.INTRODUCER() != null) {
            throw new TransformationException(
                "Character set introducers (INTRODUCER) in table_element not yet supported");
        }

        // Get all id_expression parts (e.g., schema.table.column)
        List<PlSqlParser.Id_expressionContext> idExpressions = ctx.id_expression();
        if (idExpressions == null || idExpressions.isEmpty()) {
            throw new TransformationException(
                "table_element missing id_expression");
        }

        // Extract text from each id_expression and join with dots
        // For simple cases like "column_name", this will be a single element
        // For qualified cases like "table.column" or "schema.table.column", multiple elements
        String result = idExpressions.stream()
            .map(PlSqlParser.Id_expressionContext::getText)
            .collect(Collectors.joining("."));

        return result;
    }
}
