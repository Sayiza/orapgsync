# Package Variable Complex Types Implementation Plan

**Status:** ✅ COMPLETE (All 6 Phases)
**Created:** 2026-04-17
**Depends On:** Package Variable Implementation (COMPLETE), Inline Type System (COMPLETE)
**Estimated Effort:** Medium (extends existing infrastructure)

---

## 1. Overview

### Problem Statement

Package variables currently support only scalar/primitive types (NUMBER, VARCHAR2, INTEGER, DATE, BOOLEAN). Complex types (RECORD, TABLE OF, VARRAY, INDEX BY) are extracted from package specifications but not properly handled during getter/setter generation or variable reference transformation.

### Goal

Extend package variable support to handle complex types using the established jsonb strategy already implemented for normal (function-local) variables.

### Example - Current Failure

```sql
-- Oracle package
CREATE PACKAGE emp_pkg AS
  TYPE salary_range_t IS RECORD (min_sal NUMBER, max_sal NUMBER);
  g_range salary_range_t;  -- Complex type package variable

  FUNCTION get_min_salary RETURN NUMBER;
END emp_pkg;

CREATE PACKAGE BODY emp_pkg AS
  FUNCTION get_min_salary RETURN NUMBER AS
  BEGIN
    RETURN g_range.min_sal;  -- Field access on package variable
  END;
END emp_pkg;
```

**Current output (BROKEN):**
```sql
-- PackageHelperGenerator doesn't recognize salary_range_t
CREATE FUNCTION emp_pkg__get_g_range() RETURNS text AS ...  -- Wrong type!
```

**Required output:**
```sql
CREATE FUNCTION emp_pkg__get_g_range() RETURNS jsonb AS $$
BEGIN
  RETURN COALESCE(
    current_setting('hr.emp_pkg.g_range', true)::jsonb,
    '{}'::jsonb
  );
END;
$$ LANGUAGE plpgsql;

-- Field access transformation
RETURN (emp_pkg__get_g_range()->>'min_sal')::numeric;
```

---

## 2. Current State Analysis

### What's Already Working

| Component | Status | Location |
|-----------|--------|----------|
| Package type extraction | ✅ Works | `PackageContextExtractor.extractTypeDeclaration()` |
| Type storage in context | ✅ Works | `PackageContext.addType()` / `getTypes()` |
| InlineTypeDefinition model | ✅ Works | `transformer/inline/InlineTypeDefinition.java` |
| Normal variable jsonb handling | ✅ Works | `VisitVariable_declaration.java` |
| Normal field access (RHS) | ✅ Works | `VisitGeneralElement.handleInlineTypeFieldAccess()` |
| Normal field assignment (LHS) | ✅ Works | `VisitAssignment_statement.tryTransformInlineTypeFieldAssignment()` |
| Normal collection element access | ✅ Works | `VisitGeneralElement.tryTransformCollectionElementAccess()` |
| Normal collection element assignment | ✅ Works | `VisitAssignment_statement.tryTransformCollectionElementAssignment()` |

### What's Missing

| Component | Status | Issue |
|-----------|--------|-------|
| Type-aware getter/setter generation | ✅ Done | `PackageHelperGenerator` now checks for inline types |
| Package variable field access | ✅ Done | `VisitGeneralElement.tryTransformPackageVariableFieldAccess()` |
| Package variable field assignment | ✅ Done | `VisitAssignment_statement.tryTransformPackageVariableFieldAssignment()` |
| Package variable collection access | ✅ Done | `VisitGeneralElement.tryTransformPackageVariableCollectionAccess()` |
| Package variable collection assignment | ✅ Done | `VisitAssignment_statement.tryTransformPackageVariableCollectionAssignment()` |

---

## 3. Implementation Changes

### Phase 1: Type-Aware Getter/Setter Generation

**File:** `src/main/java/me/christianrobert/orapgsync/transformer/packagevariable/PackageHelperGenerator.java`

#### 1.1 Add PackageContext parameter to generation methods

Current signature:
```java
public List<String> generateHelperSql(PackageContext context)
```

The context already contains types - we just need to use them.

