# PL/SQL OUT Parameter Consistency Plan

**Status:** 🔴 **CRITICAL INCONSISTENCY FOUND** - Requires immediate attention
**Date:** 2025-10-29
**Issue:** Stub generation and transformation produce incompatible signatures

---

## Problem Statement

### Critical Issue: Incompatible Signatures

**Stub Generation** (`PostgresFunctionStubCreationJob`):
```sql
-- Oracle PROCEDURE with OUT parameter
CREATE OR REPLACE PROCEDURE divide_numbers
  (p_dividend IN NUMBER, p_divisor IN NUMBER, p_quotient OUT NUMBER)

-- PostgreSQL STUB (current implementation)
CREATE OR REPLACE PROCEDURE hr.divide_numbers(
  IN p_dividend numeric,
  OUT p_divisor numeric,
  OUT p_quotient numeric
) AS $$
BEGIN
  -- Stub implementation
END;
$$ LANGUAGE plpgsql;
```

**Transformation** (`VisitProcedureBody`):
```sql
-- PostgreSQL TRANSFORMED (current implementation)
CREATE OR REPLACE FUNCTION hr.divide_numbers(
  p_dividend numeric,
  p_divisor numeric,
  p_quotient OUT numeric
)
RETURNS numeric
LANGUAGE plpgsql
AS $$
BEGIN
  p_quotient := TRUNC(p_dividend / p_divisor);
END;
$$;
```

**Problem:** You cannot `CREATE OR REPLACE` a PROCEDURE with a FUNCTION (or vice versa) in PostgreSQL!

This breaks the entire stub architecture because:
1. Dependencies on the stub will break when replaced
2. `CREATE OR REPLACE` will fail with: `ERROR: cannot change routine kind`

---

## Current State Analysis

### 1. Parameter Syntax Inconsistency

**Stub Generator** (`PostgresFunctionStubCreationJob:298-349`):
- Format: `MODE param_name type`
- Example: `INOUT p_counter numeric`, `OUT p_result numeric`

**Transformer** (`VisitParameter.java:70-84`):
- Format: `param_name MODE type`
- Example: `p_counter INOUT numeric`, `p_result OUT numeric`

**PostgreSQL Standard:** Both are valid! PostgreSQL allows either order.

**Recommendation:** Choose ONE consistent format across the codebase.

### 2. PROCEDURE vs FUNCTION Decision

**PostgreSQL 11+ Differences:**

| Aspect | PROCEDURE | FUNCTION |
|--------|-----------|----------|
| Created with | `CREATE PROCEDURE` | `CREATE FUNCTION` |
| Returns value? | No (void) | Yes (RETURNS clause) |
| OUT parameters? | ✅ Yes | ✅ Yes |
| Called with | `CALL proc()` | `SELECT func()` or `PERFORM func()` |
| Transactions | Can use COMMIT/ROLLBACK | Cannot use COMMIT/ROLLBACK |
| Replaceability | Cannot replace with FUNCTION | Cannot replace with PROCEDURE |

**Oracle Procedures:**
- Don't have explicit RETURN type
- Can have OUT/INOUT parameters
- Are called as statements: `procedure_name(args);`

**Current Implementation:**
- Stubs: Creates `PROCEDURE` objects (line 245)
- Transformer: Creates `FUNCTION` objects with `RETURNS void/type/RECORD`

**Result:** 🔴 **INCOMPATIBLE!** Cannot replace stub with transformation.

### 3. RETURNS Clause for OUT Parameters

**Current Stub Generator Behavior:**
```sql
-- Procedure with OUT parameter (NO RETURNS clause)
CREATE OR REPLACE PROCEDURE hr.proc(OUT p_result numeric) AS $$
```

**Current Transformer Behavior:**
```sql
-- Single OUT → RETURNS type
CREATE OR REPLACE FUNCTION hr.proc(p_result OUT numeric)
RETURNS numeric

-- Multiple OUT → RETURNS RECORD
CREATE OR REPLACE FUNCTION hr.proc(p_out1 OUT numeric, p_out2 OUT text)
RETURNS RECORD
```

**PostgreSQL Behavior:**
- Functions with OUT parameters automatically have a return type
- Single OUT → implicitly RETURNS that type
- Multiple OUT → implicitly RETURNS RECORD
- RETURNS clause is **optional but recommended** for clarity

### 4. Calling Functions/Procedures with OUT Parameters

**PostgreSQL Calling Conventions:**

```sql
-- Single OUT parameter (RETURNS type)
SELECT divide_numbers(100, 3) AS quotient;
-- Returns: 33

-- Multiple OUT parameters (RETURNS RECORD)
SELECT * FROM calculate_stats(5, 10);
-- Returns: (15, 50) with column names p_sum, p_product

-- INOUT parameters (RETURNS RECORD)
SELECT * FROM adjust_values(10, 5);
-- Returns: (6, 60) with column names p_counter, p_result

-- PROCEDURE call (different syntax!)
CALL my_procedure(param1, param2);
```

