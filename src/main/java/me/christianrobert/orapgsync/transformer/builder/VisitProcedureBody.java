package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

import java.util.Set;

/**
 * Static helper for visiting PL/SQL procedure bodies.
 *
 * <p>Generates complete CREATE OR REPLACE FUNCTION statement by extracting all
 * information from the ANTLR parse tree. Only requires schema from context.</p>
 *
 * <p>Note: Procedures and functions share the same body structure (DECLARE + BEGIN...END),
 * the only difference is procedures don't have a RETURN type (they return void).</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * PROCEDURE procedure_name (params) IS/AS
 *   [DECLARE declarations]
 * BEGIN
 *   [statements]
 * [EXCEPTION handlers]
 * END;
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL (complete):</h3>
 * <pre>
 * CREATE OR REPLACE FUNCTION schema.procedure_name(params)
 * RETURNS void
 * LANGUAGE plpgsql
 * AS $$
 * [DECLARE
 *   declarations]
 * BEGIN
 *   [statements]
 * [EXCEPTION
 *   handlers]
 * END;
 * $$;
 * </pre>
 */
public class VisitProcedureBody {

    /**
     * Transforms procedure to complete CREATE OR REPLACE FUNCTION statement.
     * Extracts procedure name and parameters from AST.
     * Only uses schema from context (consistent with SQL transformation).
     * Note: PostgreSQL uses FUNCTION with RETURNS void for procedures.
     *
     * @param ctx Procedure body parse tree context
     * @param b PostgresCodeBuilder instance (provides access to context for schema)
     * @return Complete CREATE OR REPLACE FUNCTION statement
     */
    public static String v(PlSqlParser.Procedure_bodyContext ctx, PostgresCodeBuilder b) {
        // Get schema from context (only metadata dependency - consistent with SQL transformation)
        String schema = b.getContext().getCurrentSchema();

        // STEP 1: Extract procedure name from AST and build qualified name
        String procedureName;
        if (ctx.identifier() != null) {
            procedureName = ctx.identifier().getText().toLowerCase();
        } else {
            throw new IllegalStateException("Procedure name not found in AST");
        }

        // Build qualified name based on package membership
        // Package members: schema.packageName__procedureName (double underscore convention)
        // Standalone: schema.procedureName
        String qualifiedName;
        String packageName = b.getContext().getCurrentPackageName();
        if (packageName != null) {
            // Package member procedure: include package prefix with double underscore
            qualifiedName = schema.toLowerCase() + "." +
                           packageName.toLowerCase() + "__" +
                           procedureName;
        } else {
            // Standalone procedure: just schema.procedureName
            qualifiedName = schema.toLowerCase() + "." + procedureName;
        }

        // STEP 2: Build parameter list from AST and count OUT parameters
        StringBuilder paramList = new StringBuilder();
        int outParamCount = 0;
        String singleOutParamType = null;

        if (ctx.parameter() != null && !ctx.parameter().isEmpty()) {
            boolean first = true;
            for (PlSqlParser.ParameterContext paramCtx : ctx.parameter()) {
                String paramString = VisitParameter.v(paramCtx, b);
                if (paramString != null) {
                    if (!first) {
                        paramList.append(", ");
                    }
                    first = false;
                    paramList.append(paramString);

                    // Count OUT and INOUT parameters for RETURNS clause calculation
                    // Both OUT and INOUT parameters contribute to the return value
                    if (paramString.contains(" OUT ") || paramString.contains(" INOUT ")) {
                        outParamCount++;
                        if (outParamCount == 1) {
                            // Extract type for single OUT/INOUT parameter
                            // Format: "param_name OUT type" or "param_name INOUT type"
                            String[] parts = paramString.split("\\s+");
                            if (parts.length >= 3) {
                                singleOutParamType = parts[2]; // type is after "param_name OUT/INOUT"
                            }
                        }
                    }
                }
            }
        }

        // STEP 3: Build procedure body (DECLARE + BEGIN...END)
        StringBuilder procedureBody = new StringBuilder();

        // Reset cursor attribute tracker for this procedure
        // Cursor names are local to procedure scope, so reset for each procedure
        b.resetCursorAttributeTracker();

        // Push loop RECORD variables context for this procedure block
        // When nested anonymous blocks are implemented, they will push their own contexts
        b.pushLoopRecordVariablesContext();

        // Push exception context for this procedure block
        // Tracks user-defined exceptions declared in this procedure's DECLARE section
        b.pushExceptionContext();

        // Push variable scope for this procedure
        // All parameters and DECLARE variables will be registered in this scope
        b.getContext().pushVariableScope("procedure:" + procedureName);

        // Register procedure parameters in scope
        // This enables deterministic variable vs function disambiguation
        if (ctx.parameter() != null && !ctx.parameter().isEmpty()) {
            for (PlSqlParser.ParameterContext paramCtx : ctx.parameter()) {
                registerParameterInScope(paramCtx, b);
            }
        }

        // Pre-scan body for cursor attributes BEFORE transformation
        // This ensures FETCH/OPEN/CLOSE statements can inject tracking code correctly
        // (cursor attributes are encountered during traversal, but FETCH may come before first attribute)
        if (ctx.body() != null) {
            b.prescanCursorAttributes(ctx.body());
        }

        // Visit declarations (if present)
        boolean hasDeclareSection = ctx.seq_of_declare_specs() != null;
        if (hasDeclareSection) {
            procedureBody.append("DECLARE\n");
            String declarations = b.visit(ctx.seq_of_declare_specs());
            procedureBody.append(declarations);
        }

        // Visit the body (BEGIN...END block) - this will register loop variables
        String bodyCode = null;
        if (ctx.body() != null) {
            bodyCode = b.visit(ctx.body());

            // PACKAGE VARIABLE SUPPORT: Inject initialization call for package procedures
            // If this is a package member procedure with variables, inject PERFORM pkg__initialize()
            // at the start of the BEGIN block
            if (b.needsPackageInitialization()) {
                String initCall = b.generatePackageInitializationCall();
                if (initCall != null && bodyCode != null) {
                    // Inject initialization call right after BEGIN
                    // Format: BEGIN\n  PERFORM schema.pkg__initialize();\n  [existing body]
                    bodyCode = injectInitializationCall(bodyCode, initCall);
                }
            }
        }

        // Pop variable scope when leaving procedure
        // Variables go out of scope
        b.getContext().popVariableScope();

        // Pop loop RECORD variables context to get variables for this block
        Set<String> loopVariables = b.popLoopRecordVariablesContext();

        // Pop exception context when leaving procedure block
        // User-defined exceptions go out of scope
        b.popExceptionContext();

        // Inject RECORD declarations for cursor FOR loop variables
        // PostgreSQL requires explicit RECORD declarations, Oracle has implicit declarations
        if (!loopVariables.isEmpty()) {
            // Add DECLARE section if not already present
            if (!hasDeclareSection) {
                procedureBody.append("DECLARE\n");
                hasDeclareSection = true;
            }

            // Add RECORD declarations
            for (String varName : loopVariables) {
                procedureBody.append("  ").append(varName).append(" RECORD;\n");
            }
        }

        // Inject cursor tracking variables for cursors that use attributes
        // These variables are auto-generated after visiting the body (now we know which cursors use attributes)
        java.util.List<String> cursorTrackingVars = b.generateCursorTrackingDeclarations();
        if (!cursorTrackingVars.isEmpty()) {
            // Add DECLARE section if not already present
            if (!hasDeclareSection) {
                procedureBody.append("DECLARE\n");
                hasDeclareSection = true;
            }

            // Add cursor tracking variable declarations
            for (String trackingVar : cursorTrackingVars) {
                procedureBody.append("  ").append(trackingVar).append("\n");
            }
        }

        // Append the body code
        if (bodyCode != null) {
            procedureBody.append(bodyCode);
        }

        // STEP 4: Determine RETURNS clause based on OUT parameters
        String returnsClause;
        if (outParamCount == 0) {
            // No OUT parameters → RETURNS void
            returnsClause = "RETURNS void";
        } else if (outParamCount == 1) {
            // Single OUT parameter → RETURNS <type>
            returnsClause = "RETURNS " + (singleOutParamType != null ? singleOutParamType : "numeric");
        } else {
            // Multiple OUT parameters → RETURNS RECORD
            returnsClause = "RETURNS RECORD";
        }

        // STEP 5: Generate complete CREATE OR REPLACE FUNCTION statement
        // Note: PostgreSQL uses FUNCTION with appropriate RETURNS clause for procedures
        StringBuilder result = new StringBuilder();
        result.append("CREATE OR REPLACE FUNCTION ").append(qualifiedName);
        result.append("(").append(paramList).append(")\n");
        result.append(returnsClause).append("\n");
        result.append("LANGUAGE plpgsql\n");
        result.append("AS $$\n");
        result.append(procedureBody);
        if (!procedureBody.toString().trim().endsWith(";")) {
            result.append("\n");
        }
        result.append("$$;\n");

        return result.toString();
    }

