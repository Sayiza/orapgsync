package me.christianrobert.orapgsync.core.job.model.typemethod;

/**
 * What an Oracle object type method actually is.
 *
 * <p>Deliberately mirrors {@code TypeBodySegments.MethodType}, which the boundary scanner derives
 * from the type body source. The dictionary side and the source side must speak the same language
 * so that scanned segments can be matched against dictionary rows.
 *
 * <p>This replaces the previous reading of {@code ALL_TYPE_METHODS.METHOD_TYPE} as
 * {@code FUNCTION}/{@code PROCEDURE} and {@code INSTANTIABLE} as member-vs-static. Both were wrong:
 * {@code METHOD_TYPE} only ever carries {@code MAP}/{@code ORDER}/{@code PUBLIC}, and
 * {@code INSTANTIABLE} marks concrete-vs-abstract. See section 2 of
 * documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md.
 */
public enum TypeMethodKind {

    /** Reads SELF. Becomes {@code t__m(self t, ...) RETURNS r}. */
    MEMBER_FUNCTION(true, true),

    /** SELF is IN OUT. Becomes {@code t__m(INOUT self t, ...)} plus a call-site rewrite. */
    MEMBER_PROCEDURE(false, true),

    /** No SELF at all. Becomes {@code t__m(...) RETURNS r}. */
    STATIC_FUNCTION(true, false),

    /** No SELF at all. Becomes {@code t__m(...) RETURNS void}. */
    STATIC_PROCEDURE(false, false),

    /** Named after its type, carries SELF IN OUT. Becomes {@code t(...) RETURNS t}. */
    CONSTRUCTOR(true, true),

    /** {@code MAP MEMBER FUNCTION}. Ordinary function, plus the multi-attribute warning. */
    MAP_FUNCTION(true, true),

    /** {@code ORDER MEMBER FUNCTION}. Ordinary function, plus the multi-attribute warning. */
    ORDER_FUNCTION(true, true);

    private final boolean returnsValue;
    private final boolean hasSelf;

    TypeMethodKind(boolean returnsValue, boolean hasSelf) {
        this.returnsValue = returnsValue;
        this.hasSelf = hasSelf;
    }

    /** True when the method has an {@code ALL_METHOD_RESULTS} row, i.e. it is a function. */
    public boolean returnsValue() {
        return returnsValue;
    }

    public boolean isProcedure() {
        return !returnsValue;
    }

    /** True when the method takes an implicit SELF, i.e. it is not static. */
    public boolean hasSelf() {
        return hasSelf;
    }

    public boolean isStatic() {
        return !hasSelf;
    }

    public boolean isConstructor() {
        return this == CONSTRUCTOR;
    }

    /** MAP and ORDER change the meaning of {@code =} and {@code ORDER BY}; see plan section 6. */
    public boolean isComparisonMethod() {
        return this == MAP_FUNCTION || this == ORDER_FUNCTION;
    }

    /** Human-readable form for logs, UI and error messages. */
    public String display() {
        return switch (this) {
            case MEMBER_FUNCTION -> "MEMBER FUNCTION";
            case MEMBER_PROCEDURE -> "MEMBER PROCEDURE";
            case STATIC_FUNCTION -> "STATIC FUNCTION";
            case STATIC_PROCEDURE -> "STATIC PROCEDURE";
            case CONSTRUCTOR -> "CONSTRUCTOR FUNCTION";
            case MAP_FUNCTION -> "MAP MEMBER FUNCTION";
            case ORDER_FUNCTION -> "ORDER MEMBER FUNCTION";
        };
    }
}