**Key Difference:**
- `FUNCTION`: Called with `SELECT` or `PERFORM` (PL/pgSQL)
- `PROCEDURE`: Called with `CALL`

### 5. PostgreSQL Version Compatibility

**PostgreSQL 11+ (Current Target):**
- ✅ `CREATE PROCEDURE` supported (introduced in PG 11)
- ✅ Both PROCEDURE and FUNCTION support OUT parameters
- ✅ Transaction control in PROCEDURE (COMMIT/ROLLBACK)

**Pre-PostgreSQL 11:**
- ❌ No `CREATE PROCEDURE`
- ✅ Only `CREATE FUNCTION` with `RETURNS void` for procedure-like behavior

**Our Target:** PostgreSQL 11+ (based on project dependencies)

---

## Root Cause Analysis

### Why This Happened

1. **Stub Generator** was written first, following PostgreSQL 11+ PROCEDURE syntax
2. **Transformer** was written later, following the "FUNCTION with RETURNS void" pattern (pre-PG 11 style)
3. No integration testing between stub creation and transformation
4. Different authors/sessions without cross-checking

### Impact Assessment

**Severity:** 🔴 **CRITICAL**

**Affected Areas:**
1. ✅ Standalone functions with no OUT parameters - **WORKS** (both use FUNCTION)
2. 🔴 Standalone procedures with no OUT parameters - **BROKEN** (stub=PROCEDURE, transform=FUNCTION)
3. 🔴 Procedures with OUT parameters - **BROKEN** (stub=PROCEDURE, transform=FUNCTION)
4. 🔴 Functions with OUT parameters - **MAY WORK** (both FUNCTION, but parameter order differs)
5. 🔴 Call statements to procedures - **BROKEN** (stub expects CALL, but PERFORM generated)

**Estimated Breakage:** ~50-70% of real-world Oracle procedures

---

## Proposed Solution

### Decision Matrix

| Approach | Pros | Cons | Recommendation |
|----------|------|------|----------------|
| **Option 1: Always use FUNCTION** | ✅ Works on all PG versions<br/>✅ Simpler call syntax (SELECT)<br/>✅ Matches existing transformer | ❌ Cannot use COMMIT/ROLLBACK<br/>❌ Less semantic match to Oracle PROCEDURE | ⭐ **RECOMMENDED** |
| **Option 2: Use PROCEDURE for procedures** | ✅ Semantic match to Oracle<br/>✅ Transaction control support | ❌ Requires PG 11+<br/>❌ Different call syntax (CALL vs SELECT)<br/>❌ More complex transformation | Not recommended |
| **Option 3: Hybrid approach** | ✅ Best of both worlds | ❌ Complex to implement<br/>❌ Confusing for users | Not recommended |

**Chosen Approach:** **Option 1 - Always use FUNCTION**

**Rationale:**
1. PostgreSQL community best practice for Oracle migrations
2. Simpler call syntax (no CALL vs SELECT/PERFORM distinction)
3. Works across all PostgreSQL versions
4. Already implemented in transformer (less work)
5. Transaction control rarely used in Oracle procedures during migration

---

## Implementation Plan

### Phase 1: Fix Stub Generator (Immediate - High Priority)

**Goal:** Make stub generator consistent with transformer

**Changes Required:**

#### 1.1 Change PROCEDURE to FUNCTION
**File:** `PostgresFunctionStubCreationJob.java:241-264`

```java
// BEFORE (lines 244-264)
sql.append(function.isFunction() ? "FUNCTION " : "PROCEDURE ");
// ...
if (function.isFunction()) {
    sql.append(" RETURNS ");
    sql.append(mapReturnType(function));
}

// AFTER
sql.append("FUNCTION ");  // Always FUNCTION
// ...
// Always add RETURNS clause
sql.append(" RETURNS ");
if (function.isFunction()) {
    sql.append(mapReturnType(function));
} else {
    // Procedure: calculate RETURNS based on OUT parameters
    sql.append(calculateProcedureReturnsClause(function));
}
```

#### 1.2 Add Method: calculateProcedureReturnsClause()
**File:** `PostgresFunctionStubCreationJob.java` (new method)

