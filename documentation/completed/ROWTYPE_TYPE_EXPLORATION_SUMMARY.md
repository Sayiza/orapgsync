# %ROWTYPE and %TYPE Support Exploration Summary

**Date:** 2025-11-07
**Status:** Comprehensive analysis of current infrastructure

---

## Executive Summary

Excellent news: **The codebase has substantial infrastructure already in place for %ROWTYPE and %TYPE support!** All foundational pieces are defined and ready for implementation. The system just needs the type resolution logic to be connected.

### Quick Assessment
- ✅ TypeCategory.ROWTYPE and TypeCategory.TYPE_REFERENCE already defined
- ✅ ANTLR grammar already parses %ROWTYPE and %TYPE syntax
- ✅ InlineTypeDefinition fully supports these categories
- ✅ TransformationContext has resolution cascade architecture
- ✅ TransformationIndices provides table metadata for %ROWTYPE resolution
- ⏳ Missing: Implementation of resolution logic in VisitVariable_declaration

---

## 1. Infrastructure Already in Place

### 1.1 TypeCategory Enum (Lines 21-122)
**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/inline/TypeCategory.java`

**Status:** ✅ COMPLETE

Both categories are fully defined with comprehensive documentation:

```java
/**
 * %ROWTYPE - Reference to a table's row structure.
 *
 * Oracle example: v_emp employees%ROWTYPE;
 * PostgreSQL transformation: jsonb object with table columns
 * Resolution: Table structure obtained from TransformationIndices metadata.
 */
ROWTYPE,

/**
 * %TYPE - Reference to another variable's or column's type.
 *
 * Oracle example: v_salary employees.salary%TYPE;
 * PostgreSQL transformation: Resolve to underlying type, then apply same rules.
 */
TYPE_REFERENCE
```

### 1.2 InlineTypeDefinition Class (Lines 1-355)
**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/inline/InlineTypeDefinition.java`

**Status:** ✅ COMPLETE

The class fully supports ROWTYPE and TYPE_REFERENCE:
- Line 93-95: `category` field can be TypeCategory.ROWTYPE or TYPE_REFERENCE
- Lines 92-102: `elementType` field (for %TYPE references)
- Lines 104-108: `fields` field (for %ROWTYPE - list of columns)
- Lines 194-213: `getPostgresType()` returns "jsonb" for Phase 1
- Lines 215-251: `getInitializer()` returns '{}' for ROWTYPE, handles TYPE_REFERENCE

**Helper Methods:**
```java
isRecord() // Returns true for ROWTYPE (line 269-272)
```

### 1.3 ANTLR Grammar Support
**Location:** `src/main/antlr4/me/christianrobert/orapgsync/antlr/PlSqlParser.g4` (lines 7238-7241)

**Status:** ✅ COMPLETE

The grammar already parses %ROWTYPE and %TYPE:

```antlr
type_spec
    : datatype
    | REF? type_name (PERCENT_ROWTYPE | PERCENT_TYPE)?
    ;
```

**Lexer Support (PlSqlLexer.g4, lines 1341-1342):**
```antlr
PERCENT_ROWTYPE : '%' SPACE* 'ROWTYPE';
PERCENT_TYPE    : '%' SPACE* 'TYPE';
```

### 1.4 TransformationContext Resolution Cascade (Lines 481-522)
**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/context/TransformationContext.java`

**Status:** ✅ COMPLETE

Three-level resolution cascade already implemented:

```java
public InlineTypeDefinition resolveInlineType(String typeName) {
    // Level 1: Block-level (function-local inline types)
    InlineTypeDefinition blockLevelType = getInlineType(typeName);
    if (blockLevelType != null) return blockLevelType;

    // Level 2: Package-level (from PackageContext)
    if (currentPackageName != null) {
        PackageContext pkgCtx = getPackageContext(currentPackageName);
        if (pkgCtx != null && pkgCtx.hasType(typeName)) {
            return pkgCtx.getType(typeName);
        }
    }

    // Level 3: Schema-level (from TransformationIndices) - FUTURE
    // TODO: Implement when schema-level type support is added
    return null;
}
```

**Note:** Level 3 (schema-level) is NOT YET IMPLEMENTED. For %ROWTYPE and %TYPE, we need:
- Method to resolve table names → get columns from TransformationIndices
- Method to resolve column type references

### 1.5 TransformationIndices Metadata (Lines 34-248)
**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/context/TransformationIndices.java`

