# TRANSFORMATION.md

**Oracle to PostgreSQL SQL/PL/SQL Transformation Architecture**

This document describes the architecture and implementation plan for the ANTLR-based transformation module that converts Oracle SQL and PL/SQL code to PostgreSQL-compatible code.

---

## Overview

The transformation module is a self-contained, reusable system for parsing Oracle SQL/PL/SQL and converting it to PostgreSQL equivalents. It uses a **semantic syntax tree** approach where parsed code is converted into custom Java classes that know how to transform themselves.

**Core Pipeline:**
```
Oracle SQL/PL/SQL → ANTLR AST → Semantic Tree → PostgreSQL SQL/PL/pgSQL
                      ↓              ↓               ↓
                   PlSqlParser   SemanticNode    toPostgres()
```

**Key Design Principles:**
1. **Self-Transforming Nodes**: Each semantic node contains its transformation logic
2. **Incremental Development**: Start simple, add complexity progressively
3. **Test-Driven**: Comprehensive unit tests before integration
4. **Decoupled**: Independent from migration jobs initially
5. **Reusable**: Same infrastructure for views, functions, procedures, triggers

---

## Module Structure

```
transformation/                          # Oracle→PostgreSQL code transformation
│
├── parser/                              # ANTLR Parsing Layer
│   ├── AntlrParser.java                 # Thin wrapper around PlSqlParser
│   ├── ParseResult.java                 # Parse tree + errors wrapper
│   └── SqlType.java                     # Enum: VIEW_SELECT, FUNCTION_BODY, etc.
│
├── semantic/                            # Semantic Syntax Tree (Self-Transforming)
│   │
│   ├── SemanticNode.java                # Base interface with toPostgres()
│   │
│   ├── statement/                       # SQL Statements
│   │   ├── SelectStatement.java         # SELECT with all clauses
│   │   ├── InsertStatement.java         # INSERT (future)
│   │   ├── UpdateStatement.java         # UPDATE (future)
│   │   ├── DeleteStatement.java         # DELETE (future)
│   │   └── MergeStatement.java          # MERGE (future)
│   │
│   ├── expression/                      # SQL Expressions
│   │   ├── FunctionCall.java            # Function calls (NVL, DECODE, etc.)
│   │   ├── BinaryOperation.java         # a + b, a = b, etc.
│   │   ├── UnaryOperation.java          # NOT, -, +
│   │   ├── CaseExpression.java          # CASE WHEN ... END
│   │   ├── Literal.java                 # String, number, date literals
│   │   ├── Identifier.java              # Column/table names
│   │   ├── SubqueryExpression.java      # (SELECT ...)
│   │   └── CastExpression.java          # Type casting
│   │
│   ├── clause/                          # SQL Clauses
│   │   ├── SelectList.java              # Column list in SELECT
│   │   ├── FromClause.java              # FROM with tables/joins
│   │   ├── WhereClause.java             # WHERE condition
│   │   ├── JoinClause.java              # JOIN with ON condition
│   │   ├── GroupByClause.java           # GROUP BY
│   │   ├── HavingClause.java            # HAVING
│   │   ├── OrderByClause.java           # ORDER BY
│   │   └── WithClause.java              # WITH (CTE) (future)
│   │
│   ├── element/                         # Sub-Elements
│   │   ├── TableReference.java          # Table/view reference with alias
│   │   ├── ColumnReference.java         # Qualified column reference
│   │   ├── OrderByElement.java          # Column + ASC/DESC
│   │   └── SelectColumn.java            # Column or expression with alias
│   │
│   └── plsql/                           # PL/SQL Nodes (Future)
│       ├── FunctionBody.java            # Function implementation
│       ├── ProcedureBody.java           # Procedure implementation
│       ├── DeclareSection.java          # Variable declarations
│       ├── IfStatement.java             # IF-THEN-ELSE
│       ├── LoopStatement.java           # FOR/WHILE loops
│       ├── CursorDeclaration.java       # Cursor definitions
│       └── ExceptionHandler.java        # EXCEPTION blocks
│
├── builder/                             # AST to Semantic Tree Conversion
│   └── SemanticTreeBuilder.java         # Visitor: ANTLR AST → Semantic Tree
│
├── context/                             # Transformation Context
│   ├── TransformationContext.java       # Global state (StateService, schema, etc.)
│   ├── TransformationResult.java        # Success/error result wrapper
│   └── TransformationException.java     # Custom exception for transform errors
│
└── service/                             # High-Level Services (Job Integration)
    ├── ViewTransformationService.java   # View SQL transformation
    └── (future) FunctionTransformationService.java
```

