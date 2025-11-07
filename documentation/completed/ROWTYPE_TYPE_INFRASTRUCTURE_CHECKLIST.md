# %ROWTYPE and %TYPE Infrastructure Checklist

## Quick Reference: What's Ready vs What Needs Work

### Legend
- ✅ = Complete and tested
- ⏳ = Ready but needs connection logic
- ❌ = Not implemented

---

## Component Status Matrix

| Component | Status | Location | Notes |
|-----------|--------|----------|-------|
| **Type Categories** |
| ROWTYPE category | ✅ | TypeCategory.java:108 | Fully documented |
| TYPE_REFERENCE category | ✅ | TypeCategory.java:121 | Fully documented |
| **Data Models** |
| InlineTypeDefinition.ROWTYPE support | ✅ | InlineTypeDefinition.java | Supports fields list |
| InlineTypeDefinition.TYPE_REFERENCE support | ✅ | InlineTypeDefinition.java | Supports elementType |
| FieldDefinition (column metadata) | ✅ | FieldDefinition.java | Complete |
| **ANTLR Grammar** |
| PERCENT_ROWTYPE token | ✅ | PlSqlLexer.g4:1341 | Recognized |
| PERCENT_TYPE token | ✅ | PlSqlLexer.g4:1342 | Recognized |
| type_spec with %ROWTYPE | ✅ | PlSqlParser.g4:7240 | Parser rule complete |
| type_spec with %TYPE | ✅ | PlSqlParser.g4:7240 | Parser rule complete |
| **Metadata Access** |
| TransformationIndices.tableColumns | ✅ | TransformationIndices.java:34 | All tables available |
| TransformationIndices.getColumnType() | ✅ | TransformationIndices.java:71 | O(1) lookup |
| ColumnTypeInfo class | ✅ | TransformationIndices.java:209 | Full type info |
| **Type Resolution** |
| Level 1: Block-level types | ✅ | TransformationContext.java:505 | Working |
| Level 2: Package-level types | ✅ | TransformationContext.java:510 | Working |
| Level 3: Schema-level types | ⏳ | TransformationContext.java:518 | Needs %ROWTYPE/%TYPE |
| resolveInlineType() method | ✅ | TransformationContext.java:499 | Existing cascade |
| **Variable Scope** |
| pushVariableScope() | ✅ | TransformationContext.java:689 | For %TYPE variable refs |
| lookupVariable() | ✅ | TransformationContext.java:774 | For %TYPE chains |
| isLocalVariable() | ✅ | TransformationContext.java:818 | Already implemented |
| **Transformation** |
| RECORD field assignment | ✅ | VisitAssignment_statement.java | jsonb_set pattern |
| RECORD field access (RHS) | ✅ | VisitGeneralElement.java | Type casting |
| Collection element access | ✅ | VisitGeneralElement.java | 1-based → 0-based |
| Collection element assignment | ✅ | VisitAssignment_statement.java | jsonb_set pattern |
| **Missing: %ROWTYPE/%TYPE** |
| Detect %ROWTYPE in type_spec | ❌ | VisitVariable_declaration.java | New |
| Detect %TYPE in type_spec | ❌ | VisitVariable_declaration.java | New |
| resolveRowtypeReference() | ❌ | VisitVariable_declaration.java | ~80 lines |
| resolveTypeReference() | ❌ | VisitVariable_declaration.java | ~100 lines |
| resolveColumnTypeReference() | ❌ | VisitVariable_declaration.java | ~50 lines |

---

## Dependency Tree

```
Variable Declaration
    └─→ VisitVariable_declaration.v() [ENTRY POINT]
        ├─→ ctx.type_spec().getText()  [AST]
        ├─→ [NEW] detectRowtype()       ✅ READY TO IMPLEMENT
        ├─→ [NEW] detectTypeRef()       ✅ READY TO IMPLEMENT
        ├─→ [NEW] resolveRowtype()      ✅ READY TO IMPLEMENT
        │   ├─→ context.resolveSynonym()        ✅ WORKING
        │   ├─→ indices.getColumnType()         ✅ WORKING
        │   └─→ TypeConverter.toPostgre()       ✅ WORKING
        ├─→ [NEW] resolveTypeRef()      ✅ READY TO IMPLEMENT
        │   ├─→ [NEW] resolveColumnTypeRef()    ✅ READY TO IMPLEMENT
        │   │   ├─→ context.resolveSynonym()    ✅ WORKING
        │   │   └─→ indices.getColumnType()     ✅ WORKING
        │   └─→ context.lookupVariable()        ✅ WORKING
        ├─→ context.resolveInlineType()         ✅ WORKING
        ├─→ TypeConverter.toPostgre()           ✅ WORKING
        └─→ context.registerVariable()          ✅ WORKING
```

---

## What's Already Working (Reference Implementation)

### RECORD Type Declaration
```java
// Oracle: TYPE salary_range_t IS RECORD (min_sal NUMBER, max_sal NUMBER);
// Result: Registered in context, variable uses jsonb

// PostgreSQL: v_range jsonb := '{}'::jsonb;
```

### RECORD Field Assignment
```java
// Oracle: v_range.min_sal := 1000;
// PostgreSQL: v_range := jsonb_set(v_range, '{min_sal}', to_jsonb(1000))
```

### RECORD Field Access (RHS)
```java
// Oracle: x := v_range.max_sal;
// PostgreSQL: x := (v_range->>'max_sal')::numeric
```

### TABLE OF Constructor
```java
// Oracle: v_nums num_list_t(10, 20, 30);
// PostgreSQL: v_nums := '[10, 20, 30]'::jsonb
```