**Status:** ✅ COMPLETE

Table column metadata is available:

```java
private final Map<String, Map<String, ColumnTypeInfo>> tableColumns;

// Access method (line 71)
public ColumnTypeInfo getColumnType(String qualifiedTable, String columnName)

// Field info (lines 209-247)
public static class ColumnTypeInfo {
    public String getTypeName()
    public String getTypeOwner()
    public String getQualifiedType()
    public boolean isCustomType()
}
```

**Key Features:**
- Maps qualified table names (e.g., "hr.employees") to column maps
- Each column maps to ColumnTypeInfo with type name and owner
- Supports both built-in types (typeOwner = null) and custom types

### 1.6 VisitVariable_declaration (Lines 64-165)
**Location:** `src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitVariable_declaration.java`

**Status:** ⏳ PARTIAL - Needs %ROWTYPE and %TYPE resolution

Current implementation (lines 78-95):
```java
// STEP 3: Check if this is an inline type
String oracleType = ctx.type_spec().getText();
InlineTypeDefinition inlineType = b.getContext().resolveInlineType(oracleType);

String postgresType;
String autoInitializer = null;

if (inlineType != null) {
    postgresType = "jsonb";
    if (ctx.default_value_part() == null) {
        autoInitializer = inlineType.getInitializer();
    }
} else {
    // REGULAR TYPE: Convert using TypeConverter
    postgresType = TypeConverter.toPostgre(oracleType);
}
```

**What's Missing:**
1. Detection of %ROWTYPE syntax in type_spec (e.g., "employees%ROWTYPE")
2. Detection of %TYPE syntax in type_spec (e.g., "employees.salary%TYPE")
3. Resolution logic to extract actual type from table/column metadata
4. Field definition extraction for ROWTYPE

---

## 2. What Needs to Be Implemented

### 2.1 %ROWTYPE Resolution Logic

**Problem:** When we see `v_emp employees%ROWTYPE`, we need to:
1. Parse the table reference (employees)
2. Look up table structure in TransformationIndices
3. Create FieldDefinition list from table columns
4. Create InlineTypeDefinition with TypeCategory.ROWTYPE

**Implementation Location:** VisitVariable_declaration.java (new method)

**Pseudocode:**
```java
private static InlineTypeDefinition resolveRowtypeReference(
        String rowtypeReference,  // e.g., "employees%ROWTYPE"
        TransformationContext context) {
    
    // Step 1: Extract table name (before %ROWTYPE)
    String tableName = rowtypeReference.replace("%ROWTYPE", "").trim();
    
    // Step 2: Resolve any synonyms
    String qualifiedTable = context.resolveSynonym(tableName);
    if (qualifiedTable == null) {
        qualifiedTable = context.getCurrentSchema() + "." + tableName;
    }
    
    // Step 3: Get table columns from TransformationIndices
    Map<String, ColumnTypeInfo> columns = 
        context.getIndices().getTableColumns(qualifiedTable);
    
    if (columns == null) {
        return null;  // Table not found
    }
    
    // Step 4: Create FieldDefinition list
    List<FieldDefinition> fields = new ArrayList<>();
    for (String columnName : columns.keySet()) {
        ColumnTypeInfo columnInfo = columns.get(columnName);
        String oracleType = columnInfo.getQualifiedType();
        String postgresType = TypeConverter.toPostgre(oracleType);
        fields.add(new FieldDefinition(columnName, oracleType, postgresType));
    }
    
    // Step 5: Create and return InlineTypeDefinition
    return new InlineTypeDefinition(
        rowtypeReference,
        TypeCategory.ROWTYPE,
        null,  // No elementType for ROWTYPE
        fields,
        ConversionStrategy.JSONB,
        null   // No sizeLimit
    );
}
```

