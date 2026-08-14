package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.builder.objectfield.ObjectFieldAccessTransformer;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Adapter that wraps {@link ObjectFieldAccessTransformer} to implement {@link GeneralElementTransformer}.
 *
 * <p><strong>Transformation Pattern:</strong>
 * <ul>
 *   <li>Oracle: {@code table.column.field} → PostgreSQL: {@code (table.column).field}</li>
 *   <li>Oracle: {@code table.column.field1.field2} → PostgreSQL: {@code ((table.column).field1).field2}</li>
 * </ul>
 *
 * <p><strong>Detection criteria:</strong>
 * <ul>
 *   <li>At least 3 parts (alias.column.field)</li>
 *   <li>TransformationContext available</li>
 *   <li>First part resolves to a table/alias</li>
 *   <li>Second part is a column of a custom (object) type</li>
 *   <li>Third+ parts are fields of that type</li>
 * </ul>
 *
 * <p>This adapter must come BEFORE FunctionCallTransformer in priority order because:
 * <ul>
 *   <li>Field access has no parentheses: table.col.field</li>
 *   <li>Method calls have parentheses: table.col.method()</li>
 * </ul>
 */
public class ObjectFieldAccessAdapter implements GeneralElementTransformer {

    @Override
    public GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        // Need at least 3 parts: alias.column.field
        if (parts.size() < 3) {
            return GeneralElementResult.notHandled();
        }

        TransformationContext context = builder.getContext();
        if (context == null) {
            return GeneralElementResult.notHandled();
        }

        // Build full identifier chain from parts
        String identifierChain = parts.stream()
                .map(p -> IdentifierHelper.unquote(p.id_expression().getText()))
                .collect(Collectors.joining("."));

        // Delegate to existing ObjectFieldAccessTransformer
        ObjectFieldAccessTransformer fieldTransformer = new ObjectFieldAccessTransformer(context);
        String result = fieldTransformer.transform(identifierChain);

        if (result != null) {
            return GeneralElementResult.handled(result);
        }

        return GeneralElementResult.notHandled();
    }
}
