package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms object type method <b>call sites</b> — type-method plan Phase 4.
 *
 * <pre>
 * a.describe('x')          -- a is a local variable of demo_item_t
 *   -> hr.demo_item_t__describe(a, 'x')
 * demo_item_t.make_code(x) -- static method on the type
 *   -> hr.demo_item_t__make_code(x)
 * </pre>
 *
 * <p><b>The instance is an argument, not a prefix.</b> PostgreSQL has no method dispatch, so
 * Oracle's receiver becomes the first argument — matching the {@code self t} first parameter that
 * {@code TypeMethodEmitter} puts on every member function. Declaration and call site are two halves
 * of one convention and are wrong independently.
 *
 * <p><b>What this fixes.</b> {@code a.describe('x')} previously fell through to the package
 * function branch and emitted {@code hr.a__describe('x')}: the <i>variable</i> name used where the
 * type name belongs, <i>and the instance dropped entirely</i>. Inside a PL/pgSQL body that is not
 * caught at {@code CREATE} — the function name is only resolved when the line executes — so it
 * failed at runtime, in production, having reported a successful migration.
 *
 * <p><b>Static calls looked correct for the wrong reason.</b> {@code demo_item_t.make_code(x)}
 * already produced the right text, but via the package-function branch, which happens to build
 * {@code schema.first__second} — the same shape. It only worked because PostgreSQL case-folds and
 * the type name occupied the package slot. This class gives it a real type-name branch, so it is
 * right by decision rather than by coincidence.
 *
 * <p><b>Only positive metadata rewrites.</b> A rewrite happens when the qualifier is known to be a
 * variable of an object type, or a known object type, <i>and</i> that type is known to have the
 * method. Absent that evidence the transformer declines and the existing branches handle it. The
 * inverse rule — "the qualifier is not a known alias, therefore it is a type" — is the same
 * mistake CLAUDE.md forbids for parenthesis-less routines, and is deliberately not implemented.
 */
public class TypeMethodCallTransformer implements GeneralElementTransformer {

    @Override
    public GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        // Exactly qualifier.method(...). Longer chains (table.column.method()) are handled by the
        // existing object-field path in VisitGeneralElement, which already resolves the column type.
        if (parts.size() != 2) {
            return GeneralElementResult.notHandled();
        }

        PlSqlParser.General_element_partContext qualifierPart = parts.get(0);
        PlSqlParser.General_element_partContext methodPart = parts.get(1);

        // A method call needs an argument list. Without one this is a field access or a
        // parenthesis-less reference, and neither belongs here.
        if (methodPart.function_argument() == null || methodPart.function_argument().isEmpty()) {
            return GeneralElementResult.notHandled();
        }
        // The qualifier itself must be a plain name, not a call.
        if (qualifierPart.function_argument() != null && !qualifierPart.function_argument().isEmpty()) {
            return GeneralElementResult.notHandled();
        }

        TransformationContext context = builder.getContext();
        String qualifier = IdentifierHelper.canonical(qualifierPart.id_expression().getText());
        String methodName = IdentifierHelper.canonical(methodPart.id_expression().getText());

        if (qualifier == null || methodName == null) {
            return GeneralElementResult.notHandled();
        }

        String lowerQualifier = qualifier.toLowerCase();
        String lowerMethod = methodName.toLowerCase();

        // --- instance method on a local variable or parameter ---
        TransformationContext.VariableDefinition varDef = context.lookupVariable(lowerQualifier);
        if (varDef != null) {
            String qualifiedType = context.qualifyTypeName(varDef.getOracleType());
            if (context.isObjectType(qualifiedType) && context.hasTypeMethod(qualifiedType, lowerMethod)) {
                return GeneralElementResult.handled(
                        call(qualifiedType, lowerMethod, lowerQualifier, methodPart, builder));
            }
            // A variable of a non-object type, or a name this type does not have: not ours.
            return GeneralElementResult.notHandled();
        }

        // --- static method on the type itself ---
        String qualifiedType = context.qualifyTypeName(lowerQualifier);
        if (context.isObjectType(qualifiedType) && context.hasTypeMethod(qualifiedType, lowerMethod)) {
            return GeneralElementResult.handled(
                    call(qualifiedType, lowerMethod, null, methodPart, builder));
        }

        return GeneralElementResult.notHandled();
    }

    /**
     * Builds {@code schema.type__method(instance, args…)}, omitting the instance for a static call.
     *
     * <p>{@code qualifiedType} already carries the schema, so the emitted name is schema-qualified
     * — which is required, not cosmetic: the stub job creates
     * {@code schema.address_type__get_street}, and an unqualified call resolves only when
     * {@code search_path} happens to cover the schema.
     */
    private String call(String qualifiedType,
                        String methodName,
                        String instance,
                        PlSqlParser.General_element_partContext methodPart,
                        PostgresCodeBuilder builder) {

        List<String> arguments = new ArrayList<>();
        if (instance != null) {
            arguments.add(instance);
        }
        arguments.addAll(renderArguments(methodPart, builder));

        return qualifiedType + "__" + methodName + "(" + String.join(", ", arguments) + ")";
    }

    private List<String> renderArguments(PlSqlParser.General_element_partContext methodPart,
                                         PostgresCodeBuilder builder) {
        List<String> rendered = new ArrayList<>();
        for (PlSqlParser.Function_argumentContext argList : methodPart.function_argument()) {
            if (argList.argument() == null) {
                continue;
            }
            for (PlSqlParser.ArgumentContext argument : argList.argument()) {
                rendered.add(builder.visit(argument));
            }
        }
        return rendered;
    }
}
