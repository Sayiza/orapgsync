# TRANSFORMATION.md

**Oracle to PostgreSQL SQL/PL/SQL Transformation Architecture**

**Last Updated:** 2025-10-18
**Status:** Direct AST Approach - Phase 2 Nearly Complete ✅ (72 tests passing)

This document describes the architecture and implementation plan for the ANTLR-based transformation module that converts Oracle SQL and PL/SQL code to PostgreSQL-compatible code.

---

## ⚠️ ARCHITECTURAL DECISION: Direct AST Transformation

**Decision Date:** 2025-10-17

After implementing and comparing two approaches (semantic tree vs direct AST), we have adopted the **Direct AST-to-Code transformation strategy** as the primary implementation.

**Rationale:**
1. ✅ **Already working** - Tests pass (4/4), basic SELECT transforms successfully
2. ✅ **Simpler architecture** - Single transformation pass (ANTLR AST → PostgreSQL SQL)
3. ✅ **Faster development** - Add visitor methods incrementally
4. ✅ **Quarkus-native** - Natural CDI integration via `TransformationContext`
5. ✅ **Pragmatic fit** - Oracle and PostgreSQL SQL are similar enough for direct translation
6. ✅ **Maintains boundaries** - Proper dependency separation through context injection

**See also:** `TRANSFORMATION_STATUS.md` for detailed comparison and migration status.

---

## Overview

The transformation module is a self-contained, reusable system for parsing Oracle SQL/PL/SQL and converting it to PostgreSQL equivalents. It uses a **direct visitor pattern** where ANTLR parse trees are directly transformed to PostgreSQL SQL strings.

**Core Pipeline:**
```
Oracle SQL/PL/SQL → ANTLR Parser → Direct Visitor → PostgreSQL SQL/PL/pgSQL
                         ↓              ↓                  ↓
                    PlSqlParser    PostgresCodeBuilder   String
```

**Key Design Principles:**
1. **Direct Transformation**: Visitor returns PostgreSQL SQL strings directly
2. **Static Helper Pattern**: Each ANTLR rule has a static helper class for scalability
3. **Dependency Boundaries**: `TransformationContext` injected for metadata access
4. **Incremental Development**: Start simple, add complexity progressively
5. **Test-Driven**: Comprehensive unit tests before integration
6. **Decoupled**: Independent from migration jobs initially

---

## Metadata Strategy

### Required Context for Transformation

The transformation module requires **minimal metadata** to disambiguate Oracle syntax patterns. Metadata is accessed through `TransformationContext`, maintaining proper dependency boundaries.

**Two Types of Metadata:**

1. **Synonym Resolution** (already implemented)
   - `TransformationContext.resolveSynonym(name)` via `TransformationIndices`
   - Follows Oracle rules: current schema → PUBLIC fallback
   - Essential because PostgreSQL has no synonyms

2. **Structural Indices** (implemented in `TransformationIndices`)
   - Table → Column → Type mappings
   - Type → Method mappings
   - Package → Function mappings
   - Built once at transformation session start from existing StateService data

### Dependency Architecture

**Proper Separation Maintained:**

```
ViewTransformationService (ApplicationScoped)
         ↓ @Inject
    AntlrParser (ApplicationScoped)
         ↓ creates
    PostgresCodeBuilder (NOT injected - created per transformation)
         ↓ uses (passed as parameter)
    TransformationContext (created per transformation)
         ↓ contains
    TransformationIndices (built from StateService metadata)
```

**Critical:** `PostgresCodeBuilder` is **NOT** a CDI bean. It receives `TransformationContext` as a method parameter, maintaining clean boundaries between transformation logic and application services.

**Why This Works:**
- ✅ No direct dependency on `StateService` in visitor
- ✅ `TransformationContext` acts as facade for metadata access
- ✅ Can inject `TypeConverter` if needed (via context)
- ✅ Testable - mock `TransformationContext` for unit tests
- ✅ Reusable - same builder can transform multiple queries with different contexts

