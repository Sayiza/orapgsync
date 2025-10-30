# PL/SQL Exception Handling: Oracle vs PostgreSQL Analysis

**Date:** 2025-10-30
**Purpose:** Analyze differences and challenges before implementing exception handling transformation

---

## Overview

Exception handling is one of the most critical features for PL/SQL to PL/pgSQL transformation. While both Oracle and PostgreSQL use similar syntax (`EXCEPTION WHEN ... THEN`), there are significant differences in exception names, error codes, and error raising mechanisms that must be carefully handled.

---

## Syntax Comparison

### Basic Exception Handling

**Oracle:**
```sql
BEGIN
  -- statements
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    DBMS_OUTPUT.PUT_LINE('No data found');
  WHEN TOO_MANY_ROWS THEN
    DBMS_OUTPUT.PUT_LINE('Too many rows');
  WHEN OTHERS THEN
    DBMS_OUTPUT.PUT_LINE('Error: ' || SQLERRM);
END;
```

**PostgreSQL:**
```sql
BEGIN
  -- statements
EXCEPTION
  WHEN no_data_found THEN
    RAISE NOTICE 'No data found';
  WHEN too_many_rows THEN
    RAISE NOTICE 'Too many rows';
  WHEN OTHERS THEN
    RAISE NOTICE 'Error: %', SQLERRM;
END;
```

**Key Differences:**
1. ✅ Block structure is identical (`EXCEPTION` keyword, `WHEN ... THEN` clauses)
2. ⚠️ Exception names have different casing (Oracle: UPPER_CASE, PostgreSQL: lower_case)
3. ⚠️ DBMS_OUTPUT → RAISE NOTICE (already handled by Oracle compatibility layer)

---

## Exception Name Mapping

### Standard Oracle Exceptions → PostgreSQL Equivalents

| Oracle Exception | PostgreSQL Exception | Oracle Code | PostgreSQL SQLSTATE | Notes |
|-----------------|---------------------|-------------|---------------------|-------|
| `NO_DATA_FOUND` | `no_data_found` | ORA-01403 | 02000 | SELECT INTO returns no rows |
| `TOO_MANY_ROWS` | `too_many_rows` | ORA-01422 | 21000 | SELECT INTO returns >1 row |
| `ZERO_DIVIDE` | `division_by_zero` | ORA-01476 | 22012 | Division by zero |
| `VALUE_ERROR` | `invalid_text_representation` | ORA-06502 | 22P02 | Type conversion error |
| `INVALID_NUMBER` | `invalid_text_representation` | ORA-01722 | 22P02 | Invalid number format |
| `DUP_VAL_ON_INDEX` | `unique_violation` | ORA-00001 | 23505 | Unique constraint violation |
| `INVALID_CURSOR` | `invalid_cursor_state` | ORA-01001 | 24000 | Invalid cursor operation |
| `CURSOR_ALREADY_OPEN` | `duplicate_cursor` | ORA-06511 | 42P03 | Cursor already open |
| `TIMEOUT_ON_RESOURCE` | `lock_not_available` | ORA-00051 | 55P03 | Lock timeout |
| `LOGIN_DENIED` | `invalid_authorization_specification` | ORA-01017 | 28000 | Login failed |
| `NOT_LOGGED_ON` | `connection_does_not_exist` | ORA-01012 | 08003 | Not connected |
| `PROGRAM_ERROR` | `internal_error` | ORA-06501 | XX000 | Internal error |
| `STORAGE_ERROR` | `out_of_memory` | ORA-06500 | 53200 | Out of memory |
| `ROWTYPE_MISMATCH` | `datatype_mismatch` | ORA-06504 | 42804 | Row type mismatch |
| `COLLECTION_IS_NULL` | `null_value_not_allowed` | ORA-06531 | 39004 | Collection is null |
| `SUBSCRIPT_BEYOND_COUNT` | `array_subscript_error` | ORA-06533 | 2202E | Array index out of bounds |
| `SUBSCRIPT_OUTSIDE_LIMIT` | `array_subscript_error` | ORA-06532 | 2202E | Array index invalid |

### Special Cases

**WHEN OTHERS:**
- Oracle: `WHEN OTHERS` catches all exceptions
- PostgreSQL: `WHEN OTHERS` also catches all exceptions
- ✅ **Direct mapping, no transformation needed**

