package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser.*;

/**
 * Strategy interface for evaluating expression types during SQL transformation.
 * <p>
 * This abstraction allows different type evaluation strategies:
 * <ul>
 *   <li><strong>Simple (current):</strong> {@link SimpleTypeEvaluator} - Lazy evaluation with basic rules</li>
 *   <li><strong>Two-pass (future):</strong> Pre-compute all types in a first pass, cache for second pass</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong> Transformation helpers call {@code getType()} when they need type information
 * to make decisions (e.g., whether ROUND needs a cast).
 * </p>
 * <p>
 * <strong>Design Goal:</strong> Transformation code should depend only on this interface,
 * not on any specific implementation. This allows swapping implementations without
 * touching transformation logic.
 * </p>
 *
 * @see SimpleTypeEvaluator
 */
public interface TypeEvaluator {

    /**
     * Evaluates the type of an expression node.
     * <p>
     * Implementations may use different strategies:
     * <ul>
     *   <li>Lazy: Compute on-demand, cache results</li>
     *   <li>Pre-computed: Look up from pre-built cache (two-pass)</li>
     * </ul>
     *
     * @param ctx The expression context to evaluate
     * @return The type of the expression, or {@link TypeInfo#UNKNOWN} if it cannot be determined
     */
    TypeInfo getType(ExpressionContext ctx);

    /**
     * Clears any cached type information (if applicable).
     * <p>
     * Simple implementations may clear their cache between queries.
     * Two-pass implementations typically don't need this (cache is per-query).
     */
    void clearCache();
}
