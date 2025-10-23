# Testing Strategy

This document outlines the comprehensive testing strategy for the Oracle-to-PostgreSQL migration tool, with emphasis on validating SQL transformations against real PostgreSQL databases.

## Philosophy

**Quality over Quantity**: Integration tests should be comprehensive, testing multiple features together rather than isolated micro-tests. Each test should validate real-world scenarios end-to-end.

## Testing Tiers

### Tier 1: PostgreSQL-Only Integration Tests ⭐ PRIMARY FOCUS
**Speed**: ⚡⚡⚡ Very Fast (100-500ms per test)
**Confidence**: ✅✅ High (validates actual SQL execution)
**Effort**: 🔨 Low-Medium

#### Concept
Use Testcontainers with PostgreSQL to validate transformed SQL executes correctly and produces expected results. No Oracle required.

#### Architecture
```
Test Flow:
1. Start PostgreSQL container (once per test class, reused)
2. Create test schema + sample data in @BeforeEach
3. Transform Oracle SQL → PostgreSQL SQL
4. Execute transformed SQL on PostgreSQL
5. Assert result set matches expectations
6. Container auto-cleanup after test class
```

#### Benefits
- **Fast**: Containers start in ~2 seconds, reused across test methods
- **Real validation**: Proves SQL is syntactically correct AND semantically correct
- **Result verification**: Can assert ORDER BY, JOIN order, aggregation correctness, hierarchy traversal
- **No Oracle dependency**: Tests SQL transformation module in isolation
- **CI/CD friendly**: Works on GitHub Actions, GitLab CI, etc.
- **Incremental**: Add tests as you implement new transformations

#### Test Categories
1. **CONNECT BY → CTE**: Hierarchy correctness, level counting, order preservation, WHERE clause distribution
2. **Oracle Functions**: NVL→COALESCE, DECODE→CASE produce same results
3. **JOINs**: Oracle outer join (+) syntax produces correct result sets
4. **Window Functions**: ROWNUM transformation correctness
5. **String Functions**: SUBSTR, INSTR, TRIM edge cases with actual data

#### Implementation Status
- ✅ Base infrastructure (`PostgresSqlValidationTestBase`) - **COMPLETE**
- ✅ First comprehensive test (`PostgresConnectByValidationTest`) - **COMPLETE**
  - 5 comprehensive tests created
  - Tests failing due to LEVEL pseudo-column bug (expected - bug in transformation code)
  - Infrastructure validated and working perfectly
- ⏳ Additional transformation tests (as features are implemented)

#### First Results (October 2025)
**Infrastructure**: ✅ Working perfectly
- PostgreSQL container starts in ~2-3 seconds
- Database setup, SQL execution, assertions all functional
- Auto-cleanup between tests works correctly

**Bug Discovered**: 🔍 CONNECT BY LEVEL handling
- Integration tests caught that transformation includes Oracle's `LEVEL` pseudo-column in PostgreSQL SQL
- Example: `SELECT emp_id, LEVEL AS lvl, 1 as level FROM ...` (should only have `1 as level`)
- PostgreSQL error: `ERROR: column "level" does not exist`
- **This validates the value of integration tests** - unit tests missed this bug!

**Next Steps**:
1. Fix unit tests to properly assert LEVEL column removal
2. Fix CONNECT BY transformation to handle LEVEL pseudo-column correctly
3. Verify all integration tests pass

---

### Tier 2: Full Migration Integration Tests
**Speed**: ⚡⚡ Medium (5-30 seconds per test)
**Confidence**: ✅✅✅ Very High (tests entire pipeline)
**Effort**: 🔨🔨 Medium

#### Concept
Test complete migration jobs (schema → tables → views → data transfer) using predefined StateService snapshots. No Oracle extraction, just PostgreSQL creation.

#### Architecture
```
Test Flow:
1. Load pre-built StateService JSON fixture (Oracle metadata)
2. Start clean PostgreSQL container
3. Run migration jobs sequentially:
   - PostgresSchemaCreationJob
   - PostgresObjectTypeCreationJob
   - PostgresTableCreationJob
   - PostgresViewImplementationJob (transformed views)
4. Insert sample data
5. Query views and verify results
```