---

## Core Interfaces and Classes

### SemanticNode Interface

```java
/**
 * Base interface for all semantic syntax tree nodes.
 * Each node knows how to transform itself to PostgreSQL.
 */
public interface SemanticNode {
    /**
     * Transform this node to PostgreSQL equivalent.
     * @param context Global context (StateService, synonym resolution, etc.)
     * @return PostgreSQL SQL/PL/pgSQL string
     */
    String toPostgres(TransformationContext context);
}
```

### TransformationContext Class

```java
/**
 * Provides global context for transformation process.
 * Injected into semantic nodes to access StateService, synonym resolution, etc.
 */
public class TransformationContext {
    private final StateService stateService;
    private final String currentSchema;
    private final Map<String, String> localAliases;

    // Helper methods
    public String resolveSynonym(String name);
    public String convertType(String oracleType);
    public void registerAlias(String alias, String tableName);
    public String resolveAlias(String alias);
}
```

### AntlrParser Class

```java
/**
 * Thin wrapper around ANTLR PlSqlParser.
 * Handles parser instantiation and error collection.
 */
@ApplicationScoped
public class AntlrParser {
    public ParseResult parseSelectStatement(String sql);
    public ParseResult parseFunctionBody(String plsql);  // Future
    public ParseResult parseProcedureBody(String plsql); // Future
}
```

### SemanticTreeBuilder Class

```java
/**
 * Visitor that converts ANTLR parse tree to semantic syntax tree.
 * This is the only class that directly touches ANTLR classes.
 */
public class SemanticTreeBuilder extends PlSqlParserBaseVisitor<SemanticNode> {
    @Override
    public SemanticNode visitSelect_statement(PlSqlParser.Select_statementContext ctx);

    @Override
    public SemanticNode visitFunction_call(PlSqlParser.Function_callContext ctx);

    // ... visitor methods for each ANTLR rule
}
```

---

## Implementation Phases

### Phase 1: Foundation (Week 1)

**Goal**: Build core infrastructure with minimal functionality

**Components to Implement:**

1. **Base Infrastructure**
   - `SemanticNode` interface
   - `TransformationContext` class
   - `TransformationResult` class
   - `TransformationException` class

2. **Parser Layer**
   - `SqlType` enum (VIEW_SELECT, FUNCTION_BODY, etc.)
   - `ParseResult` class (wraps parse tree + errors)
   - `AntlrParser` class (SELECT statement parsing only)

3. **Minimal Semantic Nodes**
   - `Identifier` - Column/table names
   - `Literal` - String, number literals
   - `TableReference` - Simple table reference with optional alias
   - `ColumnReference` - Column reference (optionally qualified)

4. **Builder**
   - `SemanticTreeBuilder` (visits only the nodes above)

5. **Tests**
   - Unit tests for each semantic node
   - Parser tests for simple SELECT statements

**Deliverable**: Parse and transform `SELECT col1, col2 FROM table1`

**Test Coverage:**
```java
@Test
void transformSimpleSelect() {
    String oracle = "SELECT empno, ename FROM emp";
    String expected = "SELECT empno, ename FROM emp";

    ParseResult parseResult = parser.parseSelectStatement(oracle);
    SemanticNode tree = builder.visit(parseResult.getTree());
    String postgres = tree.toPostgres(context);

    assertEquals(expected, postgres);
}
```

---

### Phase 2: Basic SELECT Statements (Week 2)

**Goal**: Handle common SELECT statement components

**Components to Implement:**

1. **Statement Nodes**
   - `SelectStatement` - Complete SELECT with clauses

2. **Clause Nodes**
   - `SelectList` - Column list with expressions
   - `FromClause` - Table list (no joins yet)
   - `WhereClause` - Filter conditions
   - `OrderByClause` - Sorting

3. **Expression Nodes**
   - `BinaryOperation` - Arithmetic (+, -, *, /) and comparison (=, <, >, etc.)
   - `UnaryOperation` - NOT, unary minus
   - `SelectColumn` - Column/expression with optional alias

4. **Element Nodes**
   - `OrderByElement` - Column + ASC/DESC + NULLS FIRST/LAST

**Deliverable**: Transform SELECTs with WHERE, ORDER BY

**Test Examples:**
```sql
-- Oracle
SELECT empno, ename, sal * 12 AS annual_salary
FROM emp
WHERE deptno = 10
ORDER BY ename;

-- PostgreSQL (same)
SELECT empno, ename, sal * 12 AS annual_salary
FROM emp
WHERE deptno = 10
ORDER BY ename;
```