### Critical Disambiguation

**Where metadata IS needed:**

```sql
-- Type method call (requires metadata to identify)
SELECT emp.address.get_street() FROM employees emp;
-- → (emp.address).get_street()

-- Package function call (pattern-based transformation)
SELECT emp_pkg.get_salary(emp.empno) FROM employees emp;
-- → emp_pkg__get_salary(emp.empno)

-- Type attribute access (pattern-based transformation)
SELECT emp.address.street FROM employees emp;
-- → (emp.address).street
```

Without metadata, `a.b.c()` is ambiguous. With `TransformationContext`, we can resolve:
1. Is `a` a table alias? → `context.resolveAlias(a)`
2. Does table have column `b` of custom type? → `context.getColumnType(table, b)`
3. Does that type have method `c`? → `context.hasTypeMethod(type, c)`
4. Otherwise, is `a.b` a package function? → `context.isPackageFunction(qualified)`

### Metadata Indexing Architecture

**Build indices once per transformation session:**

```java
public class MetadataIndexBuilder {
    /**
     * Build lookup indices from StateService metadata.
     * Called once at transformation session start.
     */
    public static TransformationIndices build(StateService state, List<String> schemas) {
        // Index table columns: "SCHEMA.TABLE" → "COLUMN" → type info
        Map<String, Map<String, ColumnTypeInfo>> tableColumns = indexTableColumns(state, schemas);

        // Index type methods: "SCHEMA.TYPE_NAME" → Set("METHOD1", "METHOD2", ...)
        Map<String, Set<String>> typeMethods = indexTypeMethods(state, schemas);

        // Index package functions: Set("SCHEMA.PACKAGE.FUNCTION", ...)
        Set<String> packageFunctions = indexPackageFunctions(state, schemas);

        return new TransformationIndices(tableColumns, typeMethods, packageFunctions);
    }
}
```

**Use during transformation via context:**

```java
// In VisitGeneralElement.java (static helper)
public static String v(PlSqlParser.General_elementContext ctx, PostgresCodeBuilder b) {
    if (ctx.PERIOD() != null && !ctx.PERIOD().isEmpty()) {
        // Dot notation detected: a.b.c()
        String[] parts = parseDotNotation(ctx);

        // Access metadata through builder's context (passed as parameter)
        TransformationContext context = b.getContext();

        // 1. Is 'a' a table alias?
        String table = context.resolveAlias(parts[0]);
        if (table != null) {
            // 2. Does table have column 'b' of custom type?
            TransformationIndices.ColumnTypeInfo typeInfo =
                context.getColumnType(table, parts[1]);
            if (typeInfo != null) {
                // 3. Does type have method 'c'?
                if (context.hasTypeMethod(typeInfo.getQualifiedType(), parts[2])) {
                    return String.format("(%s.%s).%s()", parts[0], parts[1], parts[2]);
                }
            }
        }

        // 4. Is a.b a package function?
        if (context.isPackageFunction(parts[0] + "." + parts[1])) {
            return String.format("%s__%s(%s)", parts[0], parts[1], transformArgs(parts[2]));
        }

        // Simple column reference
        return ctx.getText();
    }

    // Simple identifier
    return ctx.getText();
}
```

### Benefits of This Approach

✅ **Uses existing data**: All metadata already in StateService
✅ **No database dependency**: Transformation doesn't query Oracle
✅ **Fast**: O(1) lookups via hash maps
✅ **Testable**: Mock `TransformationContext` for unit tests
✅ **Clean architecture**: Proper dependency boundaries maintained
✅ **Offline capable**: Can transform without Oracle connection
✅ **Quarkus-compatible**: Service layer uses CDI, visitor layer stays pure

---

## Module Structure

