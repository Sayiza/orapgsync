# Package Variable Implementation Plan (Unified Approach)

**Status:** ✅ **COMPLETE** - All phases implemented (920 tests passing, 0 failures)
**Created:** 2025-10-31
**Updated:** 2025-11-02 (Option B architecture enhancement completed)
**Integration:** Extends existing Step 25 (Function/Procedure Implementation)

**Latest Update (2025-11-02):**
- ✅ Enhanced TransformationContext Architecture (Option B) implemented
- ✅ Single source of truth for all transformation context
- ✅ Ready for future inline complex type support
- ✅ All 920 tests passing with zero regressions
- ✅ Package variable transformations now working in production

---

## Overview

Implements Oracle package variable support in PostgreSQL using `set_config`/`current_setting` mechanism for session-level state management. This enables migrated package functions/procedures to maintain state across calls, matching Oracle's package variable semantics.

**Key Architectural Decision: Unified On-Demand Approach**

Package variable support is **integrated directly into the existing function transformation job** (Step 25), not split into separate extraction/creation steps.

**Why unified?**
- ✅ Maintains ANTLR-only-in-transformation pattern (no ANTLR in extraction jobs)
- ✅ Simpler: No StateService properties, no separate jobs, no new REST endpoints
- ✅ More efficient: Package spec parsed once per package, cached in-memory during job
- ✅ Self-contained: All package logic in transformation job
- ✅ Same visitor classes work for standalone and package functions

**Architecture:**
```
PostgresFunctionImplementationJob (existing Step 25)
  ↓
For each function to transform:
  ↓
Is it a package member? (detect "__" in function name)
  ↓ YES
  ↓
Package context already cached? (in-memory, per-job execution)
  ↓ NO
  ↓
Query ALL_SOURCE for package spec
Parse with ANTLR (extract variable declarations)
Generate helper SQL (initialize, getters, setters)
Execute helper creation in PostgreSQL
Cache package context
  ↓
Transform function body:
  - Inject initialization call at start
  - Transform pkg.variable references to getters/setters
  ↓
Execute CREATE OR REPLACE FUNCTION
```

**Strategy:** Progressive implementation starting with simple primitive types and expanding to complex cases.

---

## Design Decisions

### 1. Variable Reference Transformation: Helper Functions ✅

**Decision:** Generate getter/setter helper functions (not direct `set_config`/`current_setting`)

**Pattern:**
```sql
-- Generated helper functions (one-time per package variable)
CREATE FUNCTION hr.pkg__get_g_counter() RETURNS integer AS $$
BEGIN
  RETURN COALESCE(current_setting('hr.pkg.g_counter', true)::integer, 0);
EXCEPTION WHEN OTHERS THEN RETURN 0;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION hr.pkg__set_g_counter(p_value integer) RETURNS void AS $$
BEGIN
  PERFORM set_config('hr.pkg.g_counter', p_value::text, false);
END;
$$ LANGUAGE plpgsql;
```

**Usage in transformed code:**
```sql
-- Oracle: pkg.g_counter := pkg.g_counter + 1;
-- PostgreSQL:
PERFORM hr.pkg__set_g_counter(hr.pkg__get_g_counter() + 1);
```

**Benefits:**
- ✅ Cleaner transformed code (readable)
- ✅ Type safety (casting handled once in helper)
- ✅ NULL/default handling (centralized exception handling)
- ✅ Consistent naming (follows `pkg__function` convention)
- ✅ Easier debugging (can add logging to helpers)
- ✅ Future-proof (can change storage mechanism without retransforming)

---

### 2. Package Initialization: Automatic with Flag Check ✅

**Decision:** Generate initialization function called automatically in every package function

**Pattern:**
```sql
-- Generated initialization function
CREATE FUNCTION hr.pkg__initialize() RETURNS void AS $$
BEGIN
  -- Fast-path: Already initialized?
  IF current_setting('hr.pkg.__initialized', true) = 'true' THEN
    RETURN;
  END IF;

  -- Initialize all package variables with defaults
  PERFORM set_config('hr.pkg.g_counter', '0', false);
  PERFORM set_config('hr.pkg.g_status', 'ACTIVE', false);
  -- ... more variables ...

  -- Mark as initialized (idempotent)
  PERFORM set_config('hr.pkg.__initialized', 'true', false);
END;
$$ LANGUAGE plpgsql;
```

**Call from every package function:**
```sql
CREATE FUNCTION hr.pkg__increment_counter() RETURNS integer AS $$
BEGIN
  -- Ensure package is initialized (idempotent, fast after first call)
  PERFORM hr.pkg__initialize();

  -- Function body
  PERFORM hr.pkg__set_g_counter(hr.pkg__get_g_counter() + 1);
  RETURN hr.pkg__get_g_counter();
END;
$$ LANGUAGE plpgsql;
```

**Benefits:**
- ✅ Oracle-compatible (automatic initialization on first use)
- ✅ Idempotent (safe to call multiple times)
- ✅ Fast (flag check is quick after first initialization)
- ✅ Centralized (all initialization logic in one place)
- ✅ Session-scoped (matches Oracle behavior)

---

### 3. Variable Scope: Session-Level ✅

**Decision:** Use `set_config(key, value, false)` for session-level scope

**Rationale:**
- Oracle package variables have **session-level** state by default
- `is_local = false` → persists across transactions
- `is_local = true` → transaction-local (reset after commit/rollback)
- Session-level matches Oracle semantics

**Config keys:**
- Variables: `{schema}.{package}.{variable_name}` (e.g., `hr.emp_pkg.g_counter`)
- Init flag: `{schema}.{package}.__initialized` (e.g., `hr.emp_pkg.__initialized`)

---

### 4. Package Context Management: On-Demand with Caching ✅

**Decision:** Parse package specs on-demand during transformation, cache in-memory for job duration

**Architecture:**

Package context is **ephemeral** - exists only during transformation job execution, not stored in StateService.

**When first package function is encountered:**
1. Query `ALL_SOURCE` for package spec
2. Parse with ANTLR to extract variable declarations
3. Generate helper function SQL (initialize + getters + setters)
4. Execute helper creation SQL in PostgreSQL
5. Cache package context in job-local map

**When subsequent functions from same package are encountered:**
1. Check cache - context already exists
2. Use cached context for transformation
3. No re-parsing, no re-generation

**Benefits:**
- ✅ Maintains pattern: ANTLR only in transformation jobs (not extraction jobs)
- ✅ No StateService pollution with transformation-time context
- ✅ Package spec parsed once per package per job execution
- ✅ Self-contained: All package logic lives in transformation layer
- ✅ Simpler: No separate extraction/creation jobs needed

**Comparison to existing patterns:**

| Feature | Metadata Extraction | Package Variables |
|---------|---------------------|-------------------|
| **Function Signatures** | Pre-extracted from ALL_ARGUMENTS | Stubs already created |
| **Function Bodies** | Parsed on-demand from ALL_SOURCE | ✅ Same approach |
| **Loop RECORD Variables** | Parsed on-demand in FOR loops | ✅ Same approach |
| **Package Variables** | ~~Pre-extracted~~ ❌ | Parsed on-demand ✅ |

Package variables are like loop RECORD variables - discovered during parsing, not pre-extracted as metadata.

---

### 5. Variable Reference Detection: Context Flag Approach ✅

**Problem:** Distinguish between getter and setter contexts

```sql
-- Oracle
pkg.g_counter := pkg.g_counter + 1;
--   ^^^^^^^^ SETTER (LHS)    ^^^^^^^^ GETTER (RHS)
```

**Solution:** Boolean flag in `PostgresCodeBuilder` to coordinate visitor behavior

#### Architecture

**Flag in PostgresCodeBuilder:**
```java
private boolean isInAssignmentTarget = false;

public void setInAssignmentTarget(boolean value) {
    this.isInAssignmentTarget = value;
}

public boolean isInAssignmentTarget() {
    return this.isInAssignmentTarget;
}
```

**Modified VisitAssignment_statement:**
```java
public static String v(PlSqlParser.Assignment_statementContext ctx, PostgresCodeBuilder b) {
    // STEP 1: Parse LHS with flag protection (prevents getter transformation)
    b.setInAssignmentTarget(true);
    String leftSide;
    if (ctx.general_element() != null) {
        leftSide = b.visit(ctx.general_element());
    } else if (ctx.bind_variable() != null) {
        leftSide = b.visit(ctx.bind_variable());
    } else {
        throw new IllegalStateException("Assignment has no LHS");
    }
    b.setInAssignmentTarget(false);

    // STEP 2: Check if LHS is a package variable (format: "pkg.varname")
    PackageVariableReference pkgVar = b.parsePackageVariableReference(leftSide);

    if (pkgVar != null) {
        // Transform to setter call
        String rightSide = b.visit(ctx.expression()); // RHS can have getters!
        return "PERFORM " + pkgVar.getSetterCall(rightSide);
    }

    // STEP 3: Normal assignment (not a package variable)
    return leftSide + " := " + b.visit(ctx.expression());
}
```

**Modified VisitGeneralElement (dot navigation section):**
```java
} else {
    // COLUMN REFERENCE or PACKAGE VARIABLE

    // Check if this is a package variable reference (NEW)
    if (parts.size() == 2 && b.isPackageVariable(parts)) {
        String packageName = parts.get(0).id_expression().getText();
        String variableName = parts.get(1).id_expression().getText();

        if (b.isInAssignmentTarget()) {
            // Return raw reference (assignment statement will transform to setter)
            return packageName + "." + variableName;
        } else {
            // Transform to getter
            return b.transformToPackageVariableGetter(packageName, variableName);
        }
    }

    // Not a package variable - regular column reference
    return handleQualifiedColumn(parts, b);
}
```

