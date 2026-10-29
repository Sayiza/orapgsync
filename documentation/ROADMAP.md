# Roadmap — PL/SQL reuse from Java

**Started 2026-10-24.** Replaces `CONSOLIDATION_PLAN.md` and the pipeline-wide ordering in
`TYPE_METHOD_IMPLEMENTATION_PLAN.md` §13–§16 (both now in `completed/`). This is the **single
active plan**. Write-ups of finished items go into the relevant section here, or into
[TRANSFORMATION.md](TRANSFORMATION.md) for transformer constructs.

**Status: Phase 0 done 2026-10-24** — baseline in §10, §5 re-ranked from it. Phase 1: P1.1 done
2026-10-24 (§6.A1), confirmed by the 2026-10-25 run (§10.3), which also found P1.8. P1.2 and P1.3 done
2026-10-25 (§6.P1.2, §6.P1.3), both confirmed by the 2026-10-26 run (§10.4). P1.4 and P1.5 done
2026-10-26 (§6.P1.4, §6.P1.5), both confirmed by the 2026-10-26 evening run (§10.5), which also
named four new causes; §5 re-ranked from it 2026-10-27. P1.8 and P1.9 done 2026-10-27 (§6.P1.8,
§6.P1.9), measured on the corpus only. P1.13 and P1.7 done 2026-10-28 (§6.P1.13, §6.P1.7), not yet
measured. P1.14 found and done 2026-10-29 (§6.P1.14): Oracle `(+)` outer joins were silently lost in
views reported implemented. Measured on the corpus.

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

Ranked from the 2026-10-24 baseline (§10), re-ranked 2026-10-27 from the 2026-10-26 evening run
(§10.5). Phase 1's done items stay listed for their history. The open items are in working order.
Correctness comes first: a false "implemented" (P1.8) costs more than any failure count. Then the
cheap items. Then the two calling-convention decisions, D8 and D9, made together, because both fix
what a Java caller passes and receives.

| Phase | Item | Addresses |
|---|---|---|
| **1 done** | ✅ **P1.1 = A1** — one shared block walker for both boundary scanners; CASE expressions and four more shapes (§6.A1) | 2026-10-24: ~60% of blocked functions + the `<EOF>` share of genuine errors |
| | ✅ **P1.2** — stubs emit `DEFAULT` for defaulted parameters (§6.P1.2) | 2026-10-24: 30% of view failures; also lets Java callers omit defaulted args |
| | ✅ **P1.3** — grammar: a length given by a constant expression, `varchar2(pkg.CONST CHAR)` (§6.P1.3) | 2026-10-24: ~23% of stub errors, ~14% of blocked |
| | ✅ **P1.4** — `%TYPE`/`%ROWTYPE` kept for package-private parameters; `%TYPE` anchors on **views** resolve (§6.P1.4) | confirmed §10.5: `%TYPE` stub errors −98% |
| | ✅ **P1.5** — bare type names resolve against the enclosing package (§6.P1.5) | confirmed §10.5: blocked members −65% |
| | ✅ **P1.8** *(found 2026-10-25)* — package member sources were stored **keyed by name**, so every overload is transformed from the *last* overload's source. Same-kind overloads all report "implemented" from one body; mixed function/procedure overloads fail to parse. Key by segment (name + position or signature), match to `ALL_ARGUMENTS` overloads, and add an "exactly one function per signature" execution test (§6.P1.8) | ~1,400 members reported implemented share ~620 names (2026-10-25); 2.4% of step-28 errors (§10.5). **Silent**, and it makes "implemented" untrustworthy for exactly the routines the readiness report will list |
| | ✅ **P1.9** *(found 2026-10-25, widened 2026-10-27)* — `TypeResolver`/`TypeConverter` did not map built-ins written with a length: `VARCHAR(n)` (synonym of `VARCHAR2`), `STRING(n)`, `INTEGER(n)` (§6.P1.9) | 9.5% of blocked members (§10.5) |
| | ✅ **P1.13** *(found 2026-10-27)* — `#` in identifiers: Oracle allows it unquoted, PostgreSQL does not. The normalizer already quoted it; local declarations and parameters bypassed it (§6.P1.13) | 1.7% of step-28 errors |
| | ✅ **P1.7** *(reshaped 2026-10-27, done 2026-10-28)* — types declared by Oracle's own packages (`OWA.VC_ARR` and four others) resolved to `oracle_compat.pkg__type`, which nothing installs. They now resolve from Oracle's documented declarations, to `jsonb` like any package collection; an unlisted one is refused by name. Routine names that are not plain identifiers are quoted as a whole (§6.P1.7) | 17% of stub errors, 18% of blocked members (mostly web entry points); routine names 5% of stub errors |
| | ✅ **P1.14** *(found 2026-10-29)* — Oracle `(+)` outer joins: only a bare top-level `a.k = b.k(+)` was translated. Parenthesized and other shapes became inner or cross joins, a RIGHT JOIN preserved the wrong side, and an unrecognized `(+)` was stripped. Rewritten to Oracle's rule; any `(+)` not translated now throws (§6.P1.14) | 11% of views use `(+)`. On the 2026-10-26 run, 12% of the implemented ones had lost every outer join, **silently**, and 37% of the unimplemented ones failed on a join-tree scope error |
| **1 open** | **D8 + D9**, decided together — the PostgreSQL shape of an Oracle `FUNCTION` with OUT parameters, and of `%ROWTYPE` outside a local declaration (§7) | prerequisite for P1.6 and P1.10 |
| | **P1.10** *(found 2026-10-27)* — `%ROWTYPE` in signatures, package variables and `SUBTYPE x IS tab%ROWTYPE`. Locals already map to `jsonb` (`VisitVariable_declaration`); the other three routes have no representation (D9) | **largest remaining cause** (§10.5): 32% of blocked members, ~9% of step-28 errors, 6% of stub errors |
| | **P1.6** — Oracle `FUNCTION` with OUT parameters (D8) | 28% of stub errors, 9% of step-28 errors (§10.5) |
| | **P1.11** *(found 2026-10-27)* — local subprograms: a function or procedure declared in a routine's `DECLARE` is emitted as a nested `CREATE OR REPLACE FUNCTION`. Either lift it to a top-level function (a name scheme, plus access to the enclosing routine's variables, which PostgreSQL has no equivalent for) or refuse it by name | 9% of step-28 errors, the largest single construct |
| | **P1.12** *(found 2026-10-27)* — element writes to a **package-level** collection (`g(i) := v`, `g(i).f := v`) bypass the package-variable setter and are emitted as a local `jsonb_set`; collection methods on such a variable (`g.EXISTS(i)`) are read as package function calls | 3.6% of step-28 errors, tripled by P1.5 |
| | **B1** stubs raise · **B2** minimal readiness report (L0–L2 + reasons) | the silent-NULL hazard; first deliverable for the Java team |
| **1b** | view call sites: member method called **without receiver**; object constructor qualified with the **caller's** schema | 16% + 5% of view failures (2026-10-24; views unchanged since §10.4) |
| **2** | **B3** dependency closure · **B4** static resolve check (L3) · **B5** risk flags · **C1** view equivalence | as before |
| **3** | **C2** smoke · **C3** function equivalence (D3) · **D** demand-driven loop | as before |
| ongoing | **A2** signature agreement test (*cannot change return type*, *cannot change name of input parameter*: ~5% of step-28 errors, ~9% of stub errors) · **A3** collections · **A4/A5** · **E** | |