```
transformer/                             # Oracle→PostgreSQL direct transformation
│
├── parser/                              # ANTLR Parsing Layer
│   ├── AntlrParser.java                 # CDI bean - thin wrapper around PlSqlParser
│   ├── ParseResult.java                 # Parse tree + errors wrapper
│   └── SqlType.java                     # Enum: VIEW_SELECT, FUNCTION_BODY, etc.
│
├── builder/                             # Direct AST to PostgreSQL Visitor
│   ├── PostgresCodeBuilder.java         # Main visitor (NOT CDI bean)
│   │                                    # Returns PostgreSQL SQL strings
│   │                                    # Context passed as parameter to visit methods
│   │
│   └── Visit*.java                      # Static helper classes (33+ files):
│       ├── VisitSelectStatement.java
│       ├── VisitQueryBlock.java
│       ├── VisitFromClause.java
│       ├── VisitSelectedList.java
│       ├── VisitSelectListElement.java
│       ├── VisitExpression.java
│       ├── VisitLogicalExpression.java
│       ├── VisitUnaryLogicalExpression.java
│       ├── VisitMultisetExpression.java
│       ├── VisitRelationalExpression.java
│       ├── VisitCompoundExpression.java
│       ├── VisitConcatenation.java
│       ├── VisitModelExpression.java
│       ├── VisitUnaryExpression.java
│       ├── VisitAtom.java
│       ├── VisitGeneralElement.java     # ⭐ Transformation decision point
│       ├── VisitStandardFunction.java   # Oracle function transformations
│       ├── VisitStringFunction.java
│       ├── VisitTableReference.java
│       └── ... (33+ total)
│
├── context/                             # Transformation Context (Reused)
│   ├── TransformationContext.java       # Facade for metadata access
│   ├── TransformationIndices.java       # Pre-built metadata lookup indices
│   ├── MetadataIndexBuilder.java        # Builds indices from StateService
│   ├── TransformationResult.java        # Success/error result wrapper
│   └── TransformationException.java     # Custom exception for transform errors
│
└── service/                             # High-Level Services (CDI Integration)
    └── ViewTransformationService.java   # CDI bean - orchestrates transformation
```

---

## Core Classes and Interfaces

### PostgresCodeBuilder (Main Visitor)

```java
/**
 * Direct ANTLR visitor that transforms Oracle SQL to PostgreSQL SQL.
 * Returns PostgreSQL SQL strings directly (no intermediate semantic tree).
 *
 * NOT a CDI bean - created per transformation.
 * Context is passed as parameter, maintaining dependency boundaries.
 */
public class PostgresCodeBuilder extends PlSqlParserBaseVisitor<String> {

    private TransformationContext context;  // Passed in, not injected

    public PostgresCodeBuilder() {
        // No dependencies - keeps visitor pure
    }

    /**
     * Set context before transformation.
     * Called by ViewTransformationService.
     */
    public void setContext(TransformationContext context) {
        this.context = context;
    }

    public TransformationContext getContext() {
        return context;
    }

    // ========== SELECT STATEMENT ==========

    @Override
    public String visitSelect_statement(PlSqlParser.Select_statementContext ctx) {
        return VisitSelectStatement.v(ctx, this);
    }

    @Override
    public String visitQuery_block(PlSqlParser.Query_blockContext ctx) {
        return VisitQueryBlock.v(ctx, this);
    }

    // ... 30+ more visitor methods delegating to static helpers
}
```

### Static Helper Pattern

```java
/**
 * Static helper for visiting SELECT statements.
 * Keeps PostgresCodeBuilder clean by extracting transformation logic.
 */
public class VisitSelectStatement {
    public static String v(PlSqlParser.Select_statementContext ctx, PostgresCodeBuilder b) {
        PlSqlParser.Select_only_statementContext selectOnlyCtx = ctx.select_only_statement();
        if (selectOnlyCtx == null) {
            throw new TransformationException("SELECT statement missing select_only_statement");
        }

        return b.visit(selectOnlyCtx);  // Recursive visitor call
    }
}
```

### TransformationContext (Dependency Boundary)