#### 1.2 Update `generateGetterFunction()` (line ~110)

```java
private String generateGetterFunction(PackageContext context, PackageContext.PackageVariable var) {
    // NEW: Check if variable type is a known inline type
    InlineTypeDefinition inlineType = context.getType(var.getDataType());

    String pgType;
    String defaultValue;

    if (inlineType != null) {
        // Complex type → jsonb
        pgType = "jsonb";
        defaultValue = inlineType.getInitializer();  // '{}' or '[]'
    } else {
        // Scalar type → use TypeConverter
        pgType = TypeConverter.toPostgre(var.getDataType());
        defaultValue = transformDefaultValue(var.getDefaultValue(), var.getDataType());
    }

    // ... rest of function using pgType and defaultValue
}
```

#### 1.3 Update `generateSetterFunction()` (line ~145)

```java
private String generateSetterFunction(PackageContext context, PackageContext.PackageVariable var) {
    // NEW: Check if variable type is a known inline type
    InlineTypeDefinition inlineType = context.getType(var.getDataType());

    String pgType = (inlineType != null) ? "jsonb" : TypeConverter.toPostgre(var.getDataType());

    // ... rest of function using pgType
}
```

#### 1.4 Update `generateInitializeFunction()` (line ~67)

```java
// In the loop initializing variables:
for (PackageContext.PackageVariable var : context.getVariables().values()) {
    String configKey = schema + "." + pkgName + "." + var.getVariableName().toLowerCase();

    // NEW: Check for inline type
    InlineTypeDefinition inlineType = context.getType(var.getDataType());
    String pgDefaultValue;

    if (inlineType != null) {
        pgDefaultValue = inlineType.getInitializer();  // '{}' or '[]'
    } else {
        pgDefaultValue = transformDefaultValue(var.getDefaultValue(), var.getDataType());
    }

    sql.append("  PERFORM set_config('").append(configKey).append("', '")
       .append(escapeQuotes(pgDefaultValue)).append("', true);\n");
}
```

---

### Phase 2: Package Variable Field Access (RHS)

**File:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitGeneralElement.java`

#### 2.1 Add detection for package variable field access pattern

In `handleDotNavigation()` (around line 86), after existing package variable checks:

**Pattern to detect:** `pkg.g_rec.field` or `schema.pkg.g_rec.field`
- 3 parts: `[package, variable, field]`
- 4 parts: `[schema, package, variable, field]`

```java
// STEP 0.3: Check for package variable FIELD access (Phase: Complex Types)
// Pattern: pkg.g_rec.field → (pkg__get_g_rec()->>'field')::type
// Pattern: schema.pkg.g_rec.field → (schema.pkg__get_g_rec()->>'field')::type
if (!b.isInAssignmentTarget()) {
    String fieldAccessResult = tryTransformPackageVariableFieldAccess(parts, b);
    if (fieldAccessResult != null) {
        return fieldAccessResult;
    }
}
```

#### 2.2 Implement `tryTransformPackageVariableFieldAccess()`

```java
/**
 * Tries to transform package variable field access to jsonb operations.
 *
 * <p>Patterns:
 * <ul>
 *   <li>3 parts: pkg.g_rec.field → (pkg__get_g_rec()->>'field')::type</li>
 *   <li>4 parts: schema.pkg.g_rec.field → (schema.pkg__get_g_rec()->>'field')::type</li>
 *   <li>Nested: pkg.g_rec.addr.city → (pkg__get_g_rec()->'addr'->>'city')::type</li>
 * </ul>
 *
 * @param parts Dot-separated parts
 * @param b PostgreSQL code builder
 * @return Transformed expression, or null if not a package variable field access
 */
