package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Static helper for visiting PL/SQL call statements.
 *
 * <p>Transforms Oracle procedure/function calls to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Call Statement:</h3>
 * <pre>
 * procedure_name(args);
 * package.procedure(args);
 * schema.package.function(args);
 * function_name(args) INTO variable;
 * </pre>
 *
 * <h3>PostgreSQL Call Statement:</h3>
 * <pre>
 * PERFORM schema.procedure_name(args);
 * PERFORM schema.package__procedure(args);
 * PERFORM schema.package__function(args);
 * SELECT schema.function_name(args) INTO variable;
 * </pre>
 *
 * <h3>Key Transformations:</h3>
 * <ul>
 *   <li>Standalone calls → PERFORM (procedures and functions without return value usage)</li>
 *   <li>Calls with INTO → SELECT ... INTO</li>
 *   <li>Package members → Flatten: package.function → package__function</li>
 *   <li>Synonym resolution → Resolve to actual schema.object</li>
 *   <li>Schema qualification → Add current schema if unqualified</li>
 * </ul>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PERFORM works for both procedures and functions (return value discarded)</li>
 *   <li>Synonym resolution follows same pattern as VisitGeneralElement</li>
 *   <li>Chained method calls (obj.method1().method2()) not yet supported</li>
 * </ul>
 */
public class VisitCall_statement {

    /**
     * Transforms call statement to PostgreSQL syntax.
     *
     * @param ctx Call statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting arguments and accessing context)
     * @return PostgreSQL PERFORM or SELECT INTO statement
     */
    public static String v(PlSqlParser.Call_statementContext ctx, PostgresCodeBuilder b) {

        // STEP 1: Extract routine name parts (schema.package.function or package.function or function)
        List<String> routineParts = extractRoutineParts(ctx.routine_name(0));

        // SPECIAL CASE: Handle RAISE_APPLICATION_ERROR
        // Oracle: RAISE_APPLICATION_ERROR(-20001, 'Error message');
        // PostgreSQL: RAISE EXCEPTION 'Error message' USING ERRCODE = 'P0001', HINT = 'Original Oracle error code: -20001';
        if (isRaiseApplicationError(routineParts)) {
            return transformRaiseApplicationError(ctx, b);
        }

        // STEP 2: Check for chained calls (obj.method1().method2())
        // Grammar: routine_name function_argument? ('.' routine_name function_argument?)*
        List<PlSqlParser.Routine_nameContext> allRoutineNames = ctx.routine_name();
        if (allRoutineNames.size() > 1) {
            // Chained calls - not yet supported (would be type member methods)
            throw new TransformationException(
                "Chained method calls not yet supported in call statements. " +
                "Use separate statements or rewrite as nested function calls.");
        }

        // STEP 3: Apply synonym resolution
        TransformationContext context = b.getContext();
        if (context != null) {
            routineParts = resolveSynonyms(routineParts, context);
        }

        // STEP 4: Flatten package member calls and qualify with schema
        String qualifiedName = buildQualifiedName(routineParts, context);

        // STEP 5: Transform function arguments
        String arguments = transformArguments(ctx.function_argument(0), b);

        // STEP 6: Handle INTO clause
        if (ctx.INTO() != null) {
            // SELECT function(...) INTO variable
            PlSqlParser.Bind_variableContext bindVar = ctx.bind_variable();
            if (bindVar == null) {
                throw new TransformationException("INTO clause missing bind variable");
            }

            // Visit bind_variable to strip : prefix if present
            String variable = b.visit(bindVar);

            return "SELECT " + qualifiedName + arguments + " INTO " + variable + ";";
        } else {
            // PERFORM procedure/function(...)
            return "PERFORM " + qualifiedName + arguments + ";";
        }
    }

    /**
     * Extracts routine name parts from routine_name context.
     *
     * <p>Grammar: routine_name: identifier ('.' id_expression)* ('@' link_name)?
     *
     * <p>Examples:
     * - function_name → ["function_name"]
     * - package.function → ["package", "function"]
     * - schema.package.function → ["schema", "package", "function"]
     *
     * @param routineNameCtx Routine name context
     * @return List of name parts
     */
    private static List<String> extractRoutineParts(PlSqlParser.Routine_nameContext routineNameCtx) {
        List<String> parts = new ArrayList<>();

        // First part: identifier
        if (routineNameCtx.identifier() != null) {
            parts.add(routineNameCtx.identifier().getText());
        }

        // Additional parts: id_expression (after dots)
        if (routineNameCtx.id_expression() != null) {
            for (PlSqlParser.Id_expressionContext idExpr : routineNameCtx.id_expression()) {
                parts.add(idExpr.getText());
            }
        }

        // Ignore link_name (@dblink) - not supported in PostgreSQL
        if (routineNameCtx.link_name() != null) {
            throw new TransformationException(
                "Database links (@dblink) not supported in PostgreSQL. " +
                "Consider using foreign data wrappers (FDW) instead.");
        }

        return parts;
    }