**Test Cases Needed:**
- Basic %ROWTYPE: `v_emp employees%ROWTYPE`
- Qualified table: `v_emp hr.employees%ROWTYPE`
- With schema synonym resolution
- Non-existent table (error handling)
- Field access: `v_emp.empno` → `(v_emp->>'empno')::numeric`
- Field assignment: `v_emp.empno := 100` → `v_emp := jsonb_set(...)`

### 2.2 %TYPE Resolution Logic

**Problem:** When we see `v_salary employees.salary%TYPE`, we need to:
1. Parse the column reference (employees.salary)
2. Look up column type in TransformationIndices
3. Resolve that type (might itself be %ROWTYPE or another %TYPE!)
4. Create appropriate InlineTypeDefinition

**Implementation Location:** VisitVariable_declaration.java (new method)

**Pseudocode:**
```java
private static InlineTypeDefinition resolveTypeReference(
        String typeReference,  // e.g., "employees.salary%TYPE" or "v_salary%TYPE"
        TransformationContext context) {
    
    // Step 1: Extract the reference (before %TYPE)
    String reference = typeReference.replace("%TYPE", "").trim();
    
    // Step 2a: Column reference (table.column)?
    if (reference.contains(".")) {
        String[] parts = reference.split("\\.");
        if (parts.length == 2) {
            String tableName = parts[0];
            String columnName = parts[1];
            return resolveColumnTypeReference(tableName, columnName, context);
        }
    }
    
    // Step 2b: Variable reference (v_var)?
    // This requires looking up in current variable scope
    VariableDefinition varDef = context.lookupVariable(reference);
    if (varDef != null) {
        // Recursively resolve the variable's type
        return context.resolveInlineType(varDef.getOracleType());
    }
    
    return null;  // Unknown reference
}

private static InlineTypeDefinition resolveColumnTypeReference(
        String tableName,
        String columnName,
        TransformationContext context) {
    
    // Step 1: Resolve table name with synonyms
    String qualifiedTable = context.resolveSynonym(tableName);
    if (qualifiedTable == null) {
        qualifiedTable = context.getCurrentSchema() + "." + tableName;
    }
    
    // Step 2: Look up column type
    ColumnTypeInfo columnInfo = context.getColumnType(qualifiedTable, columnName);
    if (columnInfo == null) {
        return null;  // Column not found
    }
    
    // Step 3: Create TYPE_REFERENCE definition
    // This is a simple wrapper around the underlying type
    return new InlineTypeDefinition(
        tableName + "." + columnName + "%TYPE",
        TypeCategory.TYPE_REFERENCE,
        columnInfo.getQualifiedType(),  // Store the underlying type
        null,  // No fields for TYPE_REFERENCE
        ConversionStrategy.JSONB,
        null
    );
}
```

**Test Cases Needed:**
- Basic %TYPE: `v_salary employees.salary%TYPE`
- Qualified table: `v_salary hr.employees.salary%TYPE`
- Variable %TYPE: `v_copy v_salary%TYPE`
- Chained %TYPE: `v_copy2 v_copy%TYPE` (reference to a %TYPE reference)
- With different column types (NUMBER, VARCHAR2, DATE, etc.)

### 2.3 Integration Points

**Where to Hook:**

In VisitVariable_declaration.java, around line 78-95:

