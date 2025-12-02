# Job Cancellation Implementation Plan

**Status:** Planning Phase
**Created:** 2025-12-02
**Goal:** Implement proper cancellation support for all jobs to enable clean state reset without server restart

---

## 1. Problem Analysis

### Current Issues
- **Uncontrollable jobs**: Once a job starts, it cannot be stopped gracefully
- **Database queries continue**: Even after `CompletableFuture.cancel(true)`, blocking database I/O continues
- **State reset fails**: Users cannot clean state without orphaned jobs sending database queries
- **Memory leaks**: Jobs accumulate in thread pool, consuming resources

### Root Cause
Jobs have no mechanism to:
1. Check if they should stop
2. Exit early from long-running operations
3. Respond to cancellation requests

---

## 2. Solution Architecture

### 2.1 Cancellation Token

**Purpose:** Provide a thread-safe, shareable cancellation signal

**Implementation:**
```java
package me.christianrobert.orapgsync.core.job.model;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe cancellation token for cooperative job cancellation.
 *
 * Jobs periodically check isCancelled() and exit early when cancelled.
 * This enables graceful shutdown without forcefully killing threads.
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Checks if cancellation has been requested.
     * Jobs should call this frequently and exit early if true.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Requests cancellation. Thread-safe.
     * Sets the cancellation flag that jobs will check.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Throws CancellationException if cancelled.
     * Convenience method for jobs to use in cancellation checks.
     */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new JobCancelledException("Job was cancelled");
        }
    }
}
```

