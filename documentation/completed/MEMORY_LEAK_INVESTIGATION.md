# Memory Leak and Overlooked State Investigation Report

Date: 2025-11-09
Investigation Scope: Complete codebase analysis for potential memory leaks in reset functionality

## Executive Summary

The reset functionality in the application is **INCOMPLETE**. There are critical memory leaks and overlooked state:

1. **CRITICAL: Unbounded Thread Pool (JobService)** - Creates unlimited cached thread pools per job
2. **HIGH: Type Evaluator Caches** - SimpleTypeEvaluator has per-query caches that aren't reset
3. **MEDIUM: TransformationContext Query-Level State** - Alias/CTE maps persist between transformations
4. **MEDIUM: PackageContext Caches** - In-memory package body ASTs retained indefinitely
5. **LOW: ANTLR DFA Clearing** - Already handled properly, but verification needed

---

## Current Reset Functionality

### StateService.resetState() (Lines 484-522)

**What it DOES clear:**
- All metadata lists (schemas, tables, views, functions, etc.)
- All result objects (creation results, implementation results)
- Package function storage (full/stub/reduced)

### JobService.resetJobs() (Lines 160-165)

**What it DOES clear:**
- jobExecutions map via .clear()

**CRITICAL PROBLEM:** Clears the job map but NOT the associated CompletableFuture objects or their thread pools.

---

## Static/Persistent State Found in Codebase

### 1. CRITICAL: Unbounded Thread Pools (JobService)

**Location:** `/src/main/java/me/christianrobert/orapgsync/core/job/service/JobService.java:91`

```java
CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
    // ... job execution ...
}, Executors.newCachedThreadPool());  // ← CREATES NEW THREAD POOL EVERY TIME
```

**Why This Leaks:**
- newCachedThreadPool() creates a new unbounded ExecutorService for EVERY job submission
- Thread pools are referenced by CompletableFuture objects
- When resetJobs() clears jobExecutions map, CompletableFuture references are lost
- Thread pools themselves persist in memory with active threads
- Can accumulate hundreds of thread pools over migration runs
- Each pool holds idle threads for 60 seconds (default timeout)

**Risk Level:** CRITICAL
**Memory Impact:** HIGH (each pool can hold multiple threads)

---

### 2. HIGH: Type Evaluator Per-Query Caches

**Location:** `/src/main/java/me/christianrobert/orapgsync/transformer/type/SimpleTypeEvaluator.java:33`

```java
private final Map<String, TypeInfo> typeCache = new HashMap<>();
```

**The Problem:**
- Each transformation creates new SimpleTypeEvaluator instance
- Maintains HashMap typeCache (token position → TypeInfo)
- clearCache() method exists but:
  - Transformation code doesn't call it between transformations
  - Not called during reset

**Risk Level:** HIGH
**Memory Impact:** MEDIUM (per-transformation state)
**When It Leaks:**
- During view/function transformations with multiple queries
- Cache accumulates without clearing
- Large transformations could retain significant memory

**Current Reset Coverage:** NOT CLEARED

---

### 3. MEDIUM: TransformationContext Query-Level State

**Location:** `/src/main/java/me/christianrobert/orapgsync/transformer/context/TransformationContext.java`

```java
private final Map<String, String> tableAliases;  // Line 253
private final Set<String> cteNames;              // Line 254
private final Deque<VariableScope> variableScopeStack;  // Line 277
```

**The Problem:**
- Mutable state during transformation
- clearAliases() and clearCTEs() methods provided but:
  - Only called at query boundaries within single transformation
  - Not called during reset
- Variable scope stack could accumulate if unbalanced

**Risk Level:** MEDIUM
**Memory Impact:** LOW (small collections)
**Current Reset Coverage:** NOT CLEARED (ephemeral per transformation - should be OK)

---

### 4. MEDIUM: PackageContext In-Memory AST Caches

**Location:** `/src/main/java/me/christianrobert/orapgsync/transformer/packagevariable/PackageContext.java:37-38`

```java
private String packageBodySource;                              // Full source
private PlSqlParser.Create_package_bodyContext packageBodyAst; // Parsed AST
```

**The Problem:**
- Entire package body (100KB+ for large packages) loaded into memory
- Parsed into ANTLR AST and cached
- Cache exists for duration of extraction job
- If packageContextCache stored elsewhere: persists beyond job

**Risk Level:** MEDIUM
**Memory Impact:** MEDIUM-HIGH (large Oracle packages)
**Current Risk:** Appears ephemeral, but needs verification

**Current Reset Coverage:** NOT DIRECTLY CLEARED

---

### 5. LOW: ANTLR Static Caches (Already Handled Correctly)

