package me.christianrobert.orapgsync.core.job.model.typemethod;

/**
 * The key under which a type method's Oracle source is stored and retrieved.
 *
 * <p>A method name is not unique within an object type — Oracle allows arbitrarily many overloads —
 * so the key carries the method's <strong>ordinal within its name</strong>. Storing by bare name
 * collapsed every overload of a name into a single entry, so all but one lost its source.
 *
 * <p>Exists as one function used by both the storing and the retrieving side, because a key format
 * that the two sides derive independently is a key format that eventually diverges.
 */
public final class TypeMethodSourceKey {

    private TypeMethodSourceKey() {
    }

    /**
     * @param methodName      method name, case-insensitive
     * @param overloadOrdinal zero-based rank among the overloads sharing that name
     */
    public static String of(String methodName, int overloadOrdinal) {
        if (methodName == null) {
            throw new IllegalArgumentException("methodName must not be null");
        }
        if (overloadOrdinal < 0) {
            throw new IllegalArgumentException(
                    "overloadOrdinal must be assigned before use, got " + overloadOrdinal
                            + " for method " + methodName);
        }
        return methodName.toLowerCase() + "#" + overloadOrdinal;
    }
}