private static String tryTransformPackageVariableFieldAccess(
        List<PlSqlParser.General_element_partContext> parts,
        PostgresCodeBuilder b) {

    if (parts.size() < 3) {
        return null;  // Need at least pkg.var.field
    }

    TransformationContext context = b.getContext();
    if (context == null) {
        return null;
    }

    // Determine package name and variable name based on part count
    String schemaPrefix = "";
    String packageName;
    String variableName;
    int fieldStartIndex;

    if (parts.size() >= 4) {
        // Could be schema.pkg.var.field or pkg.var.field1.field2
        // Check if first part is current schema
        String firstPart = parts.get(0).id_expression().getText();
        if (firstPart.equalsIgnoreCase(context.getCurrentSchema())) {
            // schema.pkg.var.field pattern
            schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
            packageName = parts.get(1).id_expression().getText();
            variableName = parts.get(2).id_expression().getText();
            fieldStartIndex = 3;
        } else {
            // pkg.var.field1.field2 pattern (nested fields)
            packageName = parts.get(0).id_expression().getText();
            variableName = parts.get(1).id_expression().getText();
            fieldStartIndex = 2;
        }
    } else {
        // 3 parts: pkg.var.field
        packageName = parts.get(0).id_expression().getText();
        variableName = parts.get(1).id_expression().getText();
        fieldStartIndex = 2;
    }

    // Check if this is actually a package variable
    if (!b.isPackageVariable(packageName, variableName)) {
        return null;
    }

    // Get the inline type definition for the variable
    PackageContext pkgContext = context.getPackageContext(packageName);
    if (pkgContext == null) {
        return null;
    }

    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(variableName);
    if (pkgVar == null) {
        return null;
    }

    InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
    if (inlineType == null || !inlineType.isRecord()) {
        return null;  // Not a complex type - shouldn't happen if we got here
    }

    // Build the getter call
    String getterCall = schemaPrefix + packageName.toLowerCase() + "__get_" + variableName.toLowerCase() + "()";

    // Build field path access
    StringBuilder result = new StringBuilder();
    result.append("( ").append(getterCall);

    // Navigate through fields
    InlineTypeDefinition currentType = inlineType;
    FieldDefinition finalField = null;

    for (int i = fieldStartIndex; i < parts.size(); i++) {
        String fieldName = parts.get(i).id_expression().getText();
        boolean isLastField = (i == parts.size() - 1);

        if (isLastField) {
            result.append("->>'").append(fieldName).append("'");
            if (currentType != null) {
                finalField = findField(currentType, fieldName);
            }
        } else {
            result.append("->'").append(fieldName).append("'");
            // Try to resolve nested type for next iteration
            if (currentType != null) {
                FieldDefinition field = findField(currentType, fieldName);
                if (field != null) {
                    currentType = context.resolveInlineType(field.getOracleType());
                } else {
                    currentType = null;
                }
            }
        }
    }

    result.append(" )");

    // Add type cast if we know the field type
    if (finalField != null) {
        result.append("::").append(finalField.getPostgresType());
    }

    return result.toString();
}
```

---

### Phase 3: Package Variable Field Assignment (LHS)

**File:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitAssignment_statement.java`

#### 3.1 Add detection for package variable field assignment

In `v()` method (around line 85), add check before existing inline type field check:

```java
// STEP 2.5: Check if LHS is a package variable FIELD assignment (Phase: Complex Types)
// Pattern: pkg.g_rec.field := value
// Transform: PERFORM pkg__set_g_rec(jsonb_set(pkg__get_g_rec(), '{field}', to_jsonb(value)))
if (ctx.general_element() != null) {
    String pkgFieldAssignment = tryTransformPackageVariableFieldAssignment(ctx.general_element(), ctx, b);
    if (pkgFieldAssignment != null) {
        return pkgFieldAssignment;
    }
}
```

#### 3.2 Implement `tryTransformPackageVariableFieldAssignment()`

