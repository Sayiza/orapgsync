package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL function bodies.
 *
 * Oracle structure:
 * FUNCTION function_name (params) RETURN type IS/AS
 *   [DECLARE declarations]
 * BEGIN
 *   [statements]
 * [EXCEPTION handlers]
 * END;
 *
 * PostgreSQL PL/pgSQL:
 * (function signature created by TransformationService)
 * DECLARE
 *   [declarations]
 * BEGIN
 *   [statements]
 * EXCEPTION
 *   [handlers]
 * END;
 *
 * Note: We only transform the body part (declarations + BEGIN...END).
 * The function signature (name, parameters, RETURN type) is handled separately
 * by the TransformationService using FunctionMetadata.
 */
public class VisitFunctionBody {

    public static String v(PlSqlParser.Function_bodyContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // The function_body rule contains: FUNCTION identifier params RETURN type (IS|AS) body
        // We need to find and transform the body part

        // Visit declarations (if present)
        if (ctx.seq_of_declare_specs() != null) {
            result.append("DECLARE\n");
            String declarations = b.visit(ctx.seq_of_declare_specs());
            result.append(declarations);
        }

        // Visit the body (BEGIN...END block)
        if (ctx.body() != null) {
            String bodyCode = b.visit(ctx.body());
            result.append(bodyCode);
        }

        return result.toString();
    }
}