#### Transformation Flow Example

**Oracle:**
```sql
pkg.g_total := pkg.g_counter + pkg.g_increment;
```

**Step-by-step:**
1. `VisitAssignment_statement` sets flag=true, visits LHS
2. `VisitGeneralElement` sees `pkg.g_total`, flag=true → returns `"pkg.g_total"`
3. `VisitAssignment_statement` sets flag=false, parses `"pkg.g_total"` → recognizes pattern
4. Decides: "This is a setter"
5. Visits RHS expression (flag=false)
6. `VisitGeneralElement` sees `pkg.g_counter`, flag=false → transforms to getter
7. `VisitGeneralElement` sees `pkg.g_increment`, flag=false → transforms to getter
8. RHS becomes: `hr.pkg__get_g_counter() + hr.pkg__get_g_increment()`

**PostgreSQL result:**
```sql
PERFORM hr.pkg__set_g_total(hr.pkg__get_g_counter() + hr.pkg__get_g_increment());
```

**Benefits:**
- ✅ Setter detection at assignment level (clear single decision point)
- ✅ Getter detection at expression level (composable, works in nested expressions)
- ✅ Context flag prevents premature transformation
- ✅ Handles arbitrarily complex RHS expressions safely

---

## Package Context Components

### PackageContext (Ephemeral, In-Memory)

**Location:** Created on-demand in transformation job, cached during job execution

```java
// In PostgresFunctionImplementationJob or PostgresCodeBuilder
private Map<String, PackageContext> packageContextCache = new HashMap<>();

static class PackageContext {
    String schema;
    String packageName;
    Map<String, PackageVariable> variables;  // variable name → variable metadata
    boolean helpersCreated;  // Have helper functions been created in PostgreSQL?

    static class PackageVariable {
        String variableName;
        String dataType;          // e.g., "INTEGER", "VARCHAR2(100)", "DATE"
        String defaultValue;      // e.g., "0", "'ACTIVE'", "SYSDATE"
        boolean isConstant;       // true if declared with CONSTANT keyword
    }
}
```

**Lifecycle:**
1. **Creation:** When first function from package is encountered
2. **Scope:** Exists for duration of job execution
3. **Cache key:** `schema.packagename` (lowercase)
4. **Cleanup:** Garbage collected when job completes

**NOT stored in:**
- ❌ StateService (not global metadata)
- ❌ TransformationContext (too heavyweight for per-package data)
- ❌ Database (generated on-demand)

---

### PackageContextExtractor (Helper Class)

**Purpose:** Parse Oracle package spec with ANTLR, extract variable declarations

```java
public class PackageContextExtractor {
    /**
     * Parses package spec and extracts variable declarations
     *
     * @param schema Schema name
     * @param packageName Package name
     * @param packageSpecSql Package spec SQL from ALL_SOURCE
     * @return PackageContext with variables
     */
    public PackageContext extractContext(String schema, String packageName, String packageSpecSql) {
        // 1. Parse package spec with ANTLR
        PlSqlParser.Create_packageContext specCtx = AntlrParser.parsePackageSpec(packageSpecSql);

        // 2. Extract variable declarations
        List<PackageVariable> variables = new ArrayList<>();

        for (PlSqlParser.Variable_declarationContext varCtx : specCtx.variable_declaration()) {
            String varName = varCtx.identifier().getText();
            String dataType = extractDataType(varCtx.type_spec());
            String defaultValue = extractDefaultValue(varCtx);
            boolean isConstant = varCtx.CONSTANT() != null;

            variables.add(new PackageVariable(varName, dataType, defaultValue, isConstant));
        }

        // 3. Build context
        return new PackageContext(schema, packageName, variables);
    }
}
```

---

### PackageHelperGenerator (Helper Class)

**Purpose:** Generate SQL for helper functions (initialize, getters, setters)

```java
public class PackageHelperGenerator {
    /**
     * Generates helper function SQL for a package
     *
     * @param context Package context with variables
     * @return List of SQL statements to execute
     */
    public List<String> generateHelperSql(PackageContext context) {
        List<String> sqlStatements = new ArrayList<>();

        // 1. Generate initialization function
        sqlStatements.add(generateInitializeFunction(context));

        // 2. Generate getters and setters for each variable
        for (PackageVariable var : context.variables.values()) {
            if (!var.isConstant) {  // Constants don't need setters
                sqlStatements.add(generateGetterFunction(context, var));
                sqlStatements.add(generateSetterFunction(context, var));
            }
        }

        return sqlStatements;
    }

    private String generateInitializeFunction(PackageContext ctx) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE OR REPLACE FUNCTION ")
           .append(ctx.schema).append(".").append(ctx.packageName).append("__initialize()\n");
        sql.append("RETURNS void LANGUAGE plpgsql AS $$\n");
        sql.append("BEGIN\n");
        sql.append("  IF current_setting('").append(ctx.schema).append(".")
           .append(ctx.packageName).append(".__initialized', true) = 'true' THEN\n");
        sql.append("    RETURN;\n");
        sql.append("  END IF;\n\n");

        // Initialize all variables
        for (PackageVariable var : ctx.variables.values()) {
            String configKey = ctx.schema + "." + ctx.packageName + "." + var.variableName;
            String pgDefaultValue = transformDefaultValue(var.defaultValue, var.dataType);
            sql.append("  PERFORM set_config('").append(configKey).append("', '")
               .append(pgDefaultValue).append("', false);\n");
        }

        sql.append("\n  PERFORM set_config('").append(ctx.schema).append(".")
           .append(ctx.packageName).append(".__initialized', 'true', false);\n");
        sql.append("END;$$;\n");

        return sql.toString();
    }

    // Similar for generateGetterFunction() and generateSetterFunction()
}
```

---

## Integration into Existing Function Transformation Job

### Modified PostgresFunctionImplementationJob

**Existing:** `PostgresFunctionImplementationJob` (Step 25) transforms standalone functions

**New:** Same job now handles package functions by detecting package membership and managing package context

```java
@Dependent
public class PostgresFunctionImplementationJob extends AbstractDatabaseCreationJob<FunctionImplementationResult> {

    @Inject
    PostgresConnectionService postgresConnectionService;

    @Inject
    StateService stateService;

    @Inject
    AntlrParser antlrParser;

    // NEW: Package context cache (ephemeral, per-job execution)
    private Map<String, PackageContext> packageContextCache = new HashMap<>();

    @Override
    protected FunctionImplementationResult performCreation(Consumer<JobProgress> progressCallback) {
        List<FunctionMetadata> functions = stateService.getPostgresFunctionMetadata();

        // Filter: Only standalone functions (no "__" in name)
        // UPDATED: Remove filter, handle both standalone and package functions

        int implemented = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (FunctionMetadata function : functions) {
            try {
                // NEW: Check if package function
                if (isPackageFunction(function)) {
                    // Ensure package context exists (parse spec if first function from package)
                    ensurePackageContext(function.getSchema(), extractPackageName(function));
                }

                // Transform function body (works for both standalone and package)
                String transformedSql = transformFunction(function);

                // Execute CREATE OR REPLACE FUNCTION
                executeCreation(transformedSql);
                implemented++;

            } catch (UnsupportedFeatureException e) {
                skipped++;
                // Log skipped function
            } catch (Exception e) {
                errors.add(function.getName() + ": " + e.getMessage());
            }
        }

        return new FunctionImplementationResult(implemented, skipped, errors);
    }

    // NEW: Package context management
    private void ensurePackageContext(String schema, String packageName) throws SQLException {
        String cacheKey = (schema + "." + packageName).toLowerCase();

        if (packageContextCache.containsKey(cacheKey)) {
            return;  // Already cached
        }

        // 1. Query ALL_SOURCE for package spec
        String packageSpecSql = queryPackageSpec(schema, packageName);

        // 2. Parse and extract variable declarations
        PackageContextExtractor extractor = new PackageContextExtractor();
        PackageContext context = extractor.extractContext(schema, packageName, packageSpecSql);

        // 3. Generate helper function SQL
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqlStatements = generator.generateHelperSql(context);

        // 4. Execute helper creation in PostgreSQL
        try (Connection conn = postgresConnectionService.getConnection()) {
            for (String sql : helperSqlStatements) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
            }
        }

        // 5. Cache context
        context.helpersCreated = true;
        packageContextCache.put(cacheKey, context);
    }

    private boolean isPackageFunction(FunctionMetadata function) {
        return function.getName().contains("__");  // Double underscore = package member
    }

    private String extractPackageName(FunctionMetadata function) {
        String name = function.getName();
        int idx = name.indexOf("__");
        return idx > 0 ? name.substring(0, idx) : null;
    }

    private String queryPackageSpec(String schema, String packageName) throws SQLException {
        // Query ALL_SOURCE for package spec
        // ORDER BY line, concatenate TEXT column
        // ...
    }
}
```

---

### Modified PostgresCodeBuilder

**Add package context access:**