**Not ranked into Phase 1 on purpose:** multi-part package and chained method calls are the largest
step-28 error class (17%, §10.5). They are mostly object-type builder chains (`x.set(a).set(b)`), so
they belong with the type-method work and are ranked with the D pool once B3 shows who calls them.

**Re-measure after Phase 1 before ordering 1b onward.** P1.1 alone unblocks members whose bodies
have never been attempted, so the genuine-error taxonomy will change shape, and the step-28
runtime will rise (CLAUDE.md: a faster run is not a better run).

---

## 6. Workstreams

### A. Known pipeline defects, carried over

Measured on the August–September runs and still unfixed. Phase 0 confirms or retires each one.

**A1 — Function boundary-scanner truncation ✅ implemented 2026-10-24, confirmed by the 2026-10-25 run (§10.3).**
One defect with two symptoms: `mismatched input '<EOF>'` parse failures in step 28, and reduced
package bodies that will not parse because statement tails were left behind (`else`, `then`, bare
assignments), so the whole package is refused.

*What was done.* Both boundary scanners now find a subprogram's closing `END` through one shared
walker, `transformer/parser/PlSqlBlockScanner`; the type-method copy and the function copy are
gone. The CASE-expression fix of Phase 2b was the first item, and measuring against every package
body in the source database found four more shapes, each of which also ended a member early or ran
it into the next one:

| Shape | What went wrong | Rule in the walker |
|---|---|---|
| `x := CASE … END;` | bare `END` read as the block's | stack of what is open (BEGIN / CASE / LOOP) |
| `FOR i IN a .. CASE … END LOOP` | CASE's `END` + the loop's `LOOP` read as `END LOOP`; CASE never closed | `END LOOP` with a CASE innermost closes the CASE; `LOOP` then opens the loop |
| `SELECT …, NULL END FROM …` | `END` as a column alias (Oracle accepts it) | a block's `END` is `END [label];` — nothing else closes a BEGIN |
| `WHERE d BETWEEN b.begin AND b.end` | qualified column `begin` opened a block | a token after `.` is a name |
| local subprogram in a nested `DECLARE` block; `FUNCTION` token in a statement (`t.function AS f`) | a "nested subprograms seen" counter charged the wrong `END` | declare sections are walked recursively; subprograms are only recognised at declaration start |

Also: a call spec member (`AS LANGUAGE JAVA NAME '…';`) is recorded ending at its semicolon instead
of the scan hunting for an `END` in the next member, and `VisitFunctionBody` / `VisitProcedureBody`
reject a call spec by name — it parses clean, and without the check would have become an empty
PostgreSQL function. Tests: `FunctionBoundaryScannerTest` "extent" section (asserts extracted text;
each shape fails against the old scanner; one test feeds segments and reduced body through the
production parsers), `TypeMethodBoundaryScannerTest`, `CallSpecRoutineRejectionTest`.

*Acceptance, measured without a full run* (2026-10-24, every `PACKAGE BODY` of the local source
database, scanner → `PackageBodyReducer` → `parsePackageBody`; named detail in `local/runs/p1.1/`):

| | before | after |
|---|---|---|
| reduced bodies that parse (of 4,038 unwrapped) | 2,339 · 779 fail · 920 did not finish in 15 min | **4,029 (99.8%)** · 9 fail |
| segments closing with *another* routine's `END name;` | 180 | 0 |
| sampled segments (1 in 25) failing with `<EOF>` | 360 of 3,040 (11.8%) | 0 of 3,019 (one `<EOF>` is grammar-error recovery, not truncation) |

The 9 remaining packages all wrap members or declarations in **conditional compilation**
(`$IF … $THEN … $END`): removing a member leaves an empty `$IF` branch, which the grammar rejects.
That belongs with inquiry directives (§6.D), not here. **Expect step 28 to change shape on the next
run** (§5): members of ~1,000 previously refused packages will be attempted for the first time.

**P1.2 — Parameter defaults ✅ implemented 2026-10-25, not yet measured on a run.**
Neither the function stub (step 23) nor the implementation (step 28) emitted `DEFAULT`, so every
Oracle call that leaves out a defaulted argument found no PostgreSQL function. 30% of view failures
on the 2026-10-24 run were this.

*What was done.* Both sides now emit defaults under one rule, `transformer/util/ParameterDefaults`:

- **Stub:** `DEFAULT NULL`. `ALL_ARGUMENTS` says only *that* a parameter is defaulted.
  `DEFAULT_VALUE` is reserved and empty for every defaulted argument in the local source database,
  so the value is not available at step 23.
- **Implementation:** `DEFAULT <transformed Oracle expression>`, both `DEFAULT` and `:=` spellings.
  This works over the stub because `CREATE OR REPLACE` may change a default's *value*. It refuses only
  to *drop* one (*cannot remove parameter defaults from existing function*), which is why the two sides
  have to pick the same parameters.
- **Only the trailing run is emitted.** Oracle allows a default before a required parameter, and
  callers then use named notation. PostgreSQL requires every *input* parameter after a defaulted one
  to be defaulted too. OUT parameters do not break the run, IN OUT ones do. An earlier default is
  dropped on both sides, so a call that omits it fails by name. This affects about 14% of the routines
  in the source database that have any defaulted parameter.
