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
     * <p>Constant is where literals are in the grammar:
     * numeric, quoted_string, NULL, TRUE, FALSE, DATE, TIMESTAMP, etc.</p>
     */
    @Override
    public TypeInfo visitConstant(ConstantContext ctx) {
        // DATE literal: DATE 'YYYY-MM-DD' - CHECK FIRST before quoted_string!
        if (ctx.DATE() != null) {
            log.trace("Found DATE literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.DATE);
        }

        // TIMESTAMP literal - CHECK BEFORE quoted_string!
        if (ctx.TIMESTAMP() != null) {
            log.trace("Found TIMESTAMP literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.TIMESTAMP);
        }

        // Numeric literals (integers, floats)
        if (ctx.numeric() != null) {
            log.trace("Found numeric literal: {}", ctx.getText());
            return cacheAndReturn(ctx, TypeInfo.NUMERIC);
        }

        // String literals (plain strings without DATE/TIMESTAMP keyword)
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

        // Other constants (DBTIMEZONE, etc.) - treat as unknown for now
        log.trace("Unknown constant type: {}", ctx.getText());
        return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
    }

    // ========== Phase 1: Arithmetic Operators ==========

    /**
     * Visit concatenation - handles all binary operators including arithmetic.
     *
     * <p>Grammar hierarchy for expressions:
     * <ul>
     *   <li>concatenation: arithmetic ops (+, -, *, /), power (**), string concat (||)</li>
     *   <li>Handles both numeric arithmetic and date arithmetic</li>
     * </ul>
     */
    @Override
    public TypeInfo visitConcatenation(ConcatenationContext ctx) {
        // Check for binary operators
        if (ctx.ASTERISK() != null || ctx.SOLIDUS() != null) {
            // * multiplication or / division
            return resolveArithmeticOperator(ctx, ctx.concatenation());
        }

        if (ctx.PLUS_SIGN() != null || ctx.MINUS_SIGN() != null) {
            // + addition or - subtraction
            // Special handling: DATE arithmetic
            return resolvePlusMinusOperator(ctx, ctx.concatenation());
        }

        if (ctx.DOUBLE_ASTERISK() != null) {
            // ** power operator - always returns NUMBER
            return cacheAndReturn(ctx, TypeInfo.NUMERIC);
        }

        if (ctx.MOD() != null) {
            // MOD operator - always returns NUMBER
            return cacheAndReturn(ctx, TypeInfo.NUMERIC);
        }

        // Check for || string concatenation
        if (ctx.BAR() != null && ctx.BAR().size() >= 2) {
            // String concatenation - always returns TEXT
            log.trace("String concatenation at {}", nodeKey(ctx));
            return cacheAndReturn(ctx, TypeInfo.TEXT);
        }

        // No binary operator - visit the child model_expression
        if (ctx.model_expression() != null) {
            TypeInfo childType = visit(ctx.model_expression());
            return cacheAndReturn(ctx, childType);
        }

        // Unsupported or unhandled cases (COLLATE, AT, interval_expression)
        log.trace("Unsupported concatenation at {}: {}", nodeKey(ctx), ctx.getText());
        return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
    }

    /**
     * Resolves type for arithmetic operators (*, /).
     *
     * <p>Rules:
     * <ul>
     *   <li>NUMBER * NUMBER → NUMBER</li>
     *   <li>NUMBER / NUMBER → NUMBER</li>
     *   <li>NULL in any operand → NULL_TYPE</li>
     *   <li>Otherwise → UNKNOWN</li>
     * </ul>
     */
    private TypeInfo resolveArithmeticOperator(ConcatenationContext ctx,
                                                java.util.List<ConcatenationContext> operands) {
        if (operands == null || operands.size() < 2) {
            return TypeInfo.UNKNOWN;
        }

        TypeInfo left = visit(operands.get(0));
        TypeInfo right = visit(operands.get(1));

        log.trace("Arithmetic operator: {} {} {}", left.getCategory(), ctx.getText(), right.getCategory());

        // NULL propagation
        if (left.isNull() || right.isNull()) {
            return TypeInfo.NULL_TYPE;
        }

        // Numeric arithmetic
        if (left.isNumeric() && right.isNumeric()) {
            return TypeInfo.NUMERIC;
        }

        // Unknown operand types
        return TypeInfo.UNKNOWN;
    }

    /**
     * Resolves type for + and - operators.
     *
     * <p>Rules:
     * <ul>
     *   <li>DATE + NUMBER → DATE (add days)</li>
     *   <li>DATE - NUMBER → DATE (subtract days)</li>
     *   <li>DATE - DATE → NUMBER (days difference)</li>
     *   <li>NUMBER + NUMBER → NUMBER</li>
     *   <li>NUMBER - NUMBER → NUMBER</li>
     *   <li>NULL in any operand → NULL_TYPE</li>
     *   <li>Otherwise → UNKNOWN</li>
     * </ul>
     */
    private TypeInfo resolvePlusMinusOperator(ConcatenationContext ctx,
                                               java.util.List<ConcatenationContext> operands) {
        if (operands == null || operands.size() < 2) {
            return TypeInfo.UNKNOWN;
        }

        TypeInfo left = visit(operands.get(0));
        TypeInfo right = visit(operands.get(1));

        boolean isPlus = ctx.PLUS_SIGN() != null;
        log.trace("{} operator: {} {} {}", (isPlus ? "+" : "-"),
                left.getCategory(), ctx.getText(), right.getCategory());

        // NULL propagation
        if (left.isNull() || right.isNull()) {
            return TypeInfo.NULL_TYPE;
        }

        // Date arithmetic
        if (left.isDate() && right.isNumeric()) {
            // DATE +/- NUMBER → DATE
            return left;  // Preserve DATE or TIMESTAMP
        }

        if (left.isDate() && right.isDate() && !isPlus) {
            // DATE - DATE → NUMBER (days difference)
            // Note: DATE + DATE is not valid
            return TypeInfo.NUMERIC;
        }

        // Numeric arithmetic
        if (left.isNumeric() && right.isNumeric()) {
            return TypeInfo.NUMERIC;
        }

        // Unknown operand types
        return TypeInfo.UNKNOWN;
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
     * Visit general_element to resolve column types.
     *
     * <p>Phase 2: Resolves column references to their types using metadata.</p>
     *
     * <p>Grammar structure:
     * <pre>
     * general_element
     *   : general_element_part ('.' general_element_part)*
     *   ;
     *
     * general_element_part
     *   : id_expression function_argument?  // Function call or column reference
     *   ;
     * </pre>
     * </p>
     *
     * <p>Examples:
     * <ul>
     *   <li>emp_id → unqualified column</li>
     *   <li>emp.emp_id → qualified column</li>
     *   <li>UPPER(name) → function call (not a column)</li>
     * </ul>
     */
    @Override
    public TypeInfo visitGeneral_element(General_elementContext ctx) {
        // Check if this is a function call (has function_argument with actual arguments)
        General_element_partContext firstPart = ctx.general_element_part() != null && !ctx.general_element_part().isEmpty()
            ? ctx.general_element_part().get(0)
            : null;

        if (firstPart != null && firstPart.function_argument() != null && !firstPart.function_argument().isEmpty()) {
            // Has function_argument list - check if it's actually a function call
            // (function_argument exists but might be empty for non-function identifiers)
            Function_argumentContext funcArg = firstPart.function_argument().get(0);
            if (funcArg != null && funcArg.LEFT_PAREN() != null) {
                // This is a function call, not a column reference
                // Visit children to process function arguments
                TypeInfo result = visitChildren(ctx);
                // Phase 3 will handle function return types
                // For now, return UNKNOWN for the function call itself
                return cacheAndReturn(ctx, TypeInfo.UNKNOWN);
            }
        }

        // This is a column reference - resolve it
        TypeInfo resolvedType = resolveColumnType(ctx);
        return cacheAndReturn(ctx, resolvedType);
    }

    /**
     * Resolves column type from metadata.
     *
     * <p>Handles both qualified and unqualified column references.</p>
     */
    private TypeInfo resolveColumnType(General_elementContext ctx) {
        if (ctx.general_element_part() == null || ctx.general_element_part().isEmpty()) {
            return TypeInfo.UNKNOWN;
        }

        int partCount = ctx.general_element_part().size();

        if (partCount == 1) {
            // Unqualified column: column_name
            String columnName = extractIdentifier(ctx.general_element_part().get(0));
            return resolveUnqualifiedColumn(columnName);

        } else if (partCount == 2) {
            // Qualified column: table.column or schema.table
            String qualifier = extractIdentifier(ctx.general_element_part().get(0));
            String columnName = extractIdentifier(ctx.general_element_part().get(1));
            return resolveQualifiedColumn(qualifier, columnName);

        } else if (partCount == 3) {
            // Fully qualified: schema.table.column
            String schema = extractIdentifier(ctx.general_element_part().get(0));
            String table = extractIdentifier(ctx.general_element_part().get(1));
            String column = extractIdentifier(ctx.general_element_part().get(2));
            return lookupColumnType(schema, table, column);

        } else {
            // Too many parts - not a simple column reference
            log.trace("Column reference has {} parts, cannot resolve", partCount);
            return TypeInfo.UNKNOWN;
        }
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

    /**
     * Resolves unqualified column reference.
     *
     * <p>Tries to resolve from all tables in current FROM clause.</p>
     */
    private TypeInfo resolveUnqualifiedColumn(String columnName) {
        if (columnName == null) {
            return TypeInfo.UNKNOWN;
        }

        // Try each table in the current FROM clause
        for (String tableName : tableAliases.values()) {
            TypeInfo type = lookupColumnType(currentSchema, tableName, columnName);
            if (!type.isUnknown()) {
                log.trace("Resolved unqualified column {} to type {} from table {}",
                    columnName, type.getCategory(), tableName);
                return type;
            }
        }

        log.trace("Could not resolve unqualified column: {}", columnName);
        return TypeInfo.UNKNOWN;
    }

    /**
     * Resolves qualified column reference: qualifier.column.
     *
     * <p>The qualifier could be a table name or alias.</p>
     */
    private TypeInfo resolveQualifiedColumn(String qualifier, String columnName) {
        if (qualifier == null || columnName == null) {
            return TypeInfo.UNKNOWN;
        }

        // Check if qualifier is a table alias
        String tableName = tableAliases.get(qualifier);
        if (tableName != null) {
            // Alias found - lookup column in that table
            TypeInfo type = lookupColumnType(currentSchema, tableName, columnName);
            if (!type.isUnknown()) {
                log.trace("Resolved qualified column {}.{} (alias) to type {}",
                    qualifier, columnName, type.getCategory());
                return type;
            }
        }

        // Not an alias - try as direct table name
        TypeInfo type = lookupColumnType(currentSchema, qualifier, columnName);
        if (!type.isUnknown()) {
            log.trace("Resolved qualified column {}.{} (table) to type {}",
                qualifier, columnName, type.getCategory());
            return type;
        }

        log.trace("Could not resolve qualified column: {}.{}", qualifier, columnName);
        return TypeInfo.UNKNOWN;
    }

    /**
     * Looks up column type from metadata indices.
     *
     * @param schema Schema name (normalized lowercase)
     * @param table Table name (normalized lowercase)
     * @param column Column name (normalized lowercase)
     * @return TypeInfo from metadata, or UNKNOWN if not found
     */
    private TypeInfo lookupColumnType(String schema, String table, String column) {
        if (schema == null || table == null || column == null) {
            return TypeInfo.UNKNOWN;
        }

        // Build lookup key: schema.table
        String tableKey = schema + "." + table;

        // Get column metadata from indices
        TransformationIndices.ColumnTypeInfo columnInfo = indices.getColumnType(tableKey, column);
        if (columnInfo == null) {
            log.trace("Column {} not found in table {}", column, tableKey);
            return TypeInfo.UNKNOWN;
        }

        // Map Oracle type to TypeInfo
        String oracleType = columnInfo.getTypeName();
        TypeInfo type = mapOracleTypeToTypeInfo(oracleType);
        log.trace("Mapped column {}.{}.{} type {} to {}",
            schema, table, column, oracleType, type.getCategory());
        return type;
    }

    /**
     * Maps Oracle type string to TypeInfo.
     *
     * <p>Examples:
     * <ul>
     *   <li>NUMBER → NUMERIC</li>
     *   <li>VARCHAR2 → TEXT</li>
     *   <li>DATE → DATE</li>
     *   <li>TIMESTAMP → DATE (TypeInfo.TIMESTAMP constant)</li>
     * </ul>
     */
    private TypeInfo mapOracleTypeToTypeInfo(String oracleType) {
        if (oracleType == null) {
            return TypeInfo.UNKNOWN;
        }

        // Normalize and extract base type (before parentheses)
        String normalized = oracleType.toUpperCase().trim();
        String baseType = normalized.split("\\(")[0].trim();

        // Map to TypeInfo categories
        switch (baseType) {
            case "NUMBER":
            case "INTEGER":
            case "FLOAT":
            case "BINARY_FLOAT":
            case "BINARY_DOUBLE":
                return TypeInfo.NUMERIC;

            case "VARCHAR2":
            case "VARCHAR":
            case "CHAR":
            case "NCHAR":
            case "NVARCHAR2":
            case "CLOB":
            case "NCLOB":
                return TypeInfo.TEXT;

            case "DATE":
                return TypeInfo.DATE;

            case "TIMESTAMP":
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                return TypeInfo.TIMESTAMP;

            case "BOOLEAN":
                return TypeInfo.BOOLEAN;

            default:
                // Unknown type (could be user-defined type, BLOB, etc.)
                log.trace("Unknown Oracle type: {}", oracleType);
                return TypeInfo.UNKNOWN;
        }
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
