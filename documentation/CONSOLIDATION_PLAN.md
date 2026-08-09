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
- [x] Transfer N tables concurrently (worker pool, N configurable, default 4) with one
      Oracle read connection + one PostgreSQL COPY connection per worker. *(done 2026-08-08)*
- [x] Keep the existing per-table producer/consumer pipe and LOB staging workflow unchanged
      inside each worker (it is correct and tested); parallelism is *across* tables only.
- [x] Schedule largest tables first (row counts are already in state) to avoid a long tail.
- [x] Aggregate progress reporting across workers (ties into item 1's UI work).
- [ ] Secondary optimizations, only if still needed after parallelism:
      adaptive batch sizes (currently hard-coded 10K–50K, LOB 50) and deferring FK-index
      creation until after all tables are loaded.

**Acceptance:** Wall-clock transfer time for a multi-hundred-table schema improves
roughly linearly with worker count; failures in one table do not abort other workers;
per-table error reporting unchanged.

**Effort:** M

**Implementation (2026-08-08):** see "Parallel Data Transfer" below.

### 3. Index Migration (beyond FK indexes)

**Problem:** Only FK-supporting indexes are created today. All other Oracle indexes are
lost, so the migrated database silently underperforms.

**Approach:**
- [x] `OracleIndexExtractionJob`: extract from `ALL_INDEXES` / `ALL_IND_COLUMNS` /
      `ALL_IND_EXPRESSIONS` — normal, unique, composite, function-based; skip indexes
      auto-created for PK/UK constraints (already covered by constraint creation).
- [x] `PostgresIndexCreationJob`: B-tree equivalents; transform function-based index
      expressions through the existing SQL expression transformer where possible, report
      (not fail) the ones that don't transform.
- [x] Handle: DESC columns, reverse key (→ plain B-tree + note), bitmap (→ plain B-tree),
      domain/text indexes (report as unsupported).
- [x] Create indexes **after** data transfer in the orchestration order (also benefits item 2).
- [ ] Verification job comparing index coverage per table, surfaced in the UI.
      *Not built: the creation result already reports every index with its outcome and reason,
      which is what the verification job was for. Revisit only if a real run shows the two
      diverging.*

**Acceptance:** For a real schema, ≥90% of non-constraint indexes exist in PostgreSQL
after migration; the rest are explicitly listed with reasons.
**Not yet measured** — needs a run against the real schema.

**Effort:** M

**Implementation (2026-08-08):** see "Index Migration" below.

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

## Parallel Data Transfer (implemented 2026-08-08)

Tables are transferred N at a time instead of strictly sequentially. Parallelism is **across
tables only** — the per-table producer/consumer pipe, LOB staging workflow and per-table
transaction in `CsvDataTransferService` are used unchanged, so the tested and correct part of
the pipeline was not touched.

**Modules:**
- `transfer/service/ParallelTableTransferService` — worker pool and result aggregation
- `transfer/service/TransferOrdering` — largest-first scheduling (pure, no dependencies)
- `core/job/model/transfer/TableTransferOutcome` — one outcome per table
- `transfer/job/DataTransferJob` — now orders, configures and delegates

**Worker loop, not one task per table.** Each worker opens *one* Oracle + one PostgreSQL
connection and pulls tables off a shared queue until it is empty. This caps connections at the
worker count (a task-per-table pool would open a pair per table), and it self-balances: a worker
stuck on a large table simply takes fewer tables.

**Thread confinement instead of locking.** Workers only publish `TableTransferOutcome`s to a
queue; *all* aggregation into `DataTransferResult` and *all* progress reporting happen on the
calling thread. Neither the result object nor the progress callback had to become thread-safe,
so no existing class needed synchronization added.

**Largest first.** `TransferOrdering.largestFirst()` uses the row counts already in state.
Tables with unknown row counts sort last, ties break on qualified name — deterministic ordering
across runs, per the project's result-ordering principle.

**No table can silently vanish.** Every table produces exactly one outcome:
- a table that fails → error outcome, transaction rolled back, worker continues
- a worker that cannot open its connections → exits; its tables are taken by the other workers
- a worker killed by an `Error` (e.g. OOM on a LOB table) → publishes a failure for the table
  that was in flight before it dies
- if *all* workers die, the coordinator's wait is bounded and the remaining tables are reported
  as `No transfer worker available` rather than the job hanging

**Why concurrent writes are safe here:** each worker's statements (TRUNCATE, COPY, LOB staging
DDL) target only its own table, and data transfer runs *before* constraint creation in the
migration order, so there are no cross-table foreign keys to violate or deadlock on.

**Configuration:** `transfer.parallel-workers` (default 4, clamped to [1, 32] and to the table
count), settable in the UI under "Data Transfer Settings".

**Tests:** 19 new tests. `ParallelTableTransferServiceTest` drives the real worker loop through a
package-private `WorkerContext` seam with a fake, covering: every table transferred exactly once,
actual concurrency (latch-based — the test times out if the transfer is sequential), failure
isolation, per-table rollback, progress reported once per table on a single thread, one
connection pair per worker, and the all-workers-dead path. Verified by mutation: forcing the
worker count to 1 fails 4 of these tests.

