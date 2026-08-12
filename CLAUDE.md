# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project Overview

Oracle-to-PostgreSQL migration tool built with Quarkus (Java 18). CDI-based plugin architecture
for extensible database object migration with centralized in-memory state.

It is **in production use** on a real migration, where PL/SQL is being rewritten to Java
piecewise. That context drives every priority call below.

## Current Focus (August 2026)

**Consolidating the features actually used in the real migration** — DDL migration, view
transformation, data transfer. Full plan and rationale:
[CONSOLIDATION_PLAN.md](documentation/CONSOLIDATION_PLAN.md).

| Priority | Item | Status |
|---|---|---|
| 1 | Frontend performance — deferred/capped rendering, plain-text reports, trimmed payloads | ✅ done |
| 1 | Error transparency — per-object error detail (failing construct + Oracle source) | 🔄 active |
| 2 | Parallel data transfer | ✅ done |
| 3 | Index migration | ✅ done |
| 4 | Transformer hardening — eliminate silent statement drops, no new features | 🔄 next |
| 5 | View transformation gaps, ranked by the pre-flight report | 🔄 demand-driven |

**Deferred — do not invest here without an explicit decision:**
- 🔒 PL/SQL long tail (BULK COLLECT, collections, `%ROWTYPE`, dynamic SQL, autonomous
  transactions). Decide with pre-flight report data after Phase 1, not by intuition.
- 🔒 Mod-PL/SQL web gateway (`web/`, orchestration step 32). Partially built, paused.
- 🔒 State persistence / resume. Transfers run all-or-nothing in practice; not needed.

## Build and Development Commands

- **Build**: `mvn clean compile`
- **Dev mode**: `mvn quarkus:dev`
- **Test**: `mvn test` (~1,591 tests across 131 test classes)
- **Generate ANTLR parsers**: `mvn antlr4:antlr4` (from `src/main/antlr4/` → `target/generated-sources/antlr4/`)
- **Package**: `mvn clean package`

Quarkus 3.15.1 · Java 18 · ANTLR 4.13.2 · ojdbc11 23.5.0.24.07 · postgresql 42.7.1.

## Architecture

### State Management (`core/service/StateService`)

`StateService` is the single store for all migration metadata: Oracle/PostgreSQL schema lists,
table/object-type/sequence/index metadata, row counts, synonyms (dual-map for resolution), and
all creation/transfer results. Jobs update it directly via injection — no event bus, no
intermediate managers.

### Plugin-Based Job System (`core/job/`)

- `JobRegistry` — CDI auto-discovery of all jobs
- `JobService` — execution and lifecycle (async via `CompletableFuture`)
- `JobResource` — generic REST endpoints for any job type
- `DatabaseExtractionJob<T>` / `AbstractDatabaseExtractionJob<T>` — typed job contract

New job types need **no** core changes; they are discovered automatically.

### Domain Modules

Each database element type is an independent module depending only on `core/`, `database/`,
`config/`. No circular dependencies between domain modules.

| Module | Scope | Status |
|---|---|---|
| `schema/` | discovery + creation | ✅ |
| `synonym/` | Oracle synonym resolution (current schema → PUBLIC) | ✅ |
| `objectdatatype/` | composite types, dependency-ordered (`TypeDependencyAnalyzer`) | ✅ |
| `sequence/` | full Oracle property extraction + creation | ✅ |
| `table/` | columns, type mapping, NOT NULL | ✅ |
| `transfer/` | CSV bulk transfer via PostgreSQL COPY, parallel | ✅ |
| `constraint/` | dependency-ordered PK → UK → FK → CHECK (`ConstraintDependencyAnalyzer`) | ✅ |
| `index/` | non-constraint Oracle indexes, signature-matched, parallel | ✅ |
| `view/` | stubs + SQL transformation | ✅ stubs · ~90% transformation |
| `function/` | stubs + PL/SQL transformation, standalone **and** package members | ✅ stubs · 85–95% transformation |
| `typemethod/` | stubs + PL/SQL transformation | ✅ |
| `trigger/` | extraction + transformation, idempotent drop-and-recreate | ✅ |
| `oraclecompat/` | PostgreSQL equivalents for Oracle built-ins | ✅ |
| `preflight/` | pre-migration compatibility analysis (views) | ✅ |
| `web/` | mod_plsql gateway project generator | 🔒 deferred |

