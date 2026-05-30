package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Transforms Oracle sequence pseudo-column calls to PostgreSQL function calls.
 *
 * <p><strong>Transformations:</strong>
 * <ul>
 *   <li>Oracle: {@code sequence_name.NEXTVAL} → PostgreSQL: {@code nextval('schema.sequence_name')}</li>
 *   <li>Oracle: {@code sequence_name.CURRVAL} → PostgreSQL: {@code currval('schema.sequence_name')}</li>
 *   <li>Oracle: {@code schema.sequence_name.NEXTVAL} → PostgreSQL: {@code nextval('schema.sequence_name')}</li>
 * </ul>
 *
 * <p><strong>Detection criteria:</strong>
 * <ul>
 *   <li>At least 2 parts</li>
 *   <li>Last part is NEXTVAL or CURRVAL (case-insensitive)</li>
 *   <li>Last part has NO function arguments (sequence pseudo-columns don't use parentheses)</li>
 * </ul>
 *
 * <p><strong>Schema qualification:</strong>
 * <ul>
 *   <li>Unqualified sequences are automatically qualified with current schema</li>
 *   <li>Synonym resolution is applied if TransformationContext is available</li>
 *   <li>Cross-schema sequences preserve schema prefix</li>
 * </ul>
 */
public class SequenceCallTransformer implements GeneralElementTransformer {

    @Override
    public GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        if (!isSequenceCall(parts)) {
            return GeneralElementResult.notHandled();
        }

        String result = handleSequenceCall(parts, builder);
        return GeneralElementResult.handled(result);
    }

    /**
     * Checks if the dot navigation is a sequence pseudo-column call (NEXTVAL or CURRVAL).
     *
     * @param parts The dot-separated parts
     * @return true if this is a sequence NEXTVAL/CURRVAL call
     */
    private boolean isSequenceCall(List<PlSqlParser.General_element_partContext> parts) {
        if (parts.size() < 2) {
            return false;
        }

        // Last part should be NEXTVAL or CURRVAL (no function arguments)
        PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);

        // Safeguard: Sequence pseudo-columns NEVER have parentheses
        // This prevents confusion with package functions named "nextval" or "currval"
        if (lastPart.function_argument() != null && !lastPart.function_argument().isEmpty()) {
            return false; // Has arguments, not a pseudo-column
        }

        String lastIdentifier = lastPart.id_expression().getText().toUpperCase();
        return "NEXTVAL".equals(lastIdentifier) || "CURRVAL".equals(lastIdentifier);
    }

    /**
     * Handles Oracle sequence pseudo-column calls and transforms them to PostgreSQL function calls.
     *
     * @param parts The dot-separated parts (2 or 3 elements)
     * @param builder PostgreSQL code builder
     * @return Transformed PostgreSQL function call
     */
    private String handleSequenceCall(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        // Extract operation (NEXTVAL or CURRVAL)
        String operation = parts.get(parts.size() - 1).id_expression().getText().toLowerCase();

        // Extract sequence name path (all parts except last)
        List<String> sequencePath = parts.subList(0, parts.size() - 1).stream()
                .map(part -> part.id_expression().getText())
                .collect(Collectors.toList());

        // Apply transformation with metadata context
        TransformationContext context = builder.getContext();
        if (context != null) {
            return transformSequenceCallWithMetadata(sequencePath, operation, context);
        } else {
            // No context available - simple transformation without metadata
            return transformSequenceCallSimple(sequencePath, operation);
        }
    }

    /**
     * Transforms sequence call using metadata context for synonym resolution and schema handling.
     *
     * @param sequencePath Sequence name parts (1 or 2 elements: [sequence] or [schema, sequence])
     * @param operation "nextval" or "currval"
     * @param context Transformation context
     * @return PostgreSQL function call: operation('qualified.sequence')
     */
    private String transformSequenceCallWithMetadata(
            List<String> sequencePath,
            String operation,
            TransformationContext context) {

        String currentSchema = context.getCurrentSchema().toLowerCase();
        String sequenceName;

        if (sequencePath.size() == 1) {
            // Single-part: sequence_name.NEXTVAL
            String seqName = sequencePath.get(0);

            // Check if it's a synonym
            String resolved = context.resolveSynonym(seqName);
            if (resolved != null) {
                // Synonym resolved to "schema.sequence" - use as-is
                sequenceName = resolved.toLowerCase();
            } else {
                // Not a synonym - qualify with current schema
                sequenceName = currentSchema + "." + seqName.toLowerCase();
            }

        } else if (sequencePath.size() == 2) {
            // Two-part: schema.sequence_name.NEXTVAL
            String schema = sequencePath.get(0).toLowerCase();
            String seqName = sequencePath.get(1).toLowerCase();
            sequenceName = schema + "." + seqName;

        } else {
            throw new TransformationException(
                    "Sequence call with more than 2 path parts not supported: " + sequencePath);
        }

        // Transform to PostgreSQL function call
        return operation + "('" + sequenceName + "')";
    }

    /**
     * Transforms sequence call without metadata (simple heuristic).
     *
     * @param sequencePath Sequence name parts (1 or 2 elements: [sequence] or [schema, sequence])
     * @param operation "nextval" or "currval"
     * @return PostgreSQL function call: operation('qualified.sequence')
     */
    private String transformSequenceCallSimple(
            List<String> sequencePath,
            String operation) {

        // Build qualified sequence name from path parts
        String sequenceName = sequencePath.stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining("."));

        // Transform to PostgreSQL function call
        return operation + "('" + sequenceName + "')";
    }
}
