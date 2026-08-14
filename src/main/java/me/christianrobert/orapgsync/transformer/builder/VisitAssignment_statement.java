package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

/**
 * Static helper for visiting PL/SQL assignment statements.
 *
 * <p>Transforms Oracle assignment statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * (general_element | bind_variable) ASSIGN_OP expression
 *
 * Examples:
 * v_count := 10;
 * v_total := v_price * v_quantity;
 * v_name := 'John Doe';
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * Same syntax - assignments use := in both Oracle and PostgreSQL
 *
 * Examples:
 * v_count := 10;
 * v_total := v_price * v_quantity;
 * v_name := 'John Doe';
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>Syntax is identical between Oracle and PostgreSQL</li>
 *   <li>Only the expression on the right-hand side needs transformation</li>
 *   <li>The := operator is the same in both databases</li>
 * </ul>
 */
public class VisitAssignment_statement {

    /**
     * Transforms assignment statement to PostgreSQL syntax.
     *
     * <p><strong>Package Variable Support:</strong></p>
     * <p>If the left-hand side is a package variable reference (e.g., pkg.g_counter),
     * the assignment is transformed to a setter call:</p>
     * <pre>
     * Oracle:     pkg.g_counter := 100;
     * PostgreSQL: PERFORM schema.pkg__set_g_counter(100);
     * </pre>
     *
     * <p><strong>Inline Type Field Assignment (Phase 1B):</strong></p>
     * <p>If the left-hand side is an inline type field access (e.g., v_range.min_sal),
     * the assignment is transformed to a jsonb_set call:</p>
     * <pre>
     * Oracle:     v_range.min_sal := 1000;
     * PostgreSQL: v_range := jsonb_set(v_range, '{min_sal}', to_jsonb(1000));
     * </pre>
     *
     * @param ctx Assignment statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting the expression)
     * @return PostgreSQL assignment statement or setter call
     */
    public static String v(PlSqlParser.Assignment_statementContext ctx, PostgresCodeBuilder b) {
        // STEP 1: Parse LHS with flag protection (prevents getter transformation)
        // This flag tells VisitGeneralElement to NOT transform package variables to getter calls
        b.setInAssignmentTarget(true);
        String leftSide;
        if (ctx.general_element() != null) {
            leftSide = b.visit(ctx.general_element());
        } else if (ctx.bind_variable() != null) {
            leftSide = b.visit(ctx.bind_variable());
        } else {
            throw new IllegalStateException("Assignment statement has no left-hand side");
        }
        b.setInAssignmentTarget(false);

        // STEP 2: Check if LHS is a package variable
        PostgresCodeBuilder.PackageVariableReference pkgVar = b.parsePackageVariableReference(leftSide);

        if (pkgVar != null) {
            // Transform to setter call
            String rightSide = b.visit(ctx.expression());
            return "PERFORM " + pkgVar.getSetterCall(rightSide);
        }

        // STEP 2.5: Check if LHS is a package variable FIELD assignment (Phase: Complex Types)
        // Pattern: pkg.g_rec.field := value or g_rec.field := value (inside package)
        // Transform: PERFORM pkg__set_g_rec(jsonb_set(pkg__get_g_rec(), '{field}', to_jsonb(value)))
        if (ctx.general_element() != null) {
            String pkgFieldAssignment = tryTransformPackageVariableFieldAssignment(ctx.general_element(), ctx, b);
            if (pkgFieldAssignment != null) {
                return pkgFieldAssignment;
            }
        }

        // STEP 2.7: Check if LHS is a package variable COLLECTION element assignment (Phase 5: Complex Types)
        // Pattern: pkg.g_array(1) := value or g_array(1) := value (inside package)
        // Transform: PERFORM pkg__set_g_array(jsonb_set(pkg__get_g_array(), '{0}', to_jsonb(value)))
        if (ctx.general_element() != null) {
            String pkgCollectionAssignment = tryTransformPackageVariableCollectionAssignment(ctx.general_element(), ctx, b);
            if (pkgCollectionAssignment != null) {
                return pkgCollectionAssignment;
            }
        }

        // STEP 3: Check if LHS is an inline type field assignment (Phase 1B)
        // Pattern: variable.field or variable.field1.field2
        // Transform: variable := jsonb_set(variable, '{field}', to_jsonb(value))
        // For nested: variable := jsonb_set(variable, '{field1,field2}', to_jsonb(value), true)
        if (ctx.general_element() != null) {
            String fieldAssignment = tryTransformInlineTypeFieldAssignment(ctx.general_element(), ctx, b);
            if (fieldAssignment != null) {
                return fieldAssignment;
            }
        }

        // STEP 3.5: Check if LHS is a collection element assignment (Phase 1C.5 + 1D)
        // Pattern: v_nums(1) := value (array) or v_map('key') := value (map)
        // Transform: v_nums := jsonb_set(v_nums, '{0}', to_jsonb(value)) (1-based → 0-based for arrays)
        // Transform: v_map := jsonb_set(v_map, '{key}', to_jsonb(value)) (no index adjustment for maps)
        if (ctx.general_element() != null) {
            String collectionAssignment = tryTransformCollectionElementAssignment(ctx.general_element(), ctx, b);
            if (collectionAssignment != null) {
                return collectionAssignment;
            }
        }

        // STEP 4: Normal assignment (not a package variable, not an inline type field, not a collection element)
        StringBuilder result = new StringBuilder();
        result.append(leftSide);
        result.append(" := ");
        String rightSide = b.visit(ctx.expression());
        result.append(rightSide);

        return result.toString();
    }

