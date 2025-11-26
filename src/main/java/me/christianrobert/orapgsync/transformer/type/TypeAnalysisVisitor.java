package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser.*;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.helpers.*;
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
     * <p><b>Public access:</b> Helper classes need to call this method to generate keys
     * for looking up cached types.</p>
     *
     * @param ctx Parse tree node
     * @return Unique key string (e.g., "125:150" for tokens from position 125 to 150)
     */
    public String nodeKey(ParserRuleContext ctx) {
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

    /**
     * Override visit to ensure we always cache and return types for all nodes.
     * This ensures proper traversal and caching even for nodes we don't explicitly handle.
     */
    @Override
    public TypeInfo visit(org.antlr.v4.runtime.tree.ParseTree tree) {
        if (tree == null) {
            return TypeInfo.UNKNOWN;
        }

        // Visit the node (calls appropriate visitXXX method or default)
        TypeInfo result = super.visit(tree);

        // Cache the result if this is a ParserRuleContext
        if (tree instanceof ParserRuleContext && result != null) {
            ParserRuleContext ctx = (ParserRuleContext) tree;
            String key = nodeKey(ctx);
            if (!typeCache.containsKey(key)) {
                typeCache.put(key, result);
            }
        }

        return result != null ? result : TypeInfo.UNKNOWN;
    }

    // ========== Phase 1: Literal Type Detection ==========

    /**
     * Visit constant - handles all literal types.
     *
     * <p>Delegates to ResolveConstant helper for actual type resolution.</p>
     */
    @Override
    public TypeInfo visitConstant(ConstantContext ctx) {
        TypeInfo type = ResolveConstant.resolve(ctx);
        return cacheAndReturn(ctx, type);
    }

    // ========== Phase 1: Arithmetic Operators ==========

    /**
     * Visit concatenation - handles all binary operators including arithmetic.
     *
     * <p>Delegates to ResolveOperator helper for actual operator type resolution.</p>
     */
    @Override
    public TypeInfo visitConcatenation(ConcatenationContext ctx) {
        // Visit children first to populate type cache for operands
        visitChildren(ctx);

        // Delegate operator resolution to helper
        TypeInfo operatorType = ResolveOperator.resolve(ctx, this);

        // If operator helper returns UNKNOWN, try visiting child model_expression
        if (operatorType.isUnknown() && ctx.model_expression() != null) {
            TypeInfo childType = visit(ctx.model_expression());
            return cacheAndReturn(ctx, childType);
        }

        return cacheAndReturn(ctx, operatorType);
    }

    // ========== Phase 2: Column References and Metadata Integration ==========

    /**
     * Visit query_block to capture table aliases from FROM clause.
     *
     * <p>Phase 2: Tracks table aliases for column resolution within this query block.</p>
     */
    @Override
    public TypeInfo visitQuery_block(Query_blockContext ctx) {
        // Clear table aliases for this query block
        tableAliases.clear();

        // Visit FROM clause first to populate table aliases
        if (ctx.from_clause() != null) {
            visitFromClauseForAliases(ctx.from_clause());
        }

        // Now visit children (SELECT, WHERE, etc.) - they can use the aliases
        TypeInfo result = visitChildren(ctx);

        return cacheAndReturn(ctx, result);
    }

    /**
     * Visits FROM clause to extract table aliases.
     *
     * <p>Populates the tableAliases map for column resolution.</p>
     */
    private void visitFromClauseForAliases(From_clauseContext ctx) {
        if (ctx.table_ref_list() == null) {
            return;
        }

        for (Table_refContext tableRef : ctx.table_ref_list().table_ref()) {
            extractTableAlias(tableRef);
        }
    }

    /**
     * Extracts table name and alias from a table reference.
     *
     * <p>Examples:
     * <ul>
     *   <li>FROM employees → "employees" → "employees"</li>
     *   <li>FROM employees emp → "emp" → "employees"</li>
     *   <li>FROM hr.employees e → "e" → "employees"</li>
     * </ul>
     */
    private void extractTableAlias(Table_refContext ctx) {
        if (ctx.table_ref_aux() == null) {
            return;
        }

        // Get table name
        Table_ref_aux_internalContext aux = ctx.table_ref_aux().table_ref_aux_internal();
        if (aux == null) {
            return;
        }

        // Cast to specific type to access dml_table_expression_clause
        if (!(aux instanceof Table_ref_aux_internal_oneContext)) {
            // Not a simple table reference (could be subquery, etc.)
            return;
        }

        Table_ref_aux_internal_oneContext internalOne = (Table_ref_aux_internal_oneContext) aux;
        Dml_table_expression_clauseContext dmlCtx = internalOne.dml_table_expression_clause();
        if (dmlCtx == null || dmlCtx.tableview_name() == null) {
            return;
        }

        String tableName = extractTableName(dmlCtx.tableview_name());
        if (tableName == null) {
            return;
        }

        // Get alias (if exists)
        String alias = tableName;  // Default: use table name as alias
        if (ctx.table_ref_aux().table_alias() != null) {
            String explicitAlias = ctx.table_ref_aux().table_alias().getText();
            if (explicitAlias != null && !explicitAlias.trim().isEmpty()) {
                // Remove AS keyword if present
                explicitAlias = explicitAlias.replaceFirst("(?i)^AS\\s+", "");
                alias = explicitAlias;
            }
        }

        // Normalize to lowercase for case-insensitive lookup
        String normalizedAlias = alias.toLowerCase();
        String normalizedTable = tableName.toLowerCase();

        tableAliases.put(normalizedAlias, normalizedTable);
        log.trace("Captured table alias: {} → {}", normalizedAlias, normalizedTable);
    }

    /**
     * Extracts table name from tableview_name context.
     *
     * <p>Handles qualified names (schema.table) and simple names (table).</p>
     */
    private String extractTableName(Tableview_nameContext ctx) {
        if (ctx.identifier() == null) {
            return null;
        }

        String fullName = ctx.getText();
        if (fullName == null) {
            return null;
        }

        // Handle qualified names: schema.table → table
        if (fullName.contains(".")) {
            String[] parts = fullName.split("\\.");
            return parts[parts.length - 1];  // Return last part (table name)
        }

        return fullName;
    }

    /**
     * Visit general_element to resolve column types and function calls.
     *
     * <p>Delegates to appropriate helpers for resolution:</p>
     * <ul>
     *   <li>Function calls → ResolveFunction</li>
     *   <li>Pseudo-columns → ResolvePseudoColumn</li>
     *   <li>Column references → ResolveColumn</li>
     * </ul>
     */
    @Override
    public TypeInfo visitGeneral_element(General_elementContext ctx) {
        // Get first part to determine what this is
        General_element_partContext firstPart = ctx.general_element_part() != null && !ctx.general_element_part().isEmpty()
            ? ctx.general_element_part().get(0)
            : null;

        // Check if this is a function call (has function_argument with LEFT_PAREN)
        if (firstPart != null && firstPart.function_argument() != null && !firstPart.function_argument().isEmpty()) {
            Function_argumentContext funcArg = firstPart.function_argument().get(0);
            if (funcArg != null && funcArg.LEFT_PAREN() != null) {
                // Visit children first to cache argument types
                visitChildren(ctx);

                // Delegate to function resolution helper
                TypeInfo functionType = ResolveFunction.resolveFromGeneralElement(firstPart, funcArg, typeCache, this);
                return cacheAndReturn(ctx, functionType);
            }
        }

        // Check if this is a pseudo-column (single identifier)
        if (firstPart != null && ctx.general_element_part().size() == 1) {
            String identifier = extractIdentifier(firstPart);
            if (identifier != null) {
                TypeInfo pseudoColumnType = ResolvePseudoColumn.resolve(identifier);
                if (!pseudoColumnType.isUnknown()) {
                    return cacheAndReturn(ctx, pseudoColumnType);
                }
            }
        }

        // Delegate to column resolution helper
        TypeInfo columnType = ResolveColumn.resolve(ctx, currentSchema, indices, tableAliases);
        return cacheAndReturn(ctx, columnType);
    }

    /**
     * Extracts identifier text from general_element_part.
     */
    private String extractIdentifier(General_element_partContext partCtx) {
        if (partCtx == null || partCtx.id_expression() == null) {
            return null;
        }
        String text = partCtx.id_expression().getText();
        return text != null ? text.toLowerCase() : null;
    }

    // ========== Phase 3: Built-in Functions ==========

    /**
     * Visit other_function - handles Oracle built-in functions with special grammar rules.
     *
     * <p>Delegates to ResolveFunction helper for actual function type resolution.</p>
     */
    @Override
    public TypeInfo visitOther_function(Other_functionContext ctx) {
        // Visit children first to cache argument types
        visitChildren(ctx);

        // Delegate to function resolution helper
        TypeInfo functionType = ResolveFunction.resolveFromOtherFunction(ctx, typeCache, this);
        return cacheAndReturn(ctx, functionType);
    }

    /**
     * Visit string_function - handles Oracle string functions.
     *
     * <p>Delegates to ResolveFunction helper for actual function type resolution.</p>
     */
    @Override
    public TypeInfo visitString_function(String_functionContext ctx) {
        // Visit children first to cache argument types
        visitChildren(ctx);

        // Delegate to function resolution helper
        TypeInfo functionType = ResolveFunction.resolveFromStringFunction(ctx, typeCache, this);
        return cacheAndReturn(ctx, functionType);
    }

    /**
     * Visit numeric_function_wrapper - handles Oracle numeric functions.
     *
     * <p>Delegates to numeric_function visitor.</p>
     */
    @Override
    public TypeInfo visitNumeric_function_wrapper(Numeric_function_wrapperContext ctx) {
        // Visit children first
        visitChildren(ctx);

        // Delegate to numeric_function
        if (ctx.numeric_function() != null) {
            return visitNumeric_function(ctx.numeric_function());
        }

        return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
    }

    /**
     * Visit numeric_function - handles specific numeric functions.
     *
     * <p>Delegates to ResolveFunction helper for actual function type resolution.</p>
     */
    @Override
    public TypeInfo visitNumeric_function(Numeric_functionContext ctx) {
        // Visit children first to cache argument types
        visitChildren(ctx);

        // Delegate to function resolution helper
        TypeInfo functionType = ResolveFunction.resolveFromNumericFunction(ctx, typeCache, this);
        return cacheAndReturn(ctx, functionType);
    }

    /**
     * Visit standard_function - handles Oracle built-in functions.
     *
     * <p>Delegates to specialized visitors.</p>
     */
    @Override
    public TypeInfo visitStandard_function(Standard_functionContext ctx) {
        // Standard functions delegate to string_function, numeric_function_wrapper, json_function, other_function
        TypeInfo result = visitChildren(ctx);
        return cacheAndReturn(ctx, result);
    }

    // ========== Phase 4: Complex Expressions (Scalar Subqueries) ==========

    /**
     * Visit subquery to infer scalar subquery types.
     *
     * <p>A scalar subquery is a subquery that returns a single column value.
     * We propagate the type of that single column to the subquery node itself,
     * enabling accurate type inference in expressions like:</p>
     *
     * <pre>
     * TRUNC(hire_date) + (SELECT 1 FROM dual)  -- Should detect DATE + NUMERIC
     * </pre>
     *
     * <p>Phase 4: Handles scalar subqueries only (single column SELECT).
     * Multi-column subqueries return UNKNOWN.</p>
     */
    @Override
    public TypeInfo visitSubquery(SubqueryContext ctx) {
        // Visit children first (populates cache for inner expressions)
        visitChildren(ctx);

        // Try to infer scalar subquery type
        TypeInfo scalarType = inferScalarSubqueryType(ctx);

        return cacheAndReturn(ctx, scalarType);
    }

    /**
     * Infers the type of a scalar subquery by examining its SELECT list.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Navigate to query_block</li>
     *   <li>Check if SELECT list has exactly one element (scalar subquery)</li>
     *   <li>Look up the cached type of that single expression</li>
     *   <li>Return the expression's type (or UNKNOWN if not scalar)</li>
     * </ol>
     *
     * @param ctx Subquery context
     * @return TypeInfo of the scalar expression, or UNKNOWN if not a scalar subquery
     */
    private TypeInfo inferScalarSubqueryType(SubqueryContext ctx) {
        // Navigate to query block
        Subquery_basic_elementsContext basicElements = ctx.subquery_basic_elements();
        if (basicElements == null || basicElements.query_block() == null) {
            log.trace("Subquery has no basic elements or query block, returning UNKNOWN");
            return TypeInfo.UNKNOWN;
        }

        Query_blockContext queryBlock = basicElements.query_block();
        Selected_listContext selectedList = queryBlock.selected_list();

        if (selectedList == null || selectedList.select_list_elements() == null) {
            log.trace("Subquery has no selected list, returning UNKNOWN");
            return TypeInfo.UNKNOWN;
        }

        var elements = selectedList.select_list_elements();

        // Only handle scalar subqueries (single column SELECT)
        if (elements.size() != 1) {
            log.trace("Subquery has {} columns (not scalar), returning UNKNOWN", elements.size());
            return TypeInfo.UNKNOWN;  // Multi-column subquery
        }

        // Get the single expression's type from cache
        Select_list_elementsContext element = elements.get(0);
        if (element.expression() != null) {
            String key = nodeKey(element.expression());
            TypeInfo cachedType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
            log.trace("Scalar subquery resolved to type: {}", cachedType.getCategory());
            return cachedType;
        }

        log.trace("Subquery element has no expression, returning UNKNOWN");
        return TypeInfo.UNKNOWN;
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
