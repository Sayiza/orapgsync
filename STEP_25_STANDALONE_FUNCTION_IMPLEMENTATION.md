# Step 25: Standalone Function/Procedure Implementation

**Status:** 🔄 **PARTIALLY COMPLETE** - Infrastructure ✅, Simple functions ✅, Control flow ❌
**Date Completed:** Infrastructure: 2025-10-26 | Basic transformation: 2025-10-27
**Workflow Position:** Step 25 in orchestration sequence (after View Implementation)

---

## Overview

Step 25 transforms Oracle standalone functions and procedures (NOT package members) to PostgreSQL using PL/SQL→PL/pgSQL transformation via ANTLR-based direct AST transformation.

**Scope:**
- ✅ ONLY standalone functions/procedures (identified by `FunctionMetadata.isStandalone()`)
- ❌ Excludes package members (names contain `__`, handled in separate step)

**Current Capability:** Can transform **simple functions** with parameters, return types, and basic statements. Control flow (IF/LOOP/cursors/exceptions) not yet supported.

---

## Quick Status Summary

### ✅ What Works Now

**Infrastructure (100% Complete):**
- Oracle source extraction from ALL_SOURCE ✅
- ANTLR parsing (PlSqlParser) ✅
- Two-pass transformation architecture (TypeAnalysisVisitor + PostgresCodeBuilder) ✅
- PostgreSQL execution with error handling ✅
- Frontend UI with verification and creation buttons ✅
- Progress tracking and detailed result reporting ✅

**Transformation (Partial - ~20% of real-world functions):**
- Function/procedure signatures (name, parameters, return type) ✅
- BEGIN...END block structure ✅
- RETURN statements ✅
- Simple expressions and arithmetic ✅
- All SQL SELECT functionality (from view transformation - 662+ tests passing) ✅
- Function calls within PL/SQL ✅

**Example Working Function:**
```sql
-- Oracle
CREATE OR REPLACE FUNCTION add_tax(p_amount NUMBER) RETURN NUMBER IS
BEGIN
  RETURN p_amount * 1.08;
END;

-- ✅ Successfully transforms to PostgreSQL
CREATE OR REPLACE FUNCTION hr.add_tax(p_amount numeric) RETURNS numeric
LANGUAGE plpgsql
AS $$
BEGIN
  RETURN p_amount * 1.08;
END;
$$;
```

### ❌ What Doesn't Work Yet

**Missing PL/SQL Statement Visitors (~80% of real-world functions require these):**
1. Variable declarations (DECLARE section)
2. Variable assignments (`:=`)
3. IF/ELSIF/ELSE statements
4. LOOP/WHILE/FOR loops
5. SELECT INTO statements
6. Cursor operations
7. Exception handlers
8. RAISE statements

**Example Failing Function:**
```sql
-- Oracle
CREATE OR REPLACE FUNCTION get_employee_salary(p_emp_id NUMBER) RETURN NUMBER IS
  v_salary NUMBER;  -- ❌ Variable declaration not supported
BEGIN
  SELECT salary INTO v_salary  -- ❌ SELECT INTO not supported
  FROM employees WHERE employee_id = p_emp_id;

  IF v_salary > 100000 THEN  -- ❌ IF statement not supported
    v_salary := v_salary * 1.1;  -- ❌ Assignment not supported
  END IF;

  RETURN v_salary;
END;

-- ❌ Will be skipped with transformation error
```

### 🎯 Next Priority: Phase 2 Control Flow

**Goal:** Implement 15 missing visitor classes to support real-world functions

**Target:** 70% of functions working after implementing variables, assignments, IF, and SELECT INTO (~4 days of work)

See "Recommended Next Steps" below for detailed implementation plan.

---

## What Was Implemented

### 1. Frontend (Complete ✅)

#### HTML Row
**Location:** `src/main/resources/META-INF/resources/index.html` (lines 532-555)

**Structure:**
```html
<tr class="standalone-function-implementation-row">
  <td class="source-cell">Oracle: N/A - see Functions/Procedures above</td>
  <td class="target-cell">
    - Count badge
    - Refresh button (⟳) for verification
    - "Create Standalone Functions" action button
    - Collapsible results section
  </td>
</tr>
```

