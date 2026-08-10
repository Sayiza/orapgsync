# Object Type Field Access Transformation

**Created:** 2025-11-23
**Status:** 🔄 **IN PROGRESS** - Phases 1-2 complete
**Estimated Effort:** 3-4 hours
**Actual Effort (so far):** ~90 minutes (Phases 1-2)
**Priority:** HIGH - Fixes critical gap in view transformation

---

## Problem Statement

### Current Behavior

Oracle SQL with object type field access passes through unchanged, resulting in invalid PostgreSQL:

```sql
-- Oracle (WORKS)
SELECT nr, l.langy.de AS lgde, l.langy.en
FROM user_robert.langtable l

-- Current transformation (INVALID PostgreSQL)
SELECT nr, l.langy.de AS lgde, l.langy.en
FROM user_robert.langtable l
-- ERROR: missing FROM-clause entry for table "langy"
```

### Expected Behavior

PostgreSQL requires parentheses around composite type column access:

```sql
-- PostgreSQL (VALID)
SELECT nr, (l.langy).de AS lgde, (l.langy).en
FROM user_robert.langtable l
```

### Root Cause

`VisitGeneralElement` focuses on type **methods** (with parentheses) but doesn't detect type **field access** (without parentheses). The current logic can't distinguish:
- `a.b.c` as object field access (needs transformation)
- `schema.table.column` (no transformation)
- `package.function.call` (different transformation)

---

## Solution Overview

**Approach:** Metadata-driven field access detection using `TransformationIndices`

**Why this approach:**
- ✅ Deterministic and rigorous (follows CLAUDE.md design philosophy)
- ✅ No false positives
- ✅ Consistent with existing type method handling
- ✅ Handles synonyms correctly (including PUBLIC synonyms)
- ✅ Uses established `TransformationIndices` pattern

**Maximum nesting depth:** 2 levels (sufficient for 99% of real-world cases)

---

## Algorithm

### Step-by-Step Transformation Logic

```
INPUT: Dot-separated identifier chain (e.g., "l.langy.de.en")

STEP 1: Parse and validate
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
parts[] = split on "."
if parts.length < 3:
    return NO_TRANSFORMATION  // Need at least alias.column.field

STEP 2: Resolve table/alias (determine starting point)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
table = null
startIndex = -1

// Option A: Table alias in current query
if isKnownAlias(parts[0]):
    table = resolveAlias(parts[0])        // e.g., "l" → "HR.LANGTABLE"
    startIndex = 1
    goto STEP_3

// Option B: Synonym (could point to table)
synonym = resolveSynonym(parts[0], currentSchema)
if synonym != null:
    table = synonym                       // e.g., "LANGTABLE" → "HR.LANGTABLE"
    startIndex = 1
    goto STEP_3

// Option C: Schema.Table pattern
if parts.length >= 2:
    if isValidSchema(parts[0]) AND isTableInSchema(parts[0], parts[1]):
        table = parts[0] + "." + parts[1] // e.g., "HR.LANGTABLE"
        startIndex = 2
        goto STEP_3

// Option D: Unqualified table in current schema
if isTableInSchema(currentSchema, parts[0]):
    table = currentSchema + "." + parts[0]
    startIndex = 1
    goto STEP_3

// Not a table reference - could be package function, etc.
return DELEGATE_TO_EXISTING_LOGIC

STEP 3: Get column type and qualify it
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
if startIndex >= parts.length:
    return NO_TRANSFORMATION  // No parts after table

columnName = parts[startIndex]
columnType = getColumnType(table, columnName)  // Might be unqualified like "LANGY_TYPE"

if columnType == null:
    return NO_TRANSFORMATION  // Unknown column

// ⭐ KEY STEP: Qualify the type name using synonym resolution
qualifiedColumnType = qualifyTypeName(columnType, currentSchema, transformationContext)
// e.g., "LANGY_TYPE" → "HR.LANGY_TYPE" or "PUBLIC.LANGY_TYPE"

if NOT isObjectType(qualifiedColumnType):
    return NO_TRANSFORMATION  // Not an object type, no field access possible

STEP 4: Check if there's actually field access
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
if startIndex + 1 >= parts.length:
    return NO_TRANSFORMATION  // Just selecting object column, no field access
    // e.g., "SELECT l.langy FROM ..." → no transformation needed

STEP 5: Transform first-level field access
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Rebuild the base (everything up to and including column)
base = join(parts[0...startIndex], ".")  // e.g., "l.langy"

field1Name = parts[startIndex + 1]       // e.g., "de"
field1Type = getFieldType(qualifiedColumnType, field1Name)

if field1Type == null:
    return NO_TRANSFORMATION  // Unknown field - let PostgreSQL report error

// ⭐ Apply PostgreSQL transformation
result = "(" + base + ")." + field1Name  // e.g., "(l.langy).de"

// Check if there are more parts (nested object case)
if startIndex + 2 >= parts.length:
    return result  // Done! e.g., "(l.langy).de"

STEP 6: Handle nested object field access (level 2)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Qualify field1 type
qualifiedField1Type = qualifyTypeName(field1Type, currentSchema, transformationContext)

if NOT isObjectType(qualifiedField1Type):
    // field1 is primitive, but more parts exist - unusual case
    // Pass through remaining parts (will likely error, but preserve intent)
    for i = startIndex + 2 to parts.length - 1:
        result += "." + parts[i]
    return result

// field1 is also an object type, access its field
field2Name = parts[startIndex + 2]       // e.g., "en"
field2Type = getFieldType(qualifiedField1Type, field2Name)

if field2Type == null:
    return result + "." + field2Name  // Unknown nested field, pass through

// ⭐ Apply nested transformation
result = "(" + result + ")." + field2Name  // e.g., "((l.langy).de).en"

// If more parts exist beyond our max depth (2 levels), append them
if startIndex + 3 < parts.length:
    for i = startIndex + 3 to parts.length - 1:
        result += "." + parts[i]
    // Note: This might cause PostgreSQL errors if parts[i] references deeper nesting
    // But we preserve Oracle's structure

return result
```

