package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;
import me.christianrobert.orapgsync.transformer.util.ParameterDefaults;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Static helper for {@code type_elements_parameter} — the parameter rule of object type methods.
 *
 * <p><b>This is not {@code VisitParameter} and cannot be.</b> Oracle spells type-method parameters
 * with a different grammar rule than routine parameters, so they arrive as a different generated
 * context class:
 *
 * <pre>
 * parameter              : parameter_name (IN | OUT | INOUT | NOCOPY)* type_spec? default_value_part?
 * type_elements_parameter: parameter_name (IN | OUT | IN OUT | OUT NOCOPY | IN OUT NOCOPY)?
 *                          type_spec DETERMINISTIC? default_value_part?
 * </pre>
 *
 * <p>The differences are real, not cosmetic: the modes are explicit alternatives rather than a
 * repeated token list ({@code INOUT} is never one token here), {@code type_spec} is mandatory, and
 * {@code DETERMINISTIC} may appear per parameter.
 *
 * <p>{@code NOCOPY} is dropped. It is a compiler hint about argument passing with no semantic
 * content, and PostgreSQL has no equivalent to hint.
 */
public final class VisitTypeElementsParameter {

    private VisitTypeElementsParameter() {
    }

    /**
     * Renders one parameter as PostgreSQL {@code [mode] name type [DEFAULT expr]}.
     *
     * @return the rendered parameter, never null
     */
    public static String v(PlSqlParser.Type_elements_parameterContext ctx, PostgresCodeBuilder b) {
        return v(ctx, b, true);
    }

    /**
     * As {@link #v(PlSqlParser.Type_elements_parameterContext, PostgresCodeBuilder)}, with the
     * {@code DEFAULT} emitted only when {@code emitDefault} is set — decided over the whole list by
     * {@link #emittableDefaults}, since PostgreSQL rejects a default before a required parameter.
     */
    public static String v(PlSqlParser.Type_elements_parameterContext ctx, PostgresCodeBuilder b,
                           boolean emitDefault) {
        StringBuilder result = new StringBuilder();

        String mode = mode(ctx);
        if (!"IN".equals(mode)) {
            result.append(mode).append(" ");
        }

        result.append(IdentifierHelper.emit(ctx.parameter_name())).append(" ");
        result.append(postgresType(ctx.type_spec(), b));

        if (emitDefault && ctx.default_value_part() != null) {
            result.append(" DEFAULT ").append(b.visit(ctx.default_value_part().expression()));
        }

        return result.toString();
    }

    /** Which parameters may carry their DEFAULT; see {@link ParameterDefaults}. */
    public static boolean[] emittableDefaults(List<PlSqlParser.Type_elements_parameterContext> parameters) {
        List<ParameterDefaults.Param> params = new ArrayList<>();
        for (PlSqlParser.Type_elements_parameterContext p : parameters) {
            params.add(new ParameterDefaults.Param(!"OUT".equals(mode(p)), p.default_value_part() != null));
        }
        return ParameterDefaults.emittable(params);
    }

    /**
     * True when this parameter is OUT or IN OUT, which is what decides the caller's RETURNS clause.
     */
    public static boolean isOutMode(PlSqlParser.Type_elements_parameterContext ctx) {
        return !"IN".equals(mode(ctx));
    }

    /**
     * Reads the parameter mode. Oracle writes {@code IN OUT} as two tokens, so this scans the
     * children rather than looking for a single {@code INOUT} token, which this rule never has.
     */
    static String mode(PlSqlParser.Type_elements_parameterContext ctx) {
        boolean hasIn = ctx.IN() != null;
        boolean hasOut = ctx.OUT() != null;

        if (hasIn && hasOut) {
            return "INOUT";
        }
        if (hasOut) {
            return "OUT";
        }
        return "IN";
    }

    /**
     * Maps the declared Oracle type, rejecting {@code REF} per the section 3.2 catalogue.
     */
    static String postgresType(PlSqlParser.Type_specContext typeSpec, PostgresCodeBuilder b) {
        if (typeSpec.REF() != null) {
            throw TypeMethodRejection.refTypeSpec(
                    b.getContext().getCurrentFunctionName(), "parameter type");
        }

        // TypeResolver is the one authority for this, shared with every other route that emits a
        // type. It was born here -- this was the only site that consulted metadata -- and was
        // lifted out once the 2026-08-27 run showed the other nine routes emitting bare object
        // type names.
        return TypeResolver.toPostgres(typeSpec.getText(), b.getContext());
    }
}
