package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

/**
 * Static helper for {@code constructor_declaration}.
 *
 * <pre>
 * constructor_declaration
 *     : FINAL? INSTANTIABLE? CONSTRUCTOR FUNCTION type_spec (
 *         '(' (SELF IN OUT type_spec ',')? type_elements_parameter (',' type_elements_parameter)* ')'
 *       )? RETURN SELF AS RESULT ((IS | AS) (call_spec | DECLARE? seq_of_declare_specs? body ';'))?
 * </pre>
 *
 * <p>Two properties of this rule shape the handling, and both are easy to get wrong:
 *
 * <ul>
 *   <li><b>{@code SELF IN OUT} may be written explicitly</b>, as its own slot before the ordinary
 *       parameters. It is not a normal parameter and must not be rendered as one — SELF becomes an
 *       injected local, so the explicit form and the omitted form have to produce the same
 *       signature.</li>
 *   <li><b>The body is optional.</b> The whole {@code ((IS|AS) …)} group is, so a declaration with
 *       no body is valid here. That is the abstract case: nothing to implement, and a skip rather
 *       than an error.</li>
 * </ul>
 *
 * <p>The constructor is named after its type ({@code t(…) RETURNS t}) and relies on PostgreSQL's
 * native overloading — plan section 5. Because overload resolution in PostgreSQL is by exact type
 * with no implicit {@code numeric} → {@code integer}/{@code text} coercion, call sites need
 * explicit casts; {@code t(1)} is ambiguous across {@code numeric}/{@code text} overloads.
 *
 * <p>The two body rewrites SELF needs — seeding {@code self} and turning bare {@code RETURN;} into
 * {@code RETURN self;} — are Phase 3.
 */
public final class VisitConstructorDeclaration {

    private VisitConstructorDeclaration() {
    }

    public static String v(PlSqlParser.Constructor_declarationContext ctx, PostgresCodeBuilder b) {
        VisitTypeBodyElements.requireTypeContext(b);

        String typeName = IdentifierHelper.unquote(ctx.type_spec(0).getText());

        VisitTypeBodyElements.rejectCallSpec(ctx.call_spec(), typeName);

        if (VisitTypeBodyElements.isBodyLess(ctx.body(), ctx.call_spec())) {
            // Declared without a body: nothing to implement. Signalled to the caller rather than
            // thrown, because this is the one genuine "nothing to do" in the catalogue.
            throw new AbstractTypeMethodException(typeName);
        }

        return TypeMethodEmitter.emit(
                b,
                typeName,
                TypeMethodEmitter.SelfMode.CONSTRUCTOR,
                VisitTypeBodyElements.parametersOf(ctx.type_elements_parameter()),
                null,  // a constructor always returns its own type
                ctx.seq_of_declare_specs(),
                ctx.body());
    }
}