### Helper: Type Name Qualification

**Critical for synonym support!**

```
qualifyTypeName(typeName, currentSchema, context):
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// If already qualified, return as-is
if typeName.contains("."):
    return typeName.toUpperCase()  // Oracle is case-insensitive

// ⭐ Follow Oracle type resolution rules (same as synonym resolution)

// 1. Check current schema
qualified = currentSchema + "." + typeName
if context.hasObjectType(qualified):
    return qualified

// 2. Check PUBLIC schema (synonyms)
qualified = "PUBLIC." + typeName
if context.hasObjectType(qualified):
    return qualified

// 3. Check SYS schema (system types)
qualified = "SYS." + typeName
if context.hasObjectType(qualified):
    return qualified

// Not found - return unqualified (will fail later, but preserves intent)
return typeName.toUpperCase()
```

---

## Implementation Phases

### Phase 1: Metadata Infrastructure ✅ (30 minutes)

**Goal:** Extend `TransformationIndices` with object type field metadata

#### 1.1. Extend TransformationIndices

**File:** `src/main/java/.../transformer/context/TransformationIndices.java`

Add fields:
```java
// Map: QualifiedTypeName → (FieldName → FieldType)
// Example: "HR.ADDRESS_TYPE" → {"STREET" → "VARCHAR2", "CITY" → "VARCHAR2"}
private final Map<String, Map<String, String>> typeFieldTypes;

// Set: QualifiedTypeName (for quick existence checks)
// Example: "HR.ADDRESS_TYPE", "PUBLIC.LANGY_TYPE"
private final Set<String> objectTypeNames;
```

Add methods:
```java
/**
 * Get the type of a field in an object type.
 * @param qualifiedTypeName Schema-qualified type name (e.g., "HR.ADDRESS_TYPE")
 * @param fieldName Field name (case-insensitive)
 * @return Field type (unqualified), or null if not found
 */
public String getFieldType(String qualifiedTypeName, String fieldName);

/**
 * Check if a type name is a known object type.
 * @param qualifiedTypeName Schema-qualified type name
 * @return true if type exists in metadata
 */
public boolean isObjectType(String qualifiedTypeName);

/**
 * Get all fields of an object type.
 * @param qualifiedTypeName Schema-qualified type name
 * @return Map of field names to types, or empty map if not found
 */
public Map<String, String> getTypeFields(String qualifiedTypeName);
```

#### 1.2. Update MetadataIndexBuilder

**File:** `src/main/java/.../transformer/context/MetadataIndexBuilder.java`