**Key Design Principles:**
- ✅ **Thread-safe**: Uses `AtomicBoolean` for safe concurrent access
- ✅ **Cooperative**: Jobs must check the flag (can't force cancellation)
- ✅ **Non-intrusive**: Database queries complete normally, jobs just exit after
- ✅ **Exception-based**: `throwIfCancelled()` provides clean exit mechanism

### 2.2 Job Interface Changes

**Current Interface:**
```java
public interface Job<T> {
    CompletableFuture<T> execute(Consumer<JobProgress> progressCallback);
}
```

**New Interface (Option A - Preferred):**
```java
public interface Job<T> {
    CompletableFuture<T> execute(Consumer<JobProgress> progressCallback,
                                  CancellationToken cancellationToken);
}
```

**Migration Strategy:**
- Add new method signature
- Keep old method as `@Deprecated` that calls new method with empty token
- Migrate jobs incrementally
- Remove deprecated method in future release

### 2.3 Base Class Support

**AbstractDatabaseExtractionJob Enhancement:**
```java
public abstract class AbstractDatabaseExtractionJob<T> implements DatabaseExtractionJob<T> {

    // Injected dependencies remain unchanged
    protected CancellationToken cancellationToken;

    @Override
    public CompletableFuture<List<T>> execute(Consumer<JobProgress> progressCallback,
                                               CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performExtractionWithStateSaving(progressCallback);
            } catch (JobCancelledException e) {
                log.info("Job cancelled: {}", getJobId());
                throw e;
            } catch (Exception e) {
                log.error("{} extraction failed", getExtractionType(), e);
                throw new RuntimeException(...);
            }
        });
    }

    /**
     * Helper method for jobs to check cancellation.
     * Call this at strategic points (loop iterations, before queries).
     *
     * @throws JobCancelledException if job has been cancelled
     */
    protected void checkCancellation() {
        if (cancellationToken != null) {
            cancellationToken.throwIfCancelled();
        }
    }

    /**
     * Determines schemas with cancellation check.
     */
    protected List<String> determineSchemasToProcess(...) {
        checkCancellation(); // Check before expensive operation

        boolean doAllSchemas = ...;
        List<String> testSchemas = ...;
        // existing logic
    }
}
```

**AbstractDatabaseWriteJob Enhancement:**
- Same pattern as extraction job
- Add `cancellationToken` field
- Add `checkCancellation()` helper
- Insert cancellation checks at strategic points

### 2.4 JobService Integration

**JobService Changes:**
```java
@ApplicationScoped
public class JobService {

    // Add cancellation token storage
    private final Map<String, CancellationToken> cancellationTokens = new ConcurrentHashMap<>();

    public <T> String submitJob(Job<T> job) {
        String jobId = job.getJobId();
        JobExecution<T> execution = new JobExecution<>(job);

        // Create cancellation token for this job
        CancellationToken cancellationToken = new CancellationToken();
        cancellationTokens.put(jobId, cancellationToken);

        jobExecutions.put(jobId, execution);

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            execution.setStatus(JobStatus.RUNNING);
            execution.setStartTime(LocalDateTime.now());

            try {
                // Pass cancellation token to job
                T result = job.execute(progress -> {
                    execution.setProgress(progress);
                }, cancellationToken).get();

                execution.setResult(result);
                execution.setStatus(JobStatus.COMPLETED);
                return result;

            } catch (JobCancelledException e) {
                execution.setStatus(JobStatus.CANCELLED);
                execution.setEndTime(LocalDateTime.now());
                log.info("Job cancelled: {}", jobId);
                throw new RuntimeException("Job was cancelled", e);

            } catch (Exception e) {
                execution.setError(e);
                execution.setStatus(JobStatus.FAILED);
                throw new RuntimeException(...);
            } finally {
                // Clean up cancellation token
                cancellationTokens.remove(jobId);
            }
        }, executorService);

        execution.setFuture(future);
        return jobId;
    }

    /**
     * Cancels a specific job by ID.
     * Returns true if job was found and cancellation was requested.
     */
    public boolean cancelJob(String jobId) {
        CancellationToken token = cancellationTokens.get(jobId);
        if (token != null) {
            log.info("Requesting cancellation for job: {}", jobId);
            token.cancel();
            return true;
        }
        return false;
    }

    /**
     * Enhanced reset that properly cancels all jobs.
     */
    public void resetJobs() {
        int count = jobExecutions.size();
        log.info("Cancelling all jobs and resetting thread pool");

        // Request cancellation for all running jobs
        int cancelledCount = 0;
        for (Map.Entry<String, JobExecution<?>> entry : jobExecutions.entrySet()) {
            JobExecution<?> execution = entry.getValue();
            if (execution.getStatus() == JobStatus.RUNNING) {
                String jobId = entry.getKey();
                CancellationToken token = cancellationTokens.get(jobId);

                if (token != null) {
                    log.info("Requesting cancellation: {} ({})",
                        jobId, execution.getJob().getJobType());
                    token.cancel();
                    cancelledCount++;
                }

                // Also cancel CompletableFuture for immediate effect
                CompletableFuture<?> future = execution.getFuture();
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        if (cancelledCount > 0) {
            log.warn("Requested cancellation for {} running jobs", cancelledCount);
        }

        // Shut down old executor (wait for jobs to detect cancellation)
        shutdownExecutorService(executorService, 30);

        // Clear state
        jobExecutions.clear();
        cancellationTokens.clear();

        // Create new executor
        executorService = Executors.newCachedThreadPool();

        log.info("Job reset completed successfully");
    }
}
```

---

## 3. Cancellation Points Strategy

### 3.1 High-Priority Cancellation Points

Jobs should check cancellation at these critical points:

#### Loop Iterations
**Why:** Jobs process multiple items (tables, views, functions)
**Impact:** Prevents processing hundreds of remaining items
**Example:**
```java
for (TableMetadata table : tables) {
    checkCancellation(); // Check at start of each iteration

    // Process table...
}
```

#### Before Database Queries
**Why:** Prevents starting new expensive database operations
**Impact:** Avoids long-running queries after cancellation
**Example:**
```java
checkCancellation(); // Before query

try (PreparedStatement stmt = connection.prepareStatement(sql)) {
    ResultSet rs = stmt.executeQuery();
    // Process results...
}
```

#### After Major Operations
**Why:** Natural breakpoints between logical steps
**Impact:** Clean exit after completing a unit of work
**Example:**
```java
// Extract metadata
List<TableMetadata> metadata = extractMetadata(connection);

checkCancellation(); // After extraction, before processing

// Process metadata
processMetadata(metadata);
```

#### Long-Running Transformations
**Why:** ANTLR parsing can be expensive for large SQL/PL-SQL
**Impact:** Prevents transformation of remaining functions/views
**Example:**
```java
for (ViewMetadata view : views) {
    checkCancellation(); // Before transformation

    TransformationResult result = transformationService.transformSql(...);
    // Apply transformation...
}
```

### 3.2 Job-Specific Cancellation Points

| Job Type | Primary Cancellation Points | Reasoning |
|----------|----------------------------|-----------|
| **DataTransferJob** | • Before each table<br>• Before CSV export<br>• Before COPY command | Prevents transferring remaining tables |
| **ViewImplementationJob** | • Before each view<br>• Before SQL transformation<br>• Before CREATE OR REPLACE | Prevents processing hundreds of views |
| **FunctionImplementationJob** | • Before each function<br>• Before PL/SQL transformation<br>• Before stub replacement | Functions are slowest (ANTLR parsing) |
| **ConstraintCreationJob** | • Before each constraint<br>• Before dependency analysis | Constraints can be numerous |
| **ExtractionJobs** | • Before each schema<br>• Before each query<br>• After each result batch | Database queries are slowest |

### 3.3 Cancellation Check Frequency

**Rule of thumb:**
- ✅ **Always check:** At start of loop iterations
- ✅ **Always check:** Before database operations
- ✅ **Optional:** After quick operations (< 100ms)
- ❌ **Never check:** Inside tight inner loops (performance overhead)

**Example - Good balance:**
```java
for (TableMetadata table : tables) {
    checkCancellation(); // ✅ Check at loop start

    for (ColumnMetadata column : table.getColumns()) {
        // ❌ Don't check here (tight loop, quick operations)
        processColumn(column);
    }

    checkCancellation(); // ✅ Check before expensive operation
    transferData(table);
}
```

---

## 4. Phased Implementation Plan

### Phase 1: Foundation (Critical - Must Complete First)
**Goal:** Establish cancellation infrastructure

**Tasks:**
1. ✅ Create `CancellationToken` class
2. ✅ Create `JobCancelledException` class
3. ✅ Update `Job` interface (add new method, deprecate old)
4. ✅ Update `AbstractDatabaseExtractionJob` with cancellation support
5. ✅ Update `AbstractDatabaseWriteJob` with cancellation support
6. ✅ Update `JobService` with token management and `cancelJob()` method
7. ✅ Add REST endpoint: `POST /api/jobs/{jobId}/cancel`
8. ✅ Update `/api/state/reset` to use proper cancellation

**Deliverables:**
- All base classes support cancellation
- JobService can cancel individual jobs
- State reset properly cancels all jobs

**Testing:**
- Start a long-running job (data transfer)
- Call `/api/state/reset`
- Verify job stops within 5 seconds (not 30+ seconds)

---

### Phase 2: High-Impact Jobs (Immediate User Benefit)
**Goal:** Fix the slowest, most problematic jobs first

**Priority 1 - Data Transfer (Highest Impact):**
- ✅ `DataTransferJob` - Add checks before each table
- ✅ `CsvDataTransferService` - Add checks in `transferTable()` method

**Priority 2 - Transformation Jobs (Second Highest):**
- ✅ `PostgresViewImplementationJob` - Check before each view
- ✅ `PostgresFunctionImplementationJob` - Check before each function
- ✅ `PostgresTypeMethodImplementationJob` - Check before each method
- ✅ `PostgresTriggerImplementationJob` - Check before each trigger

**Priority 3 - Creation Jobs (Third Highest):**
- ✅ `PostgresTableCreationJob` - Check before each table
- ✅ `PostgresConstraintCreationJob` - Check before each constraint
- ✅ `PostgresObjectTypeCreationJob` - Check before each type

**Deliverables:**
- All long-running jobs can be cancelled mid-execution
- Users can reset state even during active data transfer

**Testing:**
- Start data transfer job with 100+ tables
- Call reset after 10 tables
- Verify job stops processing remaining tables

---

### Phase 3: Extraction Jobs (Comprehensive Coverage)
**Goal:** Complete cancellation support for all extraction jobs

**Tasks:**
- ✅ Update all Oracle extraction jobs
- ✅ Update all PostgreSQL extraction jobs
- ✅ Add cancellation checks before schema loops
- ✅ Add cancellation checks before database queries

**Jobs to Update:**
- `OracleTableMetadataExtractionJob`
- `OracleViewExtractionJob`
- `OracleFunctionExtractionJob`
- `OracleTypeMethodExtractionJob`
- `OracleTriggerExtractionJob`
- `OracleObjectDataTypeExtractionJob`
- `OracleSequenceExtractionJob`
- `OracleSynonymExtractionJob`
- `OracleConstraintSourceStateJob`
- `OracleRowCountExtractionJob`
- (Similar for PostgreSQL extraction jobs)

**Deliverables:**
- All extraction jobs support cancellation
- No job is uncancellable

---

### Phase 4: Verification Jobs (Low Priority)
**Goal:** Complete cancellation support for verification jobs

**Tasks:**
- ✅ Update all verification jobs
- ✅ Add cancellation checks in verification loops

**Jobs to Update:**
- `PostgresViewStubVerificationJob`
- `PostgresViewImplementationVerificationJob`
- `PostgresFunctionStubVerificationJob`
- `PostgresFunctionImplementationVerificationJob`
- `PostgresTypeMethodStubVerificationJob`
- `PostgresConstraintVerificationJob`
- `PostgresTriggerVerificationJob`
- `PostgresOracleCompatVerificationJob`

**Deliverables:**
- 100% job coverage for cancellation support

---

### Phase 5: Frontend Integration (User Experience)
**Goal:** Expose cancellation to users via UI

**Tasks:**
1. ✅ Add "Cancel Job" button for each running job
2. ✅ Add "Cancel All Jobs" button in header/toolbar
3. ✅ Update reset button to show "Cancelling jobs..." state
4. ✅ Add visual feedback when jobs are cancelled
5. ✅ Update job status badges to show "CANCELLED" state

**Frontend Changes:**
```javascript
// Add to each job service (e.g., view-service.js)
async function cancelJob(jobId) {
    const response = await fetch(`/api/jobs/${jobId}/cancel`, {
        method: 'POST'
    });
    return response.ok;
}

// Update reset functionality in orchestration-service.js
async function resetAll() {
    updateStatus('Cancelling all jobs...');

    const response = await fetch('/api/state/reset');

    if (response.ok) {
        updateStatus('State reset successfully');
        // Reload UI...
    }
}
```

**UI Mockup:**
```
┌─────────────────────────────────────────────────────┐
│ Oracle to PostgreSQL Migration                      │
├─────────────────────────────────────────────────────┤
│                                                      │
│ [Reset All]  [Cancel All Jobs]                     │
│                                                      │
│ Data Transfer Job #1234                             │
│ Status: RUNNING (45%)                               │
│ [Cancel Job]                                        │
│                                                      │
│ View Implementation Job #5678                       │
│ Status: CANCELLED                                   │
│                                                      │
└─────────────────────────────────────────────────────┘
```

**Deliverables:**
- Users can cancel individual jobs
- Users can cancel all jobs at once
- Clear visual feedback for cancelled jobs

---

## 5. Testing Strategy

### 5.1 Unit Tests

**CancellationToken Tests:**
```java
@Test
void testCancellationToken_initiallyNotCancelled() {
    CancellationToken token = new CancellationToken();
    assertFalse(token.isCancelled());
}

@Test
void testCancellationToken_cancelSetsFlag() {
    CancellationToken token = new CancellationToken();
    token.cancel();
    assertTrue(token.isCancelled());
}

@Test
void testCancellationToken_throwIfCancelled() {
    CancellationToken token = new CancellationToken();
    token.cancel();

    assertThrows(JobCancelledException.class, () -> {
        token.throwIfCancelled();
    });
}

@Test
void testCancellationToken_threadSafety() throws Exception {
    CancellationToken token = new CancellationToken();

    // Simulate multiple threads checking/cancelling
    CompletableFuture<Void> checker = CompletableFuture.runAsync(() -> {
        for (int i = 0; i < 1000; i++) {
            token.isCancelled();
        }
    });

    CompletableFuture<Void> canceller = CompletableFuture.runAsync(() -> {
        try { Thread.sleep(5); } catch (Exception e) {}
        token.cancel();
    });

    CompletableFuture.allOf(checker, canceller).get();
    assertTrue(token.isCancelled());
}
```

**Job Tests:**
```java
@Test
void testDataTransferJob_cancelsGracefully() throws Exception {
    // Setup: 100 tables in state
    List<TableMetadata> tables = createTestTables(100);
    stateService.setOracleTableMetadata(tables);

    // Start job
    CancellationToken token = new CancellationToken();
    DataTransferJob job = new DataTransferJob(...);

    CompletableFuture<DataTransferResult> future = job.execute(
        progress -> {}, token);

    // Cancel after 100ms (should process ~5 tables)
    Thread.sleep(100);
    token.cancel();

    // Verify job stopped early
    try {
        DataTransferResult result = future.get(5, TimeUnit.SECONDS);
        assertTrue(result.getTransferredCount() < 100,
            "Job should stop before processing all tables");
    } catch (ExecutionException e) {
        assertTrue(e.getCause() instanceof JobCancelledException);
    }
}
```

### 5.2 Integration Tests

**Reset Functionality Test:**
```java
@Test
@QuarkusTest
void testResetDuringLongRunningJob() {
    // Start a long-running job
    Response startResponse = given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/jobs/oracle/table/extract")
        .then()
        .statusCode(200)
        .extract().response();

    String jobId = startResponse.jsonPath().getString("jobId");

    // Wait for job to start
    Thread.sleep(500);

    // Call reset
    long resetStart = System.currentTimeMillis();
    given()
        .when()
        .get("/api/state/reset")
        .then()
        .statusCode(200);
    long resetDuration = System.currentTimeMillis() - resetStart;

    // Verify reset completed quickly (< 5 seconds, not 30+ seconds)
    assertTrue(resetDuration < 5000,
        "Reset should complete within 5 seconds with proper cancellation");

    // Verify job is cancelled
    Response statusResponse = given()
        .when()
        .get("/api/jobs/" + jobId + "/status")
        .then()
        .statusCode(200)
        .extract().response();

    String status = statusResponse.jsonPath().getString("status");
    assertEquals("CANCELLED", status);
}
```

### 5.3 Manual Testing Checklist

**Phase 1 Testing:**
- [ ] Start data transfer job with 100+ tables
- [ ] Call `/api/state/reset` after 5 tables
- [ ] Verify job stops within 5 seconds
- [ ] Verify state is cleared
- [ ] Verify no orphaned database connections

**Phase 2 Testing:**
- [ ] Start view implementation job with 50+ views
- [ ] Call reset mid-execution
- [ ] Verify remaining views are not processed
- [ ] Start function implementation job
- [ ] Cancel individual job via UI
- [ ] Verify job status shows CANCELLED

**Phase 3 Testing:**
- [ ] Start extraction jobs for all schemas
- [ ] Cancel via reset button
- [ ] Verify all jobs stop
- [ ] Restart server
- [ ] Verify no stale connections or threads

---

## 6. Migration Guide for Job Developers

### 6.1 Updating Existing Jobs

**Step 1: Update method signature**
```java
// Old:
@Override
protected DataTransferResult performWriteOperation(
    Consumer<JobProgress> progressCallback) throws Exception {
    // ...
}

// New: No changes needed! Base class handles cancellation token
```

**Step 2: Add cancellation checks**
```java
@Override
protected DataTransferResult performWriteOperation(
    Consumer<JobProgress> progressCallback) throws Exception {

    List<TableMetadata> tables = stateService.getOracleTableMetadata();

    for (TableMetadata table : tables) {
        checkCancellation(); // ✅ Add this line

        transferTable(table);
    }

    return result;
}
```

**Step 3: Test cancellation**
- Start job with large dataset
- Call reset/cancel mid-execution
- Verify job stops processing

### 6.2 Creating New Jobs

**Template for new extraction jobs:**
```java
@Dependent
public class MyNewExtractionJob extends AbstractDatabaseExtractionJob<MyMetadata> {

    @Override
    protected List<MyMetadata> performExtraction(
        Consumer<JobProgress> progressCallback) throws Exception {

        // Get schemas
        List<String> schemas = determineSchemasToProcess(progressCallback);
        List<MyMetadata> results = new ArrayList<>();

        for (String schema : schemas) {
            checkCancellation(); // ✅ Check at start of loop

            // Extract from schema
            List<MyMetadata> schemaResults = extractFromSchema(schema);
            results.addAll(schemaResults);
        }

        return results;
    }

    private List<MyMetadata> extractFromSchema(String schema) {
        checkCancellation(); // ✅ Check before expensive operation

        try (Connection conn = connectionService.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL)) {

            ResultSet rs = stmt.executeQuery();
            List<MyMetadata> results = new ArrayList<>();

            while (rs.next()) {
                // Process results...
                results.add(...);
            }

            return results;
        }
    }
}
```

---

## 7. Performance Considerations

### 7.1 Overhead Analysis

**Cancellation check cost:**
- `AtomicBoolean.get()`: ~5-10 nanoseconds
- Negligible for most operations
- Only check at strategic points (not tight loops)

**Memory overhead:**
- One `CancellationToken` per active job: ~32 bytes
- 100 concurrent jobs: ~3.2 KB
- Negligible compared to job memory usage (MB to GB)

### 7.2 Benchmarking

**Test: Cancellation check overhead**
```java
@Test
void benchmarkCancellationCheckOverhead() {
    CancellationToken token = new CancellationToken();

    long start = System.nanoTime();
    for (int i = 0; i < 1_000_000; i++) {
        token.isCancelled();
    }
    long duration = System.nanoTime() - start;

    System.out.printf("1M cancellation checks: %d ms%n",
        duration / 1_000_000);

    // Expected: < 10ms for 1 million checks
    assertTrue(duration < 10_000_000);
}
```

**Expected results:**
- 1 million checks: < 10ms
- Per-check overhead: < 10 nanoseconds
- Impact on job execution: < 0.01%

---

## 8. Edge Cases and Error Handling

### 8.1 Cancellation During Database Transaction

**Scenario:** Job is cancelled while database transaction is active

**Handling:**
```java
try (Connection conn = connectionService.getConnection()) {
    conn.setAutoCommit(false);

    for (TableMetadata table : tables) {
        try {
            checkCancellation(); // May throw JobCancelledException

            createTable(conn, table);
            conn.commit();

        } catch (JobCancelledException e) {
            conn.rollback(); // ✅ Clean rollback
            throw e; // Propagate cancellation
        } catch (SQLException e) {
            conn.rollback();
            log.error("Failed to create table", e);
        }
    }
}
```

**Key principles:**
- ✅ Always rollback on cancellation
- ✅ Propagate `JobCancelledException` to JobService
- ✅ Log cancellation for debugging

### 8.2 Cancellation During ANTLR Parsing

**Scenario:** Job is cancelled while parsing large PL/SQL function

**Handling:**
```java
for (FunctionMetadata function : functions) {
    checkCancellation(); // ✅ Check before parsing

    try {
        // ANTLR parsing can take 100-500ms for large functions
        TransformationResult result = transformationService
            .transformFunction(function.getSource());

        checkCancellation(); // ✅ Check after parsing

        applyTransformation(result);

    } catch (JobCancelledException e) {
        log.info("Function transformation cancelled: {}",
            function.getQualifiedName());
        throw e;
    }
}
```

**Note:** ANTLR parsing itself cannot be interrupted. We check before/after to minimize wasted work.

### 8.3 Race Condition: Cancel During Cleanup

**Scenario:** Job is cancelled during final cleanup phase

**Handling:**
```java
@Override
protected DataTransferResult performWriteOperation(...) {
    try {
        // Main work
        for (TableMetadata table : tables) {
            checkCancellation();
            transferTable(table);
        }

        return result;

    } finally {
        // Cleanup always runs, even if cancelled
        // Do NOT check cancellation in finally block
        closeResources();
    }
}
```

**Key principles:**
- ✅ Cleanup runs regardless of cancellation
- ❌ Never call `checkCancellation()` in `finally` blocks
- ✅ Log cleanup completion for debugging

### 8.4 Multiple Cancellation Requests

**Scenario:** User clicks "Cancel" multiple times

**Handling:**
- `CancellationToken.cancel()` is idempotent
- Multiple calls have no additional effect
- Job detects cancellation on first check

**JobService logic:**
```java
public boolean cancelJob(String jobId) {
    CancellationToken token = cancellationTokens.get(jobId);
    if (token != null) {
        token.cancel(); // Idempotent, safe to call multiple times
        return true;
    }
    return false;
}
```

---

## 9. Success Criteria

### Definition of Done (Phase 1)
- ✅ Infrastructure complete (Token, exceptions, interfaces)
- ✅ Base classes support cancellation
- ✅ JobService can cancel jobs
- ✅ State reset uses proper cancellation
- ✅ Unit tests pass
- ✅ Manual testing: Reset stops jobs within 5 seconds

### Definition of Done (Phase 2)
- ✅ Data transfer job cancellable mid-execution
- ✅ Transformation jobs cancellable
- ✅ Creation jobs cancellable
- ✅ Integration tests pass
- ✅ Manual testing: All high-impact jobs respond to cancellation

### Definition of Done (Complete)
- ✅ All 40+ jobs support cancellation
- ✅ Frontend exposes cancellation controls
- ✅ Documentation updated (CLAUDE.md)
- ✅ Zero uncancellable jobs
- ✅ Production-ready reset functionality

---

## 10. Future Enhancements

### 10.1 Timeout Support

**Goal:** Automatically cancel jobs that run too long

**Implementation:**
```java
public <T> String submitJobWithTimeout(Job<T> job, long timeoutMinutes) {
    String jobId = submitJob(job);

    // Schedule automatic cancellation
    executorService.schedule(() -> {
        if (!isJobComplete(jobId)) {
            log.warn("Job {} exceeded timeout, cancelling", jobId);
            cancelJob(jobId);
        }
    }, timeoutMinutes, TimeUnit.MINUTES);

    return jobId;
}
```

### 10.2 Graceful Shutdown Hook

**Goal:** Cancel all jobs on JVM shutdown

**Implementation:**
```java
@PreDestroy
public void shutdownGracefully() {
    log.info("JVM shutdown detected, cancelling all jobs");

    // Request cancellation for all running jobs
    for (String jobId : jobExecutions.keySet()) {
        cancelJob(jobId);
    }

    // Wait for jobs to detect cancellation
    shutdownExecutorService(executorService, 30);
}
```

### 10.3 Pause/Resume Support

**Goal:** Allow jobs to be paused and resumed

**Enhancement:**
```java
public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public boolean isPaused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public void waitIfPaused() {
        while (isPaused() && !isCancelled()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
```

---

## 11. References

### Related Documentation
- [CLAUDE.md](../CLAUDE.md) - Project architecture overview
- [TRANSFORMATION.md](TRANSFORMATION.md) - SQL/PL-SQL transformation details
- [PACKAGE_SEGMENTATION_IMPLEMENTATION_PLAN.md](PACKAGE_SEGMENTATION_IMPLEMENTATION_PLAN.md) - Memory optimization strategy

### Java Concurrency Resources
- [Java Concurrency in Practice](https://jcip.net/) - Chapter 7: Cancellation and Shutdown
- [AtomicBoolean Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/AtomicBoolean.html)
- [CompletableFuture Cancellation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html#cancel-boolean-)

### Design Patterns
- **Cooperative Cancellation Pattern**: Jobs check flag periodically
- **Exception-Based Exit**: Clean unwinding via `JobCancelledException`
- **Token Pattern**: Shareable cancellation state

---

## 12. Implementation Checklist

### Phase 1: Foundation
- [ ] Create `CancellationToken` class
- [ ] Create `JobCancelledException` class
- [ ] Update `Job` interface with new method signature
- [ ] Update `AbstractDatabaseExtractionJob` with cancellation support
- [ ] Update `AbstractDatabaseWriteJob` with cancellation support
- [ ] Update `JobService.submitJob()` to create and pass tokens
- [ ] Update `JobService.resetJobs()` to request cancellation
- [ ] Add `JobService.cancelJob(String jobId)` method
- [ ] Add REST endpoint `POST /api/jobs/{jobId}/cancel`
- [ ] Write unit tests for `CancellationToken`
- [ ] Write unit tests for job cancellation
- [ ] Manual testing: Verify reset works

### Phase 2: High-Impact Jobs
- [ ] Update `DataTransferJob`
- [ ] Update `CsvDataTransferService.transferTable()`
- [ ] Update `PostgresViewImplementationJob`
- [ ] Update `PostgresFunctionImplementationJob`
- [ ] Update `PostgresTypeMethodImplementationJob`
- [ ] Update `PostgresTriggerImplementationJob`
- [ ] Update `PostgresTableCreationJob`
- [ ] Update `PostgresConstraintCreationJob`
- [ ] Update `PostgresObjectTypeCreationJob`
- [ ] Integration tests for each job
- [ ] Manual testing with real database

### Phase 3: Extraction Jobs
- [ ] Update all Oracle extraction jobs (9 jobs)
- [ ] Update all PostgreSQL extraction jobs (3 jobs)
- [ ] Unit tests for extraction jobs
- [ ] Integration tests

### Phase 4: Verification Jobs
- [ ] Update all verification jobs (8 jobs)
- [ ] Unit tests
- [ ] Integration tests

### Phase 5: Frontend
- [ ] Add "Cancel Job" button for running jobs
- [ ] Add "Cancel All Jobs" button
- [ ] Update reset button with cancelling state
- [ ] Add CANCELLED status badge styling
- [ ] Update polling logic to handle CANCELLED state
- [ ] Manual UI testing

### Documentation
- [ ] Update CLAUDE.md with cancellation architecture
- [ ] Add code comments to key methods
- [ ] Update API documentation (Swagger)
- [ ] Create developer guide for new jobs

---

**End of Implementation Plan**
