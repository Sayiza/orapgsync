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

**Current state (evidence):**
- Failed view transformations surface only as a "!" badge / error count
  (`orchestration-service.js`, `pollCountBadge`); the root cause (which SQL construct
  failed, which line) is only in the server log.
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
- [ ] **Pre-flight compatibility report**: one job that parses all extracted views (and
      optionally functions) and produces a categorized report of unsupported constructs
      *before* anything is created. This is also the data source for item 5.

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

**Known silent paths (from review, verify and extend during implementation):**
- `VisitSeq_of_statements` — silently skips statements that transform to null
- `VisitPragma_declaration` — silently ignores `AUTONOMOUS_TRANSACTION` (changes commit semantics!)
- `VisitGeneralElement` — returns null for unsupported cross-schema references
- Audit the full transformer for further `return null` / silent-skip patterns

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
- [ ] Rank failing constructs by *frequency in the actual codebase*; fix top-down.
- [ ] For each fixed construct: add both a string-comparison test and (where feasible) an
      execution test against PostgreSQL (Testcontainers pattern from
      `PostgresPlSqlCursorAttributesValidationTest`).
- [ ] Constructs that don't appear in real views are out of scope, whatever they are.

**Acceptance:** Every view in the reference project either transforms or has a documented,
categorized reason visible in the UI.

**Effort:** ongoing, sized per construct after the report exists.

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
