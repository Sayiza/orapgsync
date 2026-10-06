# Reset Functionality Gaps - Quick Reference

## What Gets Reset

### StateService.resetState()
- Metadata: schemas, tables, views, functions, sequences, object types, type methods
- Results: all creation/implementation/verification results
- Package function storage: full/stub/reduced sources
- Synonyms: cleared
- Row counts: cleared

### JobService.resetJobs()
- Job execution map: cleared

---

## What DOESN'T Get Reset (Memory Leaks)

### CRITICAL: Thread Pools
```
Location: JobService.submitJob() line 91
Issue: Executors.newCachedThreadPool() creates new pool per job
Reset Gap: resetJobs() doesn't shutdown ExecutorService instances
Impact: Can accumulate 100+ thread pools with active threads
```

### HIGH: Type Evaluator Caches
```
Location: SimpleTypeEvaluator line 33 (private Map typeCache)
Issue: HashMap caches type information per query
Reset Gap: clearCache() method exists but never called
Impact: Memory accumulates during large transformations
Files:
  - SimpleTypeEvaluator.java
  - TransformationService.java
```

### MEDIUM: PackageContext ASTs
```
Location: PackageContext lines 37-38
Issue: Entire package bodies cached in memory
Reset Gap: No explicit cleanup, relies on garbage collection
Impact: Large Oracle packages (100KB+) held in memory
Risk: IF packageContextCache stored anywhere outside job
```

### MEDIUM: TransformationContext Mutable State
```
Location: TransformationContext lines 253-254, 277
Issue: tableAliases, cteNames, variableScopeStack
Reset Gap: Not reset between transformations
Impact: Could accumulate if context reused
Note: Currently ephemeral per transformation (low risk)
```

---

## Priority Fixes

1. **CRITICAL (Do First)**
   - Fix JobService thread pool leak
   - Choose: Fix ExecutorService pooling OR shutdown on reset

2. **HIGH (Do Second)**
   - Clear SimpleTypeEvaluator caches after transformations
   - Add clearCache() calls in TransformationService

3. **MEDIUM (Verify/Document)**
   - Verify PackageContext lifecycle is truly ephemeral
   - Add assertions for TransformationContext state cleanup
   - Document assumptions

---

## Quick Checklist for Testing

- [ ] Submit 100 jobs, reset, verify thread count returns to baseline
- [ ] Transform 1000 views, monitor heap size for unbounded growth
- [ ] Run full migration, reset multiple times, verify stability
- [ ] Check for ExecutorService instance accumulation

---

## Key Files to Review

1. `/src/main/java/me/christianrobert/orapgsync/core/job/service/JobService.java` - CRITICAL
2. `/src/main/java/me/christianrobert/orapgsync/transformer/type/SimpleTypeEvaluator.java` - HIGH
3. `/src/main/java/me/christianrobert/orapgsync/transformer/service/TransformationService.java` - HIGH
4. `/src/main/java/me/christianrobert/orapgsync/core/rest/StateRestService.java` - Add reset hooks
5. `/src/main/java/me/christianrobert/orapgsync/transformer/context/TransformationContext.java` - MEDIUM

---

## Related Documentation

See `MEMORY_LEAK_INVESTIGATION.md` for full analysis with:
- Detailed code snippets
- Memory leak scenarios
- Testing recommendations
- Fix options and implementations

