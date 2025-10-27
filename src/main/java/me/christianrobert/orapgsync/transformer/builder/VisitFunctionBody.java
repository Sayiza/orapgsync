package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;

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

        // Visit declarations (if present)
        if (ctx.seq_of_declare_specs() != null) {
            functionBody.append("DECLARE\n");
            String declarations = b.visit(ctx.seq_of_declare_specs());
            functionBody.append(declarations);
        }

        // Visit the body (BEGIN...END block)
        if (ctx.body() != null) {
            String bodyCode = b.visit(ctx.body());
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

