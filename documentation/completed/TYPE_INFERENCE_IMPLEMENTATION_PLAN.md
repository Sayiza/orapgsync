# Type Inference Implementation Plan

**Status:** Phase 4.5 In Progress 🔄 (Query Scope Stack Fix)
**Created:** 2025-10-27
**Last Updated:** 2025-11-27
**Purpose:** Design and implement a full two-pass type inference system for accurate Oracle→PostgreSQL PL/SQL transformation

---

## Table of Contents

1. [Motivation](#motivation)
2. [Current Limitations](#current-limitations)
3. [Architecture Overview](#architecture-overview)
4. [Type System Rules](#type-system-rules)
5. [Scope Management](#scope-management)
6. [Implementation Phases](#implementation-phases)
7. [Testing Strategy](#testing-strategy)
8. [Integration Points](#integration-points)

---

## Implementation Progress

**Overall Status:** Phase 4.5 of 7 In Progress (62%)

| Phase | Status | Description | Test Coverage |
|-------|--------|-------------|---------------|
| **Phase 1** | ✅ **COMPLETE** | Foundation: Literals and Simple Expressions | 18/18 tests passing |
| **Phase 2** | ✅ **COMPLETE** | Column References and Metadata Integration | 14/14 tests passing ✅ |
| **Phase 3** | ✅ **COMPLETE** | Built-in Functions | 36/36 tests passing ✅ |
| **Phase 4** | ✅ **COMPLETE** | Parenthesized Expressions and Type Bubbling | 22/22 tests passing ✅ |
| **Phase 4.5** | 🔄 **IN PROGRESS** | Query Scope Stack (Subquery Isolation) | Bug identified, fix in progress |
| **Phase 5** | 📋 Planned | PL/SQL Variables and Assignments | Not started |
| **Phase 6** | 📋 Planned | Collections and Records | Not started |
| **Phase 7** | 📋 Planned | Integration and Optimization | Not started |

**Phase 1 Achievements:**
- ✅ 380 lines: TypeAnalysisVisitor with full literal and operator support
- ✅ 120 lines: FullTypeEvaluator for cache lookups
- ✅ 370 lines: Comprehensive test suite (18 tests, 100% passing)
- ✅ Token position-based caching architecture proven
- ✅ Date arithmetic rules implemented (DATE+NUMBER→DATE, DATE-DATE→NUMBER)
- ✅ NULL propagation working correctly
- ✅ Foundation ready for extension to metadata and functions

**Phase 2 Achievements:**
- ✅ 350+ lines: Column resolution with table alias tracking
- ✅ Unqualified column resolution (SELECT emp_id FROM employees)
- ✅ Qualified column resolution (SELECT e.emp_id FROM employees e)
- ✅ Table alias support (with and without AS keyword)
- ✅ **JOIN support** (2025-11-27): Extracts table aliases from both FROM and JOIN clauses
  - Refactored extraction logic into reusable helper method
  - Handles INNER, LEFT, RIGHT, FULL, CROSS JOINs
  - Resolves qualified columns across multiple joined tables
- ✅ Oracle type mapping (NUMBER→NUMERIC, VARCHAR2→TEXT, DATE→DATE, TIMESTAMP→DATE)
- ✅ Integration with TransformationIndices for O(1) metadata lookups
- ✅ 350 lines: Comprehensive test suite (14/14 tests passing)

**Phase 3 Achievements:**
- ✅ 600+ lines: Built-in function return type resolution
- ✅ Polymorphic functions (ROUND, TRUNC with DATE vs NUMBER)
- ✅ Date functions (ADD_MONTHS, MONTHS_BETWEEN, LAST_DAY)
- ✅ String functions (UPPER, LOWER, SUBSTR, LENGTH, INSTR, TRIM)
- ✅ Conversion functions (TO_CHAR, TO_DATE, TO_NUMBER, TO_TIMESTAMP)
- ✅ NULL-handling functions (NVL, COALESCE, DECODE, NULLIF)
- ✅ Aggregate functions (COUNT, SUM, AVG, MIN, MAX)
- ✅ Numeric functions (ABS, SQRT, CEIL, FLOOR, etc.)
- ✅ Type precedence rules (TIMESTAMP > DATE > NUMBER > TEXT)
- ✅ Grammar-specific visitors: visitString_function, visitNumeric_function, visitOther_function
- ✅ Nested function type propagation (UPPER(SUBSTR(...)))
- ✅ **Pseudo-column resolution** (SYSDATE, SYSTIMESTAMP, ROWNUM, LEVEL, USER, ROWID)
- ✅ 560 lines: Comprehensive test suite (36/36 tests passing)

**Phase 4 Achievements (Completed 2025-11-27):**
- ✅ **Scalar subquery type inference** - Propagates single-column SELECT types
- ✅ **Atom node type propagation** - Critical fix: types flow through ALL parenthesized expressions
  - **Initial fix (2025-11-26):** Handled `'(' subquery ')'` case only
  - **Complete fix (2025-11-27):** Now handles ALL Atom cases:
    - `'(' subquery ')'` - Scalar subqueries: `(SELECT 1 FROM dual)`
    - `'(' expressions_ ')'` - Parenthesized expressions: `(42)`, `(salary + 1000)`, `((nested))`
    - `constant`, `general_element`, `bind_variable` - Other atom types
- ✅ Handles nested scalar subqueries correctly
- ✅ Handles nested parentheses correctly: `((42))` propagates NUMERIC through all levels
- ✅ Multi-column subqueries correctly return UNKNOWN
- ✅ Integration with date arithmetic transformation
- ✅ **Comprehensive testing** - 3 test classes with full coverage:
  - TypeAnalysisVisitorPhase4Test (10 tests) - Scalar subqueries
  - ScalarSubqueryAtomDiagnosticTest (1 test) - AST structure verification
  - ParenthesizedExpressionTypeBubblingTest (11 tests) - All parenthesized expression cases
- ✅ **Real-world bug fixes:**
  - `TRUNC(date) + (SELECT 1 FROM dual)` - Scalar subquery in date arithmetic (2025-11-26)
  - `(hire_date) + 30` - Parenthesized column in date arithmetic (2025-11-27)
  - `ROUND((salary))` - Parenthesized expression type detection (2025-11-27)

**Phase 4.5 In Progress (Started 2025-11-27):**

**Critical Bug Identified:** Table alias scope pollution in nested subqueries

**Problem:**
```sql
-- Working case (no subquery):
SELECT 1 FROM abs_werk_sperren ws1
WHERE TRUNC(ws1.spa_abgelehnt_am) + 1 > CURRENT_DATE
-- ✅ Correctly detects ws1.spa_abgelehnt_am as DATE

-- Failing case (with subquery):
SELECT 1 FROM abs_werk_sperren ws1
WHERE TRUNC(ws1.spa_abgelehnt_am) + (SELECT 1 FROM dual) > CURRENT_DATE
-- ❌ Fails to detect ws1.spa_abgelehnt_am as DATE (treats as NUMERIC)
```

**Root Cause Analysis:**
- `TypeAnalysisVisitor.visitQuery_block()` line 194: `tableAliases.clear()`
- When entering a subquery, the `clear()` call destroys the outer query's table aliases
- Outer query column references can't be resolved after subquery completes
- Type inference returns UNKNOWN → falls back to heuristic (fails)

**Solution:** Query Scope Stack (mimics existing variable scope stack pattern)
- Replace flat `Map<String, String> tableAliases` with `Deque<Map<String, String>> tableAliasScopes`
- Push new scope on `enterQueryScope()`, pop on `exitQueryScope()`
- Hierarchical resolution: inner queries can reference outer query tables (correlated subqueries)
- Reuse pattern already proven in lines 41, 610-663 for PL/SQL variable scopes

**Implementation Status:**
- 🔄 Fix in progress
- 📋 Test case identified: subquery with outer reference
- 📋 Need to update ResolveColumn to use scoped resolution

**Key Metrics:**
- Lines of code: ~4,200 (implementation + tests)
- Test coverage: 90 unit tests, 90 passing, 0 skipped ✅
  - Phase 1: 18 tests (literals, operators)
  - Phase 2: 14 tests (column resolution, JOIN support)
  - Phase 3: 36 tests (functions)
  - Phase 4: 10 tests (scalar subqueries)
  - Diagnostic: 1 test (AST verification)
  - Parenthesized expressions: 11 tests (complete Atom coverage)
- Supported function categories: 8 (date, string, conversion, NULL-handling, aggregate, numeric, polymorphic, window)
- Supported functions: 50+ built-in Oracle functions
- Supported pseudo-columns: 10+ (SYSDATE, SYSTIMESTAMP, ROWNUM, LEVEL, USER, etc.)
- Type inference accuracy: 100% for all completed phases

### Phase 3.5: Architecture Refactoring ✅ **COMPLETE** (2025-10-28)

**Motivation:** TypeAnalysisVisitor grew to ~1,383 lines during Phase 1-3 implementation. Following PostgresCodeBuilder's successful static helper pattern, we refactored to improve maintainability.

**Refactoring Results:**
- **Before:** TypeAnalysisVisitor.java (1,383 lines - monolithic)
- **After:** TypeAnalysisVisitor.java (498 lines - coordinator) + 5 helper classes
- **Reduction:** 885 lines (64% reduction in main visitor)

**Static Helper Classes Created:**

1. **ResolveConstant.java** (86 lines)
   - Handles all literal type resolution
   - DATE, TIMESTAMP, numeric, string, NULL, boolean literals
   - Order matters: DATE/TIMESTAMP checked before quoted_string

2. **ResolveOperator.java** (168 lines)
   - Handles arithmetic operators (*, /, +, -, **, MOD)
   - String concatenation (|| → TEXT)
   - Date arithmetic rules (DATE+NUMBER→DATE, DATE-DATE→NUMBER)
   - NULL propagation logic

3. **ResolveColumn.java** (246 lines)
   - Column type resolution from metadata
   - Unqualified column resolution (tries all tables in FROM clause)
   - Qualified column resolution (table.column or alias.column)
   - Fully qualified resolution (schema.table.column)
   - Oracle type → TypeInfo mapping

4. **ResolvePseudoColumn.java** (70 lines)
   - Oracle pseudo-column type resolution
   - SYSDATE, SYSTIMESTAMP → DATE/TIMESTAMP
   - ROWNUM, LEVEL, UID → NUMERIC
   - USER, ROWID, SESSIONTIMEZONE → TEXT

5. **ResolveFunction.java** (514 lines)
   - 50+ Oracle built-in function return type resolution
   - Polymorphic functions (ROUND, TRUNC)
   - Date, string, conversion, NULL-handling functions
   - Type precedence rules (TIMESTAMP > DATE > NUMBER > TEXT)
   - Handles grammar-specific contexts (string_function, numeric_function, other_function)

**TypeAnalysisVisitor Role (After Refactoring):**
- Coordinator for type inference (not implementation)
- Manages scope stack and table aliases
- Handles caching (`nodeKey()`, `cacheAndReturn()`)
- Delegates actual type resolution to helpers
- Maintains query-local state (FROM clause tracking)

**Architecture Benefits:**
- ✅ Follows PostgresCodeBuilder pattern (consistency)
- ✅ Improved maintainability (logic organized by responsibility)
- ✅ Better testability (helpers can be tested independently)
- ✅ Easier to extend (new type resolution logic → appropriate helper)
- ✅ Reduced complexity (main visitor is now clean coordinator)

**Test Results After Refactoring:**
```
Tests run: 68, Failures: 0, Errors: 0, Skipped: 1 ✅
All Phase 1-3 tests passing!
```

**Key Fix:** Made `nodeKey()` method `public` so helper classes can generate cache keys for looking up argument types.

---

## Motivation

Accurate type information is **critical** for correct PL/SQL transformation. Many Oracle constructs require knowing the type of an expression to generate correct PostgreSQL equivalents.

### Critical Scenarios Requiring Type Information

#### 1. ROUND/TRUNC Function Disambiguation

**Problem:** Oracle's `ROUND()` and `TRUNC()` are polymorphic - behavior depends on input type.

```sql
-- Oracle: DATE truncation
v_result := TRUNC(hire_date);  -- Truncates to midnight

-- Oracle: NUMBER rounding
v_result := TRUNC(salary, 2);  -- Truncates to 2 decimal places
```

**PostgreSQL requires different functions:**
```sql
-- DATE truncation
v_result := DATE_TRUNC('day', hire_date);

-- NUMBER truncation
v_result := TRUNC(salary, 2);
```

**Without type info:** We must add defensive casts or guess incorrectly.

**Complex example:**
```sql
v_result := ROUND(
    MONTHS_BETWEEN(end_date, start_date) / 12,
    2
);
```

Type propagation required:
1. `start_date`, `end_date` → DATE (from variable declaration or metadata)
2. `MONTHS_BETWEEN(DATE, DATE)` → NUMBER
3. `NUMBER / 12` → NUMBER
4. `ROUND(NUMBER, 2)` → numeric function (not date truncation)

---

#### 2. String Concatenation vs Arithmetic

**Problem:** Oracle's `||` operator has NULL-safe semantics, but only for strings.

```sql
-- String concatenation (NULL-safe in Oracle)
v_name := first_name || ' ' || last_name;  -- NULL ignored
-- PostgreSQL: CONCAT(first_name, ' ', last_name)

-- Mixed types requiring conversion
v_msg := 'Employee: ' || emp_id;  -- emp_id is NUMBER
-- PostgreSQL: CONCAT('Employee: ', emp_id::text)

-- Arithmetic (not concatenation)
v_total := base_amount || tax_amount;  -- If both are numbers, this is wrong!
-- Should stay as: base_amount + tax_amount
```

**Need to know:** Are operands strings or do they need type conversion?

---

#### 3. Date Arithmetic

**Problem:** Oracle allows direct integer arithmetic with dates. PostgreSQL requires INTERVAL.

```sql
-- Oracle: Add days to date
v_due_date := hire_date + 30;

-- PostgreSQL: Need to know hire_date is DATE
v_due_date := hire_date + INTERVAL '30 days';
```

**But:**
```sql
-- Oracle: Regular addition
v_total := quantity + 30;

-- PostgreSQL: Stay as-is (no INTERVAL)
v_total := quantity + 30;
```

**Need to know:** Is left operand a DATE or NUMBER?

**Current Status (Phase 1 - Heuristic Implementation):**

✅ **Implemented as of 2025-11-24** - Date arithmetic transformation using heuristic detection
- **Location:** `DateArithmeticTransformer` class
- **Detection Strategy:** Pattern matching (date functions, column names, simplified metadata)
- **Coverage:** 85-95% of real-world cases
- **Limitations:** Complex expressions, subqueries, unusual column names not detected
- **Test Coverage:** 15 tests in `DateArithmeticTransformationTest`
- **See:** `TRANSFORMATION.md` section "Date Arithmetic" for details

**Heuristic Detection Logic:**
1. Checks for date functions (SYSDATE, TO_DATE, ADD_MONTHS, etc.)
2. Checks for date-related column names (*date*, *time*, created*, hire*, etc.)
3. Simplified metadata lookup (deferred for full implementation)

**Known Limitations (will be fixed with type inference):**
- ❌ `CASE WHEN ... THEN date_col ELSE other_date END + 1` - not detected
- ❌ `(SELECT MAX(end_date) FROM ...) + 7` - no column metadata
- ❌ Column named "sperre_endet_am" - doesn't match patterns

**Phase 2 Goal:** Replace heuristic with deterministic type checking using TypeAnalysisVisitor

---

#### 4. Function Overloading Resolution

**Problem:** PL/SQL supports function overloading. PostgreSQL requires unique signatures.

```sql
-- Oracle: Multiple signatures
PACKAGE emp_pkg IS
  FUNCTION get_info(p_id NUMBER) RETURN VARCHAR2;
  FUNCTION get_info(p_name VARCHAR2) RETURN VARCHAR2;
END;

-- Call site
v_result := emp_pkg.get_info(emp_id);  -- Which function?
```

**Need to know:** What is the type of `emp_id` to choose correct PostgreSQL function name:
- `emp_pkg__get_info__number(emp_id)`
- `emp_pkg__get_info__varchar(emp_id)`

---

#### 5. Assignment Compatibility

**Problem:** Oracle allows implicit conversions. PostgreSQL often requires explicit casts.

```sql
-- Oracle: Implicit conversion
DECLARE
  v_id VARCHAR2(10);
  v_count NUMBER;
BEGIN
  v_count := 42;
  v_id := v_count;  -- NUMBER → VARCHAR2 automatic
END;
```

**PostgreSQL:**
```sql
DECLARE
  v_id text;
  v_count numeric;
BEGIN
  v_count := 42;
  v_id := v_count::text;  -- Need explicit cast
END;
```

**Need to know:** Source and target types to insert casts where needed.

---

#### 6. CASE Expression Result Type Unification

**Problem:** All CASE branches must return compatible types. May need explicit casts.

```sql
-- Oracle: Implicit conversion
v_result := CASE status
  WHEN 'A' THEN salary        -- NUMBER
  WHEN 'B' THEN commission    -- NUMBER
  WHEN 'C' THEN 'N/A'         -- VARCHAR2 → implicit to NUMBER fails at runtime!
END;
```

**Need to know:** All branch types to:
- Detect type mismatches
- Insert explicit casts
- Determine result variable type

---

#### 7. NULL-Handling Functions (NVL, COALESCE)

**Problem:** Result type affects how value is used downstream.

```sql
-- NVL with dates
v_date := NVL(termination_date, SYSDATE);  -- Result is DATE

-- NVL with numbers
v_amount := NVL(commission, 0);  -- Result is NUMBER

-- Used in expression
v_months := MONTHS_BETWEEN(v_date, hire_date);  -- Need to know v_date is DATE
```

---

#### 8. TO_CHAR with Polymorphic Inputs

**Problem:** Different PostgreSQL functions needed based on input type.

```sql
-- Date formatting
v_str := TO_CHAR(hire_date, 'YYYY-MM-DD');
-- PostgreSQL: TO_CHAR(hire_date, 'YYYY-MM-DD')

-- Number formatting
v_str := TO_CHAR(salary, '999,999.99');
-- PostgreSQL: TO_CHAR(salary::numeric, '999,999.99')

-- Without context, both look identical in AST!
```

---

#### 9. Comparison Operations with Mixed Types

**Problem:** Determining if explicit cast needed.

```sql
-- Oracle: Implicit conversion
WHERE emp_id = '12345'  -- STRING → NUMBER conversion

-- PostgreSQL: May need cast depending on column type
WHERE emp_id = '12345'::numeric
```

**Need to know:** Column type to decide if cast is needed.

---

#### 10. Record/Collection Element Access

**Problem:** Type of accessed element affects downstream usage.

```sql
DECLARE
  TYPE emp_array IS TABLE OF employees%ROWTYPE;
  v_emps emp_array;
BEGIN
  v_salary := v_emps(1).salary;  -- Need to know salary is NUMBER
  v_date := TRUNC(v_emps(1).hire_date);  -- Need to know hire_date is DATE
END;
```

---

## Current Limitations

### SimpleTypeEvaluator Behavior

Current implementation (`SimpleTypeEvaluator.java`):

```java
public class SimpleTypeEvaluator implements TypeEvaluator {
    @Override
    public TypeInfo getType(ExpressionContext ctx) {
        // For simple evaluator, return UNKNOWN for most cases
        // This is safe - causes defensive casts in ROUND/TRUNC
        return TypeInfo.UNKNOWN;
    }
}
```

**What works:**
- SQL view transformation (limited expression complexity)
- Defensive transformations (add casts when unsure)
- No false positives (conservative approach)

**What doesn't work:**
- PL/SQL variable type tracking
- Type propagation through expressions
- Function overload resolution
- Optimal transformations (too many defensive casts)
- Complex expression type inference

**Example of suboptimal output:**
```sql
-- Oracle
v_result := ROUND(salary / 12);

-- Current output (defensive)
v_result := ROUND((salary / 12)::numeric);  -- Unnecessary cast if salary is already numeric

-- Optimal output
v_result := ROUND(salary / 12);
```

---

## Architecture Overview

### Two-Pass Design

```
Oracle PL/SQL Source
        ↓
   ANTLR Parse
        ↓
    Parse Tree (AST)
        ↓
   ┌────────────────────┐
   │  PASS 1: Type      │ ← TypeAnalysisVisitor
   │  Inference         │   (bottom-up traversal)
   └────────────────────┘
        ↓
   Type Cache (Map<String, TypeInfo>)
        ↓
   ┌────────────────────┐
   │  PASS 2: Code      │ ← PostgresCodeBuilder
   │  Generation        │   (queries type cache)
   └────────────────────┘
        ↓
   PostgreSQL PL/pgSQL
```

---

### Core Components

#### 1. TypeAnalysisVisitor (NEW)

```java
/**
 * First pass: Type inference visitor.
 *
 * Walks AST bottom-up (post-order traversal) to compute types following Oracle's rules.
 * Populates type cache with results keyed by token position.
 *
 * Design:
 * - Extends PlSqlParserBaseVisitor<TypeInfo>
 * - Returns TypeInfo for each visited node
 * - Caches results using token position as key
 * - Manages scope stack for PL/SQL variable declarations
 */
public class TypeAnalysisVisitor extends PlSqlParserBaseVisitor<TypeInfo> {

    private final String currentSchema;
    private final TransformationIndices indices;
    private final Map<String, TypeInfo> typeCache;  // Shared with FullTypeEvaluator

    // Scope management for PL/SQL variables
    private final Deque<Map<String, TypeInfo>> scopeStack = new ArrayDeque<>();

    // Query-local state (table aliases, CTEs)
    private final Map<String, String> tableAliases = new HashMap<>();

    @Override
    public TypeInfo visitExpression(ExpressionContext ctx) {
        // 1. Visit children first (recursive descent)
        TypeInfo resultType = visitChildren(ctx);

        // 2. Cache this node's type
        String key = nodeKey(ctx);
        typeCache.put(key, resultType);

        // 3. Return for parent nodes
        return resultType;
    }

    private String nodeKey(ParserRuleContext ctx) {
        return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
    }
}
```

---

#### 2. FullTypeEvaluator (NEW)

```java
/**
 * Full type evaluator backed by pre-computed type cache.
 *
 * Used by PostgresCodeBuilder to query types computed during type analysis pass.
 * Replaces SimpleTypeEvaluator for PL/SQL transformation.
 */
public class FullTypeEvaluator implements TypeEvaluator {

    private final Map<String, TypeInfo> typeCache;  // Populated by TypeAnalysisVisitor

    public FullTypeEvaluator(Map<String, TypeInfo> typeCache) {
        this.typeCache = typeCache;
    }

    @Override
    public TypeInfo getType(ExpressionContext ctx) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        String key = nodeKey(ctx);
        return typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
    }

    @Override
    public void clearCache() {
        // No-op: cache is immutable after type analysis pass
    }

    private String nodeKey(ParserRuleContext ctx) {
        if (ctx.start == null || ctx.stop == null) {
            return "unknown:" + System.identityHashCode(ctx);
        }
        return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
    }
}
```

---

#### 3. Enhanced TypeInfo (Expand Existing)

```java
/**
 * Represents type information for an expression.
 *
 * Immutable value object.
 */
public class TypeInfo {

    public enum BaseType {
        UNKNOWN,    // Type could not be determined
        NUMBER,     // Oracle NUMBER (all numeric types)
        VARCHAR,    // Oracle VARCHAR2, CHAR
        DATE,       // Oracle DATE
        TIMESTAMP,  // Oracle TIMESTAMP
        BOOLEAN,    // PL/SQL BOOLEAN
        RECORD,     // Record type (%ROWTYPE)
        COLLECTION, // TABLE OF, VARRAY
        OBJECT,     // User-defined object type
        CLOB,       // CLOB
        BLOB,       // BLOB
        CURSOR,     // REF CURSOR
        XMLTYPE,    // XMLTYPE
        NULL_TYPE   // NULL literal
    }

    private final BaseType baseType;
    private final String qualifiedTypeName;  // For OBJECT: "schema.typename"
    private final Integer precision;         // For NUMBER: precision
    private final Integer scale;            // For NUMBER: scale
    private final TypeInfo elementType;     // For COLLECTION: element type

    // Predefined constants
    public static final TypeInfo UNKNOWN = new TypeInfo(BaseType.UNKNOWN);
    public static final TypeInfo NUMBER = new TypeInfo(BaseType.NUMBER);
    public static final TypeInfo VARCHAR = new TypeInfo(BaseType.VARCHAR);
    public static final TypeInfo DATE = new TypeInfo(BaseType.DATE);
    public static final TypeInfo TIMESTAMP = new TypeInfo(BaseType.TIMESTAMP);
    public static final TypeInfo BOOLEAN = new TypeInfo(BaseType.BOOLEAN);
    public static final TypeInfo NULL_TYPE = new TypeInfo(BaseType.NULL_TYPE);

    // Factory methods
    public static TypeInfo number(Integer precision, Integer scale) { ... }
    public static TypeInfo object(String qualifiedName) { ... }
    public static TypeInfo collection(TypeInfo elementType) { ... }

    // Query methods
    public boolean isNumeric() { return baseType == BaseType.NUMBER; }
    public boolean isDate() { return baseType == BaseType.DATE || baseType == BaseType.TIMESTAMP; }
    public boolean isString() { return baseType == BaseType.VARCHAR; }
    public boolean isUnknown() { return baseType == BaseType.UNKNOWN; }
    public boolean isNull() { return baseType == BaseType.NULL_TYPE; }
}
```

---

### Orchestration in TransformationService

```java
public class TransformationService {

    public TransformationResult transformFunction(String oraclePlSql, String schema, TransformationIndices indices) {
        // STEP 1: Parse Oracle PL/SQL
        ParseResult parseResult = parser.parseFunctionBody(oraclePlSql);

        if (parseResult.hasErrors()) {
            return TransformationResult.failure(oraclePlSql, "Parse errors: " + parseResult.getErrorMessage());
        }

        // STEP 2: Type Analysis Pass (NEW)
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor(schema, indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());  // Populates typeCache

        log.debug("Type analysis complete: {} types cached", typeCache.size());

        // STEP 3: Create context with full type evaluator
        TypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext(schema, indices, typeEvaluator);

        // STEP 4: Transformation Pass
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        return TransformationResult.success(oraclePlSql, postgresSql);
    }
}
```

---

## Type System Rules

### Oracle Type Precedence (Implicit Conversions)

Oracle follows these rules for implicit conversions in expressions:

```
TIMESTAMP > DATE > NUMBER > VARCHAR2 > CHAR
```

**Rule:** When mixing types, convert to higher precedence type.

**Examples:**
```sql
-- DATE + NUMBER → DATE
hire_date + 30  -- Add 30 days, result is DATE

-- NUMBER + VARCHAR2 → NUMBER (if VARCHAR2 is numeric string)
salary + '1000'  -- Result is NUMBER

-- DATE || VARCHAR2 → VARCHAR2
hire_date || ' was the date'  -- Result is VARCHAR2 (implicit TO_CHAR)
```

---

### Operator Type Resolution

#### Arithmetic Operators (+, -, *, /)

```java
TypeInfo resolveArithmetic(TypeInfo left, TypeInfo right) {
    // NULL propagation
    if (left.isNull() || right.isNull()) {
        return TypeInfo.NULL_TYPE;
    }

    // DATE arithmetic
    if (left.isDate() && right.isNumeric()) {
        return left;  // DATE + NUMBER → DATE
    }
    if (left.isDate() && right.isDate()) {
        return TypeInfo.NUMBER;  // DATE - DATE → NUMBER (days)
    }

    // Numeric arithmetic
    if (left.isNumeric() && right.isNumeric()) {
        return TypeInfo.NUMBER;
    }

    // Unknown
    return TypeInfo.UNKNOWN;
}
```

#### Concatenation Operator (||)

```java
TypeInfo resolveConcatenation(TypeInfo left, TypeInfo right) {
    // Always returns VARCHAR2 in Oracle
    // But need to know operand types to add TO_CHAR conversions
    return TypeInfo.VARCHAR;
}
```

#### Comparison Operators (=, <, >, <=, >=, !=)

```java
TypeInfo resolveComparison(TypeInfo left, TypeInfo right) {
    // Always returns BOOLEAN
    return TypeInfo.BOOLEAN;
}
```

---

### Function Return Types

#### Built-in Scalar Functions

```java
TypeInfo getFunctionReturnType(String functionName, List<TypeInfo> argTypes) {
    switch (functionName.toUpperCase()) {
        // Polymorphic functions
        case "ROUND":
        case "TRUNC":
            // Return type matches input type
            return argTypes.isEmpty() ? TypeInfo.UNKNOWN : argTypes.get(0);

        // Date functions
        case "SYSDATE":
        case "CURRENT_DATE":
        case "LAST_DAY":
            return TypeInfo.DATE;

        case "SYSTIMESTAMP":
        case "CURRENT_TIMESTAMP":
            return TypeInfo.TIMESTAMP;

        case "ADD_MONTHS":
        case "NEXT_DAY":
            return TypeInfo.DATE;

        case "MONTHS_BETWEEN":
            return TypeInfo.NUMBER;

        // String functions
        case "UPPER":
        case "LOWER":
        case "INITCAP":
        case "TRIM":
        case "LTRIM":
        case "RTRIM":
        case "SUBSTR":
        case "REPLACE":
        case "TRANSLATE":
        case "LPAD":
        case "RPAD":
            return TypeInfo.VARCHAR;

        case "LENGTH":
        case "INSTR":
            return TypeInfo.NUMBER;

        // Conversion functions
        case "TO_CHAR":
            return TypeInfo.VARCHAR;

        case "TO_NUMBER":
            return TypeInfo.NUMBER;

        case "TO_DATE":
            return TypeInfo.DATE;

        case "TO_TIMESTAMP":
            return TypeInfo.TIMESTAMP;

        // NULL-handling functions
        case "NVL":
        case "NVL2":
        case "COALESCE":
            // Return type is highest precedence of arguments
            return resolveCoalesceType(argTypes);

        case "DECODE":
            // Return type is highest precedence of result expressions
            return resolveDecodeType(argTypes);

        // Aggregate functions
        case "COUNT":
            return TypeInfo.NUMBER;

        case "SUM":
        case "AVG":
        case "MIN":
        case "MAX":
            // Return type matches argument type
            return argTypes.isEmpty() ? TypeInfo.UNKNOWN : argTypes.get(0);

        default:
            // Unknown function - need to look up in metadata
            return TypeInfo.UNKNOWN;
    }
}
```

---

### CASE Expression Type Resolution

```java
TypeInfo resolveCaseType(List<TypeInfo> branchTypes) {
    // All branches must be compatible
    // Result type is highest precedence among all branches

    TypeInfo resultType = TypeInfo.UNKNOWN;

    for (TypeInfo branchType : branchTypes) {
        if (branchType.isUnknown()) {
            continue;  // Skip unknowns
        }

        if (resultType.isUnknown()) {
            resultType = branchType;
        } else {
            resultType = higherPrecedence(resultType, branchType);
        }
    }

    return resultType;
}

TypeInfo higherPrecedence(TypeInfo t1, TypeInfo t2) {
    // TIMESTAMP > DATE > NUMBER > VARCHAR
    if (t1.baseType == BaseType.TIMESTAMP || t2.baseType == BaseType.TIMESTAMP) {
        return TypeInfo.TIMESTAMP;
    }
    if (t1.baseType == BaseType.DATE || t2.baseType == BaseType.DATE) {
        return TypeInfo.DATE;
    }
    if (t1.baseType == BaseType.NUMBER || t2.baseType == BaseType.NUMBER) {
        return TypeInfo.NUMBER;
    }
    return TypeInfo.VARCHAR;
}
```

---

## Scope Management

PL/SQL has block-scoped variables. We need a scope stack to track declarations.

### Scope Stack Design

```java
public class TypeAnalysisVisitor extends PlSqlParserBaseVisitor<TypeInfo> {

    /**
     * Scope stack for PL/SQL variable declarations.
     *
     * Each map represents a scope level (DECLARE block, FOR loop, etc.).
     * Inner scopes shadow outer scopes (normal variable resolution rules).
     */
    private final Deque<Map<String, TypeInfo>> scopeStack = new ArrayDeque<>();

    /**
     * Enters a new scope (DECLARE block, BEGIN...END, FOR loop).
     */
    private void enterScope() {
        scopeStack.push(new HashMap<>());
        log.trace("Entered new scope, depth: {}", scopeStack.size());
    }

    /**
     * Exits current scope.
     */
    private void exitScope() {
        Map<String, TypeInfo> scope = scopeStack.pop();
        log.trace("Exited scope, {} variables dropped", scope.size());
    }

    /**
     * Declares a variable in current scope.
     */
    private void declareVariable(String name, TypeInfo type) {
        String normalizedName = name.toLowerCase();
        scopeStack.peek().put(normalizedName, type);
        log.trace("Declared variable: {} with type {}", normalizedName, type);
    }

    /**
     * Looks up variable type, searching from innermost to outermost scope.
     *
     * @return TypeInfo or UNKNOWN if not found
     */
    private TypeInfo lookupVariable(String name) {
        String normalizedName = name.toLowerCase();

        // Search from inner to outer scopes
        for (Map<String, TypeInfo> scope : scopeStack) {
            TypeInfo type = scope.get(normalizedName);
            if (type != null) {
                log.trace("Resolved variable {} to type {}", normalizedName, type);
                return type;
            }
        }

        log.trace("Variable {} not found in any scope", normalizedName);
        return TypeInfo.UNKNOWN;
    }
}
```

---

### Scope Management Examples

#### Example 1: Basic Declaration

```sql
DECLARE
  v_count NUMBER;
  v_name VARCHAR2(100);
BEGIN
  v_count := 42;          -- lookupVariable("v_count") → NUMBER
  v_name := 'John';       -- lookupVariable("v_name") → VARCHAR
END;
```

Visitor behavior:
```java
@Override
public TypeInfo visitFunction_body(Function_bodyContext ctx) {
    enterScope();  // Enter function scope

    // Visit declarations
    if (ctx.seq_of_declare_specs() != null) {
        visit(ctx.seq_of_declare_specs());  // Populates scope with variables
    }

    // Visit body
    visit(ctx.body());

    exitScope();  // Exit function scope
    return TypeInfo.UNKNOWN;  // Functions don't have expression type
}

@Override
public TypeInfo visitVariable_declaration(Variable_declarationContext ctx) {
    String varName = ctx.variable_name().getText();
    TypeInfo varType = resolveTypeSpec(ctx.type_spec());

    declareVariable(varName, varType);

    return TypeInfo.UNKNOWN;
}
```

---

#### Example 2: Nested Scopes

```sql
DECLARE
  v_outer NUMBER := 100;
BEGIN
  DECLARE
    v_inner NUMBER := 200;
  BEGIN
    v_result := v_outer + v_inner;  -- Both visible
  END;

  v_result := v_outer;  -- v_inner not visible here
END;
```

Scope stack during execution:
```
At line "v_result := v_outer + v_inner":
scopeStack = [
  { v_outer: NUMBER },    // Outer scope
  { v_inner: NUMBER }     // Inner scope
]

At line "v_result := v_outer":
scopeStack = [
  { v_outer: NUMBER }     // Outer scope (inner scope exited)
]
```

---

#### Example 3: FOR Loop Variables

```sql
BEGIN
  FOR i IN 1..10 LOOP
    v_result := i * 2;  -- i is INTEGER (loop variable)
  END LOOP;
END;
```

Visitor behavior:
```java
@Override
public TypeInfo visitFor_loop_statement(For_loop_statementContext ctx) {
    enterScope();  // Enter loop scope

    // Declare loop variable
    String loopVar = ctx.index_name().getText();
    declareVariable(loopVar, TypeInfo.NUMBER);  // Loop variables are INTEGER (subtype of NUMBER)

    // Visit loop body
    visit(ctx.seq_of_statements());

    exitScope();  // Exit loop scope
    return TypeInfo.UNKNOWN;
}
```

---

### Type Resolution from Metadata

#### Column Types

```java
private TypeInfo resolveColumnType(String tableName, String columnName) {
    // Resolve table name (might be alias or synonym)
    String actualTable = resolveTableName(tableName);

    // Look up column type in indices
    TransformationIndices.ColumnTypeInfo columnInfo =
        indices.getColumnType(actualTable, columnName);

    if (columnInfo == null) {
        log.warn("Column {}.{} not found in metadata", actualTable, columnName);
        return TypeInfo.UNKNOWN;
    }

    // Convert Oracle type string to TypeInfo
    return parseOracleType(columnInfo.getOracleType());
}

private TypeInfo parseOracleType(String oracleType) {
    String upperType = oracleType.toUpperCase();

    if (upperType.startsWith("NUMBER")) {
        // Parse precision/scale if present: NUMBER(10,2)
        return parseNumberType(oracleType);
    } else if (upperType.startsWith("VARCHAR2") || upperType.startsWith("VARCHAR") || upperType.startsWith("CHAR")) {
        return TypeInfo.VARCHAR;
    } else if (upperType.equals("DATE")) {
        return TypeInfo.DATE;
    } else if (upperType.startsWith("TIMESTAMP")) {
        return TypeInfo.TIMESTAMP;
    } else if (upperType.equals("CLOB")) {
        return TypeInfo.CLOB;
    } else if (upperType.equals("BLOB")) {
        return TypeInfo.BLOB;
    } else {
        // Could be user-defined object type
        return TypeInfo.object(oracleType);
    }
}
```

---

## Implementation Phases

### Phase 1: Foundation (Literals and Simple Expressions) ✅ **COMPLETE**

**Status:** ✅ Completed 2025-10-27

**Goal:** Establish visitor infrastructure and handle simplest cases.

**Scope:**
- ✅ TypeAnalysisVisitor skeleton
- ✅ FullTypeEvaluator implementation
- ✅ Literal type detection (numbers, strings, dates, NULL, booleans)
- ✅ Simple arithmetic operators (+, -, *, /, **, MOD)
- ✅ String concatenation (||)
- ✅ Date arithmetic (DATE + NUMBER, DATE - DATE)
- ✅ NULL propagation
- ✅ Scope management infrastructure (skeleton for Phase 5)

**Implemented Features:**
```sql
-- Literals (all types detected correctly)
v_count := 42;                        -- Type: NUMERIC ✅
v_pi := 3.14159;                      -- Type: NUMERIC ✅
v_name := 'John';                     -- Type: TEXT ✅
v_flag := NULL;                       -- Type: NULL_TYPE ✅
v_active := TRUE;                     -- Type: BOOLEAN ✅
v_today := DATE '2024-01-01';         -- Type: DATE ✅
v_now := TIMESTAMP '2024-01-01 12:00:00';  -- Type: TIMESTAMP ✅

-- Arithmetic operators (full type propagation)
v_total := 100 + 50;                  -- Type: NUMERIC ✅
v_result := 10 * 5;                   -- Type: NUMERIC ✅
v_quotient := 100 / 4;                -- Type: NUMERIC ✅
v_power := 2 ** 8;                    -- Type: NUMERIC ✅

-- Date arithmetic (special rules implemented)
v_future := DATE '2024-01-01' + 30;   -- Type: DATE (add 30 days) ✅
v_past := DATE '2024-01-01' - 7;      -- Type: DATE (subtract 7 days) ✅
v_days := end_date - start_date;      -- Type: NUMERIC (days difference) ✅

-- String concatenation
v_msg := 'Hello' || ' ' || 'World';   -- Type: TEXT ✅

-- NULL propagation (critical for correct transformation)
v_result := 100 + NULL;               -- Type: NULL_TYPE ✅
```

**Test Results:**
```
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 ✅
All tests passing!
```

**Test Coverage:**
- ✅ All literal types (numeric, string, NULL, boolean, DATE, TIMESTAMP)
- ✅ All arithmetic operators (+, -, *, /, **, MOD)
- ✅ Date arithmetic rules (DATE±NUMBER→DATE, DATE-DATE→NUMBER)
- ✅ String concatenation (||→TEXT)
- ✅ NULL propagation in expressions
- ✅ Complex nested expressions (type bubbling)

**Implementation Details:**

**Key Classes Created:**
1. **`TypeAnalysisVisitor.java`** (380 lines)
   - Visitor pattern over ANTLR AST
   - Token position-based caching (stable keys)
   - `visitConstant()` - All literal types
   - `visitConcatenation()` - All binary operators
   - `resolveArithmeticOperator()` - Type rules for *, /
   - `resolvePlusMinusOperator()` - Special handling for date arithmetic
   - Scope stack infrastructure (ready for Phase 5)

2. **`FullTypeEvaluator.java`** (120 lines)
   - Queries pre-computed type cache
   - Same token keys as TypeAnalysisVisitor
   - Immutable after type analysis pass
   - Helper methods: `getCacheSize()`, `hasCachedType()`

3. **Enhanced `TypeInfo.java`**
   - Added `NULL_TYPE` category
   - Added `TypeInfo.NULL_TYPE` constant
   - Added `isNull()` helper method

4. **`TypeAnalysisVisitorPhase1Test.java`** (370 lines)
   - 18 comprehensive unit tests
   - All edge cases covered
   - Helper methods for assertion

**Architecture Highlights:**
- ✅ Token position-based cache (no AST modification needed)
- ✅ Bottom-up type propagation (post-order traversal)
- ✅ Clean separation: Analysis pass vs Lookup interface
- ✅ Extensible design for future phases

**Actual Deliverables:**
- ✅ `TypeAnalysisVisitor.java` (380 lines)
- ✅ `FullTypeEvaluator.java` (120 lines)
- ✅ Enhanced `TypeInfo.java` (NULL_TYPE support)
- ✅ Unit tests: `TypeAnalysisVisitorPhase1Test.java` (370 lines, 18 tests)

**Lessons Learned:**
1. **Order matters**: DATE/TIMESTAMP literals must be checked before quoted_string in visitConstant()
2. **Automatic caching**: Override visit() to cache all intermediate nodes automatically
3. **Date arithmetic**: Special rules needed (DATE+NUMBER→DATE, DATE-DATE→NUMBER)
4. **Test early**: Caught literal ordering bug immediately with tests

**Next Phase Prerequisites:**
Phase 2 (Column References) requires metadata lookup infrastructure. Current foundation is solid for extension.

---

### Phase 2: Column References and Metadata Integration

**Goal:** Resolve types from database metadata (tables, views).

**Scope:**
- Table alias resolution
- Column type lookup from TransformationIndices
- Qualified column references (table.column)
- Unqualified column references (resolve from FROM clause)

**Example transformations:**
```sql
SELECT emp.salary + 1000 AS new_salary
FROM employees emp
WHERE emp.hire_date > DATE '2020-01-01';

-- Need to resolve:
-- - emp.salary → NUMBER (from metadata)
-- - emp.hire_date → DATE (from metadata)
```

**Tests:**
- Column type resolution from metadata
- Table alias handling
- Qualified vs unqualified column references

**Deliverables:**
- Column resolution methods in TypeAnalysisVisitor
- Integration with TransformationIndices
- Unit tests: `TypeAnalysisVisitorMetadataTest.java`

---

### Phase 3: Built-in Functions

**Goal:** Handle Oracle built-in function return types.

**Scope:**
- Function return type mapping
- Polymorphic functions (ROUND, TRUNC, NVL)
- Date functions (SYSDATE, ADD_MONTHS, MONTHS_BETWEEN)
- String functions (UPPER, LOWER, SUBSTR, TRIM)
- Conversion functions (TO_CHAR, TO_DATE, TO_NUMBER)

**Example transformations:**
```sql
-- ROUND with DATE
v_date := ROUND(hire_date);  -- Type: DATE → use DATE_TRUNC

-- ROUND with NUMBER
v_amount := ROUND(salary, 2);  -- Type: NUMBER → use ROUND

-- NVL type propagation
v_date := NVL(termination_date, SYSDATE);  -- Type: DATE
v_count := NVL(commission_pct, 0);          -- Type: NUMBER
```

**Tests:**
- Function return type resolution
- Polymorphic function type detection
- Type propagation through nested functions

**Deliverables:**
- Function type resolution methods
- Function return type mapping table
- Unit tests: `TypeAnalysisVisitorFunctionsTest.java`

---

### Phase 4: Complex Expressions

**Goal:** Handle CASE, operators, type precedence.

**Scope:**
- CASE expression type unification
- Comparison operators (return BOOLEAN)
- String concatenation (||)
- Date arithmetic (DATE + NUMBER → DATE)
- Type precedence rules
- Implicit conversion detection

**Example transformations:**
```sql
-- CASE with mixed types
v_result := CASE status
  WHEN 'A' THEN salary        -- NUMBER
  WHEN 'B' THEN commission    -- NUMBER
  WHEN 'C' THEN 0             -- NUMBER
END;  -- Result: NUMBER

-- Date arithmetic
v_due := hire_date + 30;  -- DATE + NUMBER → DATE

-- String concatenation
v_msg := 'Employee: ' || emp_id;  -- VARCHAR || NUMBER → VARCHAR (with conversion)
```

**Tests:**
- CASE type unification
- Type precedence rules
- Implicit conversion detection
- Complex nested expressions

**Deliverables:**
- CASE expression type resolution
- Operator type rules
- Type precedence logic
- Unit tests: `TypeAnalysisVisitorExpressionsTest.java`

---

### Phase 4.5: Query Scope Stack (Subquery Isolation) 🔄 **IN PROGRESS**

**Goal:** Fix table alias scope pollution in nested subqueries.

**Problem:**
- Flat `Map<String, String> tableAliases` gets cleared when entering subqueries
- Outer query table aliases are lost, causing column resolution failures
- Type inference returns UNKNOWN for valid column references

**Solution:**
- Replace flat map with `Deque<Map<String, String>> tableAliasScopes`
- Push new scope on `enterQueryScope()`, pop on `exitQueryScope()`
- Hierarchical resolution: inner queries can reference outer query tables (correlated subqueries)
- Reuse existing variable scope stack pattern (lines 41, 610-663)

**Implementation Tasks:**
1. ✅ Bug identified and root cause analyzed
2. 🔄 Replace `tableAliases` with `tableAliasScopes` stack
3. 🔄 Add `enterQueryScope()` and `exitQueryScope()` methods
4. 🔄 Update `visitQuery_block()` to use try-finally pattern
5. 🔄 Update `ResolveColumn` to use scoped resolution
6. 🔄 Add test case: subquery with outer reference

**Test Case:**
```sql
-- Should correctly detect spa_abgelehnt_am as DATE
SELECT 1 FROM abs_werk_sperren ws1
WHERE TRUNC(ws1.spa_abgelehnt_am) + (SELECT 1 FROM dual) > CURRENT_DATE
```

**Deliverables:**
- Query scope management methods
- Updated `visitQuery_block()` with proper scoping
- Updated `ResolveColumn` for hierarchical resolution
- Unit test: `TypeAnalysisVisitorQueryScopesTest.java`

---

### Phase 5: PL/SQL Variables and Assignments

**Goal:** Full PL/SQL variable type tracking with scope management.

**Scope:**
- Variable declarations (type_spec parsing)
- %TYPE anchored types (`v_sal employees.salary%TYPE`)
- %ROWTYPE record types (`v_emp employees%ROWTYPE`)
- Assignment statements (type checking)
- Nested scopes (DECLARE blocks, loops)
- FOR loop variables

**Example transformations:**
```sql
DECLARE
  v_count NUMBER;
  v_sal employees.salary%TYPE;  -- Resolve to NUMBER from metadata
  v_emp employees%ROWTYPE;       -- Record type
BEGIN
  v_count := 42;                 -- Type: NUMBER
  v_sal := v_emp.salary;         -- Type: NUMBER (from %ROWTYPE field)

  FOR i IN 1..10 LOOP            -- i: INTEGER
    v_count := v_count + i;
  END LOOP;
END;
```

**Tests:**
- Variable declaration and scope
- %TYPE resolution
- %ROWTYPE record types
- Nested scope handling
- FOR loop variable types

**Deliverables:**
- Scope stack implementation
- %TYPE and %ROWTYPE resolution
- Variable declaration visitors
- Unit tests: `TypeAnalysisVisitorVariablesTest.java`

---

### Phase 6: Collections and Records

**Goal:** Handle complex types (collections, records, object types).

**Scope:**
- Collection types (TABLE OF, VARRAY)
- Record types (TYPE...RECORD)
- Object type member access
- Collection element access
- Record field access

**Example transformations:**
```sql
DECLARE
  TYPE num_array IS TABLE OF NUMBER;
  v_numbers num_array;

  TYPE emp_rec IS RECORD (
    emp_id NUMBER,
    emp_name VARCHAR2(100)
  );
  v_emp emp_rec;
BEGIN
  v_count := v_numbers(1);     -- Type: NUMBER (collection element)
  v_name := v_emp.emp_name;    -- Type: VARCHAR (record field)
END;
```

**Tests:**
- Collection type definitions
- Collection element access
- Record type definitions
- Record field access
- Object type member access

**Deliverables:**
- Collection type resolution
- Record type resolution
- Element/field access type inference
- Unit tests: `TypeAnalysisVisitorCollectionsTest.java`

---

### Phase 7: Integration and Optimization

**Goal:** Integrate with transformation pipeline, optimize performance.

**Scope:**
- Integration with TransformationService
- Performance profiling and optimization
- Type cache invalidation strategy
- Error handling and diagnostics
- Logging and debugging aids

**Tests:**
- End-to-end integration tests
- Performance benchmarks
- Error case handling

**Deliverables:**
- TransformationService integration
- Performance optimizations
- Error handling
- Integration tests: `TypeInferenceIntegrationTest.java`

---

## Testing Strategy

### Unit Testing

Each phase has dedicated test class focusing on specific functionality.

**Example test structure:**
```java
@Test
void literalTypes() {
    // Given: Numeric literal
    String plsql = "v_count := 42;";

    // When: Type analysis
    TypeInfo type = analyzeExpression(plsql, "42");

    // Then: Should be NUMBER
    assertEquals(TypeInfo.BaseType.NUMBER, type.getBaseType());
}

@Test
void arithmeticTypePropagation() {
    // Given: Arithmetic expression with known types
    declareVariable("v_count", TypeInfo.NUMBER);
    String plsql = "v_result := v_count + 100;";

    // When: Type analysis
    TypeInfo type = analyzeExpression(plsql, "v_count + 100");

    // Then: Should be NUMBER
    assertEquals(TypeInfo.BaseType.NUMBER, type.getBaseType());
}

@Test
void dateArithmetic() {
    // Given: DATE + NUMBER
    declareVariable("hire_date", TypeInfo.DATE);
    String plsql = "v_due := hire_date + 30;";

    // When: Type analysis
    TypeInfo type = analyzeExpression(plsql, "hire_date + 30");

    // Then: Should be DATE
    assertEquals(TypeInfo.BaseType.DATE, type.getBaseType());
}
```

---

### Integration Testing

Test complete transformation pipeline with type inference.

**Example:**
```java
@Test
void roundDateVsNumberIntegration() {
    // Given: Oracle PL/SQL with ROUND on different types
    String oraclePlSql = """
        FUNCTION test_round RETURN NUMBER IS
          v_date DATE := SYSDATE;
          v_num NUMBER := 123.456;
        BEGIN
          v_date := ROUND(v_date);      -- Should use DATE_TRUNC
          v_num := ROUND(v_num, 2);     -- Should use ROUND
          RETURN v_num;
        END;
        """;

    // When: Transform with type inference
    TransformationResult result = transformationService.transformFunction(
        oraclePlSql, "hr", indices
    );

    // Then: Should generate correct PostgreSQL
    assertTrue(result.isSuccess());
    String pgSql = result.getPostgresSql();

    // Check DATE_TRUNC used for date
    assertTrue(pgSql.contains("DATE_TRUNC('day', v_date)"));

    // Check ROUND used for number
    assertTrue(pgSql.contains("ROUND(v_num, 2)"));
}
```

---

### Regression Testing

Ensure type inference doesn't break existing transformations.

**Strategy:**
- Run all existing SQL view transformation tests with FullTypeEvaluator
- Verify output is identical or improved (fewer defensive casts)
- Run all existing function transformation tests

---

### Performance Testing

Measure overhead of type analysis pass.

**Metrics:**
- Time to complete type analysis pass
- Time to complete transformation pass
- Total transformation time vs SimpleTypeEvaluator baseline
- Memory usage (type cache size)

**Target:** Type analysis pass should add < 20% overhead to total transformation time.

---

## Integration Points

### 1. TransformationService

**Current:**
```java
public TransformationResult transformFunction(...) {
    // Parse
    ParseResult parseResult = parser.parseFunctionBody(oraclePlSql);

    // Create context with SimpleTypeEvaluator
    TypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);
    TransformationContext context = new TransformationContext(schema, indices, typeEvaluator);

    // Transform
    PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
    String postgresSql = builder.visit(parseResult.getTree());
}
```

**After Phase 7:**
```java
public TransformationResult transformFunction(...) {
    // Parse
    ParseResult parseResult = parser.parseFunctionBody(oraclePlSql);

    // Type analysis pass (NEW)
    Map<String, TypeInfo> typeCache = new HashMap<>();
    TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor(schema, indices, typeCache);
    typeAnalyzer.visit(parseResult.getTree());

    // Create context with FullTypeEvaluator
    TypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
    TransformationContext context = new TransformationContext(schema, indices, typeEvaluator);

    // Transform
    PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
    String postgresSql = builder.visit(parseResult.getTree());
}
```

---

### 2. PostgresCodeBuilder

**No changes required!** Builder continues to query types via `TypeEvaluator` interface.

**Example usage in VisitRound:**
```java
public static String v(FunctionContext ctx, PostgresCodeBuilder b) {
    String funcName = ctx.function_name().getText().toUpperCase();

    if ("ROUND".equals(funcName) || "TRUNC".equals(funcName)) {
        // Get type of first argument
        TypeInfo argType = b.getContext().getTypeEvaluator().getType(ctx.argument(0).expression());

        if (argType.isDate()) {
            // Use DATE_TRUNC for dates
            return transformDateTrunc(ctx, b);
        } else {
            // Use ROUND for numbers (or defensive cast if UNKNOWN)
            return transformNumericRound(ctx, b);
        }
    }

    return defaultTransformation(ctx, b);
}
```

---

### 3. Testing Infrastructure

**Add type inference test base class:**
```java
public abstract class TypeInferenceTestBase {

    protected TypeAnalysisVisitor typeAnalyzer;
    protected Map<String, TypeInfo> typeCache;
    protected TransformationIndices indices;

    @BeforeEach
    void setup() {
        typeCache = new HashMap<>();
        indices = createMockIndices();
        typeAnalyzer = new TypeAnalysisVisitor("hr", indices, typeCache);
    }

    protected TypeInfo analyzeExpression(String plsql, String expressionText) {
        // Parse PL/SQL
        ParseResult result = parser.parseStatement(plsql);

        // Run type analysis
        typeAnalyzer.visit(result.getTree());

        // Find expression node by text (helper method)
        ExpressionContext exprCtx = findExpressionByText(result.getTree(), expressionText);

        // Return cached type
        String key = nodeKey(exprCtx);
        return typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
    }

    protected void declareVariable(String name, TypeInfo type) {
        typeAnalyzer.declareVariable(name, type);
    }
}
```

---

## Open Questions and Future Considerations

### 1. Performance Optimization

**Question:** For large PL/SQL packages, is the type analysis pass overhead acceptable?

**Mitigation:**
- Profile with real-world code
- Optimize type cache with more efficient key generation
- Consider parallel type analysis for independent blocks

---

### 2. Incremental Type Inference

**Question:** Can we cache type information across transformations?

**Current approach:** Type analysis runs fresh for each transformation.

**Future:** Cache column types, function signatures across sessions.

---

### 3. Error Reporting

**Question:** How to report type-related errors to users?

**Examples:**
- Incompatible types in CASE branches
- Type mismatch in assignments
- Unresolvable %TYPE anchor

**Approach:** Collect type errors during analysis pass, include in TransformationResult.

---

### 4. User-Defined Functions

**Question:** How to resolve return types for user-defined functions?

**Options:**
1. Look up in FunctionMetadata (requires metadata extraction)
2. Allow type hints in transformation request
3. Return UNKNOWN and add defensive casts

**Decision:** Start with option 3 (safe default), add option 1 in future phase.

---

### 5. Dynamic SQL

**Question:** How to handle EXECUTE IMMEDIATE with unknown types?

**Answer:** Cannot infer types from dynamic SQL strings. Return UNKNOWN, require explicit casts.

---

## Success Criteria

Type inference implementation is **complete** when:

1. ✅ All 10 critical scenarios from Motivation section work correctly
2. ✅ ROUND/TRUNC disambiguation is 100% accurate for known types
3. ✅ PL/SQL variable scope management handles nested blocks
4. ✅ All existing tests continue to pass
5. ✅ Performance overhead < 20% compared to SimpleTypeEvaluator
6. ✅ Type cache reduces defensive casts by > 50%
7. ✅ Integration tests demonstrate improved transformation quality

---

## References

- Oracle PL/SQL Language Reference: Type conversion rules
- PostgreSQL Documentation: Type system and casting
- ANTLR Visitor Pattern: Multi-pass tree analysis
- Compiler Design: Symbol tables and type checking

---

## Appendix: Example Type Analysis Output

**Input:**
```sql
FUNCTION calculate_bonus(emp_id NUMBER) RETURN NUMBER IS
  v_salary NUMBER;
  v_hire_date DATE;
  v_years NUMBER;
BEGIN
  SELECT salary, hire_date
  INTO v_salary, v_hire_date
  FROM employees
  WHERE employee_id = emp_id;

  v_years := MONTHS_BETWEEN(SYSDATE, v_hire_date) / 12;

  RETURN ROUND(v_salary * 0.1 * v_years, 2);
END;
```

**Type Cache After Analysis:**
```
Variable declarations:
  v_salary     → NUMBER
  v_hire_date  → DATE
  v_years      → NUMBER
  emp_id       → NUMBER (parameter)

Expression types:
  "emp_id"                                     → NUMBER
  "employees.salary"                           → NUMBER (from metadata)
  "employees.hire_date"                        → DATE (from metadata)
  "SYSDATE"                                    → DATE
  "MONTHS_BETWEEN(SYSDATE, v_hire_date)"      → NUMBER
  "... / 12"                                   → NUMBER
  "v_salary * 0.1"                             → NUMBER
  "v_salary * 0.1 * v_years"                   → NUMBER
  "ROUND(..., 2)"                              → NUMBER
```

**Transformation Decisions Enabled:**
- `SYSDATE` → `CURRENT_TIMESTAMP` (date function)
- `MONTHS_BETWEEN` → PostgreSQL equivalent
- `ROUND(..., 2)` → numeric ROUND (not DATE_TRUNC)
- No defensive casts needed (all types known)

---

**End of Plan Document**