```java
/**
 * Provides global context for transformation process.
 * Maintains dependency boundaries - visitor accesses metadata through this facade.
 *
 * Contains:
 * - Pre-built metadata indices for fast lookups
 * - Current schema context
 * - Query-local state (table aliases)
 */
public class TransformationContext {
    private final String currentSchema;
    private final TransformationIndices indices;
    private final Map<String, String> tableAliases;  // Per-query state

    public TransformationContext(String currentSchema, TransformationIndices indices) {
        this.currentSchema = currentSchema;
        this.indices = indices;
        this.tableAliases = new HashMap<>();
    }

    // ========== Metadata Access ==========

    public String resolveSynonym(String name) {
        return indices.resolveSynonym(currentSchema, name);
    }

    public TransformationIndices.ColumnTypeInfo getColumnType(String qualifiedTable, String columnName) {
        return indices.getColumnType(qualifiedTable, columnName);
    }

    public boolean hasTypeMethod(String qualifiedType, String methodName) {
        return indices.hasTypeMethod(qualifiedType, methodName);
    }

    public boolean isPackageFunction(String qualifiedName) {
        return indices.isPackageFunction(qualifiedName);
    }

    // ========== Query-Local State ==========

    public void registerAlias(String alias, String tableName) {
        tableAliases.put(alias.toLowerCase(), tableName.toLowerCase());
    }

    public String resolveAlias(String alias) {
        return tableAliases.get(alias.toLowerCase());
    }

    public void clearAliases() {
        tableAliases.clear();
    }

    // ========== Type Conversion (Future) ==========

    public String convertType(String oracleType) {
        // TODO: Integrate with TypeConverter
        return oracleType;
    }
}
```

### AntlrParser (CDI Bean)

```java
/**
 * Thin wrapper around ANTLR PlSqlParser.
 * Handles parser instantiation and error collection.
 *
 * CDI-managed singleton.
 */
@ApplicationScoped
public class AntlrParser {
    private static final Logger log = LoggerFactory.getLogger(AntlrParser.class);

    public ParseResult parseSelectStatement(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new TransformationException("SQL cannot be null or empty");
        }

        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);

        // Collect errors
        List<String> errors = new ArrayList<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                String error = String.format("Line %d:%d - %s", line, charPositionInLine, msg);
                errors.add(error);
                log.warn("Parse error: {}", error);
            }
        });

        PlSqlParser.Select_statementContext tree = parser.select_statement();
        return new ParseResult(tree, errors, sql);
    }

    public ParseResult parseFunctionBody(String plsql) {
        throw new UnsupportedOperationException("Function body parsing not yet implemented");
    }

    public ParseResult parseProcedureBody(String plsql) {
        throw new UnsupportedOperationException("Procedure body parsing not yet implemented");
    }
}
```

### ViewTransformationService (CDI Bean)

```java
/**
 * High-level service for transforming Oracle view SQL to PostgreSQL.
 * This is the main entry point for migration jobs.
 *
 * CDI-managed singleton - orchestrates transformation with proper dependency injection.
 */
@ApplicationScoped
public class ViewTransformationService {

    private static final Logger log = LoggerFactory.getLogger(ViewTransformationService.class);

    @Inject
    AntlrParser parser;  // CDI injection

    public TransformationResult transformViewSql(String oracleSql, String schema,
                                                  TransformationIndices indices) {
        if (oracleSql == null || oracleSql.trim().isEmpty()) {
            return TransformationResult.failure(oracleSql, "Oracle SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oracleSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oracleSql, "Transformation indices cannot be null");
        }

        try {
            // STEP 1: Parse Oracle SQL using ANTLR
            ParseResult parseResult = parser.parseSelectStatement(oracleSql);

            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                return TransformationResult.failure(oracleSql, errorMsg);
            }

            // STEP 2: Create transformation context (dependency boundary)
            TransformationContext context = new TransformationContext(schema, indices);

            // STEP 3: Transform ANTLR parse tree directly to PostgreSQL SQL
            PostgresCodeBuilder builder = new PostgresCodeBuilder();
            builder.setContext(context);  // Pass context, not injected
            String postgresSql = builder.visit(parseResult.getTree());

            log.info("Successfully transformed view SQL for schema: {}", schema);
            return TransformationResult.success(oracleSql, postgresSql);

        } catch (TransformationException e) {
            log.error("Transformation failed: {}", e.getDetailedMessage(), e);
            return TransformationResult.failure(oracleSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation", e);
            return TransformationResult.failure(oracleSql, "Unexpected error: " + e.getMessage());
        }
    }
}
```

