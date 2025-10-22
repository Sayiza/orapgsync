# CONNECT BY Implementation Plan

**Last Updated:** 2025-10-22
**Status:** 🔄 **IN PROGRESS**
**Estimated Effort:** 5-7 days
**Coverage Impact:** +8-10 percentage points (82% → 90%)

---

## Abstract Transformation Logic

### Oracle CONNECT BY Structure
```sql
SELECT empno, ename, mgr, LEVEL
FROM emp
WHERE salary > 1000                    -- Optional filter
START WITH mgr IS NULL                 -- Root nodes
CONNECT BY PRIOR empno = mgr           -- Parent-child relationship
ORDER SIBLINGS BY ename;               -- Preserve hierarchical order (challenging)
```

### PostgreSQL Recursive CTE Equivalent
```sql
WITH RECURSIVE emp_hierarchy AS (
  -- Base case: START WITH condition
  SELECT empno, ename, mgr, 1 as level
  FROM emp
  WHERE mgr IS NULL                    -- START WITH
    AND salary > 1000                  -- Original WHERE moved here

  UNION ALL

  -- Recursive case: CONNECT BY condition
  SELECT e.empno, e.ename, e.mgr, eh.level + 1
  FROM emp e
  JOIN emp_hierarchy eh ON e.mgr = eh.empno  -- Derived from PRIOR
  WHERE e.salary > 1000                -- Original WHERE applied here too
)
SELECT empno, ename, mgr, level
FROM emp_hierarchy
ORDER BY ename;  -- Note: ORDER SIBLINGS BY not directly supported
```

---

## Key Transformation Components

### 1. PRIOR Keyword Translation
```sql
-- Oracle: PRIOR indicates "parent" side
CONNECT BY PRIOR emp_id = manager_id
-- Means: parent.emp_id = child.manager_id

-- PostgreSQL: JOIN direction
JOIN cte ON child.manager_id = cte.emp_id
```

**Cases to handle:**
- `PRIOR col1 = col2` → `t.col2 = cte.col1`
- `col1 = PRIOR col2` → `t.col1 = cte.col2`
- Multiple conditions: `PRIOR col1 = col2 AND col3 = col4`

### 2. WHERE Clause Distribution
- Original WHERE must apply to BOTH base and recursive cases
- Prevents filtering out intermediate nodes in hierarchy

### 3. LEVEL Pseudo-Column
- Add explicit counter column in CTE
- Initialize to 1 in base case
- Increment in recursive case: `cte.level + 1`
- Replace all LEVEL references in SELECT/WHERE with column reference

### 4. Pseudo-Columns (Advanced Features)
- `CONNECT_BY_ROOT col` → Carry root value through recursion
- `SYS_CONNECT_BY_PATH(col, '/')` → Build path string incrementally
- `CONNECT_BY_ISLEAF` → `NOT EXISTS (SELECT 1 FROM cte WHERE ...)`

---

## Architecture Design

### Module Organization

All CONNECT BY logic in: `src/main/java/.../transformer/builder/connectby/`

**Classes:**
```
connectby/
├── ConnectByComponents.java          // Data class: analyzed components
├── ConnectByAnalyzer.java            // Main analyzer: orchestrates analysis
├── PriorExpressionAnalyzer.java      // Analyzes PRIOR expressions for JOIN logic
├── PriorExpression.java              // Data class: PRIOR expression structure
├── LevelReferenceReplacer.java       // Replaces LEVEL pseudo-column references
├── HierarchicalQueryTransformer.java // Main transformer: generates recursive CTE
└── ConnectByPseudoColumns.java       // (Future) Handles advanced pseudo-columns
```

### Integration Point

**Minimal change to `VisitQueryBlock.java`:**
```java
public static String v(PlSqlParser.Query_blockContext ctx, PostgresCodeBuilder b) {

  // Detect CONNECT BY first (before normal processing)
  if (ctx.hierarchical_query_clause() != null &&
      !ctx.hierarchical_query_clause().isEmpty()) {
    return HierarchicalQueryTransformer.transform(ctx, b);
  }

  // Existing code: normal query transformation
  // ...
}
```

### Delegation Strategy

**Key principle:** Reuse existing visitor infrastructure for sub-components

