# VisitGeneralElement Refactoring Plan

**Status**: Milestone A COMPLETE, Milestone B DEFERRED
**Last Updated**: 2026-05-30

---

## Overview

This plan refactors `VisitGeneralElement.java` (originally 2245 lines, 40+ methods) into focused transformer classes using a dispatcher pattern.

**Two-Milestone Approach:**
- **Milestone A**: Pure structural refactoring (Phases 1-5) - ✅ COMPLETE
- **Milestone B**: Parameterless function detection fix (Phase 6+) - 📋 DEFERRED

---

## Problem Statement

### Issue 1: Monolithic Class ✅ ADDRESSED
The file handled 8+ distinct concerns in one file. Now extracted to separate transformer classes.

### Issue 2: Parameterless Functions Misidentified 📋 DEFERRED
Line ~252 (now in FunctionCall fallback code) checks for parentheses to detect function calls:
```java
boolean isFunctionCall = lastPart.function_argument() != null
    && !lastPart.function_argument().isEmpty();
```
This causes `dbms_utility.format_error_stack` (no parentheses) to be treated as a column reference, producing `dbms_utility . format_error_stack` instead of `oracle_compat.dbms_utility__format_error_stack()`.

---

## Milestone A: Structural Refactoring ✅ COMPLETE

### Completed Work

**New Directory**: `transformer/builder/generalelement/`

| File | Lines | Purpose | Status |
|------|-------|---------|--------|
| `GeneralElementResult.java` | 54 | Result object (handled/not handled) | ✅ |
| `GeneralElementTransformer.java` | 48 | Interface for all transformers | ✅ |
| `DotNavigationDispatcher.java` | 83 | Priority-ordered dispatcher | ✅ |
| `SequenceCallTransformer.java` | 156 | NEXTVAL/CURRVAL handling | ✅ |
| `CollectionMethodTransformer.java` | 208 | COUNT, EXISTS, FIRST, LAST, DELETE | ✅ |
| `ObjectFieldAccessAdapter.java` | 67 | Adapter for existing transformer | ✅ |
| `PackageVariableTransformer.java` | 98 | pkg.g_var → getter call | ✅ |
| `InlineTypeFieldTransformer.java` | 159 | local_rec.field → jsonb | ✅ |

**VisitGeneralElement.java Changes:**
- Reduced by ~100 lines (2245 → 2143)
- Dispatcher call moved to beginning of `handleDotNavigation()`
- Redundant inline checks removed

**Test Results**: All 1408 tests pass

### Dispatcher Priority Order (Current)
```java
private static DotNavigationDispatcher createDispatcher() {
    DotNavigationDispatcher dispatcher = new DotNavigationDispatcher();
    // 1. PackageVariable - pkg.g_var (before function calls - same syntax)
    dispatcher.addTransformer(new PackageVariableTransformer());
    // 2. InlineTypeField - local_rec.field (before table column)
    dispatcher.addTransformer(new InlineTypeFieldTransformer());
    // 3. CollectionMethod - v.COUNT (before generic method)
    dispatcher.addTransformer(new CollectionMethodTransformer());
    // 4. SequenceCall - seq.NEXTVAL (special - no parentheses)
    dispatcher.addTransformer(new SequenceCallTransformer());
    // 5. ObjectFieldAccess - table.col.field (before function)
    dispatcher.addTransformer(new ObjectFieldAccessAdapter());
    // Remaining handled by fallback code in handleDotNavigation
    return dispatcher;
}
```

### Not Yet Extracted (Remain as Fallback in VisitGeneralElement)
- `PackageVariableFieldTransformer` - pkg.g_rec.field → jsonb (complex, many helper methods)
- `PackageCollectionAccessTransformer` - pkg.g_array(1) (looks like function call)
- `FunctionCallTransformer` - type methods & package functions (complex)

---

## Milestone B: Parameterless Function Detection 📋 DEFERRED

### Problem
Oracle compatibility functions called without parentheses are misidentified:
- `dbms_utility.format_error_stack` → `dbms_utility . format_error_stack` (WRONG - treated as column)
- Should be → `oracle_compat.dbms_utility__format_error_stack()` (CORRECT)

### Proposed Solution

**Create**: `ParameterlessFunctionDetector.java`