    /**
     * Tries to transform an inline type field assignment to jsonb_set call.
     *
     * @param elemCtx General element context (LHS)
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed jsonb_set call, or null if not an inline type field assignment
     */
    private static String tryTransformInlineTypeFieldAssignment(
            PlSqlParser.General_elementContext elemCtx,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        // Check if this is a dotted access pattern
        if (elemCtx.general_element() == null) {
            return null; // Not dotted access
        }

        // Collect all parts
        java.util.List<PlSqlParser.General_element_partContext> parts = collectAllParts(elemCtx);

        if (parts.size() < 2) {
            return null; // Need at least variable.field
        }

        // Extract variable name and field path
        String variableName = IdentifierHelper.unquote(parts.get(0).id_expression().getText());

        // For Phase 1B, we use a simple heuristic:
        // If the variable name looks like a local variable (starts with v_, etc.)
        // AND the parts size suggests field access (2+ parts)
        // Transform to jsonb_set
        //
        // This is a simplification - proper implementation would track variable types
        // TODO Phase 1B.5: Track variable declarations and types in scope stack

        // For Phase 1B, always attempt transformation for dotted LHS
        // PostgreSQL will raise a runtime error if the variable is not jsonb
        // This is acceptable for Phase 1B testing

        // Build field path array
        StringBuilder fieldPath = new StringBuilder();
        fieldPath.append("'{ ");
        for (int i = 1; i < parts.size(); i++) {
            if (i > 1) {
                fieldPath.append(" , ");
            }
            fieldPath.append(IdentifierHelper.unquote(parts.get(i).id_expression().getText()));
        }
        fieldPath.append(" }'");

        // Transform RHS expression
        String rightSide = b.visit(assignCtx.expression());

        // PostgreSQL Bug Fix: String literals need explicit casting for to_jsonb()
        // PostgreSQL's to_jsonb() is polymorphic and cannot determine type from "unknown" literals
        // Example: to_jsonb('text') fails, but to_jsonb('text'::text) works
        String castedValue = addExplicitCastForLiterals(rightSide);

        // Build jsonb_set call
        // Syntax: variable := jsonb_set(variable, '{field}', to_jsonb(value), true)
        // The 'true' flag creates missing intermediate keys for nested paths
        StringBuilder result = new StringBuilder();
        result.append(variableName);
        result.append(" := jsonb_set( ");
        result.append(variableName);
        result.append(" , ");
        result.append(fieldPath);
        result.append(" , to_jsonb( ");
        result.append(castedValue);
        result.append(" ) ");

        // Add 'true' flag for nested paths (creates missing intermediate objects)
        if (parts.size() > 2) {
            result.append(" , true");
        }

        result.append(" )");

        return result.toString();
    }

