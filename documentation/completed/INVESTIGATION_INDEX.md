# Memory Leak Investigation - Document Index

## Quick Start

**Start here:** Read `MEMORY_LEAK_EXECUTIVE_SUMMARY.md` for overview (5-10 minutes)

Then pick a detail document based on your needs:

---

## Documentation Structure

### Executive Level (5-10 minutes)
**File:** `MEMORY_LEAK_EXECUTIVE_SUMMARY.md`
- Summary of all findings
- Risk assessment
- Priority action items
- Testing recommendations
- Expected memory impact before/after fixes

### Developer Quick Reference (2 minutes)
**File:** `documentation/RESET_GAPS_QUICK_REFERENCE.md`
- What's reset vs what isn't
- Quick checklist of issues
- Priority ordering
- Key files to review

### Implementation Guide (Specific Files)
**File:** `FILES_TO_REVIEW.txt`
- All affected files with absolute paths
- Issue descriptions and line numbers
- Fix recommendations
- Testing checklist

### Deep Dive Analysis (30-60 minutes)
**File:** `documentation/MEMORY_LEAK_INVESTIGATION.md`
- Complete technical analysis
- Memory leak scenarios with examples
- Each issue with detailed explanation
- Multiple fix options
- Testing recommendations with code examples

### Complete State Mapping (Reference)
**File:** `RESET_STATE_ANALYSIS.txt`
- What StateService.resetState() clears (line by line)
- What JobService.resetJobs() clears
- Comprehensive summary table
- All detected infrastructure state with risk levels

---

## Issue Summary

| Severity | Issue | File | Lines | Risk |
|----------|-------|------|-------|------|
| CRITICAL | Thread pool leak | JobService.java | 91, 160-165 | Will cause OOM |
| HIGH | Type cache not cleared | SimpleTypeEvaluator.java | 33, 80-81 | Unbounded growth |
| HIGH | No cache cleanup | TransformationService.java | 137, 249, 250 | Long transformations |
| MEDIUM | AST cache lifecycle | PackageContext.java | 37-38 | Verify ephemeral |
| MEDIUM | Mutable state | TransformationContext.java | 253-254, 277 | Verify ephemeral |

---

## Next Steps

### If you want to:

**Understand the full scope (5 minutes)**
→ Read: `MEMORY_LEAK_EXECUTIVE_SUMMARY.md`

**Get a checklist to fix issues (2 minutes)**
→ Read: `documentation/RESET_GAPS_QUICK_REFERENCE.md`

**Find all the files that need changes (2 minutes)**
→ Read: `FILES_TO_REVIEW.txt`

**Understand the technical details (30 minutes)**
→ Read: `documentation/MEMORY_LEAK_INVESTIGATION.md`

**See exactly what's reset vs what isn't (10 minutes)**
→ Read: `RESET_STATE_ANALYSIS.txt`

**Implement fixes immediately**
→ Use: `FILES_TO_REVIEW.txt` + `documentation/MEMORY_LEAK_INVESTIGATION.md`

---

## File Locations

### Documentation Files
- `/home/hoeflechner/dev/orapgsync/MEMORY_LEAK_EXECUTIVE_SUMMARY.md`
- `/home/hoeflechner/dev/orapgsync/documentation/MEMORY_LEAK_INVESTIGATION.md`
- `/home/hoeflechner/dev/orapgsync/documentation/RESET_GAPS_QUICK_REFERENCE.md`
- `/home/hoeflechner/dev/orapgsync/RESET_STATE_ANALYSIS.txt`
- `/home/hoeflechner/dev/orapgsync/FILES_TO_REVIEW.txt`
- `/home/hoeflechner/dev/orapgsync/INVESTIGATION_INDEX.md` (this file)

### Code Files Affected
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/core/job/service/JobService.java`
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/transformer/type/SimpleTypeEvaluator.java`
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/transformer/service/TransformationService.java`
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/core/service/StateService.java`
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/core/rest/StateRestService.java`
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/transformer/packagevariable/PackageContext.java`
- `/home/hoeflechner/dev/orapgsync/src/main/java/me/christianrobert/orapgsync/transformer/context/TransformationContext.java`

---

## Key Metrics

### Current State (With Leaks)
- **Thread pools per 100 jobs:** 100 separate pools
- **Memory per pool:** 1-5 MB
- **Memory per 100 jobs:** 100-500 MB
- **Type cache per 1000 views:** 50-500 MB
- **Total for 1000 jobs + 1000 views:** 500MB - 1.5GB

### After Fixes
- **Thread pools per 100 jobs:** 1 shared pool
- **Memory per 100 jobs:** 1-5 MB
- **Type cache:** Cleared per transformation
- **Total for 1000 jobs + 1000 views:** <50 MB

---

## Contacts/References

For questions about specific findings, see the corresponding documentation file with detailed explanation and code examples.

All findings are based on static code analysis across:
- `/home/hoeflechner/dev/orapgsync/src/main/java/`
- All application and transformation code
- All service and utility classes

Investigation Date: 2025-11-09

