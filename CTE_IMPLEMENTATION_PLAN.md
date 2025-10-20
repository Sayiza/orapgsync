# CTE Implementation Plan - Detailed Step-by-Step

**Last Updated:** 2025-10-20
**Estimated Effort:** 3-4 days
**Coverage Impact:** +20-25 percentage points (50% → 75%)

---

## Key Insight: CTEs are 95% Pass-Through!

The only significant transformation needed is adding the `RECURSIVE` keyword when a CTE references itself. Everything else is nearly identical between Oracle and PostgreSQL.

---

## Phase 1: Basic CTE Support (Non-Recursive) - 1.5 days

### Step 1.1: Understand the Grammar (0.5 days)

**Review grammar structure:**
```
select_statement
    : with_clause? subquery

with_clause
    : WITH (function_body | procedure_body)* with_factoring_clause (',' with_factoring_clause)*
    | WITH (function_body | procedure_body)+ (with_factoring_clause (',' with_factoring_clause)*)?

with_factoring_clause
    : subquery_factoring_clause
    | subav_factoring_clause

subquery_factoring_clause
    : query_name paren_column_list? AS '(' subquery ')'
```

**Key observations:**
1. `with_clause` is optional in `select_statement`
2. Oracle allows inline PL/SQL functions/procedures before CTEs
3. Each CTE is a `subquery_factoring_clause`
4. CTE has: name, optional column list, AS keyword, subquery

**Action items:**
- Read grammar carefully
- Understand parent-child relationships
- Note that `subquery` is recursively transformed (all our existing transformations apply!)

---

### Step 1.2: Create VisitWithClause.java (0.5 days)

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitWithClause.java`

**Responsibilities:**
1. Handle inline PL/SQL functions/procedures (throw exception)
2. Process list of CTEs
3. Delegate to VisitWithFactoringClause for each CTE
4. (Phase 2) Detect if any CTE is recursive and add RECURSIVE keyword

**Implementation:**

```java
package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

/**
 * Transforms Oracle WITH clause to PostgreSQL.
 *
 * <p>Key differences:</p>
 * <ul>
 *   <li>Oracle: WITH cte AS (...) - no RECURSIVE keyword needed</li>
 *   <li>PostgreSQL: WITH RECURSIVE cte AS (...) - RECURSIVE keyword required for recursive CTEs</li>
 *   <li>Oracle: Supports inline PL/SQL functions in WITH clause</li>
 *   <li>PostgreSQL: Does NOT support inline functions</li>
 * </ul>
 *
 * <p>Transformation strategy:</p>
 * <ul>
 *   <li>Non-recursive CTEs: Pass-through (syntax identical)</li>
 *   <li>Recursive CTEs: Detect and add RECURSIVE keyword</li>
 *   <li>Inline PL/SQL: Throw exception (not supported in PostgreSQL)</li>
 * </ul>
 */
public class VisitWithClause {

  public static String v(PlSqlParser.With_clauseContext ctx, PostgresCodeBuilder b) {
    // Check for inline PL/SQL functions/procedures (Oracle-specific feature)
    // Grammar: WITH (function_body | procedure_body)* with_factoring_clause ...
    if (!ctx.function_body().isEmpty() || !ctx.procedure_body().isEmpty()) {
      throw new TransformationException(
          "Inline PL/SQL functions/procedures in WITH clause are not supported in PostgreSQL. "
              + "Oracle allows: WITH FUNCTION my_func(...) IS ... BEGIN ... END; cte AS (...) "
              + "PostgreSQL requires: Create the function separately first, then use it in the CTE. "
              + "Manual migration required for this view."
      );
    }

    StringBuilder result = new StringBuilder("WITH ");

    // Phase 2: Add RECURSIVE keyword detection here
    // For now, we'll implement non-recursive CTEs only

    // Process each CTE (with_factoring_clause)
    List<PlSqlParser.With_factoring_clauseContext> ctes = ctx.with_factoring_clause();

    for (int i = 0; i < ctes.size(); i++) {
      if (i > 0) {
        result.append(", ");
      }
      result.append(VisitWithFactoringClause.v(ctes.get(i), b));
    }

    return result.toString();
  }
}
```

**Testing plan:**
- Test with inline function → should throw exception with clear message
- Test with inline procedure → should throw exception
- Test with single CTE → should pass through
- Test with multiple CTEs → should comma-separate correctly

---

### Step 1.3: Create VisitWithFactoringClause.java (0.25 days)

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitWithFactoringClause.java`

