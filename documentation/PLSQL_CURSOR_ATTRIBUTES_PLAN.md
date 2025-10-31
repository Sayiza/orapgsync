# PL/SQL Cursor Attributes Implementation Plan

**Status:** 📋 Planning Phase
**Priority:** HIGH IMPACT - Very common in legacy Oracle code
**Target:** Step 25 - Standalone Function/Procedure Implementation
**Created:** 2025-10-30

---

## Overview

Implement support for Oracle cursor attributes (`%FOUND`, `%NOTFOUND`, `%ROWCOUNT`, `%ISOPEN`) in PL/SQL → PL/pgSQL transformation.

**Oracle Cursor Attributes:**
- `cursor_name%FOUND` - Returns TRUE if last FETCH returned a row
- `cursor_name%NOTFOUND` - Returns TRUE if last FETCH did NOT return a row
- `cursor_name%ROWCOUNT` - Returns number of rows fetched so far
- `cursor_name%ISOPEN` - Returns TRUE if cursor is currently open

**Implicit vs Explicit Cursors:**
- **Explicit cursors:** Named cursors with OPEN, FETCH, CLOSE
- **Implicit cursors:** SQL%FOUND, SQL%NOTFOUND, SQL%ROWCOUNT (for DML and SELECT INTO)

---

## Oracle vs PostgreSQL Cursor Attributes

### Oracle Explicit Cursor Example

```sql
DECLARE
  CURSOR emp_cursor IS SELECT empno, ename FROM emp WHERE dept_id = 10;
  v_empno NUMBER;
  v_ename VARCHAR2(50);
BEGIN
  OPEN emp_cursor;
  LOOP
    FETCH emp_cursor INTO v_empno, v_ename;
    EXIT WHEN emp_cursor%NOTFOUND;

    DBMS_OUTPUT.PUT_LINE('Employee: ' || v_ename);

    IF emp_cursor%ROWCOUNT > 5 THEN
      EXIT;
    END IF;
  END LOOP;

  IF emp_cursor%ISOPEN THEN
    CLOSE emp_cursor;
  END IF;
END;
```

### PostgreSQL Equivalent Strategies

PostgreSQL does NOT have direct cursor attribute syntax like Oracle. We need to track state manually.

**Strategy 1: Use cursor loop variable (FOR loops)**
```sql
-- FOR loops in PostgreSQL automatically handle iteration
-- We need to track ROWCOUNT manually
DECLARE
  v_rowcount INTEGER := 0;
BEGIN
  FOR rec IN (SELECT empno, ename FROM emp WHERE dept_id = 10) LOOP
    v_rowcount := v_rowcount + 1;

    RAISE NOTICE 'Employee: %', rec.ename;

    IF v_rowcount > 5 THEN
      EXIT;
    END IF;
  END LOOP;
END;
```

**Strategy 2: Use GET DIAGNOSTICS for implicit cursors**
```sql
DECLARE
  v_count INTEGER;
  v_empno INTEGER;
BEGIN
  SELECT empno INTO v_empno FROM emp WHERE empno = 100;

  -- Get row count from last SQL operation
  GET DIAGNOSTICS v_count = ROW_COUNT;

  IF v_count = 0 THEN
    RAISE EXCEPTION 'No employee found';
  END IF;
END;
```

**Strategy 3: Track explicit cursor state with variables**
```sql
DECLARE
  emp_cursor CURSOR FOR SELECT empno, ename FROM emp WHERE dept_id = 10;
  v_empno INTEGER;
  v_ename TEXT;
  v_found BOOLEAN;
  v_rowcount INTEGER := 0;
  v_isopen BOOLEAN := FALSE;
BEGIN
  OPEN emp_cursor;
  v_isopen := TRUE;

  LOOP
    FETCH emp_cursor INTO v_empno, v_ename;
    v_found := FOUND; -- PostgreSQL's FOUND variable (available after FETCH)

    EXIT WHEN NOT v_found;

    v_rowcount := v_rowcount + 1;

    RAISE NOTICE 'Employee: %', v_ename;

    IF v_rowcount > 5 THEN
      EXIT;
    END IF;
  END LOOP;

  IF v_isopen THEN
    CLOSE emp_cursor;
    v_isopen := FALSE;
  END IF;
END;
```