```java
public class PostgresCodeBuilder extends PlSqlParserBaseVisitor<String> {

    // Existing fields
    private TransformationContext transformationContext;
    private boolean isInAssignmentTarget = false;

    // NEW: Package context cache (passed from job)
    private Map<String, PackageContext> packageContextCache;

    // NEW: Current function context (for detecting package membership)
    private String currentSchema;
    private String currentPackageName;  // null for standalone functions

    // Constructor
    public PostgresCodeBuilder(
        TransformationContext transformationContext,
        Map<String, PackageContext> packageContextCache  // NEW parameter
    ) {
        this.transformationContext = transformationContext;
        this.packageContextCache = packageContextCache;
    }

    // NEW: Package variable lookup
    public boolean isPackageVariable(String packageName, String variableName) {
        String cacheKey = (currentSchema + "." + packageName).toLowerCase();
        PackageContext ctx = packageContextCache.get(cacheKey);

        if (ctx == null) return false;

        return ctx.variables.containsKey(variableName.toLowerCase());
    }

    public String transformToPackageVariableGetter(String packageName, String variableName) {
        return currentSchema + "." + packageName + "__get_" + variableName.toLowerCase() + "()";
    }

    // Existing assignment target flag methods
    public void setInAssignmentTarget(boolean value) { this.isInAssignmentTarget = value; }
    public boolean isInAssignmentTarget() { return this.isInAssignmentTarget; }

    // ... rest of visitor methods
}
```

---

### Modified VisitFunctionBody / VisitProcedureBody

**Inject initialization call for package functions:**

```java
public class VisitFunctionBody {
    public static String v(PlSqlParser.Function_bodyContext ctx, PostgresCodeBuilder b) {
        // Detect package membership
        String functionName = ctx.identifier(0).getText();
        String packageName = extractPackageNameFromFunctionName(functionName);

        // Transform body
        StringBuilder body = new StringBuilder();
        body.append("BEGIN\n");

        // NEW: Inject initialization call for package functions
        if (packageName != null) {
            String schema = b.getCurrentSchema();
            body.append("  PERFORM ").append(schema).append(".")
                .append(packageName).append("__initialize();\n\n");
        }

        // Transform statements
        body.append(b.visit(ctx.seq_of_statements()));

        body.append("END");
        return body.toString();
    }
}
```

---

## Progressive Implementation Phases

### Phase 1: Simple Integer Variable (START HERE)

**Scope:** Single package with one integer variable, one function that increments it.

**Example Oracle Package:**
```sql
CREATE OR REPLACE PACKAGE hr.test_pkg AS
  g_counter INTEGER := 0;
  FUNCTION increment_counter RETURN INTEGER;
END;
/

CREATE OR REPLACE PACKAGE BODY hr.test_pkg AS
  FUNCTION increment_counter RETURN INTEGER IS
  BEGIN
    g_counter := g_counter + 1;
    RETURN g_counter;
  END;
END;
/
```

**Implementation Steps:**

1. **Create Helper Classes** (2 classes)
   - `PackageContextExtractor.java` - Parse package spec, extract variables
   - `PackageHelperGenerator.java` - Generate initialize/getter/setter SQL

2. **Extend PostgresFunctionImplementationJob** (package context management)
   - Add `packageContextCache` field (Map<String, PackageContext>)
   - Add `ensurePackageContext()` method
   - Add `queryPackageSpec()` method
   - Remove standalone-only filter (handle all functions)

3. **Extend PostgresCodeBuilder** (context tracking)
   - Add `packageContextCache` field (passed from job)
   - Add `currentPackageName` field (set during function transformation)
   - Add `isInAssignmentTarget` flag with accessors

4. **Add Helper Methods to PostgresCodeBuilder** (3 methods)
   ```java
   public boolean isPackageVariable(String packageName, String variableName) { ... }

   public String transformToPackageVariableGetter(String packageName, String varName) {
       // Returns: schema.package__get_varname()
   }

   public PackageVariableReference parsePackageVariableReference(String leftSide) {
       // Parses "pkg.varname" pattern
       // Returns null if not a package variable
   }

   static class PackageVariableReference {
       String schema;
       String packageName;
       String variableName;

       String getSetterCall(String rhsExpression) {
           return schema + "." + packageName + "__set_" + variableName + "( " + rhsExpression + " )";
       }
   }
   ```

5. **Modify VisitAssignment_statement.java** (setter detection)
   - Add context flag protection for LHS parsing
   - Add package variable detection
   - Transform to setter call

6. **Modify VisitGeneralElement.java** (getter detection)
   - Add package variable check in dot navigation section
   - Check `isInAssignmentTarget()` flag
   - Transform to getter if flag=false

7. **Add initialization call injection** (modify function transformation)
   - In `VisitFunctionBody.java` and `VisitProcedureBody.java`
   - For package functions only (detect package membership via `__` in name)
   - Inject `PERFORM {schema}.{package}__initialize();` at start of body

8. **Test with real Oracle example**
   - Create Oracle package with variable
   - Run function transformation job
   - Verify helper functions created
   - Verify package function transformed correctly
   - Execute and verify in PostgreSQL

**Expected Transformation:**

```sql
-- Oracle
FUNCTION increment_counter RETURN INTEGER IS
BEGIN
  g_counter := g_counter + 1;
  RETURN g_counter;
END;

-- PostgreSQL
CREATE OR REPLACE FUNCTION hr.test_pkg__increment_counter()
RETURNS integer
LANGUAGE plpgsql
AS $$
BEGIN
  -- Auto-injected initialization
  PERFORM hr.test_pkg__initialize();

  -- Transformed body
  PERFORM hr.test_pkg__set_g_counter(hr.test_pkg__get_g_counter() + 1);
  RETURN hr.test_pkg__get_g_counter();
END;
$$;
```

---

### Phase 2: Multiple Primitive Types

**Scope:** Extend to VARCHAR2, NUMBER, DATE, BOOLEAN

**Type-Specific Handling:**

| Oracle Type | PostgreSQL Type | Default Value | Notes |
|------------|----------------|---------------|-------|
| INTEGER | integer | 0 | Simple cast |
| NUMBER | numeric | 0 | Simple cast |
| VARCHAR2(n) | text | '' | Text cast |
| DATE | timestamp | CURRENT_TIMESTAMP | Timestamp cast |
| BOOLEAN | boolean | false | Boolean cast (Oracle: TRUE/FALSE → PostgreSQL: true/false) |

**Implementation:**
- Extend getter helper template with type-specific casting
- Extend default value transformation (SYSDATE → CURRENT_TIMESTAMP, TRUE → true)
- Add tests for each type

---

### Phase 3: Constants

**Scope:** Handle CONSTANT keyword

**Oracle:**
```sql
g_max_retry CONSTANT INTEGER := 5;
```

**Options:**

**Option A: Inline Constants**
- Don't generate getters/setters
- Replace references with literal values during transformation
- Simplest approach for true constants

**Option B: Immutable Config**
- Generate only getter (no setter)
- Use `set_config` with literal value
- Allows runtime inspection

**Recommendation:** Start with Option A (inline), add Option B if needed.

---

### Phase 4: Complex Types (Future)

**Scope:** Records, collections (more challenging)

**Challenges:**
- PostgreSQL `set_config` only stores text
- Need serialization/deserialization strategy
- May require JSON encoding for complex structures

**Deferred:** Implement primitives first, assess real-world need for complex types.

---

## File Structure

### New Files (Created)

**Helper Classes:**
- `transformer/packagevariable/PackageContextExtractor.java` - Parse package specs
- `transformer/packagevariable/PackageHelperGenerator.java` - Generate helper SQL
- `transformer/packagevariable/PackageContext.java` - Data model for package context (inner classes)

### Modified Files

**Transformation Job:**
- `function/job/PostgresFunctionImplementationJob.java`
  - Add `packageContextCache` field
  - Add `ensurePackageContext()` method
  - Add `queryPackageSpec()` method
  - Remove standalone-only filter

**Code Builder:**
- `transformer/builder/PostgresCodeBuilder.java`
  - Add `packageContextCache` field (constructor parameter)
  - Add `currentPackageName` field
  - Add `isInAssignmentTarget` flag
  - Add package variable helper methods (isPackageVariable, transformToGetter, parseReference)

**Visitor Classes:**
- `transformer/builder/VisitAssignment_statement.java`
  - Add context flag protection
  - Add package variable detection
  - Add setter transformation

- `transformer/builder/VisitGeneralElement.java`
  - Add package variable check in dot navigation
  - Add getter transformation with flag check

- `transformer/builder/VisitFunctionBody.java`
- `transformer/builder/VisitProcedureBody.java`
  - Inject initialization call for package functions

**NO changes needed:**
- ❌ StateService (no package variable metadata property)
- ❌ REST endpoints (uses existing function implementation endpoint)
- ❌ Frontend (uses existing Step 25 UI)
- ❌ Job registry (reuses existing function implementation job)

---

## Testing Strategy

### Unit Tests (Integrated into Existing Test Suite)

**Package Context Extraction Tests:**
- `PackageContextExtractorTest.java`
  - Test parsing of variable declarations
  - Test CONSTANT keyword detection
  - Test default value extraction
  - Test multiple variables in same package

**Package Helper Generation Tests:**
- `PackageHelperGeneratorTest.java`
  - Test initialization function generation
  - Test getter/setter generation for each type
  - Test multiple variables in same package

**Transformation Tests:**
- `PostgresPlSqlPackageVariableTransformationTest.java`
  - Test getter transformation (RHS)
  - Test setter transformation (LHS)
  - Test mixed getters in complex expressions
  - Test initialization call injection
  - Test constants (if inlined)

### Integration Tests (PostgreSQL Validation)

**Test Class:** `PostgresPlSqlPackageVariableValidationTest.java`

- Execute transformed code in real PostgreSQL (Testcontainers)
- Verify session-level state persistence
- Verify initialization idempotency
- Verify concurrent session isolation

**Test Cases:**
1. Simple counter increment across multiple calls
2. Multiple variables in same package
3. Multiple packages with same variable names (namespace isolation)
4. Multiple sessions (verify isolation)
5. Transaction boundaries (verify session persistence)

---

## Example: Complete Phase 1 Transformation

### Oracle Package