    /**
     * Collects all parts from a recursive general_element structure.
     * Same logic as in VisitGeneralElement.
     */
    private static java.util.List<PlSqlParser.General_element_partContext> collectAllParts(
            PlSqlParser.General_elementContext ctx) {
        java.util.List<PlSqlParser.General_element_partContext> parts = new java.util.ArrayList<>();

        // Recursively collect from nested general_element
        PlSqlParser.General_elementContext nestedElement = ctx.general_element();
        if (nestedElement != null) {
            parts.addAll(collectAllParts(nestedElement));
        }

        // Add parts from this level
        java.util.List<PlSqlParser.General_element_partContext> currentParts = ctx.general_element_part();
        if (currentParts != null) {
            parts.addAll(currentParts);
        }

        return parts;
    }

    /**
     * Adds explicit type casting for literals to fix PostgreSQL polymorphic type resolution.
     *
     * <p>PostgreSQL's to_jsonb() function is polymorphic and cannot determine the type of
     * "unknown" literals (string literals without explicit type). This causes errors like:
     * "ERROR: could not determine polymorphic type because input has type unknown"</p>
     *
     * <p><b>Examples:</b></p>
     * <pre>
     * Input: 'Hello'       Output: 'Hello'::text
     * Input: 123           Output: 123 (unchanged - numeric literals are typed)
     * Input: v_variable    Output: v_variable (unchanged - variables have types)
     * Input: NULL          Output: NULL (unchanged - NULL is handled by to_jsonb)
     * </pre>
     *
     * @param value The expression value to potentially cast
     * @return The value with explicit cast if needed, or original value
     */
    private static String addExplicitCastForLiterals(String value) {
        if (value == null) {
            return value;
        }

        String trimmed = value.trim();

        // Check if it's a string literal (starts and ends with quotes)
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            // Add ::text cast for string literals
            return trimmed + "::text";
        }

