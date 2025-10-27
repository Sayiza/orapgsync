package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser.*;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.antlr.v4.runtime.ParserRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * First pass: Type inference visitor.
 *
 * <p>Walks AST bottom-up (post-order traversal) to compute types following Oracle's rules.
 * Populates type cache with results keyed by token position.</p>
 *
 * <p><b>Design:</b></p>
 * <ul>
 *   <li>Extends PlSqlParserBaseVisitor&lt;TypeInfo&gt;</li>
 *   <li>Returns TypeInfo for each visited node</li>
 *   <li>Caches results using token position as key (stable across visitor instances)</li>
 *   <li>Manages scope stack for PL/SQL variable declarations</li>
 * </ul>
 *
 * <p><b>Phase 1 Implementation:</b> Literals and simple expressions only.</p>
 */
public class TypeAnalysisVisitor extends PlSqlParserBaseVisitor<TypeInfo> {

    private static final Logger log = LoggerFactory.getLogger(TypeAnalysisVisitor.class);

    private final String currentSchema;
    private final TransformationIndices indices;
    private final Map<String, TypeInfo> typeCache;  // Shared with FullTypeEvaluator

    // Scope management for PL/SQL variables (Phase 1: not used yet)
    private final Deque<Map<String, TypeInfo>> scopeStack = new ArrayDeque<>();

    // Query-local state (Phase 1: not used yet)
    private final Map<String, String> tableAliases = new HashMap<>();

    /**
     * Creates a type analysis visitor.
     *
     * @param currentSchema Schema context for resolution
     * @param indices Pre-built metadata indices
     * @param typeCache Shared cache to populate with type information
     */
    public TypeAnalysisVisitor(String currentSchema, TransformationIndices indices, Map<String, TypeInfo> typeCache) {
        if (currentSchema == null || currentSchema.trim().isEmpty()) {
            throw new IllegalArgumentException("Current schema cannot be null or empty");
        }
        if (indices == null) {
            throw new IllegalArgumentException("Indices cannot be null");
        }
        if (typeCache == null) {
            throw new IllegalArgumentException("Type cache cannot be null");
        }

        this.currentSchema = currentSchema;
        this.indices = indices;
        this.typeCache = typeCache;

        log.debug("TypeAnalysisVisitor created for schema: {}", currentSchema);
    }

    /**
     * Generates a unique cache key for an AST node using token position.
     *
     * <p>This is stable across multiple visitor instances for the same SQL string,
     * allowing both TypeAnalysisVisitor and PostgresCodeBuilder to reference the same nodes.</p>
     *
     * @param ctx Parse tree node
     * @return Unique key string (e.g., "125:150" for tokens from position 125 to 150)
     */
    protected String nodeKey(ParserRuleContext ctx) {
        if (ctx == null || ctx.start == null || ctx.stop == null) {
            // Fallback for nodes without token info (shouldn't happen in normal parsing)
            return "unknown:" + System.identityHashCode(ctx);
        }
        return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
    }

    /**
     * Caches a type for a given node and returns it.
     *
     * <p>Helper method to reduce boilerplate in visitor methods.</p>
     *
     * @param ctx Parse tree node
     * @param type Type to cache
     * @return The same type (for method chaining)
     */
    protected TypeInfo cacheAndReturn(ParserRuleContext ctx, TypeInfo type) {
        if (ctx != null && type != null) {
            String key = nodeKey(ctx);
            typeCache.put(key, type);
            log.trace("Cached type {} for node at {}", type.getCategory(), key);
        }
        return type;
    }

    // ========== Phase 1: Default Behavior ==========

    /**
     * Default behavior: Return UNKNOWN for all expressions.
     *
     * <p>Phase 1 will override specific visitor methods to handle literals and arithmetic.
     * All other expressions default to UNKNOWN (safe, conservative).</p>
     */
    @Override
    protected TypeInfo defaultResult() {
        return TypeInfo.UNKNOWN;
    }

    // ========== Phase 1: Literal Type Detection ==========

    /**
     * Visit constant - handles all literal types.
     *
     * <p>Constant is where literals are in the grammar:
     * numeric, quoted_string, NULL, TRUE, FALSE, DATE, TIMESTAMP, etc.</p>
     */
    @Override
    public TypeInfo visitConstant(ConstantContext ctx) {
        // Numeric literals (integers, floats)
        if (ctx.numeric() != null) {
            log.trace("Found numeric literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.NUMERIC);
        }

        // String literals
        if (ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
            log.trace("Found string literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.TEXT);
        }

        // NULL literal
        if (ctx.NULL_() != null) {
            log.trace("Found NULL literal");
            return cacheAndReturn(ctx, TypeInfo.NULL_TYPE);
        }

        // Boolean literals
        if (ctx.TRUE() != null || ctx.FALSE() != null) {
            log.trace("Found boolean literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.BOOLEAN);
        }

        // DATE literal: DATE 'YYYY-MM-DD'
        if (ctx.DATE() != null) {
            log.trace("Found DATE literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.DATE);
        }

        // TIMESTAMP literal
        if (ctx.TIMESTAMP() != null) {
            log.trace("Found TIMESTAMP literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.TIMESTAMP);
        }

        // Other constants (DBTIMEZONE, etc.) - treat as unknown for now
        log.trace("Unknown constant type: {}", ctx.getText());
        return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
    }

    // ========== Scope Management (Phase 5+) ==========
    // Currently unused - will be implemented in Phase 5

    /**
     * Enters a new scope (DECLARE block, BEGIN...END, FOR loop).
     * Phase 5+: Will be called when entering blocks.
     */
    protected void enterScope() {
        scopeStack.push(new HashMap<>());
        log.trace("Entered new scope, depth: {}", scopeStack.size());
    }

    /**
     * Exits current scope.
     * Phase 5+: Will be called when exiting blocks.
     */
    protected void exitScope() {
        if (!scopeStack.isEmpty()) {
            Map<String, TypeInfo> scope = scopeStack.pop();
            log.trace("Exited scope, {} variables dropped", scope.size());
        }
    }

    /**
     * Declares a variable in current scope.
     * Phase 5+: Will be called for variable declarations.
     */
    protected void declareVariable(String name, TypeInfo type) {
        if (!scopeStack.isEmpty() && name != null && type != null) {
            String normalizedName = name.toLowerCase();
            scopeStack.peek().put(normalizedName, type);
            log.trace("Declared variable: {} with type {}", normalizedName, type.getCategory());
        }
    }

    /**
     * Looks up variable type, searching from innermost to outermost scope.
     * Phase 5+: Will be used for variable reference resolution.
     *
     * @param name Variable name to look up
     * @return TypeInfo or UNKNOWN if not found
     */
    protected TypeInfo lookupVariable(String name) {
        if (name == null) {
            return TypeInfo.UNKNOWN;
        }

        String normalizedName = name.toLowerCase();

        // Search from inner to outer scopes
        for (Map<String, TypeInfo> scope : scopeStack) {
            TypeInfo type = scope.get(normalizedName);
            if (type != null) {
                log.trace("Resolved variable {} to type {}", normalizedName, type.getCategory());
                return type;
            }
        }

        log.trace("Variable {} not found in any scope", normalizedName);
        return TypeInfo.UNKNOWN;
    }
}
