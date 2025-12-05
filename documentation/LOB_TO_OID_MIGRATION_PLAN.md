# LOB to OID Migration Implementation Plan

## Problem Statement

### Issue
Java applications using `@Lob` annotations on `byte[]` fields expect JDBC `Blob` API (`getBlob()`). PostgreSQL's `getBlob()` implementation expects `oid` type (Large Object reference), not `bytea` (inline binary).

### Error Symptoms
```
Can not extract column "..."
at org.postgresql.jdbc.PgResultSet.toLong(PgResultSet.java:3397)
at org.postgresql.jdbc.PgResultSet.getLong(PgResultSet.java:2635)
at org.postgresql.jdbc.PgResultSet.getBlob(PgResultSet.java:468)
```

### Root Cause
- Current migration: Oracle `BLOB` → PostgreSQL `bytea`
- Java `@Lob` → JDBC `getBlob()` → Expects `oid`, not `bytea`
- PostgreSQL tries to read `bytea` column as `Long` (OID reference) → Type mismatch error

### Constraint
**Cannot modify Java code** - Migration must be database-only.

---

## Solution Overview

### Type Mapping Changes
- Oracle `BLOB` → PostgreSQL `oid` (was: `bytea`)
- Oracle `CLOB` → PostgreSQL `oid` (was: `text`)
- Oracle `NCLOB` → PostgreSQL `oid` (was: `text`)
- Oracle `LONG` → Keep as `text` (obsolete, no `@Lob`)
- Oracle `LONG RAW` → Keep as `bytea` (obsolete, no `@Lob`)

### Two-Phase Data Transfer Strategy
Since PostgreSQL `oid` columns cannot accept hex-encoded strings during COPY, we use staging columns:

1. **Table Creation**: Create final structure with `oid` columns
2. **Data Transfer**:
   - Drop NOT NULL constraints on `oid` columns (if present)
   - Add temporary `{column}_staging bytea` columns
   - COPY hex data into staging columns (oid columns remain NULL)
   - Convert: `UPDATE table SET column = lo_from_bytea(0, column_staging)`
   - Restore NOT NULL constraints on `oid` columns (if originally present)
   - Drop staging columns
3. **View Stubs**: Reference `oid` columns directly (staging columns invisible)

### Key Principles
✅ **Stub mechanism preserved** - Views only see `oid` columns
✅ **Self-contained data transfer** - Staging lifecycle managed within transfer step
✅ **Repeatable** - Re-running data transfer works identically
✅ **No new dependencies** - Only existing: tables before data transfer

---

## Architectural Changes

### Affected Components

| Component | File | Change Type | Complexity |
|-----------|------|-------------|------------|
| Type Converter | `core/tools/TypeConverter.java` | 3 line changes | Low |
| Data Transfer | `transfer/service/CsvDataTransferService.java` | Major refactor | High |
| Table Creation | *(No changes)* | Uses TypeConverter | None |
| View Stubs | *(No changes)* | Uses TypeConverter | None |

### Migration Workflow Changes

**Before:**
```
Step 4: Create tables (BLOB→bytea)
Step 5: COPY hex data → bytea column
Step 7: Create view stubs (bytea type)
```

**After:**
```
Step 4: Create tables (BLOB→oid)
Step 5: Drop NOT NULL → Add staging → COPY hex → Convert to Large Object → Restore NOT NULL → Drop staging
Step 7: Create view stubs (oid type) ← No change needed
```

---

## Detailed Implementation

### Phase 1: Type Mapping Update

**File:** `src/main/java/me/christianrobert/orapgsync/core/tools/TypeConverter.java`

**Location:** `toPostgre()` method, lines 140-145

**Changes:**
```java
// Large object types
case "bfile":
    return "text";  // Unchanged (external file reference)

case "blob":
    return "oid";  // ← CHANGED from "bytea"

case "clob":
case "nclob":
    return "oid";  // ← CHANGED from "text"
```