---

## Implementation Phases

### Phase 1: Foundation ✅ COMPLETE

**Status:** Complete - Full expression hierarchy and basic SELECT working

**Delivered:**
- ✅ `AntlrParser` - Wrapper around PlSqlParser with error collection
- ✅ `PostgresCodeBuilder` - Main visitor with static helper delegation
- ✅ `TransformationContext` - Metadata access facade with synonym resolution
- ✅ `TransformationIndices` - Pre-built metadata lookup indices (O(1) lookups)
- ✅ `MetadataIndexBuilder` - Builds indices from StateService
- ✅ `TransformationResult` - Success/error wrapper
- ✅ `TransformationException` - Custom exception with detailed messages
- ✅ **26 static visitor helpers** (Visit*.java) - scalable architecture
- ✅ Full expression hierarchy traversal (11 levels from expression → general_element)
- ✅ Simple SELECT transformation working
- ✅ `ViewTransformationService` integrated (@ApplicationScoped CDI bean)
- ✅ Tests: 72/72 passing across 9 test classes

**Current Capabilities:**
```sql
-- Basic SELECT:
SELECT nr, text FROM example         → SELECT nr , text FROM example ✅
SELECT nr, text FROM example e       → SELECT nr , text FROM example e ✅
SELECT * FROM example                → SELECT * FROM example ✅
SELECT e.* FROM example e            → SELECT e . * FROM example e ✅
```

---

### Phase 2: Complete SELECT Support ✅ ~80% COMPLETE

**Status:** Nearly complete - WHERE clause, literals, operators working. ORDER BY/GROUP BY/JOINs remaining.

**2.1 Literals and Operators** ✅ COMPLETE
- ✅ String literals: `'text'`
- ✅ Number literals: `123`, `45.67`
- ✅ Boolean literals: `TRUE`, `FALSE`
- ✅ NULL literal
- ✅ Comparison operators: `=`, `<`, `>`, `<=`, `>=`, `!=`, `<>`
- ⏳ Arithmetic operators: `+`, `-`, `*`, `/` (infrastructure ready, testing needed)
- ⏳ String concatenation: `||` operator (not yet implemented)

**2.2 WHERE Clause** ✅ COMPLETE
- ✅ Logical operators: `AND`, `OR`, `NOT`
- ✅ IS NULL / IS NOT NULL
- ✅ IN (value list): `deptno IN (10, 20, 30)`, `NOT IN`
- ✅ BETWEEN: `sal BETWEEN 1000 AND 2000`, `NOT BETWEEN`
- ✅ LIKE: `ename LIKE 'S%'`, `NOT LIKE`, `ESCAPE` clause
- ✅ Parenthesized expressions for precedence
- ✅ Complex nested conditions
- ✅ Tests: `ExpressionBuildingBlocksTest` (24 tests passing)

**2.3 ORDER BY and GROUP BY** ⏳ NOT YET IMPLEMENTED
- ⏳ ORDER BY clause
- ⏳ ASC / DESC (default ASC)
- ⏳ NULLS FIRST / NULLS LAST (compatible!)
- ⏳ GROUP BY clause
- ⏳ HAVING clause
- ⏳ Aggregate functions: COUNT(*), SUM(), AVG(), MAX(), MIN()

