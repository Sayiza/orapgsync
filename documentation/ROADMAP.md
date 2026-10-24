# Roadmap — PL/SQL reuse from Java

**Started 2026-10-24.** Replaces `CONSOLIDATION_PLAN.md` and the pipeline-wide ordering in
`TYPE_METHOD_IMPLEMENTATION_PLAN.md` §13–§16 (both now in `completed/`). This is the **single
active plan**. Write-ups of finished items go into the relevant section here, or into
[TRANSFORMATION.md](TRANSFORMATION.md) for transformer constructs.

**Status: Phase 0 done 2026-10-24** — baseline in §10, §5 re-ranked from it. Next: Phase 1.

---

## 1. Goal

The real migration moves business logic to Java, spread over several separate Quarkus services
rather than one monolith. Tables, data and SQL-only views already migrate well enough. What is new:

> **Make as many PL/SQL routines as possible usable from Java on PostgreSQL — and make it obvious,
> per routine, how far each one can be trusted.**

A Java developer porting a feature should be able to ask "is `pkg.fn` usable on PostgreSQL?" and
get an answer backed by evidence, not by a CREATE that happened to succeed. They then decide
whether to call the migrated routine or rewrite it in Java. Not everything has to convert; every
routine that converts *and can be shown to work* is Java code nobody has to write.

**This moves the measure of success.** The earlier plans counted `implemented` (the `CREATE
FUNCTION` succeeded). For reuse that is the wrong number: PL/pgSQL resolves bodies at call time, so
"implemented" proves only that the signature was accepted. The organising concept is now the
**readiness ladder** (§3), and the transformer is ranked by how many routines it moves *up* the
ladder, weighted by what the Java team actually asks for.

**Non-goals**
- 100% PL/SQL coverage. The long tail is worked demand-first (§6.D), never coverage-first.
- The mod_plsql web gateway (step 32). Partly built and plausible, but "everything works over the
  web" is not attainable enough to be the target. Parked in
  [deferred/MOD_PLSQL_IMPLEMENTATION_PLAN.md](deferred/MOD_PLSQL_IMPLEMENTATION_PLAN.md).
- State persistence / resume. Runs are all-or-nothing in practice.

**Maintenance mode** — tables, data transfer, constraints, indexes, SQL-only views. Fix
regressions and measured gaps (§6.E); no new features there unless a measurement asks for them.

---

## 2. What changed: a local source database

There is now a read-only Oracle 19c instance with test data that is structurally close to the
production source: same schemas and code, anonymised, about a tenth of the data volume. Details,
credentials and the separation rules live in git-ignored `local/SOURCE_DB.md`. **Read its §1
before any session that touches it.** In short: nothing from it enters the repository, and a
failure found there is reproduced in the repo under invented names.

Three consequences shape this plan:

1. **Failures can be reproduced on demand.** Before, evidence arrived between sessions as a full
   run's log. Now a single package can be extracted and run through the transformer in minutes, so
   the loop becomes *pick failure → extract → minimise → test with invented names → fix* instead of
   *wait for the next run*.
2. **Oracle can be asked for the right answer.** With test data on both sides, a view, and
   potentially a function, can be evaluated on Oracle and on PostgreSQL and the results compared.
   That is the rung no earlier plan could reach (§3, L5).
3. **The account cannot execute other schemas' code.** It has `CREATE SESSION`, `READ ANY TABLE`
   and `SELECT ANY DICTIONARY`, so it can read every table, view and dictionary view. It cannot
   call a function owned by another schema. `EXECUTE ANY PROCEDURE` was ruled out, because it would
   also allow running code that writes. **Calling functions directly for equivalence therefore needs
   a decision (§7 D3).** Views are not affected. A view runs its functions with the view owner's
   rights, so a view that calls a function compares that function indirectly. *(Expected Oracle
   behaviour, not yet verified on this instance.)*