```java
/**
 * Calculates RETURNS clause for Oracle procedures based on OUT parameters.
 *
 * Rules:
 * - No OUT/INOUT → RETURNS void
 * - Single OUT/INOUT → RETURNS <type>
 * - Multiple OUT/INOUT → RETURNS RECORD
 */
private String calculateProcedureReturnsClause(FunctionMetadata function) {
    // Count OUT and INOUT parameters
    int outParamCount = 0;
    String singleOutParamType = null;

    for (FunctionParameter param : function.getParameters()) {
        String inOut = param.getInOut();
        if (inOut != null && (inOut.contains("OUT"))) {
            outParamCount++;
            if (outParamCount == 1) {
                // Store type of first OUT parameter
                if (param.isCustomDataType()) {
                    // ... same logic as generateParameterDefinition
                } else {
                    singleOutParamType = TypeConverter.toPostgre(param.getDataType());
                }
            }
        }
    }

    if (outParamCount == 0) {
        return "void";
    } else if (outParamCount == 1) {
        return singleOutParamType != null ? singleOutParamType : "text";
    } else {
        return "RECORD";
    }
}
```

#### 1.3 Fix Parameter Order Consistency
**File:** `PostgresFunctionStubCreationJob.java:298-349`

**Decision:** Use `param_name MODE type` format (matches transformer)

```java
// BEFORE (lines 301-313)
def.append("INOUT ");  // or "OUT " or "IN "
def.append(normalizedParamName);
def.append(" ");
def.append(postgresType);

// AFTER
def.append(normalizedParamName);
def.append(" ");
if (inOut.contains("IN") && inOut.contains("OUT")) {
    def.append("INOUT ");
} else if (inOut.contains("OUT")) {
    def.append("OUT ");
} else {
    // IN is default, don't add keyword
}
def.append(postgresType);
```

**Result Format:** `p_quotient OUT numeric` (consistent with transformer)

### Phase 2: Update Call Statement Handling (Medium Priority)

**Goal:** Ensure call statements work with FUNCTION syntax

**Current State:**
- `VisitCall_statement.java` generates `PERFORM schema.function(args);`
- This works for functions returning void
- This works for functions with OUT parameters (return value ignored)

**Required Changes:** ✅ **NONE!** Current implementation already correct.

**Verification:**
- Procedures without OUT params → `PERFORM func()` works (returns void)
- Procedures with OUT params → `PERFORM func()` works (ignores return value)
- Functions in assignments → `v := func()` already works (different code path)

### Phase 3: Documentation Updates (Medium Priority)

**Goal:** Update all documentation to reflect FUNCTION-only approach

**Files to Update:**
1. `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md`
   - Section: "What Works Now" - clarify FUNCTION vs PROCEDURE
   - Add note about Oracle PROCEDURE → PostgreSQL FUNCTION

2. `TRANSFORMATION.md`
   - Add section on PROCEDURE transformation strategy

3. `CLAUDE.md`
   - Update migration status for procedures

### Phase 4: Testing (High Priority)

**Goal:** Verify stub→transformation replacement works

#### 4.1 Integration Test: Stub Replacement

**File:** `src/test/java/.../PostgresStubReplacementIntegrationTest.java` (new)

```java
/**
 * Tests that transformed functions can replace stubs without breaking dependencies.
 */
@Test
void procedureStubCanBeReplacedWithTransformation() throws SQLException {
    // Step 1: Create stub
    FunctionMetadata function = createTestProcedureMetadata();
    String stubSql = stubGenerator.generateCreateFunctionStubSQL(function);
    executeUpdate(stubSql);

    // Step 2: Create dependent view
    executeUpdate("CREATE VIEW test_view AS SELECT hr.divide_numbers(100, 3) AS result");

    // Step 3: Replace stub with transformation
    String oracleProcedure = """
        PROCEDURE divide_numbers(p_dividend IN NUMBER, p_divisor IN NUMBER, p_quotient OUT NUMBER)
        IS
        BEGIN
            p_quotient := TRUNC(p_dividend / p_divisor);
        END;
        """;
    TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

    // Step 4: Replace should succeed
    assertDoesNotThrow(() -> executeUpdate(result.getPostgresSql()));

    // Step 5: Dependent view should still work
    List<Map<String, Object>> rows = executeQuery("SELECT * FROM test_view");
    assertEquals(33, ((Number) rows.get(0).get("result")).intValue());
}
```

#### 4.2 Test Matrix

Create tests for all combinations:

| Test Case | IN Params | OUT Params | INOUT Params | Expected RETURNS | Test Status |
|-----------|-----------|------------|--------------|------------------|-------------|
| Pure procedure | 2 | 0 | 0 | void | ✅ Add |
| Single OUT | 2 | 1 | 0 | numeric | ✅ Add |
| Multiple OUT | 2 | 2 | 0 | RECORD | ✅ Exists |
| Single INOUT | 1 | 0 | 1 | numeric | ✅ Add |
| Mixed OUT/INOUT | 1 | 1 | 1 | RECORD | ✅ Exists |
| Pure function | 2 | 0 | 0 | numeric (explicit) | ✅ Exists |

---

## PostgreSQL OUT Parameter Reference

### Official PostgreSQL Behavior