```sql
-- Package Spec
CREATE OR REPLACE PACKAGE hr.test_pkg AS
  g_counter INTEGER := 0;
  FUNCTION increment_counter RETURN INTEGER;
  FUNCTION get_counter RETURN INTEGER;
  PROCEDURE reset_counter;
END;
/

-- Package Body
CREATE OR REPLACE PACKAGE BODY hr.test_pkg AS
  FUNCTION increment_counter RETURN INTEGER IS
  BEGIN
    g_counter := g_counter + 1;
    RETURN g_counter;
  END;

  FUNCTION get_counter RETURN INTEGER IS
  BEGIN
    RETURN g_counter;
  END;

  PROCEDURE reset_counter IS
  BEGIN
    g_counter := 0;
  END;
END;
/
```

### PostgreSQL Transformed

**Helper Functions (auto-generated on-demand):**
```sql
-- Initialization function
CREATE OR REPLACE FUNCTION hr.test_pkg__initialize()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  IF current_setting('hr.test_pkg.__initialized', true) = 'true' THEN
    RETURN;
  END IF;

  PERFORM set_config('hr.test_pkg.g_counter', '0', false);
  PERFORM set_config('hr.test_pkg.__initialized', 'true', false);
END;
$$;

-- Getter
CREATE OR REPLACE FUNCTION hr.test_pkg__get_g_counter()
RETURNS integer
LANGUAGE plpgsql
AS $$
BEGIN
  RETURN COALESCE(current_setting('hr.test_pkg.g_counter', true)::integer, 0);
EXCEPTION WHEN OTHERS THEN
  RETURN 0;
END;
$$;

-- Setter
CREATE OR REPLACE FUNCTION hr.test_pkg__set_g_counter(p_value integer)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM set_config('hr.test_pkg.g_counter', p_value::text, false);
END;
$$;
```

**Transformed Package Functions:**
```sql
-- Function: increment_counter
CREATE OR REPLACE FUNCTION hr.test_pkg__increment_counter()
RETURNS integer
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM hr.test_pkg__initialize();

  PERFORM hr.test_pkg__set_g_counter(hr.test_pkg__get_g_counter() + 1);
  RETURN hr.test_pkg__get_g_counter();
END;
$$;

-- Function: get_counter
CREATE OR REPLACE FUNCTION hr.test_pkg__get_counter()
RETURNS integer
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM hr.test_pkg__initialize();

  RETURN hr.test_pkg__get_g_counter();
END;
$$;

-- Procedure: reset_counter
CREATE OR REPLACE FUNCTION hr.test_pkg__reset_counter()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM hr.test_pkg__initialize();

  PERFORM hr.test_pkg__set_g_counter(0);
END;
$$;
```

**Usage:**
```sql
-- Session 1
SELECT hr.test_pkg__increment_counter(); -- Returns: 1
SELECT hr.test_pkg__increment_counter(); -- Returns: 2
SELECT hr.test_pkg__get_counter();       -- Returns: 2

-- Session 2 (isolated)
SELECT hr.test_pkg__get_counter();       -- Returns: 0 (different session)
```

---

## Success Criteria

**Phase 1 Complete When:**
- ✅ Package context extraction working (parse package spec with ANTLR)
- ✅ Package helper generation working (initialize + getters + setters)
- ✅ On-demand context caching working (parse once per package)
- ✅ Getter transformation working (RHS references)
- ✅ Setter transformation working (LHS assignments)
- ✅ Initialization injection working (package functions only)
- ✅ Session-level state verified in PostgreSQL
- ✅ Multiple calls preserve state within session
- ✅ Multiple sessions properly isolated
- ✅ Zero regressions in existing tests (882+ tests still passing)
- ✅ No new StateService properties, no new jobs, no new REST endpoints

**Coverage Estimate:** +10-20% of package functions (many depend on package variables)

---

## Architecture Validation

### Maintains Established Patterns ✅

| Pattern | Extraction Jobs | Transformation Jobs | Package Variables |
|---------|----------------|---------------------|-------------------|
| **Use ANTLR** | ❌ Never | ✅ Always | ✅ In transformation |
| **Query ALL_SOURCE** | ❌ Never | ✅ Always | ✅ On-demand |
| **Store in StateService** | ✅ Always | ❌ Never | ❌ Ephemeral cache |

**Comparison:**
- **View transformation:** Queries ALL_VIEWS.TEXT, parses with ANTLR in transformation job ✅
- **Function transformation:** Queries ALL_SOURCE, parses with ANTLR in transformation job ✅
- **Package variables:** Queries ALL_SOURCE, parses with ANTLR in transformation job ✅

### Simplicity Gains ✅

**Eliminated:**
- ❌ OraclePackageVariableExtractionJob
- ❌ PostgresPackageVariableHelperCreationJob
- ❌ PostgresPackageVariableHelperVerificationJob
- ❌ PackageVariableMetadata class
- ❌ PackageVariableHelperCreationResult class
- ❌ StateService.packageVariableMetadata property
- ❌ REST endpoint: POST /api/jobs/oracle/package-variable/extract
- ❌ REST endpoint: POST /api/jobs/postgres/package-variable-helper/create
- ❌ REST endpoint: POST /api/jobs/postgres/package-variable-helper-verification/verify
- ❌ Frontend: Step 26 HTML row
- ❌ Frontend: JavaScript handlers for package variables

**Total lines of code saved:** ~1500-2000 lines

**Complexity reduced:**
- No extraction/state/creation pipeline
- No synchronization between extraction and transformation
- No global state for transformation-time context

---

## Future Enhancements

**Phase 2 and beyond:**
- Multiple primitive types (VARCHAR2, DATE, BOOLEAN, NUMBER)
- Constants (inline or immutable config)
- Package initialization blocks (beyond variable initialization)
- Complex types (records, collections with JSON serialization)
- Package cursor variables (similar to regular package variables)
- Package exception variables (declared in package scope)

---

## References

- **Existing Architecture:** `TRANSFORMATION.md` for overall transformation strategy
- **Similar Patterns:** `PLSQL_CURSOR_ATTRIBUTES_PLAN.md` for context tracking pattern
- **Existing Job:** `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md` for function transformation job
- **PostgreSQL Docs:** [set_config and current_setting](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADMIN-SET)
- **Oracle Docs:** [PL/SQL Packages](https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/plsql-packages.html)

---

## Implementation Checklist

### Helper Classes ✅ COMPLETE
- [x] Create `PackageContextExtractor.java` (parse package specs with ANTLR)
- [x] Create `PackageHelperGenerator.java` (generate initialize/getter/setter SQL)
- [x] Create `PackageContext.java` (data model for context)
  - [x] **BONUS:** Added package body AST caching for efficient multi-function extraction
  - [x] Added `extractFunctionSource()` method using character-index slicing

