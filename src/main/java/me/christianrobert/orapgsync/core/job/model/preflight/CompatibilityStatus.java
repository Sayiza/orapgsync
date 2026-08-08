package me.christianrobert.orapgsync.core.job.model.preflight;

/**
 * Outcome of analysing one Oracle object in the pre-flight compatibility report.
 */
public enum CompatibilityStatus {

    /** Transformed without error and contains no construct the transformer drops. */
    OK,

    /**
     * Transformed without error, but the source contains a construct that is dropped or has no
     * visitor — the generated code is likely to be silently wrong.
     */
    OK_WITH_WARNINGS,

    /** The Oracle source could not be parsed; the grammar or the source itself is the problem. */
    PARSE_ERROR,

    /**
     * The parser stopped before the end of the source without reporting an error, so part of the
     * Oracle code was never seen by the transformer. The generated object is a truncated
     * fragment of the original.
     */
    TRUNCATED_PARSE,

    /** Parsed, but the transformation reported an error. */
    TRANSFORM_ERROR,

    /** No Oracle source available in state, so nothing could be analysed. */
    NO_SOURCE
}