```
HierarchicalQueryTransformer
├── Analyzes CONNECT BY structure
├── Generates CTE skeleton (strings)
└── Delegates sub-transformations to PostgresCodeBuilder:
    ├── b.visit(selectedListCtx) → SELECT list transformation
    ├── b.visit(fromClauseCtx) → FROM clause transformation
    ├── b.visit(whereClauseCtx) → WHERE clause transformation
    └── b.visit(orderByClauseCtx) → ORDER BY transformation
```

**Benefits:**
- Schema qualification works automatically
- Type methods transform correctly
- Package functions transform correctly
- Oracle function conversions (NVL, DECODE, etc.) work
- All future enhancements automatically apply to CONNECT BY

---

## Implementation Phases

### Phase 1: Analysis Infrastructure ⏳ IN PROGRESS
**Goal:** Extract all CONNECT BY components from AST

**Tasks:**
1. ✅ Create `ConnectByComponents.java` - data class for analyzed structure
2. ✅ Create `PriorExpression.java` - data class for PRIOR expression analysis
3. 🔄 Create `PriorExpressionAnalyzer.java` - parse PRIOR expressions
4. 🔄 Create `ConnectByAnalyzer.java` - main analyzer orchestrator
5. ⏸️ Create `LevelReferenceReplacer.java` - LEVEL pseudo-column handling

**ConnectByComponents structure:**
```java
public class ConnectByComponents {
  // Core components
  private final PlSqlParser.Start_partContext startWith;        // May be null
  private final PlSqlParser.ConditionContext connectByCondition;
  private final boolean hasNoCycle;

  // Table information
  private final String baseTableName;
  private final String baseTableAlias;

  // PRIOR expression analysis
  private final PriorExpression priorExpression;

  // Pseudo-column usage tracking
  private final boolean usesLevelInSelect;
  private final boolean usesLevelInWhere;
  private final Set<String> levelReferencePaths;  // For replacement

  // Advanced features (Phase 5)
  private final boolean usesConnectByRoot;
  private final boolean usesConnectByPath;
  private final boolean usesConnectByIsLeaf;
}
```

**PriorExpression structure:**
```java
public class PriorExpression {
  private final boolean priorOnLeft;           // PRIOR emp.id = emp.mgr
  private final String priorColumnExpression;  // "emp.emp_id" or "emp_id"
  private final String childColumnExpression;  // "emp.manager_id" or "manager_id"

  // Methods for generating JOIN condition
  public String generateJoinCondition(String childAlias, String cteAlias);
}
```

**Testing:**
- Unit tests for PriorExpressionAnalyzer (various PRIOR positions)
- Unit tests for ConnectByAnalyzer (complete component extraction)

---

### Phase 2: Basic CTE Generation ⏸️ NOT STARTED
**Goal:** Generate recursive CTE for simple CONNECT BY

**Tasks:**
1. Create `HierarchicalQueryTransformer.java`
2. Implement `buildBaseCase()` - START WITH → base CTE member
3. Implement `buildRecursiveCase()` - CONNECT BY → recursive CTE member
4. Implement `buildFinalSelect()` - outer SELECT from CTE
5. Implement `assembleCte()` - combine all parts

**Base case generation:**
```java
private static String buildBaseCase(
    ConnectByComponents components,
    PlSqlParser.Query_blockContext ctx,
    PostgresCodeBuilder b) {

  // SELECT {columns}, 1 as level
  String selectList = buildSelectListWithLevel(ctx, b, "1");

  // FROM {table}
  String fromClause = b.visit(ctx.from_clause());

  // WHERE {start_with_condition} [AND {original_where}]
  String whereClause = buildBaseCaseWhere(components, ctx, b);

  return "SELECT " + selectList +
         " FROM " + fromClause +
         " WHERE " + whereClause;
}
```

**Recursive case generation:**
```java
private static String buildRecursiveCase(
    ConnectByComponents components,
    PlSqlParser.Query_blockContext ctx,
    String cteName,
    PostgresCodeBuilder b) {

  // SELECT {child_alias}.{columns}, {cte_alias}.level + 1
  String selectList = buildSelectListWithLevel(ctx, b, cteName + ".level + 1");

  // FROM {table} {child_alias}
  String childAlias = generateChildAlias(components);
  String fromClause = buildFromWithAlias(ctx, childAlias, b);

  // JOIN {cte_name} {cte_alias} ON {join_condition}
  String joinClause = buildRecursiveJoin(components, childAlias, cteName);

  // WHERE {original_where} (if present)
  String whereClause = buildRecursiveCaseWhere(ctx, childAlias, b);

  return "SELECT " + selectList +
         " FROM " + fromClause +
         " " + joinClause +
         (whereClause != null ? " WHERE " + whereClause : "");
}
```