---

## Key PostgreSQL Features

### FOUND Variable

PostgreSQL provides a special `FOUND` variable that is automatically set after:
- `SELECT INTO`
- `FETCH`
- `UPDATE`, `DELETE`, `INSERT` (implicit cursor operations)

```sql
FETCH cursor_name INTO variables;
IF FOUND THEN
  -- fetch succeeded
END IF;

IF NOT FOUND THEN
  -- fetch failed (no more rows)
END IF;
```

### GET DIAGNOSTICS

For row counts from implicit SQL operations:

```sql
UPDATE emp SET salary = salary * 1.1 WHERE dept_id = 10;
GET DIAGNOSTICS v_count = ROW_COUNT;
RAISE NOTICE 'Updated % rows', v_count;
```

---

## Implementation Strategy

### Phase 1: Explicit Cursor Attributes with Manual Tracking

Transform Oracle cursor attributes to PostgreSQL variable tracking.

**Approach:**
1. **Declare tracking variables** for each cursor that uses attributes:
   - `cursor_name__found BOOLEAN;`
   - `cursor_name__rowcount INTEGER := 0;`
   - `cursor_name__isopen BOOLEAN := FALSE;`

2. **Inject state updates** after cursor operations:
   - After `OPEN cursor_name;` → set `cursor_name__isopen := TRUE;`
   - After `FETCH cursor_name INTO ...;` →
     - Set `cursor_name__found := FOUND;`
     - Increment `cursor_name__rowcount := cursor_name__rowcount + 1;` (if FOUND)
   - After `CLOSE cursor_name;` → set `cursor_name__isopen := FALSE;`

3. **Transform attribute references**:
   - `cursor_name%FOUND` → `cursor_name__found`
   - `cursor_name%NOTFOUND` → `NOT cursor_name__found`
   - `cursor_name%ROWCOUNT` → `cursor_name__rowcount`
   - `cursor_name%ISOPEN` → `cursor_name__isopen`

**Example Transformation:**

```sql
-- Oracle
DECLARE
  CURSOR c IS SELECT empno FROM emp;
  v_empno NUMBER;
BEGIN
  OPEN c;
  FETCH c INTO v_empno;
  IF c%FOUND THEN
    DBMS_OUTPUT.PUT_LINE('Found: ' || c%ROWCOUNT);
  END IF;
  IF c%ISOPEN THEN
    CLOSE c;
  END IF;
END;

-- PostgreSQL (transformed)
DECLARE
  c CURSOR FOR SELECT empno FROM emp;
  v_empno numeric;
  -- Auto-generated cursor tracking variables
  c__found BOOLEAN;
  c__rowcount INTEGER := 0;
  c__isopen BOOLEAN := FALSE;
BEGIN
  OPEN c;
  c__isopen := TRUE; -- Auto-injected

  FETCH c INTO v_empno;
  c__found := FOUND; -- Auto-injected
  IF c__found THEN
    c__rowcount := c__rowcount + 1; -- Auto-injected
  END IF;

  IF c__found THEN
    PERFORM oracle_compat.dbms_output__put_line('Found: ' || c__rowcount::text);
  END IF;

  IF c__isopen THEN
    CLOSE c;
    c__isopen := FALSE; -- Auto-injected
  END IF;
END;
```

### Phase 2: Implicit Cursor Attributes (SQL%)

Transform `SQL%FOUND`, `SQL%NOTFOUND`, `SQL%ROWCOUNT` for implicit cursors.

**Approach:**
1. Declare global tracking variable: `sql__rowcount INTEGER;`
2. After DML/SELECT INTO, inject: `GET DIAGNOSTICS sql__rowcount = ROW_COUNT;`
3. Transform:
   - `SQL%FOUND` → `(sql__rowcount > 0)`
   - `SQL%NOTFOUND` → `(sql__rowcount = 0)`
   - `SQL%ROWCOUNT` → `sql__rowcount`

**Example:**

