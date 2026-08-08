# Consolidation Plan (July 2026)

## Strategic Context

Decision from the July 2026 project review: **primary effort goes to consolidating the
established, production-used features** (DDL migration, view transformation, data transfer).

Rationale:
- The tool is actively used in a real migration where PL/SQL code is migrated to Java
  in small pieces. A *full* automated PL/SQL→PL/pgSQL transformation was never required
  for this project; PL/SQL functions inside views would have been helpful but are not critical.
- The mod_plsql web gateway would only have been a nice-to-have intermediary solution.
- Transfers run all-or-nothing (including the daily Jenkins run), so a resume/state-persistence
  mechanism has not been needed in practice and is **not** part of this plan.
- The review found the remaining PL/SQL long tail (BULK COLLECT, collections, %ROWTYPE,
  dynamic SQL, autonomous transactions) has steeply rising marginal cost per construct via
  hand-written visitors — the wrong investment while the established features still have
  cheap, high-value gaps.

Housekeeping: once Phase 1 starts, update the "Strategic Direction" section of CLAUDE.md
to reflect this plan (PL/SQL transformation → maintenance/hardening, mod_plsql → deferred).

---

## Phase 1: Consolidation (ACTIVE)

Items in priority order.

### 1. Error Transparency & Frontend Performance

**Problem:** When a transformation or job fails, it is hard to find out *what* is wrong.
The frontend is inconsistent between features and becomes very slow when thousands of
tables/views are involved.

**Current state (evidence, re-checked 2026-08-08):**
- Failed view transformations *do* surface in the view panel with error message and failing
  SQL (`view-service.js`, `displayViewImplementationResults`) — the plan was too pessimistic
  here. What is missing is the failing *construct*, the source line, and the Oracle source.
- Status endpoints return full object lists; the UI renders all rows — no pagination,
  aggregation, or virtualization.

**Approach:**
- [ ] Per-object error detail: clicking a failed view/function/table shows the
      transformation error message, the failing construct, and the original Oracle source.
      Backend already has the data (TransformationException messages); expose it via the
      job status/result endpoints instead of logs only.
- [ ] Aggregate-first UI: summary counts per job (ok / failed / skipped) from a lightweight
      endpoint; load object lists lazily, paginated, filterable by status ("show only failures").
- [ ] Audit frontend polling: avoid re-fetching and re-rendering full lists on every poll tick.
- [ ] Consistency pass over the feature panels (same badge semantics, same detail view,
      same toggle behavior across tables/views/functions/triggers).
- [x] **Pre-flight compatibility report** (done 2026-08-08, views only): one job that parses
      all extracted views and produces a categorized report of unsupported constructs *before*
      anything is created. This is also the data source for item 5.
      See "Pre-Flight Compatibility Report" below.
- [ ] Extend the pre-flight report to functions/procedures (needs package context, so it is a
      separate step from the view analysis).

**Acceptance:** A failed object's root cause is reachable in ≤2 clicks; the UI stays
responsive with 5,000+ tables/views.

**Effort:** M–L (largest item, but can be delivered incrementally per sub-task).

### 2. Parallel Data Transfer

**Problem:** Data transfer is the slowest part of the pipeline for realistically sized
databases. Tables are currently transferred strictly sequentially over a single
Oracle/PostgreSQL connection pair (`CsvDataTransferService` / `DataTransferJob`).

**Approach:**
- [ ] Transfer N tables concurrently (worker pool, N configurable, default ~4–8) with one
      Oracle read connection + one PostgreSQL COPY connection per worker.
- [ ] Keep the existing per-table producer/consumer pipe and LOB staging workflow unchanged
      inside each worker (it is correct and tested); parallelism is *across* tables only.
