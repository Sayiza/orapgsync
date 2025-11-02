# PL/SQL DML Statements Implementation Plan

**Status:** 📋 **PLANNED** - Ready for implementation
**Date:** 2025-11-02
**Priority:** **HIGH** - Critical for real-world PL/SQL migration

---

## Problem Statement

INSERT/UPDATE/DELETE statements in PL/SQL are not currently supported by the transformation module. This is a critical gap because:

1. **High usage in real-world code**: Most PL/SQL procedures perform data modifications
2. **SQL% cursor tracking incomplete**: Infrastructure exists but can't be fully tested without DML support
3. **5 tests disabled**: Tests written and waiting for implementation
4. **Affects coverage estimate**: Current "85-95%" estimate doesn't account for this gap

---

## Current State

### ✅ What Exists
- **ANTLR grammar**: Full support for `insert_statement`, `update_statement`, `delete_statement` rules
- **SQL% cursor tracking infrastructure**: `VisitSql_statement.java` checks for DML statements and injects `GET DIAGNOSTICS`
- **Test cases ready**: 5 comprehensive tests written (currently disabled)
- **Expression transformation**: All expression types already supported (for WHERE clauses, SET clauses, VALUES clauses)

### ❌ What's Missing
- **Visitor implementations**: No `VisitInsert_statement.java`, `VisitUpdate_statement.java`, `VisitDelete_statement.java`
- **Registration**: DML statement visitors not registered in `PostgresCodeBuilder.java`
- **Integration testing**: Tests disabled waiting for implementation

---

## Grammar Analysis

### INSERT Statement
```antlr4
insert_statement
    : INSERT (single_table_insert | multi_table_insert)
    ;

single_table_insert
    : insert_into_clause (values_clause static_returning_clause? | select_statement) error_logging_clause?
    ;

insert_into_clause
    : INTO general_table_ref ('(' column_name (',' column_name)* ')')?
    ;

values_clause
    : VALUES expression_list (',' expression_list)*
    ;
```

**Oracle Examples:**
```sql
-- Simple INSERT with VALUES
INSERT INTO emp (empno, ename, salary) VALUES (100, 'Alice', 50000);

-- INSERT with SELECT
INSERT INTO emp_archive SELECT * FROM emp WHERE hire_date < '2020-01-01';

-- INSERT with schema qualification
INSERT INTO hr.emp (empno, ename) VALUES (101, 'Bob');

-- INSERT with RETURNING (Oracle-specific - may need special handling)
INSERT INTO emp (empno, ename) VALUES (102, 'Charlie') RETURNING empno INTO v_new_id;
```

**PostgreSQL Differences:**
- ✅ Basic INSERT syntax identical
- ✅ INSERT INTO ... SELECT identical
- ⚠️ RETURNING clause supported but syntax slightly different (INTO vs. RETURNING)
- ⚠️ Multi-table INSERT not supported (Oracle-specific feature)

### UPDATE Statement
```antlr4
update_statement
    : UPDATE general_table_ref update_set_clause where_clause? static_returning_clause? error_logging_clause?
    ;

update_set_clause
    : SET (
        column_based_update_set_clause (',' column_based_update_set_clause)*
        | VALUE '(' identifier ')' '=' expression
    )
    ;

column_based_update_set_clause
    : column_name '=' expression
    | paren_column_list '=' subquery
    ;
```

**Oracle Examples:**
```sql
-- Simple UPDATE
UPDATE emp SET salary = salary * 1.1 WHERE dept_id = 10;

-- UPDATE with multiple columns
UPDATE emp SET salary = 60000, bonus = 5000 WHERE empno = 100;

-- UPDATE with subquery
UPDATE emp SET salary = (SELECT AVG(salary) FROM emp WHERE dept_id = 10) WHERE empno = 100;

-- UPDATE with schema qualification
UPDATE hr.emp SET ename = 'Updated' WHERE empno = 100;

-- UPDATE with RETURNING
UPDATE emp SET salary = 70000 WHERE empno = 100 RETURNING salary INTO v_new_salary;
```

