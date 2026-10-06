package me.christianrobert.orapgsync.typemethod.service;

/**
 * Thrown when a method's data dictionary rows do not match any classification rule.
 *
 * <p>Deliberately not recoverable by falling back to a default. The bug this replaces was exactly
 * a silent default: {@code METHOD_TYPE} was compared against values it never carries, every
 * comparison failed, and every method was quietly treated as a static procedure while the job
 * reported success.
 */
public class TypeMethodClassificationException extends RuntimeException {

    private final String typeName;
    private final String methodName;

    public TypeMethodClassificationException(String typeName, String methodName, String reason) {
        super(String.format("Cannot classify type method %s.%s: %s", typeName, methodName, reason));
        this.typeName = typeName;
        this.methodName = methodName;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getMethodName() {
        return methodName;
    }
}