#### Test Data Strategy
Create JSON fixtures for common Oracle schemas:
```
src/test/resources/fixtures/
  ├── hr-schema-simple.json         # 5 tables, 3 views
  ├── hr-schema-complex.json        # 20 tables, 15 views, object types
  ├── scott-emp-dept.json           # Classic SCOTT.EMP/DEPT
  └── edge-cases.json               # CONNECT BY, window functions, etc.
```

#### Benefits
- Tests real job flow: Validates job orchestration, dependency ordering
- Uses actual StateService: Tests metadata index building
- No Oracle extraction: Reuses pre-captured metadata (fast)
- Regression detection: Catches breaking changes in job pipeline

#### Implementation Status
- ⏳ Planned for Phase 2-3 (after Tier 1 tests stabilize)

---

### Tier 3: Oracle-PostgreSQL Comparative Tests (OPTIONAL)
**Speed**: ⚡ Slow (30s-5min per test)
**Confidence**: ✅✅✅ Absolute (compares Oracle vs PostgreSQL results)
**Effort**: 🔨🔨🔨 High

#### Concept
Run identical queries on both Oracle and PostgreSQL, assert result sets match exactly.

#### When to Use
- **Critical transformations**: CONNECT BY, complex window functions
- **Release validation**: Before deploying to production
- **Not for CI/CD**: Too slow for every commit

#### Benefits
- Absolute confidence: Proves semantic equivalence
- Catches subtle bugs: ORDER BY differences, NULL handling

#### Drawbacks
- ❌ Slow: Oracle container takes 30-60s to start
- ❌ Flaky: Oracle container stability issues
- ❌ Complex setup: License, memory requirements

#### Implementation Status
- ⏳ Future consideration (manual validation currently sufficient)

---

## Implementation Roadmap

### Phase 1: PostgreSQL Validation Tests (Week 1-2) ✅ IN PROGRESS

**Goal**: Validate transformed SQL executes correctly and produces expected results

**Steps**:
1. ✅ Add Testcontainers dependency to pom.xml
2. ✅ Create base test class: `PostgresSqlValidationTestBase`
3. ✅ Implement first comprehensive test: `PostgresConnectByValidationTest`
4. ⏳ Expand coverage as transformations are implemented:
   - Oracle functions (NVL, DECODE, SYSDATE)
   - Outer joins
   - ROWNUM/window functions
   - String functions

**Deliverables**:
- `PostgresConnectByValidationTest.java` (5-8 comprehensive tests)
- `PostgresOracleFunctionValidationTest.java` (when NVL/DECODE implemented)
- `PostgresJoinValidationTest.java` (when outer joins implemented)

**Expected Effort**: 2-3 days

---

### Phase 2: StateService Fixture Library (Week 2-3)

**Goal**: Create reusable metadata fixtures for testing full migration jobs

**Steps**:
1. Extract metadata from current test Oracle schemas
2. Serialize to JSON: `StateServiceJsonSerializer.java`
3. Create test fixtures:
   - `hr-simple.json` (5 tables, 3 views)
   - `connect-by-examples.json` (hierarchical queries)
   - `complex-functions.json` (package functions, type methods)

**Deliverables**:
- Fixture files in `src/test/resources/fixtures/`
- Fixture loader utility: `TestFixtureLoader.java`

**Expected Effort**: 1-2 days

---

### Phase 3: Full Migration Integration Tests (Week 3-4)

**Goal**: Test complete job pipelines with predefined metadata

**Steps**:
1. Create `MigrationIntegrationTestBase` with PostgreSQL container
2. Implement job orchestration tests:
   - Schema → Tables → Views pipeline
   - Constraint dependency ordering
   - View stub → implementation replacement
3. Add data verification tests

**Deliverables**:
- `ViewMigrationIntegrationTest.java`
- `ConstraintMigrationIntegrationTest.java`
- `CompleteMigrationIntegrationTest.java`

**Expected Effort**: 3-4 days

---

### Phase 4: CI/CD Integration (Week 4)

**Goal**: Run tests automatically on every commit