**PostgreSQL Differences:**
- ✅ Basic UPDATE syntax identical
- ✅ SET clause with multiple columns identical
- ✅ Subqueries in SET clause identical
- ⚠️ RETURNING clause supported but syntax slightly different

### DELETE Statement
```antlr4
delete_statement
    : DELETE FROM? general_table_ref where_clause? static_returning_clause? error_logging_clause?
    ;
```

**Oracle Examples:**
```sql
-- Simple DELETE
DELETE FROM emp WHERE empno = 100;

-- DELETE without FROM keyword (Oracle allows this)
DELETE emp WHERE dept_id = 10;

-- DELETE with schema qualification
DELETE FROM hr.emp WHERE hire_date < '2020-01-01';

-- DELETE with subquery in WHERE
DELETE FROM emp WHERE dept_id IN (SELECT dept_id FROM departments WHERE location = 'NY');

-- DELETE with RETURNING
DELETE FROM emp WHERE empno = 100 RETURNING ename INTO v_deleted_name;
```

**PostgreSQL Differences:**
- ✅ DELETE FROM syntax identical
- ⚠️ DELETE without FROM not recommended (PostgreSQL requires FROM keyword for clarity)
- ⚠️ RETURNING clause supported but syntax slightly different

---

## Transformation Strategy

### Phase 1: Basic DML (No RETURNING clause) - **Estimated: 3-4 hours**

Focus on the 90% use case: Basic INSERT/UPDATE/DELETE without RETURNING clauses.

**Key Principle:** Oracle and PostgreSQL DML syntax is **nearly identical** for basic operations. Most transformations will be **pass-through with schema qualification**.

#### 1.1 INSERT Statement Transformation

**VisitInsert_statement.java** (estimated 80-100 lines)

```java
/**
 * Transforms Oracle INSERT statements to PostgreSQL.
 *
 * Oracle:  INSERT INTO emp (empno, ename) VALUES (100, 'Alice')
 * Postgres: INSERT INTO hr.emp (empno, ename) VALUES (100, 'Alice')
 *
 * Key transformations:
 * - Schema qualification for table names
 * - Expression transformation for VALUES and SELECT clauses
 * - Pass-through for most syntax (nearly identical)
 * - Defer multi-table INSERT to Phase 2 (rare, complex)
 * - Defer RETURNING clause to Phase 2
 */
public class VisitInsert_statement {

    public static String v(PlSqlParser.Insert_statementContext ctx, PostgresCodeBuilder b) {
        // 1. Check for single_table_insert (common case)
        if (ctx.single_table_insert() != null) {
            return visitSingleTableInsert(ctx.single_table_insert(), b);
        }

        // 2. Multi-table INSERT (Oracle-specific, rare)
        if (ctx.multi_table_insert() != null) {
            throw new UnsupportedOperationException(
                "Multi-table INSERT not yet supported (Oracle-specific feature)");
        }

        return "";
    }

    private static String visitSingleTableInsert(...) {
        StringBuilder result = new StringBuilder("INSERT INTO ");

        // Insert INTO clause (table name + optional columns)
        result.append(visitInsertIntoClause(...));

        // VALUES clause OR SELECT statement
        if (ctx.values_clause() != null) {
            result.append(" VALUES ");
            result.append(visitValuesClause(...));
        } else if (ctx.select_statement() != null) {
            result.append(" ");
            result.append(b.visit(ctx.select_statement()));
        }

        // Ignore RETURNING and error_logging_clause for Phase 1

        return result.toString();
    }

    private static String visitInsertIntoClause(...) {
        // 1. Get table name (may be schema.table or just table)
        // 2. Apply schema qualification via context.qualifyTableName()
        // 3. Handle optional column list: (col1, col2, ...)
        // 4. Delegate to VisitGeneralElement for table reference
    }

    private static String visitValuesClause(...) {
        // Handle: VALUES (expr1, expr2), (expr3, expr4), ...
        // Delegate expression transformation to existing visitors
    }
}
```

