package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;


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
            functionName = IdentifierHelper.unquote(ctx.identifier().getText()).toLowerCase();
        } else {
            throw new IllegalStateException("Function name not found in AST");
        }

        // A call spec (IS LANGUAGE JAVA NAME '...') parses clean but has no PL/SQL body; without
        // this check it would become a PostgreSQL function with nothing in it.
        if (ctx.call_spec() != null) {
            throw new TransformationException(
                    "Function '" + functionName + "' is implemented as a call spec (LANGUAGE JAVA / C). "
                            + "There is no PL/SQL body to transform. Reimplement it in PostgreSQL by hand.",
                    null, "function: call_spec body");
        }

        // Build qualified name based on package membership
        // Package members: schema.packageName__functionName (double underscore convention)
        // Standalone: schema.functionName
        // The stub job names the routine through the same call, so CREATE OR REPLACE finds it; a
        // name Oracle accepts quoted ("skip all") is quoted as a whole, never as pkg__"skip all".
        String packageName = b.getContext().getCurrentPackageName();
        String qualifiedName = schema.toLowerCase() + "." + PostgresIdentifierNormalizer.normalizeRoutineName(
                packageName != null ? packageName.toLowerCase() : null, functionName);

        // STEP 2: Build parameter list from AST
        StringBuilder paramList = new StringBuilder();
        if (ctx.parameter() != null && !ctx.parameter().isEmpty()) {
            boolean first = true;
            boolean[] withDefault = VisitParameter.emittableDefaults(ctx.parameter());
            for (int i = 0; i < ctx.parameter().size(); i++) {
                PlSqlParser.ParameterContext paramCtx = ctx.parameter(i);
                String paramString = VisitParameter.v(paramCtx, b, withDefault[i]);
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
            // Package types, migrated object types and built-ins, in that order.
            returnType = TypeResolver.toPostgres(ctx.type_spec().getText(), b.getContext());
        } else {
            // Fallback if type_spec is missing (shouldn't happen for valid Oracle functions)
            returnType = "numeric";
        }

        // STEP 4: Build function body (DECLARE + BEGIN...END)
        // Shared with procedures and, from Phase 2 of the type-method plan, with type methods.
        String functionBody = RoutineBodyBuilder.build(
                b,
                "function:" + functionName,
                ctx.seq_of_declare_specs(),
                ctx.body(),
                () -> RoutineBodyBuilder.registerParameters(ctx.parameter(), b));

        // STEP 5: Generate complete CREATE OR REPLACE FUNCTION statement
        StringBuilder result = new StringBuilder();
        result.append("CREATE OR REPLACE FUNCTION ").append(qualifiedName);
        result.append("(").append(paramList).append(")\n");
        result.append("RETURNS ").append(returnType).append("\n");
        result.append("LANGUAGE plpgsql\n");
        result.append("AS $$\n");
        result.append(functionBody);
        if (!functionBody.trim().endsWith(";")) {
            result.append("\n");
        }
        result.append("$$;\n");

        return result.toString();
    }
}
