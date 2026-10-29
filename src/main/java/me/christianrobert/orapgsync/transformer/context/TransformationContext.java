package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(TransformationContext.class);

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
     * Current object type name (e.g., "address_type") when transforming a type method.
     *
     * <p>Null for standalone routines, package members and views. A type-method source segment
     * carries only the method, never its enclosing {@code TYPE BODY}, so the owning type cannot be
     * read off the parse tree the way a package member's name can — the caller supplies it from
     * {@code TypeMethodMetadata}. It is what {@code t__m} naming and the {@code self t} parameter
     * are built from.
     */
    private final String currentTypeName;

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

    /**
     * Qualified relations named in a FROM clause, aliased or not (e.g. "hr.emp").
     *
     * <p>{@link #tableAliases} cannot answer "which relations are in scope?", because a table
     * written without an alias registers nothing there. This set can, and that is what lets
     * {@link #isColumnInScope} give a real column precedence over a same-named parameterless
     * function when an identifier appears bare.
     */
    private final Set<String> fromRelations;

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
        this(currentSchema, indices, typeEvaluator, null, null, null);
    }

    /**
     * Creates context with full transformation-level context (for PL/SQL functions/procedures).
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
     */
    public TransformationContext(String currentSchema,
                                 TransformationIndices indices,
                                 TypeEvaluator typeEvaluator,
                                 Map<String, PackageContext> packageContextCache,
                                 String functionName,
                                 String packageName) {
        this(currentSchema, indices, typeEvaluator, packageContextCache, functionName, packageName, null);
    }

    /**
     * As above, plus the enclosing object type for type-method transformation.
     *
     * @param typeName Owning object type name (null unless transforming a type method)
     */
    public TransformationContext(String currentSchema,
                                 TransformationIndices indices,
                                 TypeEvaluator typeEvaluator,
                                 Map<String, PackageContext> packageContextCache,
                                 String functionName,
                                 String packageName,
                                 String typeName) {
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
        this.currentTypeName = typeName;
        this.packageContextCache = packageContextCache != null ? packageContextCache : new HashMap<>();
        this.inlineTypes = new HashMap<>();  // FUTURE - ready for inline type support

        // Layer 3: Query-level context (mutable during transformation)
        this.tableAliases = new HashMap<>();
        this.cteNames = new HashSet<>();
        this.fromRelations = new HashSet<>();
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
     * Gets the object type whose method is being transformed.
     *
     * @return Type name, or null when not transforming a type method
     */
    public String getCurrentTypeName() {
        return currentTypeName;
    }

    /**
     * Checks if currently transforming an object type method.
     */
    public boolean isInTypeMethod() {
        return currentTypeName != null;
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
     * Gets type information for a column of a table or a view — for {@code %TYPE} anchors only.
     *
     * @see TransformationIndices#getRelationColumnType
     */
    public TransformationIndices.ColumnTypeInfo getRelationColumnType(String qualifiedRelation, String columnName) {
        return indices.getRelationColumnType(qualifiedRelation, columnName);
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

    /**
     * Checks if a function exists in a specific package (for package-local call resolution).
     *
     * <p>This method is used to detect unqualified calls to private package functions.
     * When inside a package member, an unqualified call like {@code mini2} should be
     * resolved to {@code schema.package__mini2} if {@code mini2} exists in the current package.</p>
     *
     * <p>Example:
     * <pre>
     * -- Oracle package body
     * PROCEDURE mini2 IS BEGIN ... END;  -- private procedure
     * FUNCTION mini1 RETURN NUMBER IS
     * BEGIN
     *   mini2;  -- unqualified call to private procedure
     * END;
     *
     * -- When transforming mini1, isPackageFunction("hr", "minitest", "mini2") returns true
     * -- So mini2 transforms to: hr.minitest__mini2()
     * </pre>
     *
     * @param schema Schema name (e.g., "hr")
     * @param packageName Package name (e.g., "minitest")
     * @param functionName Function/procedure name (e.g., "mini2")
     * @return true if this function exists in the specified package
     */
    public boolean isPackageFunction(String schema, String packageName, String functionName) {
        if (schema == null || packageName == null || functionName == null) {
            return false;
        }
        String qualifiedName = schema.toLowerCase() + "." + packageName.toLowerCase() + "." + functionName.toLowerCase();
        return indices.isPackageFunction(qualifiedName);
    }

    /**
     * Checks if a qualified name is a standalone (non-package) routine.
     *
     * @param qualifiedName "schema.function" (case-insensitive)
     * @return true if this is a known standalone routine
     */
    public boolean isStandaloneFunction(String qualifiedName) {
        return indices.isStandaloneFunction(qualifiedName);
    }

    /**
     * Checks if a routine can be invoked with no arguments.
     *
     * @param qualifiedName "schema.function" or "schema.package.function" (case-insensitive)
     * @return true if the routine takes no mandatory arguments
     * @see TransformationIndices#isNoArgCallable(String)
     */
    public boolean isNoArgCallable(String qualifiedName) {
        return indices.isNoArgCallable(qualifiedName);
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

            // PUBLIC is not a schema, it is Oracle's public-synonym namespace, and the data
            // dictionary reports it in the owner column exactly where a real schema would go.
            // Taken literally it becomes PostgreSQL's `public` schema, where the type is not and
            // never will be. Strip the qualifier and resolve the bare name as the synonym it is.
            if (normalized.startsWith("public.")) {
                String bareName = normalized.substring("public.".length());
                String publicTarget = resolveSynonym(bareName);
                if (publicTarget != null && indices.isObjectType(publicTarget.toLowerCase())) {
                    return publicTarget.toLowerCase();
                }
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
     * Checks if a package name is an Oracle compatibility package.
     *
     * <p>These packages are provided in the oracle_compat schema and should not be
     * prefixed with the current schema during transformation. They include:</p>
     * <ul>
     *   <li>HTP - Hypertext Procedures (web output buffer)</li>
     *   <li>HTF - Hypertext Functions (return HTML strings)</li>
     *   <li>OWA - Oracle Web Agent (request initialization)</li>
     *   <li>OWA_UTIL - OWA Utilities (CGI environment, redirects)</li>
     *   <li>OWA_COOKIE - Cookie handling</li>
     *   <li>DBMS_OUTPUT - Debug output</li>
     *   <li>DBMS_UTILITY - Utility functions</li>
     *   <li>DBMS_LOB - Large object handling</li>
     *   <li>UTL_FILE - File I/O</li>
     * </ul>
     *
     * @param packageName Package name to check (case-insensitive)
     * @return true if it's an Oracle compatibility package
     */
    public boolean isOracleCompatibilityPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        String upper = packageName.toUpperCase();
        return switch (upper) {
            case "HTP", "HTF", "OWA", "OWA_UTIL", "OWA_COOKIE",
                 "DBMS_OUTPUT", "DBMS_UTILITY", "DBMS_LOB", "UTL_FILE" -> true;
            default -> false;
        };
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
     * Records a relation named in a FROM clause, whether or not it carries an alias.
     *
     * @param qualifiedTable Resolved relation name in "schema.table" form
     */
    public void registerFromRelation(String qualifiedTable) {
        if (qualifiedTable != null && !qualifiedTable.isEmpty()) {
            fromRelations.add(qualifiedTable.toLowerCase());
        }
    }

    /**
     * Reports whether an unqualified name is a column of some relation currently in scope.
     *
     * <p>Consulted before rewriting a bare identifier into a parameterless function call, so a
     * genuine column always wins over a same-named routine. Unknown names answer {@code false} —
     * derived tables and CTEs contribute no column metadata — which is why the routine lookup
     * must itself be a positive metadata match rather than a fallback.
     *
     * @param columnName Unqualified column name (case-insensitive)
     * @return true if any relation in scope has a column of that name
     */
    public boolean isColumnInScope(String columnName) {
        if (columnName == null) {
            return false;
        }

        for (String relation : fromRelations) {
            if (indices.getColumnType(relation, columnName) != null) {
                return true;
            }
        }

        for (String relation : tableAliases.values()) {
            if (indices.getColumnType(relation, columnName) != null) {
                return true;
            }
        }

        return false;
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

    // ========== Package-Qualified Type Resolution ==========

    /**
     * Result of package-qualified type resolution.
     *
     * <p>Either contains an inline type definition (for RECORD, TABLE OF, etc.)
     * or a PostgreSQL type string (for composite types or defaulting to jsonb).</p>
     *
     * <p>Usage:</p>
     * <ul>
     *   <li>Variable declarations: Use both postgresType and inlineType (for initialization)</li>
     *   <li>Parameters and return types: Use only postgresType</li>
     * </ul>
     */
    /**
     * @param fromMetadata whether the answer came from a package this context actually has, as
     *        opposed to the {@code jsonb} fallback taken when the package is unknown. The visitor
     *        layer uses the fallback because it is mid-expression and must emit something; a
     *        caller that can refuse should tell the two apart, because a guessed {@code jsonb} in
     *        a signature is the "stub and implementation disagree" failure waiting to happen.
     * @param aliasOf set when the type is a {@code SUBTYPE} whose base leaves the declaring package
     *        and is not a built-in or object type — another package's type, or a {@code %TYPE}
     *        anchor. The base is handed back as written, for
     *        {@link me.christianrobert.orapgsync.transformer.util.TypeResolver} to resolve exactly as
     *        it would resolve that text anywhere else; {@code postgresType} is then null.
     */
    public record PackageTypeResolution(String postgresType, InlineTypeDefinition inlineType,
                                        boolean fromMetadata, String aliasOf) {
        public static PackageTypeResolution ofType(String postgresType) {
            return new PackageTypeResolution(postgresType, null, true, null);
        }

        public static PackageTypeResolution ofInlineType(InlineTypeDefinition inlineType) {
            return new PackageTypeResolution("jsonb", inlineType, true, null);
        }

        /** The {@code jsonb} taken when nothing could place the package type. */
        public static PackageTypeResolution ofUnknownPackageType() {
            return new PackageTypeResolution("jsonb", null, false, null);
        }

        /** A subtype whose base the caller must resolve; see {@code aliasOf}. */
        public static PackageTypeResolution ofAlias(String baseOracleType) {
            return new PackageTypeResolution(null, null, true, baseOracleType);
        }
    }

    /**
     * Resolves a <em>bare</em> type name against the package being transformed: its spec and its
     * body declarations.
     *
     * <p>That is the first scope Oracle searches for a type named inside a package, ahead of schema
     * objects and {@code STANDARD}, so a package may declare {@code Label} or {@code SlotCache} and
     * use it unqualified everywhere. Nothing looked there: a bare name went straight to the object
     * type and built-in lookups, missed, and was refused in a signature and passed through bare in a
     * body — the largest cause of refused package contexts, and of failed stubs for package-private
     * members, until ROADMAP P1.5.
     *
     * <p>Two sources, which must agree: the live {@link PackageContext} the implementation job builds
     * from the spec and the reduced body, and the step-22 index, which is all the stub job has. Both
     * carry body declarations, and only this lookup — made from inside the package — sees them.
     *
     * @param typeName the type as written
     * @return the resolution, or null if this is not a bare name, no package is being transformed, or
     *         the package declares no such type
     */
    public PackageTypeResolution resolveEnclosingPackageType(String typeName) {
        if (currentPackageName == null || typeName == null) {
            return null;
        }
        String name = typeName.trim();
        if (name.isEmpty() || name.contains(".") || name.contains("%") || name.contains("(")
                || name.contains("\"")) {
            return null;
        }

        PackageContext pkgCtx = getPackageContext(currentPackageName);
        PackageTypeMetadata declared = indices.getPackageTypes(
                (currentSchema + "." + currentPackageName).toLowerCase());

        if (pkgCtx != null && pkgCtx.hasType(name)) {
            return PackageTypeResolution.ofInlineType(pkgCtx.getType(name));
        }
        if (pkgCtx != null && pkgCtx.hasSubtype(name)) {
            return resolveSubtypeAlias(pkgCtx.getSubtypeBaseType(name), pkgCtx, declared, true);
        }
        if (declared != null) {
            if (declared.declaresStructuredType(name, true)) {
                return PackageTypeResolution.ofType("jsonb");
            }
            String baseType = declared.findSubtype(name, true);
            if (baseType != null) {
                return resolveSubtypeAlias(baseType, pkgCtx, declared, true);
            }
        }
        return null;
    }

    /**
     * Resolves a package-qualified type reference like "packageName.typeName".
     *
     * <p>This method distinguishes between:</p>
     * <ul>
     *   <li>Schema-qualified object types (e.g., "HR.ADDRESS_TYPE") → returns null (use TypeConverter)</li>
     *   <li>Package-qualified types (e.g., "packageName.typeName") → resolves via PackageContext or defaults to jsonb</li>
     *   <li>Types Oracle's own packages declare (e.g., "OWA.VC_ARR") → jsonb, from {@link OracleBuiltinPackageTypes}</li>
     * </ul>
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Check if type contains "." (dotted notation)</li>
     *   <li>Check if it's a known object type (schema.type) → return null (use normal path)</li>
     *   <li>Check if it's an Oracle compatibility package → its documented declaration, or unknown</li>
     *   <li>Resolve package name via synonym lookup to find actual schema</li>
     *   <li>Try to resolve from PackageContext (inline type like RECORD) → jsonb with InlineTypeDefinition</li>
     *   <li>Package type not found → default to jsonb</li>
     * </ol>
     *
     * <p>Usage:</p>
     * <pre>
     * // In variable declaration, parameter, or return type handling:
     * PackageTypeResolution resolution = context.resolvePackageQualifiedType(oracleType);
     * if (resolution != null) {
     *     // Use resolution.postgresType() for the type
     *     // Use resolution.inlineType() for initialization (variables only)
     * } else {
     *     // Not a package type - use TypeConverter.toPostgre()
     * }
     * </pre>
     *
     * @param oracleType Oracle type specification (e.g., "packageName.typeName")
     * @return PackageTypeResolution or null if not a package-qualified type
     */
    public PackageTypeResolution resolvePackageQualifiedType(String oracleType) {
        // Only applies to dotted types (package.type syntax)
        if (oracleType == null || !oracleType.contains(".")) {
            return null;
        }

        // Skip if contains % (e.g., table.column%TYPE) - handled separately
        if (oracleType.toUpperCase().contains("%")) {
            return null;
        }

        String[] parts = oracleType.split("\\.", 2);
        if (parts.length != 2) {
            return null;
        }

        String firstPart = parts[0].trim();
        String secondPart = parts[1].trim();

        // STEP 1: Check if this is a schema-qualified object type (e.g., "HR.ADDRESS_TYPE")
        // Object types are defined at schema level, not inside packages
        String qualifiedAsObjectType = qualifyTypeName(oracleType);
        if (isObjectType(qualifiedAsObjectType)) {
            // It's an object type - return null to let normal path handle it
            // (TypeConverter will handle schema.type correctly)
            return null;
        }

        // Also check if firstPart.secondPart could be schema.objecttype
        String potentialObjectType = firstPart.toLowerCase() + "." + secondPart.toLowerCase();
        if (isObjectType(potentialObjectType)) {
            // It's a schema-qualified object type - let TypeConverter handle it
            return null;
        }

        // STEP 2: Not an object type - treat as package.type
        String packageName = firstPart;
        String typeName = secondPart;

        // STEP 3: A type one of Oracle's own packages declares. The compatibility layer installs
        // functions only, so there is no oracle_compat.pkg__type to name; these resolve like any
        // other package type, from Oracle's documented declarations. A type not listed there is
        // as undeclared as any other: a guess mid-expression, refused in a signature.
        if (isOracleCompatibilityPackage(packageName)) {
            PackageTypeResolution builtin = OracleBuiltinPackageTypes.resolve(packageName, typeName);
            return builtin != null ? builtin : PackageTypeResolution.ofUnknownPackageType();
        }

        // STEP 4: Resolve package name via synonym lookup to find actual schema
        // This handles cases like: functionName → packageName.functionName
        String resolvedSchema = currentSchema.toLowerCase();
        String resolvedPackageName = packageName.toLowerCase();

        String synonymTarget = resolveSynonym(packageName);
        if (synonymTarget != null) {
            // Synonym resolved to "schema.package" or just "package"
            String[] synonymParts = synonymTarget.toLowerCase().split("\\.");
            if (synonymParts.length == 2) {
                // "schema.package" format
                resolvedSchema = synonymParts[0];
                resolvedPackageName = synonymParts[1];
            } else if (synonymParts.length == 1) {
                // Just "package" - use current schema
                resolvedPackageName = synonymParts[0];
            }
        }

        // STEP 5: Try to resolve from PackageContext (inline type like RECORD, TABLE OF)
        // Use resolved package name for lookup
        PackageContext pkgCtx = getPackageContext(resolvedPackageName);
        if (pkgCtx != null && pkgCtx.hasType(typeName)) {
            // Found as inline type - return jsonb with the inline type definition
            InlineTypeDefinition inlineType = pkgCtx.getType(typeName);
            return PackageTypeResolution.ofInlineType(inlineType);
        }

        // STEP 5b: A SUBTYPE is an alias, not a structure. `SUBTYPE string IS VARCHAR2(4000)`
        // means the parameter is text, and answering jsonb below would be a different type with a
        // different set of operators.
        if (pkgCtx != null && pkgCtx.hasSubtype(typeName)) {
            return resolveSubtypeAlias(pkgCtx.getSubtypeBaseType(typeName), pkgCtx, null, false);
        }

        // STEP 5c: The enclosing PackageContext only ever holds the package currently being
        // transformed, so everything above answers for one package out of thousands. The index
        // answers for all of them, from the spec types extracted at step 22 — which is what makes
        // `otherPkg.rec_t` resolve at all in the stub job, and makes it resolve the *same way*
        // here regardless of which packages happen to have been processed first.
        // Body declarations are private: visible to `ownpkg.t` written inside the package, never to
        // another unit.
        PackageTypeMetadata declared = indices.getPackageTypes(resolvedSchema + "." + resolvedPackageName);
        boolean fromInside = currentPackageName != null
                && resolvedSchema.equals(currentSchema.toLowerCase())
                && resolvedPackageName.equals(currentPackageName.toLowerCase());
        if (declared != null) {
            if (declared.declaresStructuredType(typeName, fromInside)) {
                // A structure, and every one of them is jsonb. The index deliberately carries no
                // field list: a signature needs none, and a declaration site of *another* package's
                // record keeps the initializer-less jsonb it already had.
                return PackageTypeResolution.ofType("jsonb");
            }
            String baseType = declared.findSubtype(typeName, fromInside);
            if (baseType != null) {
                return resolveSubtypeAlias(baseType, pkgCtx, declared, fromInside);
            }
        }

        // STEP 6: Nothing declares this type. Answer jsonb, but mark it as the guess it is —
        // acceptable mid-expression, refused in a signature. See PackageTypeResolution.
        log.debug("Package type '{}.{}' is not declared by any extracted package spec; "
                + "defaulting to jsonb", packageName, typeName);
        return PackageTypeResolution.ofUnknownPackageType();
    }

    /**
     * Follows a {@code SUBTYPE} alias to something emittable.
     *
     * <p>Chased one hop at a time within the package that declared it. The hop limit guards a spec
     * that aliases in a cycle — illegal in Oracle, but this runs against whatever the database holds.
     * A base that leaves the package and is neither a built-in nor an object type — another
     * package's type, a {@code %TYPE} anchor — is returned as {@link PackageTypeResolution#ofAlias}
     * for {@code TypeResolver} to resolve, rather than answered here with a second, partial copy of
     * its chain.
     *
     * @param pkgCtx  the live context, if the subtype came from one; may be null
     * @param declared the indexed declarations, if it came from those; may be null
     * @param includeBody whether the package's body declarations are visible to the lookup
     */
    private PackageTypeResolution resolveSubtypeAlias(String baseType, PackageContext pkgCtx,
                                                      PackageTypeMetadata declared,
                                                      boolean includeBody) {
        for (int hop = 0; baseType != null && hop < 16; hop++) {
            String next = pkgCtx != null && pkgCtx.hasSubtype(baseType)
                    ? pkgCtx.getSubtypeBaseType(baseType)
                    : (declared != null ? declared.findSubtype(baseType, includeBody) : null);
            if (next == null) {
                break;
            }
            baseType = next;
        }

        if (baseType == null) {
            return PackageTypeResolution.ofUnknownPackageType();
        }

        // The alias may name a structure or an object type rather than a built-in, so ask rather
        // than assume a scalar.
        if (pkgCtx != null && pkgCtx.hasType(baseType)) {
            return PackageTypeResolution.ofInlineType(pkgCtx.getType(baseType));
        }
        if (declared != null && declared.declaresStructuredType(baseType, includeBody)) {
            return PackageTypeResolution.ofType("jsonb");
        }
        String qualifiedBase = qualifyTypeName(baseType);
        if (isObjectType(qualifiedBase)) {
            return PackageTypeResolution.ofType(qualifiedBase);
        }

        String builtIn = TypeConverter.toPostgreOrNull(baseType);
        return builtIn != null
                ? PackageTypeResolution.ofType(builtIn)
                : PackageTypeResolution.ofAlias(baseType);
    }
}
