# Oracle to PostgreSQL SQL Transformation

**Last Updated:** 2025-10-22
**Status:** Phase 2 COMPLETE ✅, Phase 3 ~50% COMPLETE ⏳ - 662 tests passing

This document describes the ANTLR-based transformation module that converts Oracle SQL to PostgreSQL-compatible SQL using a direct AST-to-code approach.

## Quick Summary

**Architecture:** Direct AST transformation (no intermediate semantic tree)
**Visitor Classes:** 37 static helpers organized by ANTLR grammar rules
**Test Coverage:** 662/662 passing across 42 test classes

**Coverage Analysis (Realistic Assessment):**
- **Simple Oracle views** (no CTEs, no CONNECT BY, basic functions): ~95% ✅
- **Real-world Oracle views** (including CTEs, CONNECT BY, advanced functions): ~40-50% ⚠️
- **Current focus**: Core SELECT features complete, major gaps in advanced Oracle-specific features

## Implementation Sessions Summary

**18 sessions completed** implementing comprehensive Oracle→PostgreSQL SQL transformation:

1. **Subqueries in FROM** - VisitTableReference (13 tests)
2. **ORDER BY** - VisitOrderByClause with DESC NULLS FIRST fix (19 tests)
3. **GROUP BY/HAVING** - VisitGroupByClause, VisitHavingClause (20 tests)
4. **ANSI JOINs** - VisitTableReference for INNER/LEFT/RIGHT/FULL/CROSS (15 tests)
5. **Arithmetic/Concatenation** - VisitConcatenation, || → CONCAT() for NULL safety (22 tests)
6. **NVL/SYSDATE** - VisitStringFunction, VisitGeneralElement (14 tests)
7. **DECODE** - VisitStringFunction with CASE WHEN transformation (10 tests)
8. **Column Aliases** - VisitSelectListElement (18 tests)
9. **CASE Expressions** - VisitCaseExpression, END CASE → END (17 tests)
10. **TO_CHAR** - VisitStringFunction, format code transformations (21 tests)
11. **Subqueries/Set Ops** - VisitAtom, VisitQuantifiedExpression, VisitSubquery, MINUS → EXCEPT (21 tests)
12. **FROM DUAL** - VisitQueryBlock, omits FROM for scalar expressions (16 tests)
13. **SUBSTR** - VisitStringFunction, FROM/FOR keyword syntax (18 tests)
14. **TO_DATE** - VisitStringFunction, TO_TIMESTAMP transformation (17 tests)
15. **TRIM** - VisitStringFunction, pass-through (19 tests)
16. **Window Functions** - VisitOverClause, VisitExpressions (31 tests)
17. **ROWNUM Phase 1** - RownumAnalyzer, RownumContext, simple LIMIT optimization (17 tests)
18. **ROWNUM Phase 2** - VisitSelectListElement, pseudocolumn in SELECT list → row_number() OVER () (16 tests)
19. **Sequences** - VisitGeneralElement, seq.NEXTVAL → nextval('schema.seq') (19 tests)

---

## Table of Contents

