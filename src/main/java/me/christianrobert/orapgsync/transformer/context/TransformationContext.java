package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;

import java.util.ArrayDeque;
import java.util.Deque;
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
 *   <li>{@link #variableScopeStack} - Variable scope stack for local variable tracking</li>
 * </ul>
 *
 * <p><strong>Lifecycle and Memory Safety:</strong></p>
 * <ul>
 *   <li><strong>Ephemeral instances:</strong> Each transformation creates a fresh TransformationContext</li>
 *   <li><strong>No caching:</strong> Context is never stored in StateService or static fields</li>
 *   <li><strong>Automatic cleanup:</strong> Garbage collected when transformation completes</li>
 *   <li><strong>Thread-safe by design:</strong> No shared state between transformations</li>
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
    public TransformationContext(String hr, TransformationIndices emptyIndices, SimpleTypeEvaluator typeEvaluator, Map<String, PackageContext> packageContextCache, String testProc, String empPkg) {
        this(hr, emptyIndices, typeEvaluator, packageContextCache, testProc, empPkg, new HashMap<>(), null);
    }

    // ========== Variable Scope Tracking (Inner Classes) ==========

    /**
     * Represents a single scope level for local variables.
     *
     * <p>Scopes are organized in a stack: function scope → nested block → loop, etc.
     * Each scope has its own set of variable definitions.</p>
     *
     * <p><strong>Example scope hierarchy:</strong></p>
     * <pre>
     * Function scope: v_total, v_count
     *   ↳ IF block scope: v_temp
     *     ↳ Loop scope: i (loop variable)
     * </pre>
     */
    public static class VariableScope {
        private final Map<String, VariableDefinition> variables = new HashMap<>();
        private final String scopeName;

        public VariableScope(String scopeName) {
            this.scopeName = scopeName;
        }

        public void addVariable(String name, VariableDefinition definition) {
            variables.put(name.toLowerCase(), definition);
        }

        public VariableDefinition getVariable(String name) {
            return variables.get(name.toLowerCase());
        }

        public boolean hasVariable(String name) {
            return variables.containsKey(name.toLowerCase());
        }

        public String getScopeName() {
            return scopeName;
        }

        public Map<String, VariableDefinition> getVariables() {
            return variables;
        }

        @Override
        public String toString() {
            return "VariableScope{" +
                    "scopeName='" + scopeName + '\'' +
                    ", variables=" + variables.size() +
                    '}';
        }
    }

    /**
     * Metadata about a local variable declaration.
     *
     * <p>Stores type information, inline type references, and other metadata
     * needed for accurate transformation of variable references.</p>
     *
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>Simple variable: {@code v_count NUMBER} → postgresType="numeric", inlineType=null</li>
     *   <li>RECORD variable: {@code v_emp employee_rec} → inlineType=InlineTypeDefinition(RECORD)</li>
     *   <li>Collection: {@code v_nums num_array_t} → inlineType=InlineTypeDefinition(TABLE OF)</li>
     * </ul>
     */
    public static class VariableDefinition {
        private final String variableName;
        private final String oracleType;
        private final String postgresType;
        private final boolean isConstant;
        private final String defaultValue;
        private final InlineTypeDefinition inlineType;  // null if not an inline type

        public VariableDefinition(String variableName,
                                   String oracleType,
                                   String postgresType,
                                   boolean isConstant,
                                   String defaultValue,
                                   InlineTypeDefinition inlineType) {
            this.variableName = variableName;
            this.oracleType = oracleType;
            this.postgresType = postgresType;
            this.isConstant = isConstant;
            this.defaultValue = defaultValue;
            this.inlineType = inlineType;
        }

        public String getVariableName() {
            return variableName;
        }

        public String getOracleType() {
            return oracleType;
        }

        public String getPostgresType() {
            return postgresType;
        }

        public boolean isConstant() {
            return isConstant;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public InlineTypeDefinition getInlineType() {
            return inlineType;
        }

        public boolean hasInlineType() {
            return inlineType != null;
        }

        /**
         * Checks if this variable is a RECORD type (inline or global).
         */
        public boolean isRecord() {
            return inlineType != null && inlineType.getCategory() == TypeCategory.RECORD;
        }

        /**
         * Checks if this variable is a collection (TABLE OF, VARRAY, INDEX BY).
         */
        public boolean isCollection() {
            if (inlineType == null) {
                return false;
            }
            TypeCategory category = inlineType.getCategory();
            return category == TypeCategory.TABLE_OF ||
                   category == TypeCategory.VARRAY ||
                   category == TypeCategory.INDEX_BY;
        }

        @Override
        public String toString() {
            return "VariableDefinition{" +
                    "variableName='" + variableName + '\'' +
                    ", oracleType='" + oracleType + '\'' +
                    ", postgresType='" + postgresType + '\'' +
                    ", isConstant=" + isConstant +
                    ", inlineType=" + (inlineType != null ? inlineType.getCategory() : "null") +
                    '}';
        }
    }

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

    /**
     * View column types for view transformations (column name → PostgreSQL type).
     * Used to cast SELECT list expressions to match stub column types.
     * Null for non-view transformations (functions, procedures, ad-hoc SQL).
     *
     * <p><strong>Purpose:</strong> Ensures CREATE OR REPLACE VIEW succeeds by matching stub types exactly.</p>
     * <p><strong>Example:</strong> COUNT(*) returns bigint, but stub expects numeric → cast to numeric.</p>
     */
    private final Map<String, String> viewColumnTypes;

    /**
     * Ordered list of view column names (for position-based type casting).
     * Used when SELECT list elements have no explicit alias - match by position.
     * Null for non-view transformations.
     *
     * <p><strong>Example:</strong></p>
     * <pre>
     * Stub: CREATE VIEW v AS SELECT NULL::numeric AS col1, NULL::text AS col2
     * Oracle SQL: SELECT 1, 'hello' FROM dual
     * Position 0 → col1 (numeric), Position 1 → col2 (text)
     * </pre>
     */
    private final java.util.List<String> viewColumnNamesOrdered;

    /**
     * Current position in SELECT list (0-based index).
     * Set by VisitSelectedList before visiting each element, read by VisitSelectListElement.
     * Used for position-based column type matching when no explicit alias exists.
     *
     * <p><strong>Lifecycle:</strong> Ephemeral state, only valid during SELECT list traversal.</p>
     */
    private int currentSelectListPosition = -1;  // -1 = not in SELECT list

    // ========== Layer 3: Query-Level Context ==========

    private final Map<String, String> tableAliases;  // alias → table name (per query)
    private final Set<String> cteNames;  // CTE names (from WITH clause)

    // ========== Variable Scope Stack (Implemented) ==========

    /**
     * Stack of variable scopes for tracking local variables during transformation.
     *
     * <p><strong>Scope hierarchy (bottom to top):</strong></p>
     * <ol>
     *   <li>Function scope (parameters + DECLARE variables)</li>
     *   <li>Nested block scopes (IF, LOOP, anonymous DECLARE blocks)</li>
     *   <li>Loop scopes (FOR loop variables)</li>
     * </ol>
     *
     * <p><strong>Lifecycle:</strong></p>
     * <ul>
     *   <li>Push function scope when starting function transformation</li>
     *   <li>Push/pop nested scopes as entering/exiting blocks</li>
     *   <li>Lookup searches from top to bottom (innermost to outermost)</li>
     * </ul>
     *
     * <p>See VARIABLE_SCOPE_TRACKING_PLAN.md for full architecture.</p>
     */
    private final Deque<VariableScope> variableScopeStack;

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
        this(currentSchema, indices, typeEvaluator, null, null, null, null, null);
    }

    /**
     * Creates context for view transformation with column type metadata.
     *
     * <p>This constructor is for view transformations that need to cast SELECT list expressions
     * to match stub column types (ensures CREATE OR REPLACE VIEW succeeds).</p>
     *
     * @param currentSchema Schema context for resolution (e.g., "hr")
     * @param indices Pre-built metadata indices for fast lookups
     * @param typeEvaluator Type evaluator for type-aware transformations
     * @param viewColumnTypes Column name → PostgreSQL type mapping (from view stub metadata)
     */
    public TransformationContext(String currentSchema,
                                 TransformationIndices indices,
                                 TypeEvaluator typeEvaluator,
                                 Map<String, String> viewColumnTypes) {
        this(currentSchema, indices, typeEvaluator, null, null, null, viewColumnTypes, null);
    }

    /**
     * Creates context for view transformation with column type metadata and ordered column names.
     *
     * <p>This constructor is for view transformations that need to cast SELECT list expressions
     * to match stub column types, including position-based matching for expressions without explicit aliases.</p>
     *
     * @param currentSchema Schema context for resolution (e.g., "hr")
     * @param indices Pre-built metadata indices for fast lookups
     * @param typeEvaluator Type evaluator for type-aware transformations
     * @param viewColumnTypes Column name → PostgreSQL type mapping (from view stub metadata)
     * @param viewColumnNamesOrdered Ordered list of column names (for position-based matching)
     */
    public TransformationContext(String currentSchema,
                                 TransformationIndices indices,
                                 TypeEvaluator typeEvaluator,
                                 Map<String, String> viewColumnTypes,
                                 java.util.List<String> viewColumnNamesOrdered) {
        this(currentSchema, indices, typeEvaluator, null, null, null, viewColumnTypes, viewColumnNamesOrdered);
    }

    /**
     * Creates context with full transformation-level context (for PL/SQL functions/procedures and views).
     *
     * <p>This is the master constructor that all other constructors delegate to.
     * It accepts all possible context parameters.</p>
     *
     * @param currentSchema Schema context for resolution (e.g., "hr")
     * @param indices Pre-built metadata indices for fast lookups
     * @param typeEvaluator Type evaluator for type-aware transformations
     * @param packageContextCache Package variable contexts (null for standalone/views)
     * @param functionName Current function/procedure name (null for views)
     * @param packageName Current package name (null for standalone/views)
     * @param viewColumnTypes View column types for casting (null for functions/procedures)
     * @param viewColumnNamesOrdered Ordered view column names (null for functions/procedures)
     */
    public TransformationContext(String currentSchema,
                                 TransformationIndices indices,
                                 TypeEvaluator typeEvaluator,
                                 Map<String, PackageContext> packageContextCache,
                                 String functionName,
                                 String packageName,
                                 Map<String, String> viewColumnTypes,
                                 java.util.List<String> viewColumnNamesOrdered) {
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
        this.viewColumnTypes = viewColumnTypes;  // Null for non-view transformations
        this.viewColumnNamesOrdered = viewColumnNamesOrdered;  // Null for non-view transformations

        // Layer 3: Query-level context (mutable during transformation)
        this.tableAliases = new HashMap<>();
        this.cteNames = new HashSet<>();
        this.variableScopeStack = new ArrayDeque<>();
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

    // ========== View Column Type Support ==========

    /**
     * Checks if currently transforming a view with column type metadata.
     *
     * @return true if viewColumnTypes is set (view transformation with metadata)
     */
    public boolean isViewTransformation() {
        return viewColumnTypes != null;
    }

    /**
     * Gets the PostgreSQL type for a view column (case-insensitive lookup).
     *
     * @param columnName Column name (alias in SELECT list)
     * @return PostgreSQL type (e.g., "numeric", "text", "bigint") or null if not found
     */
    public String getViewColumnType(String columnName) {
        if (viewColumnTypes == null || columnName == null) {
            return null;
        }
        // Case-insensitive lookup (Oracle/PostgreSQL identifiers are case-insensitive by default)
        String normalizedName = columnName.toLowerCase();
        return viewColumnTypes.get(normalizedName);
    }

    /**
     * Checks if column type metadata exists for a given column.
     *
     * @param columnName Column name (alias in SELECT list)
     * @return true if column type is known
     */
    public boolean hasViewColumnType(String columnName) {
        return getViewColumnType(columnName) != null;
    }

    /**
     * Sets the current position in the SELECT list (for position-based type casting).
     * Called by VisitSelectedList before visiting each select_list_elements.
     *
     * <p><strong>Lifecycle:</strong> Set before each SELECT list element, reset after SELECT list completes.</p>
     *
     * @param position 0-based index in SELECT list (0 = first column, 1 = second, etc.)
     */
    public void setCurrentSelectListPosition(int position) {
        this.currentSelectListPosition = position;
    }

    /**
     * Gets the current position in the SELECT list.
     * Used by VisitSelectListElement for position-based column matching.
     *
     * @return 0-based index in SELECT list, or -1 if not in SELECT list
     */
    public int getCurrentSelectListPosition() {
        return currentSelectListPosition;
    }

    /**
     * Gets the view column name at a specific position (for position-based type casting).
     * Used when SELECT expression has no explicit alias - match by position instead.
     *
     * <p><strong>Example:</strong></p>
     * <pre>
     * Stub columns: ["col1", "col2", "col3"]
     * Oracle SQL: SELECT 1, 'hello', SYSDATE FROM dual
     * Position 0 → "col1", Position 1 → "col2", Position 2 → "col3"
     * </pre>
     *
     * @param position 0-based index in column list
     * @return Column name at position, or null if position out of bounds or no ordered list
     */
    public String getViewColumnNameByPosition(int position) {
        if (viewColumnNamesOrdered == null || position < 0 || position >= viewColumnNamesOrdered.size()) {
            return null;
        }
        return viewColumnNamesOrdered.get(position);
    }

    /**
     * Gets the PostgreSQL type for a view column at a specific position.
     * Convenience method that combines position → name → type lookup.
     *
     * @param position 0-based index in column list
     * @return PostgreSQL type (e.g., "numeric", "text", "bigint") or null if not found
     */
    public String getViewColumnTypeByPosition(int position) {
        String columnName = getViewColumnNameByPosition(position);
        if (columnName == null) {
            return null;
        }
        return getViewColumnType(columnName);
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

    /**
     * Resolves an inline type definition using the three-level resolution cascade.
     *
     * <p>Resolution order (INLINE_TYPE_IMPLEMENTATION_PLAN.md:210-226):</p>
     * <ol>
     *   <li><b>Level 1:</b> Block-level (function-local inline types)</li>
     *   <li><b>Level 2:</b> Package-level (from PackageContext)</li>
     *   <li><b>Level 3:</b> Schema-level (from TransformationIndices) - FUTURE</li>
     * </ol>
     *
     * <p><b>Phase 1G Task 4:</b> Implements full type resolution cascade for package-level types.</p>
     *
     * <p><b>Implementation Status:</b> Level 1 + Level 2 complete (Phase 1B + Phase 1G Task 4).
     * Level 3 (schema-level) deferred to future work.</p>
     *
     * @param typeName Type name to resolve (e.g., "salary_range_t")
     * @return InlineTypeDefinition or null if not found at any level
     */
    public InlineTypeDefinition resolveInlineType(String typeName) {
        if (typeName == null) {
            return null;
        }

        // Level 1: Block-level (function-local inline types)
        InlineTypeDefinition blockLevelType = getInlineType(typeName);
        if (blockLevelType != null) {
            return blockLevelType;
        }

        // Level 2: Package-level (from PackageContext)
        if (currentPackageName != null) {
            PackageContext pkgCtx = getPackageContext(currentPackageName);
            if (pkgCtx != null && pkgCtx.hasType(typeName)) {
                return pkgCtx.getType(typeName);
            }
        }

        // Level 3: Schema-level (from TransformationIndices) - FUTURE
        // TODO: Implement when schema-level type support is added

        return null;
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

    // ========== Object Type Field Access Support ==========

    /**
     * Qualifies a type name following Oracle resolution rules.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Check current schema</li>
     *   <li>Check PUBLIC schema (synonyms)</li>
     *   <li>Check SYS schema (system types)</li>
     * </ol>
     *
     * <p>This is critical for synonym support - types may be defined in
     * different schemas and referenced via PUBLIC synonyms.</p>
     *
     * @param typeName Type name (may be unqualified like "LANGY_TYPE" or qualified like "HR.ADDRESS_TYPE")
     * @return Qualified type name (e.g., "hr.address_type") or unqualified if not found
     */
    public String qualifyTypeName(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) {
            return typeName;
        }

        // Normalize to lowercase for case-insensitive matching (indices are lowercase)
        String normalized = typeName.toLowerCase();

        // If already qualified, verify it exists as an object type
        if (normalized.contains(".")) {
            if (indices.isObjectType(normalized)) {
                return normalized;
            }
            // Already qualified but not found - return as-is (will fail later)
            return normalized;
        }

        // STEP 1: Try synonym resolution first
        // Synonyms can point to object types (e.g., PUBLIC.ADDRESS_TYPE → HR.ADDRESS_TYPE)
        String synonymTarget = resolveSynonym(normalized);
        if (synonymTarget != null) {
            // Synonym resolved - check if target is an object type
            String normalizedTarget = synonymTarget.toLowerCase();
            if (indices.isObjectType(normalizedTarget)) {
                return normalizedTarget;
            }
        }

        // STEP 2: Not a synonym - try to qualify by checking if it exists as an object type
        // Check current schema
        String qualified = currentSchema.toLowerCase() + "." + normalized;
        if (indices.isObjectType(qualified)) {
            return qualified;
        }

        // Check PUBLIC schema
        qualified = "public." + normalized;
        if (indices.isObjectType(qualified)) {
            return qualified;
        }

        // Check SYS schema
        qualified = "sys." + normalized;
        if (indices.isObjectType(qualified)) {
            return qualified;
        }

        // Not found - return unqualified (will fail later, but preserves intent)
        return normalized;
    }

    /**
     * Gets the type of a field in an object type.
     *
     * @param qualifiedTypeName Qualified type name (e.g., "hr.address_type")
     * @param fieldName Field name (e.g., "street")
     * @return Field type or null if not found
     */
    public String getFieldType(String qualifiedTypeName, String fieldName) {
        return indices.getFieldType(qualifiedTypeName, fieldName);
    }

    /**
     * Checks if a type name is a known object type.
     *
     * @param qualifiedTypeName Qualified type name (e.g., "hr.address_type")
     * @return true if type exists in metadata
     */
    public boolean isObjectType(String qualifiedTypeName) {
        return indices.isObjectType(qualifiedTypeName);
    }

    /**
     * Gets all fields of an object type.
     *
     * @param qualifiedTypeName Qualified type name (e.g., "hr.address_type")
     * @return Map of field names to types, or empty map if not found
     */
    public Map<String, String> getTypeFields(String qualifiedTypeName) {
        return indices.getTypeFields(qualifiedTypeName);
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

    // ========== Variable Scope Management ==========

    /**
     * Pushes a new variable scope onto the stack.
     *
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Call when entering a function: {@code pushVariableScope("function:calculate_salary")}</li>
     *   <li>Call when entering a nested block: {@code pushVariableScope("if-block")}</li>
     *   <li>Call when entering a loop: {@code pushVariableScope("for-loop")}</li>
     * </ul>
     *
     * <p><strong>Example:</strong></p>
     * <pre>
     * // At function start
     * context.pushVariableScope("function:" + functionName);
     * // Register parameters and DECLARE variables
     * context.registerVariable("p_salary", new VariableDefinition(...));
     * context.registerVariable("v_bonus", new VariableDefinition(...));
     * </pre>
     *
     * @param scopeName Descriptive name for this scope (for debugging)
     */
    public void pushVariableScope(String scopeName) {
        variableScopeStack.push(new VariableScope(scopeName));
    }

    /**
     * Pops the current variable scope from the stack.
     *
     * <p>Call when exiting a block, loop, or function. Ensures proper
     * variable scoping and cleanup.</p>
     *
     * <p><strong>Usage:</strong></p>
     * <pre>
     * try {
     *     context.pushVariableScope("if-block");
     *     // ... transform block contents ...
     * } finally {
     *     context.popVariableScope();  // Always pop in finally
     * }
     * </pre>
     *
     * @throws IllegalStateException if scope stack is empty
     */
    public void popVariableScope() {
        if (variableScopeStack.isEmpty()) {
            throw new IllegalStateException("Cannot pop variable scope: stack is empty");
        }
        variableScopeStack.pop();
    }

    /**
     * Registers a variable in the current scope.
     *
     * <p><strong>Usage:</strong></p>
     * <pre>
     * // Simple variable: v_count NUMBER := 0;
     * context.registerVariable("v_count", new VariableDefinition(
     *     "v_count", "NUMBER", "numeric", false, "0", null
     * ));
     *
     * // RECORD variable: v_emp employee_rec;
     * InlineTypeDefinition recordType = context.resolveInlineType("employee_rec");
     * context.registerVariable("v_emp", new VariableDefinition(
     *     "v_emp", "employee_rec", "jsonb", false, null, recordType
     * ));
     *
     * // Collection: v_nums num_array_t;
     * InlineTypeDefinition arrayType = context.resolveInlineType("num_array_t");
     * context.registerVariable("v_nums", new VariableDefinition(
     *     "v_nums", "num_array_t", "jsonb", false, null, arrayType
     * ));
     * </pre>
     *
     * @param name Variable name
     * @param definition Variable metadata
     * @throws IllegalStateException if no scope is active
     */
    public void registerVariable(String name, VariableDefinition definition) {
        if (variableScopeStack.isEmpty()) {
            throw new IllegalStateException("Cannot register variable: no active scope. Call pushVariableScope() first.");
        }
        variableScopeStack.peek().addVariable(name, definition);
    }

    /**
     * Looks up a variable in the scope chain (innermost to outermost).
     *
     * <p>Searches from the top of the stack (current scope) down to the bottom
     * (function scope). Returns the first match found.</p>
     *
     * <p><strong>Example scope chain lookup:</strong></p>
     * <pre>
     * Stack (top to bottom):
     *   Loop scope: i
     *   IF block scope: v_temp
     *   Function scope: v_count, v_total
     *
     * lookupVariable("i") → Loop scope (found in innermost scope)
     * lookupVariable("v_temp") → IF block scope
     * lookupVariable("v_count") → Function scope
     * lookupVariable("v_missing") → null (not found in any scope)
     * </pre>
     *
     * @param name Variable name to look up
     * @return VariableDefinition or null if not found in any scope
     */
    public VariableDefinition lookupVariable(String name) {
        if (name == null) {
            return null;
        }

        // Search from innermost to outermost scope
        for (VariableScope scope : variableScopeStack) {
            VariableDefinition varDef = scope.getVariable(name);
            if (varDef != null) {
                return varDef;
            }
        }

        return null;
    }

    /**
     * Checks if a name is a local variable in any active scope.
     *
     * <p>This is the key method that replaces heuristic detection.
     * Instead of guessing based on naming patterns, we now have
     * deterministic knowledge of all local variables.</p>
     *
     * <p><strong>Usage in transformation:</strong></p>
     * <pre>
     * // OLD APPROACH (heuristic - WRONG):
     * if (looksLikeVariable(identifier)) {
     *     // Treat as variable - but might be a function!
     * }
     *
     * // NEW APPROACH (deterministic - CORRECT):
     * if (context.isLocalVariable(identifier)) {
     *     VariableDefinition varDef = context.lookupVariable(identifier);
     *     if (varDef.isCollection()) {
     *         // Transform as collection element access
     *     }
     * } else {
     *     // Transform as function call
     * }
     * </pre>
     *
     * @param name Name to check
     * @return true if this is a registered local variable
     */
    public boolean isLocalVariable(String name) {
        return lookupVariable(name) != null;
    }

    /**
     * Gets the current variable scope stack depth.
     *
     * <p>Useful for debugging and validation. Depth 0 means no scopes active.</p>
     *
     * @return Number of active scopes
     */
    public int getVariableScopeDepth() {
        return variableScopeStack.size();
    }
}
