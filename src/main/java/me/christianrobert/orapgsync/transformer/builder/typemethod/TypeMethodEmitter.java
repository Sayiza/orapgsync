package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.builder.RoutineBodyBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code CREATE OR REPLACE FUNCTION} for an object type method.
 *
 * <p>All four {@code type_body_elements} forms funnel through here, because they differ only in
 * how SELF appears and what the function is called. Section 4 of the type-method plan:
 *
 * <table>
 *   <tr><th>Oracle form</th><th>SELF</th><th>PostgreSQL</th></tr>
 *   <tr><td>MEMBER FUNCTION</td><td>IN</td><td>{@code t__m(self t, …) RETURNS r}</td></tr>
 *   <tr><td>MEMBER PROCEDURE</td><td>IN OUT</td><td>{@code t__m(INOUT self t, …)}</td></tr>
 *   <tr><td>STATIC</td><td>absent</td><td>{@code t__m(…)}</td></tr>
 *   <tr><td>CONSTRUCTOR</td><td>injected local</td><td>{@code t(…) RETURNS t}</td></tr>
 *   <tr><td>MAP / ORDER MEMBER</td><td>IN</td><td>ordinary function</td></tr>
 * </table>
 *
 * <p><b>Names are schema-qualified</b>, because the stub job creates
 * {@code schema.address_type__get_street} and an unqualified emission only resolves when
 * {@code search_path} happens to cover the schema.
 *
 * <p><b>Constructors keep the Oracle type name</b> ({@code t(…)}, not {@code t__new}), relying on
 * PostgreSQL's native overloading — plan section 5. The reason is failure-mode asymmetry: a
 * constructor call site we fail to detect already transforms to {@code t(…)} through the generic
 * function path, so under this naming it keeps working, whereas a {@code __new} suffix would turn
 * the same undetected site into a dangling reference that stays silent until runtime.
 */
public final class TypeMethodEmitter {

    /** How SELF appears in the emitted signature. */
    public enum SelfMode {
        /** No SELF at all — a static method. */
        NONE,
        /** {@code self t} as the first parameter — a read-only member. */
        IN,
        /** {@code INOUT self t} as the first parameter — a member procedure. */
        INOUT,
        /** No SELF parameter; the body gets a local (Phase 3) — a constructor. */
        CONSTRUCTOR
    }

    private TypeMethodEmitter() {
    }

    /**
     * @param methodName   method name as written in the body, or null for a constructor
     * @param selfMode     how SELF appears
     * @param parameters   declared parameters, SELF excluded
     * @param returnType   PostgreSQL return type, or null to derive from the OUT parameters
     * @param declareSpecs the method's DECLARE section, or null
     * @param body         the method's BEGIN…END block; null means a body-less declaration
     */
    public static String emit(PostgresCodeBuilder b,
                              String methodName,
                              SelfMode selfMode,
                              List<PlSqlParser.Type_elements_parameterContext> parameters,
                              String returnType,
                              PlSqlParser.Seq_of_declare_specsContext declareSpecs,
                              PlSqlParser.BodyContext body) {

        TransformationContext context = b.getContext();
        String schema = context.getCurrentSchema().toLowerCase();
        String typeName = context.getCurrentTypeName();

        if (typeName == null) {
            throw new TransformationException(
                    "Cannot transform a type method without knowing its owning object type. The "
                            + "source segment contains only the method, so the caller must supply the "
                            + "type name from TypeMethodMetadata.",
                    null, "type method: missing type context");
        }

        String pgTypeName = IdentifierHelper.emit(typeName).toLowerCase();
        String qualifiedType = schema + "." + pgTypeName;

        // A constructor is named after its type; everything else is type__method.
        String qualifiedName = selfMode == SelfMode.CONSTRUCTOR
                ? qualifiedType
                : schema + "." + pgTypeName + "__" + IdentifierHelper.emit(methodName).toLowerCase();

        // ---- signature ----
        List<String> renderedParams = new ArrayList<>();
        int outParamCount = 0;
        String singleOutParamType = null;

        if (selfMode == SelfMode.IN) {
            renderedParams.add("self " + qualifiedType);
        } else if (selfMode == SelfMode.INOUT) {
            renderedParams.add("INOUT self " + qualifiedType);
            outParamCount++;
            singleOutParamType = qualifiedType;
        }

        if (parameters != null) {
            boolean[] withDefault = VisitTypeElementsParameter.emittableDefaults(parameters);
            for (int i = 0; i < parameters.size(); i++) {
                PlSqlParser.Type_elements_parameterContext paramCtx = parameters.get(i);
                renderedParams.add(VisitTypeElementsParameter.v(paramCtx, b, withDefault[i]));
                if (VisitTypeElementsParameter.isOutMode(paramCtx)) {
                    outParamCount++;
                    if (outParamCount == 1) {
                        singleOutParamType = VisitTypeElementsParameter.postgresType(paramCtx.type_spec(), b);
                    }
                }
            }
        }

        String returnsClause = returnsClause(selfMode, returnType, qualifiedType,
                outParamCount, singleOutParamType);

        // ---- body ----
        // SELF is registered as an ordinary scoped composite variable, which is what lets the
        // existing field-access machinery handle `self.x` with no special casing. Without it the
        // scope tracker does not know `self`, and field assignment falls into the inline-RECORD
        // branch and silently emits jsonb_set() against a composite — plan section 4.
        String scopeLabel = "typemethod:" + pgTypeName + "."
                + (methodName == null ? pgTypeName : methodName.toLowerCase());

        // A constructor's SELF is a local, not a parameter: Oracle hands the method an empty
        // instance to populate and `RETURN;` hands it back. Both halves are needed -- declaring
        // `self` without rewriting the RETURN produces a function that returns nothing from a
        // RETURNS t signature, and rewriting the RETURN without declaring `self` produces a
        // function that references an unknown variable. Plan section 4.
        List<String> injectedDeclarations = selfMode == SelfMode.CONSTRUCTOR
                ? List.of("self " + qualifiedType + " := ROW(" + nullSeed(context, qualifiedType)
                        + ")::" + qualifiedType + ";")
                : List.of();
        String bareReturnExpression = selfMode == SelfMode.CONSTRUCTOR ? "self" : null;

        String routineBody = RoutineBodyBuilder.build(
                b,
                scopeLabel,
                declareSpecs,
                body,
                () -> {
                    if (selfMode != SelfMode.NONE) {
                        registerSelf(context, typeName, qualifiedType);
                    }
                    registerParameters(parameters, b);
                },
                injectedDeclarations,
                bareReturnExpression);

        StringBuilder result = new StringBuilder();
        result.append("CREATE OR REPLACE FUNCTION ").append(qualifiedName);
        result.append("(").append(String.join(", ", renderedParams)).append(")\n");
        result.append(returnsClause).append("\n");
        result.append("LANGUAGE plpgsql\n");
        result.append("AS $$\n");
        result.append(routineBody);
        if (!routineBody.trim().endsWith(";")) {
            result.append("\n");
        }
        result.append("$$;\n");

        return result.toString();
    }

