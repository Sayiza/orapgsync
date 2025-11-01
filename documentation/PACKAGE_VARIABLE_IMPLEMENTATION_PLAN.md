# Package Variable Implementation Plan (Unified Approach)

**Status:** 📋 **PLANNED** - Ready for implementation
**Created:** 2025-10-31
**Updated:** 2025-10-31 (Revised to unified architecture)
**Integration:** Extends existing Step 25 (Function/Procedure Implementation)

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