**Responsibilities:**
1. Route to subquery_factoring_clause (standard CTEs)
2. Handle subav_factoring_clause (analytic views - rare, defer)

**Implementation:**

```java
package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Routes to the appropriate CTE type visitor.
 *
 * <p>Oracle supports two types of factoring clauses:</p>
 * <ul>
 *   <li>subquery_factoring_clause - Standard CTEs (common)</li>
 *   <li>subav_factoring_clause - Analytic views (Oracle 12c+, rare)</li>
 * </ul>
 */
public class VisitWithFactoringClause {

  public static String v(PlSqlParser.With_factoring_clauseContext ctx, PostgresCodeBuilder b) {
    // Standard CTE (most common case)
    if (ctx.subquery_factoring_clause() != null) {
      return VisitSubqueryFactoringClause.v(ctx.subquery_factoring_clause(), b);
    }

    // Analytic view factoring clause (Oracle 12c+ feature, rare)
    if (ctx.subav_factoring_clause() != null) {
      throw new TransformationException(
          "Subquery analytic view factoring clauses (Oracle 12c+) are not yet supported. "
              + "This is a rare Oracle feature for materialized analytic views. "
              + "Manual migration required."
      );
    }

    throw new TransformationException(
        "Unknown with_factoring_clause type - neither subquery nor analytic view");
  }
}
```

---

### Step 1.4: Create VisitSubqueryFactoringClause.java (0.25 days)

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitSubqueryFactoringClause.java`

**Responsibilities:**
1. Extract CTE name
2. Handle optional column list
3. Transform subquery (recursive transformation!)
4. Assemble: `cte_name (col1, col2) AS (subquery)`

**Implementation:**

```java
package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Transforms a single CTE definition.
 *
 * <p>Grammar: query_name paren_column_list? AS '(' subquery ')'</p>
 *
 * <p>Syntax is identical in Oracle and PostgreSQL - pass-through transformation.</p>
 *
 * <p>Examples:</p>
 * <pre>
 * -- Without column list:
 * dept_totals AS (SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id)
 *
 * -- With column list:
 * dept_totals (dept_id, emp_count) AS (SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id)
 * </pre>
 */
public class VisitSubqueryFactoringClause {

  public static String v(PlSqlParser.Subquery_factoring_clauseContext ctx, PostgresCodeBuilder b) {
    StringBuilder result = new StringBuilder();

    // 1. CTE name
    String cteName = ctx.query_name().getText();
    result.append(cteName);

    // 2. Optional column list: (col1, col2, col3)
    if (ctx.paren_column_list() != null) {
      result.append(" ");
      // paren_column_list already includes parentheses
      result.append(b.visit(ctx.paren_column_list()));
    }

    // 3. AS keyword
    result.append(" AS (");

    // 4. Subquery (CRITICAL: This is recursively transformed!)
    // All our existing transformations apply:
    // - Schema qualification
    // - Synonym resolution
    // - Type methods, package functions
    // - Outer joins, ORDER BY, GROUP BY, etc.
    result.append(b.visit(ctx.subquery()));

    result.append(")");

    return result.toString();
  }
}
```

**Key insight:** The subquery transformation is **recursive**! This means:
- Schema qualification works automatically
- Synonym resolution works automatically
- All our existing transformations (ORDER BY NULLS FIRST, || → CONCAT(), etc.) work automatically
- CTEs can contain outer joins, window functions, everything we've already implemented

---

### Step 1.5: Update VisitSelectStatement.java (0.25 days)

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitSelectStatement.java`

**Current implementation:**
```java
public static String v(PlSqlParser.Select_statementContext ctx, PostgresCodeBuilder b) {
  return b.visit(ctx.subquery());
}
```