```sql
-- Oracle
BEGIN
  UPDATE emp SET salary = 5000 WHERE empno = 100;
  IF SQL%FOUND THEN
    DBMS_OUTPUT.PUT_LINE('Updated ' || SQL%ROWCOUNT || ' rows');
  END IF;
END;

-- PostgreSQL (transformed)
DECLARE
  sql__rowcount INTEGER; -- Auto-generated
BEGIN
  UPDATE emp SET salary = 5000 WHERE empno = 100;
  GET DIAGNOSTICS sql__rowcount = ROW_COUNT; -- Auto-injected

  IF (sql__rowcount > 0) THEN
    PERFORM oracle_compat.dbms_output__put_line('Updated ' || sql__rowcount::text || ' rows');
  END IF;
END;
```

---

## Implementation Plan

### Step 1: Cursor Declaration Analysis (Prerequisites)

**Current State:** Named cursors already handled in `VisitCursor_declaration.java`

**Action:** Track which cursors use attributes (for optimization - only generate tracking variables if needed)

### Step 2: Create Cursor Attribute Tracking Infrastructure

**New Class:** `CursorAttributeTracker` (inner class in PostgresCodeBuilder)

```java
private static class CursorAttributeTracker {
    private Set<String> cursorsNeedingTracking = new HashSet<>();
    private Set<String> trackingVariablesDeclared = new HashSet<>();

    // Check if cursor uses any attributes
    void registerCursorAttributeUsage(String cursorName) {
        cursorsNeedingTracking.add(cursorName.toLowerCase());
    }

    // Generate tracking variable declarations
    List<String> generateTrackingDeclarations() {
        List<String> declarations = new ArrayList<>();
        for (String cursor : cursorsNeedingTracking) {
            if (!trackingVariablesDeclared.contains(cursor)) {
                declarations.add(cursor + "__found BOOLEAN;");
                declarations.add(cursor + "__rowcount INTEGER := 0;");
                declarations.add(cursor + "__isopen BOOLEAN := FALSE;");
                trackingVariablesDeclared.add(cursor);
            }
        }
        return declarations;
    }

    // Check if cursor needs tracking
    boolean needsTracking(String cursorName) {
        return cursorsNeedingTracking.contains(cursorName.toLowerCase());
    }
}
```

### Step 3: Implement Cursor Attribute Visitor

**New Visitor:** `VisitCursor_attribute.java`

**ANTLR Grammar Rule:**
```antlr
cursor_attribute
    : cursor_name '%' (FOUND | NOTFOUND | ROWCOUNT | ISOPEN)
    ;
```

**Implementation:**

```java
public class VisitCursor_attribute {
    public static String visit(
        PlSqlParser.Cursor_attributeContext ctx,
        PostgresCodeBuilder builder
    ) {
        String cursorName = ctx.cursor_name().getText();
        String attribute = ctx.children.get(2).getText().toUpperCase(); // After '%'

        // Register that this cursor needs tracking variables
        builder.cursorAttributeTracker.registerCursorAttributeUsage(cursorName);

        // Transform attribute reference
        switch (attribute) {
            case "FOUND":
                return cursorName + "__found";
            case "NOTFOUND":
                return "NOT " + cursorName + "__found";
            case "ROWCOUNT":
                return cursorName + "__rowcount";
            case "ISOPEN":
                return cursorName + "__isopen";
            default:
                throw new IllegalArgumentException("Unknown cursor attribute: " + attribute);
        }
    }
}
```

### Step 4: Inject State Updates in Cursor Operations

**Modify Existing Visitors:**

**A. OPEN Statement** (need to implement VisitOpen_statement.java)

```java
public class VisitOpen_statement {
    public static String visit(
        PlSqlParser.Open_statementContext ctx,
        PostgresCodeBuilder builder
    ) {
        String cursorName = ctx.cursor_name().getText();

        StringBuilder result = new StringBuilder();
        result.append("OPEN ").append(cursorName).append(";\n");

        // If cursor uses tracking, inject state update
        if (builder.cursorAttributeTracker.needsTracking(cursorName)) {
            result.append(cursorName).append("__isopen := TRUE;\n");
        }

        return result.toString();
    }
}
```

**B. FETCH Statement** (need to implement VisitFetch_statement.java)

