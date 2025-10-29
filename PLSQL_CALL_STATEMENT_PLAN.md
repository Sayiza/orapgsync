# PL/SQL Call Statement Implementation Plan

**Status:** 🔴 **NOT STARTED** - Critical gap identified
**Date Created:** 2025-10-29
**Priority:** HIGH (blocks many real-world functions)

---

## Overview

Oracle PL/SQL allows standalone procedure/function calls as statements. PostgreSQL distinguishes between:
1. **Procedure calls**: PERFORM procedure_name(args)
2. **Function calls for side effects**: PERFORM function_name(args)
3. **Function calls with return value**: Used in expressions (already works)

**Current State:** No `call_statement` visitor exists. Any function/procedure call as a statement causes null transformation.

---

## Oracle Grammar

```antlr
call_statement
    : CALL? routine_name function_argument? ('.' routine_name function_argument?)* (
        INTO bind_variable
    )?
    ;

routine_name
    : identifier ('.' id_expression)* ('@' link_name)?
    ;
```

**Patterns:**
- `procedure_name(args);` - Simple call
- `package.procedure(args);` - Package member
- `schema.package.procedure(args);` - Fully qualified
- `function_name(args) INTO v_result;` - Function call with INTO

---

## PostgreSQL Requirements

### 1. **Procedure Calls → PERFORM**
```plsql
-- Oracle
BEGIN
  log_message('Test');
END;

-- PostgreSQL
BEGIN
  PERFORM hr.log_message('Test');
END;
```

### 2. **Function Calls (no return used) → PERFORM**
```plsql
-- Oracle
BEGIN
  calculate_bonus(salary);  -- Side effects only
END;

-- PostgreSQL
BEGIN
  PERFORM hr.calculate_bonus(salary);
END;
```

### 3. **Function Calls with INTO → SELECT INTO**
```plsql
-- Oracle
BEGIN
  calculate_bonus(salary) INTO v_result;
END;

-- PostgreSQL
BEGIN
  SELECT hr.calculate_bonus(salary) INTO v_result;
END;
```

### 4. **Package Member Calls → Flattened**
```plsql
-- Oracle
BEGIN
  utilities.log('Test');
END;

-- PostgreSQL
BEGIN
  PERFORM hr.utilities__log('Test');
END;
```

---

## Implementation Strategy

### Step 1: Create VisitCall_statement.java