**New implementation:**
```java
public static String v(PlSqlParser.Select_statementContext ctx, PostgresCodeBuilder b) {
  StringBuilder result = new StringBuilder();

  // Handle WITH clause if present
  if (ctx.with_clause() != null) {
    result.append(VisitWithClause.v(ctx.with_clause(), b));
    result.append(" ");
  }

  // Handle main subquery
  result.append(b.visit(ctx.subquery()));

  return result.toString();
}
```

**Changes:**
- Check for optional `with_clause`
- Call VisitWithClause if present
- Add space separator
- Continue with main subquery

---

### Step 1.6: Update PostgresCodeBuilder.java (0.25 days)

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/PostgresCodeBuilder.java`

**Add visit methods:**
```java
public String visitWith_clause(PlSqlParser.With_clauseContext ctx) {
  return VisitWithClause.v(ctx, this);
}

public String visitWith_factoring_clause(PlSqlParser.With_factoring_clauseContext ctx) {
  return VisitWithFactoringClause.v(ctx, this);
}

public String visitSubquery_factoring_clause(PlSqlParser.Subquery_factoring_clauseContext ctx) {
  return VisitSubqueryFactoringClause.v(ctx, this);
}
```

**Also need to handle paren_column_list if not already implemented:**
```java
public String visitParen_column_list(PlSqlParser.Paren_column_listContext ctx) {
  // Grammar: '(' column_list ')'
  // Just pass through - syntax is identical
  return "(" + b.visit(ctx.column_list()) + ")";
}