    /**
     * Resolves synonyms in routine name parts.
     *
     * <p>Only applies to single-part names (unqualified references).
     *
     * <p>Examples:
     * - ["emp_pkg"] + synonym emp_pkg → hr.employee_package → ["hr", "employee_package"]
     * - ["hr", "emp_pkg"] → no synonym lookup (already qualified)
     * - ["function_name"] + no synonym → ["function_name"]
     *
     * @param parts Routine name parts
     * @param context Transformation context
     * @return Resolved parts (may be unchanged)
     */
    private static List<String> resolveSynonyms(List<String> parts, TransformationContext context) {
        if (parts.size() != 1) {
            // Already qualified (schema.package or schema.package.function) - no synonym lookup
            return parts;
        }

        String name = parts.get(0);
        String resolved = context.resolveSynonym(name);

        if (resolved != null) {
            // Synonym resolved to "schema.object" or "schema.package.object"
            // Split and return as list
            return Arrays.asList(resolved.toLowerCase().split("\\."));
        }

        return parts;
    }

    /**
     * Builds qualified routine name with schema prefix and package flattening.
     *
     * <p>Transformation rules:
     * <ul>
     *   <li>1 part: function → schema.function</li>
     *   <li>2 parts: package.function → schema.package__function</li>
     *   <li>3 parts: schema.package.function → schema.package__function</li>
     * </ul>
     *
     * <p>Note: Always adds schema prefix for consistency (PostgreSQL search_path may not include migration schema).
     *
     * @param parts Routine name parts (after synonym resolution)
     * @param context Transformation context (can be null)
     * @return Qualified routine name
     */
    private static String buildQualifiedName(List<String> parts, TransformationContext context) {
        String currentSchema = (context != null) ? context.getCurrentSchema().toLowerCase() : "public";

        if (parts.size() == 1) {
            // Single part: function_name → schema.function_name
            String functionName = parts.get(0).toLowerCase();
            return currentSchema + "." + functionName;

        } else if (parts.size() == 2) {
            // Two parts: package.function → schema.package__function
            String packageName = parts.get(0).toLowerCase();
            String functionName = parts.get(1).toLowerCase();
            return currentSchema + "." + packageName + "__" + functionName;

        } else if (parts.size() == 3) {
            // Three parts: schema.package.function → schema.package__function
            String schemaName = parts.get(0).toLowerCase();
            String packageName = parts.get(1).toLowerCase();
            String functionName = parts.get(2).toLowerCase();
            return schemaName + "." + packageName + "__" + functionName;

        } else {
            throw new TransformationException(
                "Routine name with more than 3 parts not supported: " + parts);
        }
    }

    /**
     * Transforms function arguments.
     *
     * <p>Grammar: function_argument: '(' (argument (',' argument)*)? ')' keep_clause?
     *
     * @param funcArgCtx Function argument context (can be null for parameterless calls)
     * @param b PostgresCodeBuilder for visiting expressions
     * @return Transformed arguments as string: "( arg1 , arg2 , ... )" or "( )"
     */
    private static String transformArguments(
            PlSqlParser.Function_argumentContext funcArgCtx,
            PostgresCodeBuilder b) {

        if (funcArgCtx == null) {
            // No arguments: function_name (no parentheses in Oracle)
            // PostgreSQL requires parentheses
            return "( )";
        }

        // Get all argument contexts
        List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
        if (arguments == null || arguments.isEmpty()) {
            return "( )";
        }

        // Transform each argument
        List<String> transformedArgs = arguments.stream()
            .map(arg -> transformArgument(arg, b))
            .collect(Collectors.toList());

        return "( " + String.join(" , ", transformedArgs) + " )";
    }

    /**
     * Transforms a single argument (an expression or named parameter).
     *
     * <p>Grammar: argument: (id_expression '=' '>')? expression
     *
     * <p>Note: PostgreSQL doesn't support named parameters in the same way as Oracle.
     * Named parameters in PostgreSQL use := syntax and are handled differently.
     * For now, we'll just transform the expression and ignore the name.
     *
     * @param argCtx Argument context
     * @param b PostgresCodeBuilder for visiting expression
     * @return Transformed expression
     */
    private static String transformArgument(
            PlSqlParser.ArgumentContext argCtx,
            PostgresCodeBuilder b) {

        // argument: (id_expression '=' '>')? expression
        // For named parameters, we'd need to handle the id_expression part
        // For now, just transform the expression (positional parameters)
        if (argCtx.expression() != null) {
            return b.visit(argCtx.expression());
        }

        // Fallback: just get the text
        return argCtx.getText();
    }