    /**
     * Injects package initialization call into the BEGIN block.
     * Inserts the call right after the BEGIN keyword.
     *
     * @param bodyCode Original body code (BEGIN...END block)
     * @param initCall Initialization call (e.g., "PERFORM schema.pkg__initialize()")
     * @return Modified body code with initialization call injected
     */
    private static String injectInitializationCall(String bodyCode, String initCall) {
        // Find BEGIN keyword and inject initialization call right after it
        // Pattern: BEGIN\n  [statements]
        // Result:  BEGIN\n  PERFORM schema.pkg__initialize();\n  [statements]

        int beginIndex = bodyCode.indexOf("BEGIN");
        if (beginIndex == -1) {
            // No BEGIN found - return original (shouldn't happen for valid PL/SQL)
            return bodyCode;
        }

        // Find the end of the BEGIN line (newline after BEGIN)
        int endOfLineIndex = bodyCode.indexOf('\n', beginIndex);
        if (endOfLineIndex == -1) {
            // No newline after BEGIN - inject at end of string (edge case)
            return bodyCode + "\n  " + initCall + ";\n";
        }

        // Build new body: [before BEGIN\n] + [BEGIN\n] + [  initCall;\n] + [rest]
        StringBuilder result = new StringBuilder();
        result.append(bodyCode, 0, endOfLineIndex + 1); // Include BEGIN\n
        result.append("  ").append(initCall).append(";\n");
        result.append(bodyCode.substring(endOfLineIndex + 1)); // Rest of body

        return result.toString();
    }

    /**
     * Registers a procedure parameter in the current variable scope.
     *
     * <p>Parameters are treated as variables for scope lookup purposes.
     * This enables deterministic disambiguation: if an identifier matches a parameter,
     * it's a variable reference, not a function call.</p>
     *
     * @param paramCtx Parameter parse tree context
     * @param b PostgresCodeBuilder instance (for context access)
     */
    private static void registerParameterInScope(PlSqlParser.ParameterContext paramCtx, PostgresCodeBuilder b) {
        // Extract parameter name
        String paramName = paramCtx.parameter_name().getText().toLowerCase();

        // Extract parameter type
        String oracleType = paramCtx.type_spec().getText();
        String postgresType = TypeConverter.toPostgre(oracleType);

        // Parameters are never CONSTANT in the DECLARE sense (though they may be IN parameters)
        // Parameters typically don't have default values in the body scope (default values are in signature)
        TransformationContext.VariableDefinition paramDef = new TransformationContext.VariableDefinition(
            paramName,
            oracleType,
            postgresType,
            false,  // not constant
            null,   // no default value in body scope
            null    // parameters are simple types, not inline types
        );

        b.getContext().registerVariable(paramName, paramDef);
    }
}
