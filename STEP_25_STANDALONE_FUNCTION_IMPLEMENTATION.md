# Step 25: Standalone Function/Procedure Implementation

**Status:** 🔄 **INFRASTRUCTURE COMPLETE** - Framework ready, transformation logic TODO
**Date Completed:** 2025-10-26
**Workflow Position:** Step 25 in orchestration sequence (after View Implementation)

---

## Overview

Step 25 implements the infrastructure for transforming Oracle standalone functions and procedures (NOT package members) to PostgreSQL using PL/SQL→PL/pgSQL transformation.

**Scope:**
- ✅ ONLY standalone functions/procedures (identified by `FunctionMetadata.isStandalone()`)
- ❌ Excludes package members (names contain `__`, handled in separate step)

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
**File:** `function/job/PostgresStandaloneFunctionImplementationJob.java`

**Type:** `AbstractDatabaseWriteJob<StandaloneFunctionImplementationResult>`
**WriteOperationType:** `"STANDALONE_FUNCTION_IMPLEMENTATION"`
**Returns:** `StandaloneFunctionImplementationResult`

**Current Behavior:**
- Retrieves Oracle functions from `StateService`
- Filters to standalone only: `.filter(FunctionMetadata::isStandalone)`
- **Skips all functions** with message: "PL/SQL transformation not yet implemented"
- Returns result with all functions in `skippedFunctions` map

**Placeholder Method:**
```java
private String transformFunctionToPlpgsql(FunctionMetadata function) {
    // TODO: Future implementation
    throw new UnsupportedOperationException("PL/SQL transformation not yet implemented");
}
```

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

## Files Created (4 new files)

1. `core/job/model/function/StandaloneFunctionImplementationResult.java` (158 lines)
2. `function/job/PostgresStandaloneFunctionImplementationVerificationJob.java` (213 lines)
3. `function/job/PostgresStandaloneFunctionImplementationJob.java` (159 lines)
4. `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md` (this file)

## Files Modified (5 files)

1. `src/main/resources/META-INF/resources/index.html` (+24 lines)
2. `src/main/resources/META-INF/resources/function-service.js` (+340 lines)
3. `core/service/StateService.java` (+9 lines)
4. `function/rest/FunctionResource.java` (+52 lines)
5. `core/job/rest/JobResource.java` (+10 lines)

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
1. Oracle database with at least one standalone function
2. Extract Oracle functions: Click ⟳ on "Functions/Procedures found" (Oracle side)
3. Create function stubs: Click "Create Function Stubs" (PostgreSQL side)

### Test Verification
1. Click ⟳ next to "Standalone Functions/Procedures implemented"
2. Should show count (might be 0 if no standalone functions)
3. Should NOT show "NaN"
4. Message: "Verified X standalone functions/procedures"

### Test Creation
1. Click "Create Standalone Functions"
2. Should show results panel with:
   - Implemented: 0
   - Skipped: X (number of standalone functions)
   - Errors: 0
3. Should show list of skipped functions with reason: "PL/SQL transformation not yet implemented"

---

## Next Steps: Add Transformation Logic

To enable actual PL/SQL transformation, implement in `PostgresStandaloneFunctionImplementationJob`:

### 1. Extract Oracle Function Source
```java
private String extractOracleFunctionSource(Connection oracleConn, FunctionMetadata function) {
    // Query ALL_SOURCE for function PL/SQL code
    // Assemble multi-line source into single string
}
```

### 2. Transform PL/SQL to PL/pgSQL
```java
private String transformFunctionToPlpgsql(FunctionMetadata function, String oracleSource) {
    // Use SqlTransformationService
    // Parse with ANTLR PlSqlParser
    // Transform using PostgresCodeBuilder with PL/SQL visitors
    // Generate CREATE OR REPLACE FUNCTION/PROCEDURE
}
```

### 3. Extend PostgresCodeBuilder
Add PL/SQL statement visitors:
- `VisitFunctionBody` - Function/procedure body structure
- `VisitDeclareSection` - Variable declarations
- `VisitIfStatement` - IF/ELSIF/ELSE
- `VisitLoopStatement` - LOOP/WHILE/FOR
- `VisitCursorDeclaration` - Cursor handling
- `VisitExceptionHandler` - Exception blocks
- `VisitReturnStatement` - RETURN statements
- `VisitAssignment` - Variable assignments (`:=`)

### 4. PL/SQL-Specific Transformations
- `RAISE_APPLICATION_ERROR` → `RAISE EXCEPTION`
- `%TYPE`, `%ROWTYPE` → PostgreSQL equivalents
- Exception name mapping (Oracle→PostgreSQL)
- Cursor differences
- Record type transformations

### 5. Execute in PostgreSQL
```java
try (PreparedStatement ps = postgresConnection.prepareStatement(pgSql)) {
    ps.execute();
    result.addImplementedFunction(function);
} catch (SQLException e) {
    result.addError(function.getDisplayName(), e.getMessage(), pgSql);
}
```

---

## References

- **TRANSFORMATION.md** - SQL transformation architecture and patterns
- **CTE_IMPLEMENTATION_PLAN.md** - Example of transformation implementation
- **CONNECT_BY_IMPLEMENTATION_PLAN.md** - Example of complex feature transformation
- **CLAUDE.md** - Overall project architecture and migration status
- View implementation jobs - Similar pattern for SQL transformation

---

## Build Status

✅ **All code compiles successfully** - 199 source files
✅ **All endpoints registered** - Jobs auto-discovered by JobRegistry
✅ **Frontend functional** - UI displays correctly, no console errors
✅ **Backend functional** - Verification works, creation skips with clear message

**Ready for transformation logic implementation!**
