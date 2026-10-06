package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Signals a method that is declared without a body and therefore has nothing to implement.
 *
 * <p><b>The one legitimate skip.</b> Everything else in the rejection catalogue is a failure and
 * must be reported as an error; this is genuinely "nothing to do" — an abstract method on a
 * {@code NOT INSTANTIABLE} type has no PL/SQL anywhere to translate.
 *
 * <p>It is a distinct exception type precisely so the two cannot be confused. Routing a failure to
 * the skip list is what let orchestration step 29 fail completely and still report success, so
 * "skip" needs to be something a caller has to ask for by name rather than a default landing place.
 */
public class AbstractTypeMethodException extends TransformationException {

    private final String methodName;

    public AbstractTypeMethodException(String methodName) {
        super("Type method '" + methodName + "' is declared without a body (abstract). There is "
                + "nothing to implement.", null, "type method: abstract");
        this.methodName = methodName;
    }

    public String getMethodName() {
        return methodName;
    }
}
