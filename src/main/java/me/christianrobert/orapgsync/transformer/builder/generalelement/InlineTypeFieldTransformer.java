package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;

import java.util.List;

/**
 * Transforms inline RECORD type field access to PostgreSQL jsonb operations.
 *
 * <p><strong>Transformations:</strong>
 * <ul>
 *   <li>Simple: {@code v.field} → {@code (v->>'field')::type}</li>
 *   <li>Nested: {@code v.addr.city} → {@code (v->'addr'->>'city')::type}</li>
 * </ul>
 *
 * <p><strong>Detection criteria:</strong>
 * <ul>
 *   <li>At least 2 parts: variable.field</li>
 *   <li>Not in assignment target (LHS handled by VisitAssignment_statement)</li>
 *   <li>First part is a registered local RECORD variable</li>
 * </ul>
 *
 * <p>Uses deterministic variable scope lookup via TransformationContext.
 */
public class InlineTypeFieldTransformer implements GeneralElementTransformer {

    @Override
    public GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        // Need at least 2 parts: variable.field
        if (parts.size() < 2) {
            return GeneralElementResult.notHandled();
        }

        // Skip if in assignment target (LHS handling is separate)
        if (builder.isInAssignmentTarget()) {
            return GeneralElementResult.notHandled();
        }

        TransformationContext context = builder.getContext();
        if (context == null) {
            return GeneralElementResult.notHandled();
        }

        // Check if first part is a local RECORD variable
        String variableName = parts.get(0).id_expression().getText();
        TransformationContext.VariableDefinition varDef = context.lookupVariable(variableName);

        if (varDef == null || !varDef.isRecord()) {
            return GeneralElementResult.notHandled();
        }

        // Transform inline type field access
        String result = handleInlineTypeFieldAccess(parts, builder, varDef);
        return GeneralElementResult.handled(result);
    }

    /**
     * Handles RECORD field access on RHS.
     *
     * @param parts All dot-separated parts [variable, field1, field2, ...]
     * @param builder PostgreSQL code builder
     * @param varDef Variable definition (KNOWN to be RECORD type)
     * @return Transformed jsonb field access expression with type cast
     */
    private String handleInlineTypeFieldAccess(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder,
            TransformationContext.VariableDefinition varDef) {

        if (parts.size() < 2) {
            throw new TransformationException(
                    "Inline type field access requires at least 2 parts: variable.field");
        }

        String variableName = parts.get(0).id_expression().getText();
        InlineTypeDefinition inlineType = varDef.getInlineType();

        StringBuilder result = new StringBuilder();

        // Build JSON path access
        // For nested access: variable->'field1'->'field2'->>'field3'
        // Last field uses ->> (extract as text), intermediate fields use -> (keep as jsonb)
        result.append(variableName);

        // Track current type as we navigate nested fields
        InlineTypeDefinition currentType = inlineType;
        FieldDefinition finalField = null;

        for (int i = 1; i < parts.size(); i++) {
            String fieldName = parts.get(i).id_expression().getText();
            boolean isLastField = (i == parts.size() - 1);

            if (isLastField) {
                // Last field: use ->> to extract as text
                result.append("->>'").append(fieldName).append("'");

                // Look up final field for type casting
                if (currentType != null) {
                    finalField = findField(currentType, fieldName);
                }
            } else {
                // Intermediate field: use -> to keep as jsonb
                result.append("->'").append(fieldName).append("'");

                // Try to resolve nested type for next iteration
                if (currentType != null) {
                    FieldDefinition field = findField(currentType, fieldName);
                    if (field != null && field.getOracleType() != null) {
                        // Check if this field is itself a RECORD type
                        TransformationContext context = builder.getContext();
                        InlineTypeDefinition nestedType = context.resolveInlineType(field.getOracleType());
                        if (nestedType != null && nestedType.isRecord()) {
                            currentType = nestedType;
                        } else {
                            currentType = null;
                        }
                    } else {
                        currentType = null;
                    }
                }
            }
        }

        // Type casting: Cast to the final field's PostgreSQL type
        if (finalField != null) {
            result.insert(0, "( ");
            result.append(" )::").append(finalField.getPostgresType());
        } else {
            // Field type unknown - wrap in parentheses but no cast
            result.insert(0, "( ");
            result.append(" )");
        }

        return result.toString();
    }

    /**
     * Finds a field definition by name in an inline type definition.
     *
     * @param inlineType Inline type definition
     * @param fieldName Field name to find (case-insensitive)
     * @return FieldDefinition or null if not found
     */
    private FieldDefinition findField(InlineTypeDefinition inlineType, String fieldName) {
        if (inlineType.getFields() == null) {
            return null;
        }

        String normalizedFieldName = fieldName.toLowerCase();

        for (FieldDefinition field : inlineType.getFields()) {
            if (field.getFieldName().toLowerCase().equals(normalizedFieldName)) {
                return field;
            }
        }

        return null;
    }
}