**Multiple Exceptions in One Handler:**
- Oracle: `WHEN e1 OR e2 THEN ...`
- PostgreSQL: `WHEN e1 OR e2 THEN ...`
- ✅ **Direct mapping, syntax identical**

---

## Error Code Systems

### Oracle Error Codes (SQLCODE)

**Oracle uses numeric error codes:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    v_error_code := SQLCODE;      -- Returns -1403, -1422, etc.
    v_error_msg := SQLERRM;       -- Returns full error message
    v_error_msg2 := SQLERRM(v_error_code);  -- Error message for specific code
END;
```

**Characteristics:**
- Negative numbers for errors (e.g., -1403 for NO_DATA_FOUND)
- 0 for success
- +100 for NO_DATA_FOUND in some contexts
- User-defined errors: -20000 to -20999

### PostgreSQL Error Codes (SQLSTATE)

**PostgreSQL uses 5-character SQLSTATE codes:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    v_error_code := SQLSTATE;     -- Returns '02000', '21000', etc.
    v_error_msg := SQLERRM;       -- Returns error message
    -- No SQLERRM(code) function in PostgreSQL
END;
```

**Characteristics:**
- 5-character alphanumeric codes (e.g., '02000' for NO_DATA_FOUND)
- Class codes: First 2 chars indicate error class
- User-defined errors: 'P0001' to 'P0999' recommended

### Transformation Challenge: SQLCODE References

**Problem:** If Oracle code references SQLCODE, we must transform it to SQLSTATE or handle differently.

**Example Oracle Code:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1403 THEN
      -- Handle NO_DATA_FOUND
    ELSIF SQLCODE = -1422 THEN
      -- Handle TOO_MANY_ROWS
    END IF;
END;
```

**Possible PostgreSQL Transformations:**

**Option 1: Transform to SQLSTATE (cleaner):**
```sql
EXCEPTION
  WHEN OTHERS THEN
    IF SQLSTATE = '02000' THEN
      -- Handle NO_DATA_FOUND
    ELSIF SQLSTATE = '21000' THEN
      -- Handle TOO_MANY_ROWS
    END IF;
END;
```

**Option 2: Create SQLCODE compatibility function:**
```sql
CREATE OR REPLACE FUNCTION oracle_compat.sqlcode()
RETURNS integer
LANGUAGE plpgsql
AS $$
BEGIN
  -- Map SQLSTATE to Oracle error numbers
  RETURN CASE SQLSTATE
    WHEN '02000' THEN -1403  -- NO_DATA_FOUND
    WHEN '21000' THEN -1422  -- TOO_MANY_ROWS
    WHEN '22012' THEN -1476  -- ZERO_DIVIDE
    -- ... many more mappings
    ELSE -20000  -- Generic user error
  END;
END;
$$;
```

**Recommendation:** Use **Option 2** for better Oracle compatibility, but add comment explaining the transformation.

---

## RAISE and RAISE_APPLICATION_ERROR

### Raising Exceptions

**Oracle: RAISE statement**
```sql
DECLARE
  my_exception EXCEPTION;
BEGIN
  IF v_salary < 0 THEN
    RAISE my_exception;
  END IF;
EXCEPTION
  WHEN my_exception THEN
    DBMS_OUTPUT.PUT_LINE('Invalid salary');
END;
```

**PostgreSQL: RAISE statement**
```sql
DECLARE
  -- No explicit exception declaration needed
BEGIN
  IF v_salary < 0 THEN
    RAISE EXCEPTION 'my_exception';
  END IF;
EXCEPTION
  WHEN OTHERS THEN  -- Must catch by message or generic OTHERS
    RAISE NOTICE 'Invalid salary';
END;
```

**Key Difference:** PostgreSQL doesn't support named exception variables like Oracle. Must raise by message string.

### RAISE_APPLICATION_ERROR

**Oracle:**
```sql
IF v_salary < 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Salary cannot be negative');
END IF;
```

**PostgreSQL Equivalent:**
```sql
IF v_salary < 0 THEN
  RAISE EXCEPTION 'Salary cannot be negative'
    USING ERRCODE = 'P0001',
          HINT = 'Original Oracle error code: -20001';
