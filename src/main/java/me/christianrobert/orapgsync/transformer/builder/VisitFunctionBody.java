package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.tools.TypeConverter;

/**
 * Static helper for visiting PL/SQL function bodies.
 *
 * <p>Generates complete CREATE OR REPLACE FUNCTION statement using metadata from TransformationContext.</p>
 *
 * <h3>Oracle Structure:</h3>
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
     * Gets all necessary metadata from TransformationContext via the builder.
     *
     * @param ctx Function body parse tree context
     * @param b PostgresCodeBuilder instance (provides access to context)
     * @return Complete CREATE OR REPLACE FUNCTION statement
     */
    public static String v(PlSqlParser.Function_bodyContext ctx, PostgresCodeBuilder b) {
        // Get function metadata from context (visitor pattern - everything comes from context)
        FunctionMetadata metadata = b.getContext().getFunctionMetadata();
        if (metadata == null) {
            throw new IllegalStateException("Function metadata not found in transformation context");
        }

        String schema = b.getContext().getCurrentSchema();

        // STEP 1: Build function body (DECLARE + BEGIN...END)
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

        // STEP 2: Build function signature
        String functionName = metadata.getPostgresName();
        String qualifiedName = schema.toLowerCase() + "." + functionName;

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

        // STEP 4: Extract return type from ANTLR parse tree
        String returnType = "void";  // Default for procedures
        if (metadata.isFunction()) {
            // Extract return type from grammar: FUNCTION ... RETURN type_spec ...
            if (ctx.type_spec() != null) {
                // Get Oracle type text and convert to PostgreSQL type
                String oracleType = ctx.type_spec().getText();
                returnType = TypeConverter.toPostgre(oracleType);
            } else {
                // Fallback if type_spec is missing (shouldn't happen for valid Oracle functions)
                returnType = "numeric";
            }
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

