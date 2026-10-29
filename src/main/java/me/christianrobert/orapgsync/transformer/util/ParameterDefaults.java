package me.christianrobert.orapgsync.transformer.util;

import java.util.List;

/**
 * Decides which Oracle parameter defaults a PostgreSQL signature may carry.
 *
 * <p>The stub job (from {@code ALL_ARGUMENTS}) and the implementation (from the parse tree) both ask
 * this, and they must get the same answer: {@code CREATE OR REPLACE} refuses to drop a default the
 * existing function has (<i>cannot remove parameter defaults from existing function</i>), so a stub
 * with more defaults than its implementation can never be replaced.
 *
 * <p>Oracle lets any IN parameter have a default; callers skip the ones before a required parameter
 * with named notation. PostgreSQL requires every <em>input</em> parameter (IN or INOUT) after a
 * defaulted one to be defaulted too. So only the trailing run of defaulted input parameters is
 * emitted; an earlier default is dropped on both sides, and a call that omits it fails by name.
 * OUT parameters are not inputs and do not break the run.
 */
public final class ParameterDefaults {

    private ParameterDefaults() {
    }

    /** One parameter as far as this rule cares. */
    public record Param(boolean input, boolean defaulted) {
    }

    /**
     * @return for each parameter, in declaration order, whether its DEFAULT may be emitted
     */
    public static boolean[] emittable(List<Param> parameters) {
        boolean[] result = new boolean[parameters.size()];
        for (int i = parameters.size() - 1; i >= 0; i--) {
            Param p = parameters.get(i);
            if (!p.input()) {
                continue;
            }
            if (!p.defaulted()) {
                break;
            }
            result[i] = true;
        }
        return result;
    }

    /**
     * Whether an Oracle mode string ({@code IN}, {@code OUT}, {@code IN OUT}, {@code INOUT}, or
     * null for IN) names an input parameter.
     */
    public static boolean isInput(String oracleMode) {
        if (oracleMode == null) {
            return true;
        }
        String mode = oracleMode.toUpperCase();
        return !mode.contains("OUT") || mode.contains("IN");
    }
}