        // For all other cases (variables, numbers, NULL, expressions), return unchanged
        // - Numeric literals: Already typed (e.g., 123 is integer, 12.5 is numeric)
        // - Variables: Have declared types
        // - NULL: Handled by to_jsonb()
        // - Expressions: Type inference determines type
        return value;
    }

    /**
     * Tries to transform a collection element assignment to jsonb_set call (Phase 1C.5 + 1D).
     *
     * <p>Detects and transforms collection element assignment patterns:
     * <ul>
     *   <li>Array (TABLE OF/VARRAY): v_nums(1) := 100 → v_nums := jsonb_set(v_nums, '{0}', to_jsonb(100))</li>
     *   <li>Map (INDEX BY): v_map('dept10') := 'Eng' → v_map := jsonb_set(v_map, '{dept10}', to_jsonb('Eng'))</li>
     * </ul>
     *
     * <p>Detection logic:
     * <ol>
     *   <li>Check if general_element is a simple part (no dot navigation)</li>
     *   <li>Check if it has exactly ONE argument (collection element access)</li>
     *   <li>Determine if argument is string literal (map) or numeric (array)</li>
     *   <li>Build jsonb_set call with appropriate path</li>
     * </ol>
     *
     * @param elemCtx General element context (LHS)
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed jsonb_set call, or null if not a collection element assignment
     */
    private static String tryTransformCollectionElementAssignment(
            PlSqlParser.General_elementContext elemCtx,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        // Check if this is a simple element (no nested general_element = no dot navigation)
        if (elemCtx.general_element() != null) {
            return null; // Dotted access, not a simple collection element
        }

        // Get the parts
        java.util.List<PlSqlParser.General_element_partContext> parts = elemCtx.general_element_part();
        if (parts == null || parts.size() != 1) {
            return null; // Must be exactly one part
        }

        PlSqlParser.General_element_partContext part = parts.get(0);

        // Must have exactly ONE argument (collection element access)
        java.util.List<PlSqlParser.Function_argumentContext> funcArgCtxList = part.function_argument();
        if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
            return null; // No arguments
        }

        PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);
        java.util.List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
        if (arguments == null || arguments.size() != 1) {
            return null; // Must be exactly one argument for element access
        }

        // Get variable name
        String variableName = IdentifierHelper.unquote(part.id_expression().getText());

        // Transform argument expression
        PlSqlParser.ArgumentContext arg = arguments.get(0);
        if (arg.expression() == null) {
            return null;
        }

        String argValue = b.visit(arg.expression());

        // Transform RHS expression
        String rightSide = b.visit(assignCtx.expression());
        String castedValue = addExplicitCastForLiterals(rightSide);

        // Determine if this is array or map access based on argument type
        boolean isStringKey = isStringLiteral(argValue);

        String pathExpression;
        if (isStringKey) {
            // MAP ACCESS: v_map('dept10') := value → v_map := jsonb_set(v_map, '{dept10}', to_jsonb(value))
            // Extract string value (remove quotes)
            String keyValue = argValue.substring(1, argValue.length() - 1);
            pathExpression = "'{ " + keyValue + " }'";
        } else {
            // ARRAY ACCESS: v_nums(1) := value → v_nums := jsonb_set(v_nums, '{0}', to_jsonb(value))
            // Apply 1-based → 0-based index conversion

            // Check if argument is a simple numeric literal
            boolean isNumericLiteral = argValue.matches("\\d+");

            String indexExpression;
            if (isNumericLiteral) {
                // Simple numeric literal: v_nums(1) → '{0}'
                int oracleIndex = Integer.parseInt(argValue);
                int postgresIndex = oracleIndex - 1;
                indexExpression = String.valueOf(postgresIndex);
            } else {
                // Variable or expression: v_nums(i) → '{' || (i - 1) || '}'
                // Use PostgreSQL concatenation to build dynamic path
                // Note: jsonb_set requires text array for path, so we use text[] constructor
                // Actually, jsonb_set accepts text path like '{0}' so we need to build it dynamically
                // For Phase 1C.5, we'll use a simpler approach with array notation
                indexExpression = "' || ( " + argValue + " - 1 ) || '";
            }

            pathExpression = "'{ " + indexExpression + " }'";
        }

        // Build jsonb_set call
        // Syntax: variable := jsonb_set(variable, path, to_jsonb(value))
        StringBuilder result = new StringBuilder();
        result.append(variableName);
        result.append(" := jsonb_set( ");
        result.append(variableName);
        result.append(" , ");
        result.append(pathExpression);
        result.append(" , to_jsonb( ");
        result.append(castedValue);
        result.append(" ) )");

        return result.toString();
    }

    /**
     * Checks if a value is a string literal (enclosed in single quotes).
     *
     * @param value Value to check
     * @return true if value is a string literal
     */
    private static boolean isStringLiteral(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }

        String trimmed = value.trim();
        return trimmed.startsWith("'") && trimmed.endsWith("'");
    }

    /**
     * Tries to transform a package variable field assignment to setter with jsonb_set.
     *
     * <p>Detects and transforms package variable RECORD field assignment patterns:
     * <ul>
     *   <li>Unqualified (inside package): {@code g_rec.field := value} →
     *       {@code PERFORM pkg__set_g_rec(jsonb_set(pkg__get_g_rec(), '{field}', to_jsonb(value)))}</li>
     *   <li>Package-qualified: {@code pkg.g_rec.field := value} →
     *       {@code PERFORM pkg__set_g_rec(jsonb_set(pkg__get_g_rec(), '{field}', to_jsonb(value)))}</li>
     *   <li>Schema-qualified: {@code schema.pkg.g_rec.field := value} →
     *       {@code PERFORM schema.pkg__set_g_rec(jsonb_set(schema.pkg__get_g_rec(), '{field}', to_jsonb(value)))}</li>
     *   <li>Nested fields: {@code pkg.g_rec.addr.city := value} →
     *       {@code PERFORM pkg__set_g_rec(jsonb_set(pkg__get_g_rec(), '{addr,city}', to_jsonb(value), true))}</li>
     * </ul>
     *
     * @param elemCtx General element context (LHS)
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed PERFORM setter call, or null if not a package variable field assignment
     */
    private static String tryTransformPackageVariableFieldAssignment(
            PlSqlParser.General_elementContext elemCtx,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        // Must have nested general_element (dotted access)
        if (elemCtx.general_element() == null) {
            return null;
        }

        java.util.List<PlSqlParser.General_element_partContext> parts = collectAllParts(elemCtx);
        if (parts.size() < 2) {
            return null; // Need at least var.field
        }

        TransformationContext context = b.getContext();
        if (context == null) {
            return null;
        }

        // Determine the pattern based on number of parts and context
        String schemaPrefix;
        String packageName;
        String variableName;
        int fieldStartIndex;
        PackageContext pkgContext = null;

        // Try Pattern 1: Unqualified inside package function (g_rec.field)
        if (parts.size() >= 2 && context.isInPackageMember()) {
            String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
            String currentPackage = context.getCurrentPackageName();

            if (currentPackage != null && b.isPackageVariable(currentPackage, firstPart)) {
                pkgContext = context.getPackageContext(currentPackage);
                if (pkgContext != null) {
                    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(firstPart);
                    if (pkgVar != null) {
                        InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
                        if (inlineType != null && inlineType.isRecord()) {
                            schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
                            packageName = currentPackage;
                            variableName = firstPart;
                            fieldStartIndex = 1;
                            return buildPackageVariableFieldAssignment(
                                schemaPrefix, packageName, variableName, fieldStartIndex,
                                parts, pkgContext, assignCtx, b);
                        }
                    }
                }
            }
        }

        // Try Pattern 2: Package-qualified (pkg.g_rec.field)
        if (parts.size() >= 3) {
            String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
            String secondPart = IdentifierHelper.unquote(parts.get(1).id_expression().getText());

            if (b.isPackageVariable(firstPart, secondPart)) {
                pkgContext = context.getPackageContext(firstPart);
                if (pkgContext != null) {
                    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(secondPart);
                    if (pkgVar != null) {
                        InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
                        if (inlineType != null && inlineType.isRecord()) {
                            schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
                            packageName = firstPart;
                            variableName = secondPart;
                            fieldStartIndex = 2;
                            return buildPackageVariableFieldAssignment(
                                schemaPrefix, packageName, variableName, fieldStartIndex,
                                parts, pkgContext, assignCtx, b);
                        }
                    }
                }
            }
        }

        // Try Pattern 3: Schema-qualified (schema.pkg.g_rec.field)
        if (parts.size() >= 4) {
            String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
            String secondPart = IdentifierHelper.unquote(parts.get(1).id_expression().getText());
            String thirdPart = IdentifierHelper.unquote(parts.get(2).id_expression().getText());
            String currentSchema = context.getCurrentSchema();

            if (firstPart.equalsIgnoreCase(currentSchema) && b.isPackageVariable(secondPart, thirdPart)) {
                pkgContext = context.getPackageContext(secondPart);
                if (pkgContext != null) {
                    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(thirdPart);
                    if (pkgVar != null) {
                        InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
                        if (inlineType != null && inlineType.isRecord()) {
                            schemaPrefix = currentSchema.toLowerCase() + ".";
                            packageName = secondPart;
                            variableName = thirdPart;
                            fieldStartIndex = 3;
                            return buildPackageVariableFieldAssignment(
                                schemaPrefix, packageName, variableName, fieldStartIndex,
                                parts, pkgContext, assignCtx, b);
                        }
                    }
                }
            }
        }

        // Not a package variable field assignment
        return null;
    }

    /**
     * Builds the PostgreSQL PERFORM statement for package variable field assignment.
     *
     * <p>Transforms to:
     * {@code PERFORM schema.pkg__set_varname(jsonb_set(schema.pkg__get_varname(), '{field}', to_jsonb(value)))}
     *
     * @param schemaPrefix Schema prefix with dot (e.g., "hr.")
     * @param packageName Package name
     * @param variableName Variable name
     * @param fieldStartIndex Index where field path starts in parts
     * @param parts All dot-separated parts
     * @param pkgContext Package context for type lookup
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed PERFORM statement
     */
    private static String buildPackageVariableFieldAssignment(
            String schemaPrefix,
            String packageName,
            String variableName,
            int fieldStartIndex,
            java.util.List<PlSqlParser.General_element_partContext> parts,
            PackageContext pkgContext,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        String pkgLower = packageName.toLowerCase();
        String varLower = variableName.toLowerCase();

        // Build getter and setter names
        String getterCall = schemaPrefix + pkgLower + "__get_" + varLower + "()";
        String setterName = schemaPrefix + pkgLower + "__set_" + varLower;

        // Build field path for jsonb_set: '{field1,field2,...}'
        StringBuilder fieldPath = new StringBuilder();
        fieldPath.append("'{ ");
        for (int i = fieldStartIndex; i < parts.size(); i++) {
            if (i > fieldStartIndex) {
                fieldPath.append(" , ");
            }
            fieldPath.append(IdentifierHelper.unquote(parts.get(i).id_expression().getText()));
        }
        fieldPath.append(" }'");

        // Transform RHS expression
        String rightSide = b.visit(assignCtx.expression());
        String castedValue = addExplicitCastForLiterals(rightSide);

        // Build: PERFORM setter(jsonb_set(getter(), '{path}', to_jsonb(value), true))
        StringBuilder result = new StringBuilder();
        result.append("PERFORM ").append(setterName).append("( ");
        result.append("jsonb_set( ");
        result.append(getterCall);
        result.append(" , ");
        result.append(fieldPath);
        result.append(" , to_jsonb( ");
        result.append(castedValue);
        result.append(" )");

        // Add 'true' flag for nested paths (creates missing intermediate objects)
        int fieldCount = parts.size() - fieldStartIndex;
        if (fieldCount > 1) {
            result.append(" , true");
        }

        result.append(" ) )");

        return result.toString();
    }

    /**
     * Tries to transform a package variable collection element assignment to setter with jsonb_set.
     *
     * <p>Detects and transforms package variable collection element assignment patterns:
     * <ul>
     *   <li>Package-qualified: {@code pkg.g_array(1) := value} →
     *       {@code PERFORM pkg__set_g_array(jsonb_set(pkg__get_g_array(), '{0}', to_jsonb(value)))}</li>
     *   <li>Unqualified (inside package): {@code g_array(1) := value} →
     *       {@code PERFORM pkg__set_g_array(jsonb_set(pkg__get_g_array(), '{0}', to_jsonb(value)))}</li>
     *   <li>Schema-qualified: {@code schema.pkg.g_array(1) := value} →
     *       {@code PERFORM schema.pkg__set_g_array(jsonb_set(schema.pkg__get_g_array(), '{0}', to_jsonb(value)))}</li>
     *   <li>Map: {@code pkg.g_map('key') := value} →
     *       {@code PERFORM pkg__set_g_map(jsonb_set(pkg__get_g_map(), '{key}', to_jsonb(value)))}</li>
     * </ul>
     *
     * @param elemCtx General element context (LHS)
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed PERFORM setter call, or null if not a package variable collection assignment
     */
    private static String tryTransformPackageVariableCollectionAssignment(
            PlSqlParser.General_elementContext elemCtx,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        TransformationContext context = b.getContext();
        if (context == null) {
            return null;
        }

        // Collect all parts - we need to find the pattern with function arguments
        java.util.List<PlSqlParser.General_element_partContext> parts = collectAllParts(elemCtx);
        if (parts.isEmpty()) {
            return null;
        }

        // Last part should have function arguments (the collection element access)
        PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
        java.util.List<PlSqlParser.Function_argumentContext> funcArgList = lastPart.function_argument();
        if (funcArgList == null || funcArgList.isEmpty()) {
            return null;  // No function arguments - not collection element access
        }

        PlSqlParser.Function_argumentContext funcArgCtx = funcArgList.get(0);
        java.util.List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
        if (arguments == null || arguments.size() != 1) {
            return null;  // Collection access has exactly one argument
        }

        String argValue = b.visit(arguments.get(0).expression());

        // Determine the pattern based on number of parts and context
        String schemaPrefix;
        String packageName;
        String variableName = IdentifierHelper.unquote(lastPart.id_expression().getText());
        PackageContext pkgContext = null;

        if (parts.size() == 1) {
            // Pattern 1: Unqualified inside package function (g_array(1))
            if (!context.isInPackageMember()) {
                return null;  // Must be inside a package
            }
            packageName = context.getCurrentPackageName();
            if (packageName == null || !b.isPackageVariable(packageName, variableName)) {
                return null;
            }
            schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
            pkgContext = context.getPackageContext(packageName);
        } else if (parts.size() == 2) {
            // Pattern 2: Package-qualified (pkg.g_array(1))
            packageName = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
            if (!b.isPackageVariable(packageName, variableName)) {
                return null;
            }
            schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
            pkgContext = context.getPackageContext(packageName);
        } else if (parts.size() == 3) {
            // Pattern 3: Schema-qualified (schema.pkg.g_array(1))
            String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
            String currentSchema = context.getCurrentSchema();
            if (!firstPart.equalsIgnoreCase(currentSchema)) {
                return null;  // Cross-schema not supported
            }
            packageName = IdentifierHelper.unquote(parts.get(1).id_expression().getText());
            if (!b.isPackageVariable(packageName, variableName)) {
                return null;
            }
            schemaPrefix = currentSchema.toLowerCase() + ".";
            pkgContext = context.getPackageContext(packageName);
        } else {
            return null;  // Too many parts for collection access
        }

        // Verify it's a collection type
        if (pkgContext == null) {
            return null;
        }

        PackageContext.PackageVariable pkgVar = pkgContext.getVariable(variableName);
        if (pkgVar == null) {
            return null;
        }

        InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
        if (inlineType == null || !inlineType.isCollection()) {
            return null;  // Not a collection type
        }

        // IT'S PACKAGE VARIABLE COLLECTION ELEMENT ASSIGNMENT!
        return buildPackageVariableCollectionAssignment(
            schemaPrefix, packageName, variableName, argValue, inlineType, assignCtx, b);
    }

    /**
     * Builds the PostgreSQL PERFORM statement for package variable collection element assignment.
     *
     * <p>Transforms to:
     * {@code PERFORM schema.pkg__set_varname(jsonb_set(schema.pkg__get_varname(), '{index}', to_jsonb(value)))}
     *
     * @param schemaPrefix Schema prefix with dot (e.g., "hr.")
     * @param packageName Package name
     * @param variableName Variable name
     * @param argValue Index/key argument (already transformed)
     * @param inlineType Inline type definition for the collection
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed PERFORM statement
     */
    private static String buildPackageVariableCollectionAssignment(
            String schemaPrefix,
            String packageName,
            String variableName,
            String argValue,
            InlineTypeDefinition inlineType,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        String pkgLower = packageName.toLowerCase();
        String varLower = variableName.toLowerCase();

        // Build getter and setter names
        String getterCall = schemaPrefix + pkgLower + "__get_" + varLower + "()";
        String setterName = schemaPrefix + pkgLower + "__set_" + varLower;

        // Transform RHS expression
        String rightSide = b.visit(assignCtx.expression());
        String castedValue = addExplicitCastForLiterals(rightSide);

        // Determine path expression based on collection type
        // IMPORTANT: jsonb_set requires text[] for path. Use ARRAY[] for dynamic paths
        // and explicit ::text[] cast for static paths to avoid "function does not exist" errors.
        String pathExpression;
        if (inlineType.isAssociativeArray() && isStringIndexKeyType(inlineType.getIndexKeyType())) {
            // MAP: Use string key as-is
            if (isStringLiteral(argValue)) {
                // Extract key value (remove quotes): 'key' → key
                // Static key: use text array literal with explicit cast
                String keyValue = argValue.substring(1, argValue.length() - 1);
                pathExpression = "'{" + keyValue + "}'::text[]";
            } else {
                // Variable key: build dynamic path using ARRAY constructor
                pathExpression = "ARRAY[" + argValue + "]";
            }
        } else {
            // ARRAY: Apply 1-based → 0-based index conversion
            boolean isNumericLiteral = argValue.matches("\\d+");

            if (isNumericLiteral) {
                int oracleIndex = Integer.parseInt(argValue);
                int postgresIndex = oracleIndex - 1;
                // Static index: use text array literal with explicit cast
                pathExpression = "'{" + postgresIndex + "}'::text[]";
            } else {
                // Variable index: build dynamic path using ARRAY constructor
                // Cast to text for jsonb_set path requirement
                pathExpression = "ARRAY[( " + argValue + " - 1 )::text]";
            }
        }

        // Build: PERFORM setter(jsonb_set(getter(), '{path}', to_jsonb(value)))
        StringBuilder result = new StringBuilder();
        result.append("PERFORM ").append(setterName).append("( ");
        result.append("jsonb_set( ");
        result.append(getterCall);
        result.append(" , ");
        result.append(pathExpression);
        result.append(" , to_jsonb( ");
        result.append(castedValue);
        result.append(" ) ) )");

        return result.toString();
    }

    /**
     * Checks if the INDEX BY key type is string-based.
     *
     * @param indexKeyType Index key type from InlineTypeDefinition
     * @return true if string-based key type
     */
    private static boolean isStringIndexKeyType(String indexKeyType) {
        if (indexKeyType == null) {
            return false;
        }
        String upperType = indexKeyType.toUpperCase();
        return upperType.startsWith("VARCHAR2") ||
               upperType.startsWith("VARCHAR") ||
               upperType.startsWith("STRING") ||
               upperType.startsWith("CHAR");
    }
}