```java
public class VisitFetch_statement {
    public static String visit(
        PlSqlParser.Fetch_statementContext ctx,
        PostgresCodeBuilder builder
    ) {
        String cursorName = ctx.cursor_name().getText();
        // ... extract INTO clause variables ...

        StringBuilder result = new StringBuilder();
        result.append("FETCH ").append(cursorName).append(" INTO ")
              .append(variables).append(";\n");

        // If cursor uses tracking, inject state updates
        if (builder.cursorAttributeTracker.needsTracking(cursorName)) {
            result.append(cursorName).append("__found := FOUND;\n");
            result.append("IF ").append(cursorName).append("__found THEN\n");
            result.append("  ").append(cursorName).append("__rowcount := ")
                  .append(cursorName).append("__rowcount + 1;\n");
            result.append("END IF;\n");
        }

        return result.toString();
    }
}
```

**C. CLOSE Statement** (need to implement VisitClose_statement.java)

```java
public class VisitClose_statement {
    public static String visit(
        PlSqlParser.Close_statementContext ctx,
        PostgresCodeBuilder builder
    ) {
        String cursorName = ctx.cursor_name().getText();

        StringBuilder result = new StringBuilder();
        result.append("CLOSE ").append(cursorName).append(";\n");

        // If cursor uses tracking, inject state update
        if (builder.cursorAttributeTracker.needsTracking(cursorName)) {
            result.append(cursorName).append("__isopen := FALSE;\n");
        }

        return result.toString();
    }
}
```

### Step 5: Pre-scan for Cursor Attributes

**Add Pre-scan Pass:**

Before generating function body, scan for cursor attribute usage:

```java
// In PostgresCodeBuilder
private void prescanForCursorAttributes(PlSqlParser.BodyContext ctx) {
    // Walk entire tree looking for cursor_attribute nodes
    ctx.accept(new PlSqlParserBaseVisitor<Void>() {
        @Override
        public Void visitCursor_attribute(PlSqlParser.Cursor_attributeContext ctx) {
            String cursorName = ctx.cursor_name().getText();
            cursorAttributeTracker.registerCursorAttributeUsage(cursorName);
            return null;
        }
    });
}
```

### Step 6: Inject Tracking Variable Declarations

**Modify VisitSeq_of_declare_specs.java:**

After processing all DECLARE items, inject cursor tracking variables:

```java
List<String> trackingVars = builder.cursorAttributeTracker.generateTrackingDeclarations();
for (String var : trackingVars) {
    declarations.add(var);
}
```

### Step 7: Handle Implicit Cursors (SQL%)

**Implementation:**

1. Always declare `sql__rowcount INTEGER;` at top of function
2. After DML statements (UPDATE, DELETE, INSERT), inject:
   ```sql
   GET DIAGNOSTICS sql__rowcount = ROW_COUNT;
   ```
3. After SELECT INTO, inject same
4. Transform SQL% attributes:
   - `SQL%FOUND` → `(sql__rowcount > 0)`
   - `SQL%NOTFOUND` → `(sql__rowcount = 0)`
   - `SQL%ROWCOUNT` → `sql__rowcount`

---

## Testing Strategy

### Test Coverage (Planned)

1. **Basic cursor attributes** (4 tests)
   - %FOUND in IF condition
   - %NOTFOUND in EXIT WHEN
   - %ROWCOUNT in expression
   - %ISOPEN in IF condition

2. **Complete cursor lifecycle** (3 tests)
   - OPEN → FETCH → check attributes → CLOSE
   - LOOP with FETCH and EXIT WHEN %NOTFOUND
   - Conditional CLOSE based on %ISOPEN

3. **Multiple cursors** (2 tests)
   - Two cursors with independent tracking
   - Nested cursor loops with attribute checks

4. **SQL% implicit cursors** (4 tests)
   - UPDATE with SQL%FOUND check
   - DELETE with SQL%ROWCOUNT check
   - SELECT INTO with SQL%NOTFOUND check
   - INSERT with SQL%ROWCOUNT check

5. **Edge cases** (3 tests)
   - Check %ISOPEN before OPEN (should be FALSE)
   - Check %ROWCOUNT before any FETCH (should be 0)
   - Check %FOUND before first FETCH (should be NULL or FALSE)

