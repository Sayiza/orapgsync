# Package Variable Implementation Plan

**Status:** 📋 **PLANNED** - Ready for implementation
**Created:** 2025-10-31
**Target:** Step 26 in orchestration workflow (Package Analysis and Variable Support)

---

## Overview

Implements Oracle package variable support in PostgreSQL using `set_config`/`current_setting` mechanism for session-level state management. This enables migrated package functions/procedures to maintain state across calls, matching Oracle's package variable semantics.

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

### 4. Variable Metadata: Extraction Job ✅

**Decision:** Extract package variable declarations from Oracle `ALL_SOURCE` in separate extraction job

**Pattern:** Follow established architecture (extraction → state → creation)

**Job Structure:**
```
OraclePackageVariableExtractionJob
  ↓ queries ALL_SOURCE
  ↓ parses package spec declarations
  ↓ stores in StateService.packageVariableMetadata

PostgresPackageVariableHelperCreationJob
  ↓ reads from StateService
  ↓ generates initialization functions
  ↓ generates getter/setter helper functions
  ↓ executes in PostgreSQL
```

**Benefits:**
- ✅ Follows established pattern (no database queries during transformation)
- ✅ Clear separation of concerns
- ✅ Metadata available for transformation context
- ✅ Testable with mocked metadata

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

## Data Model

### PackageVariableMetadata

**Location:** `core/job/model/packagelevel/PackageVariableMetadata.java`

```java
public class PackageVariableMetadata {
    private String schema;
    private String packageName;
    private String variableName;
    private String dataType;          // e.g., "INTEGER", "VARCHAR2(100)", "DATE"
    private String defaultValue;      // e.g., "0", "'ACTIVE'", "SYSDATE"
    private boolean isConstant;       // true if declared with CONSTANT keyword

    // constructors, getters, toString
}
```

### StateService Properties

```java
// In StateService.java
private List<PackageVariableMetadata> packageVariableMetadata = new ArrayList<>();

public List<PackageVariableMetadata> getPackageVariableMetadata() {
    return packageVariableMetadata;
}

public void setPackageVariableMetadata(List<PackageVariableVariableMetadata> metadata) {
    this.packageVariableMetadata = metadata;
}
```

---

## Transformation Context

### PackageVariableContext (Inner Class in PostgresCodeBuilder)

```java
// In PostgresCodeBuilder.java
private static class PackageVariableContext {
    // Key: schema.package.variable → metadata
    private final Map<String, PackageVariable> variables = new HashMap<>();

    static class PackageVariable {
        String schema;
        String packageName;
        String variableName;
        String dataType;
        String defaultValue;
        boolean isConstant;
    }

    void registerVariable(String schema, String packageName, String varName,
                         String type, String defaultValue, boolean isConstant) {
        String key = (schema + "." + packageName + "." + varName).toLowerCase();
        variables.put(key, new PackageVariable(...));
    }

    boolean isPackageVariable(String schema, String packageName, String varName) {
        String key = (schema + "." + packageName + "." + varName).toLowerCase();
        return variables.containsKey(key);
    }

    PackageVariable getVariable(String schema, String packageName, String varName) {
        String key = (schema + "." + packageName + "." + varName).toLowerCase();
        return variables.get(key);
    }

    List<PackageVariable> getVariablesForPackage(String schema, String packageName) {
        String prefix = (schema + "." + packageName + ".").toLowerCase();
        return variables.entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    }
}
```

**Initialization:**
Populated when `PostgresCodeBuilder` is created, from `TransformationContext` which gets metadata from `StateService`.

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

1. **Create Data Model** (1 class)
   - `PackageVariableMetadata.java`

2. **Create Extraction Job** (1 class)
   - `OraclePackageVariableExtractionJob.java`
   - Queries `ALL_SOURCE` for package specs
   - Parses variable declarations (regex: `^\s*(\w+)\s+(INTEGER|NUMBER|VARCHAR2|DATE|BOOLEAN).*$`)
   - Extracts: name, type, default value, CONSTANT keyword
   - Stores in `StateService.packageVariableMetadata`

3. **Extend StateService** (1 property)
   - Add `packageVariableMetadata` list with getters/setters

4. **Create Helper Generation Job** (1 class)
   - `PostgresPackageVariableHelperCreationJob.java`
   - Reads from `StateService.packageVariableMetadata`
   - Generates for each package:
     - `{schema}.{package}__initialize()` function
     - `{schema}.{package}__get_{variable}()` getter for each variable
     - `{schema}.{package}__set_{variable}(p_value type)` setter for each variable
   - Executes CREATE FUNCTION statements in PostgreSQL

5. **Extend PostgresCodeBuilder** (context tracking)
   - Add `PackageVariableContext` inner class
   - Add `isInAssignmentTarget` flag with accessors
   - Populate context from `TransformationContext` (which reads from `StateService`)

