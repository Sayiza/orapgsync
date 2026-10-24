# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project Overview

Oracle-to-PostgreSQL migration tool built with Quarkus (Java 18). CDI-based plugin architecture
for extensible database object migration with centralized in-memory state.

It is **in production use** on a real migration, where PL/SQL is being rewritten to Java
piecewise. That context drives every priority call below.

## Current Focus (from 2026-10-24)

**Plan: [ROADMAP.md](documentation/ROADMAP.md) — the single active plan. Read §1–§5 before
picking anything up.** Goal: make as many PL/SQL routines as possible *usable from Java* on
PostgreSQL, with per-routine evidence of how far each can be trusted. Tables, data and SQL views are
in maintenance mode.

What a fresh session would otherwise get wrong:

- **`implemented` is no longer the success measure.** Routines are ranked on the roadmap's
  **readiness ladder** (extracted → stubbed → implemented → resolves → smoke-tested → equivalent to
  Oracle → approved), using the *effective* rung over transitive callees. A transformer fix is worth
  what it moves up the ladder for routines the Java team asks for, not its raw failure count.
- **There is a local, read-only Oracle source database with test data** — `local/SOURCE_DB.md`,
  git-ignored. Read its §1 first. Nothing from it (names, code, values, identifying counts) enters
  the repo; reproduce failures under invented names. Its account **cannot execute** other schemas'
  code, so calling functions for Oracle-side equivalence needs a decision (roadmap D3).
- **The baseline is the 2026-10-24 run against that database** (ROADMAP §10; named detail in
  `local/runs/2026-10-24/`). The 2026-08/09 runs (`completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md`
  §11–§16) were evidently against the same database and are broadly comparable.
- **A faster run is not a better run** — compare counts per rung, never wall-clock. And **check
  `logs/reports/` (or `local/runs/`) before concluding a measurement needs a new run.**