**Testing:**
- Unit test: Verify `TypeConverter.toPostgre("BLOB")` returns `"oid"`
- Unit test: Verify `TypeConverter.toPostgre("CLOB")` returns `"oid"`
- Unit test: Verify `TypeConverter.toPostgre("NCLOB")` returns `"oid"`
- Integration: Create table with BLOB column, verify PostgreSQL schema shows `oid`

---

### Phase 2: Data Transfer Modification

**File:** `src/main/java/me/christianrobert/orapgsync/transfer/service/CsvDataTransferService.java`

#### 2.1 New Helper Method: Detect OID Columns

**Purpose:** Query PostgreSQL table metadata to find `oid` columns (LOB columns that need staging).

**Status:** ✅ Implemented

```java
/**
 * Detects columns with oid type in PostgreSQL table.
 * These columns need staging columns for COPY + conversion.
 *
 * @param postgresConn PostgreSQL connection
 * @param table Table metadata
 * @return List of column names with oid type
 */
private List<String> detectOidColumns(Connection postgresConn, TableMetadata table) throws SQLException {
    List<String> oidColumns = new ArrayList<>();

    String sql = "SELECT column_name " +
                 "FROM information_schema.columns " +
                 "WHERE table_schema = ? " +
                 "AND table_name = ? " +
                 "AND data_type = 'oid'";

    try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
        stmt.setString(1, table.getSchema());
        stmt.setString(2, table.getTableName());

        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                oidColumns.add(rs.getString("column_name"));
            }
        }
    }

    log.debug("Found {} oid columns in table {}.{}: {}",
              oidColumns.size(), table.getSchema(), table.getTableName(), oidColumns);

    return oidColumns;
}
```

**Testing:**
- Unit test with mock connection returning oid column
- Integration test with real PostgreSQL table

#### 2.2 New Helper Methods: NOT NULL Constraint Management

**Purpose:** Temporarily drop NOT NULL constraints on `oid` columns during staging, then restore after conversion.

**Status:** ✅ Implemented (2025-12-03)

**Background:** When `oid` columns have NOT NULL constraints, COPY fails because data goes into staging columns, leaving `oid` columns NULL. Solution: temporarily drop constraints, perform transfer, restore after conversion.

**2.2a - Detect NOT NULL OID Columns:**
```java
/**
 * Detects which oid columns have NOT NULL constraints.
 * These constraints must be temporarily dropped during staging.
 */
private List<String> detectNotNullOidColumns(Connection postgresConn,
                                             TableMetadata table,
                                             List<String> oidColumns) throws SQLException {
    // Query information_schema.columns for is_nullable = 'NO'
    // Returns list of oid column names with NOT NULL constraints
}
```

**2.2b - Drop NOT NULL Constraints:**
```java
/**
 * Temporarily drops NOT NULL constraints on oid columns.
 * Constraints will be restored after Large Object conversion.
 */
private void dropNotNullConstraints(Connection postgresConn,
                                   TableMetadata table,
                                   List<String> notNullOidColumns) throws SQLException {
    // For each column: ALTER TABLE ... ALTER COLUMN ... DROP NOT NULL
}
```

**2.2c - Restore NOT NULL Constraints:**
```java
/**
 * Restores NOT NULL constraints on oid columns after conversion.
 * Only restores if all values are non-NULL (safe to enforce constraint).
 */
private void restoreNotNullConstraints(Connection postgresConn,
                                      TableMetadata table,
                                      List<String> notNullOidColumns) throws SQLException {
    // Safety check: COUNT(*) WHERE column IS NULL
    // If no NULLs: ALTER TABLE ... ALTER COLUMN ... SET NOT NULL
}
```

**Testing:**
- Unit test: Verify detection of NOT NULL constraints
- Integration test: Create table with NOT NULL oid column, verify constraint dropped/restored
- Integration test: Verify data transfer succeeds with NOT NULL constraints

#### 2.3 New Helper Method: Add Staging Columns

**Purpose:** Add temporary `{column}_staging bytea` columns for COPY target.

