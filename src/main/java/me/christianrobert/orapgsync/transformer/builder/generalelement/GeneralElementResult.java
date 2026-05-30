package me.christianrobert.orapgsync.transformer.builder.generalelement;

/**
 * Result object for GeneralElementTransformer transformations.
 *
 * <p>Indicates whether a transformer handled the input and provides the transformed result.
 * This follows a similar pattern to RownumContext in the rownum package.
 *
 * <p>Usage:
 * <pre>
 * GeneralElementResult result = transformer.tryTransform(parts, b);
 * if (result.isHandled()) {
 *     return result.getResult();
 * }
 * // Otherwise, try next transformer
 * </pre>
 */
public class GeneralElementResult {

    private static final GeneralElementResult NOT_HANDLED = new GeneralElementResult(false, null);

    private final boolean handled;
    private final String result;

    private GeneralElementResult(boolean handled, String result) {
        this.handled = handled;
        this.result = result;
    }

    /**
     * Creates a result indicating the transformer handled the input.
     *
     * @param result The transformed PostgreSQL expression
     * @return A handled result
     */
    public static GeneralElementResult handled(String result) {
        return new GeneralElementResult(true, result);
    }

    /**
     * Returns a result indicating the transformer did not handle the input.
     *
     * @return A not-handled result (singleton)
     */
    public static GeneralElementResult notHandled() {
        return NOT_HANDLED;
    }

    /**
     * @return true if the transformer handled the input
     */
    public boolean isHandled() {
        return handled;
    }

    /**
     * @return The transformed result (only valid if isHandled() is true)
     */
    public String getResult() {
        return result;
    }
}