**Element IDs:**
- `oracle-standalone-function-implementation`
- `postgres-standalone-function-implementation`
- `postgres-standalone-function-implementation-results`
- `postgres-standalone-function-implementation-details`

#### JavaScript Handlers
**Location:** `src/main/resources/META-INF/resources/function-service.js` (lines 531-871)

**Functions Added (340 lines):**
1. `createPostgresStandaloneFunctionImplementation()` - Main creation handler
2. `pollStandaloneFunctionImplementationJobStatus()` - Creation job polling
3. `handleStandaloneFunctionImplementationJobComplete()` - Creation result handler
4. `displayStandaloneFunctionImplementationResults()` - Display creation results
5. `toggleStandaloneFunctionImplementationResults()` - Toggle result visibility
6. `verifyPostgresStandaloneFunctionImplementation()` - Verification handler
7. `pollStandaloneFunctionVerificationJobStatus()` - Verification job polling (separate from creation!)

**Key Pattern:** Separate polling handlers for creation vs verification due to different result types.

---

### 2. Backend (Complete ✅)

#### Data Model
**File:** `core/job/model/function/StandaloneFunctionImplementationResult.java`

**Properties:**
- `Map<String, FunctionMetadata> implementedFunctions`
- `Map<String, FunctionMetadata> skippedFunctions`
- `Map<String, ErrorInfo> errors`
- `int implementedCount`, `skippedCount`, `errorCount`
- `boolean isSuccessful()`

**Inner Class:** `ErrorInfo` (functionName, error, sql)

#### Verification Job
**File:** `function/job/PostgresStandaloneFunctionImplementationVerificationJob.java`

**Type:** `AbstractDatabaseExtractionJob<FunctionMetadata>`
**ExtractionType:** `"STANDALONE_FUNCTION_IMPLEMENTATION_VERIFICATION"`
**Returns:** `List<FunctionMetadata>`

**Features:**
- Queries PostgreSQL `pg_proc` for standalone functions
- Filters: `WHERE proname NOT LIKE '%__%'` (excludes package members)
- Analyzes function bodies to detect stubs vs implementations
- Stub detection: Looks for "stub implementation" comments, `RETURN NULL`, empty bodies

**SQL Query:**
```sql
SELECT n.nspname, p.proname, pg_get_functiondef(p.oid), p.prokind
FROM pg_proc p
JOIN pg_namespace n ON p.pronamespace = n.oid
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  AND n.nspname NOT LIKE 'pg_%'
  AND p.proname NOT LIKE '%__%'  -- Key filter for standalone only
```

**Current Behavior:** Fully functional, returns count of verified standalone functions.

#### Implementation Job
**File:** `function/job/PostgresStandaloneFunctionImplementationJob.java` (295 lines)

**Type:** `AbstractDatabaseWriteJob<StandaloneFunctionImplementationResult>`
**WriteOperationType:** `"STANDALONE_FUNCTION_IMPLEMENTATION"`
**Returns:** `StandaloneFunctionImplementationResult`

**Status:** ✅ **FULLY IMPLEMENTED** - Complete pipeline from Oracle extraction to PostgreSQL execution

**Implementation:**
1. **Oracle Source Extraction** ✅ (lines 222-270)
   ```java
   private String extractOracleFunctionSource(Connection oracleConn, FunctionMetadata function) {
       // Queries ALL_SOURCE for complete PL/SQL source code
       // Assembles multi-line source into single string
       // Handles FUNCTION vs PROCEDURE type distinction
   }
   ```

2. **Transformation Integration** ✅ (lines 152-176)
   ```java
   // Build TransformationIndices from StateService metadata
   TransformationIndices indices = metadataIndexBuilder.build(currentSchema);

   // Transform using unified TransformationService
   TransformationResult transformResult;
   if (function.isFunction()) {
       transformResult = transformationService.transformFunction(
           oracleSource, function.getSchema(), indices);
   } else {
       transformResult = transformationService.transformProcedure(
           oracleSource, function.getSchema(), indices);
   }
   ```