```sql
-- Oracle
SELECT * FROM emp
ORDER BY sal DESC NULLS LAST;

-- PostgreSQL (same)
SELECT * FROM emp
ORDER BY sal DESC NULLS LAST;
```

---

### Phase 3: Oracle-Specific Transformations (Week 3)

**Goal**: Handle Oracle-specific SQL constructs

**Components to Implement:**

1. **Function Transformations**
   - `FunctionCall` node with Oracle→PostgreSQL mappings:
     - `NVL(a, b)` → `COALESCE(a, b)`
     - `DECODE(...)` → `CASE ... END`
     - `SYSDATE` → `CURRENT_TIMESTAMP`
     - `ROWNUM` → `row_number() OVER ()`
     - `TO_DATE(str, fmt)` → `TO_TIMESTAMP(str, fmt)` (with format conversion)
     - `TO_CHAR(date, fmt)` → `TO_CHAR(date, fmt)` (with format conversion)
     - `SUBSTR(str, pos, len)` → `SUBSTRING(str FROM pos FOR len)`
     - `INSTR(str, substr)` → `POSITION(substr IN str)`
     - `CONCAT(a, b)` → `a || b`
     - `LENGTH` → `LENGTH` (compatible)
     - `UPPER`, `LOWER`, `TRIM` → (compatible)

2. **Dual Table Handling**
   - `SELECT expression FROM DUAL` → `SELECT expression`

3. **Sequence Syntax**
   - `seq_name.NEXTVAL` → `nextval('schema.seq_name')`
   - `seq_name.CURRVAL` → `currval('schema.seq_name')`

4. **Oracle Join Syntax** (if present in views)
   - `a.id = b.id(+)` → `LEFT JOIN`
   - Detect (+) in WHERE clause, convert to ANSI JOIN syntax

**Test Examples:**
```sql
-- Oracle
SELECT NVL(commission, 0) FROM emp;
-- PostgreSQL
SELECT COALESCE(commission, 0) FROM emp;
```

```sql
-- Oracle
SELECT DECODE(deptno, 10, 'A', 20, 'B', 'C') FROM emp;
-- PostgreSQL
SELECT CASE deptno WHEN 10 THEN 'A' WHEN 20 THEN 'B' ELSE 'C' END FROM emp;
```

```sql
-- Oracle
SELECT emp_seq.NEXTVAL FROM DUAL;
-- PostgreSQL
SELECT nextval('schema.emp_seq');
```

---

### Phase 4: Complex SELECT Features (Week 4)

**Goal**: Handle advanced SQL constructs

**Components to Implement:**

1. **Joins**
   - `JoinClause` - INNER, LEFT, RIGHT, FULL OUTER
   - Convert Oracle (+) syntax to ANSI joins

2. **Aggregation**
   - `GroupByClause`
   - `HavingClause`
   - Aggregate functions (SUM, COUNT, AVG, MAX, MIN)

3. **Subqueries**
   - `SubqueryExpression` - Subquery in SELECT/WHERE
   - IN (SELECT ...)
   - EXISTS (SELECT ...)

4. **CASE Expressions**
   - `CaseExpression` - CASE WHEN ... END

5. **Type Casting**
   - `CastExpression` - CAST(expr AS type)
   - Oracle implicit conversions → explicit CAST

**Test Examples:**
```sql
-- Oracle
SELECT e.ename, d.dname
FROM emp e, dept d
WHERE e.deptno = d.deptno(+);

-- PostgreSQL
SELECT e.ename, d.dname
FROM emp e
LEFT JOIN dept d ON e.deptno = d.deptno;
```

```sql
-- Oracle
SELECT deptno, COUNT(*)
FROM emp
GROUP BY deptno
HAVING COUNT(*) > 5;

-- PostgreSQL (same)
SELECT deptno, COUNT(*)
FROM emp
GROUP BY deptno
HAVING COUNT(*) > 5;
```

---

### Phase 5: Integration with View Jobs (Week 5)

**Goal**: Connect transformation module to actual migration jobs

**Components to Implement:**

1. **Service Layer**
   - `ViewTransformationService` - Orchestrates view transformation
   - Integrates with StateService for synonym resolution
   - Error handling and logging

2. **Job Updates**
   - Update `PostgresViewImplementationJob` to use transformation service
   - Replace stub views with transformed SQL

3. **Verification**
   - Compare transformed views against PostgreSQL syntax
   - Identify views that need manual intervention