Detection checks (in order):
1. Is first part an Oracle compatibility package? (HTP, DBMS_UTILITY, etc.) → fast, hardcoded list
2. Is the identifier chain in function metadata? → requires `isPackageFunction()` lookup

**Modify**: `FunctionCallTransformer.java` (when extracted)
```java
boolean hasFunctionArguments = lastPart.function_argument() != null
    && !lastPart.function_argument().isEmpty();
boolean isParameterlessFunction = !hasFunctionArguments
    && ParameterlessFunctionDetector.isParameterlessFunction(parts, b);

if (hasFunctionArguments || isParameterlessFunction) {
    // Transform as function call
    // Add "()" for parameterless functions
}
```

### Performance Concern
Each ambiguous 2-part identifier (like `pkg.name`) requires metadata lookup. May need:
- Caching of lookup results
- Configurable behavior (opt-in for performance-sensitive contexts)

### New Tests Needed
- `ParameterlessFunctionDetectorTest.java`
  - `dbms_utility.format_error_stack` → detected as function
  - `custom_pkg.init` (if in metadata) → detected as function
  - `table.column` → NOT detected as function

---

## File Locations

### Modified Files
- `src/main/java/.../transformer/builder/VisitGeneralElement.java` - Main file, reduced

### New Files (Milestone A)
- `src/main/java/.../transformer/builder/generalelement/GeneralElementResult.java`
- `src/main/java/.../transformer/builder/generalelement/GeneralElementTransformer.java`
- `src/main/java/.../transformer/builder/generalelement/DotNavigationDispatcher.java`
- `src/main/java/.../transformer/builder/generalelement/SequenceCallTransformer.java`
- `src/main/java/.../transformer/builder/generalelement/CollectionMethodTransformer.java`
- `src/main/java/.../transformer/builder/generalelement/ObjectFieldAccessAdapter.java`
- `src/main/java/.../transformer/builder/generalelement/PackageVariableTransformer.java`
- `src/main/java/.../transformer/builder/generalelement/InlineTypeFieldTransformer.java`

### Files to Create (Milestone B)
- `src/main/java/.../transformer/builder/generalelement/ParameterlessFunctionDetector.java`
- `src/test/java/.../transformer/ParameterlessFunctionDetectorTest.java`

---

## How to Resume Milestone B

1. **Read this plan** to understand the context
2. **Create `ParameterlessFunctionDetector.java`** with detection logic
3. **Extract `FunctionCallTransformer.java`** from VisitGeneralElement (lines ~230-300)
4. **Integrate detection** in FunctionCallTransformer
5. **Add tests** for parameterless functions
6. **Benchmark** for performance impact
7. **Consider configurability** if performance is a concern

### Key Code Locations in VisitGeneralElement.java
- Line ~140: `handleDotNavigation()` method
- Line ~145: Dispatcher call (first thing)
- Line ~165: PackageVariableField fallback (not yet extracted)
- Line ~175: Function call detection (`isFunctionCall` boolean)
- Line ~220-280: Type member method handling
- Line ~285: Package function call handling

---

## Test Commands

```bash
# Quick validation
mvn test -Dtest=*TransformationTest

# Sequence-specific
mvn test -Dtest=SequenceTransformationTest

# Collection-specific
mvn test -Dtest=*Collection*Test

# Full suite
mvn test
```

---

## Git Commit for Milestone A

When ready to commit:
```bash
git add src/main/java/me/christianrobert/orapgsync/transformer/builder/generalelement/
git add src/main/java/me/christianrobert/orapgsync/transformer/builder/VisitGeneralElement.java
git add documentation/VISIT_GENERAL_ELEMENT_REFACTORING_PLAN.md

git commit -m "Refactor VisitGeneralElement into focused transformer classes (Milestone A)

- Create generalelement/ subdirectory with 8 transformer classes
- Implement DotNavigationDispatcher with priority-ordered dispatch
- Extract: SequenceCall, CollectionMethod, PackageVariable, InlineTypeField, ObjectFieldAccess
- Move dispatcher call to beginning of handleDotNavigation()
- Remove redundant inline checks
- No behavior change - all 1408 tests pass

Milestone B (parameterless function detection) deferred for future work.

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```