The tool already reads `DBA_*` when it can
([completed/CONSOLIDATION_PLAN.md](completed/CONSOLIDATION_PLAN.md#oracle-data-dictionary-dba_-with-all_-fallback-implemented-2026-10-23)),
so a run against this database sees all the PL/SQL, not the fraction `ALL_*` shows.

---

## 3. The readiness ladder

Each routine sits on exactly one rung, the highest it has evidence for. Each rung needs the one
below it.

| Rung | Name | Evidence | Who produces it |
|---|---|---|---|
| L0 | extracted | in the Oracle metadata | step 22 |
| L1 | stubbed | signature exists in PostgreSQL | step 23 |
| L2 | implemented | `CREATE OR REPLACE` with the transformed body succeeded | step 28 / 29 |
| L3 | resolves | every reference in the body resolves: tables, columns, called routines, variables | static check (§6.B4) |
| L4 | smoke-tested | called with representative arguments on test data, no error | smoke harness (§6.C2) |
| L5 | equivalent | same result as Oracle for the same arguments on the same data | equivalence harness (§6.C3) |
| L6 | approved | a person signed it off for Java use: who, when, on what evidence | the Java team, **outside the repo** |

Two refinements make the ladder honest:

- **A routine is only as ready as what it calls.** A routine at L5 that calls a stub is not
  usable. Each routine reports its *own* rung and its *effective* rung, the minimum over its
  transitive callees, using Oracle's dependency graph (`DBA_DEPENDENCIES`, §6.B3). The effective
  rung is what a Java developer needs. The callee list shows which routines are worth fixing next.
- **Some routines carry reuse risk that no rung shows.** These are properties the Java side must
  know about even when the routine is equivalent in a single-call test (§6.B5):
  - It reads or writes **package state**. That state is per session in Oracle and leaks between
    requests over a connection pool.
  - It issues **COMMIT/ROLLBACK**. Every migrated routine is a PostgreSQL `FUNCTION`, and a function
    cannot commit.
  - It is an **autonomous transaction**.
  - It runs **dynamic SQL**.
  - It **writes data**.
  - Its **calling convention** has no direct JDBC equivalent: OUT parameters become a record,
    REF CURSOR becomes `refcursor` (which needs a transaction in pgjdbc), and BOOLEAN parameters
    have no Oracle JDBC counterpart.

The ladder is a **report and a contract**, not a gate inside the pipeline. The pipeline still
creates everything it can. The ladder states how far each object can be trusted.

---

## 4. Phase 0 — baseline against the local source database

**Do this first. Nothing else in this plan is ranked until it exists.**

1. A full orchestration run against the local source database, in `DBA_*` mode, into an empty
   PostgreSQL target. Archive the reports from `logs/reports/` into `local/runs/<date>/`. The
   numbers identify the source, so they stay out of the repo.
2. **A ratio for every one of the 32 steps** (CLAUDE.md: an unmeasured step is not a passing one).
   Three are owed from earlier plans: step 17's acceptance criterion (≥90% of non-constraint
   indexes created, never measured); triggers (step 31, never taxonomised); type methods (step 29,
   last measured 2026-08-28).
3. **Taxonomise by cause, not by message.** Use the §14 method from the type-method plan: group by
   originating code path and by (package, error) pair before counting. A broken package context
   fails every member, so raw error counts rank packages, not causes.
4. Write the result in this file as §10 with dated numbers (**ratios and categories only**, no
   names), then re-rank §5.

**Earlier figures are broadly comparable.** The 2026-10-24 baseline (§10) has the same trigger
totals to the unit as 2026-08-28 and a function denominator within ~1%, so the August–September
runs were evidently taken against the same database. Still check denominators per step before
comparing.

**Phase 0 also tests the hypotheses carried over in §6.A.** Each is a measured defect from the
earlier runs, and each states the number that confirms or retires it on the new baseline.

---

## 5. Sequencing

Re-ranked from the 2026-10-24 baseline (§10). Phase 1 is ordered by leverage: each item names the
share it addresses, and all but P1.6 are small, mechanical fixes with a clear reproduction.

| Phase | Item | Addresses (2026-10-24, §10) |
|---|---|---|
| **1** | **P1.1 = A1** — `FunctionBoundaryScanner` counts open CASE expressions (port from `TypeMethodBoundaryScanner`, ideally one shared implementation) | ~60% of blocked functions + the `<EOF>` share of genuine errors — by far the largest lever |
| | **P1.2** — stubs emit `DEFAULT` for defaulted parameters (implementation must emit the same, or `CREATE OR REPLACE` fails with *cannot remove parameter defaults*) | 30% of view failures; also lets Java callers omit defaulted args |
| | **P1.3** — grammar: a length given by a constant expression, `varchar2(pkg.CONST CHAR)` | the package spec defining the most-used subtypes does not parse → ~23% of stub errors, ~14% of blocked |
| | **P1.4** — `extractTypeFromTypeSpec` keeps `%TYPE`/`%ROWTYPE` for package-private parameters | ~25% of stub errors |
| | **P1.5** — bare type names resolve against the enclosing package (spec index **and** body-declared types) | ~12% of stub errors + body-type share of package-variable errors |
| | **P1.6** — Oracle `FUNCTION` with OUT parameters: decide the PostgreSQL shape (D8) | ~12% of stub errors, ~9% of genuine function errors; a Java calling-convention question |
| | **P1.7** — small: install the compat layer (step 26) before stubs (step 23); quoted member names in flattened names | ~8% of stub errors (mostly web entry points); ~1% |
| | **B1** stubs raise · **B2** minimal readiness report (L0–L2 + reasons) | the silent-NULL hazard; first deliverable for the Java team |
| **1b** | view call sites: member method called **without receiver**; object constructor qualified with the **caller's** schema | 16% + 5% of view failures |
| **2** | **B3** dependency closure · **B4** static resolve check (L3) · **B5** risk flags · **C1** view equivalence | as before |
| **3** | **C2** smoke · **C3** function equivalence (D3) · **D** demand-driven loop | as before |
| ongoing | **A2** signature agreement test (*cannot change return type*: ~7% of genuine errors) · **A3** collections · **A4/A5** · **E** | |

**Re-measure after Phase 1 before ordering 1b onward.** P1.1 alone unblocks members whose bodies
have never been attempted, so the genuine-error taxonomy will change shape, and the step-28
runtime will rise (CLAUDE.md: a faster run is not a better run).

---

## 6. Workstreams

### A. Known pipeline defects, carried over

Measured on the August–September runs and still unfixed. Phase 0 confirms or retires each one.

**A1 — Function boundary-scanner truncation** (type-method plan Phase 2b, applied to package
bodies). One defect with two symptoms: `mismatched input '<EOF>'` parse failures in step 28, and
reduced package bodies that will not parse because statement tails were left behind (`else`,
`then`, bare assignments). On 2026-08-30 this was ~97% of step 28's parse failures and the cause of
the largest group of refused packages. Fix it the way Phase 2b fixed `TypeMethodBoundaryScanner`:
lexer-based, CASE-expression aware, with tests that assert the *extracted text*, plus one test that
feeds every scanned segment through the production parser. **Confirmed on the 2026-10-24
baseline (§10):** `FunctionBoundaryScanner` does not count open CASE *expressions*. Prefer one
shared depth-tracking implementation over a second copy; two scanners drifting apart is how this
defect survived. **Acceptance without a full run:** stream every package body from the local source
database through scanner → `PackageBodyReducer` → `parsePackageBody`, and compare the share of
reduced bodies that parse before and after the fix. The probes for this
(`local/tools/src/probe/ReduceProbe.java`, `FetchSource.java`) and a reproducing package are listed
in `local/runs/2026-10-24/ANALYSIS.md`. Repo tests use invented names only.

**A2 — Function stub↔implementation signature agreement.** The function equivalent of
`TypeMethodStubSignatureAgreementTest`: build both sides from the same Oracle object and compare,
plus an execution test that asserts **exactly one** function of that name exists afterwards. This
targets the `cannot change return type` / `function result type must be …` family. *Confirm:* count
those messages in step 28.

**A3 — Oracle collection types.** Step 7 silently emits `CREATE TYPE … AS ()` for schema-level
`TABLE OF` / `VARRAY` types (CLAUDE.md, "Collection Types"). First **A3a, the diagnostic step**:
extract `TYPECODE` and `ALL_COLL_TYPES`, and refuse `AS ()` with a named error. Only then **A3b,
representation**, scoped by A3a's numbers. For reuse, a schema-level collection in a signature also
decides what a Java caller passes (`jsonb`? an array?), so A3b is a calling-convention decision as
well as a storage one. `EXTEND`/`TRIM` are unimplemented for *both* collection models.

**A4 — Small defects found and deliberately not fixed:**
- `getExistingPostgresFunctions` keys on `pg_proc.proname`, so a re-run into a non-empty target
  silently skips every overload after the first. Local runs make re-runs more likely, so this rises
  in priority.
- `TypeMethodImplementationResult` keys its buckets by name, so overloaded type methods have no
  recoverable per-object record (the function job was fixed in §14.4; the sibling was not).
- `resolvePackageQualifiedType` reports unknown packages on `System.err`, which reaches no log.
- Seven type-method "source not found" errors on base types, probably abstract methods filed as
  errors instead of skips.

**A5 — Should a failed package context block its members?** Today it does: conservative, and every
blocked member is counted. A member that touches no package variable does not need the helpers.
Decide from per-package blocked counts plus a check of whether the generated body references a
missing helper. **Do not unblock speculatively.**

### B. Readiness instrumentation (new)

**B1 — Stubs must raise, not return NULL.** Function stubs currently `RETURN NULL`. Harmless while
stubs only bridge circular references during migration. Dangerous once Java calls into the
database, because a not-yet-implemented routine answers with a plausible NULL instead of failing.
Proposed: the stub body raises a named exception (`'<schema>.<pkg__fn> is a migration stub'`).
Views that reference such a stub then fail at query time, which is the honest outcome. Decision D1.

**B2 — One readiness record per routine.** For each routine: own rung, effective rung, the reason
it is not higher (the named error, the missing callee, the risk flag), and the Oracle→PostgreSQL
name mapping (`schema.pkg.fn` → `schema.pkg__fn`, overload included). Ship it as a job result
through the usual `JobReportArchive`/report path, plus a machine-readable export (CSV/JSON) that the
Java team can consume. Where it is published, and how Java finds the approved routines, is decision
D2.

**B3 — Dependency closure.** Extract `DBA_DEPENDENCIES` (add `dependencies` to
`OracleDataDictionary.VIEWS`, and check that its `DBA_*` twin has the same columns), restricted to
PL/SQL→PL/SQL and PL/SQL→table/view edges. Compute effective rungs and the inverse view: "fixing X
unblocks N routines (M of them requested)". This replaces raw failure counts as the ranking input
for §6.D.

**B4 — Static resolve check (L3).** Close the "CREATE succeeds, call fails" gap without calling
anything. The preferred instrument is the `plpgsql_check` extension. It resolves every reference in
a PL/pgSQL body against the catalog and reports per statement. Whether it can be installed on the
target is decision D4. Fallback: our own check for the most common failure, calls to functions that
do not exist or are still stubs, using the B3 graph.

**B5 — Reuse-risk flags.** Derived from the parse tree at transformation time (package variable
access, `COMMIT`/`ROLLBACK`, `PRAGMA AUTONOMOUS_TRANSACTION`, `EXECUTE IMMEDIATE`/`DBMS_SQL`, DML)
and from the signature (OUT params, REF CURSOR, BOOLEAN, collection types). Reported in B2. They
are not failures. Two of them are currently also *silent transformation defects* (§6.D, hardening):
the autonomous-transaction pragma is ignored, and COMMIT inside a function fails at run time.

**B6 — Per-object error detail** (carried from Consolidation item 1). From a failed routine, two
clicks should reach the failing construct, its source line and the Oracle source. The audience is
now the Java developer asking "why isn't my routine there?" as much as us.

### C. Verification against Oracle (new)

**C1 — View equivalence harness.** For each view: row count, plus an order-insensitive hash of a
canonical rendering of the rows, on Oracle and on PostgreSQL. Needs only `READ ANY TABLE`. Why
first:
- It turns "views are satisfactory" from an impression into a measurement.
- It is the only detector so far for the **unanchored-grammar silent truncation** (CLAUDE.md), where
  a view transforms "successfully" from a fragment.
- Views that call functions give indirect function evidence without new grants.

Required infrastructure, which C3 reuses: a canonical value rendering that settles the known
Oracle/PostgreSQL differences *explicitly*: `''` vs NULL, `DATE` as timestamp, NUMBER scale,
trailing blanks in CHAR, collation-dependent ordering. Each difference is either normalised with a
stated reason or reported. Never smoothed over silently. Data must be the same snapshot on both
sides: transfer immediately before comparing, and record the transfer job id in the result.

**C2 — Smoke harness (L4).** Call a routine on PostgreSQL with given arguments inside a transaction
that is always rolled back; record success, error, or result. Argument sets come from a
developer-supplied call file to start with, kept **outside the repo** when it contains real values.
Generating arguments automatically from test data is a later, separate decision. Never write
argument values from the source database into the repo.

**C3 — Function equivalence (L5).** The same call on both sides, compared through C1's canonical
rendering. Read-only functions only, since the Oracle account cannot write and must not. Blocked on
D3, how the Oracle side gets to call the function. Until then L5 is reachable only indirectly
through views (C1).

### D. Transformation expansion, demand-driven

**The ranking rule changes.** Not "which construct fails most often" but "which fix moves the most
*requested* routines up the ladder, transitively" (B3). The Java team's candidate list is the
demand signal. Until it exists, rank by "unblocks most routines".

**The working loop** (§2, consequence 1): take a blocking failure from the baseline or the readiness report →
extract the routine from the local source database into `local/` → minimise → reproduce in a repo
test under invented names → fix → a string test plus an execution test that *calls* the routine →
re-run that package locally to confirm.

**Hardening first, before new constructs** (Consolidation item 4, still open; re-verify each before
fixing, since all were last checked 2026-08-08):
- Unanchored `select_statement` / `function_body` entry rules: a parse that stops early reports no
  error. Decide whether truncated parses are rejected (D6). C1 is what will show the cost.
- `VisitSeq_of_statements` silently `continue`s on statements that transform to null.
- `VisitPragma_declaration` silently ignores `AUTONOMOUS_TRANSACTION`, which changes commit
  semantics.
- `VisitGeneralElement` returns null for unsupported cross-schema references.
- The remaining `return null` sites in `transformer/`.

**Known long-tail candidates**, collected from the archived plans. They are a *pool* to rank from,
not a queue. Check the status of each against the baseline before starting it:
- BULK COLLECT / FORALL; `RETURNING … INTO` (DML plan Phase 2, currently rejected loudly);
  multi-table INSERT (DML Phase 3)
- Collection `EXTEND` / `TRIM`; schema-level collection access (after A3)
- Mutating type members, `obj.set_x()` as statement-level rewrite (type-method plan Phase 6)
- `%ROWTYPE` in signatures (PostgreSQL rejects it there); cross-schema bare type references
- Dynamic SQL (`EXECUTE IMMEDIATE`, `DBMS_SQL`), autonomous transactions (see B5)
- Inquiry directives (`$IF`, `$$PLSQL_UNIT`), chained method calls, `CURRENT OF`,
  `TABLE()` collection expressions, `json_object_t`
- Possibly already done, verify: named parameters (`p => v`), OUT/INOUT arguments in call
  statements (both were listed as missing in the 2025 STEP_25 plan)

**AI-assisted transformation** stays deferred ([design](deferred/AI_TRANSFORMATION_IMPLEMENTATION_PLAN.md)).
The precondition it always lacked now has a name: an AI-drafted body is usable only with L4/L5
evidence, so revisit the idea once C2/C3 exist *and* the baseline shows a genuinely long tail.

### E. Maintenance — tables, data, constraints, indexes, SQL views

- Measure in Phase 0 and fix regressions; C1 provides the view-level evidence.
- Index acceptance (≥90%) gets its first measurement in Phase 0.
- Parked from Consolidation, revisit only on a concrete need: server-side result filtering
  ("only failures"), frontend polling audit, adaptive transfer batch sizes.

---

## 7. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 | Stub body: `RETURN NULL` or raise? | **Raise**, named. A silent NULL is the one wrong answer a Java caller cannot detect. |
| D2 | Where does the approval list (L6) live, and how does Java discover approved routines? | List lives with the Java team, outside this repo and outside the target DB (the target is rebuilt by the daily run). The tool *imports* it and writes the merged readiness into a small metadata table in the target, which the Java side's name-mapping can query at startup. |
| D3 | How does the Oracle side call a function for C3? | Ask the DBA for `EXECUTE` on a **named list** of read-only candidates, extended as candidates appear. Not `EXECUTE ANY PROCEDURE`. Until then, indirect evidence through views. |
| D4 | May `plpgsql_check` be installed on the target? | Yes if the target is ours to configure. It is the rigorous way to reach L3. |
| D5 | Block members of a failed package context? (A5) | Keep blocking until per-package data says otherwise. |
| D6 | Reject truncated parses (unanchored entry rules)? | Decide after C1 has measured how many "successful" views are fragments. |
| D7 | Routines that touch package state: transform but flag, or refuse for Java use? | Transform and flag (B5). The approval step (L6) is where a person decides whether pooled-session semantics are acceptable for that caller. |
| D8 | Oracle `FUNCTION` with OUT parameters — PostgreSQL has no "return value *and* OUT params" | `RETURNS record` with the Oracle return value as an extra leading OUT column (e.g. `result`), and the body's `RETURN x` rewritten to assign it. Matters for Java: the call shape changes from `{? = call f(?, ?)}` to a record. Decide before P1.6. |

---

## 8. Where the old plans went, and what was carried

| File | Now | Carried into this plan |
|---|---|---|
| `CONSOLIDATION_PLAN.md` | `completed/` | B6 error detail; D hardening list; E parked items; index acceptance → Phase 0. Write-ups (frontend rendering, payloads, parallel transfer, index migration, `DBA_*` dictionary) stay there as reference. |
| `TYPE_METHOD_IMPLEMENTATION_PLAN.md` | `completed/` | A1 (Phase 2b for functions), A2 (item 11), A3 (§13.3), A4 (§15.6 + §13.5), A5 (§14.5), Phase 6 → D pool. §11–§16 are the run history and the taxonomy method. |
| `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md` | `completed/` | "Not yet supported" list → D pool (with verify-first notes). Its coverage figures were never measured. |
| `PLSQL_DML_STATEMENTS_IMPLEMENTATION_PLAN.md` | `completed/` | RETURNING, multi-table INSERT → D pool. |
| `AI_TRANSFORMATION_IMPLEMENTATION_PLAN.md` | `deferred/` | Precondition named in D. |
| `MOD_PLSQL_IMPLEMENTATION_PLAN.md` | `deferred/` | Nothing. Non-goal (§1). |
| `JOB_CANCELLATION_IMPLEMENTATION_PLAN.md` | `deferred/` | Nothing. Revisit if aborting local runs becomes a real need. |
| `TRANSFORMATION.md`, `TESTING.md` | unchanged | Living references. TESTING.md's "Tier 3: Oracle comparative tests" was "future consideration"; C1–C3 are that tier, built against the local source database rather than an Oracle container. |

---

## 9. Rules that still apply

These come from the earlier work and are in CLAUDE.md. They are restated here only as the checks
this plan's items must pass:

- **Fail loudly.** A new readiness rung or risk flag never turns a failure into a neutral count.
  "Skipped" means nothing to do.
- **Never build on a failed parse.** Applies to the scanner fix (A1) and to anything that reads
  recovered ANTLR trees.
- **A ratio nobody measured against a run is not a status.** Each item here gets its number from a
  run, dated.
- **Names from the source database never enter the repo**, including test fixtures for the D loop
  and argument values for C2.

---

## 10. Baseline — 2026-10-24 full run (local source database, `DBA_*` mode)

Ratios and causes only; the named detail is in `local/runs/2026-10-24/ANALYSIS.md`. Run 2h09m.

### 10.1 Ratios per step

| Step | Result | vs. 2026-08-30 |
|---|---|---|
| 4, 9, 11 schemas · sequences · tables | 100%, 0 errors | = |
| 7 object types | 100%, 0 errors — **collections still emitted as `AS ()`** (A3) | = |
| 13 transfer | 99.6% of non-empty tables; every skip is an empty table (genuine); errors = row count unreadable | |
| 15 constraints | 99.5% (CHECK with `IS JSON` on a LOB→oid column, FK violations in the data) | |
| 17 indexes | **98.2%** — the ≥90% acceptance criterion, first measurement, met | new |
| 18 FK gap-fill | 0 errors; 94% of FKs already covered by a migrated index | |
| 20 view stubs | 99.9% | |
| 23 function stubs | **83.0%** | 62% |
| 25 type method stubs | 87.9% | |
| 27 views | **63.0%** | 63% — unchanged |
| 28 functions | **26.9%** implemented · 14.5% error · **58.6% blocked** by a failed package context | ~22% |
| 29 type methods | 73.9% — denominator +42%; most added failures are correct-by-design rejections (OVERRIDING, mutating) | 84% |
| 31 triggers | 70.4% | = to the unit |

### 10.2 What the failures are

**Almost none of it is the PL/SQL long tail.** As in §13–§15 of the type-method plan, the large
causes are extraction and signature defects upstream of the transformer:

- **Step 28 is still dominated by the boundary scanner.** ~60% of blocked members sit in packages
  whose reduced body will not parse. Reproduced on a single package: `FunctionBoundaryScanner` ends a
  function at the `END` of a **CASE expression**, and the remainder of the function lands in the
  reduced body as statement debris. Package-variable "types" that are really variable names (the
  next-largest package error) are the same debris, and so are the `<EOF>` errors (20% of genuine
  errors). One defect, three symptoms → P1.1.
- **Stub failures fall into five fixable groups** (P1.3–P1.7): a grammar gap that stops the most
  widely used subtype package from parsing; `%TYPE` lost on package-private parameters; bare names
  not looked up in the enclosing package; Oracle functions with OUT parameters; the compat layer
  installed after the stubs that need its types.
- **View failures are mostly missing or mismatched stubs, not view transformation.** 30% are calls
  that omit defaulted arguments, failing because stubs carry no `DEFAULT`. 21% are two call-site
  defects (member method without receiver; constructor in the wrong schema). Unmapped built-ins
  are ~5%.
- **The real long tail, as a share of genuine step-28 errors:** TABLE() ~3%, BULK COLLECT ~2%,
  multi-part package calls ~3%, inquiry directives ~1.6%, chained calls ~1%, CURRENT OF, MULTISET,
  JSON functions <1% each. Still "not yet" (§6.D), and the ranking will change once P1.1 lets the
  blocked members be attempted.