### Extend Transformation Job ✅ COMPLETE
- [x] Add `packageContextCache` field to `PostgresFunctionImplementationJob`
- [x] Implement `ensurePackageContext()` method (parse + generate + execute + cache)
- [x] Implement `queryPackageSpec()` method (query ALL_SOURCE)
  - [x] **FIX:** Prepend `CREATE OR REPLACE` (ALL_SOURCE doesn't include it)
- [x] **BONUS:** Implement `queryPackageBody()` method for package body extraction
  - [x] **FIX:** Prepend `CREATE OR REPLACE` for ANTLR parsing
- [x] Remove standalone-only filter (handle all functions)
- [x] **BONUS:** Implement package function source extraction
  - [x] Added `extractPackageMemberSource()` method
  - [x] Modified `extractOracleFunctionSource()` to handle both standalone and package members
  - [x] **FIX:** Use `function.getPackageName()` directly (not parsed from objectName)
- [x] **BONUS:** Add `parsePackageBody()` to AntlrParser

### Extend PostgresCodeBuilder ✅ COMPLETE
- [x] Add `packageContextCache` constructor parameter
- [x] Add `currentPackageName` field
- [x] Add `isInAssignmentTarget` flag with accessors
- [x] Add `isPackageVariable()` method
- [x] Add `transformToPackageVariableGetter()` method
- [x] Add `parsePackageVariableReference()` method
- [x] Add `PackageVariableReference` inner class

### Visitor Modifications ⏳ PENDING
- [ ] Modify `VisitAssignment_statement.java` (flag protection + setter detection)
- [ ] Modify `VisitGeneralElement.java` (package variable check + getter transformation)
- [ ] Modify `VisitFunctionBody.java` (inject initialization call for package functions)
- [ ] Modify `VisitProcedureBody.java` (inject initialization call for package functions)

### Testing ⏳ PENDING
- [ ] Unit tests: PackageContextExtractor (parsing)
- [ ] Unit tests: PackageHelperGenerator (SQL generation)
- [ ] Unit tests: Getter transformation (VisitGeneralElement)
- [ ] Unit tests: Setter transformation (VisitAssignment_statement)
- [ ] Integration tests: PostgreSQL validation (Testcontainers)
  - [ ] Simple counter increment
  - [ ] Multiple variables in same package
  - [ ] Multiple packages (namespace isolation)
  - [ ] Multiple sessions (session isolation)
  - [ ] Transaction boundaries (session persistence)
- [ ] Verify zero regressions (all 882+ tests still passing)

### Documentation ⏳ PENDING
- [ ] Update `TRANSFORMATION.md` with package variable status
- [ ] Update `CLAUDE.md` with unified approach (no Step 26)
- [ ] Update `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md` (rename to include package functions)

---

## Implementation Progress

### ✅ Phase 1A: Infrastructure (COMPLETE - 2025-10-31)

**Status:** All core infrastructure implemented and working!

**What was built:**
1. **Package Context System**
   - `PackageContext.java` with variable declarations and package body AST caching
   - `PackageContextExtractor.java` for parsing package specs
   - `PackageHelperGenerator.java` for generating PostgreSQL helper functions
   - Ephemeral caching pattern (job-scoped, garbage collected after job)

2. **Package Function Source Extraction** (Bonus - not originally planned)
   - Extended `PackageContext` to cache parsed package body AST
   - Character-index slicing for efficient multi-function extraction
   - Avoids re-querying/re-parsing package body for each function
   - **Efficiency:** Package with 10 functions = 1 parse (not 10!)

3. **Job Integration**
   - Extended `PostgresFunctionImplementationJob` (unified approach)
   - Added `ensurePackageContext()` - parse spec + body, generate helpers, cache
   - Added `queryPackageSpec()` and `queryPackageBody()` methods
   - Modified `extractOracleFunctionSource()` for package member extraction
   - Removed standalone-only filter (handles both standalone and package)

4. **Code Builder Support**
   - Extended `PostgresCodeBuilder` with package variable helper methods
   - Added `isPackageVariable()`, `transformToPackageVariableGetter()`, etc.
   - Added `PackageVariableReference` inner class for setter calls
   - Added assignment target flag for getter/setter disambiguation

5. **Parser Extensions**
   - Added `parsePackageBody()` to `AntlrParser`

**Critical fixes applied:**
- ✅ **Fix #1:** Prepend `CREATE OR REPLACE` to ALL_SOURCE results (Oracle doesn't store it)
- ✅ **Fix #2:** Use `function.getPackageName()` directly (not parsed from objectName)
- ✅ **Fix #3:** Character-index extraction from AST (preserves Oracle formatting)

**Current Status:**
- ✅ Package functions can be extracted from Oracle (both spec and body)
- ✅ Package variables are identified and helper functions generated
- ✅ Package body parsed once per package, cached for all functions
- ✅ All code compiles successfully
- ⏳ Visitor modifications needed for actual getter/setter transformation

### ✅ Phase 1B: Transformation (COMPLETE - 2025-11-01)

**All tasks completed:**
1. ✅ Modified `VisitAssignment_statement.java` for setter transformation
2. ✅ Modified `VisitGeneralElement.java` for getter transformation
3. ✅ Injected initialization calls in function/procedure bodies (`VisitFunctionBody.java`, `VisitProcedureBody.java`)
4. ✅ Created comprehensive unit tests (26 tests, all passing)
   - `PackageContextExtractorTest` - 8 tests for package spec parsing
   - `PackageHelperGeneratorTest` - 8 tests for helper SQL generation
   - `PackageVariableTransformationTest` - 10 tests for getter/setter transformations

**Implementation highlights:**
- Package variable assignments → setter calls: `emp_pkg.g_counter := 100` → `PERFORM hr.emp_pkg__set_g_counter(100)`
- Package variable references → getter calls: `emp_pkg.g_counter` → `hr.emp_pkg__get_g_counter()`
- Initialization injection: `PERFORM hr.emp_pkg__initialize()` injected at start of package function bodies
- Flag protection in assignments: `setInAssignmentTarget()` prevents getter transformation on LHS
- Case-insensitive variable lookup (Oracle compatibility)

---

**Implementation approach validated!** 🎉

**Key Advantages:**
- ✅ Maintains architectural patterns (ANTLR only in transformation)
- ✅ Simpler (no separate jobs, no StateService pollution)
- ✅ More efficient (parse once per package, cache in-memory)
- ✅ Self-contained (all package logic in transformation layer)
- ✅ Same visitor classes work for standalone and package functions

---

## ⚠️ CRITICAL ISSUE DISCOVERED: Package Context Never Passed to Builder (2025-11-02)

### Problem Statement

Package variable transformations are **completely disconnected** from the transformation pipeline despite all infrastructure being complete and working.

**Root Cause:** The `packageContextCache` created by `PostgresStandaloneFunctionImplementationJob` is never passed to `PostgresCodeBuilder`.

**Evidence:**
1. ✅ Job creates and populates cache (lines 77, 400-458)
2. ✅ Builder has 2-param constructor accepting cache (line 243)
3. ✅ Visitor transformation logic complete (VisitAssignment_statement, VisitGeneralElement)
4. ✅ All 26 unit tests passing (correctly use 2-param constructor)
5. ❌ **TransformationService only uses 1-param constructor** (line 271)
6. ❌ **transformFunction/transformProcedure don't accept cache parameter**

**Impact:** Package variable references NOT transformed to getter/setter calls in actual production transformations.

### Architectural Analysis: Current State Problems

**Problem 1: Fragmented Context - Multiple Sources of Truth**

PostgresCodeBuilder currently has FOUR separate context sources:
```java
1. context.getCurrentSchema()        // From TransformationContext
2. currentSchema (field)             // DUPLICATE! Set via setCurrentPackage()
3. currentPackageName (field)        // Set separately via setCurrentPackage()
4. packageContextCache (constructor) // Passed separately
```

**Issues:**
- Schema exists in TWO places (TransformationContext + PostgresCodeBuilder field)
- Manual coordination required: job must call `setCurrentPackage()` after construction
- Package cache passed separately from context (architectural inconsistency)
- **Future blocker:** Where do inline types go? Another parameter? Another field?

**Problem 2: Parameter Explosion**

Current transformation call chain:
```java
// Job has all context:
- function.getSchema()
- function.getPackageName()
- function.getObjectName()
- indices
- packageContextCache

// But TransformationService signature insufficient:
transformFunction(oraclePlSql, schema, indices)  // ← Missing package info!

// Then manual coordination required:
new PostgresCodeBuilder(context, packageContextCache)
builder.setCurrentPackage(schema, packageName)  // ← Manual setter
```

**Future with inline types would be worse:**
```java
transformFunction(
    oraclePlSql, schema, indices,
    packageContextCache,      // Parameter 1
    inlineTypeCache,          // Parameter 2
    currentPackageName,       // Parameter 3
    currentFunctionName,      // Parameter 4
    nestedBlockContext)       // Parameter 5 ...
```

This is **unsustainable**.

---

## 🎯 SOLUTION: Enhanced TransformationContext Architecture (Option B)

### Core Principle

**TransformationContext should be the ONLY source of all transformation-time information.**

### Design: Three-Layer Context Architecture

```java
public class TransformationContext {

    // ========== Layer 1: Immutable Global Context ==========
    // Set at context creation, never changes during transformation

    private final String currentSchema;           // e.g., "hr"
    private final TransformationIndices indices;  // Pre-built metadata lookups
    private final TypeEvaluator typeEvaluator;    // Type inference engine

    // ========== Layer 2: Transformation-Level Context ==========
    // Set once per transformation (function/procedure/view)
    // Immutable after context creation

    private String currentFunctionName;   // e.g., "calculate_salary" (null for views)
    private String currentPackageName;    // e.g., "emp_pkg" (null for standalone/views)

    // Package variable support
    private final Map<String, PackageContext> packageContextCache;  // schema.package -> context

    // Inline type support (FUTURE - ready now with stub)
    private final Map<String, InlineTypeDefinition> inlineTypes;    // typename -> definition

    // ========== Layer 3: Query-Level Context ==========
    // Mutable, changes during transformation (per-query state)

    private final Map<String, String> tableAliases;  // alias → table name
    private final Set<String> cteNames;              // CTE names

    // Future: Nested block context for exception handling, variable scoping
}
```

### Key Architectural Decisions

**Decision 1: Package Context Lives in TransformationContext**

**Rationale:**
- Package variables are transformation-level context (same lifecycle as schema)
- Not query-local (like aliases)
- Not builder-specific (like loop stacks)
- Inline types will have same lifecycle → same pattern

**Benefits:**
- Single place to check: `context.isPackageVariable()`
- Future inline types: `context.getInlineType()`
- No parameter passing through chain
- Builder becomes simpler

**Decision 2: Context Knows Current Function/Package**

**Rationale:**
- TransformationService receives this from `FunctionMetadata`
- Needed for: package variable qualification, initialization injection, inline type scoping
- Future: nested blocks will need function scope

**Benefits:**
- Helper methods use currentSchema + currentPackage: `context.getPackageVariableGetter()`
- No manual `setCurrentPackage()` calls in builder
- Context is self-contained

**Decision 3: Backward-Compatible Constructor Overloading**

```java
// Constructor 1: Simple (existing - for SQL views, no package context)
public TransformationContext(String currentSchema,
                             TransformationIndices indices,
                             TypeEvaluator typeEvaluator) {
    this(currentSchema, indices, typeEvaluator, null, null, null);
}

// Constructor 2: Full (new - for PL/SQL with package context)
public TransformationContext(String currentSchema,
                             TransformationIndices indices,
                             TypeEvaluator typeEvaluator,
                             Map<String, PackageContext> packageContextCache,
                             String functionName,
                             String packageName) {
    // Initialize all layers
    this.currentSchema = currentSchema;
    this.indices = indices;
    this.typeEvaluator = typeEvaluator;
    this.packageContextCache = packageContextCache != null ? packageContextCache : new HashMap<>();
    this.currentFunctionName = functionName;
    this.currentPackageName = packageName;
    this.tableAliases = new HashMap<>();
    this.cteNames = new HashSet<>();
    this.inlineTypes = new HashMap<>();  // FUTURE (stub now)
}
```

**Benefits:**
- Existing SQL transformation code (views) unchanged
- New PL/SQL code uses rich constructor
- Single constructor for all future needs (inline types, nested blocks)

---

## 🏗️ Implementation Plan: Enhanced Context Architecture

### Phase 1: Enhance TransformationContext ✅

**Add fields:**
```java
// Transformation-level context
private String currentFunctionName;
private String currentPackageName;
private final Map<String, PackageContext> packageContextCache;

// Future: Inline type support (stub now)
private final Map<String, InlineTypeDefinition> inlineTypes;
```

**Add full constructor with all parameters**

**Add package variable helper methods:**
```java
public boolean isPackageVariable(String packageName, String variableName);
public String getPackageVariableGetter(String packageName, String variableName);
public String getPackageVariableSetter(String packageName, String variableName, String value);
public PackageContext getPackageContext(String packageName);
public boolean isInPackageMember();
public String getCurrentPackageName();
public String getCurrentFunctionName();

// Future: Inline type support (stubs now)
public void registerInlineType(String typeName, InlineTypeDefinition definition);
public InlineTypeDefinition getInlineType(String typeName);
```

**Add comprehensive javadoc explaining three layers**

### Phase 2: Update TransformationService

**Add method overloads:**
```java
// Existing (backward compatible - for views)
public TransformationResult transformFunction(String oraclePlSql,
                                               String schema,
                                               TransformationIndices indices) {
    return transformFunction(oraclePlSql, schema, indices, null, null, null);
}

// New (for PL/SQL with package context)
public TransformationResult transformFunction(String oraclePlSql,
                                               String schema,
                                               TransformationIndices indices,
                                               Map<String, PackageContext> packageContextCache,
                                               String functionName,
                                               String packageName) {
    // ... validation, type analysis ...

    // Create FULL context
    TypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);
    TransformationContext context = new TransformationContext(
        schema, indices, typeEvaluator,
        packageContextCache, functionName, packageName);

    // Transform (builder only needs context now!)
    PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
    String createFunction = builder.visit(parseResult.getTree());

    return TransformationResult.success(oraclePlSql, createFunction);
}

// Same for transformProcedure
```

### Phase 3: Update Job to Pass Full Context

**Simplified transformation call:**
```java
for (FunctionMetadata function : oracleFunctions) {
    // Ensure package context
    if (!function.isStandalone()) {
        ensurePackageContext(oracleConnection, postgresConnection,
                            function.getSchema(), function.getPackageName());
    }

    // Extract source
    String oracleSource = extractOracleFunctionSource(oracleConnection, function);

    // Transform with FULL context
    TransformationResult transformResult;
    if (function.isFunction()) {
        transformResult = transformationService.transformFunction(
                oracleSource,
                function.getSchema(),
                indices,
                packageContextCache,           // ← Package cache
                function.getObjectName(),      // ← Function name
                function.getPackageName());    // ← Package name (null for standalone)
    } else {
        transformResult = transformationService.transformProcedure(
                oracleSource,
                function.getSchema(),
                indices,
                packageContextCache,
                function.getObjectName(),
                function.getPackageName());
    }
}
```

### Phase 4: Simplify PostgresCodeBuilder

**Remove duplicate fields:**
```java
// ❌ REMOVE these (now in context):
private Map<String, PackageContext> packageContextCache;
private String currentSchema;
private String currentPackageName;
```

**Remove 2-param constructor:**
```java
// ❌ REMOVE this constructor:
public PostgresCodeBuilder(TransformationContext context,
                           Map<String, PackageContext> packageContextCache)

// ✅ KEEP ONLY:
public PostgresCodeBuilder(TransformationContext context)
```

**Delegate to context:**
```java
public boolean isPackageVariable(String packageName, String variableName) {
    return context != null && context.isPackageVariable(packageName, variableName);
}

public String transformToPackageVariableGetter(String packageName, String variableName) {
    if (context == null) return packageName + "." + variableName;
    return context.getPackageVariableGetter(packageName, variableName);
}
```

**Remove setCurrentPackage() method** (no longer needed)

### Phase 5: Update Tests

**Update unit tests to use new constructor:**
```java
// Old:
PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
builder.setCurrentPackage("hr", "emp_pkg");

// New:
TransformationContext context = new TransformationContext(
    "hr", indices, typeEvaluator, packageContextCache, "test_func", "emp_pkg");
PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
```

### Phase 6: Add Integration Tests

Verify end-to-end package variable transformation with real Oracle/PostgreSQL.

---

## 🔮 Future: Inline Complex Types (Already Supported!)

With enhanced context architecture, adding inline type support requires **zero new parameters**.

### Example Use Case:

```sql
-- Oracle function with inline type
CREATE FUNCTION calculate_bonus(p_emp_id NUMBER) RETURN NUMBER AS
  -- Inline type definition (local to this function)
  TYPE salary_breakdown_t IS RECORD (
    base_salary NUMBER,
    bonus NUMBER,
    total NUMBER
  );

  v_salary salary_breakdown_t;
BEGIN
  SELECT base_sal, bonus_amt, total_comp
  INTO v_salary.base_salary, v_salary.bonus, v_salary.total
  FROM emp_summary WHERE emp_id = p_emp_id;

  RETURN v_salary.total * 0.10;
END;
```

### Implementation (Future):

**When visiting TYPE declaration:**
```java
public static String v(PlSqlParser.Type_declarationContext ctx, PostgresCodeBuilder b) {
    String typeName = ctx.identifier().getText();
    InlineTypeDefinition typeDef = extractTypeDefinition(ctx);

    // Register in context (already has infrastructure!)
    b.getContext().registerInlineType(typeName, typeDef);

    return generatePostgresTypeDefinition(typeName, typeDef);
}
```

**When resolving variable types:**
```java
public static String v(PlSqlParser.Variable_declarationContext ctx, PostgresCodeBuilder b) {
    String typeName = ctx.type_spec().getText();

    // Type resolution cascade:
    // 1. Check inline types (function-local)
    InlineTypeDefinition inlineType = b.getContext().getInlineType(typeName);
    if (inlineType != null) return inlineType.getPostgresType();

    // 2. Check package types (package-level)
    PackageContext pkgCtx = b.getContext().getPackageContext(
        b.getContext().getCurrentPackageName());
    if (pkgCtx != null && pkgCtx.hasType(typeName)) {
        return pkgCtx.getType(typeName).getPostgresType();
    }

    // 3. Check global types (schema-level from indices)
    return b.getContext().getIndices().resolveType(typeName);
}
```

**No new parameters needed!** Infrastructure already in `TransformationContext`.

---

## ✅ Architectural Benefits Summary

### Immediate Benefits:
1. ✅ Single source of truth - All context in one place
2. ✅ No duplicate state - Schema/package exist once
3. ✅ Simpler builder - Only needs context
4. ✅ Cleaner tests - Mock one object
5. ✅ Clear ownership - Context owns transformation state

### Future Benefits:
6. ✅ Inline types ready - Just use existing `inlineTypes` map
7. ✅ Nested blocks ready - BlockContext stack fits in context
8. ✅ Package-level types ready - Already in PackageContext
9. ✅ No parameter explosion - Context grows internally, API stable
10. ✅ Consistent pattern - All transformation state in context

---

## 📊 Implementation Checklist (Option B)

### ✅ Phase 1: TransformationContext Enhancement - COMPLETE
- [x] Add currentFunctionName, currentPackageName fields
- [x] Add packageContextCache field
- [x] Add inlineTypes field (stub)
- [x] Add full constructor (6 parameters)
- [x] Update simple constructor to delegate
- [x] Add package variable helper methods
- [x] Add inline type stub methods
- [x] Add comprehensive javadoc

### ✅ Phase 2: TransformationService Update - COMPLETE
- [x] Add transformFunction overload (6 parameters)
- [x] Add transformProcedure overload (6 parameters)
- [x] Update both to create full context
- [x] Remove builder.setCurrentPackage() calls
- [x] Verify backward compatibility

### ✅ Phase 3: Job Update - COMPLETE
- [x] Update transformFunction call (add 3 parameters)
- [x] Update transformProcedure call (add 3 parameters)
- [x] Remove any setCurrentPackage() calls

### ✅ Phase 4: PostgresCodeBuilder Simplification - COMPLETE
- [x] Remove packageContextCache field
- [x] Remove currentSchema field
- [x] Remove currentPackageName field
- [x] Remove 2-param constructor
- [x] Remove setCurrentPackage() method
- [x] Delegate all package methods to context
- [x] Update javadoc

### ✅ Phase 5: Test Updates - COMPLETE
- [x] Update PackageVariableTransformationTest (9 tests)
- [x] Update PackageContextExtractorTest (not needed)
- [x] Update PackageHelperGeneratorTest (not needed)
- [x] Verify all 25 package tests pass
- [x] Tests automatically use new context constructor

### ✅ Phase 6: Integration Testing - COMPLETE
- [x] Full test suite: 920 tests passed, 0 failures, 0 errors
- [x] Package variable getter transformation verified
- [x] Package variable setter transformation verified
- [x] Initialization injection verified
- [x] Helper function creation verified
- [x] Zero regressions confirmed

---

## 🎓 Implementation Order Rationale

**Build from inside-out:**
1. **TransformationContext first** - Foundation must be solid
2. **TransformationService second** - Middle layer uses new context
3. **Job third** - Top layer calls service with full context
4. **Builder simplification last** - Remove duplication after new pattern established
5. **Tests throughout** - Update as each phase completes

This order minimizes breakage and allows incremental verification.

---

## Decision Log

**2025-11-02:** Selected Option B (Enhanced TransformationContext) over Option A (Quick Fix)

**Rationale:**
- Future requirement: Inline complex types in functions/packages
- Avoid parameter explosion in TransformationService
- Eliminate duplicate state (schema in two places)
- Single source of truth for all transformation context
- Ready for nested blocks, package-level types, etc.

**Decisions:**
1. ✅ Add inline type infrastructure now (as stub)
2. ✅ Constructor overloading (no builder pattern)
3. ✅ No API versioning (overload existing methods)

---

## ⚠️ CRITICAL ISSUES DISCOVERED (2025-11-02)

**Status:** Three severe issues identified during integration testing that prevent package variable transformations from working correctly in production.

### ✅ Issue A: Package Body Variables Not Recognized - RESOLVED

**Status:** ✅ **FIXED** (2025-11-02)

**Problem:** Package variables declared in the package body (not in the spec) were completely ignored.

**Root Cause Analysis:**

`PackageContextExtractor.java` (lines 66-73 before fix):
```java
for (PlSqlParser.Package_obj_specContext specCtx : packageCtx.package_obj_spec()) {
    if (specCtx.variable_declaration() != null) {
        extractVariableDeclaration(specCtx.variable_declaration(), context);
    }
}
```

This code:
- ✅ Correctly extracted variables from **package specification** (public variables)
- ❌ Did NOT extract variables from **package body** (private/body variables)
- ❌ Ignored `package_obj_body()` entirely

**Oracle Package Structure:**

```sql
-- PACKAGE SPECIFICATION (public interface)
CREATE OR REPLACE PACKAGE hr.test_pkg AS
  g_public_counter INTEGER := 0;  -- ✅ Extracted from spec
  FUNCTION get_counter RETURN INTEGER;
END test_pkg;
/

-- PACKAGE BODY (implementation + private variables)
CREATE OR REPLACE PACKAGE BODY hr.test_pkg AS
  g_private_counter INTEGER := 0;  -- ❌ Was NOT extracted (ISSUE!)
  g_internal_state VARCHAR2(20);   -- ❌ Was NOT extracted (ISSUE!)

  FUNCTION get_counter RETURN INTEGER IS
  BEGIN
    RETURN g_private_counter;  -- ❌ Was not recognized as package variable!
  END;
END test_pkg;
/
```

**Impact (Before Fix):**
- Any function that referenced a body-only variable failed transformation
- Variables were NOT transformed to getter/setter calls
- References remained as simple identifiers → PostgreSQL error: "column g_private_counter does not exist"
- **SEVERE** - Many Oracle packages declare variables in the body for encapsulation

**Evidence:**
- `PostgresStandaloneFunctionImplementationJob.java:461` - Package body WAS being parsed and cached
- `PackageContext.setPackageBody()` - Body AST was stored but NEVER scanned for variables
- Body AST only used for function source extraction, not variable extraction

**Fix Applied (2025-11-02):**

**1. Added `extractBodyVariables()` method to `PackageContextExtractor.java`:**
```java
/**
 * Extracts package variable declarations from a package body.
 * This should be called after extractContext() to get body variables in addition to spec variables.
 */
public void extractBodyVariables(PlSqlParser.Create_package_bodyContext bodyAst, PackageContext context) {
    if (bodyAst == null || bodyAst.package_obj_body() == null) {
        log.debug("No package body or no body objects to extract variables from");
        return;
    }

    int bodyVariableCount = 0;

    // Iterate through package body objects
    for (PlSqlParser.Package_obj_bodyContext bodyObjCtx : bodyAst.package_obj_body()) {
        // Extract variable declarations (package-level body variables)
        if (bodyObjCtx.variable_declaration() != null) {
            extractVariableDeclaration(bodyObjCtx.variable_declaration(), context);
            bodyVariableCount++;
        }
    }

    log.debug("Extracted {} variables from package body {}.{}",
              bodyVariableCount, context.getSchema(), context.getPackageName());
}
```

**2. Modified `PostgresStandaloneFunctionImplementationJob.ensurePackageContext()` to extract body variables BEFORE generating helpers:**

**Flow Before (Wrong):**
```
1. Extract spec variables
2. Generate helpers (spec only) ← Missing body variables!
3. Execute helpers
4. Parse package body
5. Cache body AST
```

**Flow After (Correct):**
```
1. Extract spec variables
2. Parse package body
3. Extract body variables           ← NEW!
4. Generate helpers (spec + body)   ← Now includes ALL variables
5. Execute helpers
6. Cache body AST
```

**Code Changes in Job (lines 427-467):**
```java
// Step 2: Parse spec and extract variable declarations from spec
PackageContextExtractor extractor = new PackageContextExtractor(antlrParser);
PackageContext context = extractor.extractContext(schema, packageName, packageSpecSql);
log.info("Extracted {} variables from package spec {}.{}", ...);

// Step 3: Query and parse package body (for function source extraction AND body variables)
String packageBodySql = queryPackageBody(oracleConn, schema, packageName);
ParseResult bodyParseResult = antlrParser.parsePackageBody(packageBodySql);
PlSqlParser.Create_package_bodyContext bodyAst = ...;
context.setPackageBody(packageBodySql, bodyAst);

// Step 4: Extract body variables (private package variables declared in body)
extractor.extractBodyVariables(bodyAst, context);  // ← NEW!
log.info("Total variables (spec + body) for package {}.{}: {}", ...);

// Step 5: Generate helper function SQL (for ALL variables - spec + body)
PackageHelperGenerator generator = new PackageHelperGenerator();
List<String> helperSqlStatements = generator.generateHelperSql(context);

// Step 6: Execute helper creation in PostgreSQL
...
```

**Verification:**

Added integration test `packageBodyVariables_privateVariables()`:
- Package spec with 1 public variable
- Package body with 2 private variables
- Function that uses both public and private variables
- ✅ All variables extracted (3 total)
- ✅ Helpers generated for all variables
- ✅ Transformation converts all references to getters
- ✅ PostgreSQL execution successful

**Test Results:**
- ✅ All 4 tests in PostgresPackageVariableIntegrationTest passing (0 failures, 0 errors)
- ✅ Body variables now correctly recognized and transformed
- ✅ Functions using body variables execute successfully in PostgreSQL
- ✅ Zero regressions

---

### ✅ Issue B: Generated Function Name Missing Package Prefix - RESOLVED

**Status:** ✅ **FIXED** (2025-11-02)

**Problem:** Package member functions were generated with wrong names - missing the package prefix.

**Expected:** `CREATE OR REPLACE FUNCTION user_vanessa.testpackagevar1__test1()`
**Actual (before fix):** `CREATE OR REPLACE FUNCTION user_vanessa.test1()`

**Root Cause Analysis:**

`VisitFunctionBody.java` (lines 56-62):
```java
// STEP 1: Extract function name from AST
String functionName;
if (ctx.identifier() != null) {
    functionName = ctx.identifier().getText().toLowerCase();  // ← Just the function name!
} else {
    throw new IllegalStateException("Function name not found in AST");
}
String qualifiedName = schema.toLowerCase() + "." + functionName;  // ← Missing package!
```

**The Problem:**
1. Line 58: Extracts function name from AST (`test1`)
2. Line 62: Builds qualified name as `schema.functionName` (`user_vanessa.test1`)
3. ❌ Does NOT check if this is a package member
4. ❌ Does NOT apply the `packageName__functionName` convention

**What Should Happen:**
```java
// Get context info
String schema = b.getContext().getCurrentSchema();          // "user_vanessa"
String packageName = b.getContext().getCurrentPackageName(); // "testpackagevar1"
String functionName = ctx.identifier().getText().toLowerCase(); // "test1"

// Build qualified name based on package membership
String qualifiedName;
if (packageName != null) {
    // Package member: schema.packageName__functionName
    qualifiedName = schema.toLowerCase() + "." +
                   packageName.toLowerCase() + "__" +
                   functionName;
} else {
    // Standalone: schema.functionName
    qualifiedName = schema.toLowerCase() + "." + functionName;
}
```

**Impact:**
- **CRITICAL** - Generated function name doesn't match the stub
- Stub created as: `testpackagevar1__test1` (correct naming convention)
- Implementation creates: `test1` (wrong - missing package prefix)
- `CREATE OR REPLACE` fails because names don't match
- Results in duplicate functions: stub still exists, new function created with different name

**Evidence:**
- `TransformationContext.getCurrentPackageName()` exists and returns correct package name
- Value is set correctly in `TransformationService.transformFunction()` (line 120: 6-param call with packageName)
- But VisitFunctionBody never uses it for naming!

**Same Issue in VisitProcedureBody:**
- Same pattern exists in `VisitProcedureBody.java`
- Fixed in both places

**Fix Applied (2025-11-02):**

Modified both `VisitFunctionBody.java` and `VisitProcedureBody.java` to check for package membership:

```java
// Build qualified name based on package membership
String qualifiedName;
String packageName = b.getContext().getCurrentPackageName();
if (packageName != null) {
    // Package member: schema.packageName__functionName
    qualifiedName = schema.toLowerCase() + "." +
                   packageName.toLowerCase() + "__" +
                   functionName;
} else {
    // Standalone: schema.functionName
    qualifiedName = schema.toLowerCase() + "." + functionName;
}
```

**Verification:**
- ✅ All 923 tests passing (0 failures, 0 errors, 6 skipped)
- ✅ Integration tests confirm correct naming:
  - `hr.counter_pkg__get_counter()` ✓
  - `hr.counter_pkg__increment_counter()` ✓
  - `hr.calc_pkg__calculate_total()` ✓
  - `hr.config_pkg__get_config()` ✓
- ✅ Stub replacement now works (names match)
- ✅ Zero regressions

---

### ✅ Issue C: Package Variable Detection Patterns Incomplete - RESOLVED

**Status:** ✅ **FIXED** (2025-11-02)

**Problem:** Package variable detection only handled one pattern (package.variable), missing two other valid Oracle patterns.

**Root Cause Analysis:**

`VisitGeneralElement.java` (lines 84-100):
```java
// STEP 0: Check for package variable references (unless in assignment target)
// Pattern: package.variable (2 parts, no function arguments)
if (!b.isInAssignmentTarget() && parts.size() == 2) {  // ← ONLY 2 parts!
    PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
    boolean hasArguments = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();

    if (!hasArguments) {
        // Could be a package variable - build the reference string
        String packageName = parts.get(0).id_expression().getText();
        String variableName = parts.get(1).id_expression().getText();

        // Check if this is a package variable
        if (b.isPackageVariable(packageName, variableName)) {
            // Transform to getter call
            return b.transformToPackageVariableGetter(packageName, variableName);
```

**Current Detection:** Only `parts.size() == 2`

**Valid Oracle Patterns NOT Handled:**

**Pattern 1: Schema-Qualified Reference (3 parts)**
```sql
-- Oracle code inside a package function
FUNCTION calculate RETURN NUMBER IS
BEGIN
  RETURN hr.test_pkg.g_counter + 1;  -- schema.package.variable (3 parts)
       -- ^^  ^^^^^^^^  ^^^^^^^^^
       -- schema package  variable
END;
```

Current code: `parts.size() == 2` → **FAILS** (size is 3)
Result: Not recognized as package variable, passed through as-is
PostgreSQL error: "column hr.test_pkg.g_counter does not exist"

**Pattern 2: Unqualified Reference in Current Package (1 part)**
```sql
-- Oracle code inside testpackagevar1 package
PACKAGE BODY testpackagevar1 AS
  g_counter INTEGER := 0;

  FUNCTION test1 RETURN NUMBER IS
  BEGIN
    RETURN g_counter + 1;  -- Just variable name (1 part)
         -- ^^^^^^^^^
         -- No package qualifier - use CURRENT package
  END;
END;
```

Current code: `parts.size() == 2` → **FAILS** (size is 1)
Result: Not recognized as package variable, passed through as simple identifier
PostgreSQL error: "column g_counter does not exist"

**Required Logic:**

```java
// Pattern 1: Unqualified variable in current package (1 part)
if (parts.size() == 1 && !hasArguments) {
    String variableName = parts.get(0).id_expression().getText();
    String currentPackage = b.getContext().getCurrentPackageName();

    if (currentPackage != null && b.isPackageVariable(currentPackage, variableName)) {
        return b.transformToPackageVariableGetter(currentPackage, variableName);
    }
}

// Pattern 2: Package-qualified variable (2 parts) - CURRENT CODE
if (parts.size() == 2 && !hasArguments) {
    String packageName = parts.get(0).id_expression().getText();
    String variableName = parts.get(1).id_expression().getText();

    if (b.isPackageVariable(packageName, variableName)) {
        return b.transformToPackageVariableGetter(packageName, variableName);
    }
}

// Pattern 3: Schema-qualified variable (3 parts) - MISSING!
if (parts.size() == 3 && !hasArguments) {
    String schema = parts.get(0).id_expression().getText();
    String packageName = parts.get(1).id_expression().getText();
    String variableName = parts.get(2).id_expression().getText();

    // Verify schema matches current schema (Oracle doesn't allow cross-schema package refs)
    if (schema.equalsIgnoreCase(b.getContext().getCurrentSchema()) &&
        b.isPackageVariable(packageName, variableName)) {
        return b.transformToPackageVariableGetter(packageName, variableName);
    }
}
```

**Impact (Before Fix):**
- ❌ Schema-qualified references (hr.pkg.var) not transformed → PostgreSQL syntax error
- ❌ Unqualified references (just var) not transformed → "column does not exist" error
- Only package.var pattern works
- Real Oracle code uses all three patterns!

**Fix Applied (2025-11-02):**

**1. Extended `VisitGeneralElement.handleDotNavigation()` to support all three patterns:**

Modified lines 87-142 to check for all three patterns:
```java
// STEP 0: Check for package variable references (unless in assignment target)
// Three Oracle patterns for package variable references:
//   1. Unqualified (1 part):        g_counter
//   2. Package-qualified (2 parts): pkg.g_counter
//   3. Schema-qualified (3 parts):  hr.pkg.g_counter
if (!b.isInAssignmentTarget()) {
  PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
  boolean hasArguments = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();

  if (!hasArguments) {
    // Pattern 1: Unqualified variable in current package (1 part)
    if (parts.size() == 1) {
      String variableName = parts.get(0).id_expression().getText();
      String currentPackage = b.getContext().getCurrentPackageName();

      if (currentPackage != null && b.isPackageVariable(currentPackage, variableName)) {
        return b.transformToPackageVariableGetter(currentPackage, variableName);
      }
    }

    // Pattern 2: Package-qualified variable (2 parts)
    if (parts.size() == 2) {
      String packageName = parts.get(0).id_expression().getText();
      String variableName = parts.get(1).id_expression().getText();

      if (b.isPackageVariable(packageName, variableName)) {
        return b.transformToPackageVariableGetter(packageName, variableName);
      }
    }

    // Pattern 3: Schema-qualified variable (3 parts)
    if (parts.size() == 3) {
      String schemaName = parts.get(0).id_expression().getText();
      String packageName = parts.get(1).id_expression().getText();
      String variableName = parts.get(2).id_expression().getText();

      String currentSchema = b.getContext().getCurrentSchema();
      if (schemaName.equalsIgnoreCase(currentSchema) &&
          b.isPackageVariable(packageName, variableName)) {
        return b.transformToPackageVariableGetter(packageName, variableName);
      }
    }
  }
}
```

**2. Extended `PostgresCodeBuilder.parsePackageVariableReference()` to support all three patterns:**

Modified lines 621-680 to handle assignment targets for all three patterns:
```java
// Check if it's a dot-qualified reference
// Three Oracle patterns for package variable references:
//   1. Unqualified (1 part):        g_counter
//   2. Package-qualified (2 parts): pkg.g_counter
//   3. Schema-qualified (3 parts):  hr.pkg.g_counter
String[] parts = leftSide.split("\\.");

if (parts.length == 1 && context.isInPackageMember()) {
  // Pattern 1: Unqualified reference within a package function
  String variableName = parts[0].trim().toLowerCase();
  String currentPackageName = context.getCurrentPackageName();

  if (context.isPackageVariable(currentPackageName, variableName)) {
    return new PackageVariableReference(context.getCurrentSchema(), currentPackageName, variableName);
  }
}
else if (parts.length == 2) {
  // Pattern 2: Package-qualified reference
  String packageName = parts[0].trim().toLowerCase();
  String variableName = parts[1].trim().toLowerCase();

  if (!isPackageVariable(packageName, variableName)) {
    return null;
  }

  return new PackageVariableReference(context.getCurrentSchema(), packageName, variableName);
}
else if (parts.length == 3) {
  // Pattern 3: Schema-qualified reference
  String schemaName = parts[0].trim().toLowerCase();
  String packageName = parts[1].trim().toLowerCase();
  String variableName = parts[2].trim().toLowerCase();

  String currentSchema = context.getCurrentSchema();
  if (schemaName.equalsIgnoreCase(currentSchema) &&
      isPackageVariable(packageName, variableName)) {
    return new PackageVariableReference(currentSchema, packageName, variableName);
  }
}
```

**Verification:**

Added 8 comprehensive unit tests in `PackageVariableTransformationTest.java`:
- `pattern1_unqualifiedGetter_currentPackage()` - Unqualified reference → getter
- `pattern1_unqualifiedSetter_currentPackage()` - Unqualified assignment → setter
- `pattern2_packageQualifiedGetter()` - Package-qualified reference → getter
- `pattern2_packageQualifiedSetter()` - Package-qualified assignment → setter
- `pattern3_schemaQualifiedGetter()` - Schema-qualified reference → getter
- `pattern3_schemaQualifiedSetter()` - Schema-qualified assignment → setter
- `pattern3_wrongSchema_noTransform()` - Wrong schema → not transformed (negative test)

**Test Results:**
- ✅ All 16 tests in PackageVariableTransformationTest passing (7 new tests added)
- ✅ Full test suite: 931 tests passed, 0 failures, 0 errors
- ✅ All three Oracle patterns now correctly detected and transformed
- ✅ Zero regressions

---

### Summary of Issues

| Issue | Status | Location | Severity | Impact |
|-------|--------|----------|----------|--------|
| **A: Body variables ignored** | ✅ **FIXED** | `PackageContextExtractor.java:66-73` + `PostgresStandaloneFunctionImplementationJob.java:427-467` | CRITICAL | Body variables now extracted and transformed correctly |
| **B: Function name missing package** | ✅ **FIXED** | `VisitFunctionBody.java:55-76` + `VisitProcedureBody.java:58-79` | CRITICAL | CREATE OR REPLACE now works, correct naming convention |
| **C: Detection patterns incomplete** | ✅ **FIXED** | `VisitGeneralElement.java:87-142` + `PostgresCodeBuilder.java:621-680` | HIGH | All 3 Oracle patterns now detected and transformed |

**Fix Progress:**
- ✅ **Issue A** (body variables) - **COMPLETED** (2025-11-02) - All 4 integration tests passing
- ✅ **Issue B** (function naming) - **COMPLETED** (2025-11-02) - 923 tests passing
- ✅ **Issue C** (detection patterns) - **COMPLETED** (2025-11-02) - 931 tests passing (7 new tests)

**Testing Status:**
- ✅ Generated function names verified (Issue B)
- ✅ Package body variables test added and passing (Issue A)
- ✅ All 3 reference patterns tested and verified (Issue C)

**🎉 ALL CRITICAL ISSUES RESOLVED! Package variable transformation is now production-ready.**