**Test Cases:**
```java
@Test
void testSimpleInsertWithValues() {
    String oracle = "INSERT INTO emp (empno, ename) VALUES (100, 'Alice')";
    String expected = "INSERT INTO hr.emp (empno, ename) VALUES (100, 'Alice')";
    assertTransformation(oracle, expected);
}

@Test
void testInsertWithSelect() {
    String oracle = "INSERT INTO emp_archive SELECT * FROM emp WHERE dept_id = 10";
    String expected = "INSERT INTO hr.emp_archive SELECT * FROM hr.emp WHERE dept_id = 10";
    assertTransformation(oracle, expected);
}

@Test
void testInsertWithMultipleRows() {
    String oracle = "INSERT INTO emp (empno, ename) VALUES (100, 'A'), (101, 'B')";
    String expected = "INSERT INTO hr.emp (empno, ename) VALUES (100, 'A'), (101, 'B')";
    assertTransformation(oracle, expected);
}
```

#### 1.2 UPDATE Statement Transformation

**VisitUpdate_statement.java** (estimated 100-120 lines)

```java
/**
 * Transforms Oracle UPDATE statements to PostgreSQL.
 *
 * Oracle:  UPDATE emp SET salary = 60000 WHERE empno = 100
 * Postgres: UPDATE hr.emp SET salary = 60000 WHERE empno = 100
 *
 * Key transformations:
 * - Schema qualification for table names
 * - Expression transformation for SET and WHERE clauses
 * - Pass-through for most syntax (nearly identical)
 * - Defer RETURNING clause to Phase 2
 */
public class VisitUpdate_statement {

    public static String v(PlSqlParser.Update_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder("UPDATE ");

        // 1. Table reference (with schema qualification)
        result.append(b.visit(ctx.general_table_ref()));

        // 2. SET clause
        result.append(" ");
        result.append(visitUpdateSetClause(ctx.update_set_clause(), b));

        // 3. Optional WHERE clause
        if (ctx.where_clause() != null) {
            result.append(" ");
            result.append(b.visit(ctx.where_clause()));
        }

        // Ignore RETURNING and error_logging_clause for Phase 1

        return result.toString();
    }

    private static String visitUpdateSetClause(...) {
        StringBuilder result = new StringBuilder("SET ");

        // Handle column_based_update_set_clause list
        // Example: col1 = expr1, col2 = expr2
        // Delegate expression transformation to existing visitors

        return result.toString();
    }
}
```

**Test Cases:**
```java
@Test
void testSimpleUpdate() {
    String oracle = "UPDATE emp SET salary = 60000 WHERE empno = 100";
    String expected = "UPDATE hr.emp SET salary = 60000 WHERE empno = 100";
    assertTransformation(oracle, expected);
}

@Test
void testUpdateWithMultipleColumns() {
    String oracle = "UPDATE emp SET salary = 60000, bonus = 5000 WHERE empno = 100";
    String expected = "UPDATE hr.emp SET salary = 60000, bonus = 5000 WHERE empno = 100";
    assertTransformation(oracle, expected);
}

@Test
void testUpdateWithSubquery() {
    String oracle = "UPDATE emp SET salary = (SELECT AVG(salary) FROM emp) WHERE empno = 100";
    String expected = "UPDATE hr.emp SET salary = (SELECT AVG(salary) FROM hr.emp) WHERE empno = 100";
    assertTransformation(oracle, expected);
}
```

#### 1.3 DELETE Statement Transformation

**VisitDelete_statement.java** (estimated 60-80 lines)

```java
/**
 * Transforms Oracle DELETE statements to PostgreSQL.
 *
 * Oracle:  DELETE FROM emp WHERE empno = 100
 *          DELETE emp WHERE empno = 100  (FROM is optional in Oracle)
 * Postgres: DELETE FROM hr.emp WHERE empno = 100  (FROM required)
 *
 * Key transformations:
 * - Schema qualification for table names
 * - Always include FROM keyword (PostgreSQL best practice)
 * - Expression transformation for WHERE clause
 * - Defer RETURNING clause to Phase 2
 */
public class VisitDelete_statement {

    public static String v(PlSqlParser.Delete_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder("DELETE FROM ");

        // 1. Table reference (with schema qualification)
        result.append(b.visit(ctx.general_table_ref()));

        // 2. Optional WHERE clause
        if (ctx.where_clause() != null) {
            result.append(" ");
            result.append(b.visit(ctx.where_clause()));
        }

        // Ignore RETURNING and error_logging_clause for Phase 1

        return result.toString();
    }
}
```

