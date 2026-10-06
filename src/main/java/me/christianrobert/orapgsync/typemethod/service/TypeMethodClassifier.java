package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodKind;

/**
 * Derives what an Oracle object type method actually is, from the data dictionary.
 *
 * <p>Pure logic with no database dependency, so it is unit-testable against the dictionary value
 * combinations directly. The rules are section 2 of
 * documentation/TYPE_METHOD_IMPLEMENTATION_PLAN.md, verified against a production Oracle database:
 *
 * <table>
 *   <tr><th>Test</th><th>Conclusion</th></tr>
 *   <tr><td>{@code method_name = type_name}</td><td>constructor</td></tr>
 *   <tr><td>no SELF row in {@code ALL_METHOD_PARAMS}</td><td>static</td></tr>
 *   <tr><td>no {@code ALL_METHOD_RESULTS} row</td><td>procedure</td></tr>
 *   <tr><td>{@code METHOD_TYPE} is MAP or ORDER</td><td>comparison method</td></tr>
 * </table>
 *
 * <p><strong>Neither {@code METHOD_TYPE} nor {@code INSTANTIABLE} decides member-vs-static.</strong>
 * The previous code read {@code METHOD_TYPE} as {@code FUNCTION}/{@code PROCEDURE} — values it never
 * carries — and {@code INSTANTIABLE} as member-vs-static, when it marks concrete-vs-abstract.
 *
 * <p>Unexpected dictionary values <strong>throw</strong> rather than fall back to a default. A
 * default here is how the previous reading survived: it matched nothing and quietly classified
 * every method as a static procedure.
 */
public final class TypeMethodClassifier {

    /** ALL_TYPE_METHODS.METHOD_TYPE values. Anything else means the reading is wrong again. */
    private static final String METHOD_TYPE_PUBLIC = "PUBLIC";
    private static final String METHOD_TYPE_MAP = "MAP";
    private static final String METHOD_TYPE_ORDER = "ORDER";

    /** ALL_METHOD_PARAMS.PARAM_MODE values for the SELF row. */
    public static final String SELF_MODE_IN = "IN";
    public static final String SELF_MODE_IN_OUT = "IN OUT";

    private TypeMethodClassifier() {
    }

    /**
     * @param typeName        owning object type name
     * @param methodName      method name
     * @param oracleMethodType raw {@code ALL_TYPE_METHODS.METHOD_TYPE}
     * @param selfParamMode   {@code PARAM_MODE} of the SELF row, or null when there is no SELF row
     * @param hasResultRow    whether {@code ALL_METHOD_RESULTS} has a row for this method
     * @throws TypeMethodClassificationException on any dictionary value combination the rules above
     *                                           do not cover
     */
    public static TypeMethodKind classify(String typeName,
                                          String methodName,
                                          String oracleMethodType,
                                          String selfParamMode,
                                          boolean hasResultRow) {

        String methodType = normalize(oracleMethodType);
        String selfMode = normalize(selfParamMode);

        if (!METHOD_TYPE_PUBLIC.equals(methodType)
                && !METHOD_TYPE_MAP.equals(methodType)
                && !METHOD_TYPE_ORDER.equals(methodType)) {
            throw new TypeMethodClassificationException(typeName, methodName,
                    "unexpected ALL_TYPE_METHODS.METHOD_TYPE '" + oracleMethodType
                            + "' (expected PUBLIC, MAP or ORDER). The dictionary reading in "
                            + "TypeMethodClassifier is out of date -- re-run the section 9 "
                            + "verification queries against this database.");
        }

        if (selfMode != null
                && !SELF_MODE_IN.equals(selfMode)
                && !SELF_MODE_IN_OUT.equals(selfMode)) {
            throw new TypeMethodClassificationException(typeName, methodName,
                    "unexpected SELF parameter mode '" + selfParamMode
                            + "' (expected IN or IN OUT)");
        }

        boolean namedAfterType = typeName != null && typeName.equalsIgnoreCase(methodName);
        boolean hasSelf = selfMode != null;

        if (namedAfterType) {
            // Oracle requires a constructor to be named after its type, and gives it a SELF row
            // with mode IN OUT. A method named after its type without SELF is a combination the
            // rules do not explain -- do not guess which of the two it is.
            if (!hasSelf) {
                throw new TypeMethodClassificationException(typeName, methodName,
                        "method is named after its type, which means constructor, but has no SELF "
                                + "parameter row. Either it is a static method sharing the type "
                                + "name, or the dictionary is representing constructors "
                                + "differently on this database.");
            }
            if (!hasResultRow) {
                throw new TypeMethodClassificationException(typeName, methodName,
                        "method is named after its type, which means constructor, but has no "
                                + "ALL_METHOD_RESULTS row. A constructor always returns its type.");
            }
            return TypeMethodKind.CONSTRUCTOR;
        }

        if (METHOD_TYPE_MAP.equals(methodType) || METHOD_TYPE_ORDER.equals(methodType)) {
            if (!hasSelf) {
                throw new TypeMethodClassificationException(typeName, methodName,
                        methodType + " method has no SELF parameter row; MAP and ORDER methods are "
                                + "always member methods.");
            }
            if (!hasResultRow) {
                throw new TypeMethodClassificationException(typeName, methodName,
                        methodType + " method has no ALL_METHOD_RESULTS row; MAP and ORDER methods "
                                + "always return a value.");
            }
            return METHOD_TYPE_MAP.equals(methodType)
                    ? TypeMethodKind.MAP_FUNCTION
                    : TypeMethodKind.ORDER_FUNCTION;
        }

        if (!hasSelf) {
            return hasResultRow ? TypeMethodKind.STATIC_FUNCTION : TypeMethodKind.STATIC_PROCEDURE;
        }
        return hasResultRow ? TypeMethodKind.MEMBER_FUNCTION : TypeMethodKind.MEMBER_PROCEDURE;
    }

    /**
     * True when the method writes to SELF and therefore needs the statement-level call-site
     * rewrite. Oracle signals this with {@code SELF IN OUT}: implicit for a member procedure,
     * explicit for a member function.
     */
    public static boolean mutatesSelf(String selfParamMode) {
        return SELF_MODE_IN_OUT.equals(normalize(selfParamMode));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Oracle renders the mode as 'IN OUT'; collapse any internal whitespace variation.
        return trimmed.toUpperCase().replaceAll("\\s+", " ");
    }
}
