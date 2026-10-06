package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

/**
 * Static helper for {@code subprog_decl_in_type} — member and static methods.
 *
 * <pre>
 * subprog_decl_in_type : modifier_clause* (MEMBER | STATIC) (proc_decl_in_type | func_decl_in_type)
 * modifier_clause      : NOT? (INSTANTIABLE | FINAL | OVERRIDING | PERSISTABLE)
 * </pre>
 *
 * <p>Four of the section 3.1 coverage rows land here — MEMBER/STATIC × FUNCTION/PROCEDURE — and so
 * does <b>route 1 of 2 for {@code OVERRIDING}</b>. That is the subtle one: ANTLR takes the
 * lowest-numbered matching alternative, so an ordinary
 * {@code OVERRIDING MEMBER FUNCTION f RETURN varchar2 IS … END;} matches {@code modifier_clause}
 * here and never reaches {@code overriding_subprogram_spec}. Guarding only that rule would let this
 * form through and emit a function that quietly ignores the inheritance it exists to override.
 *
 * <p>The other modifiers — {@code INSTANTIABLE}, {@code FINAL}, {@code PERSISTABLE} — are parsed
 * and ignored, which is correct: they constrain the Oracle type system and carry no transformation
 * consequence for a PostgreSQL function.
 */
public final class VisitSubprogDeclInType {

    private VisitSubprogDeclInType() {
    }

    public static String v(PlSqlParser.Subprog_decl_in_typeContext ctx, PostgresCodeBuilder b) {
        VisitTypeBodyElements.requireTypeContext(b);

        rejectOverridingModifier(ctx);

        boolean isStatic = ctx.STATIC() != null;

        if (ctx.func_decl_in_type() != null) {
            return function(ctx.func_decl_in_type(), b, isStatic);
        }
        return procedure(ctx.proc_decl_in_type(), b, isStatic);
    }

    /**
     * {@code MEMBER FUNCTION} → {@code t__m(self t, …) RETURNS r};
     * {@code STATIC FUNCTION} → {@code t__m(…) RETURNS r}.
     */
    static String function(PlSqlParser.Func_decl_in_typeContext ctx,
                           PostgresCodeBuilder b,
                           boolean isStatic) {
        String methodName = IdentifierHelper.unquote(ctx.function_name().getText());

        VisitTypeBodyElements.rejectOdci(methodName);
        VisitTypeBodyElements.rejectCallSpec(ctx.call_spec(), methodName);
        VisitTypeBodyElements.rejectRefReturn(ctx.type_spec(), methodName);

        String returnType = VisitTypeElementsParameter.postgresType(ctx.type_spec(), b);

        return TypeMethodEmitter.emit(
                b,
                methodName,
                isStatic ? TypeMethodEmitter.SelfMode.NONE : TypeMethodEmitter.SelfMode.IN,
                VisitTypeBodyElements.parametersOf(ctx.type_elements_parameter()),
                returnType,
                ctx.seq_of_declare_specs(),
                ctx.body());
    }

    /**
     * {@code MEMBER PROCEDURE} → {@code t__m(INOUT self t, …)};
     * {@code STATIC PROCEDURE} → {@code t__m(…) RETURNS void}.
     *
     * <p>A member procedure takes SELF as {@code IN OUT} — Oracle grants it implicitly, which is
     * what makes {@code obj.set_x(1)} able to modify {@code obj}. In PostgreSQL that becomes an
     * {@code INOUT} parameter, and the call site therefore needs the statement-level rewrite
     * {@code obj := t__set_x(obj, 1)} (plan Phase 6).
     */
    static String procedure(PlSqlParser.Proc_decl_in_typeContext ctx,
                            PostgresCodeBuilder b,
                            boolean isStatic) {
        String methodName = IdentifierHelper.unquote(ctx.procedure_name().getText());

        VisitTypeBodyElements.rejectOdci(methodName);
        VisitTypeBodyElements.rejectCallSpec(ctx.call_spec(), methodName);

        return TypeMethodEmitter.emit(
                b,
                methodName,
                isStatic ? TypeMethodEmitter.SelfMode.NONE : TypeMethodEmitter.SelfMode.INOUT,
                VisitTypeBodyElements.parametersOf(ctx.type_elements_parameter()),
                null,  // derived from the OUT parameters
                ctx.seq_of_declare_specs(),
                ctx.body());
    }

    /**
     * Route 1 of 2 for the inheritance rejection — see the class comment and plan section 3.1.1.
     */
    private static void rejectOverridingModifier(PlSqlParser.Subprog_decl_in_typeContext ctx) {
        if (ctx.modifier_clause() == null) {
            return;
        }
        for (PlSqlParser.Modifier_clauseContext modifier : ctx.modifier_clause()) {
            // NOT OVERRIDING is the default and says nothing; only a bare OVERRIDING is the
            // construct that cannot be transformed.
            if (modifier.OVERRIDING() != null && modifier.NOT() == null) {
                throw TypeMethodRejection.overriding(methodNameOf(ctx));
            }
        }
    }

    private static String methodNameOf(PlSqlParser.Subprog_decl_in_typeContext ctx) {
        if (ctx.func_decl_in_type() != null) {
            return IdentifierHelper.unquote(ctx.func_decl_in_type().function_name().getText());
        }
        if (ctx.proc_decl_in_type() != null) {
            return IdentifierHelper.unquote(ctx.proc_decl_in_type().procedure_name().getText());
        }
        return "<unknown>";
    }
}