**Testing:**
- Simple hierarchy (employees table, manager-employee relationship)
- START WITH null check
- Basic PRIOR expression
- Target: 5-8 tests passing

---

### Phase 3: LEVEL and WHERE Handling ⏸️ NOT STARTED
**Goal:** Support LEVEL pseudo-column and WHERE clause distribution

**Tasks:**
1. Implement `LevelReferenceReplacer` visitor
2. Handle LEVEL in SELECT list
3. Handle LEVEL in WHERE clause (filtering after hierarchy built)
4. Distribute original WHERE to both base and recursive cases

**LEVEL replacement strategy:**
```java
public class LevelReferenceReplacer {
  // Scans SELECT list and WHERE for LEVEL references
  // Replaces with column reference: level

  public static String replaceInExpression(
      PlSqlParser.ExpressionContext ctx,
      PostgresCodeBuilder b) {
    // Walk expression tree
    // If atom is LEVEL identifier → return "level"
    // Otherwise → delegate to normal visitor
  }
}
```

**WHERE clause distribution:**
```sql
-- Oracle
SELECT emp_id FROM emp
WHERE salary > 50000         -- Must apply to both base and recursive!
CONNECT BY PRIOR emp_id = mgr;

-- PostgreSQL
WITH RECURSIVE emp_hierarchy AS (
  SELECT emp_id, 1 as level FROM emp
  WHERE mgr IS NULL AND salary > 50000      -- Base case
  UNION ALL
  SELECT e.emp_id, eh.level + 1 FROM emp e
  JOIN emp_hierarchy eh ON e.mgr = eh.emp_id
  WHERE e.salary > 50000                    -- Recursive case
)
SELECT emp_id FROM emp_hierarchy;
```

**Testing:**
- LEVEL in SELECT list
- LEVEL in WHERE clause (e.g., `WHERE LEVEL <= 3`)
- WHERE clause with business filters
- Combined: WHERE + LEVEL
- Target: 10-15 tests passing total

---

### Phase 4: Integration and Edge Cases ⏸️ NOT STARTED
**Goal:** Integrate with VisitQueryBlock, handle complex cases

**Tasks:**
1. Modify `VisitQueryBlock.java` (minimal change - detection only)
2. Handle reversed PRIOR (`col = PRIOR col` instead of `PRIOR col = col`)
3. Handle complex PRIOR conditions (AND, multiple comparisons)
4. Handle ORDER BY (not ORDER SIBLINGS BY)
5. CTE name conflict detection and resolution

**Complex PRIOR conditions:**
```sql
-- Oracle: Multiple conditions in CONNECT BY
CONNECT BY PRIOR emp_id = mgr AND dept_id = PRIOR dept_id

-- PostgreSQL: Multiple JOIN conditions
JOIN emp_hierarchy eh ON e.mgr = eh.emp_id AND e.dept_id = eh.dept_id
```

**CTE name conflicts:**
```sql
-- Oracle: Existing WITH + CONNECT BY
WITH emp_summary AS (...)
SELECT * FROM emp
CONNECT BY PRIOR emp_id = mgr;

-- PostgreSQL: Need to merge CTEs
WITH RECURSIVE
  emp_summary AS (...),
  emp_hierarchy AS (...)  -- Generated with unique name
SELECT * FROM emp_hierarchy;
```

**Testing:**
- Reversed PRIOR
- Multiple PRIOR conditions
- Existing WITH clause + CONNECT BY
- ORDER BY (convert to simple ORDER BY)
- Target: 20-25 tests passing total

---

### Phase 5: Advanced Features (Optional) ⏸️ NOT STARTED
**Goal:** Support advanced Oracle hierarchical query features

**Tasks:**
1. CONNECT_BY_ROOT pseudo-column
2. SYS_CONNECT_BY_PATH function
3. CONNECT_BY_ISLEAF pseudo-column
4. NOCYCLE handling (best-effort warning)
5. ORDER SIBLINGS BY (document limitation or best-effort)

