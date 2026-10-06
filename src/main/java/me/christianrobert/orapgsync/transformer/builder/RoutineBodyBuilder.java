package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;

import java.util.List;
import java.util.Set;

/**
 * The PL/pgSQL routine body shared by every Oracle routine form.
 *
 * <p><b>This is not a visitor for one grammar rule.</b> It is the block that
 * {@code function_body}, {@code procedure_body}, {@code func_decl_in_type},
 * {@code proc_decl_in_type} and {@code constructor_declaration} all need, because they all spell
 * their body as the same two rules — {@code seq_of_declare_specs?} and {@code body}. Only the
 * signature around it differs: how the routine names itself, how its parameters are spelled, and
 * what the {@code RETURNS} clause is. Those stay with the caller; everything between
 * {@code DECLARE} and the final {@code END} lives here.
 *
 * <p>It was extracted from {@code VisitFunctionBody} and {@code VisitProcedureBody}, which held
 * byte-identical copies of it. That duplication is the reason for this class: the type-method
 * visitors (type-method plan, Phase 2) would have made a third and fourth copy, and a block that
 * exists four times is a block where a fix lands in one copy and not the others.
 *
 * <p>The ordering here is load-bearing and not obvious:
 * <ul>
 *   <li>Parameters are registered <i>after</i> the scope is pushed, so they land in the routine's
 *       own scope rather than the caller's.</li>
 *   <li>Cursor attributes are pre-scanned <i>before</i> the body is visited, because a
 *       {@code FETCH} can precede the first attribute reference and still needs its tracking
 *       code.</li>
 *   <li>Loop RECORD and cursor-tracking declarations are collected <i>after</i> the body is
 *       visited — they are discovered by visiting it — but are emitted <i>before</i> it, because
 *       PL/pgSQL requires every declaration up front.</li>
 * </ul>
 *
 * @see VisitFunctionBody
 * @see VisitProcedureBody
 */
public final class RoutineBodyBuilder {

    private RoutineBodyBuilder() {
    }

    /**
     * Builds the PL/pgSQL body: the {@code DECLARE} section, any injected declarations, and the
     * {@code BEGIN…END} block. The caller wraps this in {@code CREATE OR REPLACE FUNCTION …}.
     *
     * @param b                  code builder, for the visitor and context stacks
     * @param scopeLabel         variable-scope label, e.g. {@code "function:calc_total"}; used for
     *                           diagnostics only, but make it identifying
     * @param declareSpecs       the routine's {@code seq_of_declare_specs}, or null when it has no
     *                           declare section
     * @param body               the routine's {@code body} (the {@code BEGIN…END} block)
     * @param parameterRegistrar registers the routine's parameters as scoped variables. Invoked
     *                           once, after the scope is pushed. Supplied by the caller because
     *                           the parameter <i>rule</i> differs per routine form —
     *                           {@code parameter} for standalone and package routines,
     *                           {@code type_elements_parameter} for type methods.
     * @return the body text, ready to place between {@code AS $$} and {@code $$}
     */
    public static String build(PostgresCodeBuilder b,
                               String scopeLabel,
                               PlSqlParser.Seq_of_declare_specsContext declareSpecs,
                               PlSqlParser.BodyContext body,
                               Runnable parameterRegistrar) {
        return build(b, scopeLabel, declareSpecs, body, parameterRegistrar, List.of(), null);
    }