```java
/**
 * Tries to transform package variable field assignment to setter with jsonb_set.
 *
 * <p>Pattern: pkg.g_rec.field := value
 * <p>Transform: PERFORM pkg__set_g_rec(jsonb_set(pkg__get_g_rec(), '{field}', to_jsonb(value)))
 *
 * @param elemCtx General element context (LHS)
 * @param assignCtx Assignment statement context (for RHS)
 * @param b PostgreSQL code builder
 * @return Transformed setter call, or null if not a package variable field assignment
 */
private static String tryTransformPackageVariableFieldAssignment(
        PlSqlParser.General_elementContext elemCtx,
        PlSqlParser.Assignment_statementContext assignCtx,
        PostgresCodeBuilder b) {

    // Must have nested general_element (dotted access)
    if (elemCtx.general_element() == null) {
        return null;
    }

    List<PlSqlParser.General_element_partContext> parts = collectAllParts(elemCtx);
    if (parts.size() < 3) {
        return null;  // Need at least pkg.var.field
    }

    TransformationContext context = b.getContext();
    if (context == null) {
        return null;
    }

    // Parse pattern (same logic as field access)
    String schemaPrefix = "";
    String packageName;
    String variableName;
    int fieldStartIndex;

    if (parts.size() >= 4) {
        String firstPart = parts.get(0).id_expression().getText();
        if (firstPart.equalsIgnoreCase(context.getCurrentSchema())) {
            schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
            packageName = parts.get(1).id_expression().getText();
            variableName = parts.get(2).id_expression().getText();
            fieldStartIndex = 3;
        } else {
            packageName = parts.get(0).id_expression().getText();
            variableName = parts.get(1).id_expression().getText();
            fieldStartIndex = 2;
        }
    } else {
        packageName = parts.get(0).id_expression().getText();
        variableName = parts.get(1).id_expression().getText();
        fieldStartIndex = 2;
    }

    // Check if this is actually a package variable
    if (!b.isPackageVariable(packageName, variableName)) {
        return null;
    }

    // Build getter and setter names
    String getterCall = schemaPrefix + packageName.toLowerCase() + "__get_" + variableName.toLowerCase() + "()";
    String setterName = schemaPrefix + packageName.toLowerCase() + "__set_" + variableName.toLowerCase();

    // Build field path for jsonb_set
    StringBuilder fieldPath = new StringBuilder();
    fieldPath.append("'{ ");
    for (int i = fieldStartIndex; i < parts.size(); i++) {
        if (i > fieldStartIndex) {
            fieldPath.append(" , ");
        }
        fieldPath.append(parts.get(i).id_expression().getText());
    }
    fieldPath.append(" }'");

    // Transform RHS expression
    String rightSide = b.visit(assignCtx.expression());
    String castedValue = addExplicitCastForLiterals(rightSide);

    // Build: PERFORM setter(jsonb_set(getter(), '{path}', to_jsonb(value), true))
    StringBuilder result = new StringBuilder();
    result.append("PERFORM ").append(setterName).append("( ");
    result.append("jsonb_set( ");
    result.append(getterCall);
    result.append(" , ");
    result.append(fieldPath);
    result.append(" , to_jsonb( ");
    result.append(castedValue);
    result.append(" )");

    // Add 'true' flag for nested paths (creates missing intermediate objects)
    if (parts.size() - fieldStartIndex > 1) {
        result.append(" , true");
    }

    result.append(" ) )");

    return result.toString();
}
```

---

### Phase 4: Package Variable Collection Element Access

**File:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitGeneralElement.java`

#### 4.1 Add detection in `handleSimplePart()` or as separate check

**Pattern:** `pkg.g_array(i)` - This is tricky because it looks like a function call.

The current flow sees `pkg.g_array(i)` as:
- 2 parts: `[pkg, g_array(i)]` where g_array has function arguments

Need to detect: Is `g_array` a package variable (not a function)?

```java
// In handlePackageFunctionCall() or before it:
// Check if this is actually a package variable with collection access
// Pattern: pkg.g_array(1) where g_array is a TABLE OF/VARRAY/INDEX BY variable