    private static String returnsClause(SelfMode selfMode,
                                        String returnType,
                                        String qualifiedType,
                                        int outParamCount,
                                        String singleOutParamType) {
        // A constructor always returns its own type -- that is what CONSTRUCTOR FUNCTION means,
        // and Oracle spells it RETURN SELF AS RESULT rather than naming the type.
        if (selfMode == SelfMode.CONSTRUCTOR) {
            return "RETURNS " + qualifiedType;
        }

        if (returnType != null) {
            return "RETURNS " + returnType;
        }

        // Procedure: PostgreSQL has no independent return, so the OUT/INOUT parameters dictate it.
        if (outParamCount == 0) {
            return "RETURNS void";
        }
        if (outParamCount == 1) {
            return "RETURNS " + (singleOutParamType != null ? singleOutParamType : "numeric");
        }
        return "RETURNS RECORD";
    }

    /**
     * {@code NULL, NULL, …}, one per attribute of the type.
     *
     * <p>The seed is load-bearing, not decoration — established by execution test, per the open
     * question in plan section 4. A PL/pgSQL composite variable declared without an initialiser is
     * NULL, and assigning to a field of a NULL composite raises at call time. Seeding it with a row
     * of NULLs makes the variable a real row whose fields happen to be null, which is what an Oracle
     * constructor is handed.
     */
    private static String nullSeed(TransformationContext context, String qualifiedType) {
        Map<String, String> fields = context.getTypeFields(qualifiedType);
        if (fields == null || fields.isEmpty()) {
            // The type's attributes are not in the indices. Emitting ROW() would be invalid SQL, so
            // fail here rather than produce a function that cannot be created.
            throw new TransformationException(
                    "Cannot build the constructor prologue for '" + qualifiedType + "': its "
                            + "attributes are not in the transformation metadata, so the number of "
                            + "columns to seed SELF with is unknown. Extract object types before "
                            + "implementing type methods.",
                    null, "type method: constructor prologue");
        }
        return String.join(", ", Collections.nCopies(fields.size(), "NULL"));
    }

    /**
     * Registers {@code self} as a composite variable of the enclosing type.
     */
    private static void registerSelf(TransformationContext context,
                                     String oracleTypeName,
                                     String qualifiedType) {
        context.registerVariable("self", new TransformationContext.VariableDefinition(
                "self",
                oracleTypeName,
                qualifiedType,
                false,
                null,
                null
        ));
    }

    /**
     * Registers type-method parameters as scoped variables.
     *
     * <p>The type-method counterpart of {@code RoutineBodyBuilder.registerParameters}, which takes
     * the other parameter rule. Keyed through {@link IdentifierHelper#canonical} for the same
     * reason: every lookup canonicalises, so a raw key cannot be found.
     */
    private static void registerParameters(List<PlSqlParser.Type_elements_parameterContext> parameters,
                                           PostgresCodeBuilder b) {
        if (parameters == null) {
            return;
        }
        TransformationContext context = b.getContext();
        for (PlSqlParser.Type_elements_parameterContext paramCtx : parameters) {
            String paramName = IdentifierHelper.canonical(paramCtx.parameter_name());
            String oracleType = paramCtx.type_spec().getText();
            String postgresType = VisitTypeElementsParameter.postgresType(paramCtx.type_spec(), b);

            context.registerVariable(paramName, new TransformationContext.VariableDefinition(
                    paramName,
                    oracleType,
                    postgresType,
                    false,
                    null,
                    null
            ));
        }
    }
}
