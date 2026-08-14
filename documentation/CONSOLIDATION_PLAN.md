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
- [x] **Aggregate-first UI** (done 2026-08-12): summary counts render immediately, object rows
      are built only on expand and discarded on collapse, every list section is capped, and the
      complete result is one click away as plain text. See "Frontend Rendering" below.
- [x] **Trimmed result payloads** (done 2026-08-12): extraction responses carry a projection of
      the fields the UI renders instead of the raw metadata. 14 MB → 252 KB for a realistic
      schema. See "Result Payloads" below.
- [ ] Server-side pagination/filtering of the result endpoints ("show only failures").
      No longer about size — the remaining reason would be filtering.
- [ ] Audit frontend polling: avoid re-fetching and re-rendering full lists on every poll tick.
- [x] **Consistency pass over the feature panels** (done 2026-08-12): all 22 result panels and
      12 extraction lists now go through the same two helpers, so badge semantics, toggle
      behaviour and detail rendering are identical by construction rather than by convention.
- ~~Pre-flight compatibility report~~ — **removed from main 2026-08-12**, parked on branch
  `parked/preflight-compatibility-report`. See "Pre-Flight Compatibility Report (parked)" below.
- ~~Extend the pre-flight report to functions/procedures~~ — dropped with the feature.

**Acceptance:** A failed object's root cause is reachable in ≤2 clicks; the UI stays
responsive with 5,000+ tables/views. **The responsiveness half is met** — a panel reporting
3,000 views now puts 8 elements in the document instead of 3,819. The error-detail half
(failing construct + Oracle source) is still open.

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
- [x] Verification job comparing index coverage per table, surfaced in the UI.
      `PostgresIndexExtractionJob` reads the indexes currently in PostgreSQL, excluding
      constraint-backed ones so the count is directly comparable with the Oracle side. Surfaced
      as the ⟳ button on the target cell, matching every other object type. A separate
      per-table coverage diff was *not* built — the creation result already reports every index
      with its outcome and reason.

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
  → This is the one finding of the pre-flight work worth keeping. The *detection*
  (`ParseCompleteness`) went with the parked feature, so if this is picked up, recover that
  class from `parked/preflight-compatibility-report` rather than rewriting it.
- `VisitSeq_of_statements:31` — silently `continue`s on statements that transform to null
- `VisitPragma_declaration:69` — silently ignores `AUTONOMOUS_TRANSACTION` (changes commit
  semantics!)
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