- [ ] Schedule largest tables first (row counts are already in state) to avoid a long tail.
- [ ] Aggregate progress reporting across workers (ties into item 1's UI work).
- [ ] Secondary optimizations, only if still needed after parallelism:
      adaptive batch sizes (currently hard-coded 10K–50K, LOB 50) and deferring FK-index
      creation until after all tables are loaded.

**Acceptance:** Wall-clock transfer time for a multi-hundred-table schema improves
roughly linearly with worker count; failures in one table do not abort other workers;
per-table error reporting unchanged.

**Effort:** M

### 3. Index Migration (beyond FK indexes)

**Problem:** Only FK-supporting indexes are created today. All other Oracle indexes are
lost, so the migrated database silently underperforms.

**Approach:**
- [ ] `OracleIndexExtractionJob`: extract from `ALL_INDEXES` / `ALL_IND_COLUMNS` /
      `ALL_IND_EXPRESSIONS` — normal, unique, composite, function-based; skip indexes
      auto-created for PK/UK constraints (already covered by constraint creation).
- [ ] `PostgresIndexCreationJob`: B-tree equivalents; transform function-based index
      expressions through the existing SQL expression transformer where possible, report
      (not fail) the ones that don't transform.
- [ ] Handle: DESC columns, reverse key (→ plain B-tree + note), bitmap (→ plain B-tree),
      domain/text indexes (report as unsupported).
- [ ] Create indexes **after** data transfer in the orchestration order (also benefits item 2).
- [ ] Verification job comparing index coverage per table, surfaced in the UI.

**Acceptance:** For a real schema, ≥90% of non-constraint indexes exist in PostgreSQL
after migration; the rest are explicitly listed with reasons.

**Effort:** M

### 4. Transformer Hardening (no new features)

**Problem:** Several transformer paths fail *silently* — statements are dropped or pragmas
ignored while the function is reported as successfully transformed. Silent semantic
corruption is worse than a loud failure, and it undermines trust in the transformer's
otherwise real coverage.

**Known silent paths (verified still present 2026-08-08):**
- **Unanchored grammar entry rules — the worst one, found by the pre-flight work.**
  `select_statement` / `function_body` are not anchored to EOF, so when the parser hits a
  construct it cannot place, it ends the rule early, leaves the rest of the source unread and
  reports **no error**. Measured: `SELECT ... FROM sales_view MODEL PARTITION BY ...` consumes
  49 of 149 characters, reads `MODEL` as a table alias, and transforms "successfully" into a
  truncated view. Every construct outside the grammar's reach fails this way, silently.
  → Detection exists now (`ParseCompleteness`, reported as `TRUNCATED_PARSE`); making the
  *transformer* reject truncated parses is the open decision — it will convert an unknown
  number of currently "successful" views into loud failures, so run the report first.
- `VisitSeq_of_statements:31` — silently `continue`s on statements that transform to null
- `VisitPragma_declaration:69` — silently ignores `AUTONOMOUS_TRANSACTION` (changes commit
  semantics!); now at least *reported* by the pre-flight construct catalog
- `VisitGeneralElement` — returns null for unsupported cross-schema references
- 166 `return null` sites in `transformer/` still to audit

**Approach:**
- [ ] Policy: every unsupported construct either throws `TransformationException` (function
      is then skipped and *reported*, existing mechanism in `PostgresFunctionImplementationJob`)
      or — where continuing is genuinely safe — emits an explicit marker comment in the
      output and a warning in the result.
- [ ] Per-function transformation report: construct category + source line for every
      failure/warning (feeds item 1's error detail view).
- [ ] No new grammar/visitor features in this phase. Bug fixes to *existing* supported
      constructs are in scope.

**Acceptance:** Zero code paths that drop Oracle statements without a trace; a function
is either correct, explicitly annotated, or explicitly failed.

**Effort:** S–M

### 5. View Transformation Gaps — driven by real-world cases

**Problem:** Some complicated views still fail. Known candidate gaps from the review:
PIVOT/UNPIVOT, MODEL clause, compound `(+)` outer-join expressions, `REGEXP_*` with
position/occurrence > 1, `ORDER SIBLINGS BY`, RETURNING clause, cursor expressions.

**Approach — explicitly demand-driven, not coverage-driven:**
- [ ] Run the pre-flight compatibility report (item 1) against the real project's views.
      **This is the immediate next action** — the report exists now, so the ranking that
      decides this item's order can be produced instead of guessed.
- [ ] Rank failing constructs by *frequency in the actual codebase*; fix top-down.
- [ ] For each fixed construct: add both a string-comparison test and (where feasible) an
      execution test against PostgreSQL (Testcontainers pattern from
      `PostgresPlSqlCursorAttributesValidationTest`).
- [ ] Constructs that don't appear in real views are out of scope, whatever they are.

**Acceptance:** Every view in the reference project either transforms or has a documented,
categorized reason visible in the UI.

**Effort:** ongoing, sized per construct after the report exists.

---

## Pre-Flight Compatibility Report (implemented 2026-08-08)

Analyses every extracted Oracle view in memory — **no database connection, nothing created** —
and answers "what will fail, why, and how often" before a migration run.

**Modules:**
- `transformer/analysis/` — pure, transformer-side detection:
  - `ConstructCatalog` — the constructs worth reporting (PIVOT, UNPIVOT, MODEL, CURSOR(),
    flashback, SAMPLE, GROUPING SETS, ROLLUP/CUBE, XMLTABLE, JSON_TABLE, ORDER SIBLINGS BY,
    FORALL, BULK COLLECT, EXECUTE IMMEDIATE, PRAGMA AUTONOMOUS_TRANSACTION).
    Support status is **derived by reflection** from the visit methods `PostgresCodeBuilder`
    actually declares, so the catalog cannot go stale when a visitor is added. Only constructs
    that have a visitor but are dropped inside it carry a hand-maintained `IGNORED` override.
  - `ConstructDetector` — walks the parse tree and reports occurrences with line + snippet.
    Independent of the transformation outcome, which is what makes *silent* losses visible.
  - `ParseCompleteness` — detects source the parser never read (see item 4).
- `preflight/` — `OracleCompatibilityReportJob` (extraction job, type `COMPATIBILITY_REPORT`),
  `ViewCompatibilityAnalyzer`, `CompatibilityReportAggregator`, `PreFlightResource`.
- `core/job/model/preflight/` — `CompatibilityFinding`, `CompatibilityStatus`,
  `CompatibilityReport`, `ConstructStat`, `FailureStat`.

**Statuses:** `OK`, `OK_WITH_WARNINGS` (transformed but contains an unhandled construct),
`TRUNCATED_PARSE`, `PARSE_ERROR`, `TRANSFORM_ERROR`, `NO_SOURCE`.

**Ranking:** constructs are ranked by the number of *failing* views they appear in, not by raw
occurrences — that is the number that decides what is worth implementing. A construct that is
unhandled but appears in *passing* views is flagged as a **silent loss**.

**REST API:**
- `POST /api/preflight/oracle/analyze` — start the analysis (requires extracted Oracle views)
- `GET /api/preflight/report` — aggregate: status counts, ranked constructs, failure groups
- `GET /api/preflight/report/findings?status=&construct=&signature=&limit=&offset=` — per-object
  detail, filtered and paginated so the UI never pulls thousands of rows

**Frontend:** "Pre-Flight Compatibility Report" panel in `index.html` / `preflight-service.js`.
Aggregate first; the objects behind a construct or failure group load lazily on expand.

**Not covered yet:** functions/procedures (need package context), and the transformer still
accepts truncated parses — the report only reports them.

---

## Phase 2: PL/SQL Long Tail (DEFERRED — decide after Phase 1)

Decide with data, not intuition: the pre-flight report + hardened error reporting from
Phase 1 will show which constructs actually block the real codebase and how often.
- If failures cluster in 2–3 constructs → consider targeted deterministic support.
- If long-tail → implement the already-designed AI-assisted transformation queue
  (`AI_TRANSFORMATION_IMPLEMENTATION_PLAN.md`, designed but never built) instead of
  writing more visitors.

## Phase 3: Mod_plsql Web Gateway (DEFERRED — paused)

Paused until Phase 2 resolves. Known state: HTP buffer + routing work; parameter passing
(`PlsqlExecutor`, TODO at the procedure call), HTF functions, cookies, and package-variable
session state under connection pooling are all open. Nice-to-have intermediary solution only;
no further investment while the PL/SQL→Java piecewise migration remains the primary strategy.