    /**
     * As {@link #build(PostgresCodeBuilder, String, PlSqlParser.Seq_of_declare_specsContext,
     * PlSqlParser.BodyContext, Runnable)}, with the two things an Oracle constructor needs that no
     * other routine form does.
     *
     * @param injectedDeclarations declarations the caller must add to the DECLARE section, each a
     *                             complete PL/pgSQL declaration without the trailing newline (e.g.
     *                             {@code "self hr.demo_item_t;"}). Emitted before the routine's own
     *                             declarations, and they create the DECLARE section if the routine
     *                             has none — same mechanism the loop-RECORD and cursor-tracking
     *                             declarations already use.
     * @param bareReturnExpression what a bare {@code RETURN;} returns in this routine, or null when
     *                             it returns nothing. Scoped to this routine: a local subprogram
     *                             declared inside it pushes its own and shadows this one.
     */
    public static String build(PostgresCodeBuilder b,
                               String scopeLabel,
                               PlSqlParser.Seq_of_declare_specsContext declareSpecs,
                               PlSqlParser.BodyContext body,
                               Runnable parameterRegistrar,
                               List<String> injectedDeclarations,
                               String bareReturnExpression) {

        StringBuilder routineBody = new StringBuilder();

        // Cursor names are local to the routine, so the tracker starts clean for each one.
        b.resetCursorAttributeTracker();

        // Nested anonymous blocks will push their own contexts once they are implemented.
        b.pushLoopRecordVariablesContext();
        b.pushExceptionContext();
        b.pushBareReturnExpression(bareReturnExpression);
        b.getContext().pushVariableScope(scopeLabel);

        // Parameters must be in scope before the body is visited: that is what makes an identifier
        // resolve as a variable reference rather than a parameterless function call.
        if (parameterRegistrar != null) {
            parameterRegistrar.run();
        }

        // Before transformation, not during: a FETCH/OPEN/CLOSE can appear earlier in the body than
        // the first cursor-attribute reference, and it still has to emit tracking code.
        if (body != null) {
            b.prescanCursorAttributes(body);
        }

        boolean hasDeclareSection = declareSpecs != null || !injectedDeclarations.isEmpty();
        if (hasDeclareSection) {
            routineBody.append("DECLARE\n");
        }
        for (String declaration : injectedDeclarations) {
            routineBody.append("  ").append(declaration).append("\n");
        }
        if (declareSpecs != null) {
            routineBody.append(b.visit(declareSpecs));
        }

        // Visiting the body is what discovers loop RECORD variables and used cursor attributes.
        String bodyCode = null;
        if (body != null) {
            bodyCode = b.visit(body);

            // Package member routines get PERFORM pkg__initialize() injected after BEGIN, so package
            // variables hold their initial values before the first statement runs.
            if (b.needsPackageInitialization()) {
                String initCall = b.generatePackageInitializationCall();
                if (initCall != null && bodyCode != null) {
                    bodyCode = injectInitializationCall(bodyCode, initCall);
                }
            }
        }

        b.getContext().popVariableScope();
        b.popBareReturnExpression();
        Set<String> loopVariables = b.popLoopRecordVariablesContext();
        b.popExceptionContext();

        // Oracle declares cursor FOR loop variables implicitly; PL/pgSQL needs them explicit.
        if (!loopVariables.isEmpty()) {
            if (!hasDeclareSection) {
                routineBody.append("DECLARE\n");
                hasDeclareSection = true;
            }
            for (String varName : loopVariables) {
                routineBody.append("  ").append(varName).append(" RECORD;\n");
            }
        }

        // Only known after the body was visited: which cursors actually used attributes.
        List<String> cursorTrackingVars = b.generateCursorTrackingDeclarations();
        if (!cursorTrackingVars.isEmpty()) {
            if (!hasDeclareSection) {
                routineBody.append("DECLARE\n");
                hasDeclareSection = true;
            }
            for (String trackingVar : cursorTrackingVars) {
                routineBody.append("  ").append(trackingVar).append("\n");
            }
        }

        if (bodyCode != null) {
            routineBody.append(bodyCode);
        }

        return routineBody.toString();
    }

    /**
     * Registers {@code parameter}-rule parameters as scoped variables.
     *
     * <p>For the routine forms that use the {@code parameter} rule — standalone and package
     * functions and procedures. Type methods use {@code type_elements_parameter}, a different rule
     * with a different context class, and will supply their own registrar.
     */
    public static void registerParameters(List<PlSqlParser.ParameterContext> parameters,
                                          PostgresCodeBuilder b) {
        if (parameters == null) {
            return;
        }
        for (PlSqlParser.ParameterContext paramCtx : parameters) {
            registerParameterInScope(paramCtx, b);
        }
    }

    /**
     * Registers one parameter as a scoped variable, so the body can tell a variable reference from
     * a parameterless function call without guessing.
     *
     * <p>The name is keyed through {@link IdentifierHelper#canonical} rather than
     * {@code getText().toLowerCase()}. For an unquoted name the two agree, which is why this never
     * showed up; for a quoted {@code "Foo"} the old form kept the quotes and registered the literal
     * string {@code "foo"}, which no lookup could ever match — so every reference to a
     * quoted-name parameter silently fell through to the not-a-variable branch.
     */
    private static void registerParameterInScope(PlSqlParser.ParameterContext paramCtx,
                                                 PostgresCodeBuilder b) {
        String paramName = IdentifierHelper.canonical(paramCtx.parameter_name());

        String oracleType = paramCtx.type_spec().getText();

        TransformationContext context = b.getContext();
        String postgresType = TypeResolver.toPostgres(oracleType, context);

        // Parameters are never CONSTANT, and a default value belongs to the signature rather than
        // to the body scope.
        TransformationContext.VariableDefinition paramDef = new TransformationContext.VariableDefinition(
                paramName,
                oracleType,
                postgresType,
                false,
                null,
                null
        );

        context.registerVariable(paramName, paramDef);
    }

    /**
     * Injects the package initialization call immediately after {@code BEGIN}.
     */
    private static String injectInitializationCall(String bodyCode, String initCall) {
        int beginIndex = bodyCode.indexOf("BEGIN");
        if (beginIndex == -1) {
            // Not reachable for valid PL/SQL; leave the body alone rather than corrupt it.
            return bodyCode;
        }

        int endOfLineIndex = bodyCode.indexOf('\n', beginIndex);
        if (endOfLineIndex == -1) {
            return bodyCode + "\n  " + initCall + ";\n";
        }

        StringBuilder result = new StringBuilder();
        result.append(bodyCode, 0, endOfLineIndex + 1);
        result.append("  ").append(initCall).append(";\n");
        result.append(bodyCode.substring(endOfLineIndex + 1));
        return result.toString();
    }
}
