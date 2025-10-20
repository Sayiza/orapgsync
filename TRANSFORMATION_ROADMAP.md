# Oracle to PostgreSQL SQL Transformation - Detailed Roadmap

**Last Updated:** 2025-10-20
**Purpose:** Detailed implementation plans for reaching 90% real-world Oracle view coverage

---

## Overview

**Current Status:** ~40-50% real-world coverage
**Target:** ~90% real-world coverage
**Estimated Total Effort:** 16-21 days

---

## Priority 1: CTEs (WITH Clause) 🔴 CRITICAL

**Impact:** 40-60% of complex Oracle views use CTEs
**Estimated Effort:** 4-5 days
**Coverage Gain:** ~50% → ~75% (+25 percentage points)

### Part A: Non-Recursive CTEs (2-3 days)

**Oracle Syntax:**
```sql
WITH
  dept_totals AS (
    SELECT dept_id, COUNT(*) as emp_count
    FROM employees
    GROUP BY dept_id
  ),
  high_count_depts AS (
    SELECT dept_id
    FROM dept_totals
    WHERE emp_count > 10
  )
SELECT d.dept_name, dt.emp_count
FROM departments d
JOIN dept_totals dt ON d.dept_id = dt.dept_id
WHERE d.dept_id IN (SELECT dept_id FROM high_count_depts);
```

**PostgreSQL Syntax:**
```sql
-- Nearly identical! Main differences:
-- 1. Oracle allows inline function declarations (WITH FUNCTION ... WITH ...)
-- 2. PostgreSQL requires RECURSIVE keyword for recursive CTEs
```

**Implementation Plan:**

1. **Parse `with_clause` in grammar (0.5 days)**
   - Grammar: `with_clause : WITH ... with_factoring_clause (',' with_factoring_clause)*`
   - Grammar: `subquery_factoring_clause : query_name paren_column_list? AS '(' subquery ')'`
   - Handle: CTE name, optional column list, subquery

2. **Create VisitWithClause.java (0.5 days)**
   ```java
   public class VisitWithClause {
     public static String v(PlSqlParser.With_clauseContext ctx, PostgresCodeBuilder b) {
       StringBuilder result = new StringBuilder("WITH ");

       // Handle inline PL/SQL functions/procedures (Oracle-specific)
       if (ctx.function_body() != null || ctx.procedure_body() != null) {
         throw new TransformationException(
           "Inline PL/SQL functions in WITH clause not yet supported"
         );
       }

       // Process each CTE
       List<PlSqlParser.With_factoring_clauseContext> ctes =
         ctx.with_factoring_clause();
       for (int i = 0; i < ctes.size(); i++) {
         if (i > 0) result.append(", ");
         result.append(VisitWithFactoringClause.v(ctes.get(i), b));
       }

       return result.toString();
     }
   }
   ```

3. **Create VisitWithFactoringClause.java (0.5 days)**
   ```java
   public class VisitWithFactoringClause {
     public static String v(PlSqlParser.With_factoring_clauseContext ctx,
                           PostgresCodeBuilder b) {
       // Handle subquery_factoring_clause (standard CTEs)
       if (ctx.subquery_factoring_clause() != null) {
         return VisitSubqueryFactoringClause.v(
           ctx.subquery_factoring_clause(), b
         );
       }

       // Handle subav_factoring_clause (subquery with analytic views)
       // This is an advanced Oracle 12c+ feature - defer for now
       throw new TransformationException(
         "Subav factoring clause (analytic views) not yet supported"
       );
     }
   }
   ```

4. **Create VisitSubqueryFactoringClause.java (0.5 days)**
   ```java
   public class VisitSubqueryFactoringClause {
     public static String v(PlSqlParser.Subquery_factoring_clauseContext ctx,
                           PostgresCodeBuilder b) {
       StringBuilder result = new StringBuilder();

       // Get CTE name
       String cteName = ctx.query_name().getText();
       result.append(cteName);

       // Handle optional column list: (col1, col2, col3)
       if (ctx.paren_column_list() != null) {
         result.append(" ");
         result.append(b.visit(ctx.paren_column_list()));
       }

       // Add AS keyword
       result.append(" AS (");

       // Transform the subquery (recursive transformation)
       result.append(b.visit(ctx.subquery()));

       result.append(")");

       return result.toString();
     }
   }
   ```