Add method:
```java
private void buildTypeFieldIndices(StateService stateService) {
    List<ObjectDataTypeMetaData> types = stateService.getOracleObjectTypeMetadata();

    for (ObjectDataTypeMetaData type : types) {
        String qualifiedTypeName = (type.getSchema() + "." + type.getTypeName()).toUpperCase();

        objectTypeNames.add(qualifiedTypeName);

        Map<String, String> fields = new HashMap<>();
        for (TypeAttribute attr : type.getAttributes()) {
            fields.put(attr.getAttrName().toUpperCase(), attr.getAttrType());
        }

        typeFieldTypes.put(qualifiedTypeName, fields);
    }
}
```

Call from `build()`:
```java
public TransformationIndices build() {
    buildTableIndices(stateService);
    buildTypeMethodIndices(stateService);
    buildPackageFunctionIndices(stateService);
    buildTypeFieldIndices(stateService);  // ← NEW

    return new TransformationIndices(...);
}
```

#### 1.3. Extend TransformationContext

**File:** `src/main/java/.../transformer/context/TransformationContext.java`

Add methods:
```java
/**
 * Qualify a type name following Oracle resolution rules.
 * Checks: current schema → PUBLIC → SYS
 */
public String qualifyTypeName(String typeName) {
    if (typeName.contains(".")) {
        return typeName.toUpperCase();
    }

    // Check current schema
    String qualified = currentSchema + "." + typeName.toUpperCase();
    if (indices.isObjectType(qualified)) {
        return qualified;
    }

    // Check PUBLIC schema
    qualified = "PUBLIC." + typeName.toUpperCase();
    if (indices.isObjectType(qualified)) {
        return qualified;
    }

    // Check SYS schema
    qualified = "SYS." + typeName.toUpperCase();
    if (indices.isObjectType(qualified)) {
        return qualified;
    }

    // Not found
    return typeName.toUpperCase();
}

/**
 * Get field type from a qualified object type.
 */
public String getFieldType(String qualifiedTypeName, String fieldName) {
    return indices.getFieldType(qualifiedTypeName, fieldName);
}

/**
 * Check if a type is an object type.
 */
public boolean isObjectType(String qualifiedTypeName) {
    return indices.isObjectType(qualifiedTypeName);
}
```

**Tests:** `TransformationIndicesTest.java`, `MetadataIndexBuilderTest.java`
- Test type field lookup with schema qualification
- Test `qualifyTypeName` with current schema, PUBLIC, SYS
- Test case-insensitive field access

---

### Phase 2: Field Access Detection Logic ✅ (90 minutes)

**Goal:** Implement the field access detection and transformation algorithm

#### 2.1. Create ObjectFieldAccessTransformer

**File:** `src/main/java/.../transformer/builder/objectfield/ObjectFieldAccessTransformer.java`

```java
package me.christianrobert.orapgsync.transformation.builder.objectfield;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;

/**
 * Transforms Oracle object type field access to PostgreSQL composite type syntax.
 *
 * Oracle:  table.column.field  OR  table.column.field1.field2
 * PostgreSQL: (table.column).field OR ((table.column).field1).field2
 *
 * Maximum nesting depth: 2 levels
 */
public class ObjectFieldAccessTransformer {

    private final TransformationContext context;

    public ObjectFieldAccessTransformer(TransformationContext context) {
        this.context = context;
    }

    /**
     * Attempt to transform a dot-separated identifier chain.
     *
     * @param identifierChain Full chain (e.g., "l.langy.de")
     * @return Transformed SQL, or null if no transformation needed
     */
    public String transform(String identifierChain) {
        // Implementation of algorithm described above
    }

    // Private helper methods:
    // - resolveTableAndStartIndex(String[] parts)
    // - transformFieldAccess(String base, String[] parts, int startIndex, String qualifiedColumnType)
    // - handleNestedFieldAccess(String result, String[] parts, int startIndex, String field1Type)
}
```

**Key methods:**

```java
private TableResolution resolveTableAndStartIndex(String[] parts) {
    // Step 2 from algorithm
    // Returns: table name (qualified) + startIndex, or null if not a table reference
}

private String transformFieldAccess(String base, String[] parts, int startIndex, String qualifiedColumnType) {
    // Steps 4-6 from algorithm
    // Returns: transformed SQL with parentheses
}
```

#### 2.2. Integrate into VisitGeneralElement

**File:** `src/main/java/.../transformer/builder/VisitGeneralElement.java`

**Current logic (simplified):**
```java
// Type member method call detection
if (hasTypeMethod) {
    return typeMethodCall(...);
}

// Package function detection
if (isPackageFunction) {
    return packageFunctionCall(...);
}

// Other cases...
return defaultHandling(...);
```