```java
// Current code:
String oracleType = ctx.type_spec().getText();
InlineTypeDefinition inlineType = b.getContext().resolveInlineType(oracleType);

// New code should be:
String oracleType = ctx.type_spec().getText();

// Check for %ROWTYPE
if (oracleType.contains("%ROWTYPE")) {
    inlineType = resolveRowtypeReference(oracleType, b.getContext());
} 
// Check for %TYPE
else if (oracleType.contains("%TYPE")) {
    inlineType = resolveTypeReference(oracleType, b.getContext());
}
// Fall back to existing resolution
else {
    inlineType = b.getContext().resolveInlineType(oracleType);
}
```

---

## 3. Data Structures Summary

### 3.1 TypeCategory Enum (Already Defined)
```
RECORD          - TYPE t IS RECORD (...)
TABLE_OF        - TYPE t IS TABLE OF ...
VARRAY          - TYPE t IS VARRAY(n) OF ...
INDEX_BY        - TYPE t IS TABLE OF ... INDEX BY ...
ROWTYPE         - employees%ROWTYPE ✅ DEFINED
TYPE_REFERENCE  - employees.salary%TYPE ✅ DEFINED
```

### 3.2 InlineTypeDefinition Fields (Already Defined)
```
typeName        - "employees%ROWTYPE" or "employees.salary%TYPE"
category        - TypeCategory.ROWTYPE or TYPE_REFERENCE
elementType     - Underlying type for %TYPE (e.g., "NUMBER")
fields          - Column definitions for %ROWTYPE (e.g., [empno, ename, salary])
strategy        - ConversionStrategy.JSONB (Phase 1)
```

### 3.3 FieldDefinition (Already Defined)
```
fieldName       - Column name (e.g., "empno", "ename")
oracleType      - Oracle type (e.g., "NUMBER", "VARCHAR2")
postgresType    - PostgreSQL type (e.g., "numeric", "text")
```

---

## 4. Key Implementation Decisions

### 4.1 %ROWTYPE Phase 1 Strategy
- **Transformation:** `v_emp employees%ROWTYPE` → `v_emp jsonb := '{}'::jsonb`
- **Why jsonb:** 
  - Consistent with existing inline type approach
  - Supports all column types (built-in, custom, LOBs)
  - Field access/assignment same pattern as RECORD types
- **Advantages:**
  - No need to create temporary composite types
  - Handles schema-level custom types transparently
  - Extensible for future %TYPE chains

### 4.2 %TYPE Phase 1 Strategy
- **Transformation:** `v_salary employees.salary%TYPE` → determine underlying type, then apply
  - If underlying is NUMBER: `v_salary numeric;`
  - If underlying is VARCHAR2: `v_salary text;`
  - If underlying is custom type: Apply normal type resolution
- **Why resolve immediately:** 
  - %TYPE is just an alias - resolve once at declaration
  - Avoids carrying %TYPE through expression transformation
  - Simplifies downstream logic

### 4.3 Type Resolution Cascade (Deferred Level 3)
**Current Status:** Levels 1 and 2 working (block and package types)

**What's missing:** Level 3 - Schema-level type resolution
```
Level 1: Block-level types (implemented) ✅
Level 2: Package-level types (implemented) ✅
Level 3: Schema-level types (FUTURE)
  - %ROWTYPE from tables
  - %TYPE from columns
  - References to schema-level RECORD types
```

---

## 5. Dependencies and Prerequisites

### 5.1 Already Available
- ✅ ANTLR parser recognizes %ROWTYPE and %TYPE syntax
- ✅ TypeCategory enum has ROWTYPE and TYPE_REFERENCE
- ✅ InlineTypeDefinition supports these categories
- ✅ TransformationContext has resolution method
- ✅ TransformationIndices has table metadata
- ✅ FieldDefinition supports column metadata
- ✅ TypeConverter supports Oracle→PostgreSQL type mapping