END IF;
```

**Transformation Strategy:**
1. Extract error number and message from RAISE_APPLICATION_ERROR
2. Map error number to PostgreSQL ERRCODE (P0001-P0999 range)
3. Add original Oracle error code as HINT for debugging

**Mapping:**
- Oracle: -20000 to -20999 → PostgreSQL: 'P0001' to 'P0999' (simple offset mapping)
- Formula: `ERRCODE = 'P' || LPAD((oracle_code + 20000)::text, 4, '0')`
- Example: -20001 → 'P0001', -20055 → 'P0055', -20999 → 'P0999'

---

## User-Defined Exceptions

### Oracle Approach

**Oracle: Declare + PRAGMA EXCEPTION_INIT**
```sql
DECLARE
  invalid_salary EXCEPTION;
  PRAGMA EXCEPTION_INIT(invalid_salary, -20001);
BEGIN
  IF v_salary < 0 THEN
    RAISE invalid_salary;
  END IF;
EXCEPTION
  WHEN invalid_salary THEN
    DBMS_OUTPUT.PUT_LINE('Salary error occurred');
END;
```

**Characteristics:**
- Declare exception variable
- Link to error code via PRAGMA
- Catch by name

### PostgreSQL Approach

**PostgreSQL: Custom error codes**
```sql
BEGIN
  IF v_salary < 0 THEN
    RAISE EXCEPTION 'invalid_salary' USING ERRCODE = 'P0001';
  END IF;
EXCEPTION
  WHEN SQLSTATE 'P0001' THEN
    RAISE NOTICE 'Salary error occurred';
END;
```

**Characteristics:**
- No exception variables
- Catch by SQLSTATE code
- Must track error code separately

### Transformation Challenge

**Problem:** Oracle code uses named exceptions, but PostgreSQL uses error codes.

**Solution Strategy:**
1. **Track user-defined exceptions in visitor context:**
   - Store exception name → error code mapping during DECLARE section visit
   - Use mapping when transforming RAISE and EXCEPTION handlers

2. **Transform declarations:**
   - `invalid_salary EXCEPTION` → Skip declaration (add comment)
   - `PRAGMA EXCEPTION_INIT(invalid_salary, -20001)` → Store mapping

3. **Transform RAISE:**
   - `RAISE invalid_salary` → `RAISE EXCEPTION 'invalid_salary' USING ERRCODE = 'P0001'`

4. **Transform handlers:**
   - `WHEN invalid_salary THEN` → `WHEN SQLSTATE 'P0001' THEN`

**Implementation Note:** Requires adding exception mapping context to PostgresCodeBuilder (similar to loop record variables stack).

---

## Re-Raising Exceptions

**Oracle:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    log_error(SQLERRM);
    RAISE;  -- Re-raise current exception
END;
```

**PostgreSQL:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    PERFORM log_error(SQLERRM);
    RAISE;  -- Re-raise current exception
END;
```

✅ **Direct mapping, syntax identical**

---

## Exception Propagation

**Oracle:**
- Exceptions propagate up call stack
- Unhandled exceptions terminate function and return to caller
- Caller can catch with EXCEPTION block

**PostgreSQL:**
- ✅ Same behavior - exceptions propagate up call stack
- ✅ Same termination behavior
- ✅ Same caller handling

✅ **No transformation needed, behavior identical**

---

## SQLERRM Function

**Oracle:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    v_msg := SQLERRM;           -- Current error message
    v_msg2 := SQLERRM(-1403);   -- Error message for specific code
END;
```

**PostgreSQL:**
```sql
EXCEPTION
  WHEN OTHERS THEN
    v_msg := SQLERRM;           -- Current error message
    -- No SQLERRM(code) equivalent
END;
```

**Transformation:**
- `SQLERRM` (no args) → `SQLERRM` ✅
- `SQLERRM(code)` → Not directly supported ⚠️
  - Option 1: Create compatibility function with hardcoded messages
  - Option 2: Comment out and warn user
  - **Recommendation:** Option 2 (rare usage, maintenance burden not worth it)

---

## GET DIAGNOSTICS (PostgreSQL-specific)

PostgreSQL has GET DIAGNOSTICS for detailed exception info (no Oracle equivalent):

```sql
EXCEPTION
  WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS
      v_sqlstate = RETURNED_SQLSTATE,
      v_message = MESSAGE_TEXT,
      v_context = PG_EXCEPTION_CONTEXT;
END;
```