5. **Update VisitSelectStatement.java (0.5 days)**
   ```java
   public class VisitSelectStatement {
     public static String v(PlSqlParser.Select_statementContext ctx,
                           PostgresCodeBuilder b) {
       StringBuilder result = new StringBuilder();

       // Handle WITH clause first (if present)
       if (ctx.with_clause() != null) {
         result.append(VisitWithClause.v(ctx.with_clause(), b));
         result.append(" ");
       }

       // Handle main subquery
       result.append(b.visit(ctx.subquery()));

       return result.toString();
     }
   }
   ```

6. **Testing (1 day)**
   - Single CTE
   - Multiple CTEs
   - CTEs with column lists
   - Nested CTEs (CTE referencing another CTE)
   - CTEs with complex subqueries (JOINs, WHERE, GROUP BY, etc.)
   - CTEs used in main query FROM clause
   - CTEs used in main query WHERE clause (IN subquery)
   - Target: 15-20 tests

**Pass-Through Strategy:**
- Non-recursive CTE syntax is nearly identical
- Main work is routing through grammar correctly
- All subquery transformations apply recursively to CTE definitions

---

### Part B: Recursive CTEs (2 days)

**Oracle Syntax:**
```sql
WITH employee_hierarchy (emp_id, emp_name, manager_id, level_num) AS (
  -- Base case (anchor member)
  SELECT emp_id, emp_name, manager_id, 1
  FROM employees
  WHERE manager_id IS NULL

  UNION ALL

  -- Recursive case (recursive member)
  SELECT e.emp_id, e.emp_name, e.manager_id, eh.level_num + 1
  FROM employees e
  JOIN employee_hierarchy eh ON e.manager_id = eh.emp_id
)
SELECT * FROM employee_hierarchy;
```

**PostgreSQL Syntax:**
```sql
WITH RECURSIVE employee_hierarchy (emp_id, emp_name, manager_id, level_num) AS (
  -- Base case
  SELECT emp_id, emp_name, manager_id, 1
  FROM employees
  WHERE manager_id IS NULL

  UNION ALL

  -- Recursive case
  SELECT e.emp_id, e.emp_name, e.manager_id, eh.level_num + 1
  FROM employees e
  JOIN employee_hierarchy eh ON e.manager_id = eh.emp_id
)
SELECT * FROM employee_hierarchy;
```

**Key Difference:** PostgreSQL requires explicit `RECURSIVE` keyword!

**Implementation Plan:**

1. **Create RecursiveCteAnalyzer.java (1 day)**
   - Detect UNION ALL pattern in CTE definition
   - Check if CTE references itself in recursive member
   - Return boolean: isRecursive

   ```java
   public class RecursiveCteAnalyzer {
     public static boolean isRecursive(
         PlSqlParser.Subquery_factoring_clauseContext ctx) {
       String cteName = ctx.query_name().getText().toLowerCase();
       PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();

       // Check if subquery contains UNION ALL
       if (!containsUnionAll(subqueryCtx)) {
         return false;
       }

       // Check if CTE name appears in FROM clause of any subquery branch
       return containsRecursiveReference(subqueryCtx, cteName);
     }

     private static boolean containsUnionAll(
         PlSqlParser.SubqueryContext ctx) {
       // Walk through subquery_operation_part nodes
       // Look for UNION ALL keyword
       // ...
     }

     private static boolean containsRecursiveReference(
         PlSqlParser.SubqueryContext ctx,
         String cteName) {
       // Walk AST looking for table references matching cteName
       // ...
     }
   }
   ```