### 5.2 Need to Add
- ⏳ Resolution methods in VisitVariable_declaration
- ⏳ %ROWTYPE detection logic (check for "%ROWTYPE" in string)
- ⏳ %TYPE detection logic (check for "%TYPE" in string)
- ⏳ Table lookup from TransformationIndices
- ⏳ Column type lookup from TransformationIndices
- ⏳ Error handling for non-existent tables/columns

### 5.3 Test Infrastructure Ready
- ✅ Test base classes for PL/SQL transformation
- ✅ Integration test framework (PostgreSQL Testcontainers)
- ✅ Variable scope tracking (for RHS field access)
- ✅ RECORD type tests as reference implementation

---

## 6. Potential Issues and Mitigations

### 6.1 Issue: Table/Column Not Found in Metadata
**Scenario:** Variable declares `v_emp unknown_table%ROWTYPE` but table not in TransformationIndices

**Mitigation:**
- Gracefully fall back to treating as regular type (TypeConverter lookup)
- Log warning but don't fail
- Alternative: Generate dummy ROWTYPE with warning

**Code pattern:**
```java
if (columns == null) {
    // Table not found - fallback to TypeConverter or warn
    return null;  // Will trigger TypeConverter fallback
}
```

### 6.2 Issue: Circular %TYPE References
**Scenario:** `v_a v_b%TYPE; v_b v_a%TYPE;` (circular reference)

**Mitigation:**
- Implement cycle detection in resolution
- Limit recursion depth (max 10 levels)
- Throw exception with helpful message

**Code pattern:**
```java
private static final int MAX_TYPE_RESOLUTION_DEPTH = 10;

private static InlineTypeDefinition resolveTypeReference(
        String typeReference,
        TransformationContext context,
        int depth) {
    if (depth > MAX_TYPE_RESOLUTION_DEPTH) {
        throw new IllegalArgumentException(
            "Circular type reference detected: " + typeReference);
    }
    // ... resolution logic ...
    return resolveTypeReference(..., context, depth + 1);
}
```

### 6.3 Issue: Case Sensitivity
**Scenario:** `v_emp EMPLOYEES%ROWTYPE` (table name uppercase)

**Mitigation:**
- Normalize all names to lowercase for lookup
- Already done in TransformationIndices (uses lowercase keys)

**Code pattern:**
```java
String qualifiedTable = 
    (context.getCurrentSchema() + "." + tableName).toLowerCase();
```

### 6.4 Issue: Schema-Qualified References
**Scenario:** `v_emp hr.employees%ROWTYPE` (explicit schema)

**Mitigation:**
- Parse schema.table format
- Handle both qualified and unqualified references

**Code pattern:**
```java
String tableName = rowtypeReference.replace("%ROWTYPE", "").trim();
String qualifiedTable;

if (tableName.contains(".")) {
    qualifiedTable = tableName.toLowerCase();
} else {
    qualifiedTable = (context.getCurrentSchema() + "." + tableName).toLowerCase();
}
```

---

## 7. Implementation Checklist

### Phase 1: %ROWTYPE Support
- [ ] Create `resolveRowtypeReference()` method in VisitVariable_declaration
- [ ] Add %ROWTYPE detection logic (contains "%ROWTYPE")
- [ ] Extract table name from reference
- [ ] Resolve synonyms using TransformationContext
- [ ] Build qualified table name (schema.table)
- [ ] Query TransformationIndices for table columns
- [ ] Build FieldDefinition list with type conversion
- [ ] Create InlineTypeDefinition with TypeCategory.ROWTYPE
- [ ] Test with basic %ROWTYPE (e.g., `v_emp employees%ROWTYPE`)
- [ ] Test with qualified table (e.g., `v_emp hr.employees%ROWTYPE`)
- [ ] Test with non-existent table (error handling)
- [ ] Integration test: %ROWTYPE field access and assignment