**CONNECT_BY_ROOT implementation:**
```sql
-- Oracle
SELECT emp_id, CONNECT_BY_ROOT emp_id as root_emp
FROM emp
CONNECT BY PRIOR emp_id = mgr;

-- PostgreSQL
WITH RECURSIVE emp_hierarchy AS (
  SELECT emp_id, emp_id as root_emp, 1 as level
  FROM emp WHERE mgr IS NULL
  UNION ALL
  SELECT e.emp_id, eh.root_emp, eh.level + 1  -- Preserve root
  FROM emp e JOIN emp_hierarchy eh ON e.mgr = eh.emp_id
)
SELECT emp_id, root_emp FROM emp_hierarchy;
```

**SYS_CONNECT_BY_PATH implementation:**
```sql
-- Oracle
SELECT SYS_CONNECT_BY_PATH(emp_name, '/') as path
FROM emp
CONNECT BY PRIOR emp_id = mgr;

-- PostgreSQL
WITH RECURSIVE emp_hierarchy AS (
  SELECT emp_id, '/' || emp_name as path, 1 as level
  FROM emp WHERE mgr IS NULL
  UNION ALL
  SELECT e.emp_id, eh.path || '/' || e.emp_name, eh.level + 1
  FROM emp e JOIN emp_hierarchy eh ON e.mgr = eh.emp_id
)
SELECT path FROM emp_hierarchy;
```

**Testing:**
- CONNECT_BY_ROOT with different columns
- SYS_CONNECT_BY_PATH with various separators
- CONNECT_BY_ISLEAF detection
- Target: 30-35 tests passing total

---

## Grammar Reference

### Hierarchical Query Clause
```antlr
hierarchical_query_clause
    : CONNECT BY NOCYCLE? condition start_part?
    | start_part CONNECT BY NOCYCLE? condition
    ;

start_part
    : START WITH condition
    ;
```

### PRIOR in Expressions
```antlr
unary_expression
    : ('-' | '+') unary_expression
    | PRIOR unary_expression              # PRIOR operator
    | CONNECT_BY_ROOT unary_expression
    | ...
    ;
```

### Query Block Context
```antlr
query_block
    : SELECT ... from_clause? where_clause?
      (hierarchical_query_clause | group_by_clause)*
      order_by_clause? ...
    ;
```

**Key observations:**
- `hierarchical_query_clause` appears AFTER WHERE, BEFORE/INSTEAD OF GROUP BY
- START WITH can come before or after CONNECT BY
- PRIOR is a unary operator in expressions
- NOCYCLE is optional flag

---

## Testing Strategy

### Test Progression

**Phase 1 Tests: Simple hierarchy**
```java
@Test
public void testSimpleHierarchy() {
  String oracle = """
    SELECT emp_id, manager_id
    FROM employees
    START WITH manager_id IS NULL
    CONNECT BY PRIOR emp_id = manager_id
    """;
  // Verify CTE generation with correct JOIN
}
```

**Phase 2 Tests: LEVEL usage**
```java
@Test
public void testWithLevelInSelect() {
  String oracle = """
    SELECT emp_id, LEVEL
    FROM employees
    START WITH manager_id IS NULL
    CONNECT BY PRIOR emp_id = manager_id
    """;
  // Verify LEVEL → level column replacement
}
```

**Phase 3 Tests: WHERE clause**
```java
@Test
public void testWithWhereClause() {
  String oracle = """
    SELECT emp_id
    FROM employees
    WHERE salary > 50000
    START WITH manager_id IS NULL
    CONNECT BY PRIOR emp_id = manager_id
    """;
  // Verify WHERE appears in both base and recursive cases
}
```

**Phase 4 Tests: Complex cases**
```java
@Test
public void testReversedPrior() {
  String oracle = """
    SELECT emp_id
    FROM employees
    CONNECT BY manager_id = PRIOR emp_id
    """;
  // Verify reversed JOIN direction
}

@Test
public void testMultiplePriorConditions() {
  String oracle = """
    SELECT emp_id
    FROM employees
    CONNECT BY PRIOR emp_id = manager_id
           AND PRIOR dept_id = dept_id
    """;
  // Verify multiple JOIN conditions
}
```