**Integration Pattern:**
```java
@ApplicationScoped
public class ViewTransformationService {
    @Inject StateService stateService;
    @Inject AntlrParser parser;

    public TransformationResult transformView(ViewMetadata view) {
        // 1. Parse Oracle SQL
        ParseResult parseResult = parser.parseSelectStatement(view.getSqlDefinition());

        // 2. Build semantic tree
        SemanticNode tree = new SemanticTreeBuilder().visit(parseResult.getTree());

        // 3. Create context
        TransformationContext ctx = new TransformationContext(stateService, view.getSchema());

        // 4. Transform
        String postgresSql = tree.toPostgres(ctx);

        return TransformationResult.success(view, postgresSql);
    }
}
```

**Job Integration:**
```java
@Override
protected List<ViewImplementationResult> performExtraction(...) {
    for (ViewMetadata view : stateService.getOracleViewMetadata()) {
        TransformationResult result = viewTransformationService.transformView(view);

        if (result.isSuccess()) {
            createView(view.getSchema(), view.getViewName(), result.getPostgresSql());
        } else {
            logError(view.getViewName(), result.getError());
        }
    }
}
```

---

### Phase 6: PL/SQL Foundation (Future - Week 6+)

**Goal**: Extend to PL/SQL function/procedure bodies

**Components to Implement:**

1. **PL/SQL Statement Nodes**
   - `FunctionBody` - Complete function with DECLARE, BEGIN, END
   - `ProcedureBody` - Complete procedure
   - `DeclareSection` - Variable/cursor declarations
   - `BeginBlock` - Executable statements

2. **PL/SQL Control Flow**
   - `IfStatement` - IF-THEN-ELSIF-ELSE
   - `LoopStatement` - FOR/WHILE loops
   - `ExitStatement` - EXIT WHEN
   - `ReturnStatement` - RETURN expression

3. **PL/SQL Data Structures**
   - `VariableDeclaration` - Variable declarations
   - `CursorDeclaration` - Cursor definitions
   - `RecordType` - Record types
   - `TableType` - Table types (collections)

4. **Exception Handling**
   - `ExceptionHandler` - EXCEPTION blocks
   - Exception name mapping (NO_DATA_FOUND, etc.)

**Reuse Pattern:**
```
Same semantic nodes for:
- Expressions: FunctionCall, BinaryOperation, etc.
- Statements: SelectStatement (in cursor loops)
- Context: Same TransformationContext with StateService access
```

---

## Oracle Function Mapping Reference

### Critical Functions (Priority 1 - Phase 3)

| Oracle Function | PostgreSQL Equivalent | Notes |
|-----------------|----------------------|-------|
| `NVL(a, b)` | `COALESCE(a, b)` | Null handling |
| `NVL2(expr, val1, val2)` | `CASE WHEN expr IS NOT NULL THEN val1 ELSE val2 END` | Conditional |
| `DECODE(expr, search, result, ...)` | `CASE expr WHEN search THEN result ... END` | Multi-way branch |
| `SYSDATE` | `CURRENT_TIMESTAMP` | Current date/time |
| `ROWNUM` | `row_number() OVER ()` | Row numbering |
| `DUAL` | (remove table) | Dummy table |

### String Functions (Priority 2 - Phase 3)

| Oracle Function | PostgreSQL Equivalent | Notes |
|-----------------|----------------------|-------|
| `SUBSTR(str, pos, len)` | `SUBSTRING(str FROM pos FOR len)` | Substring |
| `INSTR(str, substr)` | `POSITION(substr IN str)` | String search |
| `LENGTH(str)` | `LENGTH(str)` | Compatible |
| `UPPER(str)` | `UPPER(str)` | Compatible |
| `LOWER(str)` | `LOWER(str)` | Compatible |
| `TRIM(str)` | `TRIM(str)` | Compatible |
| `LTRIM(str)` | `LTRIM(str)` | Compatible |
| `RTRIM(str)` | `RTRIM(str)` | Compatible |
| `CONCAT(a, b)` | `a || b` | Concatenation |
| `LPAD(str, len, pad)` | `LPAD(str, len, pad)` | Compatible |
| `RPAD(str, len, pad)` | `RPAD(str, len, pad)` | Compatible |
| `REPLACE(str, from, to)` | `REPLACE(str, from, to)` | Compatible |

### Date Functions (Priority 3 - Phase 3)

