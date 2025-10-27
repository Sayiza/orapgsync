package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.tools.TypeConverter;

/**
 * Static helper for visiting PL/SQL procedure bodies.
 *
 * <p>Generates complete CREATE OR REPLACE FUNCTION statement using metadata from TransformationContext.</p>
 *
 * <p>Note: Procedures and functions share the same body structure (DECLARE + BEGIN...END),
 * the only difference is procedures don't have a RETURN type (they return void).</p>
 *
 * <h3>Oracle Structure:</h3>
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
     * Gets all necessary metadata from TransformationContext via the builder.
     * Note: PostgreSQL uses FUNCTION with RETURNS void for procedures.
     *
     * @param ctx Procedure body parse tree context
     * @param b PostgresCodeBuilder instance (provides access to context)
     * @return Complete CREATE OR REPLACE FUNCTION statement
     */
    public static String v(PlSqlParser.Procedure_bodyContext ctx, PostgresCodeBuilder b) {
        // Get function metadata from context (visitor pattern - everything comes from context)
        FunctionMetadata metadata = b.getContext().getFunctionMetadata();
        if (metadata == null) {
            throw new IllegalStateException("Function metadata not found in transformation context");
        }

        String schema = b.getContext().getCurrentSchema();

        // STEP 1: Build procedure body (DECLARE + BEGIN...END)
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

        // STEP 2: Build procedure signature
        String procedureName = metadata.getPostgresName();
        String qualifiedName = schema.toLowerCase() + "." + procedureName;

        // STEP 3: Build parameter list
        StringBuilder paramList = new StringBuilder();
        if (metadata.getParameters() != null && !metadata.getParameters().isEmpty()) {
            boolean first = true;
            for (FunctionParameter param : metadata.getParameters()) {
                String inOut = param.getInOut();
                // Only include IN and IN OUT parameters in signature
                if ("IN".equals(inOut) || "IN OUT".equals(inOut)) {
                    if (!first) {
                        paramList.append(", ");
                    }
                    first = false;

                    // Parameter name
                    paramList.append(param.getParameterName().toLowerCase());
                    paramList.append(" ");

                    // IN OUT mode indicator
                    if ("IN OUT".equals(inOut)) {
                        paramList.append("INOUT ");
                    }

                    // Parameter type (Oracle → PostgreSQL type mapping)
                    String oracleType = param.getDataType();
                    String postgresType = TypeConverter.toPostgre(oracleType);
                    paramList.append(postgresType);
                }
            }
        }

        // STEP 4: Generate complete CREATE OR REPLACE FUNCTION statement
        // Note: PostgreSQL uses FUNCTION with RETURNS void for procedures
        StringBuilder result = new StringBuilder();
        result.append("CREATE OR REPLACE FUNCTION ").append(qualifiedName);
        result.append("(").append(paramList).append(")\n");
        result.append("RETURNS void\n");
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