**Test Cases:**
```java
@Test
void testSimpleDelete() {
    String oracle = "DELETE FROM emp WHERE empno = 100";
    String expected = "DELETE FROM hr.emp WHERE empno = 100";
    assertTransformation(oracle, expected);
}

@Test
void testDeleteWithoutFrom() {
    String oracle = "DELETE emp WHERE empno = 100";
    String expected = "DELETE FROM hr.emp WHERE empno = 100";  // FROM added
    assertTransformation(oracle, expected);
}

@Test
void testDeleteWithSubqueryInWhere() {
    String oracle = "DELETE FROM emp WHERE dept_id IN (SELECT dept_id FROM departments WHERE location = 'NY')";
    String expected = "DELETE FROM hr.emp WHERE dept_id IN (SELECT dept_id FROM hr.departments WHERE location = 'NY')";
    assertTransformation(oracle, expected);
}
```

#### 1.4 Registration in PostgresCodeBuilder

Add visitor registrations:

```java
// In PostgresCodeBuilder.java constructor or initialization block
public PostgresCodeBuilder(...) {
    // ... existing registrations ...

    // DML statements (Phase 1 - Basic support)
    register(PlSqlParser.Insert_statementContext.class, VisitInsert_statement::v);
    register(PlSqlParser.Update_statementContext.class, VisitUpdate_statement::v);
    register(PlSqlParser.Delete_statementContext.class, VisitDelete_statement::v);
}
```

#### 1.5 Enable Existing Tests

Enable 5 tests in `PostgresPlSqlCursorAttributesValidationTest.java`:

```java
// Remove @Disabled annotation from these tests:
@Test
// @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
void testSqlRowCountAfterUpdate() { ... }

@Test
// @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
void testSqlFoundAfterDelete() { ... }

@Test
// @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
void testSqlNotFoundAfterInsert() { ... }

@Test
// @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
void testSqlIsOpenAlwaysFalse() { ... }

@Test
// @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
void testMultipleSqlAttributes() { ... }
```

#### 1.6 Create Comprehensive Test Suite

**New Test Class:** `PostgresPlSqlDmlStatementsValidationTest.java`

```java
/**
 * Integration tests for DML statement transformation in PL/SQL.
 * Tests INSERT, UPDATE, DELETE statements with SQL% cursor tracking.
 */
@QuarkusTest
public class PostgresPlSqlDmlStatementsValidationTest extends BasePostgresValidationTest {

    @Test
    void testInsertWithValues() { ... }

    @Test
    void testInsertWithSelect() { ... }

    @Test
    void testInsertWithMultipleRows() { ... }

    @Test
    void testUpdateWithSingleColumn() { ... }

    @Test
    void testUpdateWithMultipleColumns() { ... }

    @Test
    void testUpdateWithSubquery() { ... }

    @Test
    void testDeleteWithWhere() { ... }

    @Test
    void testDeleteWithSubquery() { ... }

    @Test
    void testMixedDmlOperations() { ... }  // INSERT + UPDATE + DELETE in same function

    @Test
    void testDmlWithExceptionHandling() { ... }  // DML inside EXCEPTION blocks

    @Test
    void testDmlInLoops() { ... }  // UPDATE inside FOR loop

    @Test
    void testDmlWithTransactionControl() { ... }  // COMMIT after INSERT
}
```

**Estimated test count:** 12-15 tests

---

### Phase 2: RETURNING Clause Support - **Estimated: 2-3 hours** (Optional - Lower Priority)

**Why deferred:**
- RETURNING clause usage is less common (~10-20% of DML statements)
- Phase 1 covers 80-90% of real-world DML usage
- Syntax differences between Oracle and PostgreSQL require careful handling