2. **Update VisitWithClause.java (0.5 days)**
   ```java
   public static String v(PlSqlParser.With_clauseContext ctx,
                         PostgresCodeBuilder b) {
     StringBuilder result = new StringBuilder("WITH ");

     // Detect if ANY CTE is recursive
     boolean hasRecursiveCte = false;
     for (PlSqlParser.With_factoring_clauseContext cte :
          ctx.with_factoring_clause()) {
       if (cte.subquery_factoring_clause() != null) {
         if (RecursiveCteAnalyzer.isRecursive(
               cte.subquery_factoring_clause())) {
           hasRecursiveCte = true;
           break;
         }
       }
     }

     // Add RECURSIVE keyword if needed
     if (hasRecursiveCte) {
       result.append("RECURSIVE ");
     }

     // Process CTEs...
     // (rest of implementation unchanged)
   }
   ```

3. **Testing (0.5 days)**
   - Simple recursive CTE (employee hierarchy)
   - Recursive CTE with depth limit
   - Multiple CTEs with one recursive
   - Recursive CTE with complex JOIN
   - Target: 8-10 tests

**Detection Strategy:**
- Analyze CTE definition AST
- Look for UNION ALL pattern (required for recursion)
- Check if CTE name appears in FROM clause of second UNION branch
- Add RECURSIVE keyword only when detected

---

## Priority 2: Common Date/Time Functions 🟡 HIGH IMPACT

**Impact:** 20-30% of views
**Estimated Effort:** 3-5 days
**Coverage Gain:** ~75% → ~80% (+5 percentage points)

### Date Arithmetic Functions

**1. ADD_MONTHS (0.5 days)**
```sql
-- Oracle
SELECT ADD_MONTHS(hire_date, 6) FROM employees;

-- PostgreSQL
SELECT hire_date + INTERVAL '6 months' FROM employees;
```

**Implementation:** VisitDateFunction.java
- Extract date expression and month count
- Transform to: `date_expr + INTERVAL 'N months'`

**2. MONTHS_BETWEEN (0.5 days)**
```sql
-- Oracle
SELECT MONTHS_BETWEEN(end_date, start_date) FROM projects;

-- PostgreSQL
SELECT (EXTRACT(YEAR FROM AGE(end_date, start_date)) * 12 +
        EXTRACT(MONTH FROM AGE(end_date, start_date))) FROM projects;
```

**Implementation:** VisitDateFunction.java
- More complex transformation using AGE() and EXTRACT()

**3. NEXT_DAY (0.5 days)**
```sql
-- Oracle
SELECT NEXT_DAY(SYSDATE, 'MONDAY') FROM dual;

-- PostgreSQL (custom function required)
-- Need to create a PostgreSQL function: next_day(date, text)
-- Or transform inline with complex CASE WHEN logic
```

**Implementation:**
- Option A: Document that users must create next_day() function
- Option B: Generate complex CASE WHEN expression (verbose)
- Recommend: Option A

**4. LAST_DAY (0.5 days)**
```sql
-- Oracle
SELECT LAST_DAY(hire_date) FROM employees;

-- PostgreSQL
SELECT (DATE_TRUNC('MONTH', hire_date) + INTERVAL '1 month' - INTERVAL '1 day')::DATE
FROM employees;
```

**Implementation:** VisitDateFunction.java
- Transform to DATE_TRUNC + INTERVAL arithmetic

**5. TRUNC(date) (0.5 days)**
```sql
-- Oracle
SELECT TRUNC(hire_date) FROM employees;          -- Truncate to day
SELECT TRUNC(hire_date, 'MONTH') FROM employees; -- Truncate to month

-- PostgreSQL
SELECT DATE_TRUNC('day', hire_date)::DATE FROM employees;
SELECT DATE_TRUNC('month', hire_date)::DATE FROM employees;
```

**Implementation:** VisitDateFunction.java
- Map Oracle format strings to PostgreSQL date_part values
- 'DD' → 'day', 'MONTH' → 'month', 'YEAR' → 'year', 'HH' → 'hour'

**6. ROUND(date) (0.5 days)**
```sql
-- Oracle
SELECT ROUND(hire_date) FROM employees;          -- Round to nearest day
SELECT ROUND(hire_date, 'MONTH') FROM employees; -- Round to nearest month

-- PostgreSQL (complex transformation)
-- Need DATE_TRUNC + conditional logic based on time/day of month
```

