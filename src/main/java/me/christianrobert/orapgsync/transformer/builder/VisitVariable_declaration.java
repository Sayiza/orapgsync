package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;

/**
 * Static helper for visiting PL/SQL variable declarations.
 *
 * <p>Transforms Oracle variable declarations to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * identifier CONSTANT? type_spec (NOT NULL)? default_value_part? ';'
 *
 * Examples:
 * v_count NUMBER;
 * v_name VARCHAR2(100);
 * v_rate CONSTANT NUMBER := 0.08;
 * v_total NUMBER NOT NULL := 0;
 * v_date DATE DEFAULT SYSDATE;
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * identifier [CONSTANT] pg_type [NOT NULL] [:= default_value];
 *
 * Examples:
 * v_count numeric;
 * v_name text;
 * v_rate CONSTANT numeric := 0.08;
 * v_total numeric NOT NULL := 0;
 * v_date timestamp := CURRENT_TIMESTAMP;
 * </pre>
 *
 * <h3>Transformations Applied:</h3>
 * <ul>
 *   <li>Oracle type → PostgreSQL type via TypeConverter</li>
 *   <li>CONSTANT keyword preserved</li>
 *   <li>NOT NULL constraint preserved</li>
 *   <li>Default value expressions transformed</li>
 *   <li>Semicolon terminator</li>
 * </ul>
 *
 * <h3>Inline Type Support (Phase 1B):</h3>
 * <ul>
 *   <li>If variable type is an inline type (RECORD, TABLE OF, etc.), emit jsonb</li>
 *   <li>Automatic initialization with appropriate jsonb literal</li>
 *   <li>RECORD → '{}'::jsonb (empty object)</li>
 *   <li>TABLE OF/VARRAY → '[]'::jsonb (empty array)</li>
 *   <li>INDEX BY → '{}'::jsonb (empty object)</li>
 * </ul>
 */
public class VisitVariable_declaration {

    /**
     * Transforms variable declaration to PostgreSQL syntax.
     *
     * @param ctx Variable declaration parse tree context
     * @param b PostgresCodeBuilder instance (for visiting default value expressions)
     * @return PostgreSQL variable declaration statement
     */
    public static String v(PlSqlParser.Variable_declarationContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // STEP 1: Extract variable name
        String varName = ctx.identifier().getText().toLowerCase();
        result.append(varName).append(" ");

        // STEP 2: Handle CONSTANT keyword (optional)
        if (ctx.CONSTANT() != null) {
            result.append("CONSTANT ");
        }

        // STEP 3: Resolve type specification (handles inline types, %ROWTYPE, %TYPE)
        // Three-level resolution cascade: block-level → package-level → schema-level
        String oracleType = ctx.type_spec().getText();
        TransformationContext context = b.getContext();

        InlineTypeDefinition inlineType = null;
        boolean isRowtypeOrType = false;

        // STEP 3a: Check for %ROWTYPE (e.g., employees%ROWTYPE)
        if (oracleType.toUpperCase().contains("%ROWTYPE")) {
            inlineType = resolveRowtypeReference(oracleType, context);
            isRowtypeOrType = true;
        }
        // STEP 3b: Check for %TYPE (e.g., employees.salary%TYPE or v_emp.salary%TYPE)
        else if (oracleType.toUpperCase().contains("%TYPE")) {
            inlineType = resolveTypeReference(oracleType, context, varName);
            isRowtypeOrType = true;
        }
        // STEP 3c: Check for regular inline type (RECORD, TABLE OF, etc.)
        else {
            inlineType = context.resolveInlineType(oracleType);
        }

        String postgresType;
        String autoInitializer = null;

        if (inlineType != null) {
            // INLINE TYPE: Use jsonb and prepare automatic initialization
            postgresType = "jsonb";

            // Only add automatic initialization if there's no explicit default value
            if (ctx.default_value_part() == null) {
                autoInitializer = inlineType.getInitializer();
            }
        } else if (isRowtypeOrType) {
            // %TYPE or %ROWTYPE resolved to simple type (not inline type)
            // Extract and use the actual PostgreSQL type
            postgresType = resolveSimpleTypeFromReference(oracleType, context, varName);
        } else {
            // REGULAR TYPE: Convert using TypeConverter
            postgresType = TypeConverter.toPostgre(oracleType);
        }

        result.append(postgresType);

        // STEP 4: Handle NOT NULL constraint (optional)
        if (ctx.NULL_() != null && ctx.NOT() != null) {
            result.append(" NOT NULL");
        }

        // STEP 5: Handle default value (optional)
        if (ctx.default_value_part() != null) {
            PlSqlParser.Default_value_partContext defaultCtx = ctx.default_value_part();

            // Visit the expression to transform it
            String defaultValue = b.visit(defaultCtx.expression());

            // PostgreSQL uses := for default values (same as Oracle)
            result.append(" := ").append(defaultValue);
        } else if (autoInitializer != null) {
            // STEP 5b: Add automatic initialization for inline types (if no explicit default)
            result.append(" := ").append(autoInitializer);
        }

        // STEP 6: Semicolon terminator with newline
        result.append(";\n");

        // STEP 7: Register variable in scope for deterministic lookup
        // This enables accurate disambiguation between variables and functions in expressions
        registerVariableInScope(b.getContext(), varName, oracleType, postgresType,
                               ctx.CONSTANT() != null,
                               ctx.default_value_part() != null ? ctx.default_value_part().expression().getText() : null,
                               inlineType);

        return result.toString();
    }