**Oracle RETURNING Clause:**
```sql
INSERT INTO emp (empno, ename) VALUES (100, 'Alice') RETURNING empno INTO v_new_id;
UPDATE emp SET salary = 60000 WHERE empno = 100 RETURNING salary INTO v_new_salary;
DELETE FROM emp WHERE empno = 100 RETURNING ename INTO v_deleted_name;
```

**PostgreSQL RETURNING Clause:**
```sql
-- PostgreSQL: RETURNING without INTO (returns result set)
INSERT INTO emp (empno, ename) VALUES (100, 'Alice') RETURNING empno;

-- PL/pgSQL: Need to capture with SELECT INTO
SELECT empno INTO v_new_id FROM (
    INSERT INTO emp (empno, ename) VALUES (100, 'Alice') RETURNING empno
) subquery;
```

**Transformation Strategy:**
1. Detect `static_returning_clause` in grammar
2. Extract RETURNING expression and INTO variable
3. Wrap DML in SELECT ... FROM (...) subquery pattern
4. Assign result to PL/SQL variable

**Implementation Notes:**
- May need to wrap entire DML statement in SELECT subquery
- Handle multiple variables: `RETURNING col1, col2 INTO var1, var2`
- Edge case: RETURNING with aggregate functions

---

### Phase 3: Multi-Table INSERT Support - **Estimated: 4-6 hours** (Optional - Very Low Priority)

**Why deferred:**
- Oracle-specific feature (not in SQL standard)
- Rare usage (~1-2% of INSERT statements)
- Complex transformation required (may need to split into multiple INSERTs)

**Oracle Multi-Table INSERT:**
```sql
INSERT ALL
  INTO emp (empno, ename) VALUES (empno, ename)
  INTO emp_audit (empno, ename) VALUES (empno, ename)
SELECT 100 AS empno, 'Alice' AS ename FROM dual;
```

**PostgreSQL Equivalent:**
- No direct equivalent
- Requires CTE or separate INSERT statements
- May need transaction wrapper

---

## Implementation Checklist

### Phase 1: Basic DML (HIGH PRIORITY)

- [ ] **VisitInsert_statement.java** (80-100 lines)
  - [ ] Single table INSERT with VALUES
  - [ ] INSERT with SELECT
  - [ ] Schema qualification
  - [ ] Column list handling
  - [ ] Multiple rows in VALUES clause
- [ ] **VisitUpdate_statement.java** (100-120 lines)
  - [ ] Basic UPDATE with SET
  - [ ] Multiple columns in SET clause
  - [ ] Subqueries in SET clause
  - [ ] Schema qualification
  - [ ] WHERE clause delegation
- [ ] **VisitDelete_statement.java** (60-80 lines)
  - [ ] Basic DELETE with WHERE
  - [ ] Always include FROM keyword
  - [ ] Schema qualification
  - [ ] Subqueries in WHERE clause
- [ ] **Register visitors in PostgresCodeBuilder.java** (5 lines)
- [ ] **Enable 5 existing tests** (remove @Disabled annotations)
- [ ] **Create comprehensive test suite** (12-15 tests)
  - [ ] PostgresPlSqlDmlStatementsValidationTest.java
  - [ ] INSERT tests (3-4 tests)
  - [ ] UPDATE tests (3-4 tests)
  - [ ] DELETE tests (2-3 tests)
  - [ ] Integration tests (DML in loops, exceptions, transactions)
- [ ] **Verify zero regressions** (run full test suite)
- [ ] **Update documentation**
  - [ ] TRANSFORMATION.md Phase 5 section
  - [ ] STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md
  - [ ] CLAUDE.md

### Phase 2: RETURNING Clause (MEDIUM PRIORITY - Optional)

- [ ] **Extend VisitInsert_statement.java** (add 30-40 lines)
- [ ] **Extend VisitUpdate_statement.java** (add 30-40 lines)
- [ ] **Extend VisitDelete_statement.java** (add 20-30 lines)
- [ ] **Handle variable assignments** (INTO variable list)
- [ ] **Wrap in SELECT subquery pattern**
- [ ] **Tests** (4-5 tests)
- [ ] **Update documentation**

### Phase 3: Multi-Table INSERT (LOW PRIORITY - Future)