public String visitColumn_list(PlSqlParser.Column_listContext ctx) {
  // Grammar: column_name (',' column_name)*
  List<String> columns = new ArrayList<>();
  for (PlSqlParser.Column_nameContext colCtx : ctx.column_name()) {
    columns.add(colCtx.getText());
  }
  return String.join(", ", columns);
}
```

---

### Step 1.7: Testing Phase 1 (0.5 days)

**Create:** `src/test/java/me/christianrobert/orapgsync/transformer/CteBasicTransformationTest.java`

**Test cases:**

1. **Single CTE without column list**
```java
@Test
void singleCteWithoutColumnList() {
  String oracleSql = "WITH dept_totals AS " +
      "(SELECT dept_id, COUNT(*) as cnt FROM departments GROUP BY dept_id) " +
      "SELECT * FROM dept_totals";

  String result = transform(oracleSql);

  assertTrue(result.startsWith("WITH dept_totals AS ("));
  assertTrue(result.contains("FROM hr.departments")); // Schema qualification works!
  assertTrue(result.contains("FROM dept_totals")); // CTE reference preserved
}
```

2. **Single CTE with column list**
```java
@Test
void singleCteWithColumnList() {
  String oracleSql = "WITH dept_totals (dept_id, emp_count) AS " +
      "(SELECT dept_id, COUNT(*) FROM departments GROUP BY dept_id) " +
      "SELECT * FROM dept_totals";

  String result = transform(oracleSql);

  assertTrue(result.contains("WITH dept_totals (dept_id, emp_count) AS ("));
}
```

3. **Multiple CTEs**
```java
@Test
void multipleCtes() {
  String oracleSql = "WITH " +
      "dept_totals AS (SELECT dept_id, COUNT(*) as cnt FROM departments GROUP BY dept_id), " +
      "high_count AS (SELECT dept_id FROM dept_totals WHERE cnt > 10) " +
      "SELECT * FROM high_count";

  String result = transform(oracleSql);

  assertTrue(result.contains("WITH dept_totals AS ("));
  assertTrue(result.contains(", high_count AS ("));
  assertTrue(result.contains("FROM dept_totals WHERE")); // CTE reference in second CTE
}
```

4. **CTE with complex subquery (ORDER BY, JOINs, etc.)**
```java
@Test
void cteWithComplexSubquery() {
  String oracleSql = "WITH emp_dept AS (" +
      "SELECT e.emp_id, e.emp_name, d.dept_name " +
      "FROM employees e " +
      "JOIN departments d ON e.dept_id = d.dept_id " +
      "WHERE e.salary > 50000 " +
      "ORDER BY e.emp_name DESC" +
      ") SELECT * FROM emp_dept";

  String result = transform(oracleSql);

  // Verify all transformations work inside CTE:
  assertTrue(result.contains("FROM hr.employees e")); // Schema qualification
  assertTrue(result.contains("JOIN hr.departments d")); // ANSI JOIN preserved
  assertTrue(result.contains("ORDER BY e . emp_name DESC NULLS FIRST")); // NULL ordering fix
}
```

5. **CTE used in main query WHERE clause**
```java
@Test
void cteUsedInWhereSubquery() {
  String oracleSql = "WITH high_earners AS " +
      "(SELECT emp_id FROM employees WHERE salary > 100000) " +
      "SELECT dept_id FROM departments WHERE dept_id IN (SELECT emp_id FROM high_earners)";

  String result = transform(oracleSql);

  assertTrue(result.contains("WITH high_earners AS ("));
  assertTrue(result.contains("WHERE dept_id IN ( SELECT emp_id FROM high_earners )"));
}
```

6. **Inline PL/SQL function (should throw exception)**
```java
@Test
void inlinePlsqlFunction_throwsException() {
  String oracleSql = "WITH " +
      "FUNCTION double_val(x NUMBER) RETURN NUMBER IS BEGIN RETURN x * 2; END; " +
      "dept_totals AS (SELECT dept_id FROM departments) " +
      "SELECT * FROM dept_totals";

  TransformationException ex = assertThrows(TransformationException.class,
      () -> transform(oracleSql));

  assertTrue(ex.getMessage().contains("Inline PL/SQL"));
  assertTrue(ex.getMessage().contains("not supported"));
}
```

7. **CTE with FROM DUAL**
```java
@Test
void cteWithFromDual() {
  String oracleSql = "WITH constants AS " +
      "(SELECT 1 as one, 2 as two FROM DUAL) " +
      "SELECT one, two FROM constants";

  String result = transform(oracleSql);

  // FROM DUAL should be removed inside CTE
  assertTrue(result.contains("WITH constants AS ( SELECT 1 as one , 2 as two )"));
  assertFalse(result.contains("FROM DUAL"));
  assertFalse(result.contains("FROM dual"));
}
```

8. **CTE with Oracle functions (NVL, DECODE, etc.)**
```java
@Test
void cteWithOracleFunctions() {
  String oracleSql = "WITH processed AS " +
      "(SELECT emp_id, NVL(bonus, 0) as bonus FROM employees) " +
      "SELECT * FROM processed";

  String result = transform(oracleSql);

  // NVL should be transformed to COALESCE inside CTE
  assertTrue(result.contains("COALESCE( bonus , 0 )"));
}
```

**Target:** 15-20 tests covering all non-recursive CTE scenarios

---

## Phase 2: Recursive CTE Support - 1.5 days

### Step 2.1: Understand Recursion Detection (0.5 days)

**What makes a CTE recursive?**

A CTE is recursive if it references itself in its definition. The typical pattern:

```sql
WITH cte_name AS (
  SELECT ... FROM base_table     -- Anchor member (non-recursive)
  UNION ALL
  SELECT ... FROM cte_name       -- Recursive member (references itself!)
)
```

**Detection strategy:**
1. Extract CTE name
2. Walk the CTE's subquery AST
3. Look for table references matching the CTE name
4. If found → recursive, otherwise → non-recursive

**Edge cases to consider:**
- Mutually recursive CTEs: `cte1` references `cte2`, `cte2` references `cte1`
  - PostgreSQL supports this (WITH RECURSIVE covers all CTEs in the clause)
  - Oracle also supports this
  - Solution: If ANY CTE is recursive, add RECURSIVE keyword to entire WITH clause

---

### Step 2.2: Create CteRecursionAnalyzer.java (0.75 days)

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/cte/CteRecursionAnalyzer.java`

**Responsibilities:**
1. Analyze a CTE definition to detect self-reference
2. Walk subquery AST to find table references
3. Return boolean: isRecursive

**Implementation:**

