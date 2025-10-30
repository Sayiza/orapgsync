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

        // STEP 1: Extract function name from AST
        String functionName;
        if (ctx.identifier() != null) {
            functionName = ctx.identifier().getText().toLowerCase();
        } else {
            throw new IllegalStateException("Function name not found in AST");
        }
        String qualifiedName = schema.toLowerCase() + "." + functionName;

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

        // Push loop RECORD variables context for this function block
        // When nested anonymous blocks are implemented, they will push their own contexts
        b.pushLoopRecordVariablesContext();

        // Push exception context for this function block
        // Tracks user-defined exceptions declared in this function's DECLARE section
        b.pushExceptionContext();

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
            }

            // Add RECORD declarations
            for (String varName : loopVariables) {
                functionBody.append(varName).append(" RECORD;\n");
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
}