    /**
     * Registers a variable in the current scope for deterministic lookup.
     *
     * <p>This replaces heuristic variable detection with explicit registration.
     * When transforming expressions later, we can definitively know if an identifier
     * refers to a local variable or a function call.</p>
     *
     * @param context Transformation context
     * @param varName Variable name
     * @param oracleType Original Oracle type
     * @param postgresType Transformed PostgreSQL type
     * @param isConstant Whether the variable is declared as CONSTANT
     * @param defaultValue Default value expression (null if none)
     * @param inlineType Inline type definition (null if not an inline type)
     */
    private static void registerVariableInScope(TransformationContext context,
                                                String varName,
                                                String oracleType,
                                                String postgresType,
                                                boolean isConstant,
                                                String defaultValue,
                                                InlineTypeDefinition inlineType) {
        TransformationContext.VariableDefinition varDef = new TransformationContext.VariableDefinition(
            varName,
            oracleType,
            postgresType,
            isConstant,
            defaultValue,
            inlineType
        );

        context.registerVariable(varName, varDef);
    }

    /**
     * Resolves a %ROWTYPE reference to an inline type definition.
     *
     * <p>Transforms Oracle %ROWTYPE declarations to jsonb-based RECORD types:</p>
     * <pre>
     * Oracle:     v_emp employees%ROWTYPE;
     * PostgreSQL: v_emp jsonb := '{}'::jsonb;
     * </pre>
     *
     * @param oracleType Type specification containing %ROWTYPE (e.g., "employees%ROWTYPE")
     * @param context Transformation context with table metadata indices
     * @return InlineTypeDefinition representing the table structure as a RECORD, or null if table not found
     */
    private static InlineTypeDefinition resolveRowtypeReference(String oracleType, TransformationContext context) {
        // Extract table name from "tablename%ROWTYPE" pattern
        String tableName = oracleType.replace("%ROWTYPE", "")
                                    .replace("%rowtype", "")
                                    .trim();

        // Qualify table name with schema if not already qualified
        String qualifiedTable = tableName;
        if (!tableName.contains(".")) {
            qualifiedTable = context.getCurrentSchema() + "." + tableName;
        }

        // Get table column mappings from indices
        java.util.Map<String, me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo> columns =
            context.getIndices().getAllTableColumns().get(qualifiedTable.toLowerCase());

        if (columns == null || columns.isEmpty()) {
            // Table not found in metadata - log warning and return null
            // This will cause variable to use regular type conversion
            System.err.println("Warning: Table '" + qualifiedTable + "' not found for %ROWTYPE resolution");
            return null;
        }

        // Build field definitions from table columns
        java.util.List<me.christianrobert.orapgsync.transformer.inline.FieldDefinition> fields = new java.util.ArrayList<>();

        for (java.util.Map.Entry<String, me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo> entry : columns.entrySet()) {
            String columnName = entry.getKey();
            me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo typeInfo = entry.getValue();

            // Get Oracle type name (qualified if custom type)
            String oracleColType = typeInfo.getQualifiedType();

            // Convert to PostgreSQL type
            String postgresColType = TypeConverter.toPostgre(oracleColType);

            // Create field definition
            me.christianrobert.orapgsync.transformer.inline.FieldDefinition field =
                new me.christianrobert.orapgsync.transformer.inline.FieldDefinition(
                    columnName,
                    oracleColType,
                    postgresColType
                );
            fields.add(field);
        }

        // Create ROWTYPE inline type definition
        // Uses jsonb storage (JSON object with column name → value mappings)
        // Constructor parameter order: typeName, category, elementType, fields, strategy, sizeLimit, indexKeyType
        return new InlineTypeDefinition(
            tableName + "%ROWTYPE",  // Synthetic type name
            me.christianrobert.orapgsync.transformer.inline.TypeCategory.ROWTYPE,
            null,        // No element type (not a collection)
            fields,      // Table columns as fields
            me.christianrobert.orapgsync.transformer.inline.ConversionStrategy.JSONB,
            null,        // No size limit
            null         // No index key type
        );
    }