3. **PostgreSQL Execution** ✅ (lines 281-293)
   ```java
   private void executeInPostgres(Connection postgresConn, String pgSql,
                                  FunctionMetadata function,
                                  StandaloneFunctionImplementationResult result) {
       try (PreparedStatement ps = postgresConn.prepareStatement(pgSql)) {
           ps.execute();
           result.addImplementedFunction(function);
       } catch (SQLException e) {
           result.addError(function.getDisplayName(), errorMsg, pgSql);
       }
   }
   ```

4. **Error Handling & Progress Tracking** ✅
   - Transformation failures tracked as skipped with reason
   - PostgreSQL execution failures tracked as errors with SQL
   - Progress callback updates during processing

#### State Service Integration
**File:** `core/service/StateService.java`

**Added:**
- Import: `StandaloneFunctionImplementationResult`
- Property: `standaloneFunctionImplementationResult`
- Getter/setter methods
- Reset in `resetState()`

#### REST Endpoints
**File:** `function/rest/FunctionResource.java`

**Endpoints:**
```java
POST /api/functions/postgres/standalone-implementation/create
POST /api/functions/postgres/standalone-implementation/verify
```

**Summary Generator:**
```java
public static Map<String, Object> generateStandaloneFunctionImplementationSummary(
    StandaloneFunctionImplementationResult result)
```

Returns: `implementedCount`, `skippedCount`, `errorCount`, detailed maps of functions.

#### Job Result Handling
**File:** `core/job/rest/JobResource.java`

**Added:**
- Import: `StandaloneFunctionImplementationResult`
- Result handler (lines 266-274):
  ```java
  else if (result instanceof StandaloneFunctionImplementationResult) {
      // Maps result to summary, counts, isSuccessful
  }
  ```

---

## API Flow

### Verification Flow
```
User clicks ⟳ (refresh)
    ↓
POST /api/functions/postgres/standalone-implementation/verify
    ↓
PostgresStandaloneFunctionImplementationVerificationJob
    ↓
Queries PostgreSQL for standalone functions (excludes __)
    ↓
Returns List<FunctionMetadata>
    ↓
JobResource wraps with functionCount
    ↓
pollStandaloneFunctionVerificationJobStatus()
    ↓
Updates count badge (no detailed results)
```

### Creation Flow
```
User clicks "Create Standalone Functions"
    ↓
POST /api/functions/postgres/standalone-implementation/create
    ↓
PostgresStandaloneFunctionImplementationJob
    ↓
Filters Oracle functions to standalone only
    ↓
For each function: Skip (transformation not implemented)
    ↓
Returns StandaloneFunctionImplementationResult
    ↓
JobResource maps to summary
    ↓
pollStandaloneFunctionImplementationJobStatus()
    ↓
Displays: Implemented: 0, Skipped: X, Errors: 0
```

---

## Transformation Architecture

### Two-Pass Transformation Pipeline

Step 25 uses the unified transformation infrastructure (see `TRANSFORMATION.md` and `TYPE_INFERENCE_IMPLEMENTATION_PLAN.md`):

**Pipeline:**
```
Oracle PL/SQL → ANTLR Parser → Pass 1: TypeAnalysisVisitor → Pass 2: PostgresCodeBuilder → PostgreSQL PL/pgSQL
                     ↓                        ↓                           ↓                          ↓
                PlSqlParser              Type Cache                Static Visitors            CREATE OR REPLACE
```

**Pass 1: Type Inference** (`TypeAnalysisVisitor`)
- Walks AST to populate type cache for all expressions
- Tracks variable declarations in scope stack
- Resolves column types from metadata indices
- Caches types using token position keys

**Pass 2: Code Generation** (`PostgresCodeBuilder`)
- Walks AST using static visitor helpers
- Queries type cache for expression types
- Generates PostgreSQL PL/pgSQL syntax
- Uses TransformationIndices for metadata lookups

### TransformationService Integration

**File:** `transformer/service/TransformationService.java`

**Methods:**
- `transformFunction(oraclePlSql, schema, indices)` - Entry point for Oracle functions
- `transformProcedure(oraclePlSql, schema, indices)` - Entry point for Oracle procedures