**2.4 JOINs** ⏳ NOT YET IMPLEMENTED
- Currently only single table FROM clause supported
- ⏳ Multiple tables (comma-separated)
- ⏳ ANSI JOIN syntax: INNER JOIN, LEFT JOIN, RIGHT JOIN, FULL OUTER JOIN
- ⏳ ON condition parsing
- ⏳ **Critical:** Oracle (+) syntax conversion to ANSI JOIN

**2.5 Subqueries** ⏳ NOT YET IMPLEMENTED
- ⏳ Subqueries in SELECT list
- ⏳ Subqueries in WHERE clause
- ⏳ EXISTS / NOT EXISTS
- ⏳ IN (SELECT ...)

**Advanced Features Implemented (Beyond Original Phase 2 Plan):** ✅
- ✅ **Type Member Method Transformation** (critical for Oracle UDTs)
  - `emp.address.get_street()` → `address_type__get_street(emp.address)`
  - Chained method calls: `emp.data.method1().method2()` (nested functions)
  - Tests: `TypeMemberMethodTransformationTest` (8 tests passing)
- ✅ **Package Function Transformation**
  - `pkg.func(args)` → `pkg__func(args)`
  - Tests: `PackageFunctionTransformationTest` (10 tests passing)
- ✅ **Table Alias Support**
  - Tests: `TableAliasTransformationTest` (9 tests passing)
- ✅ **SELECT * Support**
  - Both `SELECT *` and qualified `SELECT e.*`
  - Tests: `SelectStarTransformationTest` (10 tests passing)

**Test Examples:**
```sql
-- Complex WHERE clause (working now!)
SELECT empno, ename FROM employees
WHERE (deptno = 10 OR deptno = 20)
  AND sal BETWEEN 1000 AND 5000
  AND commission IS NOT NULL
  AND ename LIKE 'S%'

-- Type member method (working now!)
SELECT emp.address.get_street() FROM employees emp
→ SELECT address_type__get_street(emp . address) FROM employees emp
```

---

        String qualifiedSeq = context.getCurrentSchema() + "." + obj;
        return String.format("%s('%s')", funcName, qualifiedSeq);
    }
}
```

**3.4 Metadata-Driven Disambiguation** (Week 5)

Extend `VisitGeneralElement` for complex dot notation `a.b.c()`:

```java
public static String v(PlSqlParser.General_elementContext ctx, PostgresCodeBuilder b) {
    if (ctx.PERIOD() != null && !ctx.PERIOD().isEmpty()) {
        // Dot notation detected
        String[] parts = parseDotNotation(ctx);
        TransformationContext context = b.getContext();

        // Disambiguation logic using metadata
        if (parts.length == 3 && hasFunction Arguments(ctx)) {
            // Could be type method or package function

            // 1. Is 'a' a table alias?
            String table = context.resolveAlias(parts[0]);
            if (table != null) {
                // 2. Does table have column 'b' of custom type?
                TransformationIndices.ColumnTypeInfo typeInfo =
                    context.getColumnType(table, parts[1]);
                if (typeInfo != null) {
                    // 3. Does type have method 'c'?
                    if (context.hasTypeMethod(typeInfo.getQualifiedType(), parts[2])) {
                        // Type method: (emp.address).get_street()
                        return String.format("(%s.%s).%s(%s)",
                            parts[0], parts[1], parts[2], transformArgs(ctx, b));
                    }
                }
            }

            // 4. Is a.b a package function?
            if (context.isPackageFunction(parts[0] + "." + parts[1])) {
                // Package function: emp_pkg__get_salary()
                return String.format("%s__%s(%s)",
                    parts[0], parts[1], transformArgs(ctx, b));
            }
        }

        // Simple qualified name
        return ctx.getText();
    }

    // Simple identifier
    return ctx.getText();
}
```

**Deliverable:** Transform Oracle-specific SQL constructs

**Test Examples:**
```sql
-- Oracle
SELECT NVL(commission, 0), DECODE(deptno, 10, 'A', 20, 'B', 'C')
FROM emp;