**Implementation:** VisitDateFunction.java
- Complex transformation with CASE WHEN logic

**7. INTERVAL Expressions (1 day)**
```sql
-- Oracle
SELECT hire_date + INTERVAL '1' DAY FROM employees;
SELECT hire_date + INTERVAL '3' MONTH FROM employees;

-- PostgreSQL
SELECT hire_date + INTERVAL '1 day' FROM employees;
SELECT hire_date + INTERVAL '3 months' FROM employees;
```

**Key Difference:** Oracle uses singular (DAY, MONTH), PostgreSQL uses plural (days, months)

**Implementation:** VisitIntervalExpression.java
- Parse INTERVAL literal
- Transform unit: DAY → day(s), MONTH → month(s), YEAR → year(s)
- Pass through value unchanged

**Testing (1 day):**
- Each function: basic, in WHERE clause, in ORDER BY, with NULL values
- INTERVAL expressions: days, months, years, hours, minutes
- Target: 25-30 tests

---

## Priority 3: Common String Functions 🟡 HIGH IMPACT

**Impact:** 20-30% of views
**Estimated Effort:** 3-4 days
**Coverage Gain:** ~80% → ~85% (+5 percentage points)

### String Manipulation Functions

**1. INSTR (0.5 days)**
```sql
-- Oracle
SELECT INSTR(email, '@') FROM employees;
SELECT INSTR(email, '@', 1, 2) FROM employees; -- 2nd occurrence

-- PostgreSQL
SELECT POSITION('@' IN email) FROM employees;
SELECT POSITION('@' IN SUBSTRING(email FROM POSITION('@' IN email) + 1)) +
       POSITION('@' IN email) FROM employees; -- 2nd occurrence (complex!)
```

**Implementation:** VisitStringFunction.java
- 2-arg INSTR: Direct POSITION() transformation
- 3-arg/4-arg INSTR: Complex (may defer or throw unsupported)

**2. LPAD / RPAD (0.5 days)**
```sql
-- Oracle
SELECT LPAD(emp_id, 10, '0') FROM employees;
SELECT RPAD(emp_name, 20, ' ') FROM employees;

-- PostgreSQL
SELECT LPAD(emp_id::TEXT, 10, '0') FROM employees;
SELECT RPAD(emp_name, 20, ' ') FROM employees;
```

**Implementation:** VisitStringFunction.java
- Nearly identical syntax
- Only difference: PostgreSQL may require ::TEXT cast for numeric values
- Pass-through with type checking

**3. TRANSLATE (0.5 days)**
```sql
-- Oracle
SELECT TRANSLATE(phone, '()-', '') FROM contacts;

-- PostgreSQL
SELECT TRANSLATE(phone, '()-', '') FROM contacts;
```

**Implementation:** VisitStringFunction.java
- Identical syntax - pass-through!

**4. REGEXP Functions (1-2 days)**

**REGEXP_REPLACE:**
```sql
-- Oracle
SELECT REGEXP_REPLACE(text, '[0-9]', 'X') FROM data;

-- PostgreSQL
SELECT REGEXP_REPLACE(text, '[0-9]', 'X', 'g') FROM data;
```

**Key Difference:** PostgreSQL requires 'g' flag for global replace

**REGEXP_SUBSTR:**
```sql
-- Oracle
SELECT REGEXP_SUBSTR(email, '[^@]+') FROM employees;

-- PostgreSQL
SELECT (REGEXP_MATCH(email, '[^@]+'))[1] FROM employees;
```

**Key Difference:** PostgreSQL returns array, need to extract first element

**REGEXP_INSTR:**
```sql
-- Oracle
SELECT REGEXP_INSTR(text, '[0-9]') FROM data;

-- PostgreSQL
-- No direct equivalent - need to use POSITION() with REGEXP_REPLACE() or substring
```

**Implementation:** VisitStringFunction.java
- REGEXP_REPLACE: Add 'g' flag
- REGEXP_SUBSTR: Transform to REGEXP_MATCH()[1]
- REGEXP_INSTR: Complex transformation or throw unsupported (defer)