**Done so far — quoted identifiers (✅ 2026-08-14).** Found from a real failing view, and it was
this item's pattern exactly: the transformer never called `PostgresIdentifierNormalizer`, so
Oracle `DELIMITED_ID`s (`"RUN_ID"`) reached PostgreSQL case-sensitive *and* poisoned every
lower-case-keyed metadata lookup with quote characters. The emission half failed loudly; the
lookup half was silent — synonym resolution, CTE detection, alias resolution and type inference
took the wrong branch and still reported success. Fixed by routing all identifier reads through
`transformer/util/IdentifierHelper`; unquoted identifiers are emitted byte-identical, so the
change cannot regress a working view. Case-colliding column names (`"Foo"` / `"FOO"`) are now
refused at table and view-stub creation instead of silently merging.
Full write-up: [TRANSFORMATION.md](TRANSFORMATION.md#quoted-identifiers-oracle-delimited_id--2026-08-14).

### 5. View Transformation Gaps — driven by real-world cases

**Problem:** Some complicated views still fail. Known candidate gaps from the review:
PIVOT/UNPIVOT, MODEL clause, compound `(+)` outer-join expressions, `REGEXP_*` with
position/occurrence > 1, `ORDER SIBLINGS BY`, RETURNING clause, cursor expressions.

**Approach — explicitly demand-driven, not coverage-driven:**
- [ ] Take failures from the normal migration run. **The views are in a good state** (assessed
      2026-08-12); the few that still fail surface in the view implementation panel with their
      error and failing SQL, which is enough to act on at this volume. This replaced the
      pre-flight report as the demand signal — see the parking note below.
- [ ] Fix top-down by how often a construct actually blocks a run.
- [ ] For each fixed construct: add both a string-comparison test and (where feasible) an
      execution test against PostgreSQL (Testcontainers pattern from
      `PostgresPlSqlCursorAttributesValidationTest`).
- [ ] Constructs that don't appear in real views are out of scope, whatever they are.

**Acceptance:** Every view in the reference project either transforms or has a documented,
categorized reason visible in the UI.

**Effort:** ongoing, sized per construct.

---

## Frontend Rendering (implemented 2026-08-12)

After a full run the single-page UI became unusable: every one of ~22 result panels and ~12
extraction lists rendered every row of its result into the same document, hidden panels kept
their nodes, and each failed object carried a `<pre>` block of its SQL. One panel reporting
3,000 views alone contributed 3,819 elements.

**Modules:**
- `resources/results-panel.js` — the shared rendering infrastructure (new)
- `core/job/service/JobTextReportFormatter` — plain-text rendering of any job result (new)
- `JobResource.getJobReport` — `GET /api/jobs/{jobId}/report`, `text/plain`

**Three rules, implemented once instead of in 34 places:**

1. **Deferred detail.** `setResultsPanel(panelId, {summaryHtml, renderDetail})` renders the
   counts immediately and stores the detail closure. `toggleResultsPanel` invokes it on expand
   and sets `innerHTML = ''` on collapse. Hiding a subtree was not enough — a hidden subtree
   still occupies memory and is still walked by style recalculation. Extraction lists get the
   same treatment through `setDeferredList` / `toggleDeferredList`, with a second level:
   expanding a list builds one row per *schema*, and expanding a schema builds that schema's
   rows.
2. **Capped rows.** `renderCappedList` renders at most `RESULTS_ROW_CAP` (200) rows per section
   and appends "Showing 200 of 4,312 views" plus a link to the full report.
3. **Deferred code.** `renderDeferredCode` holds SQL and function DDL in a JS `Map` and builds
   the `<pre>` only on click. These were the most expensive nodes in the page.

**The plain-text report is the escape hatch that makes capping acceptable.** The browser
renders a megabyte of text instantly at sizes where the DOM cannot, and unlike the panel view
it supports the browser's own search, saves to a file, and can be diffed between two runs.
`JobTextReportFormatter` walks the Jackson tree of the result rather than switching on the
result type — `JobResource` already carries a 300-line type switch, and a second one would
silently produce empty reports for whichever job type someone forgot to add.

**Measured** (view implementation panel, 3,000 views + 200 failures): 3,819 → 8 elements on
arrival; 1,016 when expanded (capped, no `<pre>`); 0 again when collapsed.

**Two pre-existing bugs fixed in passing**, both found by the consolidation:
- `escapeHtml` was defined three times and applied inconsistently, so a view whose SQL
  contained `<` corrupted the panel it was rendered into. There is now one definition, it also
  escapes quotes (the old DOM-based one did not), and it is applied on every interpolated value.
- `toggleSchemaGroup` was defined in three service files with the same name; the last one
  loaded won for all three. All three are now gone, replaced by `toggleSchemaGroupRows`.

Toggle state lives in the helper registries, not in `element.style.display`. Inline style is
not the place to keep application state and reading it back breaks as soon as a stylesheet has
an opinion about the same property.

**Not changed:** `oracle-compat-service.js` and `web-gateway-service.js` still render directly.
Their output is a fixed catalog and a single generated project — bounded, so there is nothing
to gain and no reason to churn them.

---

## Result Payloads (implemented 2026-08-12)

With the DOM fixed, the wire was the next bottleneck: `/api/jobs/{jobId}/result` serialized the
full metadata list for every extraction. That meant `ViewMetadata.sqlDefinition` (the complete
Oracle view source) and every `ColumnMetadata`, plus `TriggerMetadata.triggerBody` and *both*
generated PostgreSQL DDL fields — three copies of code per trigger. **The frontend read none of
those**; the only column information it rendered was `columns.length`.

**Each extraction summary now carries a projection** of exactly the fields the list renders, and
`JobResource` no longer puts the raw list under `result`:

| Job type | Summary key | Fields |
|---|---|---|
| `VIEW` | `views` | schema, viewName, columnCount |
| `FUNCTION` | `functions` | schema, objectName, packageName, objectType |
| `TRIGGER` | `triggers` | schema, triggerName, tableName, triggerType, triggerLevel, status |
| `TYPE_METHOD` | `typeMethods` | schema, typeName, methodName, methodType, instantiable |
| `SEQUENCE` | `sequences` | schema, sequenceName |
| `CONSTRAINT` | `constraints` | schema, tableName, constraintName, constraintType |

`TABLE_METADATA` (`tables`), `ROW_COUNT` (`tables`) and `INDEX` (`indexes`) already worked this
way — this completes the pattern rather than introducing one. `OBJECT_DATATYPE` and `SYNONYM`
still send their raw models: both are already minimal, and the object type detail view renders
the variable list, so trimming them would cost a feature and save nothing.

**Measured**, 3,000 views (1.5 KB of SQL, 12 columns each) + 800 triggers:

| | before | after | |
|---|---|---|---|
| 3,000 views | 12,553 KB | 158 KB | 79× |
| 800 triggers | 1,682 KB | 93 KB | 18× |
| combined | 14,236 KB | 252 KB | **56×** |

**Nothing became unreachable.** The full result is at `GET /api/jobs/{jobId}/report`, and
`GET /api/views/postgres/source/{schema}/{viewName}` now returns `oracleSql` alongside
`postgresSql` — its javadoc had promised that since it was written but it only ever returned the
PostgreSQL side. The view detail panel shows both, so a transformation can be checked against its
source in place.

`ExtractionSummaryPayloadTest` asserts the omissions directly (serialize the summary, assert the
source text is absent) rather than just checking the fields that are present — otherwise the next
person to add a field to a summary has nothing telling them the omissions are deliberate.

---

## Pre-Flight Compatibility Report (implemented 2026-08-08, parked 2026-08-12)

> **Removed from `main` on 2026-08-12. The full implementation is preserved on branch
> `parked/preflight-compatibility-report`** (branched from `5505cd9`, the last commit that
> contains it). Nothing was deleted outright — recover with
> `git checkout parked/preflight-compatibility-report -- <path>`.
>
> **Why it was parked.** The report was built to answer "which view constructs should we fix
> first?" That question stopped being open: the views reached a good state, and the small number
> that still fail are identified perfectly well by the normal migration run, which reports each
> failure with its error and failing SQL in the view panel. A whole second analysis pipeline —
> its own job, REST resource, aggregator and UI panel — was left standing to answer a question
> the ordinary workflow already answers. On the frontend it was an extra always-visible panel;
> in the codebase, ~2,100 lines and a `StateService` field carried for no live consumer.
>
> **What this cost.** Item 5 lost its ranking data source, which is a real loss in principle and
> not in practice at the current failure volume — the ranking is now "what blocked the last run".
> Item 4 lost `ParseCompleteness`, the truncated-parse detector; that one is worth recovering
> from the branch if transformer hardening is picked up, because the underlying grammar problem
> it detects is still present and still silent.
>
> **Revisit if** the failure volume grows enough that per-run triage stops scaling, or the
> analysis is extended to functions/procedures, where there is no cheap equivalent signal.

The description below documents the parked implementation as it stood.

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
- `index/service/OracleIndexExtractor`, `index/job/OracleIndexExtractionJob` — Oracle extraction
- `index/job/PostgresIndexExtractionJob` — reads the current PostgreSQL indexes
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

**Acceptance** (≥90% of non-constraint indexes present) is **not yet measured** — that needs a run
against the real schema. The two ⟳ counts are now directly comparable, so measuring it is a
matter of running both sides and reading the badges.

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