**New logic (insert BEFORE type method check):**
```java
// ⭐ NEW: Object field access detection
ObjectFieldAccessTransformer fieldTransformer =
    new ObjectFieldAccessTransformer(context);
String fieldAccessResult = fieldTransformer.transform(identifierChain);

if (fieldAccessResult != null) {
    return fieldAccessResult;  // Transformed!
}

// Existing logic continues...
if (hasTypeMethod) {
    return typeMethodCall(...);
}
```

**Why before type method check?**
- Field access has no parentheses: `table.col.field`
- Method calls have parentheses: `table.col.method()`
- Method check relies on function context → won't match field access

**Tests:** `ObjectFieldAccessTransformationTest.java`
- Test all resolution paths (alias, synonym, schema.table, unqualified table)
- Test 1-level and 2-level nesting
- Test PUBLIC synonyms
- Test unknown fields (pass through)
- Test non-object types (pass through)

---

### Phase 3: Comprehensive Testing ✅ (60 minutes)

**Goal:** Ensure all edge cases are handled correctly

#### 3.1. Unit Tests

**File:** `src/test/java/.../transformer/ObjectFieldAccessTransformationTest.java`

Test cases:

```java
// Level 1: Basic field access
@Test void testBasicFieldAccess() {
    // Oracle: SELECT l.langy.de FROM langtable l
    // PostgreSQL: SELECT (l.langy).de FROM hr.langtable l
}

@Test void testMultipleFieldAccesses() {
    // Oracle: SELECT l.langy.de, l.langy.en FROM langtable l
    // PostgreSQL: SELECT (l.langy).de, (l.langy).en FROM hr.langtable l
}

// Level 2: Nested object field access
@Test void testNestedFieldAccess() {
    // Oracle: SELECT e.address.location.city FROM employees e
    // PostgreSQL: SELECT ((e.address).location).city FROM hr.employees e
}

// Synonym resolution
@Test void testFieldAccessWithPublicSynonym() {
    // Object type in PUBLIC schema referenced via synonym
}

@Test void testFieldAccessWithSchemaSynonym() {
    // Object type in HR schema referenced via synonym in SALES schema
}

// Schema qualification
@Test void testFieldAccessSchemaQualified() {
    // Oracle: SELECT hr.employees.address.street
    // PostgreSQL: SELECT (hr.employees.address).street
}

// Edge cases
@Test void testFieldAccessNoTransformationNeeded() {
    // Oracle: SELECT l.langy FROM langtable l
    // PostgreSQL: SELECT l.langy FROM hr.langtable l (no transformation)
}

@Test void testFieldAccessUnknownField() {
    // Oracle: SELECT l.langy.unknown FROM langtable l
    // PostgreSQL: Pass through (will error, but preserve intent)
}

@Test void testFieldAccessNonObjectType() {
    // Oracle: SELECT l.name.something FROM langtable l
    // PostgreSQL: Pass through (name is VARCHAR2, not object)
}

@Test void testFieldAccessInWhereClause() {
    // Oracle: WHERE l.langy.de = 'German'
    // PostgreSQL: WHERE (l.langy).de = 'German'
}

@Test void testFieldAccessInOrderBy() {
    // Oracle: ORDER BY l.langy.de
    // PostgreSQL: ORDER BY (l.langy).de
}

// Mixed with other features
@Test void testFieldAccessWithAliasing() {
    // Oracle: SELECT l.langy.de AS german_text FROM langtable l
    // PostgreSQL: SELECT (l.langy).de AS german_text FROM hr.langtable l
}

@Test void testFieldAccessInJoin() {
    // Oracle: FROM t1 JOIN t2 ON t1.obj.field = t2.obj.field
    // PostgreSQL: FROM t1 JOIN t2 ON (t1.obj).field = (t2.obj).field
}
```

#### 3.2. Integration Tests

**File:** Extend existing view transformation tests

Add real-world Oracle view with object field access:

```sql
CREATE TYPE hr.langy_type AS OBJECT (
    de VARCHAR2(100),
    en VARCHAR2(100)
);

CREATE TABLE hr.langtable (
    nr NUMBER,
    langy hr.langy_type
);

CREATE VIEW hr.multilang_view AS
SELECT nr, l.langy.de AS german, l.langy.en AS english
FROM hr.langtable l
WHERE l.langy.de IS NOT NULL;
```