| Oracle Function | PostgreSQL Equivalent | Notes |
|-----------------|----------------------|-------|
| `TO_DATE(str, fmt)` | `TO_TIMESTAMP(str, fmt)` | Format codes differ |
| `TO_CHAR(date, fmt)` | `TO_CHAR(date, fmt)` | Format codes differ |
| `ADD_MONTHS(date, n)` | `date + INTERVAL 'n months'` | Date arithmetic |
| `MONTHS_BETWEEN(d1, d2)` | Custom function needed | Month difference |
| `TRUNC(date, fmt)` | `DATE_TRUNC(fmt, date)` | Truncate date |
| `LAST_DAY(date)` | `(DATE_TRUNC('MONTH', date) + INTERVAL '1 MONTH - 1 day')::date` | Last day of month |
| `NEXT_DAY(date, day)` | Custom function needed | Next specified weekday |

**Date Format Code Mapping:**
- Oracle `DD-MON-YYYY` → PostgreSQL `DD-Mon-YYYY`
- Oracle `HH24` → PostgreSQL `HH24`
- Oracle `MI` → PostgreSQL `MI`
- Oracle `SS` → PostgreSQL `SS`

### Numeric Functions (Priority 4 - Phase 4)

| Oracle Function | PostgreSQL Equivalent | Notes |
|-----------------|----------------------|-------|
| `ROUND(n, d)` | `ROUND(n, d)` | Compatible |
| `TRUNC(n, d)` | `TRUNC(n, d)` | Compatible |
| `CEIL(n)` | `CEIL(n)` | Compatible |
| `FLOOR(n)` | `FLOOR(n)` | Compatible |
| `ABS(n)` | `ABS(n)` | Compatible |
| `MOD(n, m)` | `MOD(n, m)` | Compatible |
| `POWER(n, m)` | `POWER(n, m)` | Compatible |
| `SQRT(n)` | `SQRT(n)` | Compatible |
| `SIGN(n)` | `SIGN(n)` | Compatible |

### Aggregate Functions (Priority 5 - Phase 4)

| Oracle Function | PostgreSQL Equivalent | Notes |
|-----------------|----------------------|-------|
| `COUNT(*)` | `COUNT(*)` | Compatible |
| `SUM(expr)` | `SUM(expr)` | Compatible |
| `AVG(expr)` | `AVG(expr)` | Compatible |
| `MAX(expr)` | `MAX(expr)` | Compatible |
| `MIN(expr)` | `MIN(expr)` | Compatible |
| `STDDEV(expr)` | `STDDEV(expr)` | Compatible |
| `VARIANCE(expr)` | `VARIANCE(expr)` | Compatible |

### Analytic Functions (Priority 6 - Phase 4)

| Oracle Function | PostgreSQL Equivalent | Notes |
|-----------------|----------------------|-------|
| `ROW_NUMBER() OVER (...)` | `ROW_NUMBER() OVER (...)` | Compatible |
| `RANK() OVER (...)` | `RANK() OVER (...)` | Compatible |
| `DENSE_RANK() OVER (...)` | `DENSE_RANK() OVER (...)` | Compatible |
| `LEAD(expr, n) OVER (...)` | `LEAD(expr, n) OVER (...)` | Compatible |
| `LAG(expr, n) OVER (...)` | `LAG(expr, n) OVER (...)` | Compatible |

---

## Advanced Features (Deferred)

### Not Implementing Initially

These Oracle features are **out of scope** for initial phases. Views using these will be flagged for manual migration:

1. **CONNECT BY** - Hierarchical queries
   - PostgreSQL: Use recursive CTEs (WITH RECURSIVE)
   - Complex transformation, manual migration recommended

2. **PIVOT/UNPIVOT** - Row/column transformation
   - PostgreSQL: Use crosstab or CASE expressions
   - Uncommon in views, manual migration if needed

3. **MERGE** statements
   - PostgreSQL 15+: Native MERGE support
   - Older versions: Use INSERT ... ON CONFLICT

4. **MODEL clause** - Spreadsheet-like calculations
   - No direct PostgreSQL equivalent
   - Rare, manual migration required

5. **Flashback queries** (AS OF TIMESTAMP)
   - No PostgreSQL equivalent
   - Would need application-level versioning

6. **XML functions** (XMLELEMENT, XMLAGG, etc.)
   - PostgreSQL has XML functions but syntax differs
   - Phase 2 feature if needed

**Strategy for Unsupported Features:**
```java
// In transformation code
if (ctx.CONNECT() != null) {
    throw new TransformationException(
        "CONNECT BY not supported - manual migration required"
    );
}
```

---

## Testing Strategy

### Unit Test Structure

