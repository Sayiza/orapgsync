package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser.ExpressionContext;
import org.antlr.v4.runtime.ParserRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Full type evaluator backed by pre-computed type cache.
 *
 * <p>Used by PostgresCodeBuilder to query types computed during the type analysis pass.
 * This replaces SimpleTypeEvaluator for PL/SQL transformation where accurate type information
 * is critical for correct transformation decisions.</p>
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>TypeAnalysisVisitor populates the cache during first pass</li>
 *   <li>FullTypeEvaluator queries the cache during transformation pass</li>
 *   <li>Both use same token position-based keys for node identification</li>
 * </ul>
 *
 * <p><b>Key Benefits:</b></p>
 * <ul>
 *   <li>No duplicate traversal - types computed once, queried many times</li>
 *   <li>Immutable after type analysis pass (thread-safe for read)</li>
 *   <li>Same key generation as TypeAnalysisVisitor (stable token positions)</li>
 * </ul>
 */
public class FullTypeEvaluator implements TypeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FullTypeEvaluator.class);

    private final Map<String, TypeInfo> typeCache;  // Populated by TypeAnalysisVisitor

    /**
     * Creates a full type evaluator with pre-populated cache.
     *
     * @param typeCache Cache populated by TypeAnalysisVisitor (shared reference)
     */
    public FullTypeEvaluator(Map<String, TypeInfo> typeCache) {
        if (typeCache == null) {
            throw new IllegalArgumentException("Type cache cannot be null");
        }
        this.typeCache = typeCache;
        log.debug("FullTypeEvaluator created with {} cached types", typeCache.size());
    }

    @Override
    public TypeInfo getType(ExpressionContext ctx) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        String key = nodeKey(ctx);
        TypeInfo type = typeCache.get(key);

        if (type != null) {
            log.trace("Type cache hit for {}: {}", key, type.getCategory());
            return type;
        }

        log.trace("Type cache miss for {}", key);
        return TypeInfo.UNKNOWN;
    }

    @Override
    public void clearCache() {
        // No-op: Cache is immutable after type analysis pass.
        // Clearing would break the two-pass architecture.
        log.warn("clearCache() called on FullTypeEvaluator - this is a no-op. " +
                "Type cache is immutable after type analysis pass.");
    }

    /**
     * Generates a unique cache key for an AST node using token position.
     *
     * <p>This MUST match the key generation in TypeAnalysisVisitor for lookups to work.</p>
     *
     * @param ctx Parse tree node
     * @return Unique key string (e.g., "125:150" for tokens from position 125 to 150)
     */
    private String nodeKey(ParserRuleContext ctx) {
        if (ctx == null || ctx.start == null || ctx.stop == null) {
            // Fallback for nodes without token info (shouldn't happen in normal parsing)
            return "unknown:" + System.identityHashCode(ctx);
        }
        return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
    }

    /**
     * Returns the number of types in the cache (for debugging/metrics).
     *
     * @return Cache size
     */
    public int getCacheSize() {
        return typeCache.size();
    }

    /**
     * Checks if cache contains type for given node (for debugging/testing).
     *
     * @param ctx Parse tree node
     * @return true if type is cached
     */
    public boolean hasCachedType(ExpressionContext ctx) {
        if (ctx == null) {
            return false;
        }
        String key = nodeKey(ctx);
        return typeCache.containsKey(key);
    }
}
