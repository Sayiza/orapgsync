package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unified context for all transformation-time information.
 *
 * <p><strong>Architecture: Three-Layer Context Design</strong></p>
 *
 * <p>This context is the SINGLE SOURCE OF TRUTH for all transformation state,
 * organized into three conceptual layers based on mutability and lifecycle:</p>
 *
 * <h3>Layer 1: Immutable Global Context</h3>
 * <p>Set at context creation, never changes during transformation:</p>
 * <ul>
 *   <li>{@link #currentSchema} - Schema context for name resolution (e.g., "hr")</li>
 *   <li>{@link #indices} - Pre-built metadata indices for O(1) lookups</li>
 *   <li>{@link #typeEvaluator} - Type inference engine for ROUND/TRUNC disambiguation</li>
 * </ul>
 *
 * <h3>Layer 2: Transformation-Level Context</h3>
 * <p>Set once per transformation (function/procedure/view), immutable after context creation:</p>
 * <ul>
 *   <li>{@link #currentFunctionName} - Current function/procedure name (null for views)</li>
 *   <li>{@link #currentPackageName} - Current package name (null for standalone/views)</li>
 *   <li>{@link #packageContextCache} - Package variable definitions (for package functions)</li>
 *   <li>{@link #inlineTypes} - Inline type definitions (FUTURE - function-local types)</li>
 * </ul>
 *
 * <h3>Layer 3: Query-Level Context</h3>
 * <p>Mutable, changes during transformation (per-query or per-statement state):</p>
 * <ul>
 *   <li>{@link #tableAliases} - Table aliases for current query</li>
 *   <li>{@link #cteNames} - CTE names from WITH clause</li>
 * </ul>
 *
 * <p><strong>Benefits of Unified Context:</strong></p>
 * <ul>
 *   <li>Single source of truth - No duplicate state (e.g., schema in multiple places)</li>
 *   <li>No parameter explosion - Context grows internally, API stays stable</li>
 *   <li>Future-proof - Inline types, nested blocks, etc. fit naturally</li>
 *   <li>Simpler builder - PostgresCodeBuilder only needs context, no separate parameters</li>
 *   <li>Clear ownership - Context owns all transformation-level state</li>
 * </ul>
 *
 * <p><strong>Type Resolution Cascade (Future with inline types):</strong></p>
 * <ol>
 *   <li>Check inline types (function-local) - highest precedence</li>
 *   <li>Check package types (package-level from PackageContext)</li>
 *   <li>Check global types (schema-level from TransformationIndices)</li>
 *   <li>Built-in types (NUMBER, VARCHAR2, etc.)</li>
 * </ol>
 *
 * @see TransformationIndices
 * @see PackageContext
 * @see InlineTypeDefinition
 */
public class TransformationContext {

    // ========== Layer 1: Immutable Global Context ==========

    private final String currentSchema;
    private final TransformationIndices indices;
    private final TypeEvaluator typeEvaluator;

    // ========== Layer 2: Transformation-Level Context ==========

    /**
     * Current function or procedure name (e.g., "calculate_salary").
     * Null for view transformations.
     */
    private final String currentFunctionName;

    /**
     * Current package name (e.g., "emp_pkg").
     * Null for standalone functions/procedures or views.
     */
    private final String currentPackageName;

    /**
     * Package variable contexts, keyed by "schema.package" (lowercase).
     * Contains package variable definitions for package function transformations.
     * Empty map for standalone functions or views.
     */
    private final Map<String, PackageContext> packageContextCache;

    /**
     * Inline type definitions (function-local types).
     * FUTURE - infrastructure ready, implementation deferred.
     * Example: TYPE salary_breakdown_t IS RECORD (...) defined inside a function.
     */
    private final Map<String, InlineTypeDefinition> inlineTypes;

    // ========== Layer 3: Query-Level Context ==========

    private final Map<String, String> tableAliases;  // alias → table name (per query)
    private final Set<String> cteNames;  // CTE names (from WITH clause)

    // Future: Nested block context for exception handling, variable scoping
    // When implementing nested blocks (DECLARE...BEGIN...END inside functions), add:
    // - private final Deque<BlockContext> blockStack;  // Stack of nested blocks
    // - public void enterBlock(BlockContext block);
    // - public void exitBlock();
    // This will enable proper scoping for variables, exceptions, and labels.

    /**
     * Creates context with minimal parameters (for SQL views without package context).
     *
     * <p>This constructor is for simple SQL transformations (views) that don't need
     * package context or function context.</p>
     *
     * @param currentSchema Schema context for resolution (e.g., "hr")
     * @param indices Pre-built metadata indices for fast lookups
     * @param typeEvaluator Type evaluator for type-aware transformations
     */
    public TransformationContext(String currentSchema, TransformationIndices indices, TypeEvaluator typeEvaluator) {
        this(currentSchema, indices, typeEvaluator, null, null, null);
    }

    /**
     * Creates context with full transformation-level context (for PL/SQL functions/procedures).
     *
     * <p>This constructor is for PL/SQL transformations that may involve package variables,
     * inline types, or need function/package context.</p>
     *
     * @param currentSchema Schema context for resolution (e.g., "hr")
     * @param indices Pre-built metadata indices for fast lookups
     * @param typeEvaluator Type evaluator for type-aware transformations
     * @param packageContextCache Package variable contexts (null for standalone/views)
     * @param functionName Current function/procedure name (null for views)
     * @param packageName Current package name (null for standalone/views)
     */
    public TransformationContext(String currentSchema,
                                 TransformationIndices indices,
                                 TypeEvaluator typeEvaluator,
                                 Map<String, PackageContext> packageContextCache,
                                 String functionName,
                                 String packageName) {
        // Validate required parameters
        if (currentSchema == null || currentSchema.trim().isEmpty()) {
            throw new IllegalArgumentException("Current schema cannot be null or empty");
        }
        if (indices == null) {
            throw new IllegalArgumentException("Indices cannot be null");
        }
        if (typeEvaluator == null) {
            throw new IllegalArgumentException("Type evaluator cannot be null");
        }

        // Layer 1: Immutable global context
        this.currentSchema = currentSchema;
        this.indices = indices;
        this.typeEvaluator = typeEvaluator;

        // Layer 2: Transformation-level context (immutable after construction)
        this.currentFunctionName = functionName;
        this.currentPackageName = packageName;
        this.packageContextCache = packageContextCache != null ? packageContextCache : new HashMap<>();
        this.inlineTypes = new HashMap<>();  // FUTURE - ready for inline type support

        // Layer 3: Query-level context (mutable during transformation)
        this.tableAliases = new HashMap<>();
        this.cteNames = new HashSet<>();
    }

    // ========== Layer 1: Global Context Accessors ==========

    public String getCurrentSchema() {
        return currentSchema;
    }

    public TransformationIndices getIndices() {
        return indices;
    }

    public TypeEvaluator getTypeEvaluator() {
        return typeEvaluator;
    }

    // ========== Layer 2: Transformation-Level Context Accessors ==========

    /**
     * Gets the current function/procedure name being transformed.
     *
     * @return Function name or null if transforming a view
     */
    public String getCurrentFunctionName() {
        return currentFunctionName;
    }

    /**
     * Gets the current package name being transformed.
     *
     * @return Package name or null if standalone function/procedure or view
     */
    public String getCurrentPackageName() {
        return currentPackageName;
    }

    /**
     * Checks if currently transforming a package member function/procedure.
     *
     * @return true if currentPackageName is set
     */
    public boolean isInPackageMember() {
        return currentPackageName != null;
    }

    // ========== Package Variable Support ==========

    /**
     * Checks if a reference is a package variable.
     *
     * @param packageName Package name (e.g., "emp_pkg")
     * @param variableName Variable name (e.g., "g_counter")
     * @return true if this is a known package variable
     */
    public boolean isPackageVariable(String packageName, String variableName) {
        if (packageName == null || variableName == null) {
            return false;
        }

        String cacheKey = (currentSchema + "." + packageName).toLowerCase();
        PackageContext ctx = packageContextCache.get(cacheKey);

        if (ctx == null) {
            return false;
        }

        return ctx.getVariables().containsKey(variableName.toLowerCase());
    }

    /**
     * Generates PostgreSQL getter function call for a package variable.
     *
     * @param packageName Package name (e.g., "emp_pkg")
     * @param variableName Variable name (e.g., "g_counter")
     * @return Getter call string (e.g., "hr.emp_pkg__get_g_counter()")
     */
    public String getPackageVariableGetter(String packageName, String variableName) {
        return currentSchema.toLowerCase() + "." +
               packageName.toLowerCase() + "__get_" +
               variableName.toLowerCase() + "()";
    }

    /**
     * Generates PostgreSQL setter function call for a package variable.
     *
     * @param packageName Package name (e.g., "emp_pkg")
     * @param variableName Variable name (e.g., "g_counter")
     * @param value Expression to set (e.g., "100" or "g_counter + 1")
     * @return Setter call string (e.g., "PERFORM hr.emp_pkg__set_g_counter(100)")
     */
    public String getPackageVariableSetter(String packageName, String variableName, String value) {
        return "PERFORM " + currentSchema.toLowerCase() + "." +
               packageName.toLowerCase() + "__set_" +
               variableName.toLowerCase() + "(" + value + ")";
    }

    /**
     * Gets the package context for a given package name.
     *
     * @param packageName Package name (e.g., "emp_pkg")
     * @return PackageContext or null if not found
     */
    public PackageContext getPackageContext(String packageName) {
        if (packageName == null) {
            return null;
        }
        String cacheKey = (currentSchema + "." + packageName).toLowerCase();
        return packageContextCache.get(cacheKey);
    }

    // ========== Inline Type Support (FUTURE - stubs for now) ==========

    /**
     * Registers an inline type definition (function-local type).
     *
     * <p>FUTURE - infrastructure ready, implementation deferred.</p>
     *
     * <p>Example use case: TYPE salary_breakdown_t IS RECORD (...) defined inside a function.</p>
     *
     * @param typeName Type name (e.g., "salary_breakdown_t")
     * @param definition Inline type definition
     */
    public void registerInlineType(String typeName, InlineTypeDefinition definition) {
        if (typeName != null && definition != null) {
            inlineTypes.put(typeName.toLowerCase(), definition);
        }
    }

    /**
     * Gets an inline type definition (function-local type).
     *
     * <p>FUTURE - infrastructure ready, implementation deferred.</p>
     *
     * @param typeName Type name to look up
     * @return InlineTypeDefinition or null if not found
     */
    public InlineTypeDefinition getInlineType(String typeName) {
        if (typeName == null) {
            return null;
        }
        return inlineTypes.get(typeName.toLowerCase());
    }

    // ========== Synonym Resolution ==========

    /**
     * Resolves a name that might be a synonym.
     * Follows Oracle resolution rules: current schema → PUBLIC fallback.
     *
     * @param name Name to resolve (table, view, etc.)
     * @return Qualified target name "schema.table" or null if not a synonym
     */
    public String resolveSynonym(String name) {
        return indices.resolveSynonym(currentSchema, name);
    }

    // ========== Type Metadata ==========

    /**
     * Gets type information for a column.
     *
     * @param qualifiedTable Table name in "schema.table" format
     * @param columnName Column name
     * @return ColumnTypeInfo or null if not found
     */
    public TransformationIndices.ColumnTypeInfo getColumnType(String qualifiedTable, String columnName) {
        return indices.getColumnType(qualifiedTable, columnName);
    }

    /**
     * Checks if a type has a specific method (for disambiguation).
     *
     * @param qualifiedType Type name in "schema.typename" format
     * @param methodName Method name
     * @return true if the type has this method
     */
    public boolean hasTypeMethod(String qualifiedType, String methodName) {
        return indices.hasTypeMethod(qualifiedType, methodName);
    }

    /**
     * Checks if a qualified name is a package function (for disambiguation).
     *
     * @param qualifiedName Function name in "schema.package.function" format
     * @return true if this is a known package function
     */
    public boolean isPackageFunction(String qualifiedName) {
        return indices.isPackageFunction(qualifiedName);
    }

    // ========== Query-Local State (Table Aliases) ==========

    /**
     * Registers a table alias for the current query.
     *
     * @param alias Alias name
     * @param tableName Actual table name
     */
    public void registerAlias(String alias, String tableName) {
        if (alias != null && tableName != null) {
            tableAliases.put(alias.toLowerCase(), tableName.toLowerCase());
            // Update type evaluator with new aliases
            if (typeEvaluator instanceof me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator) {
                ((me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator) typeEvaluator)
                    .setTableAliases(tableAliases);
            }
        }
    }

    /**
     * Resolves a table alias to the actual table name.
     *
     * @param alias Alias to resolve
     * @return Table name or null if not found
     */
    public String resolveAlias(String alias) {
        if (alias == null) {
            return null;
        }
        return tableAliases.get(alias.toLowerCase());
    }

    /**
     * Clears all registered aliases (for starting a new query).
     */
    public void clearAliases() {
        tableAliases.clear();
        // Update type evaluator with cleared aliases
        if (typeEvaluator instanceof me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator) {
            ((me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator) typeEvaluator)
                .setTableAliases(tableAliases);
        }
    }

    // ========== Query-Local State (CTE Names) ==========

    /**
     * Registers a CTE (Common Table Expression) name from WITH clause.
     *
     * <p>CTE names should NOT be schema-qualified since they are temporary
     * named result sets that don't belong to any schema.
     *
     * @param cteName CTE name from WITH clause
     */
    public void registerCTE(String cteName) {
        if (cteName != null) {
            cteNames.add(cteName.toLowerCase());
        }
    }

    /**
     * Checks if a name is a registered CTE.
     *
     * @param name Name to check
     * @return true if this is a CTE name
     */
    public boolean isCTE(String name) {
        if (name == null) {
            return false;
        }
        return cteNames.contains(name.toLowerCase());
    }

    /**
     * Clears all registered CTEs (for starting a new query).
     */
    public void clearCTEs() {
        cteNames.clear();
    }

    // ========== Type Conversion (Future) ==========

    /**
     * Converts Oracle type to PostgreSQL equivalent.
     * TODO: Implement full type conversion in future phase.
     *
     * @param oracleType Oracle type name
     * @return PostgreSQL type name
     */
    public String convertType(String oracleType) {
        // TODO: Implement type conversion
        // For now, pass through as-is
        return oracleType;
    }
}