**Test Organization:**
```
src/test/java/.../transformation/
├── semantic/
│   ├── statement/
│   │   └── SelectStatementTest.java
│   ├── expression/
│   │   ├── FunctionCallTest.java
│   │   ├── BinaryOperationTest.java
│   │   └── CaseExpressionTest.java
│   └── clause/
│       ├── FromClauseTest.java
│       └── WhereClauseTest.java
├── builder/
│   └── SemanticTreeBuilderTest.java
└── service/
    └── ViewTransformationServiceTest.java
```

### Test Categories

**1. Semantic Node Unit Tests**
- Test each semantic node's `toPostgres()` method
- Mock `TransformationContext`
- Focus on transformation logic only

Example:
```java
@Test
void nvlFunctionTransformsToCoalesce() {
    FunctionCall nvl = new FunctionCall("NVL", List.of(
        new Identifier("salary"),
        new Literal("0", LiteralType.NUMBER)
    ));

    String result = nvl.toPostgres(mockContext);
    assertEquals("COALESCE(salary, 0)", result);
}
```

**2. Builder Integration Tests**
- Test ANTLR AST → Semantic Tree conversion
- Use real ANTLR parser
- Verify correct semantic nodes are created

Example:
```java
@Test
void buildSelectStatementFromAntlrTree() {
    String sql = "SELECT empno FROM emp WHERE deptno = 10";
    ParseResult parseResult = parser.parseSelectStatement(sql);

    SemanticNode tree = builder.visit(parseResult.getTree());

    assertInstanceOf(SelectStatement.class, tree);
    SelectStatement select = (SelectStatement) tree;
    assertEquals(1, select.getSelectList().size());
    assertNotNull(select.getWhereClause());
}
```

**3. End-to-End Transformation Tests**
- Full pipeline: Oracle SQL → PostgreSQL SQL
- Real ANTLR parser + semantic tree + transformation
- Compare expected vs actual output

Example:
```java
@Test
void transformCompleteSelectStatement() {
    String oracle = "SELECT NVL(sal, 0), DECODE(deptno, 10, 'A', 'B') " +
                    "FROM emp WHERE ROWNUM <= 10";
    String expected = "SELECT COALESCE(sal, 0), " +
                     "CASE deptno WHEN 10 THEN 'A' ELSE 'B' END " +
                     "FROM emp LIMIT 10";

    TransformationResult result = service.transformViewSql(oracle, "hr");
    assertTrue(result.isSuccess());
    assertEquals(expected, result.getPostgresSql());
}
```

**4. Error Handling Tests**
- Test syntax errors
- Test unsupported features
- Test transformation failures

Example:
```java
@Test
void errorOnConnectByClause() {
    String oracle = "SELECT * FROM emp CONNECT BY PRIOR empno = mgr";

    TransformationResult result = service.transformViewSql(oracle, "hr");
    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("CONNECT BY"));
}
```

### Test Coverage Goals

- **Semantic Nodes**: 100% code coverage
- **Builder**: 90%+ coverage
- **Service**: 90%+ coverage
- **Overall Module**: 95%+ coverage

### Test Data Organization

**Test SQL Repository:**
```
src/test/resources/transformation/
├── simple_select.sql
├── select_with_where.sql
├── select_with_joins.sql
├── oracle_functions.sql
├── date_functions.sql
└── complex_queries.sql
```

Each file contains pairs:
```sql
-- ORACLE
SELECT NVL(salary, 0) FROM emp;

-- EXPECTED_POSTGRES
SELECT COALESCE(salary, 0) FROM emp;

---

-- ORACLE
SELECT DECODE(status, 'A', 'Active', 'Inactive') FROM emp;

-- EXPECTED_POSTGRES
SELECT CASE status WHEN 'A' THEN 'Active' ELSE 'Inactive' END FROM emp;
```

---

## Error Handling and Logging

### Exception Hierarchy

```java
TransformationException (base)
├── ParseException                  // ANTLR parsing failed
├── UnsupportedSyntaxException      // Feature not supported
└── TransformationLogicException    // Bug in transformation logic
```

### Error Context

```java
public class TransformationException extends RuntimeException {
    private final String oracleSql;
    private final String viewName;
    private final int lineNumber;
    private final String errorContext;

    public String getDetailedMessage() {
        return String.format(
            "Failed to transform view '%s' at line %d: %s\nOracle SQL: %s\nContext: %s",
            viewName, lineNumber, getMessage(), oracleSql, errorContext
        );
    }
}
```

### Logging Strategy