Test that:
1. Metadata extraction includes type fields
2. View transformation produces valid PostgreSQL
3. Transformed view executes without errors

---

### Phase 4: Documentation and Review ✅ (30 minutes)

**Goal:** Update documentation and mark plan complete

#### 4.1. Update TRANSFORMATION.md

Add to **Phase 2: Complete SELECT Support** section (around line 139):

```markdown
**Object Type Field Access:**
- Level 1: `table.column.field` → `(table.column).field`
- Level 2: `table.column.field1.field2` → `((table.column).field1).field2`
- Synonym resolution: Supports PUBLIC synonyms and schema-specific type resolution
- Works in all SQL clauses: SELECT, WHERE, ORDER BY, JOIN conditions
```

Add to **Feature Details** section (new subsection after String Functions):

```markdown
### Object Type Field Access

**Transformation Strategy:** Metadata-driven detection with parentheses wrapping

**Examples:**
```sql
-- Level 1: Basic field access
SELECT l.langy.de → SELECT (l.langy).de

-- Level 2: Nested objects
SELECT e.address.location.city → SELECT ((e.address).location).city

-- With PUBLIC synonym
SELECT t.obj.field → SELECT (t.obj).field
-- (obj type resolved via PUBLIC synonym)
```

**Key Features:**
- Maximum nesting depth: 2 levels (covers 99% of real-world cases)
- Synonym-aware: Resolves types via current schema → PUBLIC → SYS
- Pass-through for unknown fields (preserves Oracle intent, PostgreSQL reports error)
- Works in all SQL clauses
```

#### 4.2. Update Test Coverage Statistics

Update line 140 in TRANSFORMATION.md:

```markdown
**Test Coverage:** 270 tests → 290+ tests (add 20+ for object field access)
```

#### 4.3. Mark Plan Complete

Update this file:
- Change status to ✅ **COMPLETE**
- Add completion date
- Add actual implementation time
- Update TRANSFORMATION.md with summary

---

## Edge Cases Handled

### 1. Synonym Resolution
```sql
-- PUBLIC synonym pointing to type
CREATE PUBLIC SYNONYM LANGY_TYPE FOR HR.LANGY_TYPE;

-- Query uses synonym
SELECT l.obj.field FROM langtable l;
-- Transformer resolves: LANGY_TYPE → HR.LANGY_TYPE → gets fields
```

### 2. Schema-Qualified Table
```sql
-- Fully qualified
SELECT hr.employees.address.street;
-- → SELECT (hr.employees.address).street
```

### 3. Unknown Fields (Pass Through)
```sql
-- Field doesn't exist in metadata
SELECT l.langy.unknown FROM langtable l;
-- → SELECT (l.langy).unknown (PostgreSQL will error, but intent preserved)
```

### 4. Non-Object Type Column
```sql
-- Column is VARCHAR2, not object
SELECT l.name.something FROM langtable l;
-- → SELECT l.name.something (no transformation, will error)
```

### 5. No Field Access (Just Column)
```sql
-- Selecting entire object column
SELECT l.langy FROM langtable l;
-- → SELECT l.langy FROM hr.langtable l (no transformation needed)
```

### 6. Mixed with Method Calls
```sql
-- Method call (has parentheses)
SELECT l.obj.getDescription() FROM langtable l;
-- → Delegates to type method logic (existing)

-- Field access (no parentheses)
SELECT l.obj.description FROM langtable l;
-- → SELECT (l.obj).description (this transformation)
```

### 7. Multiple Schemas with Same Type Name
```sql
-- HR.ADDRESS_TYPE has fields: street, city
-- SALES.ADDRESS_TYPE has fields: line1, line2

-- Query in HR schema
SELECT e.address.street FROM hr.employees e;
-- Resolves to HR.ADDRESS_TYPE

-- Query in SALES schema
SELECT c.address.line1 FROM sales.customers c;
-- Resolves to SALES.ADDRESS_TYPE
```

### 8. Deeper Nesting Than Max (2 levels)
```sql
-- Oracle supports deeper nesting (rare)
SELECT l.a.b.c.d FROM langtable l;
-- → SELECT ((l.a).b).c.d (transform first 2 levels, append rest)
-- Note: Will likely error in PostgreSQL, but preserves Oracle structure
```

---

## Metadata Requirements

### Source: StateService.oracleObjectTypeMetadata

