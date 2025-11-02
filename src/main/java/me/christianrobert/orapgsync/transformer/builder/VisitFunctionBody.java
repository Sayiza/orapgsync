package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;

import java.util.Set;

/**
 * Static helper for visiting PL/SQL function bodies.
 *
 * <p>Generates complete CREATE OR REPLACE FUNCTION statement by extracting all
 * information from the ANTLR parse tree. Only requires schema from context.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * FUNCTION function_name (params) RETURN type IS/AS
 *   [DECLARE declarations]
 * BEGIN
 *   [statements]
 * [EXCEPTION handlers]
 * END;
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL (complete):</h3>
 * <pre>
 * CREATE OR REPLACE FUNCTION schema.function_name(params)
 * RETURNS return_type
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
public class VisitFunctionBody {

    /**
     * Transforms function to complete CREATE OR REPLACE FUNCTION statement.
     * Extracts function name, parameters, and return type from AST.
     * Only uses schema from context (consistent with SQL transformation).
     *
     * @param ctx Function body parse tree context
     * @param b PostgresCodeBuilder instance (provides access to context for schema)
     * @return Complete CREATE OR REPLACE FUNCTION statement
     */
    public static String v(PlSqlParser.Function_bodyContext ctx, PostgresCodeBuilder b) {
        // Get schema from context (only metadata dependency - consistent with SQL transformation)
        String schema = b.getContext().getCurrentSchema();

        // STEP 1: Extract function name from AST and build qualified name
        String functionName;
        if (ctx.identifier() != null) {
            functionName = ctx.identifier().getText().toLowerCase();
        } else {
            throw new IllegalStateException("Function name not found in AST");
        }

        // Build qualified name based on package membership
        // Package members: schema.packageName__functionName (double underscore convention)
        // Standalone: schema.functionName
        String qualifiedName;
        String packageName = b.getContext().getCurrentPackageName();
        if (packageName != null) {
            // Package member function: include package prefix with double underscore
            qualifiedName = schema.toLowerCase() + "." +
                           packageName.toLowerCase() + "__" +
                           functionName;
        } else {
            // Standalone function: just schema.functionName
            qualifiedName = schema.toLowerCase() + "." + functionName;
        }

        // STEP 2: Build parameter list from AST
        StringBuilder paramList = new StringBuilder();
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
                }
            }
        }

        // STEP 3: Extract return type from AST
        String returnType;
        if (ctx.type_spec() != null) {
            // Get Oracle type text and convert to PostgreSQL type
            String oracleType = ctx.type_spec().getText();
            returnType = TypeConverter.toPostgre(oracleType);
        } else {
            // Fallback if type_spec is missing (shouldn't happen for valid Oracle functions)
            returnType = "numeric";
        }

        // STEP 4: Build function body (DECLARE + BEGIN...END)
        StringBuilder functionBody = new StringBuilder();

        // Reset cursor attribute tracker for this function
        // Cursor names are local to function scope, so reset for each function
        b.resetCursorAttributeTracker();

        // Push loop RECORD variables context for this function block
        // When nested anonymous blocks are implemented, they will push their own contexts
        b.pushLoopRecordVariablesContext();

        // Push exception context for this function block
        // Tracks user-defined exceptions declared in this function's DECLARE section
        b.pushExceptionContext();

        // Pre-scan body for cursor attributes BEFORE transformation
        // This ensures FETCH/OPEN/CLOSE statements can inject tracking code correctly
        // (cursor attributes are encountered during traversal, but FETCH may come before first attribute)
        if (ctx.body() != null) {
            b.prescanCursorAttributes(ctx.body());
        }

        // Visit declarations (if present)
        boolean hasDeclareSection = ctx.seq_of_declare_specs() != null;
        if (hasDeclareSection) {
            functionBody.append("DECLARE\n");
            String declarations = b.visit(ctx.seq_of_declare_specs());
            functionBody.append(declarations);
        }

        // Visit the body (BEGIN...END block) - this will register loop variables
        String bodyCode = null;
        if (ctx.body() != null) {
            bodyCode = b.visit(ctx.body());

            // PACKAGE VARIABLE SUPPORT: Inject initialization call for package functions
            // If this is a package member function with variables, inject PERFORM pkg__initialize()
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

        // Pop loop RECORD variables context to get variables for this block
        Set<String> loopVariables = b.popLoopRecordVariablesContext();

        // Pop exception context when leaving function block
        // User-defined exceptions go out of scope
        b.popExceptionContext();

        // Inject RECORD declarations for cursor FOR loop variables
        // PostgreSQL requires explicit RECORD declarations, Oracle has implicit declarations
        if (!loopVariables.isEmpty()) {
            // Add DECLARE section if not already present
            if (!hasDeclareSection) {
                functionBody.append("DECLARE\n");
                hasDeclareSection = true;
            }

            // Add RECORD declarations
            for (String varName : loopVariables) {
                functionBody.append("  ").append(varName).append(" RECORD;\n");
            }
        }

        // Inject cursor tracking variables for cursors that use attributes
        // These variables are auto-generated after visiting the body (now we know which cursors use attributes)
        java.util.List<String> cursorTrackingVars = b.generateCursorTrackingDeclarations();
        if (!cursorTrackingVars.isEmpty()) {
            // Add DECLARE section if not already present
            if (!hasDeclareSection) {
                functionBody.append("DECLARE\n");
                hasDeclareSection = true;
            }

            // Add cursor tracking variable declarations
            for (String trackingVar : cursorTrackingVars) {
                functionBody.append("  ").append(trackingVar).append("\n");
            }
        }

        // Append the body code
        if (bodyCode != null) {
            functionBody.append(bodyCode);
        }

        // STEP 5: Generate complete CREATE OR REPLACE FUNCTION statement
        StringBuilder result = new StringBuilder();
        result.append("CREATE OR REPLACE FUNCTION ").append(qualifiedName);
        result.append("(").append(paramList).append(")\n");
        result.append("RETURNS ").append(returnType).append("\n");
        result.append("LANGUAGE plpgsql\n");
        result.append("AS $$\n");
        result.append(functionBody);
        if (!functionBody.toString().trim().endsWith(";")) {
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
}