**From PostgreSQL Documentation (v11+):**

```sql
-- Functions with OUT parameters
CREATE FUNCTION sum_n_product(x int, y int, OUT sum int, OUT product int) AS $$
BEGIN
    sum := x + y;
    product := x * y;
END;
$$ LANGUAGE plpgsql;

-- Equivalent explicit RETURNS
CREATE FUNCTION sum_n_product(x int, y int, OUT sum int, OUT product int)
RETURNS RECORD AS $$
...

-- Calling functions with OUT parameters
SELECT * FROM sum_n_product(5, 10);
-- Returns: (15, 50)

-- Single OUT parameter
CREATE FUNCTION divide(x int, y int, OUT quotient int) AS $$
BEGIN
    quotient := x / y;
END;
$$ LANGUAGE plpgsql;

SELECT divide(100, 3);
-- Returns: 33
```

**Key Points:**
1. OUT parameters automatically add to RETURNS clause
2. Single OUT → implicit RETURNS <type>
3. Multiple OUT → implicit RETURNS RECORD
4. Can be called with `SELECT func()` or `SELECT * FROM func()`
5. Return values have same names as OUT parameters

### Common Pitfalls

**Pitfall 1: Mixing PROCEDURE and FUNCTION**
```sql
-- Create stub as PROCEDURE
CREATE PROCEDURE my_proc(OUT result int) ...

-- Try to replace with FUNCTION
CREATE OR REPLACE FUNCTION my_proc(result OUT int) ...
-- ERROR: cannot change routine kind
-- DETAIL: "my_proc" is a procedure
```

**Pitfall 2: Column List with OUT Parameters**
```sql
-- Function with OUT parameters
CREATE FUNCTION get_stats(OUT sum int, OUT avg int) ...

-- WRONG: Specify column list
SELECT * FROM get_stats() AS (sum int, avg int);
-- ERROR: a column definition list is redundant for a function with OUT parameters

-- RIGHT: Let OUT parameters define columns
SELECT * FROM get_stats();
```

**Pitfall 3: Parameter Order**
```sql
-- Both valid, but must be consistent!
CREATE FUNCTION f1(OUT p_result int) ...  -- OK
CREATE FUNCTION f2(p_result OUT int) ...  -- OK
CREATE OR REPLACE FUNCTION f1(p_result OUT int) ...  -- MISMATCH! Signature change!
```

---

## Migration Impact

### Breaking Changes

**Version:** Next release (Step 25 completion)

**Impact:**
1. ✅ **Existing stubs will be replaced** with consistent FUNCTION syntax
2. ✅ **No user code changes required** (SELECT/CALL both work)
3. ⚠️ **Re-stub required** if stubs already created in old format

**Migration Path:**
1. Drop existing procedure stubs: `DROP PROCEDURE IF EXISTS ...`
2. Re-run stub creation job
3. Run transformation job
4. Verify dependencies intact

### Rollback Plan

If issues discovered:
1. Keep old stub generator code in version control
2. Document breaking change in release notes
3. Provide migration script to drop old stubs
4. Test with customer environments before GA release

---

## Timeline

**Immediate (Today):**
- ✅ Fix stub generator (Phase 1.1-1.3)
- ✅ Add calculateProcedureReturnsClause() method
- ✅ Update parameter order

**This Week:**
- ✅ Create integration tests (Phase 4.1-4.2)
- ✅ Update documentation (Phase 3)
- ✅ Test with real Oracle procedures

**Next Week:**
- ✅ Code review
- ✅ Merge to main
- ✅ Update STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md

---

## Open Questions

1. **Transaction Control:** Do we need to support COMMIT/ROLLBACK in procedures?
   - **Answer:** Rare in migrations, defer to future if needed

2. **Calling Convention:** Should we generate `CALL` or `PERFORM` in PL/pgSQL?
   - **Answer:** PERFORM (works for FUNCTION)

3. **Hybrid Functions:** Oracle functions with OUT parameters - how to handle?
   - **Answer:** Treat as multiple return values (RETURNS RECORD)

---

## References

- PostgreSQL 11 Procedures: https://www.postgresql.org/docs/11/sql-createprocedure.html
- PostgreSQL Functions with OUT: https://www.postgresql.org/docs/current/xfunc-sql.html#XFUNC-OUTPUT-PARAMETERS
- Oracle PL/SQL Procedures: https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/plsql-procedure.html

---

## Conclusion

**Current Status:** 🔴 Critical inconsistency found
**Proposed Solution:** Always use FUNCTION (with appropriate RETURNS clause)
**Implementation Effort:** ~4-6 hours
**Risk:** Low (well-defined problem, clear solution)
**Priority:** **HIGH** - Blocks production use of Step 25

**Next Action:** Implement Phase 1 changes to stub generator immediately.
