# Oracle to PostgreSQL Trigger Transformation - Implementation Plan

**Status:** 📋 READY FOR IMPLEMENTATION
**Created:** 2025-11-10
**Estimated Effort:** 3-5 days
**Framework Status:** ✅ Shell jobs complete, metadata models ready

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Oracle vs PostgreSQL Trigger Differences](#oracle-vs-postgresql-trigger-differences)
3. [Implementation Architecture](#implementation-architecture)
4. [Phase 1: Oracle Trigger Extraction](#phase-1-oracle-trigger-extraction)
5. [Phase 2: PostgreSQL Trigger Implementation](#phase-2-postgresql-trigger-implementation)
6. [Phase 3: PostgreSQL Trigger Verification](#phase-3-postgresql-trigger-verification)
7. [Testing Strategy](#testing-strategy)
8. [Implementation Timeline](#implementation-timeline)
9. [Risk Assessment](#risk-assessment)

---

## Executive Summary

### Objective

Migrate Oracle database triggers to PostgreSQL with full PL/SQL to PL/pgSQL transformation.

### Why No Stub Phase?

Unlike views and functions, triggers **do not require stub creation**:
- ✅ Triggers are not referenced by other database objects (no circular dependencies)
- ✅ Triggers fire automatically on DML events (no explicit calls from other code)
- ✅ Other objects don't need to "know about" triggers to compile successfully
- ✅ Direct extraction → transformation → creation is sufficient

### Key Challenges

1. **Two-Part Creation Model**: PostgreSQL separates trigger functions from trigger definitions
2. **Colon Syntax Transformation**: Oracle `:NEW`/`:OLD` → PostgreSQL `NEW`/`OLD`
3. **Return Value Requirements**: PostgreSQL trigger functions MUST return `NEW`/`OLD`/`NULL`
4. **PL/SQL Transformation**: Reuse existing ANTLR-based transformation infrastructure
5. **WHEN Clause Transformation**: Oracle uses `:NEW.col` in WHEN, PostgreSQL uses `NEW.col`
6. **Statement-Level Transition Tables**: Oracle and PostgreSQL handle differently

### Expected Coverage

- **Simple triggers** (95% coverage): BEFORE/AFTER row-level triggers with basic PL/SQL
- **Complex triggers** (85% coverage): INSTEAD OF, statement-level, compound triggers
- **Advanced features** (70% coverage): Autonomous transactions, follows clause (unsupported in PostgreSQL)

---

## Oracle vs PostgreSQL Trigger Differences

### Critical Conceptual Differences

| Feature | Oracle | PostgreSQL | Transformation Required |
|---------|--------|------------|------------------------|
| **Structure** | Single CREATE TRIGGER with body | Separate function + CREATE TRIGGER | ✅ Split into two DDL statements |
| **Correlation Names** | `:NEW`, `:OLD` | `NEW`, `OLD` | ✅ Remove colons |
| **Return Value** | No return required | MUST return `NEW`/`OLD`/`NULL` | ✅ Add RETURN statement |
| **Language Declaration** | Implicit PL/SQL | Explicit `LANGUAGE plpgsql` | ✅ Add language clause |
| **WHEN Clause** | `:NEW.col > 10` | `NEW.col > 10` | ✅ Remove colons |
| **Statement-Level :NEW/:OLD** | Supported (mutating table) | Use transition tables | ✅ Transform to REFERENCING clause |
| **Autonomous Transactions** | `PRAGMA AUTONOMOUS_TRANSACTION` | Not supported | ⚠️ Warn user (dblink workaround) |
| **FOLLOWS Clause** | Order trigger execution | Not supported | ⚠️ Skip clause, warn user |

### Oracle Trigger Example

```sql
CREATE OR REPLACE TRIGGER update_emp_audit
  BEFORE UPDATE ON employees
  FOR EACH ROW
  WHEN (NEW.salary > OLD.salary)
BEGIN
  INSERT INTO audit_log (emp_id, old_sal, new_sal, change_date)
  VALUES (:OLD.employee_id, :OLD.salary, :NEW.salary, SYSDATE);
END;
```

### Equivalent PostgreSQL Trigger

```sql
-- Step 1: Create trigger function
CREATE OR REPLACE FUNCTION hr.update_emp_audit_func()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO hr.audit_log (emp_id, old_sal, new_sal, change_date)
  VALUES (OLD.employee_id, OLD.salary, NEW.salary, CURRENT_TIMESTAMP);

  RETURN NEW;  -- REQUIRED: Return NEW for BEFORE triggers
END;
$$ LANGUAGE plpgsql;

-- Step 2: Create trigger
CREATE TRIGGER update_emp_audit
  BEFORE UPDATE ON hr.employees
  FOR EACH ROW
  WHEN (NEW.salary > OLD.salary)
  EXECUTE FUNCTION hr.update_emp_audit_func();
```

### Return Value Logic

**CRITICAL RULE:** PostgreSQL trigger functions MUST return appropriate value:

| Trigger Timing | Trigger Level | Return Value | Effect |
|---------------|---------------|--------------|--------|
| BEFORE | ROW | `NEW` | Modified row will be processed |
| BEFORE | ROW | `OLD` | Original row will be processed |
| BEFORE | ROW | `NULL` | Skip operation for this row |
| AFTER | ROW | Ignored | Return `NULL` or `NEW` (convention) |
| BEFORE/AFTER | STATEMENT | Ignored | Return `NULL` (convention) |
| INSTEAD OF | ROW | Ignored | Return `NULL` or `NEW` (convention) |

**Transformation Logic:**
- Oracle triggers don't have explicit returns → Add `RETURN NEW;` before END
- BEFORE ROW triggers: `RETURN NEW;` (most common)
- AFTER ROW triggers: `RETURN NULL;` (convention, ignored anyway)
- Statement-level triggers: `RETURN NULL;`

---

## Implementation Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Trigger Migration Flow                        │
└─────────────────────────────────────────────────────────────────┘

1. EXTRACTION (OracleTriggerExtractionJob)
   ↓
   Oracle ALL_TRIGGERS → Query trigger metadata + body
   ↓
   StateService.setOracleTriggerMetadata(List<TriggerMetadata>)

2. TRANSFORMATION & CREATION (PostgresTriggerImplementationJob)
   ↓
   For each TriggerMetadata:
     ├─→ Parse trigger body with ANTLR (reuse existing PlSqlParser)
     ├─→ Transform PL/SQL → PL/pgSQL (reuse PostgresCodeBuilder)
     ├─→ Remove colons from :NEW/:OLD (ColonReferenceTransformer)
     ├─→ Add RETURN statement (TriggerReturnInjector)
     ├─→ Generate trigger function DDL (TriggerFunctionGenerator)
     ├─→ Generate trigger DDL (TriggerDefinitionGenerator)
     ├─→ Execute both DDL statements in PostgreSQL
     └─→ Track result in TriggerImplementationResult

3. VERIFICATION (PostgresTriggerVerificationJob)
   ↓
   Query PostgreSQL pg_trigger, pg_proc → Extract created triggers
   ↓
   StateService.setPostgresTriggerMetadata(List<TriggerMetadata>)
   ↓
   Frontend displays comparison: Oracle vs PostgreSQL trigger counts
```

### Module Structure

```
trigger/
├── job/
│   ├── OracleTriggerExtractionJob.java       # Extracts from Oracle
│   ├── PostgresTriggerImplementationJob.java # Transforms and creates
│   └── PostgresTriggerVerificationJob.java   # Verifies in PostgreSQL
├── service/
│   ├── OracleTriggerExtractor.java           # Oracle metadata extraction logic
│   └── PostgresTriggerExtractor.java         # PostgreSQL metadata extraction logic
├── transformer/
│   ├── TriggerTransformer.java               # Main transformation orchestrator
│   ├── ColonReferenceTransformer.java        # :NEW/:OLD → NEW/OLD
│   ├── TriggerReturnInjector.java            # Adds RETURN statements
│   ├── TriggerFunctionGenerator.java         # Generates CREATE FUNCTION DDL
│   └── TriggerDefinitionGenerator.java       # Generates CREATE TRIGGER DDL
└── rest/
    └── TriggerResource.java                  # REST endpoints (already exists)
```

### Reuse Existing Infrastructure

**Already Available (No New Code Needed):**
- ✅ ANTLR parser for PL/SQL (`AntlrParser`, `PlSqlParser.g4`)
- ✅ PostgresCodeBuilder with 20+ PL/SQL visitors (IF, LOOP, exceptions, etc.)
- ✅ TransformationContext and TransformationIndices (metadata access)
- ✅ TriggerMetadata model (schema, name, table, type, event, level, body, when clause)
- ✅ TriggerImplementationResult (success/skip/error tracking)
- ✅ StateService storage (getter/setter methods for trigger metadata)

**New Components Needed:**
1. `ColonReferenceTransformer` - String replacement for `:NEW`/`:OLD` → `NEW`/`OLD`
2. `TriggerReturnInjector` - AST analysis to add RETURN statements before END
3. `TriggerFunctionGenerator` - Format trigger function DDL
4. `TriggerDefinitionGenerator` - Format CREATE TRIGGER DDL
5. `OracleTriggerExtractor` - Extract from Oracle ALL_TRIGGERS
6. `PostgresTriggerExtractor` - Verify from PostgreSQL pg_trigger

---

## Phase 1: Oracle Trigger Extraction

### Overview

Extract trigger metadata and source code from Oracle data dictionary.

### Data Dictionary Sources

**Primary Table: ALL_TRIGGERS**

```sql
SELECT
  owner,              -- Schema name
  trigger_name,       -- Trigger name
  table_owner,        -- Table schema
  table_name,         -- Target table
  trigger_type,       -- BEFORE/AFTER + STATEMENT/EACH ROW
  triggering_event,   -- INSERT/UPDATE/DELETE (or combinations)
  status,             -- ENABLED/DISABLED
  trigger_body,       -- PL/SQL source code (CLOB)
  when_clause,        -- Optional WHEN condition
  description         -- Full DDL (optional)
FROM all_triggers
WHERE owner IN (?, ?, ...)
  AND base_object_type = 'TABLE'  -- Exclude view triggers for Phase 1
ORDER BY owner, trigger_name;
```

**Parsing TRIGGER_TYPE Field:**

Oracle encodes timing + level in single field:
- `BEFORE STATEMENT` → type="BEFORE", level="STATEMENT"
- `BEFORE EACH ROW` → type="BEFORE", level="ROW"
- `AFTER STATEMENT` → type="AFTER", level="STATEMENT"
- `AFTER EACH ROW` → type="AFTER", level="ROW"
- `INSTEAD OF` → type="INSTEAD OF", level="ROW" (views only)

**Parsing TRIGGERING_EVENT Field:**

Can be combinations:
- `INSERT` → event="INSERT"
- `UPDATE` → event="UPDATE"
- `DELETE` → event="DELETE"
- `INSERT OR UPDATE` → event="INSERT OR UPDATE"
- `UPDATE OR DELETE` → event="UPDATE OR DELETE"
- `INSERT OR UPDATE OR DELETE` → event="INSERT OR UPDATE OR DELETE"

### OracleTriggerExtractor Implementation

**Class:** `trigger/service/OracleTriggerExtractor.java`

**Responsibilities:**
1. Query ALL_TRIGGERS for specified schemas
2. Parse TRIGGER_TYPE into type + level
3. Parse TRIGGERING_EVENT into event string
4. Extract WHEN_CLAUSE (remove "WHEN (" prefix and ")" suffix if present)
5. Clean trigger body (remove CREATE OR REPLACE wrapper if present in description)
6. Return List<TriggerMetadata>

**Key Methods:**

```java
public class OracleTriggerExtractor {

    public List<TriggerMetadata> extractTriggers(Connection conn, List<String> schemas)
        throws SQLException;

    private TriggerMetadata extractSingleTrigger(ResultSet rs) throws SQLException;

    private void parseTriggerType(String triggerType, TriggerMetadata metadata);
    // "BEFORE EACH ROW" → type="BEFORE", level="ROW"

    private String parseTriggerEvent(String triggeringEvent);
    // "INSERT OR UPDATE" → "INSERT OR UPDATE"

    private String cleanWhenClause(String whenClause);
    // "WHEN (NEW.salary > 1000)" → "NEW.salary > 1000"

    private String cleanTriggerBody(String triggerBody, String description);
    // Remove CREATE OR REPLACE wrapper, keep only BEGIN...END block
}
```

### OracleTriggerExtractionJob Implementation

**Class:** `trigger/job/OracleTriggerExtractionJob.java`

**Workflow:**
1. Get schemas from StateService (or config)
2. Filter valid schemas (exclude system schemas)
3. Create OracleTriggerExtractor instance
4. Extract triggers for all schemas
5. Save results to StateService.setOracleTriggerMetadata()
6. Report progress and summary

**Progress Reporting:**
- 0-10%: Connection and initialization
- 10-90%: Processing schemas (incremental progress per schema)
- 90-100%: Saving results and finalization

**Error Handling:**
- Individual trigger extraction errors → Log warning, continue
- Schema-level errors → Log error, continue with next schema
- Connection errors → Fail entire job

### Testing Phase 1

**Unit Tests:** `OracleTriggerExtractorTest.java`
- Test parsing TRIGGER_TYPE field (6 variations)
- Test parsing TRIGGERING_EVENT field (7 variations)
- Test cleaning WHEN clause
- Test cleaning trigger body (with/without wrapper)

**Integration Tests:** `OracleTriggerExtractionJobTest.java`
- Mock Oracle connection with test data
- Verify correct metadata extraction
- Verify StateService storage
- Test error handling (invalid data, connection failure)

---

## Phase 2: PostgreSQL Trigger Implementation

### Overview

Transform Oracle trigger definitions to PostgreSQL and create them in target database.

### Transformation Pipeline

```
Oracle TriggerMetadata
  ↓
1. Extract trigger body (PL/SQL)
  ↓
2. Transform trigger body:
   a. Parse with ANTLR (reuse existing parser)
   b. Transform PL/SQL → PL/pgSQL (reuse PostgresCodeBuilder)
   c. Remove colons: :NEW/:OLD → NEW/OLD
   d. Inject RETURN statement before END
  ↓
3. Generate trigger function DDL
  ↓
4. Generate CREATE TRIGGER DDL
  ↓
5. Execute both in PostgreSQL (transaction)
  ↓
6. Track result in TriggerImplementationResult
```

### Component 1: ColonReferenceTransformer

**Purpose:** Remove colons from `:NEW` and `:OLD` references.

**Class:** `trigger/transformer/ColonReferenceTransformer.java`

**Algorithm:**
- **Simple approach:** String replacement using regex
- Match `:NEW` and `:OLD` (case-insensitive)
- Replace with `NEW` and `OLD`
- Handle edge cases: String literals (skip), comments (skip)

**Implementation:**

```java
public class ColonReferenceTransformer {

    /**
     * Removes colons from :NEW and :OLD references.
     *
     * Algorithm:
     * 1. Use regex to find :NEW and :OLD (word boundaries)
     * 2. Replace with NEW and OLD
     * 3. Case-preserving: :new → new, :NEW → NEW
     *
     * Note: Does NOT handle string literals or comments.
     * Rely on CodeCleaner.removeComments() before calling this.
     */
    public String removeColonReferences(String plsqlCode) {
        // Pattern: word boundary + : + (NEW|OLD|new|old) + word boundary
        String result = plsqlCode;

        // Replace :NEW with NEW (case-insensitive)
        result = result.replaceAll("\\b:NEW\\b", "NEW");
        result = result.replaceAll("\\b:new\\b", "new");
        result = result.replaceAll("\\b:New\\b", "New");

        // Replace :OLD with OLD (case-insensitive)
        result = result.replaceAll("\\b:OLD\\b", "OLD");
        result = result.replaceAll("\\b:old\\b", "old");
        result = result.replaceAll("\\b:Old\\b", "Old");

        return result;
    }
}
```

**Why Regex Instead of AST?**
- ✅ Simple and fast
- ✅ Oracle `:NEW`/`:OLD` are special syntax (not variables)
- ✅ ANTLR grammar already handles them correctly
- ✅ Regex is sufficient given CodeCleaner removes comments first

### Component 2: TriggerReturnInjector

**Purpose:** Add `RETURN NEW;` or `RETURN NULL;` before END statement.

**Class:** `trigger/transformer/TriggerReturnInjector.java`

**Algorithm:**
1. Check if trigger body already has RETURN statement
2. If not, determine return value based on trigger type/level:
   - BEFORE ROW → `RETURN NEW;`
   - AFTER ROW → `RETURN NULL;`
   - STATEMENT-level → `RETURN NULL;`
   - INSTEAD OF → `RETURN NULL;`
3. Insert RETURN before final END

**Implementation:**

```java
public class TriggerReturnInjector {

    /**
     * Adds RETURN statement before END if not already present.
     *
     * @param plpgsqlBody Transformed PL/pgSQL body
     * @param triggerType BEFORE/AFTER/INSTEAD OF
     * @param triggerLevel ROW/STATEMENT
     * @return Body with RETURN statement
     */
    public String injectReturn(String plpgsqlBody, String triggerType, String triggerLevel) {
        // Check if already has RETURN
        if (hasReturnStatement(plpgsqlBody)) {
            return plpgsqlBody;  // Already has return
        }

        // Determine return value
        String returnValue = determineReturnValue(triggerType, triggerLevel);

        // Find last END and insert RETURN before it
        String returnStatement = "  RETURN " + returnValue + ";\n";
        return insertBeforeLastEnd(plpgsqlBody, returnStatement);
    }

    private boolean hasReturnStatement(String body) {
        // Simple check: does body contain "RETURN" (case-insensitive)?
        return body.toUpperCase().contains("RETURN ");
    }

    private String determineReturnValue(String type, String level) {
        if ("BEFORE".equalsIgnoreCase(type) && "ROW".equalsIgnoreCase(level)) {
            return "NEW";
        } else {
            return "NULL";
        }
    }

    private String insertBeforeLastEnd(String body, String returnStatement) {
        // Find last occurrence of "END;" or "END "
        int lastEndIndex = findLastEnd(body);
        if (lastEndIndex == -1) {
            throw new IllegalArgumentException("No END statement found in trigger body");
        }

        return body.substring(0, lastEndIndex) + returnStatement + body.substring(lastEndIndex);
    }

    private int findLastEnd(String body) {
        // Case-insensitive search for last END
        String upperBody = body.toUpperCase();
        int index = upperBody.lastIndexOf("END;");
        if (index == -1) {
            index = upperBody.lastIndexOf("END ");
        }
        return index;
    }
}
```

### Component 3: TriggerFunctionGenerator

**Purpose:** Generate PostgreSQL trigger function DDL.

**Class:** `trigger/transformer/TriggerFunctionGenerator.java`

**DDL Template:**

```sql
CREATE OR REPLACE FUNCTION {schema}.{trigger_name}_func()
RETURNS TRIGGER AS $$
BEGIN
  {transformed_body}
END;
$$ LANGUAGE plpgsql;
```

**Implementation:**

```java
public class TriggerFunctionGenerator {

    /**
     * Generates CREATE FUNCTION DDL for trigger function.
     *
     * @param metadata Trigger metadata
     * @param transformedBody Transformed PL/pgSQL body (with RETURN)
     * @return CREATE FUNCTION statement
     */
    public String generateFunctionDdl(TriggerMetadata metadata, String transformedBody) {
        String schema = metadata.getSchema().toLowerCase();
        String functionName = metadata.getTriggerName().toLowerCase() + "_func";

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE OR REPLACE FUNCTION ").append(schema).append(".").append(functionName);
        ddl.append("()\n");
        ddl.append("RETURNS TRIGGER AS $$\n");
        ddl.append("BEGIN\n");
        ddl.append(transformedBody);
        ddl.append("\nEND;\n");
        ddl.append("$$ LANGUAGE plpgsql;\n");

        return ddl.toString();
    }
}
```

### Component 4: TriggerDefinitionGenerator

**Purpose:** Generate PostgreSQL CREATE TRIGGER DDL.

**Class:** `trigger/transformer/TriggerDefinitionGenerator.java`

**DDL Template:**

```sql
CREATE TRIGGER {trigger_name}
  {timing} {event} ON {schema}.{table}
  [FOR EACH ROW]
  [WHEN ({condition})]
  EXECUTE FUNCTION {schema}.{trigger_name}_func();
```

**Implementation:**

```java
public class TriggerDefinitionGenerator {

    /**
     * Generates CREATE TRIGGER DDL.
     *
     * @param metadata Trigger metadata
     * @return CREATE TRIGGER statement
     */
    public String generateTriggerDdl(TriggerMetadata metadata) {
        String schema = metadata.getSchema().toLowerCase();
        String triggerName = metadata.getTriggerName().toLowerCase();
        String tableName = metadata.getTableName().toLowerCase();
        String functionName = triggerName + "_func";

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TRIGGER ").append(triggerName).append("\n");

        // Timing and event
        ddl.append("  ").append(metadata.getTriggerType()).append(" ");
        ddl.append(metadata.getTriggerEvent()).append("\n");

        // Table
        ddl.append("  ON ").append(schema).append(".").append(tableName).append("\n");

        // Row level
        if ("ROW".equalsIgnoreCase(metadata.getTriggerLevel())) {
            ddl.append("  FOR EACH ROW\n");
        }

        // WHEN clause (if present)
        if (metadata.getWhenClause() != null && !metadata.getWhenClause().trim().isEmpty()) {
            String whenClause = transformWhenClause(metadata.getWhenClause());
            ddl.append("  WHEN (").append(whenClause).append(")\n");
        }

        // Execute function
        ddl.append("  EXECUTE FUNCTION ").append(schema).append(".").append(functionName);
        ddl.append("();\n");

        return ddl.toString();
    }

    /**
     * Transforms WHEN clause from Oracle to PostgreSQL syntax.
     * Removes colons from :NEW/:OLD references.
     */
    private String transformWhenClause(String whenClause) {
        // Use ColonReferenceTransformer
        return new ColonReferenceTransformer().removeColonReferences(whenClause);
    }
}
```

### Component 5: TriggerTransformer (Main Orchestrator)

**Purpose:** Orchestrate entire transformation process.

**Class:** `trigger/transformer/TriggerTransformer.java`

**Workflow:**

```java
@ApplicationScoped
public class TriggerTransformer {

    @Inject
    private TransformationService transformationService;

    /**
     * Transforms a single Oracle trigger to PostgreSQL DDL (function + trigger).
     *
     * @param metadata Oracle trigger metadata
     * @param indices Transformation indices for metadata lookups
     * @return TriggerDdlPair containing function DDL and trigger DDL
     */
    public TriggerDdlPair transformTrigger(TriggerMetadata metadata, TransformationIndices indices) {

        // Step 1: Extract trigger body
        String oracleTriggerBody = metadata.getTriggerBody();
        if (oracleTriggerBody == null || oracleTriggerBody.trim().isEmpty()) {
            throw new TransformationException("Trigger body is empty: " + metadata.getQualifiedName());
        }

        // Step 2: Clean comments
        String cleanedBody = CodeCleaner.removeComments(oracleTriggerBody);

        // Step 3: Transform PL/SQL → PL/pgSQL using existing infrastructure
        // Parse as procedure body (triggers are like procedures)
        TransformationResult result = transformationService.transformProcedure(
            cleanedBody, metadata.getSchema(), indices);

        if (result.isFailure()) {
            throw new TransformationException(
                "Failed to transform trigger body: " + result.getErrorMessage());
        }

        String transformedBody = result.getPostgresSql();

        // Step 4: Remove colons from :NEW/:OLD
        ColonReferenceTransformer colonTransformer = new ColonReferenceTransformer();
        transformedBody = colonTransformer.removeColonReferences(transformedBody);

        // Step 5: Inject RETURN statement
        TriggerReturnInjector returnInjector = new TriggerReturnInjector();
        transformedBody = returnInjector.injectReturn(
            transformedBody, metadata.getTriggerType(), metadata.getTriggerLevel());

        // Step 6: Generate function DDL
        TriggerFunctionGenerator functionGenerator = new TriggerFunctionGenerator();
        String functionDdl = functionGenerator.generateFunctionDdl(metadata, transformedBody);

        // Step 7: Generate trigger DDL
        TriggerDefinitionGenerator triggerGenerator = new TriggerDefinitionGenerator();
        String triggerDdl = triggerGenerator.generateTriggerDdl(metadata);

        return new TriggerDdlPair(functionDdl, triggerDdl);
    }

    /**
     * Result object containing both DDL statements.
     */
    public static class TriggerDdlPair {
        private final String functionDdl;
        private final String triggerDdl;

        public TriggerDdlPair(String functionDdl, String triggerDdl) {
            this.functionDdl = functionDdl;
            this.triggerDdl = triggerDdl;
        }

        public String getFunctionDdl() { return functionDdl; }
        public String getTriggerDdl() { return triggerDdl; }
    }
}
```

### PostgresTriggerImplementationJob Implementation

**Class:** `trigger/job/PostgresTriggerImplementationJob.java`

**Workflow:**

```java
@Override
protected TriggerImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback)
    throws Exception {

    TriggerImplementationResult result = new TriggerImplementationResult();

    // 1. Get Oracle triggers from state
    List<TriggerMetadata> oracleTriggers = stateService.getOracleTriggerMetadata();

    if (oracleTriggers == null || oracleTriggers.isEmpty()) {
        log.warn("No Oracle triggers found in state");
        return result;
    }

    // 2. Build transformation indices
    List<String> schemas = stateService.getOracleSchemaNames();
    TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

    // 3. Connect to PostgreSQL
    try (Connection pgConn = postgresConnectionService.getConnection()) {

        for (TriggerMetadata trigger : oracleTriggers) {
            String qualifiedName = trigger.getQualifiedName();

            try {
                // Transform trigger
                TriggerTransformer.TriggerDdlPair ddl =
                    triggerTransformer.transformTrigger(trigger, indices);

                // Execute function DDL
                executeDdl(pgConn, ddl.getFunctionDdl());

                // Execute trigger DDL
                executeDdl(pgConn, ddl.getTriggerDdl());

                result.addImplementedTrigger(trigger);
                log.info("Successfully implemented trigger: {}", qualifiedName);

            } catch (Exception e) {
                log.error("Failed to implement trigger: " + qualifiedName, e);
                result.addError(qualifiedName, e.getMessage(), trigger.getTriggerBody());
            }

            // Update progress
            updateProgress(progressCallback, ...);
        }
    }

    return result;
}
```

**Error Handling:**
- Individual trigger transformation errors → Log error, continue
- SQL execution errors → Log error, continue (transactional per trigger)
- Connection errors → Fail entire job

### Testing Phase 2

**Unit Tests:**
- `ColonReferenceTransformerTest.java` - Test colon removal (15 tests)
- `TriggerReturnInjectorTest.java` - Test return injection logic (12 tests)
- `TriggerFunctionGeneratorTest.java` - Test function DDL generation (8 tests)
- `TriggerDefinitionGeneratorTest.java` - Test trigger DDL generation (10 tests)
- `TriggerTransformerTest.java` - Test end-to-end transformation (20 tests)

**Integration Tests:**
- `PostgresTriggerImplementationJobTest.java` - Full job execution with test database

**Test Cases:**
1. Simple BEFORE ROW trigger (basic INSERT logging)
2. AFTER ROW trigger (audit trail)
3. INSTEAD OF trigger (view trigger)
4. Statement-level trigger
5. Trigger with WHEN clause
6. Trigger with multiple events (INSERT OR UPDATE)
7. Trigger with complex PL/SQL (loops, exceptions)
8. Trigger referencing package functions
9. Error cases: Invalid SQL, missing table, transformation failure

---

## Phase 3: PostgreSQL Trigger Verification

### Overview

Extract trigger metadata from PostgreSQL to verify successful creation.

### PostgreSQL Data Dictionary Sources

**Query Structure:**

```sql
SELECT
  n.nspname AS schema_name,
  t.tgname AS trigger_name,
  c.relname AS table_name,

  -- Trigger timing (BEFORE/AFTER/INSTEAD OF)
  CASE
    WHEN t.tgtype & 2 = 2 THEN 'BEFORE'
    WHEN t.tgtype & 64 = 64 THEN 'INSTEAD OF'
    ELSE 'AFTER'
  END AS trigger_type,

  -- Trigger event (INSERT/UPDATE/DELETE)
  CASE
    WHEN t.tgtype & 4 = 4 THEN 'INSERT'
    WHEN t.tgtype & 8 = 8 THEN 'DELETE'
    WHEN t.tgtype & 16 = 16 THEN 'UPDATE'
  END AS trigger_event,

  -- Trigger level (ROW/STATEMENT)
  CASE
    WHEN t.tgtype & 1 = 1 THEN 'ROW'
    ELSE 'STATEMENT'
  END AS trigger_level,

  -- Trigger status
  CASE
    WHEN t.tgenabled = 'O' THEN 'ENABLED'
    ELSE 'DISABLED'
  END AS status,

  -- Trigger function body (from pg_proc)
  pg_get_functiondef(p.oid) AS function_definition,

  -- WHEN clause (if present)
  pg_get_triggerdef(t.oid) AS trigger_definition

FROM pg_trigger t
  JOIN pg_class c ON t.tgrelid = c.oid
  JOIN pg_namespace n ON c.relnamespace = n.oid
  LEFT JOIN pg_proc p ON t.tgfoid = p.oid
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  AND NOT t.tgisinternal  -- Exclude internal triggers
ORDER BY n.nspname, t.tgname;
```

**Note:** PostgreSQL stores trigger metadata in bitmask format (`tgtype` field). Need to decode bits.

### PostgresTriggerExtractor Implementation

**Class:** `trigger/service/PostgresTriggerExtractor.java`

**Responsibilities:**
1. Query pg_trigger, pg_class, pg_namespace, pg_proc
2. Decode tgtype bitmask into type/event/level
3. Extract function body from pg_proc
4. Extract WHEN clause from pg_get_triggerdef() output (parse DDL)
5. Return List<TriggerMetadata>

**Key Methods:**

```java
public class PostgresTriggerExtractor {

    public List<TriggerMetadata> extractTriggers(Connection conn, List<String> schemas)
        throws SQLException;

    private TriggerMetadata extractSingleTrigger(ResultSet rs) throws SQLException;

    private String decodeTriggerType(int tgtype);
    // Decode bitmask: bit 2 = BEFORE, bit 64 = INSTEAD OF, else AFTER

    private String decodeTriggerEvent(int tgtype);
    // Decode bitmask: bit 4 = INSERT, bit 8 = DELETE, bit 16 = UPDATE

    private String decodeTriggerLevel(int tgtype);
    // Decode bitmask: bit 1 = ROW, else STATEMENT

    private String extractWhenClauseFromDdl(String triggerDef);
    // Parse "CREATE TRIGGER ... WHEN (...) ..." → extract condition
}
```

### PostgresTriggerVerificationJob Implementation

**Class:** `trigger/job/PostgresTriggerVerificationJob.java`

**Workflow:**
1. Get PostgreSQL schemas from StateService (or config)
2. Filter valid schemas
3. Create PostgresTriggerExtractor instance
4. Extract triggers for all schemas
5. Save results to StateService.setPostgresTriggerMetadata()
6. Report progress and summary

**Progress Reporting:**
- 0-10%: Connection and initialization
- 10-90%: Processing schemas
- 90-100%: Saving results

### Testing Phase 3

**Unit Tests:** `PostgresTriggerExtractorTest.java`
- Test decoding tgtype bitmask (12 combinations)
- Test WHEN clause extraction from DDL
- Test function body extraction

**Integration Tests:** `PostgresTriggerVerificationJobTest.java`
- Test against real PostgreSQL database with sample triggers

---

## Testing Strategy

### Unit Test Coverage

**Target:** 80%+ code coverage

**Test Classes (Total: ~100 tests):**
1. `OracleTriggerExtractorTest` (15 tests)
2. `ColonReferenceTransformerTest` (15 tests)
3. `TriggerReturnInjectorTest` (12 tests)
4. `TriggerFunctionGeneratorTest` (8 tests)
5. `TriggerDefinitionGeneratorTest` (10 tests)
6. `TriggerTransformerTest` (20 tests)
7. `PostgresTriggerExtractorTest` (12 tests)
8. Integration tests (8 tests)

### Integration Test Strategy

**Use Testcontainers for PostgreSQL:**
- Create test triggers in Oracle (mocked or real Oracle container)
- Run extraction job
- Run implementation job
- Verify triggers exist in PostgreSQL
- Fire triggers (INSERT/UPDATE) and verify they execute correctly

**Test Scenarios:**
1. Simple BEFORE ROW trigger (audit log)
2. AFTER ROW trigger (cascade update)
3. INSTEAD OF trigger (view trigger)
4. Statement-level trigger
5. Trigger with WHEN clause
6. Trigger with complex PL/SQL (exception handling, loops)
7. Error cases (invalid SQL, missing dependencies)

### Validation Tests

**End-to-End Validation:**
1. Extract triggers from Oracle
2. Transform and create in PostgreSQL
3. Verify trigger count matches
4. Fire triggers and compare behavior (Oracle vs PostgreSQL)

---

## Implementation Timeline

### Phase 1: Oracle Trigger Extraction (1 day)

- [ ] Implement OracleTriggerExtractor (4 hours)
- [ ] Implement OracleTriggerExtractionJob (2 hours)
- [ ] Write unit tests (2 hours)

### Phase 2: PostgreSQL Trigger Implementation (2-3 days)

- [ ] Implement ColonReferenceTransformer (2 hours)
- [ ] Implement TriggerReturnInjector (3 hours)
- [ ] Implement TriggerFunctionGenerator (2 hours)
- [ ] Implement TriggerDefinitionGenerator (2 hours)
- [ ] Implement TriggerTransformer (4 hours)
- [ ] Implement PostgresTriggerImplementationJob (4 hours)
- [ ] Write unit tests (8 hours)
- [ ] Write integration tests (4 hours)

### Phase 3: PostgreSQL Trigger Verification (0.5 day)

- [ ] Implement PostgresTriggerExtractor (3 hours)
- [ ] Implement PostgresTriggerVerificationJob (1 hour)
- [ ] Write unit tests (2 hours)

### Phase 4: Documentation and Review (0.5 day)

- [ ] Update CLAUDE.md Phase 3 status (1 hour)
- [ ] Update TRANSFORMATION.md (1 hour)
- [ ] Code review and refinement (2 hours)

**Total Estimated Effort:** 3-5 days

---

## Risk Assessment

### High-Risk Areas

1. **Complex Trigger Bodies**
   - **Risk:** Oracle triggers with advanced PL/SQL may not transform cleanly
   - **Mitigation:** Reuse existing PostgresCodeBuilder (85-95% PL/SQL coverage)
   - **Fallback:** Log transformation errors, allow user to fix manually

2. **Colon Reference Edge Cases**
   - **Risk:** Colons in string literals or comments might be incorrectly replaced
   - **Mitigation:** Use CodeCleaner.removeComments() before transformation
   - **Fallback:** If edge cases found, enhance ColonReferenceTransformer with AST-based approach

3. **Return Value Logic**
   - **Risk:** Complex triggers with multiple RETURN paths may be missed
   - **Mitigation:** TriggerReturnInjector checks for existing RETURN statements
   - **Fallback:** Log warnings if multiple RETURNs found

4. **Autonomous Transactions**
   - **Risk:** Oracle PRAGMA AUTONOMOUS_TRANSACTION not supported in PostgreSQL
   - **Mitigation:** Detect pragma and warn user (suggest dblink extension workaround)
   - **Fallback:** Skip trigger, add error to result

5. **FOLLOWS Clause**
   - **Risk:** Oracle trigger ordering (FOLLOWS clause) not supported in PostgreSQL
   - **Mitigation:** Skip FOLLOWS clause, log warning
   - **Impact:** Low - trigger order rarely critical

### Medium-Risk Areas

1. **Statement-Level :NEW/:OLD**
   - **Risk:** Oracle allows :NEW/:OLD in statement-level triggers (mutating table error)
   - **Mitigation:** Transform to PostgreSQL transition tables (REFERENCING clause)
   - **Status:** Low priority (rare usage)

2. **Compound Triggers**
   - **Risk:** Oracle 11g+ compound triggers (BEFORE STATEMENT + BEFORE EACH ROW in one)
   - **Mitigation:** Split into multiple PostgreSQL triggers
   - **Status:** Phase 2 feature (not critical)

3. **View Triggers (INSTEAD OF)**
   - **Risk:** Different view trigger semantics between Oracle and PostgreSQL
   - **Mitigation:** Extract and transform like table triggers
   - **Status:** Should work, but needs testing

### Low-Risk Areas

1. **Simple Triggers** (95% of triggers)
   - BEFORE/AFTER ROW triggers with basic PL/SQL
   - Already covered by existing transformation infrastructure

2. **Metadata Extraction**
   - Well-defined data dictionary queries
   - Follows existing extraction job pattern

---

## Unsupported Features (Documented Limitations)

The following Oracle trigger features are NOT supported in PostgreSQL:

### 1. Autonomous Transactions

**Oracle:**
```sql
CREATE TRIGGER log_error
AFTER INSERT ON orders
FOR EACH ROW
DECLARE
  PRAGMA AUTONOMOUS_TRANSACTION;
BEGIN
  INSERT INTO error_log VALUES (:NEW.order_id, SYSDATE);
  COMMIT;  -- Independent transaction
END;
```

**PostgreSQL:** Not supported natively.

**Workaround:** Use dblink extension to create separate connection.

**Migration Strategy:** Log warning, skip trigger, document workaround.

### 2. FOLLOWS Clause (Trigger Ordering)

**Oracle:**
```sql
CREATE TRIGGER check_salary
BEFORE UPDATE ON employees
FOR EACH ROW
FOLLOWS validate_dept
BEGIN
  ...
END;
```

**PostgreSQL:** Triggers fire in alphabetical order (no explicit ordering).

**Workaround:** Rename triggers with numeric prefixes (e.g., `01_trigger`, `02_trigger`).

**Migration Strategy:** Skip FOLLOWS clause, log warning, suggest renaming.

### 3. Compound Triggers (Phase 2)

**Oracle 11g+:**
```sql
CREATE TRIGGER compound_trg
FOR INSERT OR UPDATE ON employees
COMPOUND TRIGGER
  -- Multiple timing points in one trigger
  BEFORE STATEMENT IS BEGIN ... END;
  BEFORE EACH ROW IS BEGIN ... END;
  AFTER EACH ROW IS BEGIN ... END;
  AFTER STATEMENT IS BEGIN ... END;
END compound_trg;
```

**PostgreSQL:** Not supported (single timing per trigger).

**Workaround:** Split into 4 separate triggers.

**Migration Strategy:** Future enhancement (Phase 2).

### 4. Database-Level Triggers

**Oracle:**
```sql
CREATE TRIGGER logon_trigger
AFTER LOGON ON DATABASE
BEGIN
  INSERT INTO login_audit VALUES (USER, SYSDATE);
END;
```

**PostgreSQL:** Event triggers (different syntax, limited events).

**Migration Strategy:** Not supported in Phase 1 (database triggers are rare).

---

## Success Criteria

### Definition of Done

✅ **Phase 1 Complete:**
- OracleTriggerExtractionJob extracts all triggers from Oracle
- Metadata stored in StateService
- Unit tests passing (15+ tests)

✅ **Phase 2 Complete:**
- PostgresTriggerImplementationJob transforms and creates triggers
- 85%+ success rate on real-world Oracle triggers
- Both function and trigger DDL generated correctly
- RETURN statements injected appropriately
- Colons removed from :NEW/:OLD
- Unit tests passing (65+ tests)
- Integration tests passing (8+ tests)

✅ **Phase 3 Complete:**
- PostgresTriggerVerificationJob extracts triggers from PostgreSQL
- Verification results displayed in frontend
- Unit tests passing (12+ tests)

✅ **Documentation Updated:**
- CLAUDE.md Phase 3 section updated
- TRANSFORMATION.md trigger section added
- TRIGGER_IMPLEMENTATION_PLAN.md complete

### Acceptance Tests

1. **Extract 50 Oracle triggers** → All metadata extracted correctly
2. **Transform and create 50 triggers** → 85%+ success rate (42+ triggers created)
3. **Verify triggers exist** → PostgreSQL trigger count matches created count
4. **Fire triggers** → Triggers execute without errors on INSERT/UPDATE/DELETE
5. **Compare behavior** → Trigger effects match Oracle behavior (audit logs, cascades)

---

## Next Steps

### Immediate Actions

1. **Review this plan with user** - Confirm approach and timeline
2. **Create feature branch** - `feature/trigger-transformation`
3. **Start Phase 1** - Implement OracleTriggerExtractionJob
4. **Write tests incrementally** - Test-driven development

### Future Enhancements (Post-MVP)

1. **Compound Trigger Support** - Split Oracle compound triggers into multiple PostgreSQL triggers
2. **Autonomous Transaction Detection** - Better warnings and dblink generation
3. **Statement-Level Transition Tables** - Transform :NEW/:OLD in statement triggers to REFERENCING clause
4. **Database-Level Triggers** - Map to PostgreSQL event triggers
5. **Trigger Dependency Ordering** - Analyze FOLLOWS clause and suggest renaming

---

## Appendix: Oracle to PostgreSQL Trigger Mapping Reference

### Trigger Type Mapping

| Oracle | PostgreSQL | Notes |
|--------|------------|-------|
| `BEFORE INSERT` | `BEFORE INSERT` | Direct mapping |
| `AFTER UPDATE` | `AFTER UPDATE` | Direct mapping |
| `BEFORE DELETE` | `BEFORE DELETE` | Direct mapping |
| `INSTEAD OF INSERT` | `INSTEAD OF INSERT` | View triggers |
| `FOR EACH ROW` | `FOR EACH ROW` | Direct mapping |
| `FOR EACH STATEMENT` | (default) | Omit clause |

### PL/SQL to PL/pgSQL Mapping (Triggers)

| Oracle | PostgreSQL | Transformation |
|--------|------------|----------------|
| `:NEW.column` | `NEW.column` | Remove colon |
| `:OLD.column` | `OLD.column` | Remove colon |
| `WHEN (NEW.x > 10)` | `WHEN (NEW.x > 10)` | Remove colon in WHEN |
| (no return) | `RETURN NEW;` | Add return |
| `PRAGMA AUTONOMOUS_TRANSACTION` | (not supported) | Warn user |
| `SYSDATE` | `CURRENT_TIMESTAMP` | Existing transformation |
| `RAISE_APPLICATION_ERROR` | `RAISE EXCEPTION` | Existing transformation |

### DDL Structure Comparison

**Oracle:**
```sql
CREATE [OR REPLACE] TRIGGER trigger_name
  {BEFORE|AFTER|INSTEAD OF} {INSERT|UPDATE|DELETE}
  ON [schema.]table_name
  [FOR EACH ROW]
  [WHEN (condition)]
BEGIN
  -- PL/SQL body
END [trigger_name];
```

**PostgreSQL:**
```sql
-- Function
CREATE [OR REPLACE] FUNCTION [schema.]trigger_name_func()
RETURNS TRIGGER AS $$
BEGIN
  -- PL/pgSQL body
  RETURN NEW; -- or NULL
END;
$$ LANGUAGE plpgsql;

-- Trigger
CREATE TRIGGER trigger_name
  {BEFORE|AFTER|INSTEAD OF} {INSERT|UPDATE|DELETE}
  ON [schema.]table_name
  [FOR EACH ROW]
  [WHEN (condition)]
  EXECUTE FUNCTION [schema.]trigger_name_func();
```

---

**End of Implementation Plan**