- **Package-private members** now record `defaulted` from the parse tree, which was never set before.
  This also feeds the "callable without parentheses" index (TRANSFORMATION.md,
  [routines without parentheses](TRANSFORMATION.md#routines-referenced-without-parentheses--2026-08-14)),
  which so far could not see an all-defaulted private routine.
- **Type-method implementations** already emitted every default. A default before a required
  parameter therefore made the whole `CREATE` fail. They now apply the same rule.
  **Type-method stubs still carry no `DEFAULT`**, because `ALL_METHOD_PARAMS` has no `DEFAULTED`
  column. This is harmless for replacement, since adding a default is allowed. A view that calls a
  type method and omits a defaulted argument still fails at step 27, though, because type methods
  are implemented at step 29. Open; measure before fixing.

Tests: `FunctionParameterDefaultTest` checks both sides and their agreement. It has an execution
test that creates the stub, replaces it, asserts *exactly one* function, and calls it with omitted
and named arguments. It fails with *cannot remove parameter defaults* if the implementation stops
emitting defaults. `TypeMethodStubSignatureAgreementTest.staticFunctionDefaults` covers type methods.

*Confirm on the next run:* the view-step "function … does not exist" count for calls with fewer
arguments than the signature (30% of view errors on 2026-10-24) should be near zero. Step 28
gains a new failure mode, a default expression that does not transform. Count it.

**P1.3 — Package-qualified constant as a type length ✅ implemented 2026-10-25, measured without a
full run.** `precision_part` accepted a length only as a literal, a bare name or `*`. So
`SUBTYPE label IS VARCHAR2(size_limits.TEXT_CAP CHAR)` failed to parse, and with it the
whole package spec. The spec that does this defines the most widely used subtypes, and two public
synonyms point at it. Every `pkg.subtype` reference into it was therefore unplaceable: in stub
signatures (step 23), and in package variables, which block their whole package at step 28. That
includes subtypes declared *before* the offending line, because the index stores nothing for a spec
that does not parse.

*What was done.* The grammar now accepts a dotted name, `id_expression ('.' id_expression)*`, as
the first length. Its one consumer, `CastTransformer`, still rejects a symbolic precision by name.
`TypeConverter` ignores lengths when it maps `VARCHAR2`, so the subtype resolves to `text` without
evaluating the constant. Test: `PackageSpecTypeIndexTest.subtypeWithConstantLengthIsIndexed`, which
goes from spec, through the step-22 index, to `TypeResolver`.

*Measured on the local source database without a full run* (named detail in `local/runs/p1.3/`):

- **Corpus parse:** every package spec, parsed with the old and the new grammar. Exactly one spec
  changes from failing to parsing, the subtype package. No spec regresses. About 2% of specs are
  wrapped. The other three that do not parse each fail on something else, and each blocks one
  package only:
  - `INDEX BY PLS_INTEGER NOT NULL`
  - a member named `prior`, which is a keyword
  - conditional compilation around a declaration (§6.D)
- **Resolution:** the step-28 references that blocked on this package were replayed through the
  real extractor and `TypeResolver`, with its two public synonyms. 99.6% now resolve, about 91% to
  `text` (the string subtypes) and 9% to `jsonb` (index-by tables). The remainder is a bare name the
  filter caught by accident; it is not a reference into this package.

*Confirm on the next run:* the blocked share of step 28 citing the subtype package (36% of blocked members
on 2026-10-25) should disappear. Those members are attempted for the first time, so expect genuine
errors to rise again (§5). Step-23 stub errors citing these subtypes (~23% on 2026-10-24) should go
too.

**P1.4 — `%TYPE` anchors in package-private signatures, and on views ✅ implemented 2026-10-26,
confirmed by the 2026-10-26 evening run (§10.5).**
Two defects, one after the other.

1. *The anchor was dropped.* Package-private members take their signature from the parse tree, not
   from `ALL_ARGUMENTS`. `extractTypeFromTypeSpec` read only `type_name`, but `%TYPE` and `%ROWTYPE`
   are sibling tokens of it. So `term_def.nr%TYPE` reached the stub job as `TERM_DEF.NR` and was
   looked up as a package type that does not exist. The anchor is now kept. `TypeResolver` already
   qualified a `%TYPE` anchor and refuses a `%ROWTYPE` by name.
   *Measured* on every package body of the local source database, through the real extractor and
   `TypeResolver`: private-member signatures that fail to resolve fell by 42% (named detail in
   `local/runs/p1.4/`). This half was in the 2026-10-26 run.
2. *Views were invisible.* That run showed what was left. `TypeResolver` looked the anchor's relation
   up among **tables only**, but Oracle anchors on views as freely as on tables. Anchors on views were
   10% of the stub errors and 12% of the step-28 errors. In step 28 they surfaced as
   `relation "x" does not exist`, four fifths of them in the signature and the rest in `DECLARE`
   sections. Of those anchors, 58% name a view in the routine's own schema and 42% reach it through
   a synonym. View columns are now indexed (`MetadataIndexBuilder.indexViewColumns`), and the anchor
   lookup asks `getRelationColumnType`, which checks tables, then views.

   The view index is separate from the table index on purpose. `getColumnType` serves the visitor
   layer's disambiguation, and widening it to views would change transformation everywhere at once.
   That is a separate decision, to be made from a run. The declaration site needed no change of its
   own. It flattens a table anchor to the column type and hands everything else to `TypeResolver`.

Tests: `PrivateMemberSignatureExtractionTest` (extractor), `PostgresFunctionStubTypeResolutionTest`
(upper-case anchors, `%ROWTYPE` refused), and `ViewAnchoredTypeTest`. The latter builds indices
through `MetadataIndexBuilder` and checks a view in the routine's own schema, one reached through a
synonym, and a three-part anchor, plus stub↔implementation agreement. It ends with an execution test:
stub, then implementation, *exactly one* function, a call after the view stub is swapped for the real
view. Verified by mutation: 5 of its 6 tests fail when `TypeResolver` asks tables only.

*Not in scope, now P1.10 (§5):* `%ROWTYPE` in a signature (4% of stub errors; a table's or view's row type exists in
PostgreSQL under the relation's own name, but the body treats a `%ROWTYPE` variable as `jsonb`, so it
is a calling-convention decision, not a lookup); `%ROWTYPE` package variables on views (7% of blocked
members, mostly tables).

*Confirm on the next run:* `cannot resolve Oracle type '…%TYPE'` should be near zero among stub
errors. The step-28 errors `relation "x" does not exist` where the SQL uses `x` as a `%TYPE` anchor
should be near zero too.

**P1.5 — bare type names resolve against the enclosing package ✅ implemented 2026-10-26,
confirmed by the 2026-10-26 evening run (§10.5).**
Inside a package, Oracle looks a type name up in the package itself first, spec and body alike, so
package code names its own types bare. The 2026-10-26 run showed what that cost. Packages refused
over a package variable of a bare type name held 68% of all blocked members. Bare names were also
17% of stub errors. Classified against the source corpus (named detail in
`local/runs/2026-10-26/`):

- *Blocked members:*
  - 79% sit behind a `TYPE … IS TABLE/RECORD` declared in the **body**
  - 15% behind a spec or body `SUBTYPE`, mostly of a built-in, some of another package's type
  - 6% behind `SUBTYPE x IS tab%ROWTYPE`
  - 2% behind a subtype of a `%TYPE` anchor
- *Stub errors on a bare name:* 95% name a type declared by the member's **own** package, 54% in
  the spec and 38% in the body. Only package-private members are affected; `ALL_ARGUMENTS` qualifies
  public members' types already.

Three defects, fixed together:

1. **Body `TYPE`/`SUBTYPE` declarations were never extracted.** Only body *variables* were.
   `PackageContextExtractor.extractBodyDeclarations` reads all three.
2. **A bare name was never searched for in the enclosing package.** `TypeResolver` now asks
   `TransformationContext.resolveEnclosingPackageType` first. It reads the live package context
   where the implementation job has one, and the step-22 index otherwise. That index now records
   the reduced body's types too, as **body** declarations that are visible only from inside the
   package. The stub job uses one resolution context per package instead of per schema.
3. **A subtype whose base leaves the package was given up on.** `resolveSubtypeAlias` hands the base
   back, and `TypeResolver` resolves it with its full chain: another package's type, a `%TYPE`
   anchor.

Write-up: [TRANSFORMATION.md](TRANSFORMATION.md#object-type-names-in-emitted-types--one-authority-typeresolver--2026-08-27), "Bare type names inside a package".

*Measured* on every package of the local source database, by replaying step 22, the private-member
stubs of step 23, and step 28's package-context type resolution before and after (named detail in
`local/runs/p1.5/`). Nothing that resolved before fails after, on either side.

| | change |
|---|---|
| private-member stubs that fail | −62% |
| package contexts refused over type resolution | −65% |
| members blocked by them | −71% |

The replay sees only type resolution, not PostgreSQL's own verdict on the helper SQL. Of the members
still blocked:

- half are `%ROWTYPE`: package variables, and now subtypes of a row type too
- 17% sit in packages whose spec does not parse, mostly wrapped
- 15% are `VARCHAR(n)` (P1.9)
- 9% are qualified names nothing places
- 5% sit in packages whose body does not parse (conditional compilation)
- 3% are subtypes chaining into another package's unplaceable type

Remaining private-member stub failures are `%ROWTYPE` (45%), qualified names nothing places (42%)
and bare names (12%, nearly all `%ROWTYPE` subtypes).

Tests: `EnclosingPackageTypeTest`, on the production path, ending in an execution test. Each of the
five changes, reverted alone, fails one to eight of its ten tests.

*Not in scope, now P1.10 (§5):* `SUBTYPE x IS tab%ROWTYPE`, which joins the `%ROWTYPE` package variables as one
representation decision — now the largest remaining blocker by the replay above. Variables whose helper SQL PostgreSQL
rejects (8% of blocked members, "helper creation failed") are a separate defect.

*Confirm on the next run:*
- `cannot resolve Oracle type '<bare name>' of package variable` should be left with `%ROWTYPE`
  subtypes only.
- Bare names among stub errors should be down to built-ins PostgreSQL lacks (`JSON_OBJECT_T` and
  similar).
- Blocked members should fall sharply. Those packages will then show their *next* refusal, since a
  package error names only the first variable that fails, and their members will be attempted
  for the first time, so step-28 genuine errors will rise.

**P1.8 — each overload gets its own body ✅ implemented 2026-10-27, measured on the corpus, not
yet by a run.**
Step 22 stored package member sources in a map keyed by name, so a name held only its *last* body.
The damage depended on what shared the name:
- **Same-kind public overloads** all went to step 28 with that one body. The body that matched the
  last overload replaced its stub and reported success; the others did too, while their stubs stayed
  in place. That is a false "implemented", silent everywhere.
- **A function and a procedure of one name** each got the other's source and failed to parse
  (`missing 'PROCEDURE' at 'FUNCTION'`, 2.4% of step-28 errors on 2026-10-26 evening).
- **Private overloads** collapsed to one, and the rest were never extracted.
- **A private overload of a public name** was dropped, because "is it public?" was asked by name.

*What was done.* Sources are stored per name as a **list in body order**. Step 22 pairs each body
with its `ALL_ARGUMENTS` overload and records the body's position on the member
(`FunctionMetadata.sourceOrdinal`), or why it has none (`sourcePairingError`). Step 28 looks the
source up by that position and fails by name when there is none. The rules are in
`function/service/FunctionSourcePairing`:

1. Pair by **signature**, never by position. Body order differs from the dictionary's overload
   order for one overloaded name in five on the local source database. The type-method sibling
   pairs by rank within a name, and that would mis-pair here.
2. Kind, parameter names and modes must match exactly. Types are compared by base-type family,
   because the dictionary has resolved subtypes and anchors that the body still spells as written.
   A named type or an anchor matches anything except a different built-in.
3. A body is paired when it is the only candidate, or the only one that matches every type exactly.
   Paired bodies leave the pool. Overloads the dictionary cannot tell apart, and overloads with no
   unique body, are **refused by name**, not guessed.
4. One overload and one body pair on kind alone, since that body must be its. They pair even when
   the header does not parse; step 28 then reports the parse error, which is the real problem.
5. Bodies left once every overload is paired are private overloads. They are numbered after the
   public ones, so the per-member result keys stay distinct.

The stub sources step 22 also stored were read by nothing in production and carried the same
name-keyed defect, so they were removed.

Tests: `PackageOverloadSourceTest` drives the real extractor and the real step-28 lookup. Its
fixtures are:
- overloads declared in a different order from the specification
- a function and a procedure of one name
- private overloads
- a private overload of a public name
- an anchored pair that must be refused

It ends with an execution test: stubs, then implementations, exactly one function per signature,
and a call to every overload, which must answer with its own body. Verified by mutation:
- a lookup that returns the last body of a name fails 5 of its 6 tests, the execution test with the
  run's own `missing 'PROCEDURE'` error
- rank pairing fails the reordered-overload test

*Measured on the corpus* (named detail in `local/runs/p1.8/`). The real extractor ran over every
unwrapped package body of the local source database, with public members read from the dictionary
as step 22 reads them.
- 98% of overloaded public members are paired. All of them were previously given one shared body.
- Refused by name: 0.16% of public members, i.e. 2% of overloaded ones. Of those, four fifths have
  no unique body, mostly because they are anchored, and the rest cannot be told apart in the
  dictionary.
- Private members rose by about 1%: the private overloads that had collapsed into one, and private
  overloads of public names. 3% of private members are now overloads with their own number.

*Found on the way, not fixed:* the type-method sibling has the rank assumption
`TypeMethodSourcePairing` documents as unverified. On the local source database, one type declares
overloads of four method names in a different order from `METHOD_NO`, so their bodies are silently
swapped (A4).

*Confirm on the next run:*
- `missing 'PROCEDURE' at 'FUNCTION'` and its siblings should be gone from step 28.
- Step-28 members rise by the private overloads now extracted.
- A new error, `has no identifiable source`, should stay around the refused share above.

**P1.9 — built-ins written with a length ✅ implemented 2026-10-27, measured on the corpus, not
yet by a run.**
`TypeConverter` recognises the length-carrying families by prefix (`VARCHAR2(n)`, `NUMBER(p,s)`,
`CHAR(n)`, …) and everything else only by exact name. Three more built-ins appear with a length in
PL/SQL declarations, and none of them was recognised:
- `VARCHAR(n)`, Oracle's synonym of `VARCHAR2`;
- `STRING(n)`, a PL/SQL subtype of `VARCHAR2`;
- `INTEGER(n)`.

A package variable or subtype of one of them refused its whole package context, and a local
declaration reached PostgreSQL verbatim (`type "string" does not exist`). On the 2026-10-26
evening run, together they blocked 9.5% of blocked members. The run's unresolved types contain no
other built-in with a length.

*What was done.* `TypeConverter.toPostgreOrNull` maps these three when written with a length, so
package variables, subtypes, private signatures and local declarations all pick it up through
`TypeResolver`:
- `VARCHAR(n)` and `STRING(n)` map like their unconstrained forms. The length is dropped, as it is
  for `VARCHAR2(n)`.
- **`INTEGER(n)` maps to `numeric`, not `integer`.** It is `NUMBER(n,0)`, and `INTEGER(11)` holds
  values a PostgreSQL `integer` cannot, so it maps as `NUMBER(p)` does.

The types are listed rather than any `(…)` being stripped, because a length can change what a type
is. `FLOAT(126)`, for instance, stays unrecognised and is refused by name, where stripping would
silently map it to `real`. That mapping of unconstrained `FLOAT` to `real` is lossy too, but it
predates this item and is left alone.

Tests:
- `TypeConverterTest`: the three shapes, including the `VARCHAR(20CHAR)` spelling `getText()`
  produces, plus the refusal of unlisted types.
- `BuiltinTypeLengthIntegrationTest`: package variables, a `SUBTYPE … IS STRING(20)` and local
  declarations go through helper generation and transformation, and the function is called. It
  must return an eleven-digit `INTEGER(11)` value intact.

Disabling the new branch fails the integration test and three of the converter tests.

*Measured on the corpus* (named detail in `local/runs/p1.9/`). Step 22 and step 28's package-context
type resolution were replayed over every unwrapped package of the local source database, before and
after, as for P1.5 (§6.P1.5):
- Every package refused over a built-in with a length passes type resolution, except one in thirty.
  That one moves on to its next refusal, a compat-layer type (P1.7).
- Members blocked by type resolution: −13.5%.
- Nothing that resolved before fails after.

*Confirm on the next run:* no `cannot resolve Oracle type 'varchar(…)'`, `'string(…)'` or
`'integer(…)'`, and no `type "string" does not exist`.

**P1.13 — `#` and `$` in declared names ✅ implemented 2026-10-28, not yet measured.**
Oracle allows `#` and `$` in an unquoted name, and PostgreSQL does not. 1.7% of step-28 errors on
the 2026-10-26 evening run were this, in two shapes: `syntax error at or near "#"` and
`invalid type name "# integer"`. Both came from the same defect.

The roadmap had placed the fix in `PostgresIdentifierNormalizer`. That was wrong: the normalizer
already quotes such a name, and every *reference* went through it via `IdentifierHelper.emit`.
The *declarations* did not. Each declaration site lower-cased the raw text, so the name was quoted
everywhere except where it was declared:
- local variables: 97% of the errors, the `invalid type name` shape
- `FOR` loop indexes
- loop records, cursors and cursor parameters, which the run never reached but which share the
  defect
- parameters, where the implementation also disagreed with the stub, which already normalized
  them

Neither the stubs nor the package-variable helpers showed the error.

*What was done.* Local declarations use the new `IdentifierHelper.declare`, spelled the way their
references are. Parameters use the normalizer, the stub's rule. The two rules differ on purpose;
the reasons and the remaining gap (routine names) are in
[TRANSFORMATION.md](TRANSFORMATION.md#quoted-identifiers-oracle-delimited_id--2026-08-14).
On the way: a cursor FOR loop emitted `c(a, b)` as raw text, arguments untransformed, because the
grammar reads the call as one name. Its arguments now go through the visitor.

Tests: `SpecialCharacterIdentifierTest` has a stub↔implementation agreement test and an execution
test that replaces the stub, asserts exactly one function and calls it. Reverting the variable
fix fails two of its three tests; reverting the parameter fix fails all three.
`IdentifierHelperTest` covers `declare`.

*Confirm on the next run:* no step-28 `syntax error at or near "#"` and no `invalid type name "# …"`.
Those routines will then show their next error, if they have one.

**P1.7 — types of Oracle's own packages, and routine names that need quoting ✅ implemented
2026-10-28, not yet measured.** Three parts were planned. Two are done; the third turned out to be
unnecessary.

*Compat-package types.* `TransformationContext` flattened any `<compat package>.<type>` to
`oracle_compat.pkg__type`. The compat layer installs functions only, so no such type ever existed:
an answer with no metadata behind it. On the 2026-10-26 evening run this was 17% of stub errors
(`schema "oracle_compat" does not exist`), almost all `OWA.VC_ARR`, the `name_array`/`value_array`
of flexible-parameter web entry points. It was also 18% of blocked members, from the packages
whose package variables had such a type.

The roadmap's plan was to refuse these types by name. That would only have renamed the error, and
kept all of those packages blocked. These are ordinary package types: Oracle's `OWA` spec declares
`VC_ARR` as `TABLE OF VARCHAR2(32000) INDEX BY BINARY_INTEGER`, and every package's collection
becomes `jsonb`. So `OracleBuiltinPackageTypes` lists the documented declarations of the compat
packages' types, and they resolve like any package collection:
- `OWA.VC_ARR`, `DBMS_OUTPUT.CHARARR`, `DBMS_UTILITY.NUMBER_ARRAY`, `OWA_UTIL.IP_ADDRESS` and
  eleven more become `jsonb`, and a local declaration gets the `'{}'` initializer;
- `UTL_FILE.FILE_TYPE` becomes `integer`, the handle the compat layer's `utl_file__fopen` returns;
- a type no Oracle package declares is not guessed: refused in a signature by name, `jsonb`
  mid-expression, as for any undeclared package type.

The stub and the implementation both reach this through `TypeResolver`, so they agree.

*Install the compat layer before the stubs: dropped.* With no type naming `oracle_compat`, no stub
references that schema. On the evening run, every `oracle_compat` stub error was a type.

*Routine names.* The flattened name was concatenated, `pkg__` + member, on both sides. A member
whose name is not a plain identifier therefore reached PostgreSQL as `pkg__"skip all"` or
`pkg__<= limit`, a syntax error. That was 5.4% of stub errors, more than the ~2% estimated in
§10.5, which had counted only the quoted shape. Nearly three quarters of them were package-private
members whose name kept its delimiters from the body parse, so the name did not even match the
dictionary's.
Fixed in three places:
- `PostgresIdentifierNormalizer.normalizeRoutineName` quotes the whole flattened name when it needs
  it (`"pkg__skip all"`). The stub (`FunctionMetadata.getPostgresName`) and the implementation
  (`VisitFunctionBody`, `VisitProcedureBody`) both call it.
- The implementation reads the routine name through `IdentifierHelper.unquote`.
- Step 22 stores private member names without delimiters. This also closes P1.13's remaining gap,
  `#` in a routine name.

**Call sites are not changed.** A call to `pkg."skip all"` still emits the concatenated name.

*Found on the way, not fixed:*
- **Integer-keyed associative arrays (`INDEX BY PLS_INTEGER`) are broken for every inline
  collection, and silently** (A3). An element write stores object key `"i-1"`, an element read uses
  `->> int`, which on a jsonb object returns NULL, and `.COUNT` raises `cannot get array length of a
  non-array`. Nothing tested a round trip. Now that `OWA.VC_ARR` resolves, the same choice also
  decides what a caller passes for it: today it would be `{"0": …}` for index 1.
- **A parameter of a collection type is not registered as a collection**, so `name_array(i)` is
  emitted as a function call and `name_array.COUNT` as a column reference. This is true for any
  package collection type, not only the compat ones.

Tests:
- `CompatPackageTypeTest`: a function with two `OWA.VC_ARR` parameters and `NUMBER_ARRAY` and
  `FILE_TYPE` locals. Its stub and implementation signatures agree, the implementation replaces
  the stub (exactly one function), and the function is called. Its body reads no element,
  because of the first finding above.
- `PackageQualifiedTypeDeclarationTest`: its four compat-type tests asserted the old flattening, of
  an invented `HTP.PageType`. They now assert the declarations and the refusal.
- `QuotedRoutineNameTest`: a private member `"skip all"` and a member `next#` are extracted bare. Stub
  and implementation name them identically, and both run.

Restoring the old flattening fails 6 of the 16 type tests. Reverting any one of the extractor,
stub-name and implementation-name changes fails `QuotedRoutineNameTest`.

*Confirm on the next run:*
- no `schema "oracle_compat" does not exist` at step 23;
- no `oracle_compat` type in a step-28 helper error;
- no stub syntax error at a routine name.

Those packages will then show their next refusal, if they have one.

**P1.14 — Oracle `(+)` outer joins ✅ implemented 2026-10-29, measured on the corpus, not yet on a
run.** Found when a view in production use was reported to have lost its outer joins. It had lost
all three: each condition was written in parentheses, `( a.k = b.k(+) )`, which the analyzer did not
recognize, and the `(+)` visitor stripped every marker unconditionally. So the view compiled as an
inner join, and the run reported it implemented.

*Size* (named detail in `local/runs/outerjoin_*`). 11% of the source database's views use `(+)`.
Against the 2026-10-26 run's target:
- 69% of these views were still stubs, against 29% of all views. 37% of those stubs had failed on
  a join-tree scope error (`missing FROM-clause entry`), which the generator caused itself.
- Of the implemented ones, 12% had lost every outer join and 3% some. None of the rest could be
  trusted either: inverted join directions and dropped conditions do not show in a count of joins.

The code was unchanged since the initial release, so this is not a regression. Eight distinct
defects, each reproduced under invented names. All but one were silent. They are listed with the
rule that replaces them in
[TRANSFORMATION.md](TRANSFORMATION.md#oracle--outer-joins--2026-10-29).

*What was done.* `OuterJoinAnalyzer` and `VisitFromClause.generateAnsiJoinSyntax` were rewritten to
Oracle's rule. Each point of the rule was checked against the source database with read-only
`SELECT`s over `dual`, including the error numbers for the refused shapes:
- every WHERE conjunct with `(+)`, of any shape, joins the item its `(+)` columns qualify;
- conjuncts are found by splitting on AND and unwrapping redundant parentheses;
- dependencies are read off tokens, not parser rules;
- the output is always `LEFT JOIN`, keeping FROM order except where a dependency forces a move.

Two safety nets came with it:
- **Claimed markers.** `visitOuter_join_sign` drops a `(+)` only if the analysis moved it into a
  join. Anything else throws, so a shape nobody thought of fails by name instead of becoming an
  inner join.
- **One context per query block.** A context records the FROM/WHERE nodes it was built from.
  CONNECT BY, transformed outside the analysis, pushes an empty one. Without this, an inline view
  under CONNECT BY built its FROM from the *enclosing* query's join conditions.

*Measured on the corpus.* The old and the new transformer were replayed over all of these views
with the same harness (no metadata, `TransformationService.transformSql`). Each output was
`EXPLAIN`ed in the 2026-10-26 target with `search_path` set to the view's schema:

| | old | new |
|---|---|---|
| `EXPLAIN` succeeds | 20% | **40%** |
| join-tree scope error (an alias of the view's own FROM) | 19% | **0**, apart from one CONNECT BY defect (below) |
| transformation error | 24% | 2.5%, none about outer joins |
| refused for a `(+)` shape | — | 0 |

The remaining `EXPLAIN` errors are mostly the harness: without metadata, package constants and
parameterless functions (`pkg.c_const`) read as columns of a table that does not exist. Only the
next run measures the step. Per view, 158 now carry more LEFT JOINs than before. Three carry fewer,
each a table outer-joined to two others: the old code split it into two joins, one to the wrong
partner, and the new code emits one combined `ON`. The one view with a real `(+)` and no LEFT JOIN
uses `(+)` against a correlated outer column, which Oracle treats as a filter.

*Found on the way, not fixed:*
- **An unaliased table followed by a bare `JOIN` is read as the alias `JOIN`.**
  `SELECT 1 FROM a JOIN b ON a.k = b.k` comes out as `SELECT 1 FROM a JOIN`: the unanchored entry
  rule (CLAUDE.md) stops early and reports no error. `INNER JOIN`, or any alias on `a`, parses
  correctly. Not measured. A silent truncation, so it ranks with the open decision on anchoring the
  grammar.
- **CONNECT BY in an inline view emits a final `SELECT` that references the inner alias.** The
  shape is `( WITH RECURSIVE h AS (…) SELECT k.nr FROM h )`, with the alias `k` out of scope. It
  fails loudly. It was hidden behind the table-count error the context mix-up used to raise.

Tests:
- `OuterJoinTransformationTest`: 29 new string tests, one for each defect shape and each refusal.
  Two old tests changed on purpose: `a.f > b.f(+)` is valid Oracle and is now translated, and
  `a.k(+) = b.k` gives `b LEFT JOIN a`, not `a RIGHT JOIN b`. A third had its
  "known limitation" comment removed, because the limitation no longer exists.
- `PostgresOuterJoinValidationTest`: six execution tests over rows with no match, which is what
  tells an outer join from an inner one. Moving constant conditions back to WHERE fails two of
  them. Comma-joining everything fails two.

*Confirm on the next run:*
- re-run `local/tools/outerjoin_audit.py`: no implemented view with fewer outer joins than
  Oracle;
- the share of `(+)` views still stubs falls towards the share of all views;
- no step-27 `missing FROM-clause entry` naming an alias of the view's own FROM.

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
**A3c, found 2026-10-28 (§6.P1.7): the inline model is not whole either.** For integer-keyed
associative arrays, element writes and reads disagree, so reads return NULL silently and `.COUNT`
raises. Collection-typed *parameters* are not registered as collections at all. A3c should come
before A3b, because it is silent and affects code that "implements" today. Its representation
choice (object keyed by index, or array) is also the calling convention for `OWA.VC_ARR`.

**A4 — Small defects found and deliberately not fixed:**
- `getExistingPostgresFunctions` keys on `pg_proc.proname`, so a re-run into a non-empty target
  silently skips every overload after the first. Local runs make re-runs more likely, so this rises
  in priority.
- `TypeMethodImplementationResult` keys its buckets by name, so overloaded type methods have no
  recoverable per-object record (the function job was fixed in §14.4; the sibling was not).
- `resolvePackageQualifiedType` reports unknown packages on `System.err`, which reaches no log.
- `AntlrParser` clears ANTLR's **static** DFA and prediction-context caches after every parse, to
  bound memory. Every parse therefore starts cold: about 7–10 ms even for a routine header. Since
  P1.8, step 22 parses every package member's header, about four times as many parses as before,
  which adds roughly ten minutes to the run. The caches are shared across a JVM, so parsing on
  several threads makes the threads clear each other's cache, and it is slower than sequential
  parsing. Matters if step 22 or 28 is ever parallelised. (Found 2026-10-27.)
- Seven type-method "source not found" errors on base types, probably abstract methods filed as
  errors instead of skips.
- `TypeMethodSourcePairing` pairs overloads by rank within a name, assuming the type body declares
  them in `METHOD_NO` order. On the local source database one type declares four overloaded names
  in a different order, so those bodies are attached to the wrong signatures without any error. Pair
  by signature, as `FunctionSourcePairing` does (§6.P1.8).

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
- Cross-schema bare type references (`%ROWTYPE` in signatures is now P1.10, §5)
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
| D9 | `%ROWTYPE` outside a local declaration — in a signature, a package variable, a `SUBTYPE` (P1.10) | `jsonb`, the representation body code already uses for a `%ROWTYPE` local, so that one variable never has two shapes. A Java caller then passes and receives JSON instead of a composite, which pgjdbc reads only as an unparsed string. Check first what PostgreSQL does with `tab%ROWTYPE` in a signature, and whether a relation's own row type would serve the body as well. Decide with D8. |

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

### 10.3 Re-run 2026-10-25 — after P1.1

Same database and configuration; named detail in `local/runs/2026-10-25/ANALYSIS.md`. Every step
other than 23 and 28 is identical to §10.1 to the unit, type methods included — the shared walker
(§6.A1) did not move the type-method scanner.

| | 2026-10-24 | 2026-10-25 |
|---|---|---|
| 23 function stubs | 62,489 / 75,325 | 61,943 / 74,695 |
| 28 implemented | 20,298 (26.9%) | **33,365 (44.7%)** |
| 28 errors | 10,899 | 15,396 |
| 28 blocked / failed package contexts | 44,128 / 1,822 | 25,934 / 957 |
| — of which package body does not parse | 26,509 / 1,063 pkgs | 275 / 16 pkgs (conditional compilation) |
| 28 `<EOF>` errors | 2,195 | 17 |

- **The stub denominator fell by 630** because the old scanner, after truncating a member, reported
  the member's *nested* subprograms as package-level private functions. They never existed.
- **Blocking has moved to package-variable type resolution** (91% of blocked members): bare names
  that are body-declared types (46%, P1.5), references into the subtype package, whose spec does not
  parse (36%, P1.3), `%ROWTYPE` (4%), and `VARCHAR(n)` (2%, P1.9). These packages were hidden behind
  the parse failure before.
- **Errors rose because 13,000 more members were attempted**, not because anything regressed. The
  mix: relation/type does not exist ~23%, PostgreSQL syntax errors from untransformed constructs
  (`%`, `DECLARE`, `PERFORM`, `#`) ~16%, OUT-parameter and return-type signature disagreements ~15%
  (P1.6, A2), TABLE() / collection / BULK COLLECT ~12%, multi-part package calls ~6%.
- **New: overload source collision (P1.8).** 295 errors read `missing 'FUNCTION' at 'procedure'`:
  a package has a procedure and a function of the same name, and one was handed the other's source.
  The same mechanism is silent for same-kind overloads.

### 10.4 Re-run 2026-10-26 — after P1.2, P1.3 and P1.4 part 1

Same database and configuration; ratios only, named detail in `local/runs/2026-10-26/ANALYSIS.md`.
The run carried P1.4's extractor change but not its view anchors. Steps not listed are identical to
§10.1.

| | 2026-10-25 | 2026-10-26 |
|---|---|---|
| 23 function stubs | 82.9% | **89.5%** |
| 27 views | 63.0% | **71.2%** |
| 28 implemented · error · blocked | 44.7% · 20.6% · 34.7% | **50.7% · 25.0% · 24.3%** |
| 28 failed package contexts | | −36% |

- **P1.3 confirmed.** The subtype package no longer blocks anyone. It had blocked 36% of blocked
  members.
- **Stub errors:** 57% are unresolvable types. These are:
  - 17% schema-qualified names nothing places (XMLTYPE, DBMS_XMLDOM, schema-level collections)
  - 17% bare names (P1.5)
  - 10% `%TYPE` on views (P1.4 part 2)
  - 4% `%ROWTYPE`
  - 4% via a synonym to a non-relation

  Outside the unresolvable types: 21% are Oracle functions with OUT parameters (P1.6), 12% are
  `schema "x" does not exist` (P1.7), and 6% are signature disagreements on re-creation.
- **Step-28 errors:**
  - `relation "x" does not exist` 15%, of which four fifths are `%TYPE` anchors on views (P1.4 part 2)
  - PostgreSQL syntax errors 15%
  - type does not exist 10%
  - multi-part package calls 9%
  - OUT-parameter and return-type disagreements ~10% (P1.6, A2)
  - TABLE() 5%
  - BULK COLLECT 3%
  - P1.8 parse collisions 2%
  - inquiry directives 2%
- **Blocked members** by package cause:
  - package variable of a bare type name 68% (P1.5)
  - helper creation failed 8%
  - `%ROWTYPE` package variable 7%
  - spec does not parse 6% (mostly wrapped)
  - `%TYPE` package variable 3%
  - `VARCHAR(n)` 2.5% (P1.9)
  - body does not parse 1.5% (conditional compilation)

### 10.5 Re-run 2026-10-26 evening — after P1.4 part 2 and P1.5

Same database and configuration; ratios only, named detail in `local/runs/2026-10-26-evening/ANALYSIS.md`.
Only steps 23 and 28 changed. Every other report, views and type methods included, is identical in
content to §10.4. The run took 4h17m against 3h38m because step 28 attempted more members.

| | 2026-10-26 | 2026-10-26 evening |
|---|---|---|
| 23 function stubs | 89.5% | **92.1%** (errors −25%) |
| 27 views | 71.2% | 71.2%, identical |
| 28 implemented · error · blocked | 50.7% · 25.0% · 24.3% | **68.7% · 22.7% · 8.6%** |
| 28 failed package contexts | | −50% |

**No regression.** Per routine, nothing implemented on the morning run fails on the evening run, and
no overloaded name lost an implemented overload. Of the members blocked in the morning, 49% are now
implemented, 11% are errors and 34% are still blocked. Of the morning's errors, 20% are now
implemented: half as many as the members that went from blocked to an error, so genuine errors
*fell* by 9% even though far more members were attempted.

**P1.4 confirmed.**
- `%TYPE` stub errors: −98%.
- Step-28 `relation … does not exist` on a `%TYPE` anchor: −96%. Those errors were 52% of all routines
  that went from error to implemented.

**P1.5 confirmed.**
- Bare-name stub errors: −89%. The rest are `JSON_*_T` built-ins and a `%ROWTYPE` subtype.
- Package variables of a bare type name: −92% of the members they blocked.
- Of the 47 packages still refused over a bare name, 33 are refused over `SUBTYPE x IS tab%ROWTYPE`,
  and 5 over a built-in with a length (`STRING(n)`, `INTEGER(n)`, the same defect as P1.9).
- The corpus replay (§6.P1.5) predicted the remaining type-resolution blocking to within 10%.

**Stub errors (step 23):**
- 41% unresolvable types:
  - 32% qualified names nothing places (XMLTYPE, DBMS_XMLDOM, schema-level collections)
  - 6% `%ROWTYPE`
  - 2.5% bare names
- 28% Oracle functions with OUT parameters (P1.6)
- 17% `schema "oracle_compat" does not exist` (P1.7)
- 9% signature disagreements on re-creation
- 2% quoted member names (P1.7)

**Step-28 errors:**
- syntax errors 22%, of which:
  - **local subprograms 9%** (new)
  - `%ROWTYPE` in a signature or `DECLARE` 5%
  - **`#` in identifiers 2%** (new)
- multi-part package / chained method calls 17%
- OUT-parameter shape 9% (P1.6)
- TABLE() 6%
- missing FROM-clause 5%
- type does not exist 5%
- signature disagreements on re-creation 5% (A2, P1.8)
- `relation … does not exist` 4%, nearly all `%ROWTYPE` anchors
- BULK COLLECT 4%
- element assignment on a schema-level type 4% (A3)
- **writes to package-level collections 4%** (new)
- P1.8 parse collisions 2%
- inquiry directives 2%

**Blocked members** by package cause:
- `%ROWTYPE` 32%: package variables 25%, `SUBTYPE … %ROWTYPE` 7%
- **compat type that is never created 18%** (new)
- spec does not parse 17% (13% wrapped)
- built-in type with a length, `VARCHAR(n)` / `STRING(n)` / `INTEGER(n)`, 9.5% (P1.9)
- qualified names nothing places 6%
- other bare names 6%
- helper SQL rejected, quoted record-field names 5%
- body does not parse 4% (conditional compilation)

**`%ROWTYPE` is now the largest single remaining cause.** It is about a third of blocked members,
9% of step-28 errors and 6% of stub errors, on every route. It is one representation decision
(D9, P1.10), not a lookup.

**New** (in §5 since 2026-10-27 as P1.10–P1.13, plus the reshaped P1.7):
- **Local subprograms.** A function or procedure declared inside another routine's `DECLARE` is
  emitted as a nested `CREATE OR REPLACE FUNCTION` in the declare section. PostgreSQL rejects the
  whole routine. 9% of step-28 errors, the largest single construct.
- **Element writes to a package-level collection.** `g(i) := v` is emitted as a local
  `g := jsonb_set(g, …)`, without the package-variable setter, so PostgreSQL rejects `g` as not a
  known variable. A collection method on such a variable (`g.EXISTS(i)`) is also read as a package
  function call. 4% of errors, which tripled because P1.5 unblocked the packages that do this.
- **`#` in identifiers.** Oracle allows `#` in an unquoted name. PostgreSQL does not, so the name must
  be quoted. A `PostgresIdentifierNormalizer` question.
- **Compat-layer types are resolved but never installed.** `TransformationContext` maps
  `<builtin pkg>.<type>` to `oracle_compat.pkg__type` unconditionally, but no compat implementation
  creates a type. This is a positive answer without positive metadata (CLAUDE.md, "Object Type
  Names"). It costs 17% of stub errors and 18% of blocked members, nearly all `OWA.VC_ARR` in web
  entry points. P1.7's reordering would only change the message.