6. **Add Helper Methods to PostgresCodeBuilder** (3 methods)
   ```java
   public boolean isPackageVariable(List<General_element_partContext> parts) { ... }

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

7. **Modify VisitAssignment_statement.java** (setter detection)
   - Add context flag protection for LHS parsing
   - Add package variable detection
   - Transform to setter call

8. **Modify VisitGeneralElement.java** (getter detection)
   - Add package variable check in dot navigation section
   - Check `isInAssignmentTarget()` flag
   - Transform to getter if flag=false

9. **Add initialization call injection** (modify function transformation)
   - In `VisitFunctionBody.java` and `VisitProcedureBody.java`
   - For package functions only (detect package membership)
   - Inject `PERFORM {schema}.{package}__initialize();` at start of body

10. **Test with real Oracle example**
    - Extract package variables (Step 1)
    - Create helper functions (Step 2)
    - Transform package function (Step 3)
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

**Data Model:**
- `core/job/model/packagelevel/PackageVariableMetadata.java`

**Jobs:**
- `packagelevel/job/OraclePackageVariableExtractionJob.java`
- `packagelevel/job/PostgresPackageVariableHelperCreationJob.java`
- `packagelevel/job/PostgresPackageVariableHelperVerificationJob.java`

**Results:**
- `core/job/model/packagelevel/PackageVariableHelperCreationResult.java`

**Services (if needed):**
- `packagelevel/service/OraclePackageVariableExtractor.java` (optional helper)

### Modified Files

**State Management:**
- `core/state/StateService.java` - Add `packageVariableMetadata` property

**Transformation:**
- `transformer/builder/PostgresCodeBuilder.java`
  - Add `PackageVariableContext` inner class
  - Add `isInAssignmentTarget` flag
  - Add package variable helper methods
  - Populate context from TransformationContext

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

**Context:**
- `transformer/context/TransformationContext.java` - Add package variable lookup methods
- `transformer/context/TransformationIndices.java` - Add package variable index

**Frontend:**
- `orchestration.html` - Add Step 26 row
- `orchestration-service.js` - Add handlers

---

## REST API Endpoints

**Pattern:** `/api/jobs/{database}/{feature}/{action}`

```
# Oracle Extraction
POST /api/jobs/oracle/package-variable/extract

# PostgreSQL Helper Creation
POST /api/jobs/postgres/package-variable-helper/create

# PostgreSQL Helper Verification
POST /api/jobs/postgres/package-variable-helper-verification/verify
```

---

## Testing Strategy

### Unit Tests

**Extraction Tests:**
- `OraclePackageVariableExtractionTest.java`
  - Test parsing of variable declarations
  - Test CONSTANT keyword detection
  - Test default value extraction
  - Test multiple variables in same package

**Transformation Tests:**
- `PostgresPlSqlPackageVariableTransformationTest.java`
  - Test getter transformation (RHS)
  - Test setter transformation (LHS)
  - Test mixed getters in complex expressions
  - Test initialization call injection
  - Test constants (if inlined)

**Helper Generation Tests:**
- `PostgresPackageVariableHelperCreationTest.java`
  - Test initialization function generation
  - Test getter/setter generation for each type
  - Test multiple variables in same package

### Integration Tests

**PostgreSQL Validation Tests:**
- `PostgresPlSqlPackageVariableValidationTest.java`
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

**Helper Functions (auto-generated):**
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
- ✅ Oracle package variable extraction working
- ✅ PostgreSQL helper generation working
- ✅ Getter transformation working (RHS references)
- ✅ Setter transformation working (LHS assignments)
- ✅ Initialization injection working
- ✅ Session-level state verified in PostgreSQL
- ✅ Multiple calls preserve state within session
- ✅ Multiple sessions properly isolated
- ✅ Zero regressions in existing tests (882+ tests still passing)

**Coverage Estimate:** +10-20% of package functions (many depend on package variables)

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
- **PostgreSQL Docs:** [set_config and current_setting](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADMIN-SET)
- **Oracle Docs:** [PL/SQL Packages](https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/plsql-packages.html)

---

## Implementation Checklist

### Data Model
- [ ] Create `PackageVariableMetadata.java`

### Oracle Extraction
- [ ] Create `OraclePackageVariableExtractionJob.java`
- [ ] Implement variable parsing logic (regex or ANTLR)
- [ ] Add to JobRegistry

### State Management
- [ ] Extend `StateService` with `packageVariableMetadata` property
- [ ] Update state getters/setters

### PostgreSQL Helper Generation
- [ ] Create `PostgresPackageVariableHelperCreationJob.java`
- [ ] Implement initialization function template
- [ ] Implement getter function template
- [ ] Implement setter function template
- [ ] Create `PackageVariableHelperCreationResult.java`
- [ ] Add to JobRegistry

### Transformation Context
- [ ] Add `PackageVariableContext` inner class to `PostgresCodeBuilder`
- [ ] Extend `TransformationContext` with package variable lookup methods
- [ ] Extend `TransformationIndices` with package variable index
- [ ] Populate from `StateService` metadata

### Visitor Modifications
- [ ] Add `isInAssignmentTarget` flag to `PostgresCodeBuilder`
- [ ] Add helper methods to `PostgresCodeBuilder` (isPackageVariable, transformToGetter, parseReference)
- [ ] Modify `VisitAssignment_statement.java` (flag protection + setter detection)
- [ ] Modify `VisitGeneralElement.java` (package variable check + getter transformation)
- [ ] Modify `VisitFunctionBody.java` (inject initialization call)
- [ ] Modify `VisitProcedureBody.java` (inject initialization call)

### Frontend Integration
- [ ] Add Step 26 row to `orchestration.html`
- [ ] Add handlers to `orchestration-service.js`
- [ ] Add API fetch calls

### Testing
- [ ] Unit tests: Extraction job
- [ ] Unit tests: Helper generation
- [ ] Unit tests: Getter transformation
- [ ] Unit tests: Setter transformation
- [ ] Integration tests: PostgreSQL validation (Testcontainers)
- [ ] Verify zero regressions (all 882+ tests still passing)

### Documentation
- [ ] Update `TRANSFORMATION.md` with Phase 6 status
- [ ] Update `CLAUDE.md` with Step 26 status
- [ ] Update `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md` (link to package variables)

---

**Ready to implement Phase 1!** 🚀