```java
package me.christianrobert.orapgsync.transformer.builder.cte;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.HashSet;
import java.util.Set;

/**
 * Analyzes CTE definitions to detect recursion.
 *
 * <p>A CTE is recursive if it references itself in its subquery definition.</p>
 *
 * <p>Example:</p>
 * <pre>
 * WITH emp_tree AS (
 *   SELECT ... FROM employees          -- Base case (no reference to emp_tree)
 *   UNION ALL
 *   SELECT ... FROM emp_tree           -- Recursive case (references emp_tree!)
 * )
 * </pre>
 *
 * <p>Detection strategy:</p>
 * <ul>
 *   <li>Extract CTE name</li>
 *   <li>Walk subquery AST to find all table references</li>
 *   <li>Check if CTE name appears in table references</li>
 *   <li>If yes → recursive, otherwise → non-recursive</li>
 * </ul>
 *
 * <p>Note: PostgreSQL requires RECURSIVE keyword even for mutually recursive CTEs
 * (cte1 references cte2, cte2 references cte1). We handle this by checking if
 * ANY CTE in the WITH clause is recursive.</p>
 */
public class CteRecursionAnalyzer {

  /**
   * Check if a CTE references itself.
   *
   * @param ctx The subquery_factoring_clause context
   * @return true if CTE is recursive, false otherwise
   */
  public static boolean isRecursive(PlSqlParser.Subquery_factoring_clauseContext ctx) {
    // Get CTE name
    String cteName = ctx.query_name().getText().toLowerCase();

    // Get the subquery
    PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();

    // Extract all table references from the subquery
    Set<String> tableReferences = extractTableReferences(subqueryCtx);

    // Check if CTE name appears in table references
    return tableReferences.contains(cteName);
  }

  /**
   * Extract all table names referenced in a subquery.
   * Walks the AST to find all tableview_name nodes.
   */
  private static Set<String> extractTableReferences(PlSqlParser.SubqueryContext ctx) {
    TableReferenceCollector collector = new TableReferenceCollector();
    collector.visit(ctx);
    return collector.getTableNames();
  }

  /**
   * AST visitor that collects all table references.
   */
  private static class TableReferenceCollector {
    private final Set<String> tableNames = new HashSet<>();

    public Set<String> getTableNames() {
      return tableNames;
    }

    public void visit(ParseTree tree) {
      if (tree == null) {
        return;
      }

      // Check if this node is a tableview_name
      if (tree instanceof PlSqlParser.Tableview_nameContext) {
        PlSqlParser.Tableview_nameContext tableCtx = (PlSqlParser.Tableview_nameContext) tree;

        // Extract table name (ignoring schema qualifier)
        // Grammar: tableview_name : id_expression ('.' id_expression)?
        String tableName;
        if (tableCtx.id_expression().size() == 2) {
          // schema.table → take table part
          tableName = tableCtx.id_expression(1).getText();
        } else {
          // just table
          tableName = tableCtx.id_expression(0).getText();
        }

        tableNames.add(tableName.toLowerCase());
      }

      // Recursively visit children
      for (int i = 0; i < tree.getChildCount(); i++) {
        visit(tree.getChild(i));
      }
    }
  }
}
```

**Testing the analyzer (unit tests):**
```java
@Test
void nonRecursiveCte() {
  // WITH cte AS (SELECT ... FROM base_table)
  // Should return false
}

@Test
void recursiveCte() {
  // WITH cte AS (SELECT ... FROM base UNION ALL SELECT ... FROM cte)
  // Should return true
}

@Test
void recursiveCteWithSchemaQualification() {
  // WITH cte AS (... FROM schema.cte)
  // Should return true (ignores schema prefix)
}
```

---

### Step 2.3: Update VisitWithClause.java to Add RECURSIVE Keyword (0.25 days)

**Modify the `v()` method:**