**Status:** ✅ Implemented

```java
/**
 * Adds staging columns for oid columns.
 * Staging columns are temporary bytea columns used during COPY.
 * Format: {original_column}_staging bytea
 *
 * @param postgresConn PostgreSQL connection
 * @param table Table metadata
 * @param oidColumns List of oid column names
 */
private void addStagingColumns(Connection postgresConn, TableMetadata table,
                                List<String> oidColumns) throws SQLException {
    if (oidColumns.isEmpty()) {
        return;
    }

    String qualifiedTableName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

    for (String oidColumn : oidColumns) {
        String stagingColumn = oidColumn + "_staging";
        String sql = "ALTER TABLE " + qualifiedTableName +
                     " ADD COLUMN \"" + stagingColumn + "\" bytea";

        log.debug("Adding staging column: {}", stagingColumn);

        try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
            stmt.execute();
        }
    }

    log.debug("Added {} staging columns to {}", oidColumns.size(), qualifiedTableName);
}
```

**Error Handling:**
- If staging column already exists (shouldn't happen): Log warning, continue
- If ALTER fails: SQLException propagates → transaction rollback

**Testing:**
- Unit test: Verify correct SQL generation
- Integration test: Check staging columns created in PostgreSQL

#### 2.4 New Helper Method: Convert Staging to Large Objects

**Purpose:** Convert hex data in staging columns to PostgreSQL Large Objects, store OID references.

**Status:** ✅ Implemented

```java
/**
 * Converts staging column data to Large Objects using lo_from_bytea().
 * PostgreSQL function lo_from_bytea(loid oid, data bytea) creates a Large Object
 * and returns its OID reference.
 *
 * Using loid=0 means PostgreSQL auto-generates unique OID.
 *
 * @param postgresConn PostgreSQL connection
 * @param table Table metadata
 * @param oidColumns List of oid column names
 */
private void convertStagingToLargeObjects(Connection postgresConn, TableMetadata table,
                                          List<String> oidColumns) throws SQLException {
    if (oidColumns.isEmpty()) {
        return;
    }

    String qualifiedTableName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

    for (String oidColumn : oidColumns) {
        String stagingColumn = oidColumn + "_staging";

        // UPDATE table SET doc_content = lo_from_bytea(0, doc_content_staging)
        // WHERE doc_content_staging IS NOT NULL
        String sql = "UPDATE " + qualifiedTableName +
                     " SET \"" + oidColumn + "\" = lo_from_bytea(0, \"" + stagingColumn + "\") " +
                     " WHERE \"" + stagingColumn + "\" IS NOT NULL";

        log.debug("Converting staging column {} to Large Objects for column {}",
                  stagingColumn, oidColumn);

        try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
            int rowsUpdated = stmt.executeUpdate();
            log.debug("Converted {} rows for column {} (NULL rows remain NULL)",
                      rowsUpdated, oidColumn);
        }
    }

    log.debug("Completed Large Object conversion for {} columns in {}",
              oidColumns.size(), qualifiedTableName);
}
```

**Key Points:**
- `lo_from_bytea(0, data)` - 0 means auto-generate OID
- `WHERE staging IS NOT NULL` - Preserve NULL values (don't create Large Objects for NULL)
- Each row gets unique Large Object with unique OID

**Error Handling:**
- Conversion failure → SQLException → Transaction rollback → Staging columns remain for debugging

**Testing:**
- Unit test: Verify SQL syntax
- Integration test:
  - Insert hex data into staging column
  - Run conversion
  - Verify oid column contains numeric OID
  - Verify Large Object readable via `lo_get(oid)`

#### 2.5 New Helper Method: Drop Staging Columns

**Purpose:** Clean up temporary staging columns after successful conversion.

**Status:** ✅ Implemented

```java
/**
 * Drops staging columns after successful Large Object conversion.
 *
 * @param postgresConn PostgreSQL connection
 * @param table Table metadata
 * @param oidColumns List of oid column names
 */
private void dropStagingColumns(Connection postgresConn, TableMetadata table,
                                List<String> oidColumns) throws SQLException {
    if (oidColumns.isEmpty()) {
        return;
    }

    String qualifiedTableName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

    for (String oidColumn : oidColumns) {
        String stagingColumn = oidColumn + "_staging";
        String sql = "ALTER TABLE " + qualifiedTableName +
                     " DROP COLUMN \"" + stagingColumn + "\"";

        log.debug("Dropping staging column: {}", stagingColumn);

        try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
            stmt.execute();
        }
    }

    log.debug("Dropped {} staging columns from {}", oidColumns.size(), qualifiedTableName);
}
```

**Error Handling:**
- Drop failure is non-critical (data already converted)
- Log error but don't fail transaction
- Orphaned staging columns can be manually cleaned later

**Testing:**
- Integration test: Verify staging columns removed after conversion

#### 2.6 Modify `performCsvTransfer()` Method

**Status:** ✅ Implemented (including NOT NULL constraint management)

**Current signature (line 172):**
```java
private long performCsvTransfer(Connection oracleConn, Connection postgresConn,
                               TableMetadata table, int batchSize) throws Exception
```

**Add new workflow steps:**

**Location:** After line 189 (after copySql definition), before piped streams setup

```java
// Detect oid columns that need staging
List<String> oidColumns = detectOidColumns(postgresConn, table);

// Add staging columns if needed
if (!oidColumns.isEmpty()) {
    log.debug("Table {} has {} oid columns requiring staging",
              table.getTableName(), oidColumns.size());
    addStagingColumns(postgresConn, table, oidColumns);
}
```

**Location:** After line 238 (after consumer completes), before commit (line 242)

```java
// Convert staging columns to Large Objects if needed
if (!oidColumns.isEmpty()) {
    log.debug("Converting staging columns to Large Objects for table {}",
              table.getTableName());
    convertStagingToLargeObjects(postgresConn, table, oidColumns);
    dropStagingColumns(postgresConn, table, oidColumns);
}

// Commit the transaction
postgresConn.commit();
```

**Complete modified flow:**
```java
private long performCsvTransfer(...) throws Exception {
    // ... existing setup code ...

    // NEW: Detect oid columns and NOT NULL constraints
    List<String> oidColumns = detectOidColumns(postgresConn, table);
    List<String> notNullOidColumns = detectNotNullOidColumns(postgresConn, table, oidColumns);

    if (!oidColumns.isEmpty()) {
        // Drop NOT NULL constraints first
        dropNotNullConstraints(postgresConn, table, notNullOidColumns);
        // Add staging columns
        addStagingColumns(postgresConn, table, oidColumns);
    }

    // Existing COPY logic (unchanged)
    // ... piped streams, producer, consumer ...

    // NEW: Convert, restore constraints, and cleanup
    if (!oidColumns.isEmpty()) {
        convertStagingToLargeObjects(postgresConn, table, oidColumns);
        // Restore NOT NULL constraints after conversion
        restoreNotNullConstraints(postgresConn, table, notNullOidColumns);
        dropStagingColumns(postgresConn, table, oidColumns);
    }

    postgresConn.commit();
    return totalTransferred;
}
```

#### 2.7 Modify `buildQuotedColumnList()` for COPY Target

**Status:** ✅ Implemented

**Current implementation (line 543):**
```java
private String buildQuotedColumnList(TableMetadata table) {
    return table.getColumns().stream()
            .map(col -> PostgresIdentifierNormalizer.normalizeIdentifier(col.getColumnName()))
            .reduce((a, b) -> a + ", " + b)
            .orElse("*");
}
```

**Problem:** For oid columns, we need to COPY into `{column}_staging`, not `{column}`.

**Solution:** Accept `oidColumns` parameter and substitute staging columns:

```java
/**
 * Builds column list for PostgreSQL COPY command.
 * For oid columns, substitutes staging column names.
 *
 * @param table Table metadata
 * @param oidColumns List of oid column names (use staging columns)
 * @return Comma-separated quoted column list
 */
private String buildQuotedColumnList(TableMetadata table, List<String> oidColumns) {
    return table.getColumns().stream()
            .map(col -> {
                String columnName = col.getColumnName();
                // If this is an oid column, use staging column for COPY
                if (oidColumns.contains(columnName)) {
                    return PostgresIdentifierNormalizer.normalizeIdentifier(columnName + "_staging");
                } else {
                    return PostgresIdentifierNormalizer.normalizeIdentifier(columnName);
                }
            })
            .reduce((a, b) -> a + ", " + b)
            .orElse("*");
}
```

**Update call site (line 180):**
```java
// Before:
String quotedColumnList = buildQuotedColumnList(table);

// After:
List<String> oidColumns = detectOidColumns(postgresConn, table);  // Detect early
String quotedColumnList = buildQuotedColumnList(table, oidColumns);  // Pass oid columns
```

**Note:** This means we need to detect oid columns **before** building the COPY command, not after.

**Revised flow in `performCsvTransfer()`:**
```java
// Step 1: Detect oid columns and NOT NULL constraints (needed for COPY column list)
List<String> oidColumns = detectOidColumns(postgresConn, table);
List<String> notNullOidColumns = detectNotNullOidColumns(postgresConn, table, oidColumns);

// Step 2: Build column lists (uses oidColumns for staging substitution)
String columnList = buildOracleSelectColumnList(table);  // Unchanged
String quotedColumnList = buildQuotedColumnList(table, oidColumns);  // Modified

// Step 3: Build SQL commands
String selectSql = "SELECT " + columnList + " FROM " + qualifiedOracleName;
String copySql = "COPY " + qualifiedPostgresName + " (" + quotedColumnList + ") ...";

// Step 4: Prepare staging (after SQL is built)
if (!oidColumns.isEmpty()) {
    dropNotNullConstraints(postgresConn, table, notNullOidColumns);
    addStagingColumns(postgresConn, table, oidColumns);
}

// Step 5: Perform COPY (existing code)
// ... producer/consumer ...

// Step 6: Convert, restore constraints, and cleanup (after COPY succeeds)
if (!oidColumns.isEmpty()) {
    convertStagingToLargeObjects(postgresConn, table, oidColumns);
    restoreNotNullConstraints(postgresConn, table, notNullOidColumns);
    dropStagingColumns(postgresConn, table, oidColumns);
}

postgresConn.commit();
```

**Testing:**
- Unit test: Verify `buildQuotedColumnList()` substitutes `doc_content` → `doc_content_staging` for oid columns
- Integration test: Verify COPY command targets staging columns

---

## Testing Strategy

### Unit Tests

**TypeConverter Tests:**
```java
@Test
void testBlobMapsToOid() {
    assertEquals("oid", TypeConverter.toPostgre("BLOB"));
}

@Test
void testClobMapsToOid() {
    assertEquals("oid", TypeConverter.toPostgre("CLOB"));
}

@Test
void testNclobMapsToOid() {
    assertEquals("oid", TypeConverter.toPostgre("NCLOB"));
}

@Test
void testLongRemainsText() {
    assertEquals("text", TypeConverter.toPostgre("LONG"));
}

@Test
void testLongRawRemainsBytea() {
    assertEquals("bytea", TypeConverter.toPostgre("LONG RAW"));
}
```

**CsvDataTransferService Tests:**
```java
@Test
void testDetectOidColumns() {
    // Mock PostgreSQL metadata query
    // Verify oid columns detected
}

@Test
void testBuildQuotedColumnListWithOidColumns() {
    // Given: Table with BLOB column doc_content
    // When: oidColumns = ["doc_content"]
    // Then: Column list contains "doc_content_staging", not "doc_content"
}

@Test
void testBuildQuotedColumnListNoOidColumns() {
    // Given: Table with no oid columns
    // When: oidColumns = []
    // Then: Column list unchanged
}
```

### Integration Tests

**End-to-End BLOB Migration Test:**
```java
@Test
void testBlobMigrationWithJavaLob() throws Exception {
    // 1. Create Oracle table with BLOB column
    oracleConn.execute("CREATE TABLE test_lob (id NUMBER, doc BLOB)");

    // 2. Insert test data
    byte[] testData = "Hello World".getBytes();
    // Insert BLOB data

    // 3. Run table creation (should create oid column)
    tableCreationJob.execute();

    // 4. Verify PostgreSQL table structure
    ResultSet rs = postgresConn.query("SELECT data_type FROM information_schema.columns WHERE table_name='test_lob' AND column_name='doc'");
    assertEquals("oid", rs.getString("data_type"));

    // 5. Run data transfer
    dataTransferJob.execute();

    // 6. Verify data transferred correctly
    rs = postgresConn.query("SELECT doc FROM test_lob WHERE id=1");
    oid docOid = rs.getOid("doc");
    assertNotNull(docOid);

    // 7. Read Large Object content
    LargeObjectManager lom = postgresConn.unwrap(BaseConnection.class).getLargeObjectAPI();
    LargeObject lo = lom.open(docOid, LargeObjectManager.READ);
    byte[] readData = new byte[testData.length];
    lo.read(readData, 0, readData.length);
    assertArrayEquals(testData, readData);

    // 8. Verify Java @Lob can read it
    // (Requires JPA entity with @Lob annotation)
    TestEntity entity = entityManager.find(TestEntity.class, 1);
    assertArrayEquals(testData, entity.getDocContent());  // @Lob byte[] field
}
```

**Repeatability Test:**
```java
@Test
void testDataTransferRepeatability() throws Exception {
    // 1. Run data transfer (first time)
    long firstCount = dataTransferService.transferTable(oracleConn, postgresConn, table);

    // 2. Verify data
    long postgresCount1 = rowCountService.getRowCount(postgresConn, schema, table);
    assertEquals(firstCount, postgresCount1);

    // 3. Run data transfer again (should truncate + re-transfer)
    long secondCount = dataTransferService.transferTable(oracleConn, postgresConn, table);

    // 4. Verify count unchanged
    assertEquals(firstCount, secondCount);

    // 5. Verify no orphaned staging columns
    ResultSet rs = postgresConn.query(
        "SELECT column_name FROM information_schema.columns " +
        "WHERE table_name=? AND column_name LIKE '%_staging'"
    );
    assertFalse(rs.next(), "No staging columns should remain");
}
```

**Error Handling Test:**
```java
@Test
void testConversionFailureRollback() throws Exception {
    // 1. Create table with BLOB column
    // 2. Add staging columns
    // 3. COPY invalid data (simulate failure)
    // 4. Attempt conversion (should fail)
    // 5. Verify transaction rolled back
    // 6. Verify staging columns still exist (for debugging)
    // 7. Verify oid column is NULL (no partial data)
}
```

### Manual Testing Checklist

- [ ] Create Oracle table with BLOB column containing image data (>1MB)
- [ ] Run full migration pipeline
- [ ] Verify PostgreSQL table has `oid` column
- [ ] Verify no `_staging` columns remain after transfer
- [ ] Query Large Object directly: `SELECT lo_get(doc_content) FROM table LIMIT 1`
- [ ] Test Java application with `@Lob` annotation on `byte[]` field
- [ ] Verify JDBC `ResultSet.getBlob()` works without errors
- [ ] Test NULL BLOB values (should remain NULL in PostgreSQL)
- [ ] Test empty BLOB values (should create empty Large Object)
- [ ] Re-run data transfer (verify repeatability, no errors)

---

## Rollback Plan

### If Issues Discovered During Implementation

**Phase 1 Changes (TypeConverter only):**
- Revert TypeConverter changes
- Drop and recreate affected tables with `bytea`/`text`
- Re-run data transfer

**Phase 2 Changes (Data Transfer):**
- If staging columns cause issues:
  - Manually drop staging columns: `ALTER TABLE ... DROP COLUMN {col}_staging`
  - Fix code, re-run transfer
- If conversion fails:
  - Check PostgreSQL logs for lo_from_bytea errors
  - Verify hex data is valid
  - Check Large Object quota (if any)

### If Issues Discovered in Production

**Symptoms:**
- Java application still fails with `getBlob()` errors
- Data corruption (Large Objects unreadable)

**Emergency Rollback:**
1. Revert TypeConverter to BLOB→bytea, CLOB→text
2. Drop PostgreSQL tables: `DROP TABLE schema.table CASCADE`
3. Recreate tables with old mappings
4. Re-run data transfer (will use bytea/text)
5. Investigate root cause

**Partial Rollback (Per-Table):**
- If only specific tables problematic:
  ```sql
  ALTER TABLE schema.table ADD COLUMN doc_content_bytea bytea;
  UPDATE schema.table SET doc_content_bytea = lo_get(doc_content);
  ALTER TABLE schema.table DROP COLUMN doc_content;
  ALTER TABLE schema.table RENAME COLUMN doc_content_bytea TO doc_content;
  ```

---

## Known Limitations

1. **Large Object Cleanup:**
   - Orphaned Large Objects not automatically cleaned
   - Requires periodic `vacuumlo` maintenance
   - Future enhancement: Track and clean up on re-run

2. **Large Object Size Limits:**
   - Current limit: 20MB (same as before)
   - PostgreSQL Large Objects support up to 4TB
   - May need adjustment based on data size

3. **Transaction Size:**
   - Large tables with many BLOBs create many Large Objects in single transaction
   - May hit transaction limits on very large datasets
   - Mitigation: Batch data transfer if needed

4. **Concurrent Access:**
   - Large Objects require explicit locking for concurrent updates
   - Application must use Large Object API for updates
   - Not an issue for read-only migration

5. **LONG and LONG RAW:**
   - Not migrated to `oid` (kept as `text`/`bytea`)
   - If these have `@Lob` annotations, may still fail
   - Recommendation: Verify no `@Lob` on LONG/LONG RAW columns

---

## Implementation Checklist

### Pre-Implementation
- [x] Create implementation plan document
- [ ] Review plan with stakeholders
- [ ] Identify test Oracle database with BLOB/CLOB columns
- [ ] Backup test database

### Phase 1: Type Mapping
- [ ] Update TypeConverter.java (3 lines)
- [ ] Write unit tests for TypeConverter
- [ ] Run unit tests
- [ ] Commit: "feat: Map Oracle BLOB/CLOB/NCLOB to PostgreSQL oid"

### Phase 2: Data Transfer Infrastructure
- [ ] Implement `detectOidColumns()`
- [ ] Implement `addStagingColumns()`
- [ ] Implement `convertStagingToLargeObjects()`
- [ ] Implement `dropStagingColumns()`
- [ ] Write unit tests for each method
- [ ] Commit: "feat: Add staging column lifecycle for LOB→oid conversion"

### Phase 3: Integration
- [ ] Modify `buildQuotedColumnList()` to accept oidColumns parameter
- [ ] Update `performCsvTransfer()` workflow
- [ ] Write integration tests
- [ ] Run full test suite
- [ ] Commit: "feat: Integrate LOB→oid conversion into data transfer"

### Phase 4: Testing
- [ ] Manual test: Create Oracle BLOB table
- [ ] Manual test: Run full migration pipeline
- [ ] Manual test: Verify PostgreSQL oid column
- [ ] Manual test: Verify Large Object readable
- [ ] Manual test: Test repeatability (re-run transfer)
- [ ] Manual test: Test Java @Lob with migrated data

### Phase 5: Documentation
- [ ] Update CLAUDE.md with new type mapping strategy
- [ ] Document Large Object maintenance requirements
- [ ] Add troubleshooting guide for LOB issues
- [ ] Commit: "docs: Document LOB→oid migration strategy"

### Post-Implementation
- [ ] Performance benchmark (compare bytea vs oid transfer times)
- [ ] Monitor first production migration
- [ ] Document lessons learned

---

## Success Criteria

✅ **Type Mapping:**
- TypeConverter correctly maps BLOB/CLOB/NCLOB → oid
- Unit tests pass

✅ **Table Creation:**
- PostgreSQL tables created with oid columns for BLOB/CLOB
- View stubs reference oid columns

✅ **Data Transfer:**
- Staging columns created, used, and removed automatically
- No staging columns remain after successful transfer
- Large Objects created and populated correctly
- NULL values preserved

✅ **Repeatability:**
- Re-running data transfer works without errors
- Truncate + re-transfer produces identical results

✅ **Java Compatibility:**
- Java `@Lob` annotations work with oid columns
- `ResultSet.getBlob()` succeeds (no errors)
- Binary data readable and matches Oracle source

✅ **Error Handling:**
- Conversion failures trigger transaction rollback
- Staging columns remain for debugging after failure
- Clear error messages in logs

---

## Open Questions / Future Enhancements

1. **Large Object Cleanup:**
   - Should we automatically clean up orphaned Large Objects from previous runs?
   - Implementation: Track created OIDs, delete on next run before truncate

2. **Performance Optimization:**
   - Can we use `lo_import()` instead of `lo_from_bytea()` for better performance?
   - Benchmark: bytea→oid conversion time for 1M rows with 1MB BLOBs

3. **Partial Transfer:**
   - Should we support incremental LOB updates?
   - Use case: Large tables where only some rows changed

4. **Compression:**
   - PostgreSQL Large Objects don't support TOAST compression by default
   - Should we compress BLOB data before `lo_from_bytea()`?

5. **Progress Tracking:**
   - Add progress reporting for LOB conversion (separate from COPY progress)
   - Useful for large tables with many LOBs

---

## Appendix: PostgreSQL Large Object Background

### What are Large Objects?

PostgreSQL Large Objects (LOs) are a facility for storing large data values (up to 4TB) outside of normal table storage. They use a separate storage area and are referenced by OID (Object Identifier).

### Key Characteristics:

1. **Storage:** Stored in `pg_largeobject` system catalog, not in table rows
2. **Access:** Requires `lo_*` functions or JDBC `Blob` API
3. **Transactions:** Fully transactional (ACID compliant)
4. **Permissions:** Separate permission system from table data
5. **References:** `oid` column stores 32-bit integer reference

### Large Object Functions:

- `lo_create(loid oid)` - Create new Large Object with specific OID
- `lo_from_bytea(loid oid, data bytea)` - Create LO from bytea data (auto-generates OID if loid=0)
- `lo_get(loid oid)` - Read entire Large Object as bytea
- `lo_put(loid oid, offset bigint, data bytea)` - Write data to Large Object
- `lo_open(loid oid, mode int)` - Open for streaming access
- `lo_close(fd int)` - Close Large Object descriptor

### Why Use Large Objects vs bytea?

| Feature | bytea | Large Object (oid) |
|---------|-------|-------------------|
| Size limit | ~1GB (TOAST) | 4TB |
| JDBC API | `getBytes()` | `getBlob()` |
| Streaming | No | Yes (lo_open/read/write) |
| Memory | Entire value in memory | Chunked streaming |
| Permissions | Table-level | Object-level |
| Cleanup | Automatic | Manual (vacuumlo) |

**For Java @Lob:** Must use `oid` to work with JDBC `Blob` API.

### Maintenance:

Large Objects are not automatically cleaned up when referencing table rows are deleted. Use `vacuumlo` utility:

```bash
vacuumlo -v -n database_name  # Dry run
vacuumlo -v database_name     # Delete orphaned LOs
```

Recommendation: Run `vacuumlo` weekly or after major data changes.

---

## Document Version History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-12-01 | Claude | Initial implementation plan |