    /**
     * Resolves a %TYPE reference to an inline type definition or PostgreSQL type.
     *
     * <p>Handles two Oracle %TYPE patterns:</p>
     * <pre>
     * 1. Column reference:  v_sal employees.salary%TYPE  → numeric
     * 2. Variable reference: v_copy v_sal%TYPE           → numeric (inherits from v_sal)
     * </pre>
     *
     * <p>For simple types (NUMBER, VARCHAR2, etc.), returns null to use regular TypeConverter.
     * For inline types (RECORD, collections), returns the InlineTypeDefinition.</p>
     *
     * @param oracleType Type specification containing %TYPE
     * @param context Transformation context
     * @param currentVarName Current variable name (for circular reference detection)
     * @return InlineTypeDefinition if base is inline type, null for simple types
     */
    private static InlineTypeDefinition resolveTypeReference(String oracleType,
                                                             TransformationContext context,
                                                             String currentVarName) {
        // Extract base reference from "base%TYPE" pattern
        String baseRef = oracleType.replace("%TYPE", "")
                                   .replace("%type", "")
                                   .trim();

        // Check for circular reference
        if (baseRef.equalsIgnoreCase(currentVarName)) {
            throw new IllegalStateException(
                "Circular %TYPE reference detected: " + currentVarName + "%TYPE references itself"
            );
        }

        // Determine if this is a column reference (contains dot) or variable reference
        if (baseRef.contains(".")) {
            // Pattern: table.column%TYPE or variable.field%TYPE
            return resolveColumnOrFieldTypeReference(baseRef, context);
        } else {
            // Pattern: variable%TYPE
            return resolveVariableTypeReference(baseRef, context);
        }
    }

    /**
     * Resolves a column or field %TYPE reference.
     *
     * <p>Handles patterns like:</p>
     * <ul>
     *   <li>employees.salary%TYPE → Query table metadata</li>
     *   <li>v_emp.salary%TYPE → Query variable's inline type</li>
     * </ul>
     *
     * @param baseRef Base reference (e.g., "employees.salary" or "v_emp.salary")
     * @param context Transformation context
     * @return InlineTypeDefinition if field is inline type, null for simple types
     */
    private static InlineTypeDefinition resolveColumnOrFieldTypeReference(String baseRef,
                                                                          TransformationContext context) {
        String[] parts = baseRef.split("\\.");
        if (parts.length != 2) {
            System.err.println("Warning: Invalid %TYPE reference format: " + baseRef);
            return null;
        }

        String tableName = parts[0].trim();
        String columnName = parts[1].trim();

        // CASE 1: Check if this is a variable reference (v_emp.salary%TYPE)
        TransformationContext.VariableDefinition varDef = context.lookupVariable(tableName);
        if (varDef != null && varDef.getInlineType() != null) {
            // This is a field reference on an inline type variable
            InlineTypeDefinition inlineType = varDef.getInlineType();

            // Find the field in the inline type
            for (me.christianrobert.orapgsync.transformer.inline.FieldDefinition field : inlineType.getFields()) {
                if (field.getFieldName().equalsIgnoreCase(columnName)) {
                    // Check if field type is itself an inline type
                    InlineTypeDefinition fieldInlineType = context.resolveInlineType(field.getOracleType());
                    if (fieldInlineType != null) {
                        // Field is an inline type - return it
                        return fieldInlineType;
                    }
                    // Field is a simple type - return null (will use regular TypeConverter)
                    return null;
                }
            }

            System.err.println("Warning: Field '" + columnName + "' not found in variable '" + tableName + "'");
            return null;
        }

        // CASE 2: This is a table column reference (employees.salary%TYPE)
        // Qualify table name if needed
        String qualifiedTable = tableName;
        if (!tableName.contains(".")) {
            qualifiedTable = context.getCurrentSchema() + "." + tableName;
        }

        me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo typeInfo =
            context.getIndices().getColumnType(qualifiedTable, columnName);

        if (typeInfo == null) {
            System.err.println("Warning: Column '" + columnName + "' not found in table '" + qualifiedTable + "'");
            return null;
        }

        // Check if column type is an inline type (user-defined object type used as column type)
        String oracleColType = typeInfo.getQualifiedType();
        InlineTypeDefinition columnInlineType = context.resolveInlineType(oracleColType);
        if (columnInlineType != null) {
            // Column is an inline type - return it
            return columnInlineType;
        }

        // Column is a simple type - return null (will use regular TypeConverter)
        return null;
    }

