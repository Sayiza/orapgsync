# VisitGeneralElement Refactoring Plan

**Status**: ✅ COMPLETE — Milestone A (2026-05-30), Milestone B (2026-08-14)
**Last Updated**: 2026-08-14

---

## Overview

This plan refactors `VisitGeneralElement.java` (originally 2245 lines, 40+ methods) into focused transformer classes using a dispatcher pattern.

**Two-Milestone Approach:**
- **Milestone A**: Pure structural refactoring (Phases 1-5) - ✅ COMPLETE
- **Milestone B**: Parameterless function detection fix (Phase 6+) - ✅ COMPLETE

---

## Problem Statement

### Issue 1: Monolithic Class ✅ ADDRESSED
The file handled 8+ distinct concerns in one file. Now extracted to separate transformer classes.

### Issue 2: Parameterless Functions Misidentified ✅ ADDRESSED
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

## Milestone B: Parameterless Function Detection ✅ COMPLETE

**Implemented 2026-08-14.** Scope grew beyond the original sketch below: the real migration hit
this on ordinary user packages and standalone functions, not only on the Oracle compatibility
layer, so all four reference shapes are handled and the detection is metadata-driven rather than
list-driven.

### Problem

Oracle permits a routine that needs no arguments to be referenced without parentheses. In
expression position that is the same parse shape as `table.column`, and PostgreSQL reads `a.b` as
*column b of relation a* — so the emitted `pkg . func` fails with
`missing FROM-clause entry for table "pkg"`, i.e. the function is taken for a table.

The decision was made purely syntactically at `VisitGeneralElement.java:151`:

```java
boolean isFunctionCall = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();
```

No parentheses sent the reference to `handleQualifiedColumn()`, which re-emitted the dotted path.
Metadata was consulted only afterwards, so it never got a say.

`VisitCall_statement` never had this problem because `call_statement` is a grammar rule that only
matches calls — the parser has already decided. Views live in expression position, where nothing
but metadata can decide.

### The four shapes

| Oracle | Before | After |
|---|---|---|
| `pkg.func` | `pkg . func` | `hr.pkg__func()` |
| `hr.pkg.func` | `hr . pkg . func` | `hr.pkg__func()` |
| `hr.standalone_func` | `hr . standalone_func` | `hr.standalone_func()` |
| `standalone_func` | `standalone_func` | `hr.standalone_func()` |

### Solution

**Created**: `generalelement/ParameterlessFunctionDetector.java` — consulted from both
`VisitGeneralElement` branches that assumed "no parentheses ⇒ column"
(`handleDotNavigation`'s else branch, and the bare-identifier tail of `handleSimplePart`).

Resolution order mirrors Oracle's own: columns in scope → local/package variables and CTEs →
Oracle compatibility packages → routines in the current schema → synonyms.

**Three supporting changes made the decision possible:**

1. **Standalone routines are now indexed.** `MetadataIndexBuilder.indexPackageFunctions()`
   explicitly skipped them, so shapes 3 and 4 were undecidable. Added
   `indexStandaloneFunctions()` and `TransformationIndices.isStandaloneFunction()`.

2. **Arity is now known.** Existence alone is not enough: a routine with a mandatory parameter
   could never have been called bare, so a bare reference to it *is* a column.
   `indexNoArgCallableRoutines()` + `isNoArgCallable()` record which routines take no mandatory
   argument. This required extracting `ALL_ARGUMENTS.DEFAULTED` (`OracleFunctionExtractor`,
   `FunctionParameter.defaulted`) — a routine with every parameter defaulted is callable bare. A
   defaulted OUT parameter still disqualifies: the caller must supply a target.

3. **FROM scope is now tracked.** `tableAliases` could not answer "which relations are in scope?"
   because an unaliased table registers no alias. `TransformationContext.fromRelations` +
   `isColumnInScope()`, populated in `TableReferenceHelper.resolveTableviewName()`, give a real
   column precedence over a same-named routine.

`TransformationIndices` gained an 8-argument constructor; the 6-argument one delegates with empty
sets, so the ~29 test call sites that build minimal indices were untouched.

### Safety property

Every rewrite requires **positive** metadata evidence, and column-shaped readings are checked
first. An identifier the metadata does not recognise is emitted exactly as before, so a miss is
never worse than the pre-existing behaviour.

The inverse rule — *"the qualifier is not a known alias, therefore it is a package"* — is wrong
and must not be added later: an unaliased table registers no alias, so it would misfire on every
`FROM emp` / `emp.col` pair.

### Performance

The anticipated concern did not materialise. Lookups are `HashSet`/`HashMap` hits guarded by
cheaper checks, and the ordering puts the scope test first. No caching or configurability needed.

### Tests

- `transformer/ParameterlessFunctionTransformationTest` (13) — all four shapes, the negatives
  (aliased column, unaliased-table column, routine requiring arguments, unknown identifiers),
  quoted names, Oracle compat, and the still-working explicit-`()` path.
- `transformer/context/MetadataIndexBuilderRoutineIndexTest` (6) — the no-arg-callable rule:
  no parameters, mandatory parameter, all-defaulted, defaulted OUT, package vs standalone keying,
  schema filtering.

Verified by mutation: removing the `registerFromRelation` call makes
`columnOfUnaliasedTable_winsOverSameNamedFunction` fail with `SELECT hr.ename() FROM hr.emp` —
exactly the silent corruption the guard prevents.

Full suite: 1,624 tests, 0 failures.

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

### New Files (Milestone B)
- `src/main/java/.../transformer/builder/generalelement/ParameterlessFunctionDetector.java`
- `src/test/java/.../transformer/ParameterlessFunctionTransformationTest.java`
- `src/test/java/.../transformer/context/MetadataIndexBuilderRoutineIndexTest.java`

### Modified Files (Milestone B)
- `core/job/model/function/FunctionParameter.java` - `defaulted` field
- `function/service/OracleFunctionExtractor.java` - extract `ALL_ARGUMENTS.DEFAULTED`
- `transformer/context/TransformationIndices.java` - standalone + no-arg-callable indices
- `transformer/context/MetadataIndexBuilder.java` - build them
- `transformer/context/TransformationContext.java` - `fromRelations`, `isColumnInScope()`
- `transformer/builder/tablereference/TableReferenceHelper.java` - register FROM relations
- `transformer/builder/VisitGeneralElement.java` - consult the detector in both branches

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
(Milestone B was subsequently implemented on 2026-08-14.)

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```
