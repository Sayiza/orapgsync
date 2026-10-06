package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.List;

/**
 * Total dispatch over {@code type_body_elements} — the entry visitor for object type methods.
 *
 * <pre>
 * type_body_elements
 *     : map_order_func_declaration
 *     | subprog_decl_in_type
 *     | constructor_declaration
 *     | overriding_subprogram_spec
 *     ;
 * </pre>
 *
 * <p><b>Dispatch is total by construction.</b> The rule has exactly four alternatives — a closed,
 * finite, auditable set — so each is either implemented or throws its named rejection from
 * {@link TypeMethodRejection}, and the fall-through throws too. That property, not breadth of
 * coverage, is what makes the deferred parts of the type-method plan safe: a construct nobody
 * thought about cannot silently produce a function that means something else.
 *
 * <p>This is the direct answer to the finding that motivated the plan — orchestration step 29
 * implemented nothing while reporting success, because these four alternatives had no visitor and
 * no parser entry point at all.
 */
public final class VisitTypeBodyElements {

    private VisitTypeBodyElements() {
    }

    public static String v(PlSqlParser.Type_body_elementsContext ctx, PostgresCodeBuilder b) {
        if (ctx.map_order_func_declaration() != null) {
            return VisitMapOrderFuncDeclaration.v(ctx.map_order_func_declaration(), b);
        }
        if (ctx.subprog_decl_in_type() != null) {
            return VisitSubprogDeclInType.v(ctx.subprog_decl_in_type(), b);
        }
        if (ctx.constructor_declaration() != null) {
            return VisitConstructorDeclaration.v(ctx.constructor_declaration(), b);
        }
        if (ctx.overriding_subprogram_spec() != null) {
            // Route 2 of 2 for OVERRIDING. The other is modifier_clause inside
            // subprog_decl_in_type, guarded in VisitSubprogDeclInType -- see plan section 3.1.1.
            throw TypeMethodRejection.overriding(
                    overridingMethodName(ctx.overriding_subprogram_spec()));
        }

        throw TypeMethodRejection.unhandledAlternative(ctx);
    }

    /**
     * Rejects a Java or C {@code call_spec} body.
     *
     * <p>Shared by every form because every one of them admits {@code call_spec} in place of a
     * PL/SQL body. It <b>parses clean</b>, so nothing but this check stands between a
     * {@code LANGUAGE JAVA} method and a generated PostgreSQL function with an empty body.
     */
    static void rejectCallSpec(PlSqlParser.Call_specContext callSpec, String methodName) {
        if (callSpec != null) {
            throw TypeMethodRejection.callSpecBody(methodName);
        }
    }

    /**
     * Rejects an ODCI extensibility-interface method.
     *
     * <p>Detected by name: Oracle's interfaces are all spelled {@code ODCI…}. The dictionary-side
     * signal (a first parameter typed {@code SCTX}) is not visible here, so the extractor-side
     * check in the job is the complement to this one.
     */
    static void rejectOdci(String methodName) {
        if (methodName != null && methodName.toUpperCase().startsWith("ODCI")) {
            throw TypeMethodRejection.odciInterface(methodName);
        }
    }

    /**
     * Rejects {@code REF} on a return type.
     */
    static void rejectRefReturn(PlSqlParser.Type_specContext typeSpec, String methodName) {
        if (typeSpec != null && typeSpec.REF() != null) {
            throw TypeMethodRejection.refTypeSpec(methodName, "return type");
        }
    }

    /**
     * An abstract method has no body and genuinely has nothing to implement.
     *
     * <p>This is the one place a skip is correct — plan section 3.2. Skip must mean "nothing to
     * do", never "we failed", which is the distinction that let the original total failure report
     * success.
     */
    static boolean isBodyLess(PlSqlParser.BodyContext body, PlSqlParser.Call_specContext callSpec) {
        return body == null && callSpec == null;
    }

    private static String overridingMethodName(PlSqlParser.Overriding_subprogram_specContext ctx) {
        PlSqlParser.Overriding_function_specContext spec = ctx.overriding_function_spec();
        if (spec == null || spec.function_name() == null) {
            return "<unknown>";
        }
        return IdentifierHelper.unquote(spec.function_name().getText());
    }

    /**
     * Normalises a parameter list: "no parentheses" and "empty parentheses" become the same empty
     * list, and <b>an explicit {@code SELF} is removed</b>.
     *
     * <p>SELF is never an ordinary parameter in any form — it is either the injected first
     * argument (member) or an injected local (constructor), decided by
     * {@link TypeMethodEmitter.SelfMode}. Leaving it in the list would emit it twice.
     *
     * <p>It has to be filtered <i>here</i> rather than trusted to the grammar, because
     * {@code constructor_declaration} spells its dedicated SELF slot as
     * {@code (SELF IN OUT type_spec ',')?} — <b>without {@code NOCOPY}</b>. Oracle permits
     * {@code SELF IN OUT NOCOPY}, and that form does not match the dedicated slot; it falls through
     * to {@code type_elements_parameter}, whose mode alternatives <i>do</i> include
     * {@code IN OUT NOCOPY}, and arrives as an ordinary parameter literally named {@code SELF}.
     * Both spellings must produce the same signature.
     */
    static List<PlSqlParser.Type_elements_parameterContext> parametersOf(
            List<PlSqlParser.Type_elements_parameterContext> parameters) {
        if (parameters == null) {
            return List.of();
        }
        return parameters.stream()
                .filter(p -> !"SELF".equals(IdentifierHelper.canonical(p.parameter_name())))
                .toList();
    }

    /**
     * Guards against a form the caller cannot render.
     */
    static void requireTypeContext(PostgresCodeBuilder b) {
        if (b.getContext().getCurrentTypeName() == null) {
            throw new TransformationException(
                    "Type method transformation requires the owning object type name in the "
                            + "transformation context.",
                    null, "type method: missing type context");
        }
    }
}