**Class:** `ObjectDataTypeMetaData`

**Used fields:**
- `schema` - Schema where type is defined (e.g., "HR", "PUBLIC")
- `typeName` - Type name (e.g., "ADDRESS_TYPE")
- `attributes` - List of `TypeAttribute`

**Class:** `TypeAttribute`

**Used fields:**
- `attrName` - Field name (e.g., "STREET")
- `attrType` - Field type (may be unqualified, e.g., "VARCHAR2" or "CITY_TYPE")

### New Index Structures

```java
// In TransformationIndices:

// Map: "HR.ADDRESS_TYPE" → {"STREET" → "VARCHAR2", "CITY" → "VARCHAR2"}
private Map<String, Map<String, String>> typeFieldTypes;

// Set: "HR.ADDRESS_TYPE", "PUBLIC.LANGY_TYPE", "SYS.ANYDATA"
private Set<String> objectTypeNames;
```

---

## Success Criteria

### Phase 1: Metadata Infrastructure
- [ ] `TransformationIndices` has `typeFieldTypes` and `objectTypeNames`
- [ ] `MetadataIndexBuilder.buildTypeFieldIndices()` populates maps from StateService
- [ ] `TransformationContext.qualifyTypeName()` follows Oracle resolution rules
- [ ] Unit tests pass for metadata lookups

### Phase 2: Field Access Detection
- [ ] `ObjectFieldAccessTransformer` implements algorithm correctly
- [ ] Integrated into `VisitGeneralElement` before type method check
- [ ] Handles 1-level and 2-level nesting
- [ ] Synonym resolution works (current schema, PUBLIC, SYS)
- [ ] Unknown fields pass through (no transformation)

### Phase 3: Comprehensive Testing
- [ ] 20+ unit tests covering all edge cases
- [ ] Integration test with real Oracle object types
- [ ] All existing tests still pass (no regressions)
- [ ] Works in SELECT, WHERE, ORDER BY, JOIN clauses

### Phase 4: Documentation
- [ ] TRANSFORMATION.md updated with feature description
- [ ] Test coverage statistics updated
- [ ] Plan file marked complete with actual implementation time

---

## Implementation Notes

### Case Sensitivity
- Oracle identifiers are case-insensitive unless quoted
- All metadata lookups use `.toUpperCase()` normalization
- Example: `langy`, `LANGY`, `Langy` all resolve to same type

### Type Name Qualification
- Column types in metadata may be unqualified (`LANGY_TYPE`)
- Must qualify before looking up fields (`HR.LANGY_TYPE`)
- Follow Oracle resolution: current schema → PUBLIC → SYS

### Integration Point
- Insert field access detection **before** type method check in `VisitGeneralElement`
- Field access has no parentheses, method calls do
- Avoids conflict with existing method call logic

### Maximum Nesting
- Fixed at 2 levels (sufficient for 99% of real-world Oracle schemas)
- Deeper nesting appends extra parts (may error, but preserves intent)

---

## Future Enhancements

**Not planned for this implementation:**

1. **Arbitrary depth nesting** - Could add recursive transformation for 3+ levels
2. **Type method chaining** - `obj.method1().field.method2()` mixed field/method access
3. **Collection element access** - `table.array_col(1).field` (requires VARRAY/NESTED TABLE support)
4. **Type casting optimization** - Remove unnecessary casts in field access chains

---

## References

- **TRANSFORMATION.md** - Main transformation documentation
- **CLAUDE.md** - Design philosophy ("Prefer Rigorous Solutions Over Heuristics")
- **Existing patterns:**
  - Type method handling in `VisitGeneralElement`
  - Metadata indices in `TransformationIndices`
  - Synonym resolution in `TransformationContext`

---

## Status Tracking

| Phase | Status | Time | Completed |
|-------|--------|------|-----------|
| Phase 1: Metadata Infrastructure | ✅ Complete | 45 min | 2025-11-23 |
| Phase 2: Field Access Detection | ✅ Complete | 45 min | 2025-11-23 |
| Phase 3: Comprehensive Testing | 🔄 In Progress | 60 min | - |
| Phase 4: Documentation | 📋 Planned | 30 min | - |
| **Total** | **🔄 In Progress** | **~90 min / 3-4 hours** | - |

---

**Next Steps:**
1. Review plan for completeness
2. Begin Phase 1: Metadata Infrastructure
3. Implement and test incrementally
4. Update documentation upon completion