### Transformer (`transformer/`)

**Package:** `me.christianrobert.orapgsync.transformer` (note: *transformer*, not *transformation*).

```
Oracle SQL/PL-SQL → AntlrParser → PostgresCodeBuilder → PostgreSQL SQL/PL-pgSQL
```

- `parser/AntlrParser` — ANTLR wrapper; also hosts the boundary scanners and stub generators
- `builder/PostgresCodeBuilder` — main visitor; one static helper class per ANTLR rule
  (`VisitXxx`, ~80 in `builder/` plus sub-packages for connectby, cte, functions,
  generalelement, objectfield, outerjoin, rownum, tablereference)
- `context/TransformationContext` + `TransformationIndices` — metadata indices for O(1) lookups,
  passed as a **parameter**, never CDI-injected into the visitor layer
- `service/TransformationService` — high-level CDI API
- `type/` — two-pass type inference · `analysis/` — pre-flight construct detection
- `inline/`, `packagevariable/` — inline type definitions, package variable getter/setters

**Design principles:** direct transformation (visitors return PostgreSQL strings, no intermediate
semantic tree); metadata-driven disambiguation (e.g. `emp.address.get_street()` type method vs.
`emp_pkg.get_salary()` package function); visitor layer stays pure so it is unit-testable with
mocked metadata.

### Cross-Cutting (`core/`)

`core/tools/` — `TypeConverter` (Oracle→PostgreSQL types), `OracleTypeClassifier` (complex system
types), `PostgreSqlIdentifierUtils`, `UserExcluder`, `NameNormalizer`, `CodeCleaner`.
`core/service/` — `StateService` (incl. `resolveSynonym()`).

### Database & Config

`database/` — `OracleConnectionService`, `PostgresConnectionService`, `PostgresIndexCatalogService`.
`config/` — `ConfigService` + REST; connections are configured at **runtime** via UI/REST, not
in `application.properties`.

### Frontend (`src/main/resources/META-INF/resources/`)

Vanilla JavaScript, no frameworks. One `{feature}-service.js` per module, `orchestration-service.js`
drives the full run. Each feature row follows the same shape: ⟳ `refresh-btn` per side reads that
database's current state into a count badge + schema-grouped list, `action-btn` performs creation,
plus an expandable results panel.

**`results-panel.js` owns all list and panel rendering** — never build result rows directly in a
feature service. It exists because a full migration run put ~100k nodes into this single page and
made it unusable. Three rules, and they are the reason it stays responsive:

- **Deferred** — `setResultsPanel(panelId, {summaryHtml, renderDetail})` renders only the counts;
  rows are built on expand and `innerHTML = ''`d on collapse. Hiding is not enough: a hidden
  subtree still costs memory and style recalculation. Lists use `setDeferredList` +
  `renderSchemaGroups`, which defers a second time at the schema-group level.
- **Capped** — `renderCappedList` renders at most `RESULTS_ROW_CAP` (200) rows per section, then
  links to the full plain-text report.
- **Code on demand** — `renderDeferredCode` keeps SQL/DDL in a JS `Map` and builds the `<pre>`
  only on click.

