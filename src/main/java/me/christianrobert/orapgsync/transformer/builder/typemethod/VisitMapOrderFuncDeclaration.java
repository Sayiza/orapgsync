package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for {@code map_order_func_declaration} — {@code MAP} and {@code ORDER} members.
 *
 * <pre>
 * map_order_func_declaration : (MAP | ORDER) MEMBER func_decl_in_type
 * </pre>
 *
 * <p><b>Emitted as ordinary member functions</b>, with no custom operator class — plan section 6.
 * The mapping is deliberately incomplete and the incompleteness is the point to understand:
 * Oracle compares values of a type with a {@code MAP} method <i>by the map value</i>, whereas
 * PostgreSQL compares composite values <i>field-wise</i>. For a single-attribute type the two
 * coincide; for a multi-attribute type {@code =} and {@code ORDER BY} change meaning
 * <b>silently</b>, with no error at any point.
 *
 * <p>That divergence cannot be fixed here — it needs a warning at the job level, where the type's
 * attribute count is known. Building custom operator classes was rejected as disproportionate.
 *
 * <p>{@code ORDER} is implemented on the same path rather than deferred, per the
 * negative-observation rule in plan section 2: the verification database happening to contain no
 * {@code ORDER} method is a fact about that schema, not a licence to leave the branch out.
 */
public final class VisitMapOrderFuncDeclaration {

    private VisitMapOrderFuncDeclaration() {
    }

    public static String v(PlSqlParser.Map_order_func_declarationContext ctx, PostgresCodeBuilder b) {
        VisitTypeBodyElements.requireTypeContext(b);

        // MAP and ORDER are always member functions -- they compare an instance -- so they take
        // SELF IN and are otherwise an ordinary func_decl_in_type.
        return VisitSubprogDeclInType.function(ctx.func_decl_in_type(), b, false);
    }
}
