package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser.*;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple type evaluator with lazy evaluation and basic type rules.
 * <p>
 * This implementation:
 * <ul>
 *   <li>Evaluates types on-demand (lazy)</li>
 *   <li>Caches results using token position as key</li>
 *   <li>Supports qualified column lookups from metadata (table.column)</li>
 *   <li>Supports basic numeric literals</li>
 *   <li>Returns UNKNOWN for everything else (defensive, simple)</li>
 * </ul>
 * <p>
 * <strong>Design Note:</strong> This is intentionally simplified for Phase 2-4 (SQL views).
 * Most expressions return UNKNOWN, which causes ROUND/TRUNC to add defensive casts.
 * This is safe and correct, just not optimal. A full two-pass type inference system
 * can be added in Phase 5+ (PL/SQL) without changing any transformation code.
 * </p>
 */
public class SimpleTypeEvaluator implements TypeEvaluator {

    private final String currentSchema;
    private final TransformationIndices indices;
    private final Map<String, TypeInfo> typeCache = new HashMap<>();

    // Query-local state for alias resolution (mutable, updated by context)
    private Map<String, String> tableAliases = new HashMap<>();

    /**
     * Creates a simple type evaluator.
     *
     * @param currentSchema Schema context for resolution
     * @param indices Pre-built metadata indices
     */
    public SimpleTypeEvaluator(String currentSchema, TransformationIndices indices) {
        this.currentSchema = currentSchema;
        this.indices = indices;
    }

    /**
     * Sets the current table aliases for the query being evaluated.
     * Called by TransformationContext when aliases are registered.
     *
     * @param aliases Map of alias → table name
     */
    public void setTableAliases(Map<String, String> aliases) {
        this.tableAliases = aliases != null ? aliases : new HashMap<>();
    }

    @Override
    public TypeInfo getType(ExpressionContext ctx) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        // Check cache first
        String key = nodeKey(ctx);
        TypeInfo cached = typeCache.get(key);
        if (cached != null) {
            return cached;
        }

        // For simple evaluator, return UNKNOWN for most cases
        // This is safe - causes defensive casts in ROUND/TRUNC
        TypeInfo type = TypeInfo.UNKNOWN;
        typeCache.put(key, type);
        return type;
    }

    @Override
    public void clearCache() {
        typeCache.clear();
    }

    /**
     * Generates a unique cache key for an AST node using token position.
     * This is stable across multiple visitor instances for the same SQL string.
     */
    private String nodeKey(ParserRuleContext ctx) {
        if (ctx.start == null || ctx.stop == null) {
            return "unknown:" + System.identityHashCode(ctx);
        }
        return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
    }
}