Panel and list ids are conventional: `{panel}-results`/`{panel}-details`, `{x}-list`/`{x}-items`.
Toggle state lives in the helper registries, not in `element.style.display`. `escapeHtml` is
defined once, here — every interpolated value goes through it, because Oracle SQL contains `<`
and `&`. Details: [CONSOLIDATION_PLAN.md](documentation/CONSOLIDATION_PLAN.md#frontend-rendering-implemented-2026-08-12).

`oracle-compat-service.js` and `web-gateway-service.js` deliberately still render directly —
their output is bounded.

## Migration Workflow (32 steps)

Driven by `orchestration-service.js`. **Order matters** — the notes are the non-obvious constraints.

| # | Step | Note |
|---|---|---|
| 1–2 | Test Oracle / PostgreSQL connections | |
| 3–4 | Extract / create schemas | |
| 5 | Extract synonyms | needed for type resolution |
| 6–7 | Extract / create object types | dependency-ordered |
| 8–9 | Extract / create sequences | **before** tables — a column default can be a sequence |
| 10–11 | Extract / create tables | columns only, no constraints |
| 12–13 | Extract row counts / transfer data | row counts feed largest-first scheduling |
| 14–15 | Extract / create constraints | after data — no per-row validation cost |
| 16–17 | Extract / create indexes | after constraints, so constraint indexes already exist |
| 18 | FK index gap-fill | **after** index migration — see "Index Migration" |
| 19–20 | Extract views / create view stubs | |
| 21 | Create synonym replacement views | **must precede** view implementation — `CREATE VIEW` validates every referenced relation, and transformed views may reference unresolved synonym names |
| 22–23 | Extract functions / create function stubs | |
| 24–25 | Extract type methods / create type method stubs | |
| 26 | Install Oracle compatibility layer | before any code implementation |
| 27 | Implement views | `CREATE OR REPLACE VIEW` preserves dependencies |
| 28 | Implement standalone + package functions | |
| 29 | Implement type methods | |
| 30–31 | Extract / create triggers | |
| 32 | Generate web gateway project | 🔒 deferred feature |

Every step is individually skippable in the UI.

## Adding a New Database Element

1. **Model** — `core/job/model/{feature}/{Feature}Metadata`, pure data, no service dependencies.
2. **Jobs** — extend `AbstractDatabaseExtractionJob<T>`, `@Dependent` scope:

```java
@Dependent
public class OracleRowCountExtractionJob extends AbstractDatabaseExtractionJob<RowCountMetadata> {
    @Override public String getSourceDatabase() { return "ORACLE"; }
    @Override public String getExtractionType() { return "ROW_COUNT"; }
    @Override public Class<RowCountMetadata> getResultType() { return RowCountMetadata.class; }
    @Override protected void saveResultsToState(List<RowCountMetadata> r) { stateService.setOracleRowCountMetadata(r); }
    @Override protected List<RowCountMetadata> performExtraction(Consumer<JobProgress> cb) { /* ... */ }
}
```

3. **Done** — auto-discovered by `JobRegistry`; `POST /api/jobs/oracle/row-count/extract` works
   immediately, with progress tracking and error handling included.

**If the new element has a UI list**, add a `generate{Feature}Summary` projection carrying only
the fields that list renders (see "REST API" below) and render it through `results-panel.js` —
not by building rows in the feature service.

## Conventions

**Package structure:** `{feature}/` with `job/`, `rest/`, `service/` sub-packages.

**Class names:** `Oracle{Feature}ExtractionJob`, `Postgres{Feature}StubCreationJob`,
`Postgres{Feature}ImplementationJob`, `Postgres{Feature}StubVerificationJob`,
`{Feature}Metadata`, `{Feature}CreationResult`.

**ExtractionType constants** are phase-explicit: `"VIEW"`, `"VIEW_STUB_CREATION"`,
`"VIEW_STUB_VERIFICATION"`, `"VIEW_IMPLEMENTATION"`.

**StateService fields:** `{database}{Feature}Metadata` (e.g. `oracleViewMetadata`).

**Frontend:** dash-separated files and DOM ids (`view-service.js`, `oracle-views`), camelCase
functions (`extractOracleViews()`).

### REST API

Two tiers:

1. **Generic job API** — `/api/jobs/{database}/{feature}-{phase}/{action}`, dash-separated.
   For standard extraction/creation/verification. Examples: `POST /api/jobs/oracle/view/extract`,
   `POST /api/jobs/postgres/view-stub/create`, `POST /api/jobs/postgres/constraint/create`.
2. **Specialized resources** — `/api/{resource}/{database}/{operation}`, for operations that
   don't fit the job pattern: `/api/oracle-compat/*`, `/api/preflight/*`, `/api/indexes/*`,
   `/api/transformation/sql`, `/api/web-gateway/*`.

Swagger UI at `/q/swagger-ui`.

**`GET /api/jobs/{jobId}/report`** — the complete job result as `text/plain`, for opening in a
browser tab. The HTML panels render a capped preview; this is the uncapped list, in a format the
browser renders instantly and can search, save and diff between runs. `JobTextReportFormatter`
walks the result's Jackson tree instead of switching on its type, so it covers every job type,
including ones added later.

**`GET /api/jobs/{jobId}/result` does not return raw metadata for extraction jobs.** Each
`generate{Feature}Summary` carries a projection of the fields the UI renders (`summary.views`,
`summary.functions`, `summary.triggers`, `summary.typeMethods`, `summary.sequences`,
`summary.constraints`, and the pre-existing `summary.tables` / `summary.indexes`). The raw models
hold view SQL, trigger bodies, column and parameter lists that the UI never reads — 14 MB vs
252 KB for a realistic schema. **When adding a field the frontend needs, add it to the projection;
do not re-add `response.put("result", result)`.** Full data lives at `/report`; a single view's
Oracle and PostgreSQL source at `GET /api/views/postgres/source/{schema}/{viewName}`.
`OBJECT_DATATYPE` and `SYNONYM` still send raw models — already minimal, and the object type
detail view needs the variable list.

**`POST /api/transformation/sql`** — ad-hoc Oracle→PostgreSQL SQL transformation for development
testing and future dynamic SQL conversion. Body is `text/plain`, optional `?schema=HR` (defaults
to the first schema in state). Requires Oracle metadata to be extracted first.
Always returns HTTP 200 — check the `success` field; a failed transformation is a valid business
outcome, not an HTTP error.

```bash
curl -X POST "http://localhost:8080/api/transformation/sql?schema=HR" \
  -H "Content-Type: text/plain" --data "SELECT empno FROM emp WHERE dept_id = 10"
# → {"success":true,"oracleSql":"...","postgresSql":"...","errorMessage":null}
```

## Domain Rules

These are the decisions that are expensive to re-derive from the code.

### Type Mapping — four categories

1. **Built-in Oracle types** → direct mapping via `TypeConverter.toPostgre()`
   (`NUMBER`→`numeric`, `VARCHAR2`→`text`, `DATE`→`timestamp`).
2. **LOB types** → PostgreSQL `oid` (Large Object references), for Java `@Lob` /
   `ResultSet.getBlob()` compatibility: `BLOB`/`CLOB`/`NCLOB` → `oid`. The obsolete types keep
   their direct mapping: `LONG` → `varchar`, `LONG RAW` → `bytea`.
3. **User-defined object types** → PostgreSQL composite types (`HR.ADDRESS_TYPE` → `hr.address_type`),
   serialized to composite literal format during transfer.
4. **Complex Oracle system types** → `jsonb` with a metadata wrapper:
   `{"oracleType": "SYS.ANYDATA", "value": {...}}`. Covers `SYS.ANYDATA`, `SYS.XMLTYPE`,
   `SYS.AQ$_*`, `SYS.SDO_GEOMETRY`. Preserves type information for later code transformation.
   May appear under owner `SYS` **or** `PUBLIC` (public synonyms).

Key classes: `TypeConverter.toPostgre()`, `PostgresTableCreationJob.isComplexOracleSystemType()`,
`OracleComplexTypeSerializer`.

### LOB→OID Staging Workflow

PostgreSQL `oid` columns hold Large Object references (integers), so hex-encoded LOB data cannot
be COPYed into them directly. `CsvDataTransferService` therefore, per table and inside the table's
transaction: adds `{column}_staging bytea` columns → COPYs into staging →
`UPDATE t SET c = lo_from_bytea(0, c_staging)` → drops the staging columns → commits.

Self-contained, repeatable, and transactional — on failure the staging columns survive for
debugging. Methods: `detectOidColumns`, `addStagingColumns`, `convertStagingToLargeObjects`,
`dropStagingColumns`. Plan: [LOB_TO_OID_MIGRATION_PLAN.md](documentation/completed/LOB_TO_OID_MIGRATION_PLAN.md).

### Synonym Resolution

PostgreSQL has no synonyms. `StateService.resolveSynonym()`: current schema → PUBLIC → null.
Used by `PostgresObjectTypeCreationJob.normalizeObjectTypes()` and `TypeDependencyAnalyzer`.
Only relevant for object type **attributes** — table columns already store actual type names.

### Two-Phase Migration: Stubs → Implementation

Views, functions and type methods get structural placeholders first, so circular references
(function → view → function) resolve and structural migration stays separate from logic conversion.

- **View stubs:** `SELECT NULL::type AS col1, ... WHERE false`, columns from `ALL_TAB_COLUMNS`.
- **Function/procedure stubs:** signatures from `ALL_ARGUMENTS`. Package members are flattened to
  `packagename__functionname`. **Always created as PostgreSQL `FUNCTION`s, never `PROCEDURE`s**, so
  implementation can use `CREATE OR REPLACE`. RETURNS is derived: no OUT/INOUT → `void`;
  one OUT/INOUT → that type; several → `RECORD`.
- **Type method stubs:** `typename__methodname`, from `ALL_TYPE_METHODS` / `ALL_METHOD_RESULTS`,
  handling MEMBER vs STATIC.

**Package private functions** are invisible in `ALL_PROCEDURES` (spec-only), so package bodies are
parsed with ANTLR to extract them; they are marked `isPackagePrivate = true`.

### Code Segmentation

Large Oracle packages (5000+ lines) caused OutOfMemoryErrors under a full ANTLR parse. Lightweight
boundary scanners (`FunctionBoundaryScanner`, `TypeMethodBoundaryScanner`) replace it during
extraction: ~800× less memory, ~42× faster, ~90% real-world coverage. Type bodies are the simpler
case (no variables). Private type methods are likewise invisible in `ALL_TYPE_METHODS`, which is
why the type body is scanned rather than queried.

### Parallel Data Transfer

Tables are transferred N at a time; parallelism is **across tables only** — the per-table
producer/consumer pipe, LOB staging and transaction in `CsvDataTransferService` are unchanged.

- **Worker loop, not task-per-table** — each worker holds one Oracle + one PostgreSQL connection
  and pulls from a shared queue. Caps connections at the worker count and self-balances.
- **Thread confinement instead of locking** — workers only publish outcomes; all aggregation and
  progress reporting run on the calling thread, so no existing class needed synchronization.
- **Largest first** (`TransferOrdering`), unknown row counts last, ties broken on qualified name.
- **No table can vanish** — exactly one `TableTransferOutcome` per table, including the
  worker-died and all-workers-died paths (the coordinator's wait is bounded).
- **Concurrent writes are safe** because each worker touches only its own table and transfer runs
  *before* constraint creation — no cross-table FKs to violate or deadlock on.

Config: `transfer.parallel-workers` (default 4, clamped to [1, 32] and to the table count).

### Index Migration

Oracle indexes **not** backed by a PK/UK constraint are extracted and recreated; constraint-backed
ones are excluded because PostgreSQL creates them during constraint creation.

- **Signature-based matching, never name-based** — Oracle's `PK_EMP` is PostgreSQL's `emp_pkey`,
  so a name check would miss and duplicate every constraint index. `IndexSignature` applies two
  distinct rules: **exact match** for reproducing an Oracle index (fidelity), **leading-prefix
  coverage** for FK gap-fill (performance). Directions are compatible when all keys match or all
  are exactly inverted — precisely when a B-tree can substitute.
- **FK gap-fill is a separate, later step (18).** Different source (constraint metadata vs.
  `ALL_INDEXES`) and different question ("what *should* exist?" vs. "what *did* Oracle have?").
  Neither database indexes FK columns automatically, so these indexes are the migration's own
  invention; most schemas index them by hand, and running gap-fill first would duplicate every one
  under a generated `idx_fk_*` name.
- **Plan then execute** — name allocation is stateful, so `IndexCreationPlanner` makes all
  decisions single-threaded and workers receive finished SQL. The full report exists before the
  first statement runs.
- **Oracle traps handled:** descending indexes (stored as function-based indexes over hidden
  `SYS_NC0000n$` columns — the real column is recovered from `ALL_IND_EXPRESSIONS`);
  `COLUMN_EXPRESSION` is a `LONG`, so it is selected and read last (as `ALL_VIEWS.TEXT` is);
  PostgreSQL shares one namespace across relation kinds while Oracle gives indexes their own, so
  names are preserved but disambiguated on collision; bitmap/reverse-key → plain B-tree with a
  note; domain indexes → `UNSUPPORTED`; `UNUSABLE`, recycle-bin, IOT and cluster indexes skipped.
- **Not pre-checked:** PostgreSQL requires index expressions to be `IMMUTABLE`. Such an index is
  allowed to fail at `CREATE INDEX` and is reported with PostgreSQL's own message.
- **No index can vanish** — one outcome each (`CREATED` / `SKIPPED` / `UNSUPPORTED` / `ERROR`) with
  a reason.

Config: `index.parallel-workers` (default 4, clamped to [1, 32] and to the index count).

### Oracle Compatibility Layer

PostgreSQL equivalents for Oracle built-in packages, installed into the `oracle_compat` schema with
flattened names (`oracle_compat.dbms_output__put_line`). Three support tiers: FULL / PARTIAL / STUB.
Implemented: DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB, plus HTP/OWA/OWA_UTIL for the deferred
gateway. The catalog is extensible — register in `oraclecompat/catalog/OracleBuiltinCatalog`, add
the SQL in `oraclecompat/implementations/`.

### Pre-Flight Compatibility Report

Transforms every extracted Oracle view **in memory** — no connection, nothing created — and answers
"what will fail, why, and how often" before a run. Per view:
`OK` / `OK_WITH_WARNINGS` / `TRUNCATED_PARSE` / `PARSE_ERROR` / `TRANSFORM_ERROR` / `NO_SOURCE`.

- Constructs are ranked by the number of **failing** views they appear in — that is the number that
  decides what is worth implementing. This is the data source for the demand-driven view work.
- **Silent loss detection:** flags constructs dropped in views that transformed *without* an error.
- **`TRUNCATED_PARSE`:** grammar entry rules are not anchored to EOF, so the parser can end a
  statement early, leave the rest unread, and report no error — the transformation then "succeeds"
  on a fragment. `ParseCompleteness` makes this visible. The transformer still *accepts* truncated
  parses; making it reject them is an open decision (item 4) because it will convert an unknown
  number of currently "successful" views into loud failures.
- Support status is **derived by reflection** from `PostgresCodeBuilder`'s visit methods, so the
  catalog cannot go stale when a visitor is added.
- Not covered yet: functions/procedures (need package context).

REST: `POST /api/preflight/oracle/analyze`, `GET /api/preflight/report`,
`GET /api/preflight/report/findings` (filtered + paginated).

## Development Guidelines

### Design Philosophy

**Prefer rigorous solutions over heuristics.** Invest in real infrastructure (scope tracking, type
analysis, metadata indices) rather than quick pattern-matching. Example: variable scope tracking
replaced heuristic variable detection and fixed critical function-call misidentification bugs.

**Fail loudly, never silently.** A dropped statement or ignored pragma that still reports success
is worse than an error — it is silent semantic corruption. Every unsupported construct must either
throw `TransformationException` or emit an explicit marker plus a warning in the result.

**Deterministic ordering.** Always sort extraction results before returning, even when the SQL has
an `ORDER BY`, so output does not vary with the execution plan.

### Code Organization

- Domain modules depend only on `core/`, `database/`, `config/`
- Models are pure data — no service dependencies
- `@ApplicationScoped` for services, `@Dependent` for jobs
- Jobs write to `StateService` directly via injection

### Testing

JUnit 5 + Mockito. Test job logic, state management and extraction; mock connections and
`StateService` for unit tests. Execution tests against real PostgreSQL use Testcontainers — see
`PostgresPlSqlCursorAttributesValidationTest` for the pattern. For a transformation fix, add both
a string-comparison test and, where feasible, an execution test.
Details: [TESTING.md](documentation/TESTING.md).

Concurrency tests drive the real worker loop through a package-private seam with a fake, and are
latch-based so they time out if execution is sequential. Verify them by mutation (forcing worker
count to 1 must fail them).

### Documentation Policy

**An implementation is not complete until documentation is updated, in the same session.**

- Update this file with a summary: what was implemented (module, classes, key decisions), REST
  endpoints, orchestration step numbers, limitations.
- Update the relevant plan file in `documentation/` — [TRANSFORMATION.md](documentation/TRANSFORMATION.md)
  for SQL/PL-SQL transformation work, otherwise the plan being worked from.
- Mark completed phases ✅ in both places and keep the status tables in sync.
- **Keep this file lean.** Detailed narratives belong in the plan files; CLAUDE.md carries the
  decisions and invariants a fresh session needs, and links out once for the rest.
- Move a plan to `documentation/completed/` when its work is finished and no longer informs
  current decisions.

**`TODO.md` is the user's manual tracker — never update it automatically.**

## Documentation Map

Every plan document, listed once. Prefer linking here over copying content into this file.

**Current plan**
- [CONSOLIDATION_PLAN.md](documentation/CONSOLIDATION_PLAN.md) — the active plan; per-item status,
  acceptance criteria, and full implementation write-ups for parallel transfer, index migration and
  the pre-flight report

**Living references**
- [TRANSFORMATION.md](documentation/TRANSFORMATION.md) — SQL/view transformation feature list and history
- [STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md](documentation/STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md) — PL/SQL function transformation
- [TESTING.md](documentation/TESTING.md) — testing strategy

**Open work**
- [PLSQL_DML_STATEMENTS_IMPLEMENTATION_PLAN.md](documentation/PLSQL_DML_STATEMENTS_IMPLEMENTATION_PLAN.md) —
  Phase 1 (INSERT/UPDATE/DELETE) done; **Phase 2 RETURNING clause open**
- [VISIT_GENERAL_ELEMENT_REFACTORING_PLAN.md](documentation/VISIT_GENERAL_ELEMENT_REFACTORING_PLAN.md) —
  Milestone A (structural refactor) done; **Milestone B open**: parameterless functions are still
  misidentified

**Designed but not built / deferred**
- [AI_TRANSFORMATION_IMPLEMENTATION_PLAN.md](documentation/AI_TRANSFORMATION_IMPLEMENTATION_PLAN.md) —
  AI-assisted transformation queue; the fallback if the PL/SQL long tail turns out to be genuinely long
- [MOD_PLSQL_IMPLEMENTATION_PLAN.md](documentation/MOD_PLSQL_IMPLEMENTATION_PLAN.md) — web gateway
- [JOB_CANCELLATION_IMPLEMENTATION_PLAN.md](documentation/JOB_CANCELLATION_IMPLEMENTATION_PLAN.md) — planning only

**`documentation/completed/`** — finished plans and investigations, history only. Includes the
detail behind sections above: `LOB_TO_OID_MIGRATION_PLAN.md` (staging workflow),
`PACKAGE_SEGMENTATION_*` / `TYPE_METHOD_SEGMENTATION_*` (boundary scanners),
`PACKAGE_VARIABLE_*`, `INLINE_TYPE_*`, `OBJECT_TYPE_FIELD_ACCESS_*`, `TRIGGER_*`,
`PLSQL_CURSOR_ATTRIBUTES_*`, `PLSQL_EXCEPTION_HANDLING_ANALYSIS.md`.
