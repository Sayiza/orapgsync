package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser.*;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.helpers.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    // Scope management for PL/SQL variables (Phase 5+)
    private final Deque<Map<String, TypeInfo>> scopeStack = new ArrayDeque<>();

    // Query scope management for table aliases (Phase 4.5: hierarchical subquery scopes)
    // Each scope represents a query level (outer query, subquery, etc.)
    // Inner scopes can reference outer scopes (correlated subqueries)
    private final Deque<Map<String, String>> tableAliasScopes = new ArrayDeque<>();

    // CTE scope management (Phase 4.6: CTE column type tracking)
    // Each scope represents a query level and contains CTE definitions visible at that level
    // CTEs defined in WITH clause are visible in main query and all subqueries
    private final Deque<Map<String, CteDefinition>> cteScopes = new ArrayDeque<>();

    /**
     * CTE definition: name -> (column name -> type).
     *
     * <p>Example: WITH c AS (SELECT emp_id, hire_date FROM employees)</p>
     * <ul>
     *   <li>cteName = "c"</li>
     *   <li>columns = {"emp_id" -> NUMERIC, "hire_date" -> DATE}</li>
     * </ul>
     */
    private static class CteDefinition {
        final String cteName;
        final Map<String, TypeInfo> columns;  // lowercase column names

        CteDefinition(String cteName) {
            this.cteName = cteName.toLowerCase();
            this.columns = new HashMap<>();
        }

        void addColumn(String columnName, TypeInfo type) {
            if (columnName != null && type != null) {
                columns.put(columnName.toLowerCase(), type);
            }
        }

        TypeInfo getColumnType(String columnName) {
            if (columnName == null) {
                return TypeInfo.UNKNOWN;
            }
            return columns.getOrDefault(columnName.toLowerCase(), TypeInfo.UNKNOWN);
        }
    }

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

        // Initialize with global query scope
        tableAliasScopes.push(new HashMap<>());
        cteScopes.push(new HashMap<>());

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
     * <p>Phase 4.5: Creates new query scope for table aliases.
     * This ensures subqueries don't pollute outer query aliases.</p>
     */
    @Override
    public TypeInfo visitQuery_block(Query_blockContext ctx) {
        // Enter new query scope
        enterQueryScope();

        try {
            // Visit FROM clause first to populate table aliases for THIS query level
            if (ctx.from_clause() != null) {
                visitFromClauseForAliases(ctx.from_clause());
            }

            // Now visit children (SELECT, WHERE, etc.) - they can use the aliases
            // Subqueries will create their own scopes via recursive visitQuery_block calls
            TypeInfo result = visitChildren(ctx);

            return cacheAndReturn(ctx, result);
        } finally {
            // Exit query scope (even if exception occurs)
            exitQueryScope();
        }
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
     * <p>Handles both main FROM clause tables and JOIN clause tables.</p>
     *
     * <p>Examples:
     * <ul>
     *   <li>FROM employees → "employees" → "employees"</li>
     *   <li>FROM employees emp → "emp" → "employees"</li>
     *   <li>FROM hr.employees e → "e" → "employees"</li>
     *   <li>FROM employees e JOIN departments d → extracts both "e" and "d"</li>
     * </ul>
     */
    private void extractTableAlias(Table_refContext ctx) {
        // Step 1: Process main table (from table_ref_aux)
        if (ctx.table_ref_aux() != null) {
            extractTableAliasFromAux(ctx.table_ref_aux());
        }

        // Step 2: Process JOIN clauses (if any)
        if (ctx.join_clause() != null && !ctx.join_clause().isEmpty()) {
            for (Join_clauseContext joinCtx : ctx.join_clause()) {
                if (joinCtx.table_ref_aux() != null) {
                    extractTableAliasFromAux(joinCtx.table_ref_aux());
                }
            }
        }
    }

    /**
     * Extracts table name and alias from a table_ref_aux context.
     *
     * <p>This helper method is reused for both main FROM clause tables and JOIN clause tables.</p>
     *
     * @param auxCtx Table reference auxiliary context
     */
    private void extractTableAliasFromAux(Table_ref_auxContext auxCtx) {
        if (auxCtx == null) {
            return;
        }

        // Get table name
        Table_ref_aux_internalContext aux = auxCtx.table_ref_aux_internal();
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
        if (auxCtx.table_alias() != null) {
            String explicitAlias = auxCtx.table_alias().getText();
            if (explicitAlias != null && !explicitAlias.trim().isEmpty()) {
                // Remove AS keyword if present
                explicitAlias = explicitAlias.replaceFirst("(?i)^AS\\s+", "");
                alias = explicitAlias;
            }
        }

        // Normalize to lowercase for case-insensitive lookup
        String normalizedAlias = alias.toLowerCase();
        String normalizedTable = tableName.toLowerCase();

        // Register alias in current query scope
        declareTableAlias(normalizedAlias, normalizedTable);
        log.trace("Captured table alias: {} → {}", normalizedAlias, normalizedTable);
    }

    /**
     * Extracts table name from tableview_name context.
     *
     * <p>Handles qualified names (schema.table) and simple names (table).</p>
     *
     * <p><b>IMPORTANT:</b> Preserves schema qualification when explicitly specified.
     * This ensures that cross-schema table references resolve correctly regardless
     * of the current transformation context schema.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>co_abs.abs_werk_sperren → "co_abs.abs_werk_sperren" (qualified, preserved)</li>
     *   <li>employees → "employees" (unqualified, just table name)</li>
     * </ul>
     */
    private String extractTableName(Tableview_nameContext ctx) {
        if (ctx.identifier() == null) {
            return null;
        }

        String fullName = ctx.getText();
        if (fullName == null) {
            return null;
        }

        // PRESERVE schema qualification if present
        // This allows ResolveColumn to use the explicitly specified schema
        // instead of defaulting to currentSchema
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

        // Delegate to column resolution helper (uses hierarchical scope lookup + CTE resolution)
        TypeInfo columnType = ResolveColumn.resolve(ctx, currentSchema, indices, this::resolveTableAlias, this::getAllTablesInScope, this::resolveCteColumn);
        return cacheAndReturn(ctx, columnType);
    }

    /**
     * Resolves CTE column type (used as BiFunction for ResolveColumn).
     *
     * <p>Phase 4.6: Looks up CTE definition and returns column type.</p>
     *
     * @param cteName CTE name
     * @param columnName Column name
     * @return TypeInfo from CTE, or UNKNOWN if not found
     */
    private TypeInfo resolveCteColumn(String cteName, String columnName) {
        CteDefinition cte = resolveCte(cteName);
        if (cte != null) {
            return cte.getColumnType(columnName);
        }
        return TypeInfo.UNKNOWN;
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
     * Visit atom to propagate types through parentheses.
     *
     * <p>In the AST, parenthesized expressions are wrapped in Atom nodes like this:</p>
     * <pre>
     * Atom cases:
     *   1. '(' subquery ')'              <- Scalar subquery
     *   2. '(' expressions_ ')'          <- Parenthesized expression (e.g., (42), (salary + 1000))
     *   3. constant                      <- Already has type from visitConstant
     *   4. general_element               <- Already has type from visitGeneral_element
     *   5. bind_variable                 <- Variable reference
     * </pre>
     *
     * <p>This visitor ensures the Atom node propagates types correctly in all cases.</p>
     */
    @Override
    public TypeInfo visitAtom(AtomContext ctx) {
        // Visit children first (populates cache for child nodes)
        visitChildren(ctx);

        // Case 1: Atom wraps a scalar subquery - '(' subquery ')'
        if (ctx.subquery() != null) {
            String subqueryKey = nodeKey(ctx.subquery());
            TypeInfo subqueryType = typeCache.getOrDefault(subqueryKey, TypeInfo.UNKNOWN);
            return cacheAndReturn(ctx, subqueryType);
        }

        // Case 2: Atom wraps a parenthesized expression - '(' expressions_ ')'
        if (ctx.expressions_() != null) {
            // Get the first expression from expressions_ (for single-expression parentheses)
            if (ctx.expressions_().expression() != null && !ctx.expressions_().expression().isEmpty()) {
                ExpressionContext firstExpr = ctx.expressions_().expression().get(0);
                String exprKey = nodeKey(firstExpr);
                TypeInfo exprType = typeCache.getOrDefault(exprKey, TypeInfo.UNKNOWN);
                return cacheAndReturn(ctx, exprType);
            }
        }

        // Case 3-5: For other atom types (constant, general_element, bind_variable),
        // try to find a child with a cached type
        for (int i = 0; i < ctx.getChildCount(); i++) {
            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);
            if (child instanceof ParserRuleContext) {
                String childKey = nodeKey((ParserRuleContext) child);
                TypeInfo childType = typeCache.get(childKey);
                if (childType != null && !childType.isUnknown()) {
                    return cacheAndReturn(ctx, childType);
                }
            }
        }

        // Fallback: UNKNOWN
        return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
    }

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

    // ========== Query Scope Management (Phase 4.5) ==========

    /**
     * Enters a new query scope (for subqueries).
     *
     * <p>Phase 4.5: Creates new table alias scope to prevent pollution.</p>
     * <p>Phase 4.6: Creates new CTE scope for CTE tracking.</p>
     */
    protected void enterQueryScope() {
        tableAliasScopes.push(new HashMap<>());
        cteScopes.push(new HashMap<>());
        log.trace("Entered new query scope, depth: {}", tableAliasScopes.size());
    }

    /**
     * Exits current query scope.
     *
     * <p>Phase 4.5: Removes current query's table aliases.</p>
     * <p>Phase 4.6: Removes current query's CTEs.</p>
     */
    protected void exitQueryScope() {
        if (!tableAliasScopes.isEmpty()) {
            Map<String, String> aliasScope = tableAliasScopes.pop();
            log.trace("Exited query scope, {} aliases dropped", aliasScope.size());
        }
        if (!cteScopes.isEmpty()) {
            Map<String, CteDefinition> cteScope = cteScopes.pop();
            log.trace("Exited query scope, {} CTEs dropped", cteScope.size());
        }
    }

    /**
     * Declares a table alias in current query scope.
     *
     * <p>Phase 4.5: Registers table alias for column resolution.</p>
     *
     * @param alias Table alias (normalized to lowercase)
     * @param tableName Actual table name (normalized to lowercase)
     */
    protected void declareTableAlias(String alias, String tableName) {
        if (!tableAliasScopes.isEmpty() && alias != null && tableName != null) {
            tableAliasScopes.peek().put(alias, tableName);
            log.trace("Declared table alias: {} → {}", alias, tableName);
        }
    }

    /**
     * Resolves table alias, searching from innermost to outermost query scope.
     *
     * <p>Phase 4.5: Supports correlated subqueries (inner queries can reference outer tables).</p>
     *
     * @param alias Table alias to resolve
     * @return Actual table name, or null if not found
     */
    public String resolveTableAlias(String alias) {
        if (alias == null) {
            return null;
        }

        String normalizedAlias = alias.toLowerCase();

        // Search from inner to outer query scopes
        for (Map<String, String> scope : tableAliasScopes) {
            String tableName = scope.get(normalizedAlias);
            if (tableName != null) {
                log.trace("Resolved table alias {} to {}", normalizedAlias, tableName);
                return tableName;
            }
        }

        log.trace("Table alias {} not found in any query scope", normalizedAlias);
        return null;
    }

    /**
     * Gets all unique table names visible in current query scope.
     *
     * <p>Phase 4.5: Collects table names from current and all outer scopes.
     * Used for unqualified column resolution.</p>
     *
     * @return Collection of all visible table names (may include schema-qualified names)
     */
    public Collection<String> getAllTablesInScope() {
        java.util.Set<String> allTables = new java.util.LinkedHashSet<>();

        // Collect unique table names from all scopes (inner to outer)
        for (Map<String, String> scope : tableAliasScopes) {
            allTables.addAll(scope.values());
        }

        return allTables;
    }

    /**
     * Resolves CTE definition, searching from innermost to outermost query scope.
     *
     * <p>Phase 4.6: Supports nested queries referencing CTEs from outer scopes.</p>
     *
     * @param cteName CTE name to resolve
     * @return CteDefinition, or null if not found
     */
    public CteDefinition resolveCte(String cteName) {
        if (cteName == null) {
            return null;
        }

        String normalizedName = cteName.toLowerCase();

        // Search from inner to outer query scopes
        for (Map<String, CteDefinition> scope : cteScopes) {
            CteDefinition cte = scope.get(normalizedName);
            if (cte != null) {
                log.trace("Resolved CTE {} with {} columns", normalizedName, cte.columns.size());
                return cte;
            }
        }

        log.trace("CTE {} not found in any query scope", normalizedName);
        return null;
    }

    // ========== Phase 4.6: CTE Analysis ==========

    /**
     * Visit with_clause to analyze CTEs and populate column types.
     *
     * <p>Phase 4.6: Analyzes CTE SELECT lists to determine column types.
     * CTEs are registered in current scope and visible to main query and subqueries.</p>
     *
     * <p>Example:</p>
     * <pre>
     * WITH c AS (SELECT number_days tg FROM config_table)
     * SELECT tg FROM c  -- Can resolve 'tg' type from CTE definition
     * </pre>
     */
    @Override
    public TypeInfo visitWith_clause(With_clauseContext ctx) {
        // CTEs belong to the current query scope (not a new scope)
        // They are visible in the main query and all subqueries

        if (ctx.with_factoring_clause() == null || ctx.with_factoring_clause().isEmpty()) {
            return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
        }

        // Process each CTE (WITH c AS (...), d AS (...), ...)
        for (With_factoring_clauseContext factoringCtx : ctx.with_factoring_clause()) {
            if (factoringCtx.subquery_factoring_clause() != null) {
                processSubqueryFactoringClause(factoringCtx.subquery_factoring_clause());
            }
        }

        // Visit children to continue normal type analysis
        visitChildren(ctx);

        return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
    }

    /**
     * Processes a single CTE (subquery_factoring_clause).
     *
     * <p>Extracts CTE name, analyzes SELECT list, and registers column types.</p>
     */
    private void processSubqueryFactoringClause(Subquery_factoring_clauseContext ctx) {
        // Extract CTE name
        if (ctx.query_name() == null) {
            return;
        }

        String cteName = ctx.query_name().getText();
        if (cteName == null || cteName.trim().isEmpty()) {
            return;
        }

        log.trace("Processing CTE: {}", cteName);

        // Create CTE definition
        CteDefinition cteDef = new CteDefinition(cteName);

        // Check if explicit column list is provided: WITH c (col1, col2) AS ...
        List<String> explicitColumns = null;
        if (ctx.paren_column_list() != null && ctx.paren_column_list().column_list() != null) {
            explicitColumns = new ArrayList<>();
            for (var colName : ctx.paren_column_list().column_list().column_name()) {
                explicitColumns.add(colName.getText());
            }
        }

        // Analyze the CTE's SELECT statement
        if (ctx.subquery() == null) {
            return;
        }

        // Visit the CTE body first to populate type cache
        visit(ctx.subquery());

        // Navigate to query_block to get SELECT list
        Subquery_basic_elementsContext basicElements = ctx.subquery().subquery_basic_elements();
        if (basicElements == null || basicElements.query_block() == null) {
            log.trace("CTE {} has no query block, skipping", cteName);
            return;
        }

        Query_blockContext queryBlock = basicElements.query_block();
        Selected_listContext selectedList = queryBlock.selected_list();

        if (selectedList == null || selectedList.select_list_elements() == null) {
            log.trace("CTE {} has no selected list, skipping", cteName);
            return;
        }

        // Extract column names and types from SELECT list
        int colIndex = 0;
        for (Select_list_elementsContext selectElem : selectedList.select_list_elements()) {
            String columnName;

            // Determine column name
            if (explicitColumns != null && colIndex < explicitColumns.size()) {
                // Use explicit column name from WITH c (col1, col2) AS ...
                columnName = explicitColumns.get(colIndex);
            } else if (selectElem.column_alias() != null) {
                // Use column alias from SELECT
                columnName = selectElem.column_alias().getText();
                // Remove quotes if present
                columnName = columnName.replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
            } else if (selectElem.expression() != null) {
                // Try to extract column name from expression (e.g., SELECT emp_id -> "emp_id")
                columnName = extractColumnNameFromExpression(selectElem.expression());
                if (columnName == null) {
                    // Fallback: auto-generate column name
                    columnName = "column_" + colIndex;
                }
            } else {
                // Fallback: auto-generate column name
                columnName = "column_" + colIndex;
            }

            // Get type from expression
            TypeInfo colType = TypeInfo.UNKNOWN;
            if (selectElem.expression() != null) {
                String exprKey = nodeKey(selectElem.expression());
                colType = typeCache.getOrDefault(exprKey, TypeInfo.UNKNOWN);
            }

            cteDef.addColumn(columnName, colType);
            log.trace("CTE {} column: {} -> {}", cteName, columnName, colType.getCategory());

            colIndex++;
        }

        // Register CTE in current scope
        if (!cteScopes.isEmpty()) {
            cteScopes.peek().put(cteName.toLowerCase(), cteDef);
            log.trace("Registered CTE {} with {} columns", cteName, cteDef.columns.size());
        }
    }

    /**
     * Attempts to extract column name from expression.
     *
     * <p>For simple column references, returns the column name.
     * For complex expressions, returns null.</p>
     */
    private String extractColumnNameFromExpression(ExpressionContext ctx) {
        // For simple expressions like "emp_id" or "t.emp_id", try to extract the column name
        if (ctx == null) {
            return null;
        }

        String text = ctx.getText();
        if (text == null) {
            return null;
        }

        // Simple heuristic: if it looks like a column reference (no operators, functions, etc.)
        // Just use the last part after the last dot
        if (text.contains(".")) {
            String[] parts = text.split("\\.");
            return parts[parts.length - 1];
        }

        // For simple identifiers, use as-is
        if (text.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return text;
        }

        // For anything else (literals, functions, expressions), return null
        return null;
    }

    // ========== PL/SQL Variable Scope Management (Phase 5+) ==========
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