-- PostgreSQL
SELECT COALESCE(commission, 0), CASE deptno WHEN 10 THEN 'A' WHEN 20 THEN 'B' ELSE 'C' END
FROM emp;
```

```sql
-- Oracle
SELECT emp_seq.NEXTVAL FROM DUAL;

-- PostgreSQL
SELECT nextval('hr.emp_seq');
```

---

### Phase 4: Integration with Migration Jobs (Week 6)

**Goal:** Connect transformation to actual view migration

**4.1 Add View SQL Extraction**

Currently `OracleViewExtractionJob` only extracts column metadata from `ALL_TAB_COLUMNS`.
Need to extract actual SQL from `ALL_VIEWS.TEXT`:

```java
@Dependent
public class OracleViewExtractionJob extends AbstractDatabaseExtractionJob<ViewMetadata> {
    @Override
    protected List<ViewMetadata> performExtraction(...) {
        String query = """
            SELECT owner, view_name, text, text_length
            FROM all_views
            WHERE owner IN (...)
            ORDER BY owner, view_name
            """;

        // Extract SQL definition from TEXT column
        // Set viewMetadata.setSqlDefinition(text)
        // Store in StateService
    }
}
```

**4.2 Create PostgresViewImplementationJob**

Replace stubs with transformed SQL:

```java
@Dependent
public class PostgresViewImplementationJob extends AbstractDatabaseExtractionJob<ViewImplementationResult> {

    @Inject
    ViewTransformationService transformationService;

    @Inject
    StateService stateService;

    @Override
    protected List<ViewImplementationResult> performExtraction(...) {
        // Build indices once for session
        TransformationIndices indices = MetadataIndexBuilder.build(
            stateService,
            schemas
        );

        ViewImplementationResult result = new ViewImplementationResult();

        for (ViewMetadata view : stateService.getOracleViewMetadata()) {
            String oracleSql = view.getSqlDefinition();

            if (oracleSql == null || oracleSql.trim().isEmpty()) {
                log.warn("View {} has no SQL definition", view.getViewName());
                continue;
            }

            TransformationResult transformResult = transformationService.transformViewSql(
                oracleSql,
                view.getSchema(),
                indices
            );

            if (transformResult.isSuccess()) {
                String createViewSql = String.format(
                    "CREATE OR REPLACE VIEW %s.%s AS %s",
                    view.getSchema(),
                    view.getViewName(),
                    transformResult.getPostgresSql()
                );

                try {
                    executePostgresSql(createViewSql);
                    result.incrementImplemented();
                    log.info("Replaced stub for view {}.{}", view.getSchema(), view.getViewName());
                } catch (SQLException e) {
                    log.error("Failed to create view {}: {}", view.getViewName(), e.getMessage());
                    result.addError(view.getViewName(), e.getMessage());
                }
            } else {
                log.warn("Failed to transform view {}: {}",
                    view.getViewName(), transformResult.getErrorMessage());
                result.addError(view.getViewName(), transformResult.getErrorMessage());
            }
        }

        return List.of(result);
    }
}
```

**Deliverable:** Views automatically transformed and migrated

---

### Phase 5: PL/SQL Functions/Procedures (Future - Week 7+)

**Goal:** Extend to PL/SQL function/procedure bodies

Reuse `PostgresCodeBuilder` with different entry points:

```java
public class PostgresCodeBuilder extends PlSqlParserBaseVisitor<String> {
    // Already have:
    public String visitSelect_statement(PlSqlParser.Select_statementContext ctx);

    // Add for PL/SQL:
    public String visitFunction_body(PlSqlParser.Function_bodyContext ctx) {
        return VisitFunctionBody.v(ctx, this);
    }