```java
public static String v(PlSqlParser.With_clauseContext ctx, PostgresCodeBuilder b) {
  // 1. Check for inline PL/SQL (same as before)
  if (!ctx.function_body().isEmpty() || !ctx.procedure_body().isEmpty()) {
    throw new TransformationException(
        "Inline PL/SQL functions/procedures in WITH clause are not supported..."
    );
  }

  StringBuilder result = new StringBuilder("WITH ");

  // 2. NEW: Detect if any CTE is recursive
  boolean hasRecursiveCte = false;
  for (PlSqlParser.With_factoring_clauseContext cte : ctx.with_factoring_clause()) {
    if (cte.subquery_factoring_clause() != null) {
      if (CteRecursionAnalyzer.isRecursive(cte.subquery_factoring_clause())) {
        hasRecursiveCte = true;
        break; // Found one, no need to check others
      }
    }
  }

  // 3. NEW: Add RECURSIVE keyword if needed
  if (hasRecursiveCte) {
    result.append("RECURSIVE ");
  }

  // 4. Process each CTE (same as before)
  List<PlSqlParser.With_factoring_clauseContext> ctes = ctx.with_factoring_clause();
  for (int i = 0; i < ctes.size(); i++) {
    if (i > 0) {
      result.append(", ");
    }
    result.append(VisitWithFactoringClause.v(ctes.get(i), b));
  }

  return result.toString();
}
```

**Key changes:**
1. Loop through all CTEs before processing
2. Check each for recursion using CteRecursionAnalyzer
3. If ANY CTE is recursive, add RECURSIVE keyword
4. Then process CTEs normally (no other changes needed!)

---

### Step 2.4: Testing Phase 2 (0.5 days)

**Create:** `src/test/java/me/christianrobert/orapgsync/transformer/CteRecursiveTransformationTest.java`

**Test cases:**

1. **Simple recursive CTE (employee hierarchy)**
```java
@Test
void simpleRecursiveCte() {
  String oracleSql = "WITH emp_tree AS (" +
      "SELECT emp_id, mgr_id, 1 as lvl FROM employees WHERE mgr_id IS NULL " +
      "UNION ALL " +
      "SELECT e.emp_id, e.mgr_id, t.lvl+1 FROM employees e JOIN emp_tree t ON e.mgr_id = t.emp_id" +
      ") SELECT * FROM emp_tree";

  String result = transform(oracleSql);

  assertTrue(result.startsWith("WITH RECURSIVE emp_tree AS ("));
}
```

2. **Recursive CTE with column list**
```java
@Test
void recursiveCteWithColumnList() {
  String oracleSql = "WITH emp_tree (emp_id, mgr_id, lvl) AS (" +
      "SELECT emp_id, mgr_id, 1 FROM employees WHERE mgr_id IS NULL " +
      "UNION ALL " +
      "SELECT e.emp_id, e.mgr_id, t.lvl+1 FROM employees e JOIN emp_tree t ON e.mgr_id = t.emp_id" +
      ") SELECT * FROM emp_tree";

  String result = transform(oracleSql);

  assertTrue(result.startsWith("WITH RECURSIVE emp_tree (emp_id, mgr_id, lvl) AS ("));
}
```

3. **Multiple CTEs, one recursive**
```java
@Test
void multipleCtes_oneRecursive() {
  String oracleSql = "WITH " +
      "base AS (SELECT emp_id FROM employees WHERE dept_id = 10), " +
      "tree AS (SELECT emp_id FROM base UNION ALL SELECT e.emp_id FROM employees e JOIN tree t ON e.mgr_id = t.emp_id) " +
      "SELECT * FROM tree";

  String result = transform(oracleSql);

  // Should add RECURSIVE because second CTE is recursive
  assertTrue(result.startsWith("WITH RECURSIVE base AS ("));
}
```

4. **Non-recursive CTE should NOT add RECURSIVE**
```java
@Test
void nonRecursiveCte_noRecursiveKeyword() {
  String oracleSql = "WITH dept_totals AS " +
      "(SELECT dept_id, COUNT(*) FROM departments GROUP BY dept_id) " +
      "SELECT * FROM dept_totals";

  String result = transform(oracleSql);

  assertTrue(result.startsWith("WITH dept_totals AS ("));
  assertFalse(result.contains("RECURSIVE"));
}
```