### Phase 2: %TYPE Support
- [ ] Create `resolveTypeReference()` method in VisitVariable_declaration
- [ ] Add %TYPE detection logic (contains "%TYPE")
- [ ] Support column references (table.column%TYPE)
- [ ] Support variable references (v_var%TYPE)
- [ ] Implement recursion with depth limit
- [ ] Detect and prevent circular references
- [ ] Create InlineTypeDefinition with TypeCategory.TYPE_REFERENCE
- [ ] Test with column %TYPE (e.g., `v_salary employees.salary%TYPE`)
- [ ] Test with variable %TYPE (e.g., `v_copy v_salary%TYPE`)
- [ ] Test with different column types
- [ ] Test error cases (circular, non-existent)

### Phase 3: Integration and Testing
- [ ] Create `PostgresRowtypeTransformationTest` (unit tests)
- [ ] Create `PostgresTypeReferenceTransformationTest` (unit tests)
- [ ] Create `PostgresRowtypeValidationTest` (integration tests)
- [ ] Create `PostgresTypeReferenceValidationTest` (integration tests)
- [ ] Update inline type test cases file
- [ ] Regression testing (ensure zero new failures)

---

## 8. Example Transformations

### %ROWTYPE Example

**Oracle:**
```sql
DECLARE
    v_emp employees%ROWTYPE;
BEGIN
    v_emp.empno := 100;
    v_emp.ename := 'Smith';
    DBMS_OUTPUT.PUT_LINE(v_emp.salary);
END;
```

**PostgreSQL (After Implementation):**
```sql
DECLARE
    v_emp jsonb;
BEGIN
    v_emp := '{}'::jsonb;
    v_emp := jsonb_set(v_emp, '{empno}', to_jsonb(100));
    v_emp := jsonb_set(v_emp, '{ename}', to_jsonb('Smith'::text));
    PERFORM oracle_compat.dbms_output__put_line((v_emp->>'salary')::numeric);
END;
```

### %TYPE Example

**Oracle:**
```sql
DECLARE
    v_salary employees.salary%TYPE;
    v_copy v_salary%TYPE;
BEGIN
    v_salary := 50000;
    v_copy := v_salary;
END;
```

**PostgreSQL (After Implementation):**
```sql
DECLARE
    v_salary numeric;
    v_copy numeric;
BEGIN
    v_salary := 50000;
    v_copy := v_salary;
END;
```

---

## 9. References

**Existing Implementation Plans:**
- INLINE_TYPE_IMPLEMENTATION_PLAN.md (Phases 1A-1D complete)
- VARIABLE_SCOPE_TRACKING_PLAN.md (deterministic variable detection)

**Key Source Files:**
- `transformer/inline/TypeCategory.java` - Enum definition (ROWTYPE, TYPE_REFERENCE)
- `transformer/inline/InlineTypeDefinition.java` - Data model
- `transformer/inline/FieldDefinition.java` - Column metadata
- `transformer/builder/VisitVariable_declaration.java` - Where to implement
- `transformer/context/TransformationContext.java` - Resolution methods
- `transformer/context/TransformationIndices.java` - Table metadata access
- `antlr/PlSqlParser.g4` - Grammar (already supports syntax)
- `antlr/PlSqlLexer.g4` - Lexer (tokens already defined)

---

## Conclusion

The codebase is **well-prepared** for %ROWTYPE and %TYPE implementation. All necessary infrastructure exists:

1. ✅ Type categories defined (ROWTYPE, TYPE_REFERENCE)
2. ✅ ANTLR grammar parses the syntax
3. ✅ Data models support all needed fields
4. ✅ Table metadata available in TransformationIndices
5. ✅ Type resolution cascade architecture ready
6. ✅ Variable scope tracking (for %TYPE variable references)

**What remains:** Implement the ~200-300 lines of resolution logic in VisitVariable_declaration to:
- Detect %ROWTYPE and %TYPE syntax
- Query table/column metadata
- Build appropriate InlineTypeDefinition objects

This is a straightforward implementation with clear dependencies and no architectural gaps.