    /**
     * Checks if the routine is RAISE_APPLICATION_ERROR.
     *
     * @param routineParts Routine name parts
     * @return True if it's RAISE_APPLICATION_ERROR (case-insensitive)
     */
    private static boolean isRaiseApplicationError(List<String> routineParts) {
        // Must be single-part name (no schema qualification)
        if (routineParts.size() != 1) {
            return false;
        }

        String name = routineParts.get(0);
        return "RAISE_APPLICATION_ERROR".equalsIgnoreCase(name);
    }

    /**
     * Transforms RAISE_APPLICATION_ERROR to PostgreSQL RAISE EXCEPTION.
     *
     * <p>Transformation pattern:
     * <pre>
     * -- Oracle
     * RAISE_APPLICATION_ERROR(-20001, 'Custom error message');
     *
     * -- PostgreSQL
     * RAISE EXCEPTION 'Custom error message'
     *   USING ERRCODE = 'P0001',
     *         HINT = 'Original Oracle error code: -20001';
     * </pre>
     *
     * <p>Error code mapping:
     * <ul>
     *   <li>Oracle user error range: -20000 to -20999</li>
     *   <li>PostgreSQL user SQLSTATE range: 'P0001' to 'P0999'</li>
     *   <li>Formula: ERRCODE = 'P' + LPAD((oracle_code + 20000), 4, '0')</li>
     *   <li>Example: -20001 → 'P0001', -20055 → 'P0055', -20999 → 'P0999'</li>
     * </ul>
     *
     * @param ctx Call statement context
     * @param b PostgresCodeBuilder for visiting expressions
     * @return PostgreSQL RAISE EXCEPTION statement
     */
    private static String transformRaiseApplicationError(
            PlSqlParser.Call_statementContext ctx,
            PostgresCodeBuilder b) {

        // Extract arguments: RAISE_APPLICATION_ERROR(error_code, message)
        PlSqlParser.Function_argumentContext funcArgCtx = ctx.function_argument(0);

        if (funcArgCtx == null || funcArgCtx.argument() == null || funcArgCtx.argument().size() < 2) {
            throw new TransformationException(
                "RAISE_APPLICATION_ERROR requires 2 arguments: error_code and message. " +
                "Found: " + (funcArgCtx == null ? 0 : (funcArgCtx.argument() == null ? 0 : funcArgCtx.argument().size())));
        }

        List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();

        // Argument 1: Error code (must be numeric literal -20000 to -20999)
        PlSqlParser.ArgumentContext errorCodeArg = arguments.get(0);
        String errorCodeExpr = b.visit(errorCodeArg.expression());

        // Argument 2: Error message (string expression)
        PlSqlParser.ArgumentContext messageArg = arguments.get(1);
        String messageExpr = b.visit(messageArg.expression());

        // Try to extract numeric error code for mapping
        String errcode;
        String originalCode;

        try {
            // Parse error code (may be negative literal like "-20001" or expression)
            int oracleErrorCode = Integer.parseInt(errorCodeExpr.trim());

            // Validate range
            if (oracleErrorCode < -20999 || oracleErrorCode > -20000) {
                throw new TransformationException(
                    "RAISE_APPLICATION_ERROR error code must be in range -20000 to -20999. " +
                    "Found: " + oracleErrorCode);
            }

            // Map to PostgreSQL SQLSTATE (P0001 to P0999)
            // Formula: P + LPAD(abs(oracle_code) - 20000, 4, '0')
            // Oracle: -20001 to -20999 → PostgreSQL: P0001 to P0999
            int pgCode = Math.abs(oracleErrorCode) - 20000;
            // Use %04d to zero-pad to 4 digits (e.g., 1 → 0001, 55 → 0055, 999 → 0999)
            errcode = String.format("'P%04d'", pgCode);
            originalCode = String.valueOf(oracleErrorCode);

        } catch (NumberFormatException e) {
            // Error code is an expression, not a literal
            // Use dynamic error code mapping via oracle_compat.map_error_code() function
            // For now, use a default SQLSTATE and include the expression in HINT
            errcode = "'P0001'";  // Generic user-defined exception
            originalCode = errorCodeExpr;  // Expression text
        }

        // Build RAISE EXCEPTION statement
        StringBuilder result = new StringBuilder();

        // Check if message is a simple string literal or an expression
        boolean isSimpleLiteral = messageExpr.trim().startsWith("'") && messageExpr.trim().endsWith("'");

        if (isSimpleLiteral) {
            // Simple string literal: RAISE EXCEPTION 'message'
            result.append("RAISE EXCEPTION ")
                  .append(messageExpr)
                  .append(" USING ERRCODE = ")
                  .append(errcode);
        } else {
            // Expression: RAISE EXCEPTION USING MESSAGE = expression, ERRCODE = ...
            result.append("RAISE EXCEPTION USING MESSAGE = ")
                  .append(messageExpr)
                  .append(" , ERRCODE = ")
                  .append(errcode);
        }

        // Add HINT with original Oracle error code
        result.append(" , HINT = 'Original Oracle error code: ")
              .append(originalCode)
              .append("'");

        return result.toString();
    }
}