    /**
     * Resolves a variable %TYPE reference.
     *
     * <p>Pattern: v_copy v_original%TYPE</p>
     *
     * @param varName Variable name to look up
     * @param context Transformation context
     * @return InlineTypeDefinition if variable is inline type, null for simple types
     */
    private static InlineTypeDefinition resolveVariableTypeReference(String varName,
                                                                     TransformationContext context) {
        // Look up variable in scope
        TransformationContext.VariableDefinition varDef = context.lookupVariable(varName);

        if (varDef == null) {
            System.err.println("Warning: Variable '" + varName + "' not found for %TYPE reference");
            return null;
        }

        // Return inline type definition if variable is an inline type
        // Returns null for simple types (will use regular TypeConverter)
        return varDef.getInlineType();
    }

    /**
     * Resolves the PostgreSQL type for a %TYPE or %ROWTYPE reference that resolved to a simple type.
     *
     * <p>This handles cases where %TYPE refers to a simple column or variable type,
     * not an inline type (RECORD, collection, etc.).</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>employees.salary%TYPE where salary is NUMBER → numeric</li>
     *   <li>v_count%TYPE where v_count is INTEGER → integer</li>
     * </ul>
     *
     * @param oracleType Original type specification (e.g., "employees.salary%TYPE")
     * @param context Transformation context
     * @param currentVarName Current variable name (for error messages)
     * @return PostgreSQL type string
     */
    private static String resolveSimpleTypeFromReference(String oracleType,
                                                         TransformationContext context,
                                                         String currentVarName) {
        // Handle %TYPE references
        if (oracleType.toUpperCase().contains("%TYPE")) {
            String baseRef = oracleType.replace("%TYPE", "")
                                      .replace("%type", "")
                                      .trim();

            // Check for column reference (contains dot)
            if (baseRef.contains(".")) {
                String[] parts = baseRef.split("\\.");
                if (parts.length == 2) {
                    String tableName = parts[0].trim();
                    String columnName = parts[1].trim();

                    // CASE 1: Check if this is a variable.field reference
                    TransformationContext.VariableDefinition varDef = context.lookupVariable(tableName);
                    if (varDef != null && varDef.getInlineType() != null) {
                        // Find the field in the inline type
                        InlineTypeDefinition inlineType = varDef.getInlineType();
                        for (me.christianrobert.orapgsync.transformer.inline.FieldDefinition field : inlineType.getFields()) {
                            if (field.getFieldName().equalsIgnoreCase(columnName)) {
                                return field.getPostgresType();
                            }
                        }
                    }

                    // CASE 2: This is a table.column reference
                    // Qualify table name if needed
                    String qualifiedTable = tableName;
                    if (!tableName.contains(".")) {
                        qualifiedTable = context.getCurrentSchema() + "." + tableName;
                    }

                    me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo typeInfo =
                        context.getIndices().getColumnType(qualifiedTable, columnName);
                    if (typeInfo != null) {
                        // Get Oracle type and convert to PostgreSQL
                        String oracleColType = typeInfo.getQualifiedType();
                        return TypeConverter.toPostgre(oracleColType);
                    }
                }
            } else {
                // Variable reference (no dot)
                TransformationContext.VariableDefinition varDef = context.lookupVariable(baseRef);
                if (varDef != null) {
                    return varDef.getPostgresType();
                }
            }
        }

        // Fallback: use TypeConverter (though this should rarely happen)
        System.err.println("Warning: Could not resolve %TYPE reference '" + oracleType +
                         "' for variable '" + currentVarName + "', using TypeConverter fallback");
        return TypeConverter.toPostgre(oracleType);
    }
}