```java
// In ViewTransformationService
if (result.isSuccess()) {
    log.info("Successfully transformed view {}.{}", schema, viewName);
    log.debug("Transformed SQL: {}", result.getPostgresSql());
} else {
    log.warn("Failed to transform view {}.{}: {}", schema, viewName, result.getError());
    log.debug("Oracle SQL: {}", view.getSqlDefinition());
}
```

### Transformation Report

After migration, generate report:
```
View Transformation Summary
===========================
Total views: 150
Successfully transformed: 142 (94.7%)
Failed transformations: 8 (5.3%)

Failed Views:
- hr.emp_hierarchy_view - CONNECT BY not supported
- sales.pivot_summary - PIVOT clause not supported
- ... (6 more)

Manual Migration Required: See detailed error log
```

---

## Integration with Migration Jobs

### Phase 1: Decoupled Development

**Goal**: Build transformation module independently

**Approach:**
- No integration with jobs initially
- All validation via unit tests
- Mock StateService in tests

### Phase 2: Service Layer Integration

**Goal**: Create service facade for job consumption

**Implementation:**
```java
@ApplicationScoped
public class ViewTransformationService {
    @Inject StateService stateService;
    @Inject AntlrParser parser;

    /**
     * Transform Oracle view SQL to PostgreSQL.
     * This is the public API for migration jobs.
     */
    public TransformationResult transformView(ViewMetadata view) {
        // Implementation
    }
}
```

### Phase 3: Job Updates

**Goal**: Update PostgresViewImplementationJob to use transformation

**Changes:**
```java
@Dependent
public class PostgresViewImplementationJob extends AbstractDatabaseExtractionJob<ViewImplementationResult> {

    @Inject
    private ViewTransformationService transformationService;

    @Override
    protected List<ViewImplementationResult> performExtraction(...) {
        ViewImplementationResult result = new ViewImplementationResult();

        for (ViewMetadata view : stateService.getOracleViewMetadata()) {
            try {
                TransformationResult transformResult =
                    transformationService.transformView(view);

                if (transformResult.isSuccess()) {
                    String createViewSql = String.format(
                        "CREATE OR REPLACE VIEW %s.%s AS %s",
                        view.getSchema(),
                        view.getViewName(),
                        transformResult.getPostgresSql()
                    );

                    executePostgresSql(createViewSql);
                    result.incrementImplemented();

                } else {
                    log.warn("Skipping view {}: {}",
                            view.getViewName(),
                            transformResult.getError());
                    result.addError(view.getViewName(), transformResult.getError());
                }

            } catch (Exception e) {
                log.error("Failed to process view: " + view.getViewName(), e);
                result.addError(view.getViewName(), e.getMessage());
            }
        }

        return List.of(result);
    }
}
```

---

## Performance Considerations

### Parsing Performance

**Expected Load:**
- Typical Oracle schema: 50-200 views
- Each view: 5-50 lines of SQL
- Total parsing time: < 5 seconds for 200 views

**Optimizations:**
- ANTLR parser is fast (microseconds per statement)
- No caching needed initially
- If needed: Cache parsed semantic trees by view SQL hash

### Memory Usage

**Per-View Memory:**
- Semantic tree: ~10KB per view
- Negligible for typical workload
- All views in memory during migration is acceptable

**Memory Profile:**
```
100 views × 10KB/view = 1MB semantic trees
+ ANTLR overhead ~5MB
= Total < 10MB additional memory
```

---

## Future Extensions

### 1. PL/SQL Function Bodies

**Reuse:**
- Same `SemanticNode` interface
- Same expression nodes (FunctionCall, BinaryOperation, etc.)
- Same transformation context

**New Nodes:**
- PL/SQL control flow (IF, LOOP, etc.)
- Variable declarations
- Exception handlers

**Service:**
```java
@ApplicationScoped
public class FunctionTransformationService {
    public TransformationResult transformFunction(FunctionMetadata func) {
        // Parse function body
        // Build semantic tree
        // Transform to PL/pgSQL
    }
}
```

### 2. Procedure Bodies

Same approach as functions, reuse same PL/SQL nodes.

### 3. Triggers

**Additional Considerations:**
- Trigger timing (BEFORE/AFTER/INSTEAD OF)
- Row-level vs statement-level
- :NEW/:OLD row references → NEW/OLD

### 4. Type Method Implementations

Similar to functions but attached to object types.

### 5. Package Bodies

**Challenges:**
- Package state (private variables)
- Package initialization blocks
- Overloaded functions

**Approach:**
- Flatten to individual functions (already done for signatures)
- Shared state → schema-level variables or tables
- Manual migration for complex packages

---

## Development Workflow

### Week-by-Week Plan