**Architecture:**
1. Parse Oracle PL/SQL using ANTLR (`parseFunctionBody()` or `parseProcedureBody()`)
2. Run TypeAnalysisVisitor (populate type cache)
3. Create TransformationContext (schema + indices + TypeEvaluator)
4. Run PostgresCodeBuilder (generate CREATE OR REPLACE FUNCTION)
5. Return TransformationResult (success with SQL or failure with error)

**Note:** Function name and parameters extracted from AST, only schema required from metadata (consistent with SQL transformation).

### PL/SQL Visitor Implementation Status

**46 total Visit*.java files** in `transformer/builder/` package

#### ✅ Fully Implemented PL/SQL Visitors (6 core visitors)

1. **VisitFunctionBody.java** (122 lines)
   - Extracts function name from AST
   - Builds parameter list (delegates to VisitParameter)
   - Converts Oracle return type to PostgreSQL
   - Generates complete `CREATE OR REPLACE FUNCTION` statement
   - Structure: `CREATE OR REPLACE FUNCTION schema.name(params) RETURNS type LANGUAGE plpgsql AS $$ ... $$;`

2. **VisitProcedureBody.java** (114 lines)
   - Same as VisitFunctionBody but returns `void`
   - Structure: `CREATE OR REPLACE FUNCTION schema.name(params) RETURNS void LANGUAGE plpgsql AS $$ ... $$;`

3. **VisitBody.java** (60 lines)
   - Handles `BEGIN...END` block structure
   - Visits seq_of_statements (main body content)
   - Handles optional EXCEPTION block (delegates to exception_handler visitor)
   - Generates: `BEGIN\n  [statements]\n[EXCEPTION\n  handlers]\nEND;`

4. **VisitSeq_of_statements.java** (46 lines)
   - Processes statement sequence
   - Adds proper indentation (2 spaces)
   - Ensures semicolons after each statement

5. **VisitReturn_statement.java**
   - Transforms RETURN statements
   - Handles expression transformation in return value

6. **VisitParameter.java**
   - Extracts parameter name, mode (IN/OUT/INOUT), type
   - Converts Oracle types to PostgreSQL
   - Generates: `param_name IN/OUT/INOUT pg_type`

#### ✅ SQL Visitors (Reused from View Transformation)

All SELECT-related visitors work in PL/SQL context:
- Expression hierarchy (11 levels) - See TRANSFORMATION.md
- SELECT/FROM/WHERE/GROUP BY/HAVING/ORDER BY
- JOIN types (INNER, LEFT, RIGHT, FULL, CROSS)
- Functions (NVL, DECODE, TO_CHAR, SUBSTR, etc.)
- Subqueries, CTEs, set operations

#### ❌ Missing PL/SQL Control Flow Visitors

These are **NOT YET IMPLEMENTED** and will cause transformation failures:

1. **VisitIf_statement** - IF/ELSIF/ELSE/END IF
2. **VisitLoop_statement** - LOOP/END LOOP
3. **VisitWhile_loop_statement** - WHILE...LOOP/END LOOP
4. **VisitFor_loop_statement** - FOR i IN 1..10 LOOP/END LOOP
5. **VisitCursor_declaration** - CURSOR declarations in DECLARE section
6. **VisitCursor_loop_statement** - FOR rec IN cursor LOOP
7. **VisitException_handler** - WHEN exception_name THEN statements
8. **VisitAssignment_statement** - variable := expression
9. **VisitVariable_declaration** - DECLARE section variable declarations
10. **VisitSelect_into_statement** - SELECT...INTO variable
11. **VisitExit_statement** - EXIT/EXIT WHEN
12. **VisitRaise_statement** - RAISE/RAISE_APPLICATION_ERROR