**Phase 5 Tests: Advanced features**
```java
@Test
public void testConnectByRoot() {
  String oracle = """
    SELECT emp_id, CONNECT_BY_ROOT emp_id as root
    FROM employees
    CONNECT BY PRIOR emp_id = manager_id
    """;
  // Verify root column propagation
}
```

### Test Coverage Goals
- **Phase 1:** 5-8 tests (basic CTE generation)
- **Phase 2:** 10-15 tests (LEVEL support)
- **Phase 3:** 20-25 tests (WHERE and edge cases)
- **Phase 4:** 30-35 tests (advanced features)
- **Target:** 35+ comprehensive tests

---

## Known Limitations

### ORDER SIBLINGS BY
**Challenge:** Oracle's ORDER SIBLINGS BY maintains hierarchical structure while sorting siblings at each level.

**PostgreSQL issue:** No direct equivalent. Options:
1. Convert to simple ORDER BY (loses sibling semantics)
2. Add complex ordering logic in CTE (may not match Oracle exactly)
3. Document as unsupported

**Recommendation:** Option 3 initially. Throw clear error message with guidance:
```
ORDER SIBLINGS BY is not directly supported in PostgreSQL.
Consider:
1. Use simple ORDER BY (loses hierarchical ordering)
2. Add manual ordering logic in application layer
3. Use window functions with path-based ordering
```

### NOCYCLE
**Challenge:** Oracle's NOCYCLE prevents infinite loops in cyclic hierarchies.

**PostgreSQL issue:** Recursive CTEs have no built-in cycle detection.

**Recommendation:** Document limitation, suggest manual cycle detection in CTE:
```sql
WITH RECURSIVE emp_hierarchy AS (
  SELECT emp_id, manager_id, ARRAY[emp_id] as path, 1 as level
  FROM emp WHERE manager_id IS NULL
  UNION ALL
  SELECT e.emp_id, e.manager_id,
         eh.path || e.emp_id,
         eh.level + 1
  FROM emp e JOIN emp_hierarchy eh ON e.manager_id = eh.emp_id
  WHERE NOT (e.emp_id = ANY(eh.path))  -- Cycle detection
)
```

### Performance
**Note:** PostgreSQL recursive CTEs can be slower than Oracle's CONNECT BY (different execution strategies).

**Recommendation:** Document performance considerations, suggest indexing strategies.

---

## Success Criteria

### Phase Completion Requirements
Each phase is complete when:
1. ✅ All planned transformations implemented
2. ✅ Test coverage ≥ 90% for new code
3. ✅ All tests passing
4. ✅ Documentation updated
5. ✅ Real-world validation with sample queries

### Overall Success Metrics
- **90% coverage** of CONNECT BY queries transform successfully
- **No regressions** in existing functionality
- **Clear error messages** for unsupported features (ORDER SIBLINGS BY, etc.)
- **Performance** acceptable (transformation time < 1s per query)

---

## Implementation Timeline

| Phase | Description | Days | Tests | Status |
|-------|-------------|------|-------|--------|
| **Phase 1** | **Analysis Infrastructure** | **1-2** | **5-8** | **🔄 IN PROGRESS** |
| Phase 2 | Basic CTE Generation | 1-2 | 10-15 | ⏸️ NOT STARTED |
| Phase 3 | LEVEL and WHERE Handling | 1 | 20-25 | ⏸️ NOT STARTED |
| Phase 4 | Integration and Edge Cases | 1-2 | 30-35 | ⏸️ NOT STARTED |
| Phase 5 | Advanced Features (Optional) | 1-2 | 35+ | ⏸️ NOT STARTED |
| **Total** | - | **5-7** | **35+** | **🔄 IN PROGRESS** |

---

## References

- [TRANSFORMATION_ROADMAP.md](TRANSFORMATION_ROADMAP.md) - Overall transformation roadmap
- [CTE_IMPLEMENTATION_PLAN.md](CTE_IMPLEMENTATION_PLAN.md) - CTE implementation (similar pattern)
- [CLAUDE.md](CLAUDE.md) - Project architecture and development guidelines
- [TRANSFORMATION.md](TRANSFORMATION.md) - SQL transformation module documentation

---

**Last Review:** 2025-10-22
**Next Review:** After Phase 1 completion