if (parts.size() == 2) {
    String packageName = parts.get(0).id_expression().getText();
    String memberName = parts.get(1).id_expression().getText();

    // Check if it's a package variable (not a function)
    if (b.isPackageVariable(packageName, memberName)) {
        // It's collection element access on a package variable
        return transformPackageVariableCollectionAccess(parts.get(1), packageName, memberName, b);
    }
}
```

#### 4.2 Implement `transformPackageVariableCollectionAccess()`

```java
private static String transformPackageVariableCollectionAccess(
        PlSqlParser.General_element_partContext memberPart,
        String packageName,
        String variableName,
        PostgresCodeBuilder b) {

    // Get the argument (index or key)
    List<PlSqlParser.Function_argumentContext> funcArgList = memberPart.function_argument();
    if (funcArgList == null || funcArgList.isEmpty()) {
        return null;
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.size() != 1) {
        return null;
    }

    String argValue = b.visit(arguments.get(0).expression());

    // Get package context and variable type
    TransformationContext context = b.getContext();
    PackageContext pkgContext = context.getPackageContext(packageName);
    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(variableName);
    InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());

    String getterCall = packageName.toLowerCase() + "__get_" + variableName.toLowerCase() + "()";

    if (inlineType.isAssociativeArray() && isStringIndexKeyType(inlineType.getIndexKeyType())) {
        // INDEX BY VARCHAR2 → map access
        return "( " + getterCall + " ->> " + argValue + " )";
    } else {
        // TABLE OF, VARRAY, or INDEX BY PLS_INTEGER → array access (1-based to 0-based)
        return buildNumericArrayAccessForPackageVar(getterCall, argValue, inlineType);
    }
}
```

---

### Phase 5: Package Variable Collection Element Assignment

**File:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitAssignment_statement.java`

Similar pattern to Phase 4, but generating:
```sql
PERFORM pkg__set_g_array(jsonb_set(pkg__get_g_array(), '{index}', to_jsonb(value)))
```

---

## 4. Edge Cases & Patterns

### 4.1 Supported Patterns (In Scope)

| Pattern | Oracle | PostgreSQL |
|---------|--------|------------|
| Simple field access | `pkg.g_rec.field` | `(pkg__get_g_rec()->>'field')::type` |
| Nested field access | `pkg.g_rec.addr.city` | `(pkg__get_g_rec()->'addr'->>'city')::type` |
| Field assignment | `pkg.g_rec.field := val` | `PERFORM pkg__set_g_rec(jsonb_set(...))` |
| Array access | `pkg.g_arr(1)` | `(pkg__get_g_arr()->>0)::type` |
| Array assignment | `pkg.g_arr(1) := val` | `PERFORM pkg__set_g_arr(jsonb_set(...))` |
| Map access | `pkg.g_map('key')` | `(pkg__get_g_map()->>'key')` |
| Map assignment | `pkg.g_map('key') := val` | `PERFORM pkg__set_g_map(jsonb_set(...))` |
| Schema-qualified | `hr.pkg.g_rec.field` | `(hr.pkg__get_g_rec()->>'field')::type` |

### 4.2 Complex Combined Patterns (Phase 2 - Deferred)

| Pattern | Complexity | Notes |
|---------|------------|-------|
| `pkg.g_arr(1).field` | High | Collection of RECORDs |
| `pkg.g_rec.items(1)` | High | RECORD with collection field |
| `pkg.g_map('k').field` | High | Map of RECORDs |

These can be deferred to a follow-up if not immediately needed.

### 4.3 Unqualified Access (Inside Package)

When inside a package function, variables can be accessed without package prefix:
```sql
-- Oracle (inside emp_pkg)
g_range.min_sal := 1000;

-- PostgreSQL
PERFORM emp_pkg__set_g_range(jsonb_set(emp_pkg__get_g_range(), '{min_sal}', to_jsonb(1000)));
```

This requires checking `context.isInPackageMember()` and `context.getCurrentPackageName()`.

---

## 5. Test Cases

### 5.1 Unit Tests

**File:** `src/test/java/me/christianrobert/orapgsync/transformer/PackageVariableComplexTypeTest.java`

```java
// Getter/Setter Generation Tests
@Test void testRecordVariableGetterGeneration()
@Test void testRecordVariableSetterGeneration()
@Test void testTableOfVariableGetterGeneration()
@Test void testIndexByVariableGetterGeneration()
@Test void testInitializeFunctionWithComplexTypes()

// Field Access Tests (RHS)
@Test void testSimpleFieldAccess()
@Test void testNestedFieldAccess()
@Test void testSchemaQualifiedFieldAccess()
@Test void testUnqualifiedFieldAccessInsidePackage()

// Field Assignment Tests (LHS)
@Test void testSimpleFieldAssignment()
@Test void testNestedFieldAssignment()
@Test void testFieldAssignmentWithExpression()

// Collection Access Tests
@Test void testArrayElementAccess()
@Test void testArrayElementAccessWithVariable()
@Test void testMapElementAccess()

// Collection Assignment Tests
@Test void testArrayElementAssignment()
@Test void testMapElementAssignment()
```