**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitCall_statement.java`

**Responsibilities:**
1. Parse `routine_name` (handle dots for packages/schemas)
2. Apply synonym resolution (like VisitGeneralElement does for SQL)
3. Flatten package calls: `package.function` → `package__function`
4. Schema-qualify unqualified calls: `function` → `schema.function`
5. Distinguish INTO clause vs standalone call
6. Generate PERFORM or SELECT INTO accordingly

**Pseudocode:**
```java
public static String v(PlSqlParser.Call_statementContext ctx, PostgresCodeBuilder b) {
    // 1. Extract routine name (schema.package.function or package.function or function)
    List<String> routineParts = extractRoutineParts(ctx.routine_name());

    // 2. Apply synonym resolution via TransformationContext
    routineParts = resolveSynonyms(routineParts, b.getContext());

    // 3. Flatten package member: [schema, pkg, func] → schema.pkg__func
    String qualifiedName = flattenPackageCall(routineParts, b.getContext());

    // 4. Extract and transform arguments
    String arguments = transformArguments(ctx.function_argument(), b);

    // 5. Handle INTO clause
    if (ctx.INTO() != null) {
        // SELECT function(...) INTO variable
        String variable = ctx.bind_variable().getText();
        return "SELECT " + qualifiedName + arguments + " INTO " + variable + ";";
    } else {
        // PERFORM procedure/function(...)
        return "PERFORM " + qualifiedName + arguments + ";";
    }
}
```

### Step 2: Add Synonym Resolution Helper

**Reuse logic from VisitGeneralElement:**
- Lines 322-326: Package function synonym resolution
- Lines 227-234: Sequence synonym resolution

**Pattern:**
```java
private static List<String> resolveSynonyms(List<String> parts, TransformationContext ctx) {
    if (ctx == null || parts.size() != 1) {
        return parts;  // Already qualified or no context
    }

    String name = parts.get(0);
    String resolved = ctx.resolveSynonym(name);

    if (resolved != null) {
        // Synonym resolved to "schema.object"
        return Arrays.asList(resolved.split("\\."));
    }

    return parts;
}
```

### Step 3: Register in PostgresCodeBuilder

```java
@Override
public String visitCall_statement(PlSqlParser.Call_statementContext ctx) {
    return VisitCall_statement.v(ctx, this);
}
```

### Step 4: Comprehensive Tests

**Test Class:** `PostgresPlSqlCallStatementValidationTest.java`

**Test Cases:**
1. Simple procedure call → PERFORM
2. Simple function call → PERFORM
3. Function call with INTO → SELECT INTO
4. Package procedure call → PERFORM with flattening
5. Package function call with INTO → SELECT INTO with flattening
6. Schema-qualified call → Preserve schema prefix
7. Synonym resolution for procedure name
8. Synonym resolution for package name
9. Nested package calls (if supported by grammar)
10. Chained method calls (if supported: obj.method1().method2())

---

## Key Challenges

### 1. **How to Distinguish Procedure vs Function?**

**Problem:** Oracle allows both procedures and functions to be called as statements. PostgreSQL requires PERFORM for both when no return value is used.

**Solution:** Always use PERFORM for standalone calls. It works for both:
```plsql
PERFORM procedure_name(args);  -- ✅ Works
PERFORM function_name(args);   -- ✅ Works (return value discarded)
```

### 2. **How to Handle Type Member Methods?**

**Problem:** Oracle: `emp.address.get_street();` as a statement

**Solution:** Apply same transformation as VisitGeneralElement (lines 536-562):
- Flatten to `address_type__get_street(emp.address)`
- Wrap in PERFORM if standalone

**Grammar Check:** Does `call_statement` support this? Need to verify.

### 3. **Schema Qualification Strategy**

**Consistency:** Should match VisitGeneralElement approach (lines 444-451):
- Unqualified calls → qualify with current schema
- Rationale: PostgreSQL search_path may not include migration schema

**Apply same logic:**
```java
if (context != null && !qualifiedName.contains(".")) {
    qualifiedName = context.getCurrentSchema().toLowerCase() + "." + qualifiedName;
}
```

---

## Integration Points

### 1. **Reuse Existing Logic**
- VisitGeneralElement.transformPackageFunctionWithMetadata() (lines 311-368)
- VisitGeneralElement.resolveSynonym() pattern (lines 322-326, 227-234)
- VisitGeneralElement.getFunctionArguments() (lines 482-509)

### 2. **Metadata Required**
- Synonym resolution (already in TransformationContext)
- Package function registry (already in TransformationIndices)
- Current schema (already in TransformationContext)

### 3. **Testing Strategy**
- Follow same pattern as other PL/SQL statement tests
- Use Testcontainers with real PostgreSQL execution
- Verify both transformation correctness AND execution success

---

## Expected Impact

**Coverage Improvement:** ~75-80% → ~85-90% of real-world functions

**Why High Impact:**
- Procedure/function calls are extremely common in PL/SQL
- Package member calls are ubiquitous in Oracle enterprise codebases
- Logging utilities (DBMS_OUTPUT.PUT_LINE) won't work without this
- Audit/logging procedures called from every function

---

## Implementation Estimate

**Complexity:** MEDIUM
- Visitor implementation: 2-3 hours
- Tests: 2-3 hours
- Synonym resolution integration: 1 hour
- Package flattening: Already solved in VisitGeneralElement
- **Total:** ~6 hours

**Dependencies:**
- None (all required infrastructure exists)

**Risks:**
- Grammar may have edge cases not covered (chained calls, etc.)
- Type member method calls might need special handling
- INTO clause with multiple variables (if supported)

---

## Next Steps

1. ✅ Identify the gap (DONE - this document)
2. ⬜ Analyze grammar for edge cases (CALL keyword optional?)
3. ⬜ Implement VisitCall_statement.java
4. ⬜ Register in PostgresCodeBuilder
5. ⬜ Create comprehensive tests
6. ⬜ Verify synonym resolution works
7. ⬜ Update STEP_25 documentation

---

## References

- **Grammar:** `PlSqlParser.g4` lines 5685-5691 (call_statement)
- **Similar Implementation:** `VisitGeneralElement.java` lines 271-390 (package function calls in SQL)
- **Synonym Resolution:** `VisitGeneralElement.java` lines 214-249 (sequence calls with synonyms)
- **Test File Created:** `PostgresPlSqlCallStatementTest.java` (demonstrates failure)
