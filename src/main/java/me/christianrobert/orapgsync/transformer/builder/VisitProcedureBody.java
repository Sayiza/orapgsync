package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

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

        // STEP 1: Extract procedure name from AST
        String procedureName;
        if (ctx.identifier() != null) {
            procedureName = ctx.identifier().getText().toLowerCase();
        } else {
            throw new IllegalStateException("Procedure name not found in AST");
        }
        String qualifiedName = schema.toLowerCase() + "." + procedureName;

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

        // Visit declarations (if present)
        if (ctx.seq_of_declare_specs() != null) {
            procedureBody.append("DECLARE\n");
            String declarations = b.visit(ctx.seq_of_declare_specs());
            procedureBody.append(declarations);
        }

        // Visit the body (BEGIN...END block)
        if (ctx.body() != null) {
            String bodyCode = b.visit(ctx.body());
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
}