---

## Index Migration (implemented 2026-08-08)

Oracle indexes that are not backed by a primary key or unique constraint are extracted and
recreated. Constraint-backed indexes are excluded — PostgreSQL creates those itself during
constraint creation.

**Modules:**
- `index/service/OracleIndexExtractor`, `index/job/OracleIndexExtractionJob` — extraction
- `index/service/IndexCreationPlanner` — decisions and name allocation (single-threaded)
- `index/service/ParallelIndexCreationService` — worker pool execution
- `index/service/IndexExpressionTransformer` — function-based index expressions
- `index/job/PostgresIndexCreationJob`, `index/rest/IndexResource`
- `core/job/model/index/` — `IndexMetadata`, `IndexKeyPart`, `IndexSignature`,
  `PostgresIndexCatalog`, `IndexOutcome`, `IndexCreationResult`
- `database/service/PostgresIndexCatalogService` — reads the existing PostgreSQL indexes

**Design decisions taken before implementation:**

1. **Separate step from FK index creation, and ordered after it.** The two answer different
   questions — index migration asks "what did Oracle have?", FK gap-fill asks "what should
   exist?" — from different sources. Neither database indexes FK columns automatically (the FK
   job's javadoc claiming Oracle does was wrong; both index only PK/UK constraints), so those
   indexes are the migration's own invention. Real schemas usually index FK columns by hand, so
   gap-fill now runs *after* migration and skips anything already covered. Running it first would
   duplicate every hand-made FK index under a generated `idx_fk_*` name.

2. **Signature-based matching everywhere, never name-based.** Oracle's `PK_EMP` is PostgreSQL's
   `emp_pkey`; a name check would miss every constraint index and duplicate it. `IndexSignature`
   compares table, uniqueness and ordered keys under **two distinct rules**: exact match for
   reproducing an Oracle index (fidelity — a wider index is not the same index), leading-prefix
   coverage for FK gap-fill (performance — a wider index does serve the lookup). Direction is
   compatible when all keys match or all are exactly inverted, which is exactly when a B-tree can
   substitute.

3. **Standalone `IndexMetadata` in state**, not attached to `TableMetadata` — that model is
   consumed by the data transfer path and its normalizer.

4. **Worker pool for creation**, following the parallel data transfer: one connection per worker
   pulling off a shared queue. Plain `CREATE INDEX`, not `CONCURRENTLY` (nothing else uses the
   database during a migration and the concurrent build is strictly slower). Auto-commit on, so
   each index is its own transaction.

5. **Plan then execute.** Name allocation is stateful — two indexes can want the same name — so
   all decisions happen single-threaded in the planner and workers receive finished SQL. No
   shared state in the workers, and the full report exists before the first statement runs.

6. **Report, don't pre-check, for `IMMUTABLE`.** PostgreSQL requires index expressions to be
   immutable and migrated Oracle functions usually are not. Rather than a volatility pre-check,
   such an index fails at `CREATE INDEX` and is reported with PostgreSQL's own message.

**Oracle traps handled:** descending indexes (stored as function-based indexes over hidden
`SYS_NC0000n$` columns — the real column is recovered from `ALL_IND_EXPRESSIONS`);
`COLUMN_EXPRESSION` being a `LONG` (selected and read last); PostgreSQL sharing one namespace
across relation kinds while Oracle gives indexes their own (names preserved but disambiguated on
collision); bitmap and reverse key downgraded to B-tree with a note; domain indexes reported as
unsupported; `UNUSABLE`, recycle-bin, IOT and cluster indexes skipped at extraction.

**No index can vanish:** every extracted index yields exactly one outcome — `CREATED`, `SKIPPED`,
`UNSUPPORTED` or `ERROR` — each with a reason. Includes the all-workers-dead path, where
remaining indexes are reported as failures rather than the job hanging.

**Configuration:** `index.parallel-workers` (default 4, clamped to [1, 32] and to the index
count), settable in the UI under "Index Creation Settings".

**Orchestration:** Steps 16–17 (extract, create), FK gap-fill moved to Step 18. Total step count
went from 30 to 32.

**Tests:** 65 new tests. `IndexSignatureTest` pins both matching rules; `IndexCreationPlannerTest`
pins the skip/unsupported/rename decisions and generated SQL; `ParallelIndexCreationServiceTest`
drives the real worker loop through a package-private seam, covering concurrency (latch-based —
times out if sequential), failure isolation, single-threaded progress, one connection per worker
and the all-workers-dead path; `OracleIndexExtractorTest` pins the descending-index fold-back.
Verified by mutation: forcing the worker count to 1 fails 4 tests, and removing the uniqueness
rule from `makesRedundant` fails the test that says a non-unique index cannot stand in for a
unique one.

**Not done:** the separate verification job. The creation result already reports every index with
its outcome and reason, which is what verification was for. Acceptance (≥90% of non-constraint
indexes present) is **not yet measured** — that needs a run against the real schema.

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