**Deferred — do not invest here without an explicit decision:** mod_plsql web gateway (`web/`,
step 32; [deferred/](documentation/deferred/MOD_PLSQL_IMPLEMENTATION_PLAN.md)) · state persistence /
resume · AI-assisted transformation (until the roadmap's verification harness exists) · job
cancellation.

## Build and Development Commands

- **Build**: `mvn clean compile`
- **Dev mode**: `mvn quarkus:dev`
- **Test**: `mvn test` (~2,005 tests across 164 test classes)
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
- `JobReportArchive` — writes every finished job's full report to `logs/reports/`

New job types need **no** core changes; they are discovered automatically.

**A run's evidence must outlive the run — `JobReportArchive`.** Per-object failures and the exact
SQL PostgreSQL rejected otherwise live only in `StateService`, i.e. memory. Four properties are
load-bearing: it hooks into **`JobService.submitJob`**, the one terminal point every job passes
through, so job types added later are covered without anyone remembering; **failed** jobs are
archived too, since their partial result holds the errors up to the point they gave up; the report
is **streamed** via `JobTextReportFormatter.write`, because a step-28 report is hundreds of
megabytes; and archiving **never** changes a job's outcome. Why it was needed:
[§13.4](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#134-the-measurement-instrument-is-broken).

### Domain Modules

Each database element type is an independent module depending only on `core/`, `database/`,
`config/`. No circular dependencies between domain modules.

| Module | Scope | Status |
|---|---|---|
| `schema/` | discovery + creation | ✅ |
| `synonym/` | Oracle synonym resolution (current schema → PUBLIC) | ✅ |
| `objectdatatype/` | composite types, dependency-ordered (`TypeDependencyAnalyzer`) | ✅ object types · ❌ **Oracle collection types are silently broken — see "Collection Types" below** |
| `sequence/` | full Oracle property extraction + creation | ✅ |
| `table/` | columns, type mapping, NOT NULL | ✅ |
| `transfer/` | CSV bulk transfer via PostgreSQL COPY, parallel | ✅ |
| `constraint/` | dependency-ordered PK → UK → FK → CHECK (`ConstraintDependencyAnalyzer`) | ✅ |
| `index/` | non-constraint Oracle indexes, signature-matched, parallel | ✅ **98.2%** (2026-10-24 run) |
| `view/` | stubs + SQL transformation | ✅ stubs · 🔴 **63%** (2026-10-24 run). Mostly *stub* defects, not view transformation: 30% of failures are calls omitting defaulted args (stubs carry no `DEFAULT`), 21% two call-site bugs. [ROADMAP §10](documentation/ROADMAP.md#10-baseline--2026-10-24-full-run-local-source-database-dba_-mode) |
| `function/` | stubs + PL/SQL transformation, standalone **and** package members | 🟡 stubs **83%** · 🔴 impl **27%**, 59% blocked by failed package contexts (2026-10-24 run). Dominated by the boundary scanner and signature extraction, not PL/SQL. [ROADMAP §10](documentation/ROADMAP.md#10-baseline--2026-10-24-full-run-local-source-database-dba_-mode) |
| `typemethod/` | stubs + PL/SQL transformation | 🟡 **74%** (2026-10-24 run); most failures are correct-by-design rejections (OVERRIDING, mutating members). Phases 0–5 done, Phase 6 deprioritised. |
| `trigger/` | extraction + transformation, idempotent drop-and-recreate | 🟡 **70%** (2026-10-24 run); untaxonomised |
| `oraclecompat/` | PostgreSQL equivalents for Oracle built-ins | ✅ |
| `web/` | mod_plsql gateway project generator | 🔒 deferred |

### Transformer (`transformer/`)

**Package:** `me.christianrobert.orapgsync.transformer` (note: *transformer*, not *transformation*).

```
Oracle SQL/PL-SQL → AntlrParser → PostgresCodeBuilder → PostgreSQL SQL/PL-pgSQL
```

- `parser/AntlrParser` — ANTLR wrapper; also hosts the boundary scanners and stub generators
- `builder/PostgresCodeBuilder` — main visitor; one static helper class per ANTLR rule
  (`VisitXxx`, ~80 in `builder/` plus sub-packages for connectby, cte, functions,
  generalelement, objectfield, outerjoin, rownum, tablereference, typemethod)
- `builder/RoutineBodyBuilder` — **not** a rule visitor: the `DECLARE`/`BEGIN…END` body that every
  routine form shares, since they all spell it as `seq_of_declare_specs?` + `body` and only the
  signature differs. Build a new routine form **on this helper**; the copies it replaced had each
  grown the same scope bug independently.
- `context/TransformationContext` + `TransformationIndices` — metadata indices for O(1) lookups,
  passed as a **parameter**, never CDI-injected into the visitor layer
- `service/TransformationService` — high-level CDI API
- `type/` — two-pass type inference
- `inline/`, `packagevariable/` — inline type definitions, package variable getter/setters
- `util/IdentifierHelper` — the only sanctioned way to read an identifier out of the parse tree
- `util/TypeResolver` — the only sanctioned way to emit a **type**; see "Object Type Names" below

**Design principles:** direct transformation (visitors return PostgreSQL strings, no intermediate
semantic tree); metadata-driven disambiguation (e.g. `emp.address.get_street()` type method vs.
`emp_pkg.get_salary()` package function); visitor layer stays pure so it is unit-testable with
mocked metadata.

**Never call `getText()` on an identifier context.** Oracle has two identifier forms and the parser
returns both verbatim: `RUN_ID` and `"RUN_ID"` name the same column but are not the same string, and
using the raw text fails twice over — case-sensitively against PostgreSQL *and* against the
lower-case-keyed indices, so synonym resolution, CTE detection and type inference silently take the
wrong branch. Route every identifier through `util/IdentifierHelper`, picking by purpose:
`canonical()` for metadata lookup keys and comparisons, `emit()` for anything landing in output SQL,
`unquote()` for name fragments and jsonb keys. `emit()` deliberately leaves unquoted identifiers
byte-identical — that is what keeps bare `USER` and `LEVEL` working as PostgreSQL keywords. **Column
aliases are excluded on purpose** (an alias defines a name rather than referencing one; it is the
view's contract). Details and known gaps:
[TRANSFORMATION.md](documentation/TRANSFORMATION.md#quoted-identifiers-oracle-delimited_id--2026-08-14).

### Cross-Cutting (`core/`)

`core/tools/` — `TypeConverter` (Oracle→PostgreSQL types), `OracleTypeClassifier` (complex system
types), `PostgresIdentifierNormalizer` (Oracle name → PostgreSQL name; **the** authority, used by
every DDL job and by the transformer's `IdentifierHelper`), `PostgreSqlIdentifierUtils`,
`UserExcluder`, `CodeCleaner`.
`core/service/` — `StateService` (incl. `resolveSynonym()`).

### Database & Config

`database/` — `OracleConnectionService`, `PostgresConnectionService`, `PostgresIndexCatalogService`.
**Oracle dictionary queries are written against `all_*` views but run against `dba_*` when the
account can read every one** — `OracleConnectionService.getConnection()` hands out a proxy from
`OracleDataDictionary` that rewrites them. `ALL_*` hides every PL/SQL unit not granted `EXECUTE`
to the user, so a read-only account would otherwise extract a fraction of the code with no error.
The choice is all-or-nothing, never per view. A query on a dictionary view not in
`OracleDataDictionary.VIEWS` fails a test. Details:
[CONSOLIDATION_PLAN.md](documentation/completed/CONSOLIDATION_PLAN.md#oracle-data-dictionary-dba_-with-all_-fallback-implemented-2026-10-23).
`config/` — `ConfigService` + REST; connections are configured at **runtime** via UI/REST, not
in `application.properties`.

### Frontend (`src/main/resources/META-INF/resources/`)

Vanilla JavaScript, no frameworks. One `{feature}-service.js` per module, `orchestration-service.js`
drives the full run. Each feature row follows the same shape: ⟳ `refresh-btn` per side reads that
database's current state into a count badge + schema-grouped list, `action-btn` performs creation,
plus an expandable results panel.

**`results-panel.js` owns all list and panel rendering** — never build result rows directly in a
feature service. It exists because a full migration run puts ~100k nodes into this single page. Three
rules, and they are the reason it stays responsive:

- **Deferred** — `setResultsPanel(panelId, {summaryHtml, renderDetail})` renders only the counts;
  rows are built on expand and `innerHTML = ''`d on collapse. Hiding is not enough: a hidden
  subtree still costs memory and style recalculation. Lists use `setDeferredList` +
  `renderSchemaGroups`, which defers a second time at the schema-group level.
- **Capped** — `renderCappedList` renders at most `RESULTS_ROW_CAP` (200) rows per section, then
  links to the full plain-text report. Every panel summary links to that report **unconditionally**
  too — pass `jobId` to `setResultsPanel`. As a cap-overflow note only, the link is invisible on any
  schema whose sections all fit under 200 rows, which makes the report look unimplemented.
- **Code on demand** — `renderDeferredCode` keeps SQL/DDL in a JS `Map` and builds the `<pre>`
  only on click.

Panel and list ids are conventional: `{panel}-results`/`{panel}-details`, `{x}-list`/`{x}-items`.
Toggle state lives in the helper registries, not in `element.style.display`. `escapeHtml` is
defined once, here — every interpolated value goes through it, because Oracle SQL contains `<`
and `&`. Details: [CONSOLIDATION_PLAN.md](documentation/completed/CONSOLIDATION_PLAN.md#frontend-rendering-implemented-2026-08-12).

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
| 22–23 | Extract functions / create function stubs | 22 also indexes **package spec types**, which every later `pkg.type` resolution reads — skipping it silently costs stubs, not just functions |
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
   don't fit the job pattern: `/api/oracle-compat/*`, `/api/indexes/*`,
   `/api/transformation/sql`, `/api/web-gateway/*`.

Swagger UI at `/q/swagger-ui`.

**`GET /api/jobs/{jobId}/report`** — the complete job result as `text/plain`, for opening in a browser
tab; the HTML panels render only a capped preview. `JobTextReportFormatter` walks the result's Jackson
tree instead of switching on its type, so it covers every job type including ones added later. **It
falls back to the archived copy in `logs/reports/` when the job is no longer in memory**, and that
same file can be grepped without the app running.

**`GET /api/jobs/{jobId}/result` does not return raw metadata for extraction jobs.** Each
`generate{Feature}Summary` carries a projection of just the fields the UI renders (`summary.views`,
`summary.functions`, `summary.triggers`, `summary.typeMethods`, `summary.sequences`,
`summary.constraints`, `summary.tables`, `summary.indexes`) — the raw models hold view SQL, trigger
bodies and parameter lists the UI never reads, 14 MB against 252 KB for a realistic schema. **When
adding a field the frontend needs, add it to the projection; do not re-add
`response.put("result", result)`.** Full data lives at `/report`; a single view's Oracle and
PostgreSQL source at `GET /api/views/postgres/source/{schema}/{viewName}`. `OBJECT_DATATYPE` and
`SYNONYM` still send raw models — already minimal, and the object type detail view needs the
variable list.

**A result type with no branch in `JobResource`'s if-chain reports nothing at all.** It falls through
to the generic handler, which returns the object under `result` with no counts and no summary; the
frontend then reads `implementedCount || 0` and returns early on the missing summary, so the step
renders as a **0 badge and an empty panel** and reads as "does nothing" — even with its summary
generator complete but called from nowhere. **Adding a job means adding both the projection and its
branch**; a summary generator with no caller is the failure mode.

**A summary is built with `Map.of`, which throws on a null value, so a newly-nullable field in a
result aborts the whole run at the point the frontend asks for it.** The job completes, then
`GET /api/jobs/{id}/result` 500s and the orchestration stops — so the failure looks like the *job*
failing and the archived report, which handles nulls fine, shows nothing wrong. Giving a job a new
failure mode that records an error with **no SQL** did exactly this. **A value that can be null goes
in a `HashMap` with the key omitted**, never `Map.of`; `results-panel.js` reads
`item.sql || item.statement` and expects omission. Job-level tests cannot see this — the projection
is a separate path — so a new nullable field needs a test against the *summary generator*.

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

**There is a fifth category and it has no mapping — see below.**

### Collection Types — the one known silent-corruption path

**Oracle collection types (`CREATE TYPE t IS TABLE OF …` / `VARRAY`) are currently migrated to an
empty PostgreSQL composite type, and nothing reports it.** Extraction cannot tell `TYPECODE =
'COLLECTION'` from `'OBJECT'` and reads `ALL_TYPE_ATTRS` only, so a collection is ingested with zero
attributes and emitted as `CREATE TYPE s.x AS ()` — which **PostgreSQL accepts**. The empty shell
then satisfies `isObjectType()`, so declarations of it *compile*. Until this is fixed, **treat any
collection-typed object as unmigrated regardless of what the run reports**, and do not read a falling
`type "X" does not exist` count as evidence of correctness.

**Two disjoint collection models exist and only one is real.** *Inline* collections (declared inside
a package or routine) map to `jsonb` and have full machinery — element read/write, `COUNT`/`FIRST`/
`LAST`/`EXISTS`/`DELETE` in `CollectionMethodTransformer`, package variable getters/setters.
*Schema-level* collections have none of it: `VariableDefinition.isCollection()` keys on `inlineType`,
which is null for them, so every branch misses. `EXTEND` and `TRIM` are unimplemented for **both**.

**The fix starts diagnostic, not representational** — extract `TYPECODE` and refuse `AS ()` with a
named error first, then choose a representation against real numbers. Full chain, evidence and the
argument against picking a representation early:
[§13.3](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#133-collection-types--one-root-cause-and-it-is-not-in-the-transformer).

### LOB→OID Staging Workflow

PostgreSQL `oid` columns hold Large Object references (integers), so hex-encoded LOB data cannot
be COPYed into them directly. `CsvDataTransferService` therefore, per table and inside the table's
transaction: adds `{column}_staging bytea` columns → COPYs into staging →
`UPDATE t SET c = lo_from_bytea(0, c_staging)` → drops the staging columns → commits.

Self-contained, repeatable, and transactional — on failure the staging columns survive for
debugging. Methods: `detectOidColumns`, `addStagingColumns`, `convertStagingToLargeObjects`,
`dropStagingColumns`. Plan: [LOB_TO_OID_MIGRATION_PLAN.md](documentation/completed/LOB_TO_OID_MIGRATION_PLAN.md).

### Identifier Normalization — one authority, one collision guard

Every Oracle name becomes a PostgreSQL name through `PostgresIdentifierNormalizer` and nothing
else: lower-cased, quoted only when PostgreSQL requires it. The DDL jobs and the transformer must
agree, so both call this class rather than re-deriving the rule — if its curated reserved-word
list is wrong, both sides are wrong identically and still match, which is the property that
matters. In the transformer, reach it via `transformer/util/IdentifierHelper`.

Lower-casing means Oracle's case-sensitive `"Foo"` and `"FOO"` collapse to one name.
`findCollisions()` detects that, and table and view-stub creation **refuse the object** with an
error naming both Oracle columns rather than let a column be silently merged or dropped.

### Object Type Names — one authority, `TypeResolver`

Every Oracle type **anyone** emits goes through `transformer/util/TypeResolver` and nothing else:
`%TYPE` anchor → package type → migrated object type → built-in. It is to types what
`IdentifierHelper` is to identifiers, and exists for the same reason. **This includes the DDL
jobs** — a stub signature and the implementation that replaces it are one signature, so both sides
must get the same answer, and the only way to guarantee that is one resolver. `PostgresFunctionStubCreationJob`
builds `TransformationIndices` and a per-schema `TransformationContext` purely to reach it.

`TypeConverter` maps built-ins, has no metadata, and returns anything it does not recognise
**unchanged** — so `HR.ADDRESS_TYPE`, migrated as the composite type `hr.address_type`, passes
through it as bare `address_type` and resolves only if `search_path` happens to cover the schema.
**A new type-emission site calls `TypeResolver`, not `TypeConverter`.** Calling `TypeConverter`
directly was the largest single cause of failure on the 2026-08-27 run, and the four sites still
doing it in DDL jobs and package helpers cost 17,339 missing stubs and 29,705 blocked functions on
2026-08-30. Use `toPostgreOrNull` / `toPostgresOrNull` to tell "mapped" from "passed through";
comparing the result against the input does not, since several built-ins map to themselves.

**A caller that can refuse must refuse.** `toPostgres` has to answer something — the visitor layer
is mid-expression — so it falls back to the bare name, and `resolvePackageQualifiedType` falls back
to `jsonb` for a package it has no context for. Neither guess belongs in a *signature*: the other
side of the `CREATE OR REPLACE` may guess differently, and the disagreement then surfaces as
`cannot change return type`, a silent overwrite, instead of as a missing type. Signature-building
callers use `toPostgresOrNull` and record a named error.

Only **positive** metadata qualifies: `qualifyTypeName` returns the bare name when it cannot place a
type, and emitting that is indistinguishable from the bug. Inline types (`RECORD`, `TABLE OF`) are
decided by the caller first — they become `jsonb` plus an initializer, a declaration-site side effect,
not type resolution.

Three things the dictionary and the anchor syntax get wrong if taken literally:

- **`%TYPE` keeps its anchor and gains the table's schema** — `semester_def.nr%TYPE` →
  `academic.semester_def.nr%TYPE`. PostgreSQL accepts `%TYPE` in a signature and merely could not find
  the relation; flattening it to the column's current type would discard the intent. **`%ROWTYPE`
  is not accepted there** and is deliberately left unresolved rather than approximated.
- **`PUBLIC` is not a schema.** It is Oracle's public-synonym namespace, reported in the dictionary's
  owner column exactly where a real schema goes. Taken literally it becomes PostgreSQL's `public`.
- **`ALL_ARGUMENTS` needs `TYPE_SUBNAME`.** Without it a package-declared type composes to
  `TYPE_OWNER.TYPE_NAME` = `schema.package`, naming a package as though it were a type.

**Package-declared types come from an index built at step 22**, not from whatever `PackageContext`
a job happens to hold — `OracleFunctionExtractor` parses every package spec once and stores the
subtypes and structured-type names in `StateService`, `MetadataIndexBuilder` puts them in
`TransformationIndices`, and every job therefore answers `pkg.type` identically. Resolving it from
the loaded contexts instead made the answer depend on processing order
([§16.1](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#161-what-was-wrong)). The index carries
**no field lists** — a signature needs only `jsonb`, and `core/` must not gain a dependency on
`transformer/` — so a declaration site naming *another* package's RECORD still gets `jsonb` with no
initializer.

Remainders still out of scope: a bare **cross-schema** reference emits unqualified (the search is
synonyms → current schema → `PUBLIC` → `SYS`), and Oracle **collection** types have no composite
type in the target at all.
Details: [TRANSFORMATION.md](documentation/TRANSFORMATION.md#object-type-names-in-emitted-types--one-authority-typeresolver--2026-08-27)
· [§15.5](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#155-implemented-2026-08-30)
· [§16](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#16-package-spec-type-index--implemented-2026-09-01).

### Transformer — Rules That Generalize

These recur in every function mapping; the per-construct write-ups assume them.

- **PostgreSQL resolves overloads by exact type**, with no implicit `numeric` → `integer`/`text`
  coercion, so an uncast column argument fails resolution. Emit `::integer` / `::text` casts.
- **Oracle `DATE` is a timestamp** — a day *plus a time to the second*, and every migrated `DATE`
  column is a PostgreSQL `timestamp`. Emitting `::date` anywhere compiles, runs, and silently moves
  every value to midnight.
- **`AT TIME ZONE` takes its direction from the operand's static type** and runs *backwards* on a
  `timestamptz` — which is what `SYSDATE` becomes. Cast to `::timestamp` to pin it.
- **An argument that must be interpreted at transformation time is rejected when it is not a
  literal** (regexp `match_parameter`, `FROM_TZ` zone). Passing it through changes semantics silently.

### Transformer — Per-Construct Decisions

Each is fully written up in [TRANSFORMATION.md](documentation/TRANSFORMATION.md); read that section
before touching the construct. Listed here so a fresh session knows the decision exists.

- **[Routines referenced without parentheses](documentation/TRANSFORMATION.md#routines-referenced-without-parentheses--2026-08-14)**
  — `pkg.get_status` is byte-identical to `table.column` in expression position, so only metadata can
  decide; `ParameterlessFunctionDetector` does. **Only positive metadata rewrites, and columns win.**
  The inverse rule — *"the qualifier is not a known alias, therefore it is a package"* — is wrong and
  must never be added: a table written without an alias registers no alias at all.
- **[REGEXP family](documentation/TRANSFORMATION.md#the-regexp-family-and-oracle-regex-flags--2026-08-14)**
  — maps natively; assumes PostgreSQL ≥ 15, unguarded. Flags are *mapped*, never passed through: the
  engines' defaults for `.` vs newline differ, so a newline flag is always emitted.
- **[LISTAGG / WM_CONCAT](documentation/TRANSFORMATION.md#string-aggregation-listagg--wm_concat--2026-08-15)**
  → `string_agg`. The analytic form must emit an explicit `ROWS BETWEEN UNBOUNDED PRECEDING AND
  UNBOUNDED FOLLOWING` — PostgreSQL's default frame silently turns it into a *running* concatenation.
  `DISTINCT` drops the `::text` cast, because matching it in the order key would reorder the result.
- **[`FROM_TZ`](documentation/TRANSFORMATION.md#from_tz--at-time-zone--2026-08-16)** → `AT TIME ZONE`.
  A numeric offset is emitted as `INTERVAL`, never as text: PostgreSQL reads a text offset POSIX-style
  with the **sign inverted**, landing hours away with no error.
- **[`CAST(expr AS type)`](documentation/TRANSFORMATION.md#castexpr-as-type--2026-08-16)** — targets
  are an **allowlist; anything not on it throws** rather than being approximated. Deliberately *not*
  `TypeConverter`, which discards the very detail a cast exists to apply.
- **[Single-argument `TO_CHAR(expr)`](documentation/TRANSFORMATION.md#single-argument-to_charexpr--2026-08-16)**
  — PostgreSQL has no one-argument `to_char`. Solved with `oracle_compat.to_char` overloads installed
  at step 26, so PostgreSQL resolves at planning time; not with a transformation-time type decision,
  and not with `::text`, which renders differently from Oracle.
- **[The `table_element` blind spot](documentation/TRANSFORMATION.md#the-table_element-blind-spot--2026-08-16)**
  — it is the alternative the grammar names **first** for the argument of `TO_CHAR`, `TO_DATE` and
  `COALESCE`, so those arguments never reach `VisitGeneralElement`. **Anything resolved in
  `VisitGeneralElement` must be resolved in `VisitTableElement` too.**
- **[Parenthesized element references](documentation/TRANSFORMATION.md#parenthesized-element-references--2026-08-17)**
  — `'(' general_element ')'` is its own alternative, tried *before* `atom`'s `'(' expressions_ ')'`,
  so a parenthesized **name** (`(a)`, `(t.a)`) never reaches the expression path — though a
  parenthesized *expression* (`(a + 1)`) does. Parentheses must survive into the output: `(a).b` is
  composite field access, `a.b` a qualified column, and flattening either way silently resolves
  against the wrong relation.
- **[Object type methods](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md)** — four decisions, each
  failing at *call* time rather than CREATE time when got wrong. Entry rule is `type_body_elements`
  (`AntlrParser.parseTypeMethod`), **not** `function_body`, with **total** dispatch over its four
  alternatives — implemented or a named throw, never relying on a parse failure (§3.1–3.2); the owning
  type name must be supplied by the caller. At **call sites** the receiver becomes the **first
  argument**, never a prefix — `a.m(x)` → `schema.t__m(a, x)` (§4). **SELF is an ordinary composite
  variable**, so `self.x := 1` must not reach the inline-RECORD `jsonb_set` branch, and a bare
  attribute resolves to `self.<attr>` only on positive metadata, after columns and locals (§4).
  **`attr(i) := x` is rejected, not qualified** (§12.2).
- **[The row quantifier](documentation/TRANSFORMATION.md#the-row-quantifier-select-distinct--unique--all--2026-08-17)**
  — `SELECT (DISTINCT | UNIQUE | ALL)?` must be read off the query block; dropping it is valid
  PostgreSQL returning a different row set, with no error anywhere. `UNIQUE` is Oracle's synonym for
  `DISTINCT` and cannot be passed through — PostgreSQL reads it as a column name. Under `CONNECT BY`
  it belongs on the **final** SELECT of the recursive CTE, never the base or recursive case.
- **[`BITAND`](documentation/TRANSFORMATION.md#bitand--2026-09-15)** → `oracle_compat.bitand`.
  PostgreSQL's `&` has no `numeric` overload, and the conversion is not a cast: Oracle truncates its
  arguments *toward zero* where `::bigint` rounds. The compat function must stay `LANGUAGE sql` and
  `IMMUTABLE` — that is what lets PostgreSQL inline it, making the call cost exactly what the inline
  expression costs and keeping expression indexes on it legal. It returns `numeric`, not the faster
  `bigint`, because `bigint / bigint` would silently turn `BITAND(7,3)/2` into integer division.

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
- **Type method stubs:** `typename__methodname`, from `ALL_TYPE_METHODS` / `ALL_METHOD_RESULTS`
  (`TypeMethodStubSqlBuilder`). Member functions take `self t`, member **procedures** `INOUT self t`,
  constructors are named after their type and return it, statics take neither.

**The stub signature and the implementation signature are one signature, and only a test says so.**
PostgreSQL identifies a function by `(schema, name, IN/INOUT argument types)`, so `CREATE OR REPLACE`
replaces the stub *only* on exact agreement — otherwise it adds an overload beside it or fails. The
two sides are derived from different inputs (data dictionary vs. parse tree) and cannot share code,
so they drift silently. Any new stubbed element needs the equivalent of
`TypeMethodStubSignatureAgreementTest` — build both sides from the same Oracle object and compare —
plus an execution test asserting **exactly one function of that name exists** afterwards, which is
the only way to tell "replaced" from "added beside".

**Overloads that collapse after type mapping must be refused, loudly.** Oracle distinguishes
`VARCHAR2` from `CHAR` and `NUMBER(10)` from `NUMBER`; the mapping merges both pairs, so two Oracle
overloads can produce one PostgreSQL signature and the second `CREATE OR REPLACE` silently overwrites
the first — one routine ends up holding another's body. `TypeMethodStubSqlBuilder.findSignatureCollisions`
refuses the whole colliding set, the same call `PostgresIdentifierNormalizer.findCollisions()` makes
for columns. Do **not** re-add an "already exists" pre-check to avoid this: keying one on
`pg_proc.proname` is what silently skipped every overload after the first.

**Package private functions** are invisible in `ALL_PROCEDURES` (spec-only), so package bodies are
parsed with ANTLR to extract them; they are marked `isPackagePrivate = true`.

### Code Segmentation

Large Oracle packages (5000+ lines) caused OutOfMemoryErrors under a full ANTLR parse. Lightweight
boundary scanners (`FunctionBoundaryScanner`, `TypeMethodBoundaryScanner`) replace it during
extraction: ~800× less memory, ~42× faster, ~90% real-world coverage. Type bodies are the simpler
case (no variables). Private type methods are likewise invisible in `ALL_TYPE_METHODS`, which is
why the type body is scanned rather than queried.

**Both scanners run on the ANTLR lexer, and a third must too.** Boundary detection needs to know what
a keyword *is*: `END IF`, `END LOOP`, `END CASE` and the bare `END` of a CASE **expression** close no
`BEGIN`, and character matching can tell none of these apart — nor a keyword inside a comment or
string literal from a real one. **A truncated segment is invisible to a count check**, because the
orphaned tail carries no modifier keyword and segment counts still tally against the dictionary. So a
scanner test must assert the *extracted text*, and one test must feed every scanned segment through
the parser it feeds in production.
[Plan Phase 2b](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#phase-2b--extraction-hardening).

**The "~90% coverage" figure is from the design, not from a run, and truncation has a signature.**
`mismatched input '<EOF>'` in a function's parse error means the *source was cut short*, not that
the PL/SQL is unsupported — 1,950 of step 28's 2,236 parse failures on 2026-08-30. The same defect
also leaves statement tails (`else`, `then`, bare assignments) in the **reduced package body**,
which is why most package bodies that fail to parse do. One defect, both symptoms; grep the
archived report for `<EOF>` before attributing a parse failure to a grammar gap. **Mechanism,
reproduced 2026-10-24:** `FunctionBoundaryScanner` skips `END IF/LOOP/CASE` but does not count open
CASE *expressions*, so a function ends at the first `x := CASE … END;` — exactly what Phase 2b fixed
in `TypeMethodBoundaryScanner` and never ported (ROADMAP P1.1).

### Parallel Jobs — one pattern, two users

Data transfer and index creation share this; a third parallel job should follow it rather than
invent a second shape. Write-ups:
[transfer](documentation/completed/CONSOLIDATION_PLAN.md#parallel-data-transfer-implemented-2026-08-08) ·
[index](documentation/completed/CONSOLIDATION_PLAN.md#index-migration-implemented-2026-08-08).

- **Worker loop, not task-per-item** — each worker holds its own connection(s) and pulls from a
  shared queue, capping connections at the worker count and self-balancing.
- **Thread confinement instead of locking** — workers only publish outcomes; aggregation and
  progress run on the calling thread, so no existing class needed synchronization.
- **Nothing can vanish** — one outcome per item with a reason, including the worker-died and
  all-workers-died paths; the coordinator's wait is bounded, so the job cannot hang.

Parallelism is across items only — `CsvDataTransferService`'s per-table pipe, LOB staging and
transaction are untouched, and concurrent writes are safe because transfer runs *before* constraint
creation. Config: `transfer.parallel-workers` / `index.parallel-workers`, default 4, clamped to
[1, 32] and to the item count; transfer order is largest-first.

### Index Migration

Oracle indexes **not** backed by a PK/UK constraint are recreated; constraint-backed ones are
excluded because PostgreSQL creates them with the constraint. Two decisions carry the design — the
rest, including the Oracle extraction traps and the deliberate choice to let non-`IMMUTABLE`
expressions fail at `CREATE INDEX`, is in the
[write-up](documentation/completed/CONSOLIDATION_PLAN.md#index-migration-implemented-2026-08-08).

- **Signature-based matching, never name-based** — Oracle's `PK_EMP` is PostgreSQL's `emp_pkey`, so
  a name check would miss and duplicate every constraint index. Two rules: **exact match** for
  reproducing an Oracle index (fidelity), **leading-prefix coverage** for FK gap-fill (performance).
- **FK gap-fill is a separate, later step (18)** — a different question ("what *should* exist?" vs.
  "what *did* Oracle have?"). Most schemas index FK columns by hand, so running gap-fill first would
  duplicate every one under a generated `idx_fk_*` name.

### Oracle Compatibility Layer

PostgreSQL equivalents for Oracle built-in packages, installed into the `oracle_compat` schema with
flattened names (`oracle_compat.dbms_output__put_line`). Three support tiers: FULL / PARTIAL / STUB.
Implemented: DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB, plus HTP/OWA/OWA_UTIL for the deferred
gateway. The catalog is extensible — register in `oraclecompat/catalog/OracleBuiltinCatalog`, add
the SQL in `oraclecompat/implementations/`.

It also holds **standalone** entries (`packageName = "STANDALONE"`), which keep their own name
rather than being flattened: `oracle_compat.sqlcode` and the `oracle_compat.to_char` overloads.

### Unanchored Grammar Entry Rules — a live silent-failure trap

`select_statement` / `function_body` are **not anchored to EOF**. When the parser meets a construct
it cannot place, it ends the rule early, leaves the rest of the source unread and reports **no
error** — the transformation then "succeeds" on a fragment. Measured:
`SELECT ... FROM sales_view MODEL PARTITION BY ...` consumes 49 of 149 characters, reads `MODEL` as
a table alias, and produces a silently truncated view.

This is unfixed and unguarded — no detector exists. Making the transformer *reject* truncated
parses is still an open decision: it converts an unknown number of currently "successful" views
into loud failures.

## Development Guidelines

### Design Philosophy

**Prefer rigorous solutions over heuristics.** Invest in real infrastructure (scope tracking, type
analysis, metadata indices) rather than quick pattern-matching. Example: variable scope tracking
replaced heuristic variable detection and fixed critical function-call misidentification bugs.

**Fail loudly, never silently.** A dropped statement or ignored pragma that still reports success
is worse than an error — it is silent semantic corruption. Every unsupported construct must either
throw `TransformationException` or emit an explicit marker plus a warning in the result.

**A detected failure must never be built on — "best effort" on a parse error is not best effort.**
An ANTLR tree after error recovery is not a partial reading of the input; it contains invented
nodes, and no consumer can tell them from real ones. `buildPackageContext` logged a package-body
parse error and carried on, and the extractor then read a recovered `gitemtab order.tTable;` as a
variable *named* `.` and shipped `CREATE FUNCTION pkg__get_itemtab() RETURNS .` — DDL for a variable
Oracle does not have, generated from a failure already detected and discarded. If a parse fails,
refuse the thing it was parsing. Cost of refusing is measurable and was measured first:
[§15.3](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#153-the-package-errors-named-at-last).
**The rule is "never build on a failed parse", not "always throw".** Step 22's package spec type
indexing swallows a spec that will not parse *on purpose* — it is filling an index, and a missing
entry degrades to the caller refusing by name, whereas throwing would take the whole extraction
down for the wrapped packages. Ask what the result is about to be used for.

**"Skipped" must mean *nothing to do*, never *we failed*.** A job with a skip bucket will drift into
using it for failures, and a skip count reads as neutral in every summary line and UI badge. This is
the single most-repeated defect in the codebase — it hid step 29 implementing **nothing** while
reporting success, and the identical bug then sat in the function job for months because **fixing it
in one job does not fix it in the sibling**: jobs written from the same template diverge, and only
the measured one gets corrected. Reserve skip for the genuinely empty case (an abstract method with
no body), and give it a distinct exception type so the two cannot be confused.

**A ratio nobody measured against a real run is not a status, and this keeps recurring.**
`function/` carried "85–95% transformation" until the 2026-08-28 run put it at ~22%; `view/` carried
"~90%" and function stubs a bare "✅" until 2026-08-30 put them at 63% and 62%. Twice the unmeasured
figure was for the step *upstream* of the one being worked on, which is why it went unquestioned.
Status figures in the module table must name the run they came from, and **every step of the 32 has
a ratio** — an unmeasured one is not a passing one.

**Shared setup inside a per-item loop must fail against the shared thing, once, and be cached on
failure.** A per-item `catch` records whatever it caught against the *item*, so a failed setup step
is copied onto every item that needed it and the real culprit is never named — and a cache populated
only on success re-runs the failing setup once per item. Both held in step 28: one broken package
context became **44,693 opaque function errors, 79% of the job**, re-attempted ~16× per package. A
count of such errors ranks nothing, because it counts members per package rather than causes.
[§14](documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md#14-step-28-taxonomised--2026-08-30) ·
**counted-not-skipped still applies to the blocked items** — they are failures that merely share a
cause.

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

**Never copy a name from the real migration into the repo.** The source database is someone
else's intellectual property and this project is open source. Run reports and logs name real
schemas, tables, packages and types; a test or doc reproducing a failure from them must rename
every identifier to a neutral invented one (`lending.item_holds`, `main_app.propmap`) while keeping
what the test depends on — case shape, underscores vs. camelCase, and whether the name trips
`FunctionHeuristics`' date patterns.

**A successful `CREATE FUNCTION` proves nothing about the body.** PL/pgSQL resolves a function body
at *call* time, so a body referencing a function that does not exist, or a variable not in scope, is
created without complaint — the job reports it implemented and it fails in production on first call.
An execution test must therefore **call** what it created. Reviewing the generated SQL is not a
substitute: a wrong `jsonb_set()` against a composite survived two rounds of exactly that.

Concurrency tests drive the real worker loop through a package-private seam with a fake, and are
latch-based so they time out if execution is sequential. Verify them by mutation (forcing worker
count to 1 must fail them).

### Documentation Policy

**An implementation is not complete until documentation is updated, in the same session.**

- Write the full account in the relevant plan file in `documentation/` —
  [TRANSFORMATION.md](documentation/TRANSFORMATION.md) for SQL/PL-SQL transformation work, otherwise
  the plan being worked from. Mark completed phases ✅ and keep status tables in sync.
- Then add to **this** file only what a fresh session would get *wrong* without it, and link out.
- Move a plan to `documentation/completed/` when its work is finished and no longer informs
  current decisions.

**What CLAUDE.md carries, and what it must not.** This file is auto-loaded into every session, so its
cost is paid on every task; it had to be cut by ~40% twice because these rules were not applied.

- **State each fact in exactly one place.** If it is in a plan file, link — do not summarise. The
  `addSkippedFunction` defect was stated here three times; when it was fixed, one copy was updated
  and **two silently became false**, telling a fresh session the bug was still live and naming a line
  number that pointed at the fix.
- **No discovery narrative.** Keep the invariant and the consequence of breaking it; the evidence
  ("184 → 7", "449 methods on one run", how it was found, what it was before) belongs in the plan.
  If a sentence would still be true had the bug never happened, it stays; otherwise it goes.
- **No run statistics outside the module table**, and those cite the run date and
  link rather than reproducing the taxonomy. Run numbers are the fastest-perishing content here.
- **A new transformer construct gets its write-up in TRANSFORMATION.md and *one bullet* under
  "Transformer — Per-Construct Decisions"** — the shortest statement of what would be got wrong
  without knowing it, plus the link. Promote a rule to "Rules That Generalize" only once it has
  bitten in two or more constructs.

**`TODO.md` is the user's manual tracker — never update it automatically.**

## Documentation Map

Every plan document, listed once. Prefer linking here over copying content into this file.

**Current plan**
- [ROADMAP.md](documentation/ROADMAP.md) — the single active plan: goal, readiness ladder, Phase 0
  baseline, workstreams A–E, open decisions D1–D7, and where every old plan's open items went (§8)

**Living references**
- [TRANSFORMATION.md](documentation/TRANSFORMATION.md) — SQL/view transformation feature list and
  history; **the full rationale behind every per-construct decision indexed above**
- [TESTING.md](documentation/TESTING.md) — testing strategy

**`documentation/deferred/`** — designed but not built: `AI_TRANSFORMATION_IMPLEMENTATION_PLAN.md`,
`MOD_PLSQL_IMPLEMENTATION_PLAN.md` (web gateway), `JOB_CANCELLATION_IMPLEMENTATION_PLAN.md`.

**`documentation/completed/`** — finished plans and investigations, history only. Two are still
linked from this file for their write-ups: `CONSOLIDATION_PLAN.md` (frontend rendering, payloads,
parallel transfer, index migration, `DBA_*` dictionary) and `TYPE_METHOD_IMPLEMENTATION_PLAN.md`
(type-method design decisions; **§11–§16 are the 2026-08/09 full-run measurements and the
taxonomy method** the roadmap's Phase 0 reuses). Also: `STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md`
and `PLSQL_DML_STATEMENTS_IMPLEMENTATION_PLAN.md` (PL/SQL visitor history; RETURNING grammar notes),
`LOB_TO_OID_MIGRATION_PLAN.md` (staging workflow),
`PACKAGE_SEGMENTATION_*` / `TYPE_METHOD_SEGMENTATION_*` (boundary scanners),
`PACKAGE_VARIABLE_*`, `INLINE_TYPE_*`, `OBJECT_TYPE_FIELD_ACCESS_*`, `TRIGGER_*`,
`PLSQL_CURSOR_ATTRIBUTES_*`, `PLSQL_EXCEPTION_HANDLING_ANALYSIS.md`,
`VISIT_GENERAL_ELEMENT_REFACTORING_PLAN.md` (dot-navigation dispatcher; parenthesis-less routines).