**Not needed for Oracle→PostgreSQL transformation** (Oracle doesn't have this).

---

## Implementation Challenges Summary

### 1. **Exception Name Mapping** (MEDIUM DIFFICULTY)

**Challenge:** Case conversion (UPPER → lower) for 20+ standard exceptions

**Solution:**
- Create static mapping table in `VisitException_handler.java`
- Map during transformation
- Handle unknown exceptions as lowercase conversion

**Estimated Effort:** 2-3 hours

### 2. **SQLCODE → SQLSTATE Transformation** (MEDIUM DIFFICULTY)

**Challenge:** Replace SQLCODE references with SQLSTATE or compatibility function

**Solution:**
- Detect SQLCODE in expressions
- Transform numeric comparisons to SQLSTATE string comparisons
- Create oracle_compat.sqlcode() function for complex cases

**Estimated Effort:** 3-4 hours

### 3. **RAISE_APPLICATION_ERROR Transformation** (LOW DIFFICULTY)

**Challenge:** Transform to RAISE EXCEPTION with ERRCODE

**Solution:**
- Parse error number and message from function call
- Generate RAISE EXCEPTION with mapped ERRCODE
- Add HINT with original error number

**Estimated Effort:** 2 hours

### 4. **User-Defined Exceptions** (HIGH DIFFICULTY)

**Challenge:** No exception variables in PostgreSQL

**Solution:**
- Add exception mapping context to PostgresCodeBuilder
- Track PRAGMA EXCEPTION_INIT mappings
- Transform RAISE and WHEN clauses using mapping

**Estimated Effort:** 4-6 hours

### 5. **WHEN OTHERS and Standard Handlers** (LOW DIFFICULTY)

**Challenge:** Minimal - mostly direct mapping

**Solution:**
- Direct transformation with exception name lowercasing
- Handle WHEN OTHERS as-is

**Estimated Effort:** 1-2 hours

### 6. **SQLERRM(code) Function** (LOW DIFFICULTY)

**Challenge:** No PostgreSQL equivalent

**Solution:**
- Detect usage and add warning comment
- Transform no-arg SQLERRM as-is

**Estimated Effort:** 1 hour

---

## Recommended Implementation Phases

### Phase 1: Basic Exception Handling (4-6 hours)
**Scope:**
- EXCEPTION block structure
- WHEN OTHERS handler
- Standard exception name mapping (NO_DATA_FOUND, TOO_MANY_ROWS, etc.)
- RAISE (simple, no named exceptions)
- SQLERRM (no-arg)

**Deliverable:** Handle 60-70% of real-world exception handling patterns

### Phase 2: SQLCODE and RAISE_APPLICATION_ERROR (4-5 hours)
**Scope:**
- SQLCODE → SQLSTATE transformation
- Create oracle_compat.sqlcode() function
- RAISE_APPLICATION_ERROR → RAISE EXCEPTION with ERRCODE

**Deliverable:** Handle 80-85% of real-world exception handling patterns

### Phase 3: User-Defined Exceptions (5-7 hours)
**Scope:**
- Exception variable declarations
- PRAGMA EXCEPTION_INIT tracking
- Named exception RAISE transformation
- Named exception handler transformation

**Deliverable:** Handle 90-95% of real-world exception handling patterns

### Phase 4: Edge Cases (2-3 hours)
**Scope:**
- SQLERRM(code) detection and warnings
- Multiple exceptions in one handler (OR)
- Nested exception handlers
- Exception re-raising

**Deliverable:** Handle 95%+ of real-world exception handling patterns

---

## Total Estimated Effort

**Total Time:** 15-21 hours (2-3 days of focused work)

**Test Development:** 4-6 hours (comprehensive test suite)

**Documentation:** 2-3 hours

**Grand Total:** 21-30 hours

---

## Risk Assessment

### Low Risk ✅
- Basic EXCEPTION block structure (syntax identical)
- WHEN OTHERS handler (syntax identical)
- Standard exception name mapping (straightforward table lookup)
- RAISE without arguments (syntax identical)

### Medium Risk ⚠️
- SQLCODE transformation (requires careful analysis of usage context)
- RAISE_APPLICATION_ERROR (multiple transformation patterns)
- Exception name case conversion (must handle all standard exceptions)

### High Risk 🔴
- User-defined exceptions (requires complex state tracking across DECLARE and EXCEPTION sections)
- PRAGMA EXCEPTION_INIT (no PostgreSQL equivalent, must track separately)
- Named exception RAISE (must map to error codes)

---

## Recommended Approach

### Start Simple, Iterate

1. **Phase 1:** Implement basic exception handling (WHEN OTHERS, standard exceptions)
   - Validate with simple test cases
   - Ensure no regressions (860 tests must still pass)

2. **Phase 2:** Add SQLCODE and RAISE_APPLICATION_ERROR support
   - Build on stable Phase 1 foundation
   - Add compatibility function to oracle_compat schema

3. **Phase 3:** Tackle user-defined exceptions (most complex)
   - Requires architectural changes to PostgresCodeBuilder
   - Add exception mapping context (similar to loop variables)
   - Extensive testing required

4. **Phase 4:** Polish and edge cases
   - Handle less common patterns
   - Add warnings for unsupported features (SQLERRM(code))

### Success Criteria

- ✅ All 860 existing tests pass (no regressions)
- ✅ 15+ new exception handling tests pass
- ✅ Phase 1 alone provides 60-70% coverage (+5-8% overall)
- ✅ Phase 1-2 combined provides 80-85% coverage (+10-12% overall)
- ✅ Phase 1-3 combined provides 90-95% coverage (+15-18% overall)

---

## Example Transformation (End-to-End)

### Oracle Code

```sql
CREATE OR REPLACE FUNCTION calculate_bonus(p_emp_id NUMBER) RETURN NUMBER IS
  v_salary NUMBER;
  v_bonus NUMBER;
  invalid_salary EXCEPTION;
  PRAGMA EXCEPTION_INIT(invalid_salary, -20001);
BEGIN
  SELECT salary INTO v_salary FROM employees WHERE emp_id = p_emp_id;

  IF v_salary < 0 THEN
    RAISE_APPLICATION_ERROR(-20001, 'Salary cannot be negative');
  END IF;

  v_bonus := v_salary * 0.10;
  RETURN v_bonus;

EXCEPTION
  WHEN NO_DATA_FOUND THEN
    DBMS_OUTPUT.PUT_LINE('Employee not found: ' || p_emp_id);
    RETURN 0;
  WHEN invalid_salary THEN
    DBMS_OUTPUT.PUT_LINE('Invalid salary for employee: ' || p_emp_id);
    RETURN 0;
  WHEN OTHERS THEN
    DBMS_OUTPUT.PUT_LINE('Error: ' || SQLERRM);
    RAISE;
END calculate_bonus;
```

### Transformed PostgreSQL Code

```sql
CREATE OR REPLACE FUNCTION hr.calculate_bonus(p_emp_id numeric)
RETURNS numeric
LANGUAGE plpgsql
AS $$
DECLARE
  v_salary numeric;
  v_bonus numeric;
  -- invalid_salary EXCEPTION;  -- Transformed to SQLSTATE 'P0001'
  -- PRAGMA EXCEPTION_INIT(invalid_salary, -20001);  -- Tracked internally
BEGIN
  SELECT salary INTO v_salary FROM hr.employees WHERE emp_id = p_emp_id;

  IF v_salary < 0 THEN
    RAISE EXCEPTION 'Salary cannot be negative'
      USING ERRCODE = 'P0001',
            HINT = 'Original Oracle error code: -20001';
  END IF;

  v_bonus := v_salary * 0.10;
  RETURN v_bonus;

EXCEPTION
  WHEN no_data_found THEN
    PERFORM oracle_compat.dbms_output__put_line('Employee not found: ' || p_emp_id);
    RETURN 0;
  WHEN SQLSTATE 'P0001' THEN  -- Maps to invalid_salary
    PERFORM oracle_compat.dbms_output__put_line('Invalid salary for employee: ' || p_emp_id);
    RETURN 0;
  WHEN OTHERS THEN
    PERFORM oracle_compat.dbms_output__put_line('Error: ' || SQLERRM);
    RAISE;
END;$$;
```

---

## Conclusion

Exception handling transformation is feasible but requires careful phased implementation:

1. **Phase 1 (Basic):** Straightforward, low risk, high value (+5-8% coverage)
2. **Phase 2 (SQLCODE):** Medium complexity, medium risk, good value (+5% coverage)
3. **Phase 3 (User-Defined):** Complex, high risk, moderate value (+5-7% coverage)

**Recommendation:** Start with Phase 1 to deliver immediate value, then assess user needs before proceeding to Phases 2-3.

**Total Coverage Gain (All Phases):** +15-18% (70-84% → 85-95%+)