**Steps**:
1. Create GitHub Actions workflow (or GitLab CI)
2. Configure test reporting
3. Add test coverage reporting (JaCoCo)

**Deliverables**:
- `.github/workflows/integration-tests.yml`
- Maven profile: `integration-test`

**Expected Effort**: 1 day

---

## Technical Setup

### Maven Dependencies

```xml
<dependencies>
    <!-- Testcontainers for PostgreSQL -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Base Test Infrastructure

**Location**: `src/test/java/me/christianrobert/orapgsync/integration/`

**Key Classes**:
- `PostgresSqlValidationTestBase` - Base class for PostgreSQL validation tests
- Helper methods for:
  - Database setup/cleanup
  - SQL execution
  - Result set assertions
  - Transformation invocation

---

## Test Philosophy

### Comprehensive Over Numerous
**Prefer**: One test that validates CONNECT BY with:
- Simple hierarchy
- LEVEL pseudo-column
- WHERE clause filtering
- ORDER SIBLINGS BY
- Multiple START WITH conditions

**Over**: Five separate tests each checking one isolated aspect

### Real-World Scenarios
Tests should reflect actual Oracle views being migrated, not artificial minimal examples.

### Data-Driven Validation
Always verify result sets, not just SQL syntax:
- Assert row counts
- Assert column values
- Assert ordering (especially for hierarchies)
- Assert NULL handling
- Assert edge cases (empty result sets, single row, etc.)

---

## Expected Test Suite Metrics

After full implementation:

| Test Category | Test Classes | Test Methods | Execution Time | Confidence Level |
|--------------|-------------|--------------|----------------|------------------|
| **Unit Tests** (existing) | 40+ | ~300 | 5-10s | ✅ Medium (mocked) |
| **PostgreSQL Validation** | 5-8 | 30-50 | 30-60s | ✅✅ High (real DB) |
| **Full Migration Integration** | 3-5 | 15-25 | 60-120s | ✅✅✅ Very High |
| **Total** | **48-53** | **345-375** | **~2 minutes** | **✅✅✅** |

---

## Running Tests

### Run all tests
```bash
mvn test
```

### Run only unit tests (fast)
```bash
mvn test -Dgroups="!integration"
```

### Run only integration tests
```bash
mvn test -Dgroups="integration"
```

### Run specific test class
```bash
mvn test -Dtest=PostgresConnectByValidationTest
```

---

## CI/CD Integration

### GitHub Actions (Planned)
```yaml
name: Integration Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '18'
      - name: Run tests
        run: mvn test
```

### Docker-in-Docker Support
Testcontainers automatically detects CI environments and configures Docker appropriately.

---

## Key Benefits

1. **Fast Feedback Loop**: PostgreSQL validation tests run in ~30-60 seconds total
2. **No Oracle Dependency**: Development and CI/CD only need PostgreSQL
3. **High Confidence**: Tests prove SQL actually works, not just parses
4. **Result Verification**: Can assert ORDER BY, hierarchical correctness, aggregations
5. **Incremental**: Add tests as transformations are implemented
6. **CI/CD Ready**: Works on GitHub Actions, GitLab CI, etc.
7. **Regression Detection**: Catches breaking changes immediately
8. **Fixture Reuse**: Same metadata fixtures work for multiple test scenarios

---

## Maintenance

### Adding New Tests
1. Extend `PostgresSqlValidationTestBase`
2. Create schema + data in `@BeforeEach`
3. Transform SQL via `transformSql()`
4. Execute and assert results
5. Cleanup handled automatically

### Updating Fixtures
When Oracle metadata structure changes:
1. Re-extract metadata from test Oracle database
2. Update JSON fixtures
3. Re-run migration integration tests

### Performance Optimization
- Use container reuse: `.withReuse(true)`
- Minimize schema creation: Group related tests in same class
- Parallel test execution: Configure Surefire plugin

---

## Future Enhancements

1. **Performance benchmarking**: Track query execution time
2. **Query plan validation**: Verify PostgreSQL uses appropriate indexes
3. **Stress testing**: Large result sets, deep hierarchies
4. **Comparative testing**: Optional Oracle-PostgreSQL result comparison
5. **Test data generators**: Programmatically generate large hierarchies