**Location:** `/src/main/java/me/christianrobert/orapgsync/transformer/parser/AntlrParser.java:35-40, 167-180`

**Status:** ✓ PROPERLY HANDLED
- Clears DFA cache after each parse
- Clears PredictionContextCache via reflection
- Cleaned per-parse operation (not just on reset)

**Risk Level:** LOW - No action needed

---

## Missing From Current Reset

| Component | Currently Cleared | Missing? | Risk Level |
|-----------|-------------------|----------|-----------|
| jobExecutions map | ✓ Yes | No | N/A |
| **ExecutorService pools** | **✗ No** | **YES** | **CRITICAL** |
| SimpleTypeEvaluator caches | ✗ No | Yes | **HIGH** |
| TransformationContext mutable state | ✗ No | Maybe | **MEDIUM** |
| PackageContext AST caches | ✗ No | Maybe | **MEDIUM** |
| ANTLR static caches | ✓ Per-parse | No | N/A |
| ConfigService state | ✗ No | No (intentional) | N/A |

---

## Risk Assessment

### CRITICAL - Must Fix Now
1. **Thread Pool Leak in JobService**
   - Creates unlimited pools per job
   - Never shut down on reset
   - Can accumulate 100+ pools per migration run
   - **ACTION REQUIRED:** Store and shutdown ExecutorService on reset

### HIGH - Should Fix
1. **Type Evaluator Caches**
   - Not cleared between transformations
   - Grows unbounded for large migrations
   - **ACTION REQUIRED:** Clear caches after each transformation

### MEDIUM - Monitor/Document
1. **PackageContext AST Caches**
   - Verify lifecycle is truly ephemeral
   - Document assumptions about garbage collection
   - Monitor for persistence

2. **TransformationContext State**
   - Document ephemeral nature assumption
   - Add assertions to verify state reset

### LOW - Document
1. **ANTLR Caches** - Already working correctly
2. **ConfigService** - Intentional persistence

---

## Detailed Findings

### Files With Issues

**1. JobService.java (CRITICAL)**
- Line 91: Creates new cached thread pool per job
- Line 160-165: resetJobs() doesn't shutdown pools
- Thread pool accumulation scenario:
  ```
  Job 1 → ExecutorService #1 created
  Job 2 → ExecutorService #2 created
  ...
  Job 100 → ExecutorService #100 created
  resetJobs() → jobExecutions.clear()
  Result: ExecutorService #1-100 still active with threads
  ```

**2. SimpleTypeEvaluator.java (HIGH)**
- Line 33: Private HashMap typeCache
- Line 80-81: clearCache() method exists but not called
- Cache grows per transformation
- Not shared between transformations (isolated)
- Should be cleared after each transformation

**3. TransformationService.java (HIGH)**
- Creates SimpleTypeEvaluator instances
- Doesn't call clearCache() on completion
- Should add cleanup in finally block

**4. TransformationContext.java (MEDIUM)**
- Line 253-254: tableAliases and cteNames maps
- Line 277: variableScopeStack deque
- Methods exist: clearAliases(), clearCTEs()
- Should be called between queries
- Should verify stack balance on completion

**5. AntlrParser.java (GOOD)**
- Lines 172, 177: Properly clears caches
- clearDFA() and clearPredictionContextCache() called every parse
- No issues here

---

## Testing Recommendations

1. **Thread Pool Accumulation Test**
   - Submit 100 jobs sequentially
   - Measure thread count before/after reset
   - Verify threads return to baseline

2. **Type Cache Growth Test**
   - Transform 1000 views
   - Monitor SimpleTypeEvaluator instances
   - Verify caches cleared or garbage collected

3. **Memory Stability Test**
   - Run full migration (all extraction/creation jobs)
   - Reset state multiple times
   - Verify heap size returns to baseline
   - Monitor for unbounded growth

4. **Thread Pool Lifecycle Test**
   - Monitor ExecutorService instance count
   - Verify no accumulation after reset

---

## Summary

The reset functionality clears **metadata** but misses **infrastructure state**:

| Category | Status |
|----------|--------|
| Metadata (tables, views, functions, etc.) | ✓ Properly cleared |
| Result objects (creation results) | ✓ Properly cleared |
| Job execution history | ✓ Properly cleared |
| **Thread pools** | ✗ **CRITICAL LEAK** |
| **Type evaluator caches** | ✗ **HIGH LEAK** |
| **Transformation caches** | ✗ **MEDIUM LEAK** |
| ANTLR caches | ✓ Properly cleared (per-parse) |

**Recommendation:** Fix thread pool leak immediately (CRITICAL), then address type evaluator caches (HIGH), then verify PackageContext lifecycle (MEDIUM).