### Collection Element Access
```java
// Oracle: x := v_nums(1);  [1-based]
// PostgreSQL: x := (v_nums->0)  [0-based, automatic conversion]
```

---

## What Needs Implementation (Parallel Pattern)

### %ROWTYPE Declaration
```java
// Oracle: v_emp employees%ROWTYPE;
// Need: Extract columns from TransformationIndices
// PostgreSQL: v_emp jsonb := '{}'::jsonb;
```

### %ROWTYPE Field Assignment
```java
// Oracle: v_emp.empno := 100;
// Use existing pattern: VisitAssignment_statement.java
// PostgreSQL: v_emp := jsonb_set(v_emp, '{empno}', to_jsonb(100))
```

### %ROWTYPE Field Access (RHS)
```java
// Oracle: x := v_emp.salary;
// Use existing pattern: VisitGeneralElement.java
// PostgreSQL: x := (v_emp->>'salary')::numeric
```

### %TYPE Declaration
```java
// Oracle: v_salary employees.salary%TYPE;
// Need: Extract type from TransformationIndices
// PostgreSQL: v_salary numeric;  [direct type]
```

### %TYPE Variable Reference
```java
// Oracle: v_copy v_salary%TYPE;
// Need: Lookup v_salary in variable scope
// PostgreSQL: v_copy numeric;  [same as v_salary]
```

---

## Implementation Flow Diagram

```
┌─ Variable Declaration ───────────────────────────────────────────┐
│                                                                   │
│  Input: Oracle declaration                                       │
│  Example: v_emp employees%ROWTYPE;                              │
│                                                                   │
│  Step 1: Extract type_spec text ──────────────────────────────  │
│          Result: "employees%ROWTYPE"                             │
│                                                                   │
│  Step 2: Detect type category ─────────────────────────────────  │
│          ├─ Contains "%ROWTYPE"? ──YES──→ Call resolveRowtype()   │
│          ├─ Contains "%TYPE"? ────YES──→ Call resolveTypeRef()    │
│          └─ Regular type? ────────YES──→ Use TypeConverter       │
│                                                                   │
│  Step 3: For %ROWTYPE ─────────────────────────────────────────  │
│          ├─ Extract table name: "employees"                      │
│          ├─ Resolve synonyms: context.resolveSynonym()          │
│          ├─ Build qualified: "hr.employees"                      │
│          ├─ Get columns: indices.getTableColumns()              │
│          ├─ Convert types: TypeConverter.toPostgre()            │
│          └─ Create InlineTypeDefinition(ROWTYPE, fields)        │
│                                                                   │
│  Step 4: For %TYPE ────────────────────────────────────────────  │
│          ├─ Extract reference: "employees.salary"                │
│          ├─ If "table.column": resolveColumnTypeRef()           │
│          │  ├─ Get column type from indices                      │
│          │  └─ Create InlineTypeDefinition(TYPE_REFERENCE)       │
│          └─ If "v_variable": lookupVariable() then resolve       │
│                                                                   │
│  Step 5: Transform variable declaration ────────────────────────  │
│          ├─ If inline type: jsonb + initializer                 │
│          └─ If regular type: PostgreSQL type                    │
│                                                                   │
│  Output: PostgreSQL declaration                                  │
│  Example: v_emp jsonb := '{}'::jsonb;                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Code Locations Reference

### Files to Read (for context)
- `TypeCategory.java` (108-122) - Enum definitions
- `InlineTypeDefinition.java` (185-251) - Initializer logic
- `VisitVariable_declaration.java` (64-95) - Current implementation
- `VisitAssignment_statement.java` - Field assignment pattern
- `VisitGeneralElement.java` - Field access pattern
- `TransformationIndices.java` (34-248) - Metadata access

### Files to Modify
- `VisitVariable_declaration.java` - Add %ROWTYPE/%TYPE detection
  - Line 78: After `ctx.type_spec().getText()`
  - Add 200-300 lines of new methods

### Files to Create (Optional - Tests)
- `PostgresRowtypeTransformationTest.java` - Unit tests
- `PostgresTypeReferenceTransformationTest.java` - Unit tests
- `PostgresRowtypeValidationTest.java` - Integration tests

---

## Test Strategy

### Unit Tests (No database)
1. Basic %ROWTYPE declaration
2. %ROWTYPE with different column types
3. %ROWTYPE qualified table (hr.employees)
4. Basic %TYPE (column reference)
5. %TYPE with different types
6. %TYPE variable reference
7. %TYPE chained reference
8. Error cases (circular, non-existent)

### Integration Tests (With PostgreSQL)
1. %ROWTYPE field assignment and access
2. %TYPE variable usage
3. Complex expressions with %ROWTYPE
4. %ROWTYPE in function parameters
5. End-to-end transformation validation

---

## Success Criteria

- ✅ All existing tests still pass (zero regressions)
- ✅ %ROWTYPE transforms to jsonb with correct field definitions
- ✅ %TYPE resolves to correct underlying type
- ✅ %ROWTYPE field access/assignment works (using existing patterns)
- ✅ Unit tests for all %ROWTYPE/%TYPE patterns
- ✅ Integration tests pass with real PostgreSQL

---

## Next Steps (Quick Start)

1. Read `ROWTYPE_TYPE_EXPLORATION_SUMMARY.md` (this folder)
2. Review `VisitVariable_declaration.java` lines 64-95
3. Implement `resolveRowtypeReference()` method (~80 lines)
4. Implement `resolveTypeReference()` method (~100 lines)
5. Add detection logic around line 78
6. Run existing tests (should all pass)
7. Create unit tests
8. Create integration tests
9. Test with real %ROWTYPE/%TYPE code