### 5.2 Integration Tests

**File:** `src/test/java/me/christianrobert/orapgsync/integration/PostgresPackageVariableComplexTypeIntegrationTest.java`

End-to-end tests that:
1. Create package with complex type variables
2. Transform to PostgreSQL
3. Execute in PostgreSQL
4. Verify behavior matches Oracle semantics

---

## 6. Implementation Order

### Phase 1: Foundation (Required First) ✅ COMPLETE
1. ✅ Update `PackageHelperGenerator` for type-aware generation
2. ✅ `PackageContext.getType()` lookup method already exists
3. ✅ Write unit tests for getter/setter generation (6 new tests, 14 total passing)

### Phase 2: Field Access (RHS) ✅ COMPLETE
1. ✅ Implement `tryTransformPackageVariableFieldAccess()` in VisitGeneralElement
2. ✅ Handle unqualified access inside package functions
3. ✅ Write field access tests (9 new tests, 63 total package variable tests passing)

### Phase 3: Field Assignment (LHS) ✅ COMPLETE
1. ✅ Implement `tryTransformPackageVariableFieldAssignment()` in VisitAssignment_statement
2. ✅ Handle nested field paths (with `true` flag for creating intermediate objects)
3. ✅ Write field assignment tests (6 new tests, 69 total package variable tests passing)

### Phase 4: Collection Element Access ✅ COMPLETE
1. ✅ Detect package variable vs function before `handlePackageFunctionCall()` in `handleDotNavigation()`
2. ✅ Implement `tryTransformPackageVariableCollectionAccess()` and `buildPackageVariableCollectionAccess()`
3. ✅ Implement `tryTransformUnqualifiedPackageVariableCollectionAccess()` in `handleSimplePart()`
4. ✅ Write collection access tests (8 new tests, 77 total package variable tests passing)

### Phase 5: Collection Element Assignment ✅ COMPLETE
1. ✅ Add STEP 2.7 check in `VisitAssignment_statement.v()` for package variable collection element assignment
2. ✅ Implement `tryTransformPackageVariableCollectionAssignment()` and `buildPackageVariableCollectionAssignment()`
3. ✅ Write collection assignment tests (7 new tests, 60 total package variable tests passing)

### Phase 6: Integration Testing ✅ COMPLETE
1. ✅ End-to-end integration tests added to `PostgresPackageVariableIntegrationTest.java`
   - `recordTypeVariable_fieldAccessAndAssignment()` - RECORD field access and assignment
   - `tableOfVariable_elementAccessAndAssignment()` - TABLE OF (INDEX BY PLS_INTEGER) element access/assignment
   - `indexByVarchar2Variable_mapAccessAndAssignment()` - INDEX BY VARCHAR2 map operations
   - `mixedComplexTypes_recordFieldAccess()` - Mixed scalar and complex types
2. ✅ Fixed `PackageHelperGenerator` to use raw JSON values (`{}`, `[]`) instead of SQL-escaped values
3. ✅ Fixed INDEX BY PLS_INTEGER to use object key access (string keys) instead of array index access
4. ✅ All 8 integration tests passing, all 30 unit tests passing

---

## 7. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Disambiguation failures (variable vs function) | Medium | High | Leverage existing `isPackageVariable()` checks |
| Nested field path errors | Low | Medium | Reuse existing `collectAllParts()` logic |
| Type cast errors | Low | Medium | Reuse existing `FieldDefinition.getPostgresType()` |
| Performance regression | Low | Low | Same pattern as normal variables |

---

## 8. Success Criteria

1. All existing package variable tests continue to pass (920 tests)
2. New complex type tests pass
3. Oracle packages with RECORD/TABLE OF/INDEX BY variables transform correctly
4. Field access and assignment work for both qualified and unqualified patterns
5. No regressions in normal (non-package) variable handling