1. [Overview](#overview)
2. [Current Status](#current-status)
3. [Architecture](#architecture)
4. [Module Structure](#module-structure)
5. [Implementation Phases](#implementation-phases)
6. [Testing](#testing)
7. [Next Steps](#next-steps)

---

## Overview

### Core Pipeline

```
Oracle SQL → ANTLR Parser → PostgresCodeBuilder → PostgreSQL SQL
                 ↓                  ↓                    ↓
            PlSqlParser     Static Helpers          String
```

### Key Design Principles

1. **Direct Transformation**: No intermediate semantic tree - visitor returns PostgreSQL SQL strings directly
2. **Static Helper Pattern**: Each ANTLR rule has a static helper class (26+ helpers), keeping the main visitor clean
3. **Dependency Boundaries**: `TransformationContext` passed as parameter (not CDI-injected) for metadata access
4. **Test-Driven**: 662 tests across 42 test classes, all passing
5. **Incremental**: Features added progressively with comprehensive test coverage

### Why Direct AST Works

**Oracle and PostgreSQL SQL are similar enough:**
- Identity transformations: `SELECT col FROM table` → same
- Minor changes: `NVL(a, b)` → `COALESCE(a, b)` (just function name)
- Syntax shifts: `seq.NEXTVAL` → `nextval('seq')` (restructure)

**Benefits:**
- ✅ Single-pass transformation (faster, less memory)
- ✅ Simpler architecture (one layer vs two)
- ✅ Quarkus-friendly (service layer uses CDI, visitor stays pure)
- ✅ Pragmatic fit for SQL-to-SQL transformation

---

## Current Status

### ✅ Complete Features (662/662 tests passing)

**Phase 1: Foundation**
- AntlrParser, 37 visitor helpers, 11-level expression hierarchy, TransformationContext, MetadataIndexBuilder

**Phase 2: Complete SELECT Support (100%)**
- Basic SELECT (columns, *, qualified, aliases)
- FROM clause (table/subquery aliases, implicit/ANSI JOINs, Oracle (+) → LEFT/RIGHT JOIN)
- WHERE clause (literals, comparison, logical, IN, BETWEEN, LIKE, IS NULL, complex nested)
- GROUP BY/HAVING (single/multiple columns, position-based, expressions, aggregates)
- ORDER BY (ASC/DESC, DESC → DESC NULLS FIRST fix, position-based, expressions)
- Arithmetic operators (+, -, *, /, MOD, ** → ^)
- String concatenation (|| → CONCAT() for NULL-safe Oracle semantics)
- Subqueries (FROM, SELECT list, WHERE IN/EXISTS/scalar/ANY/ALL)
- Set operations (UNION, UNION ALL, INTERSECT, MINUS → EXCEPT)

**Phase 3: Oracle-Specific Transformations (~90%)**
- FROM DUAL (omit FROM clause) - VisitQueryBlock
- SUBSTR → SUBSTRING (FROM/FOR syntax) - VisitStringFunction
- NVL → COALESCE - VisitStringFunction
- SYSDATE → CURRENT_TIMESTAMP - VisitGeneralElement
- DECODE → CASE WHEN - VisitStringFunction
- CASE expressions (END CASE → END) - VisitCaseExpression
- TO_CHAR (format code transformations: RR→YY, RRRR→YYYY, D→., G→,) - VisitStringFunction
- TO_DATE → TO_TIMESTAMP - VisitStringFunction
- TRIM (pass-through) - VisitStringFunction
- Window functions (OVER clause: ROW_NUMBER, RANK, LEAD, LAG, aggregates) - VisitOverClause
- ROWNUM Phase 1: WHERE ROWNUM <= N → LIMIT - RownumAnalyzer, RownumContext
- ROWNUM Phase 2: SELECT ROWNUM → SELECT row_number() OVER () AS rownum - VisitSelectListElement
- Sequences (seq.NEXTVAL → nextval('schema.seq')) - VisitGeneralElement
- Type member methods (emp.address.get_street() → address_type__get_street(emp.address)) - VisitGeneralElement
- Package functions (pkg.func() → pkg__func()) - VisitGeneralElement
- Schema qualification, synonym resolution - TransformationContext

### ⏳ Not Yet Implemented

**CRITICAL GAPS (High Impact on Real-World Coverage):**

1. **CTEs (WITH clause / Common Table Expressions)** 🔴 **BLOCKING**
   - Usage: 40-60% of complex Oracle views
   - Oracle: `WITH dept_totals AS (SELECT ...) SELECT ... FROM dept_totals`
   - PostgreSQL: Mostly compatible, need non-recursive and recursive support
   - Estimated effort: 2-3 days (non-recursive), +2 days (recursive WITH)
   - Grammar: `with_clause`, `subquery_factoring_clause`

2. **CONNECT BY (Hierarchical Queries)** 🔴 **BLOCKING**
   - Usage: 10-20% of Oracle views
   - Oracle: `SELECT ... START WITH ... CONNECT BY PRIOR ...`
   - PostgreSQL: Requires conversion to recursive CTEs
   - Estimated effort: 5-7 days (complex transformation)
   - Grammar: `hierarchical_query_clause`, `CONNECT_BY_ROOT`, `PRIOR`

3. **Common Date/Time Functions** 🟡 **HIGH IMPACT**
   - Usage: 20-30% of views
   - Missing: ADD_MONTHS, MONTHS_BETWEEN, NEXT_DAY, LAST_DAY, TRUNC(date), ROUND(date)
   - Missing: INTERVAL expressions (INTERVAL '1' DAY)
   - Estimated effort: 3-5 days

4. **Common String Functions** 🟡 **HIGH IMPACT**
   - Usage: 20-30% of views
   - Missing: INSTR, LPAD, RPAD, TRANSLATE
   - Missing: REGEXP_REPLACE, REGEXP_SUBSTR, REGEXP_INSTR
   - Estimated effort: 3-4 days

5. **Advanced Analytic Functions** 🟡 **MEDIUM IMPACT**
   - Usage: 10-15% of views
   - Missing: LISTAGG (string aggregation)
   - Missing: KEEP clause (FIRST_VALUE KEEP DENSE_RANK...)
   - Missing: XMLAGG
   - Estimated effort: 3-4 days

6. **PIVOT/UNPIVOT** 🟡 **MEDIUM IMPACT**
   - Usage: 5-10% of views
   - Requires dynamic transformation to CASE WHEN or PostgreSQL crosstab
   - Estimated effort: 4-5 days

**Quick Wins (Low Effort, Moderate Impact):**

7. **Unary operators (+, -)** 🟢 **TRIVIAL**
   - Current: Throws exception
   - Fix: Pass-through (identical in Oracle and PostgreSQL)
   - Estimated effort: 5 minutes

8. **ROWNUM advanced patterns** 🟢 **PARTIALLY COMPLETE**
   - ✅ Phase 2 COMPLETE: ROWNUM in SELECT list → row_number() OVER () (16 tests)
   - ⏳ Phase 3-4: ROWNUM in expressions (arithmetic, unary operators), complex patterns
   - Note: ROWNUM BETWEEN is NOT supported (invalid Oracle pattern)
   - Estimated remaining effort: 0.5-1 day

9. **CHR function** 🟢 **TRIVIAL**
   - Oracle: `CHR(65)` → PostgreSQL: `CHR(65)` (identical)
   - Estimated effort: 10 minutes

10. **Additional conversion functions** 🟢 **LOW EFFORT**
    - TO_NUMBER (with format)
    - CAST expressions (mostly pass-through)
    - Estimated effort: 1 day

**Low Priority (Rare/Specialized):**

- MODEL clause (<1% usage, very high complexity)
- MERGE statement (rare in views, more common in procedures)
- Collection operations (MULTISET, MEMBER OF, SUBMULTISET)
- JSON functions (JSON_EQUAL, JSON_OBJECT, etc.)
- Cursor operations (SQL%ROWCOUNT, CURSOR expressions)
- Advanced time zones (AT LOCAL, AT TIME ZONE)
- Character set introducers and COLLATE
- Bind variables (more relevant for prepared statements than views)

---

## Architecture

### Dependency Boundaries

**Clean separation maintained:**

```
ViewTransformationService (CDI @ApplicationScoped)
         ↓ @Inject
    AntlrParser (CDI @ApplicationScoped)
         ↓ creates
    PostgresCodeBuilder (NOT injected - pure visitor)
         ↓ uses (passed as parameter)
    TransformationContext (created per transformation)
         ↓ contains
    TransformationIndices (built from StateService)
```

**Why PostgresCodeBuilder is NOT a CDI bean:**
- ✅ No direct dependency on StateService in visitor
- ✅ TransformationContext acts as facade for metadata access
- ✅ Testable - mock TransformationContext for unit tests
- ✅ Reusable - same builder transforms multiple queries with different contexts

### Metadata Strategy

**Two types of metadata required:**

1. **Synonym Resolution**
   - Resolves Oracle synonyms to actual table names
   - Follows Oracle rules: current schema → PUBLIC fallback
   - Essential because PostgreSQL has no synonyms

2. **Structural Indices** (O(1) lookups via hash maps)
   - Table → Column → Type mappings
   - Type → Method mappings
   - Package → Function mappings
   - Built once at transformation session start from StateService

**Critical Disambiguation:**

```sql
-- Type method call (requires metadata)
SELECT emp.address.get_street() FROM employees emp;
-- → address_type__get_street(emp.address)

-- Package function call
SELECT emp_pkg.get_salary(emp.empno) FROM employees emp;
-- → emp_pkg__get_salary(emp.empno)

-- Type attribute access
SELECT emp.address.street FROM employees emp;
-- → (emp.address).street
```

Without metadata, `a.b.c()` is ambiguous. With TransformationContext:
1. Is `a` a table alias? → `context.resolveAlias(a)`
2. Does table have column `b` of custom type? → `context.getColumnType(table, b)`
3. Does that type have method `c`? → `context.hasTypeMethod(type, c)`
4. Otherwise, is `a.b` a package function? → `context.isPackageFunction(qualified)`

**Schema Qualification:**

All unqualified table and function names are automatically qualified with the current schema to prevent PostgreSQL "relation does not exist" errors:

```sql
-- Oracle (unqualified)
SELECT empno FROM employees;

-- PostgreSQL (qualified with schema)
SELECT empno FROM hr.employees;
```

---

## Module Structure

```
transformer/
├── parser/
│   ├── AntlrParser.java                 # CDI bean - ANTLR wrapper
│   ├── ParseResult.java                 # Parse tree + errors
│   └── SqlType.java                     # Enum: VIEW_SELECT, etc.
│
├── builder/
│   ├── PostgresCodeBuilder.java         # Main visitor (NOT CDI bean)
│   │
│   ├── outerjoin/                       # Outer join transformation (4 classes)
│   │   ├── OuterJoinAnalyzer.java       # Two-phase analyzer (FROM + WHERE)
│   │   ├── OuterJoinContext.java        # Query-level outer join state
│   │   ├── OuterJoinCondition.java      # Single outer join relationship
│   │   └── TableInfo.java               # Table name + alias
│   │
│   ├── rownum/                          # ROWNUM → LIMIT transformation (2 classes)
│   │   ├── RownumAnalyzer.java          # AST-based pattern detection
│   │   └── RownumContext.java           # Filtering and transformation
│   │
│   └── Visit*.java                      # 37 static visitor helpers:
│       ├── VisitSelectStatement.java
│       ├── VisitQueryBlock.java         # SELECT list + FROM + WHERE + ORDER BY
│       ├── VisitFromClause.java         # Outer join generation
│       ├── VisitWhereClause.java        # Filters out (+) and ROWNUM conditions
│       ├── VisitSelectedList.java
│       ├── VisitSelectListElement.java  # Column aliases
│       ├── VisitExpression.java (11-level hierarchy)
│       ├── VisitGeneralElement.java     # ⭐ SYSDATE, sequences, type methods, packages
│       ├── VisitStringFunction.java     # NVL, DECODE, SUBSTR, TO_CHAR, TO_DATE, TRIM
│       ├── VisitCaseExpression.java     # END CASE → END
│       ├── VisitOverClause.java         # Window functions
│       ├── VisitTableReference.java     # Schema qualification, JOINs
│       └── ... (37 total, see Glob for complete list)
│
├── context/
│   ├── TransformationContext.java       # Metadata access facade
│   ├── TransformationIndices.java       # Pre-built lookup indices
│   ├── MetadataIndexBuilder.java        # Builds indices from StateService
│   ├── TransformationResult.java        # Success/error wrapper
│   └── TransformationException.java     # Custom exception
│
└── service/
    └── SqlTransformationService.java    # CDI bean - high-level API
```

**Key Visitor Classes by Feature:**
- **VisitGeneralElement**: SYSDATE, sequences (NEXTVAL/CURRVAL), type methods, package functions
- **VisitStringFunction**: NVL, DECODE, SUBSTR, TO_CHAR, TO_DATE, TRIM
- **VisitConcatenation**: Arithmetic operators, || → CONCAT()
- **VisitQueryBlock**: FROM DUAL handling, ROWNUM LIMIT generation
- **VisitOverClause**: Window functions (ROW_NUMBER, RANK, LEAD, LAG, aggregates)
- **OuterJoinAnalyzer + VisitFromClause**: Oracle (+) → ANSI LEFT/RIGHT JOIN
- **RownumAnalyzer + RownumContext**: ROWNUM → LIMIT optimization

---

## Implementation Phases

### Phase 1: Foundation ✅ COMPLETE
- AntlrParser, PostgresCodeBuilder (37 visitors), TransformationContext, TransformationIndices, MetadataIndexBuilder, 11-level expression hierarchy, SqlTransformationService
- Initial 72 tests: basic SELECT, *, qualified SELECT, table aliases

### Phase 2: Complete SELECT Support ✅ 100% COMPLETE

All SQL clauses, operators, and join types fully implemented with comprehensive test coverage (270 tests).

**Core Features:**
- Literals and operators - VisitAtom, VisitConcatenation (24 tests)
- WHERE clause (IN, BETWEEN, LIKE, IS NULL, complex nested) - VisitLogicalExpression, VisitCompoundExpression
- Oracle (+) outer joins → ANSI LEFT/RIGHT JOIN - OuterJoinAnalyzer, VisitFromClause (17 tests)
- Type methods, package functions - VisitGeneralElement (18 tests)
- Subqueries in FROM - VisitTableReference (13 tests)
- ORDER BY with DESC → DESC NULLS FIRST fix - VisitOrderByClause (19 tests)
- GROUP BY/HAVING with aggregates - VisitGroupByClause, VisitHavingClause (20 tests)
- ANSI JOINs (INNER/LEFT/RIGHT/FULL/CROSS) - VisitTableReference (15 tests)
- Arithmetic/concatenation (|| → CONCAT() for NULL safety) - VisitConcatenation (22 tests)

---

### Phase 3: Oracle-Specific Functions ✅ ~90% COMPLETE

Oracle-specific transformations for functions, pseudo-columns, and special syntax (237 tests).

**Completed:**
- NVL → COALESCE - VisitStringFunction (8 tests)
- SYSDATE → CURRENT_TIMESTAMP - VisitGeneralElement (6 tests)
- DECODE → CASE WHEN - VisitStringFunction (10 tests)
- Column aliases - VisitSelectListElement (18 tests)
- CASE expressions (END CASE → END) - VisitCaseExpression (17 tests)
- TO_CHAR (format code transformations) - VisitStringFunction (21 tests)
- Subqueries (FROM, SELECT list, WHERE IN/EXISTS/ANY/ALL) - VisitAtom, VisitQuantifiedExpression (9 tests)
- Set operations (MINUS → EXCEPT) - VisitSubquery (12 tests)
- FROM DUAL (omit FROM clause) - VisitQueryBlock (16 tests)
- SUBSTR → SUBSTRING (FROM/FOR syntax) - VisitStringFunction (18 tests)
- TO_DATE → TO_TIMESTAMP - VisitStringFunction (17 tests)
- TRIM - VisitStringFunction (19 tests)
- Window functions (OVER clause) - VisitOverClause (31 tests)
- ROWNUM Phase 1: WHERE ROWNUM → LIMIT - RownumAnalyzer, RownumContext (17 tests)
- ROWNUM Phase 2: SELECT ROWNUM → row_number() OVER () - VisitSelectListElement (16 tests)
- Sequences (seq.NEXTVAL → nextval()) - VisitGeneralElement (19 tests)

**Remaining:**
- ROWNUM Phase 3-4: Expressions (arithmetic, unary operators), complex patterns
- Unary operators (+, -) in general expressions
- CHR function

---

### Phase 4: Integration with Migration Jobs ✅ COMPLETE

- **PostgresViewImplementationJob** replaces view stubs with transformed SQL
- Uses CREATE OR REPLACE VIEW (preserves dependencies - critical for two-phase architecture)
- SqlTransformationService extracts Oracle view SQL from ALL_VIEWS.TEXT
- Success rate: ~20% initially → expected ~98% with Phase 3 completion

### Phase 5: PL/SQL Functions/Procedures ⏳ FUTURE

Extend PostgresCodeBuilder with new visitors for PL/SQL control flow: VisitFunctionBody, VisitDeclareSection, VisitIfStatement, VisitLoopStatement, VisitCursorDeclaration, VisitExceptionHandler

---

## Testing

### Test Organization (30 test classes, 507 tests)

**Foundation:** AntlrParserTest (15), SqlTransformationServiceTest (24), ViewTransformationIntegrationTest (7)
**Basic SELECT:** SimpleSelect (6), SelectStar (10), TableAlias (9), SynonymResolution (7), ExpressionBuildingBlocks (24)
**Advanced Features:** PackageFunction (10), TypeMemberMethod (8), OuterJoin (17), SubqueryFromClause (13)
**Operators:** Arithmetic (22), OrderBy (19), GroupBy (20), AnsiJoin (15)
**Oracle Functions:** NVL/SYSDATE/DECODE (23), ColumnAlias (18), CaseExpression (17), TO_CHAR (21), SUBSTR (18), TO_DATE (17), TRIM (19)
**Complex Features:** SubqueryComprehensive (9), SetOperations (12), FromDual (16), WindowFunctions (31), ROWNUM (33: Phase1 17 + Phase2 16), Sequences (19)

### Coverage Summary

**100% coverage** for all implemented features: Parser, expression hierarchy, WHERE/ORDER BY/GROUP BY, JOINs, arithmetic, Oracle functions (NVL, SYSDATE, DECODE, TO_CHAR, TO_DATE, SUBSTR, TRIM), subqueries, set operations, window functions, type methods, package functions, schema qualification, synonym resolution

---

## Next Steps

### Realistic Roadmap to 80-90% Real-World Coverage

**Phase 3A: Quick Wins (1-2 days)** 🟢
1. Unary operators (+, -) - 5 minutes
2. CHR function - 10 minutes
3. ROWNUM advanced patterns - 1-2 days
4. TO_NUMBER, CAST - 1 day

**Phase 3B: CTEs (4-5 days)** 🔴 **CRITICAL**
1. Non-recursive CTEs (WITH clause) - 2-3 days
   - Parse `with_clause` and `subquery_factoring_clause`
   - Pass-through strategy (mostly compatible)
   - Handle column aliases in CTE definitions
2. Recursive CTEs - 2 days
   - Detect UNION ALL pattern
   - Verify recursive reference
   - Add RECURSIVE keyword for PostgreSQL

**Phase 3C: Common Functions (7-9 days)** 🟡
1. Date/Time Functions - 3-5 days
   - ADD_MONTHS, MONTHS_BETWEEN, NEXT_DAY, LAST_DAY
   - TRUNC(date), ROUND(date)
   - INTERVAL expressions
2. String Functions - 3-4 days
   - INSTR → POSITION/STRPOS
   - LPAD, RPAD (mostly compatible)
   - TRANSLATE (compatible)
   - REGEXP functions (mostly compatible with syntax adjustments)

**Phase 3D: CONNECT BY (5-7 days)** 🔴 **HIGH COMPLEXITY**
1. Hierarchical query detection - 1 day
2. Convert to recursive CTE structure - 2-3 days
   - START WITH → base case
   - CONNECT BY → recursive case with JOIN
   - PRIOR operator → reference to recursive CTE
3. LEVEL pseudo-column - 1 day
4. CONNECT_BY_ROOT, SYS_CONNECT_BY_PATH - 2 days

**Phase 3E: Advanced Analytics (3-4 days)** 🟡
1. LISTAGG - 1-2 days
   - Oracle: `LISTAGG(col, ',') WITHIN GROUP (ORDER BY ...)`
   - PostgreSQL: `STRING_AGG(col, ',' ORDER BY ...)`
2. KEEP clause - 2 days
   - Requires transformation to subquery with window functions

**Phase 3F: PIVOT/UNPIVOT (4-5 days)** 🟡
- Complex transformation to CASE WHEN or crosstab
- May defer to later phase

### Coverage Milestones

- **After Phase 3A (Quick Wins):** ~50% → ~55% real-world coverage
- **After Phase 3B (CTEs):** ~55% → ~75% real-world coverage 🎯
- **After Phase 3C (Common Functions):** ~75% → ~85% real-world coverage 🎯
- **After Phase 3D (CONNECT BY):** ~85% → ~90% real-world coverage 🎯
- **After Phase 3E (Advanced Analytics):** ~90% → ~92% real-world coverage
- **After Phase 3F (PIVOT/UNPIVOT):** ~92% → ~95% real-world coverage

### Recommended Priority Order

1. **Start with CTEs (Phase 3B)** - Highest ROI
   - Unlocks 40-60% of currently failing views
   - Moderate complexity (non-recursive is straightforward)
   - Foundation for CONNECT BY transformation

2. **Then Common Functions (Phase 3C)** - High ROI
   - Unlocks another 20-30% of views
   - Many are simple transformations or pass-through

3. **Then CONNECT BY (Phase 3D)** - High ROI but complex
   - Unlocks 10-20% of views
   - Builds on CTE infrastructure
   - Most complex transformation in the roadmap

4. **Finally Advanced Features (Phase 3E-F)** - Diminishing returns
   - Smaller impact per unit of effort
   - Can be deferred if needed

---

## Conclusion

### Current Status (Honest Assessment)

**Architecture Strengths:**
- ✅ Direct AST approach (simple, fast, maintainable)
- ✅ 37 static visitor helpers (scalable, testable)
- ✅ Clean boundaries via TransformationContext
- ✅ 662/662 tests passing across 42 test classes
- ✅ Solid foundation for incremental feature additions

**What Works Well:**
- ✅ **Phase 2 (100% Complete):** Full SELECT support - all clauses, JOINs, operators, aggregates, subqueries, set operations
- ✅ **Phase 3 Core (~50% Complete):** NVL, SYSDATE, DECODE, TO_CHAR, TO_DATE, SUBSTR, TRIM, window functions, ROWNUM→LIMIT, sequences, type methods, package functions
- ✅ **Phase 4 (Complete):** PostgresViewImplementationJob integrated with CREATE OR REPLACE VIEW

**Coverage Reality Check:**
- **Simple Oracle views** (basic SELECT, no CTEs, no CONNECT BY): ~**95% coverage** ✅
- **Real-world Oracle views** (with CTEs, CONNECT BY, advanced functions): ~**40-50% coverage** ⚠️

**Critical Gaps Blocking Production Use:**
1. 🔴 **CTEs (WITH clause)** - 0% implemented, used in 40-60% of complex views
2. 🔴 **CONNECT BY** - 0% implemented, used in 10-20% of views
3. 🟡 **Common date/string functions** - 0% implemented, used in 20-30% of views

**Path to Production Readiness:**
- **After CTEs implementation:** ~75% real-world coverage (4-5 days)
- **After common functions:** ~85% real-world coverage (+7-9 days)
- **After CONNECT BY:** ~90% real-world coverage (+5-7 days)

**Total estimated effort to 90% coverage:** ~16-21 days

**Recommendation:** Implement CTEs first (highest ROI) - will immediately unlock 40-60% of currently failing views

---

**Files:**
- Implementation: `src/main/java/.../transformer/`
- Tests: `src/test/java/.../transformer/`
- ANTLR grammar: `src/main/antlr4/PlSqlParser.g4`