5. **Recursive CTE with UNION (not UNION ALL)**
```java
@Test
void recursiveCteWithUnion() {
  // Oracle allows UNION (removes duplicates) in recursive CTEs
  // PostgreSQL also allows this
  String oracleSql = "WITH tree AS (" +
      "SELECT 1 as n FROM dual " +
      "UNION " +
      "SELECT n+1 FROM tree WHERE n < 10" +
      ") SELECT * FROM tree";

  String result = transform(oracleSql);

  assertTrue(result.contains("WITH RECURSIVE tree AS ("));
}
```

6. **Deeply nested recursion**
```java
@Test
void deeplyNestedRecursion() {
  String oracleSql = "WITH RECURSIVE numbers AS (" +
      "SELECT 1 as n " +
      "UNION ALL " +
      "SELECT n + 1 FROM numbers WHERE n < 100" +
      ") SELECT * FROM numbers";

  String result = transform(oracleSql);

  assertTrue(result.contains("WITH RECURSIVE numbers AS ("));
  assertTrue(result.contains("WHERE n < 100"));
}
```

7. **Recursive CTE referencing itself with schema qualifier**
```java
@Test
void recursiveCte_withSchemaQualifier() {
  // Oracle sometimes qualifies CTE references with schema (though unusual)
  String oracleSql = "WITH tree AS (" +
      "SELECT 1 as n FROM dual " +
      "UNION ALL " +
      "SELECT n+1 FROM hr.tree WHERE n < 5" +
      ") SELECT * FROM tree";

  String result = transform(oracleSql);

  // Should detect recursion even with schema prefix
  assertTrue(result.contains("WITH RECURSIVE tree AS ("));
}
```

**Target:** 12-15 tests covering recursive CTE scenarios

---

## Total Implementation Summary

### Files to Create (7 new files):

1. `VisitWithClause.java` - Main WITH clause handler
2. `VisitWithFactoringClause.java` - Router for CTE types
3. `VisitSubqueryFactoringClause.java` - Individual CTE transformation
4. `CteRecursionAnalyzer.java` - Recursion detection logic
5. `CteBasicTransformationTest.java` - 15-20 tests
6. `CteRecursiveTransformationTest.java` - 12-15 tests

### Files to Modify (2 existing files):

1. `VisitSelectStatement.java` - Add WITH clause handling
2. `PostgresCodeBuilder.java` - Add visit methods

### Test Count:
- Non-recursive CTEs: 15-20 tests
- Recursive CTEs: 12-15 tests
- **Total: ~30 tests**

### Timeline:
- **Day 1:** Grammar understanding + VisitWithClause + VisitWithFactoringClause + VisitSubqueryFactoringClause
- **Day 2:** Update existing files + Testing Phase 1 (non-recursive)
- **Day 3:** CteRecursionAnalyzer + Update VisitWithClause + Testing Phase 2 (recursive)
- **Buffer:** 0.5 days for edge cases and debugging

**Total: 3-3.5 days**

---

## Key Success Criteria

1. ✅ Non-recursive CTEs work (pass-through)
2. ✅ Recursive CTEs detected and RECURSIVE keyword added
3. ✅ Multiple CTEs handled correctly
4. ✅ Column lists preserved
5. ✅ All existing transformations work inside CTEs (schema qualification, ORDER BY fix, etc.)
6. ✅ Inline PL/SQL functions throw clear exception
7. ✅ 30+ tests passing
8. ✅ Real-world Oracle views with CTEs transform successfully

---

## Risk Mitigation

**Risk 1: Complex recursion patterns not detected**
- Mitigation: Comprehensive AST walking in CteRecursionAnalyzer
- Fallback: Conservative approach - if uncertain, add RECURSIVE keyword (no harm in PostgreSQL)

**Risk 2: Mutually recursive CTEs**
- Mitigation: Check ALL CTEs before processing, add RECURSIVE if ANY is recursive

**Risk 3: CTE references with aliases**
- Mitigation: Handle both qualified and unqualified table references in analyzer

**Risk 4: Performance of AST walking**
- Mitigation: Only walk CTE subqueries (not entire statement), cache results if needed