**Impact:** Functions with these constructs will either:
- Fail to parse (if ANTLR can't handle the syntax)
- Generate incorrect PostgreSQL (if visitor falls through to default behavior)
- Be skipped with transformation error message

### Current Transformation Capability

**✅ Can Successfully Transform:**
- Functions with parameters (IN/OUT/INOUT)
- Functions with return types
- Procedures (RETURNS void)
- Simple expressions and arithmetic
- SELECT statements (full Oracle SQL support - see TRANSFORMATION.md)
- RETURN statements
- Function calls within PL/SQL

**❌ Cannot Yet Transform:**
- IF/ELSIF/ELSE conditional logic
- LOOP/WHILE/FOR loop constructs
- Cursor operations
- Exception handling blocks
- Variable declarations in DECLARE section
- Variable assignments (`:=`)
- SELECT INTO statements
- EXIT statements
- RAISE statements

**Result:** Only the **simplest functions** (e.g., mathematical calculations with parameters and RETURN) will transform successfully. Most real-world Oracle functions contain control flow and will fail transformation.

---

## Files Created (4 new files)

1. `core/job/model/function/StandaloneFunctionImplementationResult.java` (158 lines)
2. `function/job/PostgresStandaloneFunctionImplementationVerificationJob.java` (213 lines)
3. `function/job/PostgresStandaloneFunctionImplementationJob.java` (295 lines) - **Complete implementation with transformation**
4. `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md` (this file)

## Files Modified (5 files)

1. `src/main/resources/META-INF/resources/index.html` (+24 lines) - Step 25 UI
2. `src/main/resources/META-INF/resources/function-service.js` (+340 lines) - Step 25 handlers
3. `core/service/StateService.java` (+9 lines) - Result storage
4. `function/rest/FunctionResource.java` (+52 lines) - REST endpoints
5. `core/job/rest/JobResource.java` (+10 lines) - Result handling

## Files Used from Transformation Module (Existing Infrastructure)

**Reused from view transformation (no changes needed):**
1. `transformer/service/TransformationService.java` - Main transformation service with `transformFunction()` and `transformProcedure()` methods
2. `transformer/builder/PostgresCodeBuilder.java` - AST visitor coordinator
3. `transformer/type/TypeAnalysisVisitor.java` - Type inference pass
4. `transformer/context/TransformationContext.java` - Metadata and context
5. `transformer/context/TransformationIndices.java` - Metadata indices for O(1) lookups
6. `transformer/parser/AntlrParser.java` - ANTLR parser wrapper with `parseFunctionBody()` and `parseProcedureBody()`

**46 Visit*.java files in `transformer/builder/`:**
- 6 PL/SQL-specific visitors (function/procedure body, BEGIN...END, parameters, RETURN, seq_of_statements)
- 40 SQL visitors (SELECT, expressions, functions, etc.) - Fully functional from view transformation

---

## Known Issues Fixed

### Issue 1: Function Extraction Bug
**Problem:** `OracleFunctionExtractor` had `WHERE procedure_name IS NOT NULL` which filtered out standalone functions.
**Fix:** Removed the condition - standalone functions have `NULL` in `procedure_name` column.

### Issue 2: NULL Function Name Crash
**Problem:** Some Oracle package declarations have NULL `procedure_name`, causing NPE.
**Fix:** Added null check before processing: `if (functionName == null) continue;`

### Issue 3: Creation Results Not Displayed
**Problem:** `JobResource` didn't recognize `StandaloneFunctionImplementationResult`.
**Fix:** Added handler and summary generator.

### Issue 4: Verification Showing "NaN"
**Problem:** Verification and creation used same polling handler, but return different types.
**Fix:** Created separate `pollStandaloneFunctionVerificationJobStatus()` handler.

---

## Testing Checklist

### Prerequisites
1. ✅ Oracle database with at least one standalone function (test with simple functions first - see "Testing Guidance" below)
2. ✅ Extract Oracle functions: Click ⟳ on "Functions/Procedures found" (Oracle side)
3. ✅ Create function stubs: Click "Create Function Stubs" (PostgreSQL side) - This creates signatures for dependency resolution

### Test Verification (Step 25 - Upper ⟳ button)
1. Click ⟳ next to "Standalone Functions/Procedures implemented"
2. Should show count of verified implementations
3. Should NOT show "NaN"
4. Message: "Verified X standalone functions/procedures"

**Purpose:** Verifies what's already implemented in PostgreSQL (counts existing functions)

### Test Creation (Step 25 - "Create Standalone Functions" button)
1. Click "Create Standalone Functions"
2. Job processes each standalone function:
   - Extracts Oracle source from ALL_SOURCE
   - Transforms using TransformationService
   - Executes CREATE OR REPLACE FUNCTION in PostgreSQL
3. Results panel shows:
   - **Implemented:** Functions that transformed and executed successfully ✅
   - **Skipped:** Functions with transformation failures (missing visitors, parse errors) ⚠️
   - **Errors:** Functions that transformed but failed PostgreSQL execution ❌
4. Expandable details show:
   - List of implemented functions
   - List of skipped functions with reason (e.g., "Parse error", "Missing visitor for IF statement")
   - List of failed functions with error message and generated SQL

**Expected with current implementation:**
- Simple functions (no control flow): ✅ Implemented
- Functions with IF/LOOP/cursors/exceptions: ⚠️ Skipped (transformation error)
- Functions with syntax errors: ⚠️ Skipped (parse error)
- Functions with unsupported PostgreSQL features: ❌ Errors (execution failure)

---

## Testing Guidance

### Test with Simple Functions First

**Start with the simplest possible functions to verify the pipeline:**

1. **Simplest: No parameters, just RETURN constant**
   ```sql
   CREATE OR REPLACE FUNCTION get_version RETURN VARCHAR2 IS
   BEGIN
     RETURN '1.0.0';
   END;
   ```

2. **With parameters: Simple arithmetic**
   ```sql
   CREATE OR REPLACE FUNCTION add_numbers(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS
   BEGIN
     RETURN p_a + p_b;
   END;
   ```

3. **With expressions: Use SQL functions**
   ```sql
   CREATE OR REPLACE FUNCTION calculate_tax(p_amount NUMBER) RETURN NUMBER IS
   BEGIN
     RETURN ROUND(p_amount * 0.08, 2);
   END;
   ```

4. **With SELECT: Query database** (requires SELECT INTO visitor - not yet implemented)
   ```sql
   CREATE OR REPLACE FUNCTION get_employee_name(p_emp_id NUMBER) RETURN VARCHAR2 IS
     v_name VARCHAR2(100);
   BEGIN
     SELECT employee_name INTO v_name
     FROM employees WHERE employee_id = p_emp_id;
     RETURN v_name;
   END;
   ```

**Expected Results:**
- Functions 1-3: ✅ Should transform successfully with current implementation
- Function 4: ❌ Will fail - needs VisitVariable_declaration and VisitSelect_into_statement

### Verify Transformation Success

**UI Workflow:**
1. Extract Oracle functions (⟳ button on "Functions/Procedures found")
2. Create function stubs (button on "Functions/Procedures stubbed")
3. Click "Create Standalone Functions" (Step 25)
4. Check results:
   - **Implemented count:** Functions that transformed successfully
   - **Skipped count:** Functions with transformation errors (missing visitors)
   - **Error count:** Functions with PostgreSQL execution errors

**Log Analysis:**
- Check application logs for transformation details
- Look for "Transformation failed" messages with specific error details
- ANTLR parse errors indicate missing grammar support
- "Default visitor returned UNKNOWN" indicates missing visitor implementation

---

## References

- **TRANSFORMATION.md** - SQL transformation architecture and patterns
- **TYPE_INFERENCE_IMPLEMENTATION_PLAN.md** - Type inference system for PL/SQL transformation
- **CTE_IMPLEMENTATION_PLAN.md** - Example of transformation implementation
- **CONNECT_BY_IMPLEMENTATION_PLAN.md** - Example of complex feature transformation
- **CLAUDE.md** - Overall project architecture and migration status
- View implementation jobs - Similar pattern for SQL transformation

---

## Recommended Next Steps

### ✅ Phase 1: Infrastructure & Oracle Source Extraction (COMPLETE)

**Status:** COMPLETE - All infrastructure in place, Oracle source extraction working

**What's Done:**
- ✅ PostgresStandaloneFunctionImplementationJob with complete pipeline
- ✅ Oracle source extraction from ALL_SOURCE
- ✅ TransformationService integration (transformFunction/transformProcedure)
- ✅ Two-pass transformation architecture (TypeAnalysisVisitor + PostgresCodeBuilder)
- ✅ 6 core PL/SQL visitors (function/procedure body, BEGIN...END, parameters, RETURN)
- ✅ All 46 SQL visitors from view transformation (reused in PL/SQL)
- ✅ Error handling and progress tracking
- ✅ Frontend UI with verification and creation buttons

---

### 🔄 Phase 2: Control Flow Statements (NEXT - High Priority)

**Goal:** Implement missing PL/SQL statement visitors to handle real-world functions

**Current Gap:** Most Oracle functions use IF/LOOP/cursor/exception constructs that aren't yet supported. Infrastructure is ready, just need to add the visitor classes.

**Priority Order (by frequency in real code):**

#### 2.1: Variable Declarations & Assignments (HIGHEST PRIORITY)
**Why First:** Nearly all functions declare variables and use assignments

1. **VisitVariable_declaration.java**
   - Parse DECLARE section variable declarations
   - Convert Oracle types to PostgreSQL: `v_count NUMBER` → `v_count numeric`
   - Handle default values: `v_rate NUMBER := 0.08` → `v_rate numeric := 0.08`
   - Integrate with TypeAnalysisVisitor scope tracking

2. **VisitAssignment_statement.java**
   - Transform `:=` assignments: `v_total := v_price * v_qty;`
   - Same syntax in PostgreSQL, just need visitor
   - Ensure proper expression transformation on right-hand side

**Example:**
```sql
-- Oracle
DECLARE
  v_count NUMBER := 0;
  v_name VARCHAR2(100);
BEGIN
  v_name := 'Test';
  v_count := v_count + 1;
END;

-- PostgreSQL (Generated)
DECLARE
  v_count numeric := 0;
  v_name text;
BEGIN
  v_name := 'Test';
  v_count := v_count + 1;
END;
```

**Estimated Effort:** ~1-2 days
**Impact:** Enables ~40% of real-world functions to work

---

#### 2.2: Conditional Logic (HIGH PRIORITY)
**Why Next:** Second most common construct after variables

3. **VisitIf_statement.java**
   - Transform IF/ELSIF/ELSE/END IF blocks
   - PostgreSQL syntax is nearly identical
   - Handle nested IF statements
   - Transform condition expressions

**Example:**
```sql
-- Oracle
IF v_count > 10 THEN
  v_status := 'HIGH';
ELSIF v_count > 5 THEN
  v_status := 'MEDIUM';
ELSE
  v_status := 'LOW';
END IF;

-- PostgreSQL (Generated)
IF v_count > 10 THEN
  v_status := 'HIGH';
ELSIF v_count > 5 THEN
  v_status := 'MEDIUM';
ELSE
  v_status := 'LOW';
END IF;
```

**Estimated Effort:** ~1 day
**Impact:** Enables ~60% of real-world functions to work

---

#### 2.3: SELECT INTO Statements (HIGH PRIORITY)
**Why Critical:** Database functions typically query data

4. **VisitSelect_into_statement.java**
   - Transform `SELECT...INTO variable` statements
   - Leverage existing SELECT visitors (already complete)
   - Add INTO variable list transformation

**Example:**
```sql
-- Oracle
SELECT employee_name, salary INTO v_name, v_sal
FROM employees WHERE employee_id = p_emp_id;

-- PostgreSQL (Generated)
SELECT employee_name, salary INTO v_name, v_sal
FROM hr.employees WHERE employee_id = p_emp_id;
```

**Estimated Effort:** ~0.5 days (SELECT visitors already work)
**Impact:** Enables ~70% of real-world functions to work

---

#### 2.4: Loop Constructs (MEDIUM PRIORITY)

5. **VisitLoop_statement.java** - Basic LOOP/END LOOP
6. **VisitWhile_loop_statement.java** - WHILE condition LOOP/END LOOP
7. **VisitFor_loop_statement.java** - FOR i IN 1..10 LOOP/END LOOP
8. **VisitExit_statement.java** - EXIT / EXIT WHEN condition

**Example:**
```sql
-- Oracle
FOR i IN 1..10 LOOP
  v_sum := v_sum + i;
  EXIT WHEN v_sum > 50;
END LOOP;

-- PostgreSQL (Generated)
FOR i IN 1..10 LOOP
  v_sum := v_sum + i;
  EXIT WHEN v_sum > 50;
END LOOP;
```

**Estimated Effort:** ~2 days for all loop types
**Impact:** Enables ~85% of real-world functions to work

---

#### 2.5: Exception Handling (MEDIUM PRIORITY)

9. **VisitException_handler.java** - WHEN exception_name THEN statements
10. **VisitRaise_statement.java** - RAISE / RAISE_APPLICATION_ERROR

**Example:**
```sql
-- Oracle
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    v_result := NULL;
  WHEN OTHERS THEN
    RAISE;
END;

-- PostgreSQL (Generated)
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    v_result := NULL;
  WHEN OTHERS THEN
    RAISE;
END;
```

**Note:** Oracle exception names need mapping to PostgreSQL (NO_DATA_FOUND stays same, but RAISE_APPLICATION_ERROR → RAISE EXCEPTION)

**Estimated Effort:** ~2-3 days
**Impact:** Enables ~90% of real-world functions to work

---

#### 2.6: Cursor Operations (LOWER PRIORITY - Complex)

11. **VisitCursor_declaration.java** - CURSOR declarations in DECLARE section
12. **VisitCursor_loop_statement.java** - FOR rec IN cursor LOOP
13. **VisitOpen_statement.java** - OPEN cursor
14. **VisitFetch_statement.java** - FETCH cursor INTO variables
15. **VisitClose_statement.java** - CLOSE cursor

**Note:** Cursors are complex and less commonly used in simple functions. Defer until after basic control flow.

**Estimated Effort:** ~3-4 days
**Impact:** Enables ~95% of real-world functions to work

---

### Phase 3: Advanced PL/SQL Features (FUTURE)

**Lower priority features to implement later:**
- Dynamic SQL (EXECUTE IMMEDIATE)
- Bulk operations (BULK COLLECT, FORALL)
- Autonomous transactions (not directly supported in PostgreSQL)
- Package variables (state management)
- Record types and collections
- Ref cursors
- Nested functions

**Estimated Effort:** ~2-3 weeks total

---

## Implementation Strategy

**Recommended approach for Phase 2:**

1. **Start with 2.1 (Variables & Assignments)** - Foundation for everything else
2. **Add 2.2 (IF statements)** - Most common control flow
3. **Add 2.3 (SELECT INTO)** - Essential for database functions
4. **Test with real functions** - Use actual Oracle functions from database
5. **Add 2.4 (Loops)** - Common but not critical
6. **Add 2.5 (Exceptions)** - Important for production code
7. **Defer 2.6 (Cursors)** - Complex, can wait

**Success Metrics:**
- **After 2.1-2.3:** 70% of real-world functions should transform successfully
- **After 2.4:** 85% coverage
- **After 2.5:** 90% coverage
- **After 2.6:** 95% coverage

**Test Strategy:**
- Create unit tests for each visitor (follow pattern in existing tests)
- Test with increasingly complex functions
- Use actual Oracle functions from target database
- Track transformation success rate

---

## Build Status

✅ **All code compiles successfully** - 199+ source files
✅ **All endpoints registered** - Jobs auto-discovered by JobRegistry
✅ **Frontend functional** - UI displays correctly, no console errors
✅ **Backend functional** - Full pipeline implemented (extraction → transformation → execution)
✅ **Transformation infrastructure complete** - Two-pass architecture with 46 visitors
✅ **6 core PL/SQL visitors implemented** - Function/procedure body, BEGIN...END, parameters, RETURN
✅ **All SQL visitors working** - 40 SQL visitors from view transformation (reused in PL/SQL)

**Current Limitation:** Control flow statements (IF/LOOP/cursors/exceptions) not yet implemented. Only simple functions transform successfully.

**Next Step:** Implement Phase 2 control flow visitors (see "Recommended Next Steps" above).
