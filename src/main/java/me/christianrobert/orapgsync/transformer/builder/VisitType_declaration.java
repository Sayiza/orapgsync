package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * Static helper for visiting PL/SQL TYPE declarations in function/procedure blocks.
 *
 * <p>Transforms Oracle inline type declarations (RECORD, TABLE OF, VARRAY, INDEX BY)
 * by registering them in TransformationContext and emitting them as comments in PostgreSQL.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * TYPE identifier IS (table_type_def | varray_type_def | record_type_def | ref_cursor_type_def | type_spec) ';'
 *
 * Examples:
 * TYPE salary_range_t IS RECORD (min_sal NUMBER, max_sal NUMBER);
 * TYPE num_list_t IS TABLE OF NUMBER;
 * TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
 * TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL (Phase 1: JSON-First Strategy):</h3>
 * <pre>
 * -- TYPE salary_range_t IS RECORD (...); (Registered in context, stored as jsonb)
 *
 * All inline types are stored as jsonb in Phase 1:
 * - RECORD → jsonb object: '{}' → v := '{}'::jsonb
 * - TABLE OF → jsonb array: '[]' → v := '[]'::jsonb
 * - VARRAY → jsonb array: '[]' → v := '[]'::jsonb
 * - INDEX BY → jsonb object: '{}' → v := '{}'::jsonb
 * </pre>
 *
 * <h3>Transformations Applied:</h3>
 * <ul>
 *   <li>Parse TYPE declaration and extract type definition</li>
 *   <li>Register inline type in TransformationContext</li>
 *   <li>Emit TYPE declaration as comment (PostgreSQL doesn't have inline types)</li>
 *   <li>Variables of this type will be declared as jsonb (handled by VisitVariable_declaration)</li>
 *   <li>Field access will be transformed to JSON operations (handled by VisitGeneralElement, VisitAssignment_statement)</li>
 * </ul>
 *
 * <h3>Phase 1B Scope (Simple RECORD Types):</h3>
 * <ul>
 *   <li>✅ RECORD type declarations</li>
 *   <li>✅ Variables with RECORD types → jsonb</li>
 *   <li>✅ Field access (RHS): v.field → (v->>'field')::type</li>
 *   <li>✅ Field assignment (LHS): v.field := value → jsonb_set</li>
 *   <li>📋 Nested fields (v.address.city) - Phase 1B goal</li>
 * </ul>
 */
public class VisitType_declaration {

    /**
     * Transforms TYPE declaration to PostgreSQL comment and registers in context.
     *
     * @param ctx TYPE declaration parse tree context
     * @param b PostgresCodeBuilder instance (for accessing TransformationContext)
     * @return Commented TYPE declaration
     */
    public static String v(PlSqlParser.Type_declarationContext ctx, PostgresCodeBuilder b) {
        // STEP 1: Extract type name
        String typeName = ctx.identifier().getText();

        // STEP 2: Determine type category and build InlineTypeDefinition
        InlineTypeDefinition typeDefinition = null;

        if (ctx.record_type_def() != null) {
            // RECORD type
            typeDefinition = extractRecordType(typeName, ctx.record_type_def());
        } else if (ctx.table_type_def() != null) {
            // TABLE OF or INDEX BY
            typeDefinition = extractTableType(typeName, ctx.table_type_def());
        } else if (ctx.varray_type_def() != null) {
            // VARRAY
            typeDefinition = extractVarrayType(typeName, ctx.varray_type_def());
        } else if (ctx.ref_cursor_type_def() != null) {
            // REF CURSOR - not supported in Phase 1A
            return "-- TYPE " + typeName + " IS REF CURSOR; (REF CURSOR not supported in Phase 1)\n";
        } else if (ctx.type_spec() != null) {
            // TYPE alias (e.g., TYPE t IS VARCHAR2(100)) - not supported in Phase 1A
            String aliasType = ctx.type_spec().getText();
            return "-- TYPE " + typeName + " IS " + aliasType + "; (TYPE alias not supported in Phase 1)\n";
        }

        // STEP 3: Register type in TransformationContext
        if (typeDefinition != null) {
            b.getContext().registerInlineType(typeName, typeDefinition);
        }

        // STEP 4: Return commented TYPE declaration (PostgreSQL doesn't support inline types)
        // The type information is stored in context and used by variable declarations and field access
        StringBuilder result = new StringBuilder();
        result.append("-- TYPE ").append(typeName).append(" ");

        if (ctx.record_type_def() != null) {
            result.append("IS RECORD (...);");
        } else if (ctx.table_type_def() != null) {
            if (ctx.table_type_def().table_indexed_by_part() != null) {
                result.append("IS TABLE OF ... INDEX BY ...;");
            } else {
                result.append("IS TABLE OF ...;");
            }
        } else if (ctx.varray_type_def() != null) {
            result.append("IS VARRAY(...) OF ...;");
        }

        result.append(" (Registered as inline type, stored as jsonb)\n");

        return result.toString();
    }

    /**
     * Extracts RECORD type definition from AST.
     *
     * @param typeName Type name
     * @param recordCtx RECORD type definition context
     * @return InlineTypeDefinition for RECORD
     */
    private static InlineTypeDefinition extractRecordType(
            String typeName,
            PlSqlParser.Record_type_defContext recordCtx) {

        List<FieldDefinition> fields = new ArrayList<>();

        // Extract field specifications
        for (PlSqlParser.Field_specContext fieldSpec : recordCtx.field_spec()) {
            String fieldName = fieldSpec.column_name().getText();
            String oracleType = extractDataType(fieldSpec.type_spec());
            String postgresType = TypeConverter.toPostgre(oracleType);

            fields.add(new FieldDefinition(fieldName, oracleType, postgresType));
        }

        return new InlineTypeDefinition(
                typeName,
                TypeCategory.RECORD,
                null,  // No element type for RECORD
                fields,
                ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                null   // No size limit for RECORD
        );
    }

    /**
     * Extracts TABLE OF type definition (with or without INDEX BY) from AST.
     *
     * @param typeName Type name
     * @param tableCtx TABLE OF type definition context
     * @return InlineTypeDefinition for TABLE_OF or INDEX_BY
     */
    private static InlineTypeDefinition extractTableType(
            String typeName,
            PlSqlParser.Table_type_defContext tableCtx) {

        // Extract element type
        String elementType = extractDataType(tableCtx.type_spec());

        // Check if INDEX BY (associative array)
        if (tableCtx.table_indexed_by_part() != null) {
            String indexKeyType = extractDataType(tableCtx.table_indexed_by_part().type_spec());

            return new InlineTypeDefinition(
                    typeName,
                    TypeCategory.INDEX_BY,
                    elementType,  // Value type
                    null,         // No fields for INDEX BY
                    ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                    null,         // No size limit
                    indexKeyType  // Index key type
            );
        }

        // Regular TABLE OF (nested table)
        return new InlineTypeDefinition(
                typeName,
                TypeCategory.TABLE_OF,
                elementType,
                null,  // No fields for TABLE OF
                ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                null   // No size limit
        );
    }

    /**
     * Extracts VARRAY type definition from AST.
     *
     * @param typeName Type name
     * @param varrayCtx VARRAY type definition context
     * @return InlineTypeDefinition for VARRAY
     */
    private static InlineTypeDefinition extractVarrayType(
            String typeName,
            PlSqlParser.Varray_type_defContext varrayCtx) {

        // Extract element type
        String elementType = extractDataType(varrayCtx.type_spec());

        // Extract size limit from expression (e.g., VARRAY(10))
        Integer sizeLimit = null;
        if (varrayCtx.expression() != null) {
            try {
                String sizeLimitText = varrayCtx.expression().getText();
                sizeLimit = Integer.parseInt(sizeLimitText);
            } catch (NumberFormatException e) {
                // If size is an expression (not a literal), we can't extract it
                // This is acceptable - size limits are not enforced in Phase 1 anyway
            }
        }

        return new InlineTypeDefinition(
                typeName,
                TypeCategory.VARRAY,
                elementType,
                null,  // No fields for VARRAY
                ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                sizeLimit
        );
    }

    /**
     * Extracts Oracle data type string from type_spec context.
     *
     * @param typeSpecCtx Type specification context
     * @return Oracle type string (e.g., "NUMBER", "VARCHAR2(100)", "DATE")
     */
    private static String extractDataType(PlSqlParser.Type_specContext typeSpecCtx) {
        if (typeSpecCtx == null) {
            return "VARCHAR2(4000)"; // Default fallback
        }

        // Get the full text of the type specification
        // This includes things like VARCHAR2(100), NUMBER(10,2), DATE, etc.
        return typeSpecCtx.getText();
    }
}