**Testing (1 day):**
- Each function: basic, with NULL values, in WHERE clause
- REGEXP functions: various patterns, flags
- Target: 20-25 tests

---

## Priority 4: CONNECT BY (Hierarchical Queries) 🔴 HIGH COMPLEXITY

**Impact:** 10-20% of views
**Estimated Effort:** 5-7 days
**Coverage Gain:** ~85% → ~90% (+5 percentage points)

### Overview

**Challenge:** Oracle's CONNECT BY has no direct PostgreSQL equivalent. Must transform to recursive CTE.

**Oracle Example:**
```sql
SELECT emp_id, emp_name, manager_id, LEVEL
FROM employees
START WITH manager_id IS NULL
CONNECT BY PRIOR emp_id = manager_id
ORDER SIBLINGS BY emp_name;
```

**PostgreSQL Equivalent:**
```sql
WITH RECURSIVE employee_hierarchy AS (
  -- Base case (START WITH)
  SELECT emp_id, emp_name, manager_id, 1 as level
  FROM employees
  WHERE manager_id IS NULL

  UNION ALL

  -- Recursive case (CONNECT BY)
  SELECT e.emp_id, e.emp_name, e.manager_id, eh.level + 1
  FROM employees e
  JOIN employee_hierarchy eh ON e.manager_id = eh.emp_id
)
SELECT emp_id, emp_name, manager_id, level
FROM employee_hierarchy
ORDER BY emp_name; -- Note: ORDER SIBLINGS BY not directly supported
```

### Implementation Plan

**Day 1: Detection and AST Analysis**
1. Create HierarchicalQueryAnalyzer.java
2. Detect hierarchical_query_clause in query_block
3. Extract components:
   - START WITH condition
   - CONNECT BY condition
   - PRIOR operator locations
   - NOCYCLE flag

**Day 2-3: Transformation Core**
1. Create VisitHierarchicalQuery.java
2. Generate recursive CTE structure:
   - CTE name: `<original_table>_hierarchy`
   - Base case: Transform START WITH to WHERE clause
   - Recursive case: Transform CONNECT BY to JOIN condition
   - PRIOR: Reference to recursive CTE alias

**Day 4: LEVEL Pseudo-Column**
1. Add level counter to recursive CTE
2. Replace LEVEL references in SELECT/WHERE with counter column

**Day 5-6: Advanced Features**
1. CONNECT_BY_ROOT: Add root columns to CTE, carry through recursion
2. SYS_CONNECT_BY_PATH: Use string concatenation in recursive member
3. CONNECT_BY_ISLEAF: Add leaf detection logic

**Day 7: Testing and Edge Cases**
- Simple hierarchies
- Multiple PRIOR conditions
- CONNECT BY with complex conditions
- LEVEL in WHERE clause (filtering)
- ORDER SIBLINGS BY (best-effort transformation)
- Target: 15-20 tests

**Complexity Notes:**
- ORDER SIBLINGS BY: Very difficult, may not be fully supported
- Cyclic detection (NOCYCLE): PostgreSQL doesn't have built-in equivalent
- Performance: PostgreSQL recursive CTEs can be slower than Oracle CONNECT BY

---

## Summary: Estimated Timeline to 90% Coverage

| Phase | Feature | Days | Cumulative | Coverage Gain |
|-------|---------|------|------------|---------------|
| Current | - | 0 | 0 | 40-50% |
| 3A | Quick Wins | 1-2 | 1-2 | 50-55% |
| 3B | CTEs (non-recursive) | 2-3 | 3-5 | 55-70% |
| 3B | CTEs (recursive) | 2 | 5-7 | 70-75% |
| 3C | Date/Time Functions | 3-5 | 8-12 | 75-80% |
| 3C | String Functions | 3-4 | 11-16 | 80-85% |
| 3D | CONNECT BY | 5-7 | 16-23 | 85-90% |

**Critical Path:** CTEs → Functions → CONNECT BY

**Recommendation:**
1. Start with quick wins (unary operators, CHR) - 1 day
2. Implement CTEs - 4-5 days
3. Assess coverage with real database
4. Prioritize functions vs CONNECT BY based on actual failure patterns
