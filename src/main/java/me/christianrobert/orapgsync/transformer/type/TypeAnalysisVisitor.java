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
                // Visit children first to process function arguments (build up type cache)
                visitChildren(ctx);

                // Phase 3: Resolve function return type
                TypeInfo functionType = resolveFunctionType(ctx, firstPart, funcArg);
                return cacheAndReturn(ctx, functionType);
            }
        }

        // Check if this is a pseudo-column (SYSDATE, SYSTIMESTAMP, ROWNUM, etc.)
        // These are special identifiers that look like columns but have fixed types
        if (firstPart != null && ctx.general_element_part().size() == 1) {
            // Single identifier - could be a pseudo-column
            String identifier = extractIdentifier(firstPart);
            if (identifier != null) {
                TypeInfo pseudoColumnType = resolvePseudoColumn(identifier);
                if (!pseudoColumnType.isUnknown()) {
                    log.trace("Resolved pseudo-column {} to type {}", identifier, pseudoColumnType.getCategory());
                    return cacheAndReturn(ctx, pseudoColumnType);
                }
            }
        }

        // This is a column reference - resolve it from metadata
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

    /**
     * Resolves Oracle pseudo-columns to their types.
     *
     * <p>Pseudo-columns are special identifiers that Oracle treats as built-in values.
     * They look like column references but have fixed types and don't exist in table metadata.</p>
     *
     * <p>Common pseudo-columns:</p>
     * <ul>
     *   <li>SYSDATE, CURRENT_DATE → DATE</li>
     *   <li>SYSTIMESTAMP, CURRENT_TIMESTAMP → TIMESTAMP</li>
     *   <li>ROWNUM, LEVEL, ROWID → NUMERIC (or special)</li>
     *   <li>USER, UID → TEXT/NUMERIC</li>
     * </ul>
     *
     * @param identifier Identifier name (already normalized to lowercase)
     * @return TypeInfo for pseudo-column, or UNKNOWN if not a pseudo-column
     */
    private TypeInfo resolvePseudoColumn(String identifier) {
        if (identifier == null) {
            return TypeInfo.UNKNOWN;
        }

        String upperIdentifier = identifier.toUpperCase();

        switch (upperIdentifier) {
            // Date/time pseudo-columns
            case "SYSDATE":
            case "CURRENT_DATE":
                return TypeInfo.DATE;

            case "SYSTIMESTAMP":
            case "CURRENT_TIMESTAMP":
            case "LOCALTIMESTAMP":
                return TypeInfo.TIMESTAMP;

            // Numeric pseudo-columns
            case "ROWNUM":
            case "LEVEL":
            case "UID":
                return TypeInfo.NUMERIC;

            // String pseudo-columns
            case "USER":
            case "ROWID":
            case "SESSIONTIMEZONE":
            case "DBTIMEZONE":
                return TypeInfo.TEXT;

            default:
                // Not a recognized pseudo-column
                return TypeInfo.UNKNOWN;
        }
    }

    // ========== Phase 3: Built-in Functions ==========

    /**
     * Visit other_function - handles Oracle built-in functions with special grammar rules.
     *
     * <p>This includes functions like COUNT, TO_CHAR, TO_DATE, SYSDATE, etc.
     * which have dedicated grammar patterns in the other_function rule.</p>
     */
    @Override
    public TypeInfo visitOther_function(Other_functionContext ctx) {
        // Visit children first to cache argument types
        visitChildren(ctx);

        // Determine function type based on context
        TypeInfo functionType = TypeInfo.UNKNOWN;

        // COUNT function
        if (ctx.COUNT() != null) {
            functionType = TypeInfo.NUMERIC;
        }
        // TO_NUMBER, TO_TIMESTAMP, etc.
        else if (ctx.TO_NUMBER() != null) {
            functionType = TypeInfo.NUMERIC;
        }
        else if (ctx.TO_TIMESTAMP() != null || ctx.TO_TIMESTAMP_TZ() != null) {
            functionType = TypeInfo.TIMESTAMP;
        }
        else if (ctx.TO_DSINTERVAL() != null || ctx.TO_YMINTERVAL() != null) {
            functionType = TypeInfo.TEXT;  // Intervals
        }
        // EXTRACT function
        else if (ctx.EXTRACT() != null) {
            functionType = TypeInfo.NUMERIC;
        }
        // CAST function - would need to parse target type (Phase 4)
        else if (ctx.CAST() != null || ctx.XMLCAST() != null) {
            functionType = TypeInfo.UNKNOWN;  // Cannot determine without parsing AS type_spec
        }
        // COALESCE function
        else if (ctx.COALESCE() != null) {
            // Get argument types and resolve to highest precedence
            java.util.List<TypeInfo> argTypes = new java.util.ArrayList<>();
            if (ctx.table_element() != null) {
                String key = nodeKey(ctx.table_element());
                argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
            }
            // Additional arguments - COALESCE grammar: COALESCE '(' table_element (',' (numeric | quoted_string))? ')'
            // So there's only one optional additional argument which is either numeric or quoted_string
            if (ctx.numeric() != null) {
                argTypes.add(TypeInfo.NUMERIC);
            }
            if (ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
                argTypes.add(TypeInfo.TEXT);
            }
            functionType = resolveCoalesceType(argTypes);
        }
        // String functions
        else if (ctx.TRIM() != null || ctx.TRANSLATE() != null) {
            functionType = TypeInfo.TEXT;
        }
        // Window functions that return numeric (over_clause_keyword)
        else if (ctx.over_clause_keyword() != null) {
            String funcName = ctx.over_clause_keyword().getText().toUpperCase();
            // Most window functions return NUMERIC (COUNT, SUM, AVG, MIN, MAX, etc.)
            // Exception: MIN/MAX return argument type
            functionType = TypeInfo.NUMERIC;
        }
        // Ranking functions (within_or_over_clause_keyword)
        else if (ctx.within_or_over_clause_keyword() != null) {
            // RANK, DENSE_RANK, etc. return NUMERIC
            functionType = TypeInfo.NUMERIC;
        }
        // FIRST_VALUE, LAST_VALUE return argument type
        else if (ctx.FIRST_VALUE() != null || ctx.LAST_VALUE() != null) {
            // Would need to inspect argument type - for now return UNKNOWN
            functionType = TypeInfo.UNKNOWN;
        }
        // LEAD, LAG return argument type
        else if (ctx.LEAD() != null || ctx.LAG() != null) {
            // Would need to inspect argument type - for now return UNKNOWN
            functionType = TypeInfo.UNKNOWN;
        }
        // XML functions - typically return text/XML type
        else if (ctx.XMLAGG() != null || ctx.XMLCOLATTVAL() != null || ctx.XMLFOREST() != null ||
                 ctx.XMLELEMENT() != null || ctx.XMLPARSE() != null || ctx.XMLPI() != null ||
                 ctx.XMLQUERY() != null || ctx.XMLROOT() != null || ctx.XMLSERIALIZE() != null) {
            functionType = TypeInfo.TEXT;  // XML functions return CLOB/text
        }

        log.trace("other_function resolved to type {}", functionType.getCategory());
        return cacheAndReturn(ctx, functionType);
    }

    /**
     * Visit string_function - handles Oracle string functions.
     *
     * <p>This includes TO_CHAR, TO_DATE, SUBSTR, DECODE, NVL, TRIM.</p>
     */
    @Override
    public TypeInfo visitString_function(String_functionContext ctx) {
        // Visit children first to cache argument types
        visitChildren(ctx);

        TypeInfo functionType = TypeInfo.UNKNOWN;

        // TO_CHAR always returns TEXT
        if (ctx.TO_CHAR() != null) {
            functionType = TypeInfo.TEXT;
        }
        // TO_DATE always returns DATE
        else if (ctx.TO_DATE() != null) {
            functionType = TypeInfo.DATE;
        }
        // String manipulation functions return TEXT
        else if (ctx.SUBSTR() != null || ctx.CHR() != null || ctx.TRIM() != null) {
            functionType = TypeInfo.TEXT;
        }
        // DECODE - return type is highest precedence of result expressions
        else if (ctx.DECODE() != null) {
            // expressions_ contains all arguments
            if (ctx.expressions_() != null && ctx.expressions_().expression() != null) {
                java.util.List<TypeInfo> argTypes = new java.util.ArrayList<>();
                // Skip first argument (the expression being decoded)
                // Collect types of result expressions (every other arg after first)
                java.util.List<ExpressionContext> exprs = ctx.expressions_().expression();
                for (int i = 1; i < exprs.size(); i++) {
                    // Results are at odd indices: 2, 4, 6, ...
                    // (index 0 is expr, 1 is search1, 2 is result1, 3 is search2, 4 is result2, ...)
                    if (i >= 2 && (i - 2) % 2 == 0) {
                        String key = nodeKey(exprs.get(i));
                        argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
                    }
                }
                // If odd number of args > 2, last arg is default value (also a result)
                if (exprs.size() > 2 && (exprs.size() % 2 == 0)) {
                    String key = nodeKey(exprs.get(exprs.size() - 1));
                    argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
                }
                functionType = resolveCoalesceType(argTypes);  // Same logic as COALESCE
            } else {
                functionType = TypeInfo.UNKNOWN;
            }
        }
        // NVL - return type is highest precedence of arguments
        else if (ctx.NVL() != null) {
            java.util.List<TypeInfo> argTypes = new java.util.ArrayList<>();
            if (ctx.expression() != null && ctx.expression().size() >= 2) {
                String key1 = nodeKey(ctx.expression().get(0));
                String key2 = nodeKey(ctx.expression().get(1));
                argTypes.add(typeCache.getOrDefault(key1, TypeInfo.UNKNOWN));
                argTypes.add(typeCache.getOrDefault(key2, TypeInfo.UNKNOWN));
            }
            functionType = resolveCoalesceType(argTypes);
        }

        log.trace("string_function resolved to type {}", functionType.getCategory());
        return cacheAndReturn(ctx, functionType);
    }

    /**
     * Visit numeric_function_wrapper - handles Oracle numeric functions.
     *
     * <p>This includes ROUND, TRUNC, COUNT, SUM, AVG, MAX, MIN, etc.</p>
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
     */
    @Override
    public TypeInfo visitNumeric_function(Numeric_functionContext ctx) {
        // Visit children first to cache argument types
        visitChildren(ctx);

        TypeInfo functionType = TypeInfo.NUMERIC;  // Default for all numeric functions

        // All numeric functions (COUNT, SUM, AVG, ROUND, MAX, LEAST, GREATEST) return NUMERIC by default
        // ROUND can return DATE if argument is DATE - check expression type
        if (ctx.ROUND() != null && ctx.expression() != null) {
            String key = nodeKey(ctx.expression());
            TypeInfo argType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
            if (argType.isDate()) {
                functionType = argType;  // DATE → DATE
            }
        }
        // MAX return argument type (could be DATE or NUMBER)
        else if (ctx.MAX() != null && ctx.expression() != null) {
            String key = nodeKey(ctx.expression());
            functionType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
            if (functionType.isUnknown()) {
                functionType = TypeInfo.NUMERIC;  // Default if unknown
            }
        }
        // LEAST, GREATEST return highest precedence type
        else if (ctx.LEAST() != null || ctx.GREATEST() != null) {
            java.util.List<TypeInfo> argTypes = new java.util.ArrayList<>();
            if (ctx.expressions_() != null && ctx.expressions_().expression() != null) {
                for (ExpressionContext expr : ctx.expressions_().expression()) {
                    String key = nodeKey(expr);
                    argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
                }
            }
            functionType = resolveCoalesceType(argTypes);
            if (functionType.isUnknown()) {
                functionType = TypeInfo.NUMERIC;  // Default
            }
        }

        log.trace("numeric_function resolved to type {}", functionType.getCategory());
        return cacheAndReturn(ctx, functionType);
    }

    /**
     * Visit standard_function - handles Oracle built-in functions.
     *
     * <p>This includes standard functions that delegate to specialized visitors.</p>
     */
    @Override
    public TypeInfo visitStandard_function(Standard_functionContext ctx) {
        // Standard functions don't have explicit tokens at this level
        // They delegate to string_function, numeric_function_wrapper, json_function, other_function
        // Visit children to let them handle it
        TypeInfo result = visitChildren(ctx);
        return cacheAndReturn(ctx, result);
    }

    /**
     * Resolves function return type.
     *
     * <p>Phase 3: Determines return type based on function name and argument types.</p>
     *
     * @param ctx General element context (the whole function call)
     * @param firstPart First part containing function name
     * @param funcArg Function argument context
     * @return Function return type
     */
    private TypeInfo resolveFunctionType(General_elementContext ctx,
                                          General_element_partContext firstPart,
                                          Function_argumentContext funcArg) {
        // Extract function name
        String functionName = extractIdentifier(firstPart);
        if (functionName == null) {
            log.trace("Could not extract function name");
            return TypeInfo.UNKNOWN;
        }

        // Extract argument types (already visited and cached)
        java.util.List<TypeInfo> argumentTypes = new java.util.ArrayList<>();
        if (funcArg.argument() != null) {
            for (ArgumentContext arg : funcArg.argument()) {
                if (arg.expression() != null) {
                    // Look up cached type for this argument expression
                    String key = nodeKey(arg.expression());
                    TypeInfo argType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
                    argumentTypes.add(argType);
                }
            }
        }

        // Resolve function return type
        TypeInfo returnType = getFunctionReturnType(functionName, argumentTypes);
        log.trace("Function {}(...) returns type {}", functionName, returnType.getCategory());
        return returnType;
    }

    /**
     * Maps Oracle built-in function names to their return types.
     *
     * <p>Handles polymorphic functions where return type depends on argument types.</p>
     *
     * @param functionName Function name (normalized to lowercase)
     * @param argumentTypes Argument types
     * @return Function return type
     */
    private TypeInfo getFunctionReturnType(String functionName, java.util.List<TypeInfo> argumentTypes) {
        String upperName = functionName.toUpperCase();

        switch (upperName) {
            // ========== Polymorphic Functions ==========

            case "ROUND":
            case "TRUNC":
                // Return type matches first argument type
                // DATE → DATE, NUMBER → NUMBER
                if (!argumentTypes.isEmpty() && argumentTypes.get(0) != null) {
                    TypeInfo firstArgType = argumentTypes.get(0);
                    if (firstArgType.isDate()) {
                        return firstArgType;  // Preserve DATE or TIMESTAMP
                    } else if (firstArgType.isNumeric()) {
                        return TypeInfo.NUMERIC;
                    }
                }
                // Default: assume numeric
                return TypeInfo.NUMERIC;

            // ========== Date Functions ==========

            case "SYSDATE":
            case "CURRENT_DATE":
            case "LAST_DAY":
            case "NEXT_DAY":
                return TypeInfo.DATE;

            case "SYSTIMESTAMP":
            case "CURRENT_TIMESTAMP":
                return TypeInfo.TIMESTAMP;

            case "ADD_MONTHS":
                // ADD_MONTHS(date, n) → DATE
                return TypeInfo.DATE;

            case "MONTHS_BETWEEN":
                // MONTHS_BETWEEN(date1, date2) → NUMBER
                return TypeInfo.NUMERIC;

            case "EXTRACT":
                // EXTRACT(field FROM date) → NUMBER
                return TypeInfo.NUMERIC;

            // ========== String Functions ==========

            case "UPPER":
            case "LOWER":
            case "INITCAP":
            case "TRIM":
            case "LTRIM":
            case "RTRIM":
            case "SUBSTR":
            case "SUBSTRING":
            case "REPLACE":
            case "TRANSLATE":
            case "LPAD":
            case "RPAD":
            case "CHR":
            case "CONCAT":
                return TypeInfo.TEXT;

            case "LENGTH":
            case "INSTR":
            case "ASCII":
                return TypeInfo.NUMERIC;

            // ========== Conversion Functions ==========

            case "TO_CHAR":
                return TypeInfo.TEXT;

            case "TO_NUMBER":
                return TypeInfo.NUMERIC;

            case "TO_DATE":
                return TypeInfo.DATE;

            case "TO_TIMESTAMP":
                return TypeInfo.TIMESTAMP;

            case "CAST":
                // CAST(expr AS type) - would need to parse target type
                // For now, return UNKNOWN (Phase 4 enhancement)
                return TypeInfo.UNKNOWN;

            // ========== NULL-Handling Functions ==========

            case "NVL":
            case "COALESCE":
                // Return type is highest precedence of non-null arguments
                return resolveCoalesceType(argumentTypes);

            case "NVL2":
                // NVL2(expr1, expr2, expr3) - return type is highest of expr2 and expr3
                if (argumentTypes.size() >= 3) {
                    java.util.List<TypeInfo> resultTypes = new java.util.ArrayList<>();
                    resultTypes.add(argumentTypes.get(1));  // expr2 (if not null)
                    resultTypes.add(argumentTypes.get(2));  // expr3 (if null)
                    return resolveCoalesceType(resultTypes);
                }
                return TypeInfo.UNKNOWN;

            case "DECODE":
                // DECODE(expr, search1, result1, ..., default)
                // Return type is highest precedence of all result expressions
                return resolveDecodeType(argumentTypes);

            case "NULLIF":
                // NULLIF(expr1, expr2) - returns expr1 type or NULL
                if (!argumentTypes.isEmpty()) {
                    return argumentTypes.get(0);
                }
                return TypeInfo.UNKNOWN;

            // ========== Aggregate Functions ==========

            case "COUNT":
                return TypeInfo.NUMERIC;

            case "SUM":
            case "AVG":
            case "MIN":
            case "MAX":
                // Return type matches argument type
                if (!argumentTypes.isEmpty()) {
                    return argumentTypes.get(0);
                }
                return TypeInfo.NUMERIC;  // Default for SUM/AVG

            // ========== Numeric Functions ==========

            case "ABS":
            case "CEIL":
            case "FLOOR":
            case "MOD":
            case "POWER":
            case "SQRT":
            case "EXP":
            case "LN":
            case "LOG":
            case "SIGN":
                return TypeInfo.NUMERIC;

            // ========== Unknown Functions ==========

            default:
                // User-defined function or package function - cannot determine type without metadata
                log.trace("Unknown function: {}, returning UNKNOWN", functionName);
                return TypeInfo.UNKNOWN;
        }
    }

    /**
     * Resolves return type for NVL/COALESCE functions.
     *
     * <p>Returns highest precedence type among all arguments.</p>
     *
     * <p>Type precedence: TIMESTAMP > DATE > NUMBER > TEXT</p>
     */
    private TypeInfo resolveCoalesceType(java.util.List<TypeInfo> argumentTypes) {
        TypeInfo resultType = TypeInfo.UNKNOWN;

        for (TypeInfo argType : argumentTypes) {
            if (argType.isUnknown() || argType.isNull()) {
                // Skip UNKNOWN and NULL - they don't affect result type
                continue;
            }

            if (resultType.isUnknown()) {
                // First known type
                resultType = argType;
            } else {
                // Choose higher precedence type
                resultType = higherPrecedence(resultType, argType);
            }
        }

        return resultType;
    }

    /**
     * Resolves return type for DECODE function.
     *
     * <p>DECODE(expr, search1, result1, search2, result2, ..., default)</p>
     * <p>Return type is highest precedence among all result expressions (odd-indexed args after first).</p>
     */
    private TypeInfo resolveDecodeType(java.util.List<TypeInfo> argumentTypes) {
        if (argumentTypes.size() < 3) {
            // Need at least: expr, search, result
            return TypeInfo.UNKNOWN;
        }

        TypeInfo resultType = TypeInfo.UNKNOWN;

        // Result expressions are at indices 2, 4, 6, ...
        // If arg count is even, last arg is default value (also a result)
        for (int i = 2; i < argumentTypes.size(); i += 2) {
            TypeInfo argType = argumentTypes.get(i);
            if (argType.isUnknown() || argType.isNull()) {
                continue;
            }

            if (resultType.isUnknown()) {
                resultType = argType;
            } else {
                resultType = higherPrecedence(resultType, argType);
            }
        }

        // If arg count is odd, there's a default value at the end
        if (argumentTypes.size() % 2 == 0) {
            TypeInfo defaultType = argumentTypes.get(argumentTypes.size() - 1);
            if (!defaultType.isUnknown() && !defaultType.isNull()) {
                if (resultType.isUnknown()) {
                    resultType = defaultType;
                } else {
                    resultType = higherPrecedence(resultType, defaultType);
                }
            }
        }

        return resultType;
    }

    /**
     * Determines higher precedence type between two types.
     *
     * <p>Oracle type precedence: TIMESTAMP > DATE > NUMBER > TEXT</p>
     *
     * @param t1 First type
     * @param t2 Second type
     * @return Higher precedence type
     */
    private TypeInfo higherPrecedence(TypeInfo t1, TypeInfo t2) {
        // TIMESTAMP has highest precedence
        if (t1.getCategory() == TypeInfo.TypeCategory.DATE && t1 == TypeInfo.TIMESTAMP) {
            return t1;
        }
        if (t2.getCategory() == TypeInfo.TypeCategory.DATE && t2 == TypeInfo.TIMESTAMP) {
            return t2;
        }

        // DATE (but not TIMESTAMP)
        if (t1.isDate() && !t2.isDate()) {
            return t1;
        }
        if (t2.isDate() && !t1.isDate()) {
            return t2;
        }

        // Both DATE - return first (they're compatible)
        if (t1.isDate() && t2.isDate()) {
            return t1;
        }

        // NUMBER over TEXT
        if (t1.isNumeric() && !t2.isNumeric()) {
            return t1;
        }
        if (t2.isNumeric() && !t1.isNumeric()) {
            return t2;
        }

        // Both same category or both TEXT - return first
        return t1;
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