- [ ] **Research transformation strategy** (CTE vs separate INSERTs)
- [ ] **Implement multi-table INSERT handling** (100-150 lines)
- [ ] **Tests** (3-4 tests)
- [ ] **Update documentation**

---

## Estimated Effort

| Phase | Complexity | Estimated Time | Priority |
|-------|-----------|---------------|----------|
| **Phase 1: Basic DML** | Low | **3-4 hours** | **HIGH** |
| Phase 2: RETURNING clause | Medium | 2-3 hours | Medium (Optional) |
| Phase 3: Multi-table INSERT | High | 4-6 hours | Low (Future) |

**Total for Phase 1 (Recommended):** 3-4 hours

---

## Success Criteria

### Phase 1 (HIGH PRIORITY)
- ✅ All 3 DML statement visitors implemented
- ✅ All 5 existing disabled tests enabled and passing
- ✅ 12-15 new comprehensive tests passing
- ✅ Zero regressions in existing 882 tests
- ✅ SQL% cursor tracking verified working with DML statements
- ✅ Documentation updated

### Phase 2 (Optional)
- ✅ RETURNING clause transformation working
- ✅ 4-5 RETURNING tests passing
- ✅ Documentation updated

### Phase 3 (Future)
- ✅ Multi-table INSERT transformation working
- ✅ 3-4 multi-table INSERT tests passing
- ✅ Documentation updated

---

## Impact Assessment

### Coverage Gain
- **Current:** 85-95% PL/SQL transformation coverage
- **After Phase 1:** **90-98% PL/SQL transformation coverage** (+5-8 percentage points)

**Why significant:**
- DML statements are used in **60-80%** of real-world PL/SQL procedures
- Phase 1 covers **80-90%** of all DML usage patterns
- Enables SQL% cursor tracking tests (critical for correctness)

### Risk Assessment
- **Low risk:** Oracle and PostgreSQL DML syntax is nearly identical for basic operations
- **High code reuse:** All expression, WHERE clause, and table reference transformations already exist
- **Clear scope:** Phase 1 focuses on common cases, defers complex features
- **Test-driven:** 5 existing tests + 12-15 new tests ensure correctness

---

## Dependencies

### Prerequisites (All Exist)
- ✅ Expression transformation (all types)
- ✅ WHERE clause transformation
- ✅ Table reference and schema qualification
- ✅ SELECT statement transformation (for INSERT ... SELECT)
- ✅ Subquery transformation
- ✅ SQL% cursor tracking infrastructure

### No Blockers
- All required functionality already implemented
- No ANTLR grammar changes needed
- No architecture changes needed

---

## Next Steps

1. **Review this plan** with team/stakeholders
2. **Start with Phase 1** (3-4 hours implementation)
3. **Run existing disabled tests** to validate SQL% cursor tracking
4. **Create comprehensive test suite** (12-15 tests)
5. **Update documentation** (TRANSFORMATION.md, STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md)
6. **Decide on Phase 2** based on real-world usage patterns

---

## References

- **Grammar:** `PlSqlParser.g4` lines 6208-6240
- **Existing Infrastructure:** `VisitSql_statement.java` (SQL% cursor tracking)
- **Disabled Tests:** `PostgresPlSqlCursorAttributesValidationTest.java` lines 431-690
- **Related Documentation:**
  - [TRANSFORMATION.md](../TRANSFORMATION.md)
  - [STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md](../STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md)
  - [PLSQL_CURSOR_ATTRIBUTES_PLAN.md](PLSQL_CURSOR_ATTRIBUTES_PLAN.md)

---

## Conclusion

**Phase 1 is a HIGH PRIORITY, LOW COMPLEXITY feature** that will:
- ✅ Close a critical gap in PL/SQL transformation
- ✅ Enable 80-90% of real-world DML usage
- ✅ Unlock 5 existing disabled tests
- ✅ Increase coverage by 5-8 percentage points
- ✅ Require only 3-4 hours of focused implementation
- ✅ Leverage existing transformation infrastructure (no new patterns needed)

**Recommended approach:** Implement Phase 1 immediately, defer Phase 2 and Phase 3 until real-world usage data shows they're needed.