    public String visitProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
        return VisitProcedureBody.v(ctx, this);
    }
}
```

**New visitor helpers needed:**
- `VisitFunctionBody` / `VisitProcedureBody`
- `VisitDeclareSection` - variable declarations
- `VisitIfStatement` - IF-THEN-ELSIF-ELSE
- `VisitLoopStatement` - FOR/WHILE loops
- `VisitCursorDeclaration` - cursor definitions
- `VisitExceptionHandler` - exception blocks

---

## Oracle Function Mapping Reference

See complete Oracle function mapping reference in original sections (lines 668-750 in old doc).

Key transformations:
- **NVL/NVL2** → COALESCE / CASE
- **DECODE** → CASE WHEN
- **SYSDATE** → CURRENT_TIMESTAMP
- **ROWNUM** → row_number() OVER ()
- **String functions**: SUBSTR → SUBSTRING, INSTR → POSITION
- **Date functions**: TO_DATE → TO_TIMESTAMP (format conversion needed)
- **Numeric/Aggregate/Analytic**: Mostly compatible

---

## Testing Strategy

### Current Tests ✅

```
SimpleSelectTransformationTest.java (4/4 passing)
  ✅ testSimpleSelectTwoColumns
  ✅ testSimpleSelectWithTableAlias
  ✅ testSimpleSelectSingleColumn
  ✅ testParseError
```

### Future Test Organization

```
src/test/java/.../transformer/
├── builder/
│   ├── PostgresCodeBuilderTest.java       # Main visitor tests
│   ├── VisitWhereClauseTest.java          # WHERE clause transformations
│   ├── VisitOracleFunctionTest.java       # NVL, DECODE, etc.
│   └── VisitJoinTest.java                 # JOIN transformations
├── integration/
│   ├── ComplexSelectTransformationTest.java
│   ├── OracleFunctionIntegrationTest.java
│   └── ViewMigrationIntegrationTest.java
└── service/
    └── ViewTransformationServiceTest.java
```

### Test Coverage Goals

- **Visitor helpers**: 90%+ code coverage
- **PostgresCodeBuilder**: 85%+ coverage
- **Service**: 90%+ coverage
- **Overall Module**: 90%+ coverage

---

## Success Metrics

### Phase 1 ✅ COMPLETE
- ✅ 4/4 tests passing
- ✅ Simple SELECT transformation working
- ✅ Parser functional
- ✅ Visitor functional
- ✅ Service integrated

### Phase 2 Goals (Complete SELECT)
- ✅ WHERE, ORDER BY, GROUP BY transformations
- ✅ JOIN transformations (including Oracle (+) syntax)
- ✅ Literals and operators working
- ✅ 20+ additional tests passing

### Phase 3 Goals (Oracle Functions)
- ✅ 10+ Oracle functions transformed
- ✅ DUAL table handling
- ✅ Sequence syntax conversion
- ✅ Metadata-driven disambiguation working
- ✅ 15+ additional tests passing

### Phase 4 Goals (Integration)
- ✅ View SQL extraction from Oracle
- ✅ PostgresViewImplementationJob functional
- ✅ 90%+ of simple views transform successfully
- ✅ Clear error messages for unsupported features

---

## Conclusion

The direct AST transformation approach provides a **pragmatic, maintainable solution** for Oracle→PostgreSQL SQL transformation:

✅ **Working now**: Tests pass, simple SELECT transforms successfully
✅ **Proper boundaries**: `TransformationContext` maintains dependency separation
✅ **Scalable**: Static helper pattern handles 400+ ANTLR rules without bloat
✅ **Testable**: Mock `TransformationContext` for unit tests
✅ **Quarkus-native**: Service layer uses CDI, visitor layer stays pure
✅ **Incremental**: Add features progressively without architectural changes

The phased implementation ensures we can **deliver value incrementally** while building toward comprehensive transformation coverage.

---

**See also:**
- `TRANSFORMATION_STATUS.md` - Detailed implementation status and comparison
- `SimpleSelectTransformationTest.java` - Working test examples
- `PostgresCodeBuilder.java` - Main visitor implementation
- `VisitGeneralElement.java` - Transformation decision point example