6. **Integration with existing features** (2 tests)
   - Cursor attributes in CASE expressions
   - Cursor attributes in function calls

**Total: ~18 comprehensive tests**

---

## Grammar Analysis

**Relevant ANTLR Rules:**

```antlr
cursor_attribute
    : cursor_name '%' (FOUND | NOTFOUND | ROWCOUNT | ISOPEN)
    ;

open_statement
    : OPEN cursor_name ('(' expression (',' expression)* ')')? ';'
    ;

fetch_statement
    : FETCH cursor_name INTO variable_name (',' variable_name)* ';'
    ;

close_statement
    : CLOSE cursor_name ';'
    ;
```

**Note:** Need to verify exact rule names in PlSqlParser.g4

---

## Implementation Checklist

### Phase 1: Explicit Cursor Attributes

- [ ] Create `CursorAttributeTracker` inner class in PostgresCodeBuilder
- [ ] Implement `VisitCursor_attribute.java` (cursor attribute transformation)
- [ ] Implement `VisitOpen_statement.java` (OPEN with state injection)
- [ ] Implement `VisitFetch_statement.java` (FETCH with state injection)
- [ ] Implement `VisitClose_statement.java` (CLOSE with state injection)
- [ ] Add pre-scan pass for cursor attribute detection
- [ ] Modify `VisitSeq_of_declare_specs.java` to inject tracking declarations
- [ ] Register all new visitors in PostgresCodeBuilder
- [ ] Create `PostgresPlSqlCursorAttributesValidationTest.java` (18 tests)
- [ ] Verify all existing tests still pass

### Phase 2: Implicit Cursor Attributes (SQL%)

- [ ] Implement SQL% attribute transformation
- [ ] Inject GET DIAGNOSTICS after DML statements
- [ ] Inject GET DIAGNOSTICS after SELECT INTO
- [ ] Create additional tests for SQL% attributes

### Phase 3: Documentation

- [ ] Update STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md
- [ ] Update TRANSFORMATION.md (if applicable)
- [ ] Update CLAUDE.md migration status

---

## Estimated Complexity

**Effort:** Medium-High
- Multiple new visitors needed (5 total: attribute + OPEN/FETCH/CLOSE + SQL%)
- Requires pre-scan pass for optimization
- State injection logic is complex
- Good test coverage needed

**Lines of Code Estimate:**
- CursorAttributeTracker: ~100 lines
- VisitCursor_attribute: ~60 lines
- VisitOpen_statement: ~70 lines
- VisitFetch_statement: ~90 lines
- VisitClose_statement: ~70 lines
- Pre-scan infrastructure: ~50 lines
- Test suite: ~500 lines
- **Total: ~940 lines**

**Risk:** Medium
- PostgreSQL FOUND variable behavior needs careful handling
- State injection must not break existing logic
- Need to handle all cursor types (named, parameterized)

---

## References

**Oracle Documentation:**
- [PL/SQL Cursor Attributes](https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/static-sql.html#GUID-89-4A644F71-6A45-4372-BD9F-85AF047E49F9)

**PostgreSQL Documentation:**
- [PL/pgSQL Cursors](https://www.postgresql.org/docs/current/plpgsql-cursors.html)
- [FOUND Variable](https://www.postgresql.org/docs/current/plpgsql-statements.html#PLPGSQL-STATEMENTS-DIAGNOSTICS)
- [GET DIAGNOSTICS](https://www.postgresql.org/docs/current/plpgsql-statements.html#PLPGSQL-STATEMENTS-DIAGNOSTICS)

**Related Plans:**
- [STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md](../STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md)
- [TRANSFORMATION.md](../TRANSFORMATION.md)

---

## Success Criteria

✅ **Definition of Done:**
1. All 5 cursor attribute visitors implemented and registered
2. CursorAttributeTracker infrastructure complete
3. State injection working for OPEN/FETCH/CLOSE
4. 18+ comprehensive tests passing
5. Zero regressions in existing 882 tests
6. Documentation updated
7. Coverage gain: +8-12% (80-92% → 88-100%)

**Impact:** HIGH - Cursor attributes are very common in legacy Oracle PL/SQL code. This feature will significantly increase real-world transformation success rate.