**Week 1: Foundation**
- [ ] Create module structure
- [ ] Implement base interfaces
- [ ] Implement Identifier, Literal nodes
- [ ] Implement AntlrParser (SELECT only)
- [ ] Write unit tests
- [ ] Parse and transform `SELECT col FROM table`

**Week 2: Basic SELECT**
- [ ] Implement SelectStatement, SelectList, FromClause
- [ ] Implement WhereClause, OrderByClause
- [ ] Implement BinaryOperation, ColumnReference
- [ ] Write comprehensive tests
- [ ] Transform SELECT with WHERE, ORDER BY

**Week 3: Oracle Functions**
- [ ] Implement FunctionCall with transformations
- [ ] NVL, DECODE, SYSDATE, ROWNUM
- [ ] String functions (SUBSTR, INSTR, etc.)
- [ ] Date functions (TO_DATE, TO_CHAR)
- [ ] DUAL table handling
- [ ] Sequence syntax (.NEXTVAL)
- [ ] Write extensive function tests

**Week 4: Advanced SELECT**
- [ ] Implement JoinClause (ANSI joins)
- [ ] Oracle (+) join syntax detection and conversion
- [ ] GroupByClause, HavingClause
- [ ] SubqueryExpression
- [ ] CaseExpression
- [ ] Write complex query tests

**Week 5: Integration**
- [ ] Implement ViewTransformationService
- [ ] Update PostgresViewImplementationJob
- [ ] Integration testing with real views
- [ ] Error handling and reporting
- [ ] Generate transformation report

**Week 6+: PL/SQL (Future)**
- [ ] Extend to function bodies
- [ ] PL/SQL control flow nodes
- [ ] Variable declarations
- [ ] Exception handling

---

## Success Metrics

### Phase 1-4 (SQL Views)

**Coverage:**
- Transform 90%+ of typical Oracle views automatically
- Remaining 10% flagged for manual migration with clear error messages

**Quality:**
- Generated PostgreSQL SQL is syntactically correct
- Generated SQL is semantically equivalent to Oracle SQL
- Zero false positives (incorrect transformations)

**Performance:**
- Parse and transform 200 views in < 10 seconds
- Memory usage < 50MB additional

### Phase 5+ (PL/SQL)

**Coverage:**
- Transform 70%+ of simple functions/procedures automatically
- Complex PL/SQL may require manual intervention

**Quality:**
- Generated PL/pgSQL compiles without errors
- Behavior matches Oracle PL/SQL for common patterns

---

## Open Questions and Decisions

### 1. Date Format Conversion

**Question**: How comprehensive should date format string conversion be?

**Options:**
- A) Convert common formats only (DD-MON-YYYY, etc.)
- B) Full format code translation table
- C) Flag for manual review if uncommon format detected

**Decision**: Start with (A), expand to (B) as needed

### 2. Implicit Type Conversions

**Question**: Should we make Oracle's implicit conversions explicit in PostgreSQL?

**Example:**
```sql
-- Oracle (implicit conversion)
SELECT * FROM emp WHERE empno = '7369';

-- PostgreSQL options
SELECT * FROM emp WHERE empno = '7369';        -- May work
SELECT * FROM emp WHERE empno = 7369;          -- Safer
SELECT * FROM emp WHERE empno = CAST('7369' AS INTEGER);  -- Explicit
```

**Decision**: Keep implicit conversions initially, add explicit CAST if errors occur

### 3. Identifier Quoting

**Question**: When should identifiers be quoted in PostgreSQL?

**Oracle**: Case-insensitive unless quoted
**PostgreSQL**: Case-insensitive unless quoted, but folds to lowercase

**Decision**: Don't quote identifiers unless they contain special characters or are keywords

### 4. Schema Qualification

**Question**: Should generated SQL always use schema-qualified names?

**Example:**
```sql
-- Unqualified
SELECT * FROM emp;

-- Qualified
SELECT * FROM hr.emp;
```

**Decision**: Use qualified names for tables, rely on search_path for built-in functions

---

## Conclusion

This transformation module provides a **clean, reusable, test-driven foundation** for converting Oracle SQL and PL/SQL to PostgreSQL. The semantic tree approach allows for:

✅ **Self-documenting code**: Each node knows its transformation
✅ **Incremental development**: Add features progressively
✅ **Comprehensive testing**: Unit tests for every component
✅ **Future extensibility**: Same infrastructure for views, functions, procedures, triggers
✅ **Decoupled architecture**: Independent of migration jobs initially

The phased implementation ensures we can **deliver value incrementally** while building toward a comprehensive transformation solution.
