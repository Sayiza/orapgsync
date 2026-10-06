# Type Method Implementation Plan

**Status:** 🟡 **largely complete, and no longer the constraint** · started 2026-08-17 ·
restructured 2026-08-26 · full-run measurements §11 (08-27), §12 (08-27),
**§13 (08-28, the current one)** — 1335/1590 implemented, 255 errors of which **157 are
correct-by-design rejections**, leaving 98 real failures.
**Scope:** Oracle object type methods (member/static/constructor/map/order) → PostgreSQL functions.
**Driver:** type methods *were* the least developed transformer area, and orchestration step 29
implemented nothing while reporting success (§1). That is fixed.

> **Read §13 before picking up any remaining phase.** The third run puts this plan's 98 remaining
> failures next to **54,093** in orchestration step 28, and traces the largest shared cause —
> Oracle collection types — to a silent-corruption path in step 7 that is outside this plan's
> scope entirely. Phase 6 is no longer the highest-value work available; it may not even be the
> highest-value work *in this plan*.

**Coverage criterion: the grammar, not any one database.** Completeness is measured against the
alternatives of `type_body_elements` (§3), because that is a closed, finite, auditable set. It is
explicitly *not* measured against what a particular schema happens to contain — a construct absent
today can be written tomorrow, and the tool is meant to be reusable across Oracle databases.
The property that makes deferral safe is not breadth but **total dispatch**: every grammar
alternative is either implemented or throws a named error. See the rejection catalogue in §3.2.

---

## 1. Finding that motivates this plan

**Orchestration step 29 (implement type methods) currently implements nothing, and reports success
while doing so.**

`OracleTypeMethodExtractor.storeTypeMethodSources()` stores each method's source starting at its
modifier keyword (`MEMBER`, `STATIC`, `MAP`, `CONSTRUCTOR`). `PostgresTypeMethodImplementationJob`
feeds that source to `TransformationService.transformFunction()`, whose grammar entry rule is
`function_body` — which begins at the `FUNCTION` token. Every method therefore fails at parse.
The job routes a failed transformation to `addSkippedTypeMethod()`, not to `addError()`, so the run
finishes green with an implemented count of zero.

Verified 2026-08-17 by driving the real `AntlrParser`. The failure is on the *leading modifier
keyword*, so it is independent of the method names used:

| Input | `parseFunctionBody` result |
|---|---|
| `member function f return varchar2 is …` | `extraneous input 'member' expecting 'FUNCTION'` |
| `static function f return t deterministic is …` | `extraneous input 'static' expecting 'FUNCTION'` |
| `map member function f return number is …` | `missing 'FUNCTION' at 'map'` |
| `constructor function t(p varchar2) return self as result is …` | `extraneous input 'constructor'` + `no viable alternative at input 'result is'` |
| plain `FUNCTION f …`, identical body | parses clean |

The grammar already carries the correct rules and **none of them has a visitor or a parser entry
point** — nothing in `transformer/` references them:

- `type_body` / `type_body_elements` (PlSqlParser.g4:1085–1094)
- `map_order_func_declaration` (:1096)
- `subprog_decl_in_type` → `proc_decl_in_type` / `func_decl_in_type` (:1100–1115)
- `constructor_declaration` (:1117)
- `overriding_subprogram_spec` / `overriding_function_spec` (:1153–1161)

This is the "unanchored grammar entry rule" silent-failure family from CLAUDE.md, in its most
complete form: not a truncated parse, an empty one.

## 2. Confirmed dictionary semantics — verified 2026-08-26

Established by running the queries in §9 against a production Oracle database and reconciling the
counts three ways (method total, `SELF`-row total, per-type method total all agree exactly). These
are properties of the **Oracle data dictionary**, not of that schema, and supersede the guesses
`OracleTypeMethodExtractor` currently encodes.

**What the columns do not mean.** Both readings in the current extractor are wrong:

- `METHOD_TYPE` carries values in the `MAP` / `ORDER` / `PUBLIC` family. It **never** carries
  `FUNCTION` or `PROCEDURE`, so the existing comparison matches nothing, ever.
- `INSTANTIABLE` marks concrete-vs-abstract, **not** member-vs-static. Confirmed by observing
  methods that are `INSTANTIABLE = NO` *and* carry a `SELF` parameter — a combination impossible
  under the current interpretation. `TypeMethodMetadata.isMemberMethod()` is therefore wrong in
  both directions.

**The classification rule.** Four independent dictionary facts decide everything; neither
`METHOD_TYPE` nor `INSTANTIABLE` participates except for MAP/ORDER:

| Test | Conclusion |
|---|---|
| no `SELF` row in `ALL_METHOD_PARAMS` | **static** |
| `method_name = type_name` | **constructor** (Oracle requires the names to match) |
| no `ALL_METHOD_RESULTS` row | **procedure**, otherwise function |
| `SELF` row has `param_mode = IN OUT` | **mutating** — needs the §8 Phase 6 rewrite |
| `METHOD_TYPE IN ('MAP','ORDER')` | comparison method, see §6 |

**`SELF` is a real parameter row, and it is counted.** It appears at `param_no = 1`, and
`ALL_TYPE_METHODS.PARAMETERS` **includes** it — verified by grouping declared count against actual
row count, which matched on every group. Two consequences:

- `extractParametersForMethod` (filtering only `param_name IS NOT NULL`) ingests `SELF` as an
  ordinary parameter, while `PostgresTypeMethodStubCreationJob` *separately* prepends its own
  `self`. That is a duplicate parameter name in every member-method stub. Extraction must skip
  the `SELF` row and take its `param_mode` as the mutability signal instead.
- Constructors carry a `SELF` row too, with mode `IN OUT`. The grammar agrees independently —
  `constructor_declaration` spells `('(' (SELF IN OUT type_spec ',')? …)`. So `param_mode` alone
  cannot separate a constructor from a mutating member; the name test above is what does.

**Inherited methods are duplicated onto the subtype.** `ALL_TYPE_METHODS` carries a row on the
*subtype* for every method it inherits, and that row's `SELF` parameter keeps the **supertype** as
its type. The subtype's type body contains no source for them. Extraction must filter
`INHERITED = 'NO'`; otherwise stubs get the wrong `self` type *and* Phase 1's segment-count check
fires on a perfectly correct type body.

> **Negative observations must not be encoded.** The verification database contained no `ORDER`
> methods, no static procedures, and one inheritance edge. None of that may become an assumption
> in code — those are facts about one schema, and turning them into assumptions is exactly how a
> silent wrong answer reaches the next database. They inform *sequencing only*.

## 3. Coverage criterion — the grammar

### 3.1 Coverage matrix

`type_body_elements` has four alternatives. Each, plus each cross-cutting sub-dimension, is either
implemented or listed in §3.2. Nothing may fall through.

| Grammar production | Construct | PostgreSQL target |
|---|---|---|
| `map_order_func_declaration` | `MAP MEMBER FUNCTION` | ordinary function + §6 warning |
| `map_order_func_declaration` | `ORDER MEMBER FUNCTION` | ordinary function + §6 warning |
| `subprog_decl_in_type` | `MEMBER FUNCTION` | `t__m(self t, …) RETURNS r` |
| `subprog_decl_in_type` | `MEMBER PROCEDURE` | `t__m(INOUT self t, …)` |
| `subprog_decl_in_type` | `STATIC FUNCTION` | `t__m(…) RETURNS r` |
| `subprog_decl_in_type` | `STATIC PROCEDURE` | `t__m(…) RETURNS void` |
| `constructor_declaration` | with and without explicit `SELF IN OUT` | `t(…) RETURNS t`, §5 |
| `constructor_declaration` | body-less form (the `((IS\|AS) …)?` is optional) | stub only, no implementation |
| `overriding_subprogram_spec` **and** `subprog_decl_in_type` | `OVERRIDING MEMBER` — reachable by two routes, see §3.1.1 | §3.2 — inheritance |
| `modifier_clause` | `NOT? (INSTANTIABLE\|FINAL\|OVERRIDING\|PERSISTABLE)` | parsed, ignored except `OVERRIDING` |
| `type_elements_parameter` | `IN` / `OUT` / `IN OUT` | direct |
| `type_elements_parameter` | `OUT NOCOPY` / `IN OUT NOCOPY` | `NOCOPY` is a hint — drop it |
| `type_elements_parameter` | `default_value_part` | PostgreSQL `DEFAULT` |
| `type_elements_parameter` | `DETERMINISTIC` | function-level `IMMUTABLE` |
| `type_spec` | `datatype` | `TypeConverter` |
| `type_spec` | `type_name` | composite type |
| `type_spec` | `%TYPE` / `%ROWTYPE` | PL/pgSQL supports both |
| `type_spec` | `REF type_name` | §3.2 |

### 3.1.0 Fixtures

`src/test/resources/typemethod/` holds the neutral fixtures this matrix is checked against, both
self-contained and free of any customer material:

- **`demo_types.sql`** — the transformable rows. Two types, because `MAP` and `ORDER` are mutually
  exclusive on one Oracle type. `DEMO_ITEM_T` is deliberately *multi-attribute* so it exercises the
  §6 warning condition, which a single-attribute type would hide.
- **`demo_types_rejected.sql`** — one construct per §3.2 row, so each rejection has something to
  fire on.

`DemoTypeFixtureParseTest` parses both through `type_body` — the whole body, not individual
elements, so `(type_body_elements)+` iteration is exercised, which is where a construct that leaves
a token unconsumed corrupts the element after it.

Both fixtures are validated against the **grammar only**. Before using them to generate
`ALL_TYPE_METHODS` rows for extraction tests, create them in a scratch Oracle schema and confirm
they compile — object-type methods are more restrictive than the grammar. Two rows of the matrix
are deliberately absent for that reason and are noted at the foot of `demo_types.sql`.

### 3.1.1 Routing, settled by parse test 2026-08-26

Measured by driving the real generated parser over `type_body_elements`; pinned by
`TypeBodyElementsGrammarTest`. Three results change the design.

**`OVERRIDING MEMBER FUNCTION` reaches the visitor layer by two different routes.** `OVERRIDING` is
accepted by `modifier_clause` in alternative 2 *and* by `overriding_subprogram_spec` in
alternative 4, and which one wins depends on the return form and whether there is a body:

| Form | Alternative taken |
|---|---|
| `OVERRIDING MEMBER FUNCTION f RETURN varchar2 IS … END;` | `subprog_decl_in_type` |
| `OVERRIDING MEMBER FUNCTION f RETURN SELF AS RESULT …` | `overriding_subprogram_spec` |
| `OVERRIDING MEMBER FUNCTION f RETURN varchar2;` (body-less) | `overriding_subprogram_spec` |
| `OVERRIDING MEMBER FUNCTION f RETURN varchar2 PIPELINED …` | `overriding_subprogram_spec` |
| `OVERRIDING MEMBER PROCEDURE p IS … END;` | `subprog_decl_in_type` |

ANTLR takes the lowest-numbered matching alternative, so the ordinary form never reaches
alternative 4; the other three are accepted *only* by `overriding_function_spec`, which uniquely
allows `RETURN SELF AS RESULT`, `PIPELINED` and a body-less form. **The `OVERRIDING` rejection in
§3.2 must therefore be attached to both paths.** Guarding only `overriding_subprogram_spec` lets
the ordinary form through the non-overriding path and silently emits a function that ignores the
inheritance it was supposed to override — the same two-routes-one-construct shape as the
`table_element` blind spot in CLAUDE.md.

**A Java `call_spec` body parses clean.** `MEMBER FUNCTION f RETURN varchar2 IS LANGUAGE JAVA NAME
'…';` produces no parse error, so the §3.2 rejection cannot rely on a parse failure and must be an
explicit check on the body form. (It also leaves the trailing `;` unconsumed, because
`func_decl_in_type` places the `';'` inside the body alternative only — relevant when iterating
`(type_body_elements)+` across a whole type body.)

**`DETERMINISTIC` is correct; the other function options are a grammar gap.** `DETERMINISTIC`
parses on a member function and is correctly rejected on a member procedure, matching Oracle. But
`PARALLEL_ENABLE`, `RESULT_CACHE` and `PIPELINED` are legal Oracle on a member function and **fail
to parse** — `func_decl_in_type` accepts only `DETERMINISTIC`. The asymmetry is stark for
`PIPELINED`: it parses on an *overriding* member function via `overriding_function_spec` and not on
a plain one. This is a defect in the vendored grammar, not in Oracle. It is out of scope for this
plan, but it means such a method currently fails at parse rather than at a named rejection — which
is at least loud. The test pins the gap so that fixing the grammar is detected.

### 3.2 Rejection catalogue — must throw, with a named error

These are architectural limits, not effort limits. An approximation here would be the silent
semantic corruption CLAUDE.md forbids, so each gets a specific `TransformationException` naming the
construct and the reason.

| Construct | Why it cannot be transformed |
|---|---|
| `call_spec` (`java_spec` / `c_spec`) in any method body | The implementation is Java or C. There is no PL/SQL to translate. **Parses clean — needs an explicit body-form check, §3.1.1.** |
| `sqlj_object_type` (`EXTERNAL NAME … LANGUAGE JAVA`) | Same, at type level. |
| `REF type_name` in `type_spec` | Oracle object references have no PostgreSQL equivalent. |
| Type inheritance — `UNDER`, `OVERRIDING`, abstract supertypes | PostgreSQL composite types do not inherit. Any emulation is a heuristic. **`OVERRIDING` must be rejected on both grammar routes, §3.1.1.** |
| ODCI extensibility interfaces (`ODCIAggregate*`, first parameter `SCTX`) | Target is `CREATE AGGREGATE` with `sfunc`/`finalfunc` — a different object, not a function. |
| Mutating member **function** (`SELF IN OUT` *and* a return value) | PostgreSQL cannot express `INOUT` parameters plus an independent `RETURNS`; the return type is dictated by the `INOUT` parameters. See §8 Phase 6 for the one candidate workaround. |

**Abstract methods are a skip, not an error.** A method on a `NOT INSTANTIABLE` type genuinely has
no body. This is the one place `addSkippedTypeMethod()` is correct — skip must mean "nothing to
do", never "we failed".

## 4. The SELF mapping

**Decision: `self` becomes an ordinary PL/pgSQL variable of the composite type, registered in the
existing variable scope tracker.**

PL/pgSQL supports composite field read *and* field assignment on a typed variable, so once `self`
is a known composite variable the existing field-access machinery handles the bodies with no
special-casing at expression level.

This is what fixes the corruption observed when a member body is fed through as a plain function:

```
Oracle:      self.p_value := 'TRUE';
Produced:    self := jsonb_set( self , '{ p_value }' , to_jsonb( 'TRUE'::text ) );
```

`self` is unknown to the scope tracker, so field assignment falls into the inline-RECORD (jsonb)
branch. Reads pass through verbatim and would have been accidentally correct.

> **Correction — 2026-08-27, by execution test.** This section previously concluded: "The jsonb
> output is not a bug in the assignment visitor — it is a symptom of `self` being out of scope, and
> disappears once it is registered." **That is wrong.** Phase 2 registered `self`, and
> `PostgresTypeMethodStubReplacementValidationTest` shows the output is still
> `self := jsonb_set(self, '{qty}', …)`.
> `VisitAssignment_statement.tryTransformInlineTypeFieldAssignment` transforms **every** dotted
> left-hand side and never consults the scope tracker — its own comment says so: *"For Phase 1B,
> always attempt transformation for dotted LHS"*. Registration was necessary but not sufficient;
> Phase 3 must make that visitor ask.
>
> **And this one is not loud.** PL/pgSQL resolves function bodies at call time, so the
> `CREATE OR REPLACE` succeeds, the job reports the method implemented, and
> `function jsonb_set(hr.demo_item_t, unknown, jsonb) does not exist` appears the first time
> production calls it. An unknown share of the 509 "implemented" on the 2026-08-27 run are in this
> state — which is why the §11 taxonomy is a floor, not a total.
>
> The lesson generalises: **a claim about what PL/pgSQL does with a generated body cannot be settled
> by reading the transformer.** Only executing it settles it. This one survived two phases of review
> because it sounded right.
>
> **Fixed in Phase 3, 2026-08-27**, by making the assignment visitor ask the scope tracker.

| Oracle form | SELF mode | PostgreSQL |
|---|---|---|
| `MEMBER FUNCTION` | `IN` | `FUNCTION t__m(self t, …) RETURNS r` — first parameter |
| `MEMBER PROCEDURE` | `IN OUT` | `FUNCTION t__m(INOUT self t, …)` |
| `CONSTRUCTOR FUNCTION` | `IN OUT`, `RETURN;` returns SELF | `FUNCTION t(…) RETURNS t` — injected local |
| `STATIC` | absent | `FUNCTION t__m(…)` |
| `MAP` / `ORDER MEMBER FUNCTION` | `IN` | ordinary function, see §6 |

Constructors need exactly two body rewrites:

1. inject `DECLARE self t := ROW(NULL, …, NULL)::t;`
2. rewrite bare `RETURN;` → `RETURN self;`

> **Answered 2026-08-27 by execution test, and the reasoning that predicted it was wrong.** Field
> assignment against an *uninitialised* composite variable works fine, so the seed is not needed for
> that. It is load-bearing for something else: a constructor that takes a path assigning nothing must
> still return an all-NULL **instance**, as Oracle does, rather than a NULL composite. The seed stays,
> for that reason rather than the assumed one — and it makes the type's attribute count a real
> dependency of the prologue. See Phase 3.

## 5. Naming — decided 2026-08-17

**Constructors keep the Oracle type name:** `CONSTRUCTOR FUNCTION t(p varchar2)` →
`t(text) RETURNS t`. PostgreSQL overloads by argument type natively, so all overloads coexist.

The reason is failure-mode asymmetry, not aesthetics. A constructor call site we fail to *detect*
already transforms to `t(…)` through the generic function path — under this naming it keeps
working. Under a `__new` suffix the same undetected site produces a dangling reference: loud at
`CREATE VIEW`, but silent until runtime inside a PL/pgSQL body.

Consequence: the "PostgreSQL resolves overloads by exact type" rule applies with full force at
constructor call sites. `t(1)` is ambiguous across `numeric`/`text`/`boolean` overloads; explicit
casts are mandatory.

Member and static methods keep the established `type__method` convention, **schema-qualified**.
`VisitGeneralElement` currently emits `address_type__get_street(…)` unqualified while the stub job
creates `schema.address_type__get_street` — that only works if `search_path` covers the schema and
must be fixed.

**Overloads collide after type mapping — guard required.** Oracle distinguishes `VARCHAR2` from
`CHAR` and `NUMBER(10)` from `NUMBER`; our mapping collapses both pairs to `text` and `numeric`.
Two Oracle overloads then produce one PostgreSQL signature and `CREATE OR REPLACE` silently
overwrites the first. Phase 5 must compute all signatures for an overload set and **fail loudly on
a duplicate**, in the shape of the existing `PostgresIdentifierNormalizer.findCollisions()` guard.

## 6. MAP and ORDER methods

PostgreSQL compares composite values field-wise. Oracle with a `MAP` method compares by the map
value. For a single-attribute type the two coincide; for a multi-attribute type they diverge
**silently**.

**Decision:** emit map/order methods as ordinary functions. Do *not* attempt custom operator
classes. Emit an explicit warning in the job result when a type carries a `MAP` or `ORDER` method
*and* has more than one attribute, because that is precisely the case where `=` and `ORDER BY`
change meaning without an error.

`ORDER` is implemented on the same path as `MAP`, not deferred — see the negative-observation rule
in §2.

## 7. Per-type remodelling — rejected 2026-08-17

Considered and declined: hand-written overrides mapping a specific type to a native PostgreSQL
scalar. Object types are already migrated as composite types by `PostgresObjectTypeCreationJob`,
table columns are created against them, and `CsvDataTransferService` serialises them as composite
literals. Remodelling one type would ripple through table DDL and data transfer for a cosmetic
gain, and would be a per-database special case in a tool meant to generalise. Mapping stays
mechanical.

## 8. Phases

| # | Phase | Status |
|---|---|---|
| 0 | Verify dictionary semantics | ✅ done 2026-08-26, §2 |
| 1 | Extraction correctness — classification, `INHERITED` filter, overload keying | ✅ done 2026-08-26 |
| 2a | Extract the reusable body machinery out of `VisitFunctionBody` | ✅ done 2026-08-26 |
| 2 | Parser entry point + **total dispatch** over `type_body_elements` | ✅ done 2026-08-26 |
| 2b | Extraction hardening — lexer-based boundary scanner; result reporting wired up | ✅ done 2026-08-27 |
| 3 | SELF — composite field assignment, implicit attributes, constructor prologue and `RETURN;` | ✅ done 2026-08-27 |
| 4 | Call sites — instance / static / constructor / `NEW` / chained | ✅ done 2026-08-26 |
| 5 | Stub signature alignment, collision guard, execution tests | ✅ done 2026-08-27 |
| 6 | Mutating members — statement-level rewrite | ⬜ **deprioritised — see §13.5** |

Phase order was set by §11, which measures what actually fails rather than what looks incomplete:
**5 → 3 → the type gaps**, with Phase 6 last because its 56 rejections are correct today. Phase 5
also moved Phase 3's scope: see the correction in §4.

**Phases 0–5 are done and the ordering above is spent.** §13 supersedes it: four items outside this
plan now rank ahead of Phase 6, and the collection-type root cause behind this plan's largest
remaining bucket lives in orchestration step 7. Read §13.5 before starting Phase 6.

### Phase 1 — extraction correctness

**Done 2026-08-26.**

- ✅ `TypeMethodKind` + `TypeMethodClassifier` implement §2's rule table as pure, DB-free logic.
  Unexpected dictionary values **throw** (`TypeMethodClassificationException`) rather than
  defaulting — a default is exactly how the old reading survived: it matched nothing and quietly
  classified every method as a static procedure. Covered by `TypeMethodClassifierTest` (20 cases,
  including `ORDER` and static procedures, which the verification schema did *not* contain).
- ✅ `INHERITED = 'NO'` filter in the extraction query.
- ✅ `SELF` excluded from `extractParametersForMethod`; its `param_mode` is carried on the metadata
  as `selfParamMode`, and `mutatesSelf()` / `isMutatingMemberFunction()` derive from it.
- ✅ `TypeMethodMetadata.isMemberMethod()` / `isStaticMethod()` / `isFunction()` / `isProcedure()`
  now derive from the kind, so every existing caller became correct without changing call sites.
  `isMemberMethod()` deliberately excludes constructors: they carry SELF in Oracle but map to
  `t(…) RETURNS t` with SELF as an injected local, not a parameter.
- ✅ `getIdentityKey()` includes `method_no`.
- ✅ Status corrected in CLAUDE.md and [TRANSFORMATION.md](TRANSFORMATION.md).

**Two further silent failures found while doing this, both fixed by the above:**

- `extractReturnTypeForMethod` was called only when `METHOD_TYPE = 'FUNCTION'` — a value that never
  occurs — so **no method ever received a return type**. It is now driven by the result row.
- Because `isFunction()` was likewise always false, every stub was emitted as
  `CREATE OR REPLACE PROCEDURE` with no `RETURNS` clause, violating the "always create FUNCTIONs,
  never PROCEDUREs" invariant in CLAUDE.md. Functions now emit correctly; **converting the
  remaining procedures to `FUNCTION … RETURNS void` belongs to Phase 5** with the rest of the
  signature work.

**Source keying — done 2026-08-26, ordinal-within-name.**

- ✅ `TypeMethodSourceKey.of(methodName, overloadOrdinal)` is the single key function both sides
  call. A key format derived independently on two sides is a key format that eventually diverges.
- ✅ `TypeMethodSourcePairing.assignOverloadOrdinals` ranks dictionary rows sharing a name by
  `method_no` (numerically — `method_no` arrives as text, so a lexicographic sort would put 10
  before 2). `pair()` ranks body segments by source position and matches rank to rank.
- ✅ **Nothing is paired when the counts disagree**, and the *whole type* is rejected, not just the
  offending name: a scanner that mis-detected one boundary may have shifted its neighbours, so the
  names that happen to tally are not trustworthy either. The extractor logs the mismatch at ERROR
  and stores no sources, which surfaces downstream as an explicit "source not found" per method.
- ✅ Abstract methods (`INSTANTIABLE = 'NO'`) are excluded from the expected count — they are
  declared in the spec and genuinely have no body. This is the one place `INSTANTIABLE` carries the
  meaning it actually has.
- ✅ `StateService.getTypeMethodSource` now **requires** the ordinal rather than offering a
  bare-name overload; a bare-name lookup returned an arbitrary one of the overloads.
- Covered by `TypeMethodSourcePairingTest` and the rewritten `StateServiceTypeMethodStorageTest`;
  the refusal path is verified by mutation.

**Why ordinal-within-name over `method_no` directly.** `method_no` looked like a per-*type* sequence
following source order, which would have been usable as the segment key directly — but that was
observed by eye on a single type and never verified. Ordinal-within-name assumes only that
*overloads of one name* appear in `method_no` order, a much smaller claim, and it degrades safely:
if the assumption fails, the counts still match and the failure is confined to swapped overloads of
one name rather than a whole type shifted by one.

> **Residual risk, deliberately accepted.** The count check cannot detect two overloads of the
> *same name* being paired in the wrong order — the counts tally either way. Only a signature
> comparison between the scanned parameter list and the dictionary parameter list would catch it,
> which needs the parameter-level parse that Phase 2 builds. Revisit once `type_body_elements`
> visitors exist.

> **Correction to an earlier draft of this plan.** It said `getPostgresName()` "must not collide
> across overloads". That contradicts §5: constructors keep the Oracle type name and member methods
> keep `type__method` *precisely so* PostgreSQL's native overloading resolves them, so the **names
> are supposed to collide**. What must not collide is the full **signature** after type mapping —
> the Phase 5 guard. Identity keyed by `method_no` is about source lookup, not about naming.

### Phase 2a — extract the reusable body machinery

**Done 2026-08-26.** `transformer/builder/RoutineBodyBuilder` now holds the shared block;
`VisitFunctionBody` (140 lines, was 319) and `VisitProcedureBody` (151, was 332) call it. Full
suite green at 1,834 tests. What was found and decided along the way is below.

**Prerequisite for Phase 2, added 2026-08-26 after a code review of what is actually reusable.**
An earlier draft of Phase 2 said the new visitors would reuse "`VisitFunctionBody`'s body/parameter
machinery". Only the *body* half of that is true, and even it is not currently callable.

**The parameter half does not transfer at all.** `type_elements_parameter` (PlSqlParser.g4:1195) is
a different rule from `parameter` (:5481), so it is a different generated context class and
`VisitParameter.v(Parameter_context, …)` cannot accept it. The shapes differ too, so a cast would
not have helped: `parameter` admits a single `INOUT` token, `NOCOPY` inside a `*` list and an
*optional* `type_spec`; `type_elements_parameter` spells `IN OUT` / `OUT NOCOPY` / `IN OUT NOCOPY`
as explicit alternatives, requires `type_spec`, and carries parameter-level `DETERMINISTIC?` and
`default_value_part?`. Phase 2 needs its own `VisitTypeElementsParameter`.

**Nor does the name.** `function_body` names itself with `identifier`; `func_decl_in_type` uses
`function_name`, `proc_decl_in_type` uses `procedure_name`, and `constructor_declaration` uses a
`type_spec` as the name. Four routes, four extractions.

**The body half is genuinely shared** — `seq_of_declare_specs` and `body` are the same rules in
`func_decl_in_type` / `proc_decl_in_type` / `constructor_declaration` as in `function_body`. But
`VisitFunctionBody.v` is a single ~270-line static method keyed to `Function_bodyContext`, with the
reusable work inlined at lines 117–260: cursor-attribute tracker reset, loop-RECORD and exception
context push, variable scope push, parameter registration, cursor pre-scan, DECLARE section, body
visit, package-init injection, and the loop-RECORD plus cursor-tracking declaration injection.

**The duplication had already happened once.** `VisitProcedureBody` turned out to hold a
byte-identical copy of the block, plus byte-identical copies of `injectInitializationCall` and
`registerParameterInScope` — verified by diffing the two files with the naming normalised away.
The only genuine differences are the signature: how the routine names itself, whether OUT
parameters are counted, and the `RETURNS` clause. So this is not a speculative refactor for a
future caller; it removes an existing second copy and prevents a third and fourth. The
quoted-parameter scope bug below sat in *both* copies, which is precisely the failure mode.

- Extract that block into a helper parameterised by `(Seq_of_declare_specsContext, BodyContext,
  scopeName, parameter registrar)`, and have both `VisitFunctionBody` and `VisitProcedureBody`
  call it. Behaviour-preserving — the existing function and procedure tests are the regression net.
- **Do this before writing the type-method visitors, not after.** Four copies of a 140-line block
  is how the type-method path drifts away from the function path, which is the same
  two-routes-one-construct failure as the `table_element` blind spot in CLAUDE.md.

**Fix the identifier handling while the block is open — but only the half that is actually broken.**
The code Phase 2 inherits violates the `getText()`-on-an-identifier rule in CLAUDE.md in two
places, and they need opposite treatment. Establishing that was most of this phase's thinking:

- **Scope registration — broken, fixed here.** `registerParameterInScope` keyed parameters by
  `parameter_name().getText().toLowerCase()`. For a quoted `"Foo"` that registers the literal key
  `"foo"`, quotes attached. Every lookup canonicalises first — `ParameterlessFunctionDetector`
  does `canonical(...).toLowerCase()` → `foo` — so that key could never be hit and the parameter
  was invisible to the scope. Now `IdentifierHelper.canonical()`; byte-equivalent for unquoted
  names, hence no output change. The bug sat in both copies of the block.

  **The visible damage needs a competing name, which is why this hid.** An invisible parameter only
  produces wrong output when something else claims the name. Covered by
  `FunctionBodyTransformationTest.quotedParameterName_shadowsSameNamedStandaloneFunction`: with a
  standalone no-arg `hr.foo` in the metadata, the old code read `RETURN "Foo";` as a parameterless
  call and emitted `RETURN hr.foo();` — silently returning the function's value instead of the
  argument, no error anywhere.

  > **Method note.** The first version of this test asserted only that the reference came out as
  > `foo`, and it **passed against the unfixed code** — the quoted reference emits `foo` either way.
  > A test that cannot fail is worse than no test, because it reports coverage it does not have.
  > The mutation check per TESTING.md is what caught it. Verify a regression test by reverting the
  > fix, every time; an assertion that looks obviously related to the bug can still miss the path.
- **Name emission — narrower than it looks, deliberately left alone.** `VisitFunctionBody.java:59`
  and `VisitParameter.java:75` lower-case raw text. For ordinary *and* quoted names this
  coincidentally equals what `PostgresIdentifierNormalizer` produces. It diverges only on an
  unquoted Oracle name PostgreSQL needs quoted (`TIMESTAMP` → bare `timestamp`, where the stub job
  created `"timestamp"`). **`IdentifierHelper.emit()` is the wrong tool**: it returns unquoted
  identifiers untouched by design, so it would emit `MY_FUNC` where every test expects `my_func`
  and still not quote the reserved word. The right authority for a declaration is
  `PostgresIdentifierNormalizer`. That is a real change to emitted SQL, so it is its own decision,
  not a rider on a behaviour-preserving extraction.

### Phase 2 — parser entry point and total dispatch

**Done 2026-08-26.** Step 29 now transforms type methods instead of silently implementing nothing.
Suite green at 1,848 tests; 14 new in `TypeMethodTransformationTest`.

**What landed**

- `AntlrParser.parseTypeMethod()` over `type_body_elements` — the missing entry point.
- `transformer/builder/typemethod/`: `VisitTypeBodyElements` (total dispatch),
  `VisitSubprogDeclInType`, `VisitConstructorDeclaration`, `VisitMapOrderFuncDeclaration`,
  `VisitTypeElementsParameter`, `TypeMethodEmitter` (the shared signature/body assembly, over
  `RoutineBodyBuilder` from Phase 2a), `TypeMethodRejection` (the §3.2 catalogue in one place),
  `AbstractTypeMethodException`.
- `TransformationContext.getCurrentTypeName()` — the owning type cannot be read off the parse tree,
  because a stored segment holds the method and never its enclosing `TYPE BODY`. Supplied by the
  caller from `TypeMethodMetadata`.
- `TransformationService.transformTypeMethod()`; `PostgresTypeMethodImplementationJob` routes to it.
- **Failed transformation is now an error, not a skip.** Skip is reserved for
  `AbstractTypeMethodException`, which is a distinct type precisely so the two cannot be confused.
- Mutating member functions are rejected in the job, not the visitor: only the dictionary knows
  SELF's mode.

**Two bugs the fixtures caught, both worth recording**

- **`SELF IN OUT NOCOPY` does not match the grammar's dedicated SELF slot.**
  `constructor_declaration` spells it `(SELF IN OUT type_spec ',')?` — *without* `NOCOPY`. Oracle
  permits the `NOCOPY` form, which then falls through to `type_elements_parameter` (whose mode
  alternatives *do* include `IN OUT NOCOPY`) and arrives as an ordinary parameter literally named
  `SELF`, emitted as `INOUT SELF demo_item_t`. Both spellings must produce the same signature, so
  `SELF` is filtered from every parameter list in `parametersOf`. Only the multi-form fixture
  exposed this — the implicit-SELF constructor alone would have passed.
- **Object-type parameters were emitted unqualified.** `TypeConverter` maps built-ins and has no
  metadata, so `p_other demo_item_t` came out bare and would resolve only when `search_path`
  happened to cover the schema. Now routed through `TransformationContext.qualifyTypeName()`, which
  also handles synonyms.

**Deliberately pulled forward from Phase 3:** `self` is registered as a scoped composite variable
in `TypeMethodEmitter`. Phase 3 owns the constructor prologue and the `RETURN;` rewrite, but
*registration* could not wait — without it the scope tracker does not know `self`, field assignment
falls into the inline-RECORD branch, and every member body emits `jsonb_set()` against a composite.
Shipping Phase 2 without it would have meant knowingly emitting silent corruption.


- `AntlrParser.parseTypeMethod()` over `type_body_elements` — one rule covering all four
  alternatives, so the caller does not have to pre-classify the source it is about to parse.
  Follows the existing `parseTwoStage(plsql, PlSqlParser::rule, "label")` shape.
- `VisitSubprogDeclInType`, `VisitConstructorDeclaration`, `VisitMapOrderFuncDeclaration`, plus
  `VisitTypeElementsParameter`, over the Phase 2a helper.
- **Dispatch must be total.** Every §3.1 row implemented, every §3.2 row throwing its named error.
  A construct with no branch is a bug, not a gap — add a final `else throw`.
- Two rejections need care because a parse failure will not deliver them (§3.1.1): `OVERRIDING`
  arrives on *two* alternatives, and a Java `call_spec` body parses clean. Extend
  `TypeBodyElementsGrammarTest` as visitors land.
- `PostgresTypeMethodImplementationJob` routes to the new entry point.
- **A failed transformation must become an error, not a skip.** The current
  `addSkippedTypeMethod()` path is what hid the total failure; skip is reserved for the abstract-
  method case in §3.2.

### Phase 2b — extraction hardening

**Done 2026-08-27.** Two defects that made the first full run unreadable and its input unusable.
Neither was visible from the code alone; both came out of §11.

**The type method boundary scanner is now lexer-based.** `TypeMethodBoundaryScanner` was a
hand-written character state machine that decremented its `BEGIN`/`END` depth on **every** `END`.
`END IF`, `END LOOP` and `END CASE` close no `BEGIN`, so depth reached zero at the first one and the
method was recorded as ending there. The stored source was a fragment and transformation failed with
`mismatched input '<EOF>' expecting {'END','EXCEPTION'}` — ~325 of 351 transformation failures.

- `FunctionBoundaryScanner` had already solved this for package bodies, on the ANTLR lexer, and the
  type-method path simply never got the fix. Same two-routes-one-construct drift as the
  `table_element` blind spot. The rewrite follows it rather than patching the character scanner:
  case, comments, string literals and quoted identifiers stop being hand-rolled.
- **A CASE expression closes with a bare `END`** (`x := CASE … END;`) and no lookahead can tell it
  from a block `END`, so the scanner counts open `CASE`s. Skipping only `END IF`/`END LOOP`/`END CASE`
  would have left every method containing a CASE expression truncated — including `describe` in the
  fixture.
- **Leading modifiers are now part of the segment.** `OVERRIDING MEMBER FUNCTION` was recorded from
  `MEMBER`, so the stored source parsed as an ordinary member method and the §3.2 `OVERRIDING`
  rejection could never fire: the method was emitted as a plain function that silently discarded the
  inheritance it exists for. Expect a few methods that previously reported success to become named
  rejections.
- **A `call_spec` body is recorded rather than skipped.** It has no `BEGIN`/`END`, so the old scanner
  hunted for an `END` belonging to a later method and derailed the rest of the type — which then lost
  every source in it to the Phase 1 count refusal. Ending the segment at the call spec's semicolon
  turns a whole-type refusal into one named "implemented in Java/C" rejection.

> **Why nothing caught this.** The Phase 1 pairing check compares segment **counts**, and a truncated
> method's orphaned tail contains no modifier keyword, so no extra segment appeared and the counts
> tallied. And `TypeMethodBoundaryScannerTest.scan_orderMemberFunction` asserted the method's name,
> type and count on a body ending in `END IF; END;` — all three are correct on the truncated segment,
> so it **passed against the bug**. Every test now asserts the extracted text, and
> `DemoTypeFixtureParseTest.scannedSegmentsParseAsCompleteTypeMethods` feeds every scanned segment
> through `parseTypeMethod`: the join between the two halves, which neither half's tests covered.
> Verified by mutation — restoring the `END IF` handling reproduces the production error message
> verbatim.

**Step 29's result was invisible in the UI.** `JobResource` had no branch for
`TypeMethodImplementationResult`, so `/api/jobs/{id}/result` fell through to the generic handler with
no counts and no summary. The frontend reads `result.implementedCount || 0` and returns early on a
missing summary, so a run that implemented 509 methods and failed on 1081 showed a badge of **0** and
an empty panel — indistinguishable from "step 29 did nothing", which is how the run was first read.
`TypeMethodResource.generateTypeMethodImplementationSummary()` already existed, complete, and was
called from nowhere. The branch omits the raw result deliberately: the summary already projects every
field the panel renders, including the failing SQL per error.

### Phase 3 — SELF

**Done 2026-08-27.** Three constructs, ~150 of the 1081 measured failures plus an unknown number of
methods that were *reported implemented* and would have failed in production.

**1. Composite field assignment.** `self.qty := …` emitted
`self := jsonb_set(self, '{qty}', …)` against a composite.
`VisitAssignment_statement.tryTransformInlineTypeFieldAssignment` transformed **every** dotted
left-hand side without ever consulting the scope tracker — its own comment said so: *"For Phase 1B,
always attempt transformation for dotted LHS"*. It now falls through to a plain assignment when the
base variable is in scope as a **known object type and carries no inline type definition** (an
inline RECORD genuinely is jsonb). The fix is not `self`-specific: a local of any object type had
the same problem.

**2. Implicit SELF attribute references.** Oracle grants a member body direct access to its type's
attributes, so `qty := 0` and `RETURN qty * unit_price` are legal without a prefix — the same
courtesy a package member gets for package variables, and resolved the same way.
`generalelement/ImplicitSelfAttributeDetector` rewrites a bare name to `self.<attribute>` only on
positive metadata, in Oracle's own resolution order:

| Check | Why |
|---|---|
| `self` must be in scope | a STATIC method has no instance, so there is nothing to qualify with — leave it to fail loudly rather than invent a receiver |
| a column of a relation in scope wins | Oracle resolves a name that is both a column and a PL/SQL name to the column |
| a local, parameter or CTE wins | Oracle shadows attributes with locals |
| the name must be a known attribute | anything unrecognised passes through untouched |

Mirrored in **`VisitTableElement`** as well as `VisitGeneralElement`: `table_element` is the
alternative the grammar names *first* for the argument of `TO_CHAR`, `TO_DATE` and `COALESCE`, so
`to_char(qty)` never reaches the other route. The inverse rule — "not a local, therefore an
attribute" — is not implemented and must never be added.

**3. The constructor.** `self` is now declared as a local seeded with `ROW(NULL, …)::t`, and a bare
`RETURN;` returns it. Both halves are needed: declaring without rewriting returns nothing from a
`RETURNS t` signature, rewriting without declaring references an unknown variable.

- What a bare `RETURN;` means is **scoped to the routine**, pushed by `RoutineBodyBuilder` for every
  routine form — null for all but a constructor. A local subprogram declared inside a constructor
  therefore shadows the rewrite by construction, rather than by a check someone has to remember.
- `RoutineBodyBuilder` grew an `injectedDeclarations` parameter, reusing the mechanism the
  loop-RECORD and cursor-tracking declarations already used to create a DECLARE section.

> **The seed question in §4, settled by execution.** PostgreSQL assigns to a field of an
> *uninitialised* composite variable quite happily, so the seed is **not** needed for the ordinary
> case — the reasoning that predicted otherwise was wrong. It is needed for a different one: a
> constructor whose path assigns nothing must still return an all-NULL *instance*, as Oracle does,
> not a NULL composite. Both facts are pinned by execution tests.
>
> The cost is a real dependency: the prologue needs the type's attribute **count**, so it throws
> when the object type is absent from the metadata. In a correct run step 6 precedes step 29, and a
> run where it did not is a broken pipeline that should say so.

**Tests.** String-comparison tests in `TypeMethodTransformationTest` (including the negative
controls: parameter shadows attribute, static method resolves nothing, unknown name untouched, bare
`RETURN;` outside a constructor unchanged) and execution tests in
`PostgresTypeMethodStubReplacementValidationTest`, which **call** what they created — the only way
to see a bad body, since PL/pgSQL resolves bodies at call time. All three fixes verified by
mutation; each mutant reproduces the corresponding production error message.

### Phase 4 — call sites

**Done 2026-08-26.** Suite green at 1,852 tests; 4 new in `TypeMethodCallSiteTest`.

`generalelement/TypeMethodCallTransformer`, registered in the dot-navigation dispatcher at
priority 6 — **before** the package-function fallback, which is what previously swallowed these.

| Call | Was | Now |
|---|---|---|
| `a.describe('x')`, `a` a local of `demo_item_t` | `hr.a__describe('x')` | `hr.demo_item_t__describe(a, 'x')` |
| `a.line_total()` | `hr.a__line_total()` | `hr.demo_item_t__line_total(a)` |
| `demo_item_t.make_code('raw')` | `hr.demo_item_t__make_code('raw')` — right text, wrong branch | same text, real type-name branch |
| `NEW demo_item_t('abc')` | threw at `VisitUnaryExpression` | `hr.demo_item_t('abc')` |

**The instance is an argument, not a prefix.** PostgreSQL has no method dispatch, so the receiver
becomes the first argument, matching the `self t` first parameter Phase 2 emits. Declaration and
call site are two halves of one convention and were wrong independently.

**The member bug was the dangerous one.** It used the *variable* name where the type name belongs
*and dropped the instance entirely*. Inside a PL/pgSQL body a function name is resolved when the
line executes, not at `CREATE`, so this failed at runtime in production after reporting a
successful migration.

**Static calls looked correct for the wrong reason** and are worth understanding before touching
this code: the package-function branch happens to build `schema.first__second`, the same shape a
type method needs, so it produced the right text with the type name occupying the package slot. It
now has a real type-name branch — right by decision rather than coincidence.

**Only positive metadata rewrites**, per the parenthesis-less-routines rule in CLAUDE.md. A rewrite
needs the qualifier to be a known variable of an object type, or a known object type, *and* that
type to be known to have the method. The inverse — "not a known alias, therefore a type" — is not
implemented and must not be added.

**`NEW` is dropped, not rejected.** In Oracle it is *optional* syntax: `NEW t(1)` and `t(1)` invoke
the same constructor. Since constructors keep the Oracle type name (§5), the operand already
transforms correctly through the ordinary function path, so both spellings converge on one output.

**The `table_element` mirror is satisfied by construction, not by duplication.** A method call
always carries an argument list, and `table_element` has no syntax for arguments, so these
constructs are unreachable from that route — as `VisitTableElement` already documents.


Currently `a.get_label()` on a local variable `a` of type `t` produces `a__get_label()` — the
*variable* name used as the type name, **and the instance argument dropped entirely**. Inside
PL/pgSQL that is not caught at `CREATE`; it fails at runtime.

- instance method on a local variable or parameter
- instance method on a column (partly works today)
- static method — a real type-name branch in the dot-navigation dispatcher, not the accidental
  package-function branch that currently lands on the right name only because PostgreSQL case-folds
- constructor, with and without `NEW` (`NEW t(…)` currently throws at
  `VisitUnaryExpression.java:37`)
- chained calls
- **mirrored in `VisitTableElement`** per the `table_element` blind-spot rule in CLAUDE.md — the
  grammar names it first for `TO_CHAR`/`TO_DATE`/`COALESCE` arguments, so anything resolved in
  `VisitGeneralElement` must be resolved there too.

### Phase 5 — stubs and execution tests

Overloaded signatures; `INOUT self` for member procedures; existence check by
`(name, argument types)` rather than bare name — `getExistingPostgresFunctions()` currently queries
`pg_proc.proname` without argument types, so after the first run every further overload is skipped
as "already exists". Signature collision guard per §5. String-comparison tests plus Testcontainers
execution tests, per TESTING.md.

**Done 2026-08-27.** Was the largest single cause of failure — 449 of 1081 (§11).

**The invariant.** Step 25 builds the stub from `ALL_TYPE_METHODS` rows; step 29 replaces it with
`CREATE OR REPLACE FUNCTION` built from the type body's parse tree. PostgreSQL identifies a function
by `(schema, name, IN/INOUT argument types)` and refuses to change anything else about it, so the two
derivations must agree exactly. They share no code and cannot — different inputs, different shapes —
so **only a test keeps them in step**, and there was none. They had drifted on four points:

| Drift | Consequence |
|---|---|
| stub `CREATE OR REPLACE PROCEDURE` vs emitted `FUNCTION` | `cannot change routine kind` — 449 failures |
| member procedure stub `self t` vs emitted `INOUT self t` | changes the result type; refused |
| stub had no `RETURNS` for procedures | ditto — `void` / single OUT type / `RECORD` now derived identically |
| constructor stub named `t__t` vs emitted `t` | stub dead, implementation creates a second, unstubbed function |

**What landed**

- `typemethod/service/TypeMethodStubSqlBuilder` — the DDL in one pure, unit-testable place, with the
  agreement requirement written where the code is. The job no longer builds SQL.
- `TypeMethodStubSignatureAgreementTest` — builds **both** sides from the same Oracle method and
  compares everything up to `LANGUAGE plpgsql`, across all nine coverage-matrix forms. Verified by
  mutation: restoring any of the four drifts fails it with a message naming both signatures.
- `PostgresTypeMethodStubReplacementValidationTest` — Testcontainers. Creates the stub, then executes
  the real transformed implementation, and asserts **exactly one function of that name exists**
  afterwards. String equality cannot show what equality *means* here: that the second statement
  replaces the first rather than adding an overload beside it.
- **Signature collision guard, §5.** Oracle distinguishes `VARCHAR2` from `CHAR` and `NUMBER(10)`
  from `NUMBER`; the mapping collapses both pairs. Two overloads then produce one PostgreSQL function
  and the second `CREATE OR REPLACE` silently overwrites the first — one method quietly ends up with
  the other's body. `findSignatureCollisions` refuses the **whole** colliding set before anything is
  created; which one would have survived is an artefact of extraction order.
- **The "already exists" pre-check is gone.** It was keyed on `pg_proc.proname` alone, so from the
  second run onwards every overload after the first was reported as already created and never
  appeared. Testing existence in Java duplicates a decision `CREATE OR REPLACE` already makes
  correctly against the full signature, and the duplicate was wrong; the one case it was needed for
  — two Oracle overloads sharing a PostgreSQL signature — is now caught up front by the guard
  instead of being discovered as silent overwriting.

**Migrations start from an empty target database**, so no `DROP PROCEDURE` migration path was needed
for the stubs the old code left behind.

> **Found while doing this, and it changes Phase 3: `self.qty := …` still emits `jsonb_set`.**
> Registering `self` in Phase 2 did not fix it, because the assignment visitor never asks the scope
> tracker. Worse, PL/pgSQL resolves bodies at call time, so the method is reported implemented and
> fails in production. Full write-up in the §4 correction; pinned by a characterization test that
> will fail the moment Phase 3 closes it.

### Phase 6 — mutating members

Not a tail feature: mutating members are a first-class Oracle idiom, and on the verification
database they were the second-largest category after read-only members. Build the general
mechanism.

- **Member procedures.** `obj.set_x(1)` → `obj := t__set_x(obj, 1)`, a *statement-level* rewrite.
  In expression position there is no sound translation; throw.
- **Mutating member functions** (`SELF IN OUT` plus a return value) are in the §3.2 rejection
  catalogue. The one candidate workaround — return `ROW(self, result)` and destructure at every
  call site — is a design decision to take only if real code needs it. Until then, throw.

## 9. Dictionary verification queries

Reusable diagnostics, not a one-off inventory: run them against **any** new Oracle source database
before trusting extraction, since §2's semantics are asserted rather than defended by the data
dictionary itself. Substitute the migrated schema list for `&SCHEMAS` (e.g. `'HR','APP'`).

Q9 subsumes Q1/Q2/Q5 and is the one to run first; the rest narrow down specifics.

```sql
-- Q9: the full classification crosstab -- the primary diagnostic.
--
-- METHOD_TYPE and INSTANTIABLE carry no member/static/constructor information at all.
-- The three dimensions below are the ones that do:
--   self_mode   NULL = static; IN = read-only member; IN OUT = mutating member OR constructor
--   has_result  absence of an ALL_METHOD_RESULTS row = procedure
--   ctor_name   Oracle requires a constructor to be named after its type
-- MAX(param_mode) is safe because a method has at most one SELF row (see Q2c).
--
-- Two Oracle restrictions shape every query here, so do not "simplify" them:
--   * a subquery is not allowed in GROUP BY at all      -> ORA-22818
--   * EXISTS is not allowed in a SELECT list pre-23ai   -> ORA-00920
-- Hence the scalar COUNT(*) > 0 inside an inline view, grouped by alias in the outer query.
SELECT self_mode, has_result, ctor_name, method_type, COUNT(*) AS n
FROM (
  SELECT (SELECT MAX(p.param_mode) FROM all_method_params p
          WHERE p.owner = m.owner AND p.type_name = m.type_name
            AND p.method_name = m.method_name AND p.method_no = m.method_no
            AND UPPER(p.param_name) = 'SELF') AS self_mode,
         CASE WHEN (SELECT COUNT(*) FROM all_method_results r
                    WHERE r.owner = m.owner AND r.type_name = m.type_name
                      AND r.method_name = m.method_name AND r.method_no = m.method_no) > 0
              THEN 'function' ELSE 'procedure' END AS has_result,
         CASE WHEN m.method_name = m.type_name THEN 'name = type' ELSE 'other' END AS ctor_name,
         m.method_type
  FROM all_type_methods m
  WHERE m.owner IN (&SCHEMAS)
)
GROUP BY self_mode, has_result, ctor_name, method_type
ORDER BY n DESC;

-- Q1: METHOD_TYPE value domain. Anything outside MAP/ORDER/PUBLIC invalidates section 2.
SELECT method_type, COUNT(*) AS n
FROM all_type_methods WHERE owner IN (&SCHEMAS)
GROUP BY method_type ORDER BY n DESC;

-- Q2b: what the first parameter row of every method looks like.
-- Shows whether SELF is named literally 'SELF', and surfaces ODCI types (first param SCTX).
SELECT p.param_name, p.param_no, p.param_mode, p.param_type_owner, p.param_type_name,
       COUNT(*) AS n
FROM all_method_params p
WHERE p.owner IN (&SCHEMAS) AND p.param_no = 1
GROUP BY p.param_name, p.param_no, p.param_mode, p.param_type_owner, p.param_type_name
ORDER BY n DESC;

-- Q2c: is SELF counted in ALL_TYPE_METHODS.PARAMETERS?
-- A perfect diagonal means yes, and means a method has at most one SELF row.
SELECT declared_parameters, param_rows, COUNT(*) AS n
FROM (
  SELECT m.parameters AS declared_parameters,
         (SELECT COUNT(*) FROM all_method_params p
          WHERE p.owner = m.owner AND p.type_name = m.type_name
            AND p.method_name = m.method_name AND p.method_no = m.method_no) AS param_rows
  FROM all_type_methods m
  WHERE m.owner IN (&SCHEMAS)
)
GROUP BY declared_parameters, param_rows
ORDER BY n DESC;

-- Q12: inherited methods must be excluded from extraction -- confirm the split.
SELECT m.type_name, m.inherited, COUNT(*) AS n
FROM all_type_methods m
WHERE m.owner IN (&SCHEMAS)
GROUP BY m.type_name, m.inherited
HAVING MAX(CASE WHEN m.inherited = 'YES' THEN 1 ELSE 0 END) = 1
ORDER BY m.type_name, m.inherited;

-- Q4: overload inventory (drives method_no keying in Phase 1 and the collision guard in section 5)
SELECT owner, type_name, method_name, COUNT(*) AS overloads
FROM all_type_methods WHERE owner IN (&SCHEMAS)
GROUP BY owner, type_name, method_name HAVING COUNT(*) > 1
ORDER BY overloads DESC, type_name, method_name;

-- Q6: type shape -- inheritance, collection vs object, attribute counts.
-- supertype_name NOT NULL or instantiable = 'NO' means the section 3.2 inheritance rejection applies.
SELECT owner, type_name, typecode, attributes, methods,
       supertype_owner, supertype_name, instantiable, final
FROM all_types WHERE owner IN (&SCHEMAS)
ORDER BY type_name;

-- Q8: MAP / ORDER methods on multi-attribute types -- the section 6 warning condition.
SELECT m.owner, m.type_name, m.method_name, m.method_type, t.attributes
FROM all_type_methods m
JOIN all_types t ON t.owner = m.owner AND t.type_name = m.type_name
WHERE m.owner IN (&SCHEMAS) AND m.method_type IN ('MAP', 'ORDER')
ORDER BY m.type_name;

-- Q10: ODCI extensibility-interface types (section 3.2 rejection).
SELECT m.owner, m.type_name, m.method_name, m.method_no, m.method_type
FROM all_type_methods m
WHERE m.owner IN (&SCHEMAS)
  AND (UPPER(m.method_name) LIKE 'ODCI%'
       OR EXISTS (SELECT 1 FROM all_method_params p
                  WHERE p.owner = m.owner AND p.type_name = m.type_name
                    AND p.method_name = m.method_name AND p.method_no = m.method_no
                    AND p.param_no = 1 AND UPPER(p.param_name) <> 'SELF'
                    AND p.param_type_owner IS NOT NULL))
ORDER BY m.type_name, m.method_no;

-- Q11: which code depends on the method-bearing types -- sequencing only, never scope.
-- ALL_DEPENDENCIES rather than a regexp over ALL_SOURCE: it is compiler-resolved, so it does
-- not match comments, string literals or same-named locals.
SELECT d.referenced_name AS type_name, d.type AS depending_unit_type, COUNT(*) AS n
FROM all_dependencies d
JOIN all_types t ON t.owner = d.referenced_owner AND t.type_name = d.referenced_name
WHERE d.referenced_owner IN (&SCHEMAS)
  AND d.referenced_type = 'TYPE'
  AND t.methods > 0
GROUP BY d.referenced_name, d.type
ORDER BY type_name, n DESC;
```

## 10. Decision log

| Date | Decision |
|---|---|
| 2026-08-17 | SELF becomes an ordinary scoped composite variable/parameter, not a jsonb payload or a rewritten expression form. §4 |
| 2026-08-17 | Constructors keep the Oracle type name and rely on native PostgreSQL overloading. Rationale is failure-mode asymmetry at undetected call sites. §5 |
| 2026-08-17 | MAP/ORDER emitted as ordinary functions; no custom operator classes; warn on multi-attribute types. §6 |
| 2026-08-17 | Per-type remodelling to native scalars rejected — would ripple into table DDL and CSV transfer, and would be a per-database special case. §7 |
| 2026-08-17 | Failed type-method transformation must be an error, not a skip. Phase 2 |
| 2026-08-26 | Dictionary semantics confirmed: `METHOD_TYPE` and `INSTANTIABLE` carry no member/static information; `SELF` is a counted parameter row; inherited methods are duplicated onto the subtype. §2 |
| 2026-08-26 | **Coverage is measured against the grammar, not against any one database.** Every `type_body_elements` alternative is implemented or throws a named error; negative observations from a sample schema inform sequencing only. §3 |
| 2026-08-26 | Constructs that cannot be transformed at all get a named rejection rather than an approximation — `call_spec`, `REF`, inheritance, ODCI, mutating member functions. §3.2 |
| 2026-08-26 | Phase 6 (mutating members) promoted from a Phase-0-sized tail to core work. §8 |
| 2026-08-26 | Grammar routing settled by parse test, pinned by `TypeBodyElementsGrammarTest`: `OVERRIDING` is reachable on two alternatives and must be rejected on both; a Java `call_spec` body parses clean so its rejection must be an explicit check. §3.1.1 |
| 2026-08-26 | Classification lives in a pure, DB-free `TypeMethodClassifier` that throws on unexpected dictionary values. Kept out of the extractor so the rule table is unit-testable against value combinations no available database contains. Phase 1 |
| 2026-08-26 | Overload **names** are supposed to collide — that is what PostgreSQL native overloading resolves. Only **signatures** must be unique, guarded in Phase 5. Supersedes an earlier Phase 1 bullet that said the opposite. Phase 1 |
| 2026-08-26 | Method sources keyed by **ordinal within name**, not by `method_no` as a per-type source position. Smaller unverified assumption, and it degrades to swapped overloads of one name rather than a whole type shifted by one. Phase 1 |
| 2026-08-26 | A segment/dictionary count mismatch rejects the **whole type**, not the offending name — a mis-detected boundary may have shifted its neighbours. Phase 1 |
| 2026-08-26 | Phase 2's "reuse `VisitFunctionBody`'s body/parameter machinery" was **half wrong**: `type_elements_parameter` and `parameter` are different grammar rules with different context classes and different shapes, and the four type-body routes each name themselves differently. Only the declare/body work is shared. Type methods get their own parameter visitor. Phase 2a |
| 2026-08-26 | The shared declare/body work is extracted from `VisitFunctionBody` into a helper **before** the type-method visitors are written, not after — three copies is how the two paths drift apart. Phase 2a |
| 2026-08-26 | The `getText()`-on-identifier violations split in two: **scope registration was genuinely broken** for quoted names and is fixed (`IdentifierHelper.canonical()`); **name emission** diverges only on unquoted names PostgreSQL needs quoted, is *not* fixable with `emit()` (which leaves unquoted text untouched by design), and needs `PostgresIdentifierNormalizer` — a real output change, so left as its own decision. Phase 2a |
| 2026-08-26 | Phase 4: the receiver becomes the **first argument**, never a prefix — the call-site half of the `self t` convention Phase 2 emits. Only positive metadata rewrites; the "not an alias, therefore a type" inverse is forbidden. Phase 4 |
| 2026-08-26 | `NEW t(…)` drops the keyword rather than rejecting it: Oracle's `NEW` is optional syntax for the same constructor call, so both spellings converge on one output. Phase 4 |
| 2026-08-26 | Phase 2 landed: `parseTypeMethod` over `type_body_elements`, total dispatch, the §3.2 catalogue as named throws, and the job routed to it with failure = error. Step 29 no longer reports success while implementing nothing. Phase 2 |
| 2026-08-26 | `self` scope registration pulled forward from Phase 3 into Phase 2. Shipping the visitors without it would knowingly emit `jsonb_set()` against a composite in every member body — silent corruption, which outranks phase tidiness. Constructor prologue and `RETURN;` rewrite remain Phase 3. Phase 2 |
| 2026-08-26 | `SELF IN OUT NOCOPY` does not match `constructor_declaration`'s dedicated SELF slot (which omits `NOCOPY`) and arrives as an ordinary parameter named `SELF`. `SELF` is therefore filtered from every parameter list rather than trusted to the grammar. Phase 2 |
| 2026-08-26 | Object-type parameters are qualified via `TransformationContext.qualifyTypeName()`, not `TypeConverter` — the latter has no metadata and returns the bare name, which resolves only if `search_path` happens to cover the schema. Phase 2 |
| 2026-08-26 | `VisitProcedureBody` already held a byte-identical copy of the body block, so the extraction removes existing duplication rather than anticipating future duplication — and the quoted-parameter bug was present in both copies, which is the argument for the helper in one sentence. Phase 2a |
| 2026-08-27 | `TypeMethodBoundaryScanner` rebuilt on the ANTLR lexer instead of patching the character scanner. The character version decremented block depth on `END IF`/`END LOOP`/`END CASE` and truncated ~325 methods; `FunctionBoundaryScanner` had solved this for package bodies and the type path never got it. Phase 2b |
| 2026-08-27 | Open `CASE`s are counted rather than only inspecting the token after `END`: a CASE **expression** closes with a bare `END` that no lookahead can distinguish from a block `END`. Phase 2b |
| 2026-08-27 | Leading modifiers (`OVERRIDING`, `FINAL`, …) are part of the segment. Starting at `MEMBER` made the §3.2 `OVERRIDING` rejection unreachable and emitted a plain function that silently discarded inheritance — trading a few false successes for named rejections is the whole point. Phase 2b |
| 2026-08-27 | A `call_spec` body is recorded and ends at its semicolon rather than being skipped: skipping made the scanner consume a later method's `END` and lose the whole type to the count refusal, instead of one named "implemented in Java/C" rejection. Phase 2b |
| 2026-08-27 | Boundary-scanner tests assert the **extracted text**, and `DemoTypeFixtureParseTest` feeds every scanned segment through `parseTypeMethod`. Count-and-name assertions passed against a truncated body for months; the scanner→parser join is where the defect lived and neither half's tests looked at it. Phase 2b |
| 2026-08-27 | A job result type with no `JobResource` branch reports nothing at all — no counts, no summary. Step 29's 509 implemented / 1081 failed rendered as a badge of 0 and an empty panel. The branch carries the projection only, not the raw result. Phase 2b |
| 2026-08-27 | Remaining phase order is set by the measured failure taxonomy (§11): **5 → 3 → tail**, Phase 6 last because its rejections are correct. Counts inform sequencing only; coverage is still measured against the grammar. §11 |
| 2026-08-27 | Stub DDL moved out of the job into a pure `TypeMethodStubSqlBuilder`, and stub-vs-implementation signature agreement is asserted by a test that builds both sides from the same method. The two derivations share no code and cannot; nothing else can keep them in step, and they had drifted on four separate points. Phase 5 |
| 2026-08-27 | The "already exists" pre-check is deleted rather than fixed. It duplicated a decision `CREATE OR REPLACE` already makes correctly against the full signature, and the duplicate — keyed on the bare name — skipped every overload after the first from the second run on. Phase 5 |
| 2026-08-27 | A signature collision refuses the **whole** overload set before anything is created. Which one would survive is an artefact of extraction order, and the alternative is one method silently holding another's body. Phase 5 |
| 2026-08-27 | **§4's claim that registering `self` fixes the `jsonb_set` field assignment is wrong**, disproved by execution: the assignment visitor never consults the scope tracker. It compiles and fails at call time, so it is counted as implemented. A claim about what PL/pgSQL does with a generated body cannot be settled by reading the transformer. §4, Phase 3 |
| 2026-08-27 | Composite field assignment falls through to a plain assignment when the base variable is in scope as an object type **and has no inline type definition** — an inline RECORD genuinely is jsonb. Not `self`-specific: a local of any object type had the same corruption. Phase 3 |
| 2026-08-27 | Implicit SELF attribute references rewrite only on positive metadata, in Oracle's resolution order — SELF in scope, then columns, then locals, then the attribute list — and are mirrored in `VisitTableElement`. A STATIC method resolves nothing, because it has no instance to invent. Phase 3 |
| 2026-08-27 | What a bare `RETURN;` means is scoped to the routine and pushed by `RoutineBodyBuilder` for **every** form, so a local subprogram inside a constructor shadows the rewrite by construction rather than by a remembered check. Phase 3 |
| 2026-08-27 | The constructor's `ROW(NULL, …)` seed stays, but for a different reason than assumed: assignment to a field of an uninitialised composite works, so the seed only matters when a path assigns nothing and Oracle would still return an instance. It makes the type's attribute count a hard dependency, and a missing object type throws. §4, Phase 3 |
| 2026-08-27 | Every emitted type goes through **one authority**, `transformer/util/TypeResolver`, rather than patching the one route the log pointed at. Nine of ten type-emission sites called `TypeConverter` directly, which has no metadata and returns unrecognised names unchanged; a single-site fix would have passed both failing tests and left the identical bug in nine neighbours. §12.1 |
| 2026-08-27 | Cross-schema type references and Oracle collection types are **excluded** from that fix. Widening `qualifyTypeName`'s search risks matching a name in an unintended schema, and collection types have no composite in the target at all — a modelling question, not a qualification one. Each gets its own decision. §12.1 |
| 2026-08-27 | The diagnosis was pinned by a **passing control test** on the return-type route, not only by failing tests on the declare-section route. The 2026-08-27 log could not distinguish the two — both emit a bare type name — and the fluent-setter shape that dominated the failures made the return type the more plausible suspect. §12.1 |
| 2026-08-28 | Element assignment into an attribute is **rejected**, not resolved to `self.<attr>` and left as `jsonb_set`. An object type attribute is never an inline type, so the branch's jsonb assumption can never hold for one; qualifying the name alone would convert a CREATE-time error into a call-time one — Phase 3's bug, one branch over. §12.2 |
| 2026-08-28 | The rejection carries the **Oracle type** of the attribute, because the next run's log is what scopes the collection-type modelling work — which types, and how many methods each blocks. An error message is a measurement instrument here, not just a diagnosis. §12.2 |
| 2026-08-28 | §12.2 and the collection-type remainder of §12.1 are **one root cause**, not two items: Oracle collection types have no target representation, seen once from the type-emission side and once from the assignment side. Giving them one is a modelling decision adjacent to the per-type remodelling §7 rejected, and is deliberately not taken here. §12.2 |
| 2026-08-28 | The third run makes this plan **no longer the highest-value work**: 84% success and 98 real failures, against ~22% and 54,093 in orchestration step 28. Measuring a plan against itself for three runs is what hid that; the fourth measurement deliberately looked at the neighbouring jobs first. §13.2 |
| 2026-08-28 | Step 28 still routes a **failed transformation to `addSkippedFunction`** — §1's founding finding, left unfixed in the sibling job. A decision recorded against one job is not applied to another by being written down: 3,965 silent failures were reported as a neutral category for as long as step 29's identical bug was. §13.2 |
| 2026-08-28 | The collection-type gap is **not a transformer gap**. Extraction cannot tell `TYPECODE = 'COLLECTION'` from `'OBJECT'`, so a collection is ingested with zero attributes and step 7 emits `CREATE TYPE … AS ()`, which PostgreSQL accepts. The empty shell then satisfies `isObjectType()`, so declarations *compile* — a fail-loudly violation at DDL level, and the only one found so far outside the transformer. §13.3 |
| 2026-08-28 | §12.1's fix made this **worse before it made it visible**: qualifying same-schema types took missing-type errors 184 → 7 by resolving collection references successfully into types that cannot hold data. A drop in a failure count is not evidence of correctness when the fix and the remaining defect share a metadata source. §13.3 |
| 2026-08-28 | The first collection move is **diagnostic, not representational**: extract `TYPECODE`/`ALL_COLL_TYPES` and make step 7 refuse `AS ()`. The next run's log then scopes the modelling work. Choosing a representation from these 51 rejections would be guessing — what §7 rejected per-type remodelling for. §13.3 |
| 2026-08-28 | Log rotation (10 MB × 5) **destroyed ~95% of step 28's errors**, and the failing SQL `addError` captures lives only in `StateService`, which is memory. 255 failures could be classified and 54,093 could not, purely by which survived. Error transparency is the precondition for the remaining decisions here, not a UI feature. §13.4 |
| 2026-08-28 | The link from the 28 `syntax error at or near "common_types"` failures to collection types is recorded as a **hypothesis, not a count** — every failing method is a collection wrapper, but the failing SQL was not logged, so §2's negative-observation rule applies to it as much as to a negative one. §13.3 |

## 11. First full-run measurement — 2026-08-27

The first production run with step 29 actually transforming. **1590 type methods: 509 implemented,
0 skipped, 1081 errors.** This section exists because the phase order that looked right from the code
was not the phase order the failures justified, and because two of the causes were not in the plan at
all.

Read from `logs/migration.log`, not from the UI — which reported an implemented count of zero, for
the reason in Phase 2b.

| Count | Error | Cause | Phase |
|---|---|---|---|
| 449 | `cannot change routine kind` | stub emitted as `PROCEDURE`, implementation as `FUNCTION` | **5 — fixed** |
| ~325 | `mismatched input '<EOF>' expecting {'END','EXCEPTION' \| 'ELSE','ELSIF'…}` | truncated stored source | **2b — fixed** |
| 66 | `"self" is not a known variable` | constructor: no injected `self` local | **3 — fixed** |
| 56 | mutating member function rejected | correct today | 6 |
| ~50 | `"<attribute>" is not a known variable` | unqualified attribute reference (implicit SELF) | **3 — fixed** (was not in the plan) |
| 48 | `type "X" does not exist` | collection and system types absent from the target | tail |
| 33 | `missing expression at or near ";"` | constructor: bare `RETURN;` not rewritten | **3 — fixed** |
| 11 + 7 + tail | `syntax error at end of input`, default-parameter ordering, … | assorted | tail |

**What this changes.** Phase 5 moved ahead of Phase 3 — one emitter decision against 449 failures,
and the two were independent. Phase 6 stays last: its 56 rejections are the plan working as designed,
not a gap. **Phases 2b, 5 and 3 are all done, addressing ~925 of the 1081**, leaving the type gaps
(48), the assorted tail (~18) and Phase 6 (56) — plus whatever the recovered methods turn out to
need. **The next full run is the measurement that matters now**; this table is spent.

> **Superseded by §12.** The re-measurement asked for above was run the same day. The three
> categories predicted to disappear did; the tail predicted to grow did, and the largest cause on
> the new run was **not in this table at all**.

## 12. Second full-run measurement — 2026-08-27 (16:00)

**1590 type methods: 1178 implemented, 0 skipped, 412 errors** — against 509 / 0 / 1081 in §11.
Read from `logs/migration.log`; step 29 ran 16:00:36–16:02:16. The §11 fixes landed as predicted:
`cannot change routine kind` (449), the truncated-source parse errors (~325), `"self" is not a known
variable` (66) and `missing expression at or near ";"` (33) are **all gone, to zero**.

| Count | Error | Cause | Status |
|---|---|---|---|
| 184 | `type "X" does not exist` | object type emitted unqualified | **fixed 2026-08-27, §12.1** |
| 56 | mutating member function rejected | correct today | 6 |
| 44 | `OVERRIDING` rejected | correct — §3.2 inheritance | permanent |
| 43 | `"<attr>" is not a known variable` | element assignment into an attribute, §12.2 | **partly closed 2026-08-28 — becomes a named rejection, not a success** |
| 29 | `syntax error at or near "common_types"` (10), `"DECLARE"` (5), `IF`/`INTO`/`.1`, end-of-input | statement-level emission gaps | tail |
| 23 | `>2 path parts` (12), `dml_table_expression_clause` (5), `CURRENT OF`, chained calls, `PRIOR`, CAST-to-UDT | transformer tail | tail |
| 7 | input parameters after one with a default | Oracle permits mid-list defaults, PostgreSQL does not | tail |
| 6 | `function fw_core.url(unknown) does not exist` | cross-schema constructor, §12.1 | 🔄 next |
| 6 | ODCI interface method rejected | correct — §3.2 | permanent |
| 4 | `cannot change name of input parameter "ppk"` | **all four on overloaded methods — §12.3** | 🔄 investigate |

**106 of the 412 are named rejections working as designed** (56 + 44 + 6), so the honest denominator
for remaining *work* is ~306. As in §11, the implemented count is a **floor on success**, not a total:
PL/pgSQL resolves bodies at call time.

### 12.1 The 184 — object types emitted unqualified

The largest single cause, and **it was not in §11's table** because the failures it would have
produced were masked: those methods were failing earlier, at parse, on truncated sources.

The shape is a method naming its *own* type: `common_types.linkurl.setBaseUrl` → `type "linkurl" does not
exist`. The composite type exists — 33 other `linkurl` methods succeeded with `self common_types.linkurl` —
so this was a qualification failure, not a missing type. `VisitVariable_declaration` sent the type
straight to `TypeConverter`, which has no metadata and returns unrecognised names unchanged.

**Exactly one of ten type-emission sites consulted metadata**: `VisitTypeElementsParameter`, the site
Phase 2 fixed. Fixed by lifting that logic into `transformer/util/TypeResolver` and routing all ten
through it — the `table_element` blind-spot shape in a tenth form. **Not type-method-specific**: the
same run shows the standalone and package function job failing on `optionlist`, `url`, `cardrow`,
`propmap`. Full write-up:
[TRANSFORMATION.md](TRANSFORMATION.md#object-type-names-in-emitted-types--one-authority-typeresolver--2026-08-27).

Two sub-buckets are **not** covered by that fix and remain: **cross-schema** references
(`web_api` → `common_types.url`; ~8 including the 6 constructor calls), because `qualifyTypeName`
searches only synonyms → current schema → `PUBLIC` → `SYS`; and Oracle **collection** types
(`textlist3k`, `pm_key_array`, `rowtextlist26k`; ~12), which have no composite type
in the target at all and are a modelling question, not a qualification one.

### 12.2 The 43 — element assignment into an attribute — ✅ partly closed 2026-08-28

The failing names are type attributes and parameters (`mbindvars`, `mfvars`, `vrowcollection`,
`akey`, `pmessages`). The mechanism is provable from the code:
`VisitAssignment_statement.tryTransformCollectionElementAssignment` reads the base variable name
**verbatim from the parse tree** and emits it on both sides of a `jsonb_set`, never consulting the
scope tracker or `ImplicitSelfAttributeDetector`. So `mbindvars(i) := x` emits
`mbindvars := jsonb_set(mbindvars, …)` and PL/pgSQL answers `"mbindvars" is not a known variable`.
`tryTransformInlineTypeFieldAssignment` reads its base the same way.

**Resolving the name is only half the fix, and the wrong half alone.** That branch also *assumes the
base is jsonb*, which is true for the inline collection types it was written for (`TYPE … IS TABLE OF`
inside a package or routine) and false for an attribute. **An object type attribute is never an inline
type** — an attribute always names a schema-level Oracle type. Rewriting `mbindvars` to
`self.mbindvars` while keeping `jsonb_set` would trade a loud CREATE-time error for
`jsonb_set(composite, …) does not exist` at *call* time: precisely the silent corruption Phase 3
removed from the sibling field-assignment branch, re-introduced one branch over.

**Decision: reject, with the Oracle type in the message.** `TypeMethodRejection.collectionElementAssignment`
fires when the base is positively known not to be an inline collection — an attribute of the enclosing
type, or a variable of a migrated object type. Both are positive metadata; an unrecognised name is
left alone, so the rejection cannot capture a target it does not understand.

**This does not raise the implemented count**, and was not expected to: these methods failed before
and fail now. What changes is that the error names the real cause and **the Oracle type**, so the next
run's log says which collection types would have to be modelled and how many methods each one blocks —
which is what scopes that work. §12.1's ~12 missing-type failures and these are one root cause seen
from two sides; the same types produce both (`main_app.propmap` fails on `akey` *and* on
`pm_key_array`, `pm_value_array`, `pm_number_array`, `pm_date_array`).

> **Coverage is a subset, and deliberately unquantified.** The guard covers `attr(i) := …`, which is
> what the code proves wrong. How many of the 43 take that exact shape is **not known** — the log
> carries no source, and `common_types.rowset` and `common_types.messagelist` fail *only* with "not a known
> variable" and never on a missing type, which suggests at least one other mechanism. Per §2's
> negative-observation rule that is not guessed at here; the next run's rejection messages will split
> it.

**Tests.** `TypeMethodTransformationTest.collectionElementAssignmentOnAttribute_isRejected` plus the
control `collectionElementAssignmentOnInlineLocal_isUnchanged`. The control is load-bearing and was
verified by mutation: making the guard unconditional fails it and nine tests in
`PostgresInlineTypeCollectionElementTest`, which is what pins the rejection as narrow rather than
merely present.

### 12.3 The 4 — parameter-name drift, on overloads only

`cannot change name of input parameter` on `customfieldset.customfieldset`, `fetchtoken`, `fetchserver`,
`existsrefreshtoken` — **all four overloaded**. That is the shape of the residual risk Phase 1
accepted explicitly: two overloads of one name paired to the wrong body segments, which the
count check cannot detect because the counts tally either way. `TypeMethodStubSignatureAgreementTest`
compares parameter *types*, not names, so it would not catch it either. Cheap to confirm, and it is
the one accepted risk showing a symptom.

**Same two cautions as §11.** These are one schema set and inform **sequencing only**; the coverage
criterion is still the grammar (§3). And re-measure after the next run before committing to the tail
— §11's table did not predict what §12 found.

> **Superseded by §13.** The re-measurement asked for above was run on 2026-08-28. §12.1 and §12.2
> both held. But the caution was right again in a way it did not anticipate: the largest finding of
> the third run is not in this table, is not a type-method failure, and is not in the transformer.

**The 509 "implemented" is not 509 working methods.** PL/pgSQL resolves function bodies at call time,
so a method whose body is wrong is still created without complaint and counted as implemented. The
composite-field-assignment gap in the §4 correction is exactly that shape: every member method that
assigns to a field of `self` is in the implemented count and fails on first call. **Treat this table
as a floor on the failures, not a total** — the only way to find the rest is execution.

**Two more cautions on reading these numbers again.**

- They are one schema set. Per the negative-observation rule in §2, they inform **sequencing only** —
  the coverage criterion is still the grammar (§3), and no count here may become an assumption in
  code. That a construct is rare in this database says nothing about the next one.
- The taxonomy will shift once Phase 2b's ~325 truncated methods reach the transformer intact for
  the first time. They have never been transformed, so they will surface their own failures.
  **Re-measure after the next full run before committing to the tail items**; the table above is a
  measurement, not a forecast.

## 13. Third full-run measurement — 2026-08-28

**1590 type methods: 1335 implemented, 0 skipped, 255 errors** — against 1178 / 412 in §12 and
509 / 1081 in §11. Read from `logs/migration.log`; step 29 ran 09:33:06–09:34:41. Suite green at
1912 tests across 172 classes.

Every §12 prediction landed. The `type "X" does not exist` bucket collapsed from **184 → 7**, and
none of the 7 is the composite-qualification bug §12.1 fixed — they are `json_object_t` (Oracle 21c
native JSON, 6) and one `cref`. §12.2's rejection fired 51 times and named the Oracle type on every
one, exactly as it was built to.

**This section is different from §11 and §12.** Those measured a plan against itself and reordered
its phases. This one measures the plan against the *rest of the pipeline* and concludes the plan is
no longer where the work is.

### 13.1 The taxonomy

| Count | Category | Status |
|---|---|---|
| **56** | mutating member function | correct — Phase 6 |
| **51** | collection element assignment | correct — §12.2 |
| **44** | `OVERRIDING` | correct — §3.2 inheritance |
| **6** | ODCI | correct — §3.2 |
| 38 | `syntax error at or near …` — 28 of them `"common_types"`, then `DECLARE` (5), `IF` (2), `main_app`, `PERFORM`, `INTO` | tail, §13.3 |
| 15 | `… does not exist` — **8 collection / cross-schema constructor calls**, 6 `json_object_t`, 1 `cref` | §13.3 |
| 12 | package function call with `>2` path parts (`[LangType, new, setText]`) | tail |
| 7 | input parameters after one with a default | tail |
| 7 | **source not found** — `common_types.treedata` (5), `programmes.query` (2) | §13.5 — likely mis-filed |
| 5 | `dml_table_expression_clause` | tail |
| 4 | `cannot change name of input parameter` | §12.3 — all four still on overloads |
| 3 | `syntax error at end of input` | tail |
| 2+2+1+1+1 | `CURRENT OF`, chained calls, `PRIOR`, CAST-to-UDT, `"mbindvars" is not a known variable` | tail |

**157 of the 255 are named rejections working as designed** (56 + 51 + 44 + 6), up from 106 in §12
— because §12.2 converted 51 silent-corruption candidates into honest refusals. The denominator for
remaining *work* is **98**.

As in §11 and §12: the implemented count is a **floor on success**, not a total, because PL/pgSQL
resolves bodies at call time. And these are one schema set — they inform **sequencing only**, per
§2's negative-observation rule. Coverage is still measured against the grammar (§3).

### 13.2 Type methods are no longer the constraint — step 28 is

The same run's other job summaries, which no previous measurement looked at:

| Step | Job | Implemented | Failed | Success |
|---|---|---|---|---|
| 28 | Function implementation | 16,366 | **54,093 errors + 3,965 "skipped"** | **~22%** |
| 29 | Type methods | 1,335 | 255, of which 157 by design | **84%** |
| 31 | Trigger implementation | 214 | 90 | 70% |

Type methods went from the worst-performing area in the pipeline to the best. Step 28 is failing at
roughly **200× the absolute volume**, has never been taxonomised, and still carries a defect this
plan diagnosed and fixed sixteen decisions ago:

> `PostgresFunctionImplementationJob.java:197-199` routes a **failed transformation to
> `addSkippedFunction`, not `addError`.** That is precisely the §1 finding — the mechanism that let
> step 29 "report success while implementing nothing" — and the fix (decision log 2026-08-17,
> Phase 2) was applied to the type-method job only. Those **3,965 "skipped" functions are silent
> failures**, and the job's own summary line presents them as a neutral category.

Two caveats, stated rather than smoothed over:

- **The denominator is unverified.** `Found N functions to implement` had rotated out of the log,
  so the only evidence for the total is the counters themselves: 16,366 + 3,965 + 54,093 = 74,424.
  That is large even for this schema set, and the alternative explanation — `getOracleFunctionMetadata()`
  accumulating across re-runs — has not been excluded. In the retained window only 1.1% of names
  repeated, which argues the total is real, but this must be confirmed before the ratio is quoted
  anywhere as fact.
- **The evidence is destroyed.** See §13.4.

### 13.3 Collection types — one root cause, and it is not in the transformer

§12.2's closing note called §12.1's missing types and §12.2's element assignments "one root cause
seen from two sides". The third run shows it is **one root cause seen from four**, and that the
origin is upstream of every visitor: it is in orchestration steps 6–7.

**There is no extraction of Oracle collection types anywhere in the codebase.** `ALL_COLL_TYPES` and
`ALL_TYPES.TYPECODE` appear in **zero** files. The chain:

1. `OracleObjectDataTypeExtractionJob.java:82-88` selects `all_objects WHERE object_type = 'TYPE'`,
   which returns `TYPECODE = 'COLLECTION'` and `'OBJECT'` **indistinguishably**, then reads
   attributes from `ALL_TYPE_ATTRS` only (`:155-164`). A collection type has no rows there, so it is
   ingested as an object type with **zero attributes**.
2. `PostgresObjectTypeCreationJob.java:375-421` has no guard for that and emits
   `CREATE TYPE common_types.textlist3k AS ()`. **PostgreSQL accepts an empty composite type** —
   verified against PostgreSQL 16, 2026-08-28. So the type *exists*, and can never hold an element.
3. `TypeResolver.java:87-90` then does its job correctly and confidently: `isObjectType()` returns
   true for the empty shell, so `v TextList3K;` emits `v common_types.textlist3k` and
   **the declaration compiles**. This is why §12.1's fix took missing-type errors from 184 to 7 — the
   same-schema references now succeed *into a type that cannot work*. Only the cross-schema ones
   (`propmap`, `cardrow`, `typegrouppair`) still fail loudly.
4. `TransformationContext.VariableDefinition.isCollection()` keys on `inlineType`, which is null for
   these, so **every piece of the working collection machinery skips them**: element read, element
   assignment, and `CollectionMethodTransformer`'s `COUNT`/`FIRST`/`LAST`/`EXISTS`/`DELETE`.

**The system holds two disjoint collection models and only one of them exists.**

| | Inline collections | Schema-level collections |
|---|---|---|
| Declared | `TYPE t IS TABLE OF …` inside a package or routine | `CREATE TYPE t IS TABLE OF …` |
| Representation | `jsonb` + initializer | **none** — an empty composite by accident |
| Element read / write | ✅ | ❌ — §12.2 rejects |
| `COUNT`/`FIRST`/`LAST`/`EXISTS`/`DELETE` | ✅ `CollectionMethodTransformer` | ❌ — not recognised as a collection |
| `EXTEND` / `TRIM` | ❌ **unimplemented for both** — the only mention is a comment at `VisitSql_statement.java:14` | ❌ |
| Constructor call | ✅ | ❌ — `function fw_core.textlist3k(unknown, unknown, unknown) does not exist` |

Step 29's evidence for the schema-level column: **51** element-assignment rejections, **8** of the 15
`does not exist` failures, and — very likely — the **28** `syntax error at or near "common_types"`, whose
failing methods are *all* collection-wrapper types (`bindvars.bind`, `filters.filter`,
`optionlist.addoption`, `configvalues.addval`, `replacementtable.setreplacement`). That last link is
**a hypothesis, not a measurement**: the failing SQL is captured in the job result and never written
to the log, so it could not be confirmed here. Per §2's negative-observation rule it is not counted
as if it were.

> **The step-7 behaviour is a "fail loudly" violation, and the only one found at DDL level.**
> Everything this plan has fixed was a transformer that produced wrong output; this is a migration
> job that produces a *plausible* target object which silently cannot hold data. A table column of
> such a type would be created as an empty composite and transferred into. That outranks any
> remaining item in §13.1, and it is outside this plan's scope — which is exactly why it survived
> three measurements.

**The first move is diagnostic, not a modelling decision.** Extract `TYPECODE` (and `ALL_COLL_TYPES`
for element type, `COLL_TYPE`, `UPPER_BOUND`) and make step 7 **refuse** to emit `AS ()` with a named
error. That converts an unknown number of silently-broken types into a counted failure and makes the
next run's log state which collection types exist, which are used as table columns, and how much of
step 28 they account for. Choosing a target representation before that number exists is guessing —
and §7 already rejected per-type modelling on precisely that reasoning.

### 13.4 The measurement instrument is broken

`application.properties:24-25` retains 10 MB × 5 backups. The run produced far more, so roughly 95%
of step 28's errors were **overwritten before anyone read them**. What survived is a six-minute tail.

This is why §13.2 can state step 28's ratio but not its taxonomy: 255 type-method failures could be
classified and 54,093 function failures could not, and that asymmetry is purely an artefact of which
errors happened to survive rotation.

Compounding it: `addError(name, msg, pgSql)` **does** capture the failing SQL, faithfully, on every
job — but only into `StateService`, which is in memory. The app shut down at 12:02. The one artefact
that would settle §13.3's 28-failure hypothesis existed and is gone.

Also found, and cheap: `application.properties:18` sets the DEBUG category on
`me.christianrobert.ora2postgre`. The package is `orapgsync` — **dead config since the rename**, so
`log.debug` output has never been reaching the file.

CLAUDE.md lists "Error transparency — per-object error detail" as Priority 1 🔄 active. This run is
the argument for finishing it: it is not a UI nicety, it is the precondition for every remaining
decision in this document.

### 13.5 Revised order

Ahead of everything in this plan:

1. ✅ **Restore the instrument** — done 2026-08-28, §13.6.
2. ⬜ **Taxonomise step 28** on evidence that survives, and verify the 74,424 denominator (§13.2).
   **Needs one more full run first** — items 1 and 3 only take effect on the next one.
3. ✅ **`PostgresFunctionImplementationJob`, skip → error** — done 2026-08-28, §13.6.
4. ⬜ **Make collection types loud at step 7** (§13.3). Diagnostic only — no representation chosen yet.
5. ⬜ *Then* decide the collection representation, scoped by real numbers rather than by these 51.

Within this plan, once the above has run:

- The **7 "source not found"** are worth one look first, because they may be **mis-filed rather than
  broken**: they are on `common_types.treedata` and `programmes.query`, the *base* types whose subtypes
  supply the 44 `OVERRIDING` rejections. §3.2 says an abstract method "is a skip, not an error —
  skip must mean 'nothing to do'". If these are abstract, the count is honest but the category is
  wrong, and the fix is small.
- The 38 syntax errors are the largest genuine bucket and are probably §13.3 in disguise; re-measure
  after step 4 rather than attacking them directly.
- **Phase 6 stays last.** Its 56 rejections remain the plan working as designed, and it is now
  competing against failures three orders of magnitude larger.

**Re-measure before committing to any of this.** §11's table did not predict what §12 found, and
neither predicted §13. This one is a measurement, not a forecast.

### 13.6 Items 1 and 3 — implemented 2026-08-28

Both are preconditions rather than fixes: neither makes a single object migrate, and item 3 will
make the *reported* numbers worse. What they buy is that the next run can be read.

**Item 1 — the failing SQL now survives the process.**

- `core/job/service/JobReportArchive` writes each finished job's full text report to
  `logs/reports/{yyyyMMdd-HHmmss}-{jobId}.txt`. Same document as `GET /api/jobs/{jobId}/report`,
  which now **falls back to the archived file** when the job is no longer in memory — previously a
  restart turned every report into a 404.
- **Hooked in `JobService.submitJob`, not in each job.** That is the one terminal point every job
  passes through, so a job type added later is archived without anyone remembering — the same
  argument that made `JobTextReportFormatter` walk the Jackson tree instead of switching on the
  result type. A **failed** job is archived too: its partial result holds the per-object errors up
  to the point it gave up.
- **`JobTextReportFormatter` grew a streaming `write(Appendable, …)`.** A step-28 report is
  hundreds of megabytes; building it as one `String` would fail precisely on the run worth
  capturing. `format(…)` still exists for the REST path and now delegates, so the two cannot drift.
- **Archiving never changes a job's outcome.** Every failure path is logged and swallowed, at two
  levels. A migration that succeeded must not be reported as failed because a disk write did not.
- Log rotation raised from 10 M × 5 to **50 M × 20**, which covers a whole run.
- `quarkus.log.category` named `me.christianrobert.ora2postgre` — a leftover from the package
  rename, so it matched nothing and **no `log.debug` call in the project had ever reached a
  handler**. Corrected to `orapgsync`, but deliberately left at **INFO**, not the DEBUG it claimed:
  the implementation jobs log every function's full Oracle source and generated SQL at debug, so
  "fixing" it to DEBUG would emit gigabytes and rotate the errors away faster than before. The
  archive is the structured answer to the same need.

**Item 3 — a failed transformation is an error, not a skip.**

`PostgresFunctionImplementationJob` routed failed transformations to `addSkippedFunction`. Now
`recordTransformationFailure`, which is package-private so the real routing decision can be driven
without a database, and which carries the invariant in its javadoc.

> **Expect the next run's step-28 error count to rise by roughly 3,965 and its "skipped" to go to
> zero.** Nothing got worse; that many failures were already there, reported as a neutral count.

`FunctionImplementationResult.addSkippedFunction` now has **no caller, deliberately**, and says so:
this job has no nothing-to-do case. If one appears it needs its own exception type first, the way
`AbstractTypeMethodException` keeps the two apart for type methods.

**Tests.** `JobReportArchiveTest` (9), `PostgresFunctionImplementationJobTest` (3), plus a
streaming-equals-in-memory case in `JobTextReportFormatterTest`. Suite green at **1925 tests across
174 classes**. Item 3's tests were verified by mutation: restoring `addSkippedFunction` fails all
three, each with a message naming the real defect.

**Verified against the running application, not only by unit test.** The packaged app was started,
a job submitted, the process killed, and a **fresh** process asked for that job's report: HTTP 200,
served from the archive, byte-identical to the live response apart from the `Generated:` line — and
the new process had no log record of the job. Negative controls: an unknown job id still returns
404, and a URL-encoded traversal attempt (`..%2F..%2Fetc%2Fpasswd`) returns 404 rather than a file.
This was worth doing: CDI wiring, `@ConfigProperty` resolution and flush-on-close are exactly what
a unit test with a hand-constructed bean does not exercise.

> **One test was rewritten because mutation showed it could not fail.** The first version of the
> path-safety test asserted that an unsafe job id reads nothing — which is true *whether or not the
> guard exists*, because `read` scans the directory's own entries and matches a name suffix rather
> than building a path. Deleting the guard left all 8 tests green. It now plants a file outside the
> archive directory and asserts no job id can reach it, which fails against the realistic
> regression (`dir.resolve(jobId + ".txt")`) — verified. The guard's javadoc now says plainly that
> it is defence in depth and that the suffix scan is what actually protects the directory.
> Exactly the trap Phase 2b and Phase 2a each recorded: an assertion that looks obviously related
> to the property can still hold under the bug.

## 14. Step 28 taxonomised — 2026-08-30

**§13.5's item 2 was never blocked.** It said "needs one more full run first — items 1 and 3 only
take effect on the next one". That run had *already happened* when §13 was written: there were two
runs on 2026-08-28, and §13 measured the morning one (step 29 at 09:33). The evening run
(16:20–20:06) ran with items 1 and 3 in place and archived every report. No migration has run since.

The archived step-28 report confirms §13.6's prediction almost exactly:

| | §13.2 predicted | 2026-08-28 evening, actual |
|---|---|---|
| implemented | 16,366 | **16,366** |
| skipped | → 0 | **0** |
| errors | 54,093 + 3,965 ≈ 58,058 | **58,062** |

> **Check `logs/reports/` before concluding a measurement needs a new run.** The archive is the
> instrument item 1 built; §13 was written the same day and still reasoned as though only
> `migration.log` existed.

### 14.1 The denominator is real — §13.2's caveat closed

56,209 distinct failing function names, **zero duplicates**; 16,046 distinct implemented. The
alternative explanation §13.2 could not exclude — `getOracleFunctionMetadata()` accumulating across
re-runs — is excluded: accumulation would repeat names. **74,428 is the true size of step 28.**

### 14.2 The taxonomy, and why it is not a taxonomy of PL/SQL

All 56,338 archived error entries, first line, normalised:

| Count | Error | Distinct packages |
|---|---|---|
| 14,727 | `syntax error at or near …` — 11,234 of them `"."` | 564 (for the `"."` group) |
| 14,579 | `type "X" does not exist` | — |
| 10,804 | `schema "X" does not exist` — 5,913 of them `"type"` | **300** (for the `"type"` group) |
| 3,314 | `relation "X" does not exist` — 1,645 `semester_def` | — |
| 919 | `cannot change return type of existing function` | — |
| 714 + 349 | `mismatched input '<EOF>'` | — |
| 301 | `table_collection_expression` (TABLE() operator) | — |

Look at the third column. **5,913 identical failures from 300 packages** — every member of each,
with the same message. Across the whole job: 56,338 failures over 3,582 packages, but only **8,562
distinct (package, error) pairs**. Failures are ~6.5× redundant at the package level.

By originating code path:

| Count | Share | Path |
|---|---|---|
| 43,581 | 77.4% | raw `ERROR: …` — **thrown out of `ensurePackageContext`** |
| 7,460 | 13.2% | `executeInPostgres` — a genuine per-function failure |
| 3,821 | 6.8% | transformation failure — genuine |
| 1,112 | 2.0% | `Failed to parse package spec` — **also `ensurePackageContext`** |

Confirmed in the logs rather than inferred: across all 21 rotations, **45,036 of 45,764** logged
"Error processing function" entries (98.4%) carry the frame `PostgresFunctionImplementationJob.java:161`
→ `:513`. On one file it is 2,624 of 2,624.

> **Step 28 is not 56,000 PL/SQL transformation failures.** It is on the order of a few hundred
> broken package contexts, each failing every member function of its package, re-attempted once per
> member. **79% of the job's failures are one root cause class, and ~20% are genuine per-function
> failures.** Any ranking of PL/SQL constructs taken from the raw error list is a ranking of
> whatever those few hundred packages happen to use.

### 14.3 Three defects, all in the same twenty lines

`ensurePackageContext` is called inside the per-function loop, for every member of every package.

1. **It threw, and the caller's generic catch recorded the exception against the *function*.** The
   package was never named. That is the 44,693.
2. **The cache was populated only on success** (`packageContextCache.put` sat *after* the helper
   execution loop), so a failing package was rebuilt from scratch for every member: Oracle spec
   re-queried, body re-parsed, helpers re-generated and re-executed. ~16× per package, and a large
   part of step 28's 2h51m.
3. **`addError(..., null)` discarded the SQL.** §13.4 states `addError` "does capture the failing
   SQL, faithfully, on every job" — on this path it explicitly passed `null`. Every one of the
   2026-08-28 entries reads `sql: (none)`, which is why §13.3's 28-failure hypothesis still could
   not be settled from an archive built precisely to settle it.

Separately, and found while counting: **the result maps lose records to key collisions.**
`errorCount` 58,062 against an `errors` map of 56,338; `implementedCount` 16,366 against 16,046.
Neither `getQualifiedName()` nor `getDisplayName()` carries the overload number, so Oracle overloads
share a key and the second `put` replaces the first — while the counter incremented anyway.
**2,044 records overwritten and advertised as present.**

### 14.4 The restructure — implemented 2026-08-30

`ensurePackageContext` **does not throw and attempts each package exactly once.** It returns `null`
when the context is usable and the reason otherwise.

- **One package error per package**, in a new `packageErrors` bucket carrying the rejected SQL —
  a property of the package's helper generation, not of any member.
- **Failures are cached** (`packageContextFailures`), the negative half of the existing cache.
- **Every helper statement is attempted** rather than aborting on the first, so the error describes
  the package (`"3 of 7 package variable helpers failed: …"`) instead of whichever helper was
  generated first. Safe because the connection is in autocommit — verified, `PostgresConnectionService`
  never calls `setAutoCommit(false)` — so a failed statement does not poison the ones after it.
- **Members are recorded as `blocked`, naming the package.** A distinct bucket, but **a failure, not
  a skip**: it counts against `isSuccessful()` and is named in the summary line, the log line and the
  UI. It exists only so one broken package cannot bury the ~20% genuine failures in the taxonomy.
- **Overload-aware result keys**, so counts can no longer exceed the evidence behind them.
- Frontend: `Blocked` and `Failed packages` stats, a **Failed Package Contexts** section rendered
  first as the actionable list, and a Blocked section. Without this the change would have moved
  44,693 failures from a visible bucket to an invisible one.

**Tests.** 8 added to `PostgresFunctionImplementationJobTest` (11 total). Suite green at **1,933
tests**. Verified by mutation, four separate ones, each failing with a message naming the real
defect: removing the negative cache makes the retry test fail with an NPE on the null connection it
should never have reached; restoring the overload-unaware key drops one of two overloads; reverting
`isSuccessful()` lets a fully-blocked run report success; restoring the unconditional counter
increment makes a count outrun its map.

### 14.5 What this does not do, and the next decision

**It implements no additional function.** The expected effect on the next run is that step 28's
16,366 implemented is roughly unchanged, its 58,062 errors split into ~11,000 genuine per-function
errors plus ~45,000 blocked, and a few hundred named package errors *with SQL* appear. Runtime
should drop noticeably.

**The open decision it sets up: should a failed package context block its members at all?** Blocking
is kept here because it is conservative — a function whose package helpers are missing would be
created successfully and fail at *call* time, which is the silent-corruption mode this project
refuses. But a member that never touches a package variable does not need the helpers, and blocking
it is pure loss. Deciding needs the per-package blocked counts this change produces, and probably a
check of whether the generated body actually references a missing helper. **Do not unblock
speculatively.**

Then, in order:

1. **Fix the concentrated helper-generation bugs** the package errors name. `schema "type" does not
   exist` (300 packages) and `syntax error at or near "."` (564 packages) are the two largest, and
   the archived SQL will now say what generated them. The `schema "X"` names read like Oracle
   *package* names (`uicomponent`, `docstore`, `webmodule`) and Oracle built-ins (`owa`, `utl`),
   which points at package-qualified type/routine references emitted as `schema.object` — one
   coherent family, ~19% of the job.
2. **Then collection types** (§13.3, CLAUDE.md item 4). They are visible in the data
   (`textlist3k` 562, `propmap` 527) but reached through the same broken path, so
   fixing step 7 first would not be measurable.

---

## 15. Fourth full-run measurement — 2026-08-30

§14.4's restructure ran. Every prediction it made held, and the archived reports were readable for
the first time, so the whole pipeline could be taxonomised rather than just step 28.

| | §14.4 predicted | actual |
|---|---|---|
| implemented | ~16,366 | **16,366** |
| genuine per-function errors | ~11,000 | **12,074** |
| blocked | ~45,000 | **45,988** |
| named package errors *carrying SQL* | "a few hundred" | **1,890** |
| step 28 runtime | "drop noticeably" | **2h51m → 59m** |

16,366 + 12,074 + 45,988 = 74,428, with the 1,890 package errors as a separate bucket keyed by
package rather than by member. §14.1's denominator holds unchanged.

### 15.1 The speed-up is not a win

The whole run went 3h46m → 1h54m and **all** of it is step 28; every other step is unchanged to the
second (view implementation 17.6 min both runs, type methods 1m36s both runs). The cause is exactly
what §14.4 changed: package-context failures are cached, and blocked members are no longer
transformed. Previously each of ~46,000 blocked members re-queried Oracle for the spec, re-parsed
the body, regenerated the helpers and re-executed ~10 failing `CREATE FUNCTION`s, roughly 16 times
per package.

**So the run is fast because it stopped doing 46,000 futile attempts, not because anything got
better.** When the package defects below are fixed, runtime goes back up as ~30,000 functions are
genuinely attempted for the first time. A future reader comparing wall-clock times across runs must
not read this drop as a performance improvement.

### 15.2 Step 23 is at 62%, and nobody had measured it

**46,066 created / 28,362 errors of 74,428.** The module table in CLAUDE.md carried "✅ stubs" for
`function/`. This is the same defect §13.2 found in the transformation ratio — *a ratio nobody
measured against a real run is not a status* — one level further up the pipeline, and it matters
more than its position suggests: a view implemented at step 27 can only reference functions whose
**stubs** exist, so 1,242 of the 2,053 view failures (60%) are `function X(...) does not exist`.

| Cause | Count | Mechanism |
|---|---|---|
| type-name resolution in stub signatures | **17,339 (61%)** | `PUBLIC`-owned types emitted as PostgreSQL's `public` schema; package types emitted as `owner.package`; `%TYPE` anchors flattened and unqualified |
| `RETURNS pl/sql boolean` | **9,516 (34%)** | `ALL_ARGUMENTS.DATA_TYPE` spells a package member's BOOLEAN as the literal `PL/SQL BOOLEAN`; 11,689 occurrences across returns and parameters |
| signature disagreement, cross-database refs, misc | ~1,500 | |

### 15.3 The package errors, named at last

§14.5 asked for the SQL and got it. 1,890 broken package contexts block 45,951 members — 96% from
two defects:

| Cause | Packages | Functions blocked | Mechanism |
|---|---|---|---|
| `PackageHelperGenerator` → `TypeConverter` | ~1,067 | **29,705 (65%)** | `type "propmap" does not exist` (446), `schema "type" does not exist` (496), `relation "semester_def" does not exist` (125) |
| package body parse errors ignored | ~680 | **14,781 (32%)** | `RETURNS .` (554), `RETURNS when` (101) |
| package **spec** parse failure (wrapped packages) | 84 | 1,170 | |

§14.5's guess was half right. It read the `schema "X"` names (`uicomponent`, `webmodule`) as
package-qualified references — correct — but predicted `syntax error at or near "."` would be the
same family. It is not. It is **ANTLR error-recovery debris**: `buildPackageContext` logged a body
parse failure and continued "best effort", and `extractBodyVariables` then walked a tree containing
invented nodes. Reproduced against the real parser: `gitemtab order.tTable;` yields a variable *named*
`.` of type `tTable`, and a declaration the recovery slid into an exception handler yields a
variable named `exception` of type `when`. **Those variables do not exist in Oracle.** The generated
DDL was invented from a failure that had already been detected and thrown away.

`type "propmap" does not exist` is **not** the collection-type defect of §13.3. Step 7 created
`main_app.propmap`; it was referenced unqualified. Collections remain silently broken —
step 7 reports 248 created / 0 errors and the list contains `common_types.textlist3k`,
`common_types.numberlist` — but they are not what these errors are about, and §13.3 must not be
credited with them.

### 15.4 The genuine per-function errors are mostly not PL/SQL either

Of 12,074: **3,405** `relation "X" does not exist` (unqualified `%TYPE` anchors), **1,226**
`type "X" does not exist`, **~1,788** signature disagreement (`cannot change return type` 949,
`function result type must be Y because of OUT parameters` 796, and friends), **2,236** parse
errors — of which **1,950 are `mismatched input '<EOF>'`** and 220 `missing 'FUNCTION'/'PROCEDURE'`.

That last line is the important one: **~97% of the parse failures are boundary-scanner truncation,
not PL/SQL coverage.** Phase 2b predicted exactly this shape. The same defect shows up on the other
side — the reduced package bodies that fail to parse (§15.3) fail on `else`, `then`, `elsif` and
bare assignments, i.e. statement tails the scanner left behind when it mis-detected a function end.
**One defect, two symptoms, 1,950 functions and 1,070 packages.**

The actual PL/SQL long tail is small by comparison: BULK COLLECT 226, inquiry directives 168,
chained method calls 90, `CURRENT OF` 39. §13's instruction to decide the long tail with real
failure data rather than intuition is now answerable, and the answer is "not yet".

### 15.5 Implemented 2026-08-30

Four changes, one argument: **a type is emitted through `TypeResolver`, and a detected failure is
never built on.**

1. **`TypeConverter`: `PL/SQL BOOLEAN` → `boolean`, `REF CURSOR` → `refcursor`.** The dictionary's
   spellings, not the source's. `PL/SQL TABLE`/`PL/SQL RECORD` are deliberately *not* mapped — they
   name package-declared types, which only metadata can place, and a guess here would be invisible.
   `TypeConverter` also gained `toPostgreOrNull`, the same switch, so a caller can tell "mapped"
   from "passed through" without comparing strings (several built-ins map to themselves).

2. **`PostgresFunctionStubCreationJob` routes all three type-emission sites through `TypeResolver`**,
   building `TransformationIndices` once and one `TransformationContext` per schema — the same
   inputs the implementation side uses, which is what makes the stub signature and the
   implementation signature one answer instead of two derived independently. It **refuses** a stub
   whose type cannot be placed, naming the type, rather than emitting the bare Oracle name.

3. **`PackageHelperGenerator` takes a `TransformationContext`** and refuses a package whose variable
   type cannot be placed. The package's own context is added to the resolution map before use, so a
   variable naming its own package's type resolves.

4. **`buildPackageContext` throws on a package body parse error** instead of logging and continuing.
   Cost measured before the change: of the 1,070 packages whose body failed to parse, **1,018 were
   already failing** on one of the invented helpers; only 52 lose a context, and that context was
   built from the same debris.

Supporting changes, each needed to make the above produce a *right* answer rather than a
consistently wrong one:

- **Package `SUBTYPE`s are extracted.** The grammar has carried `subtype_declaration` in
  `package_obj_spec` all along; the extractor read only variables and TYPE declarations, so every
  `pkg.subtype` fell through `resolvePackageQualifiedType` to its "unknown, assume jsonb" branch.
  `TYPE.STRING` is `VARCHAR2` — 1,339 parameter occurrences, and the largest single named package
  error at 295 packages. Subtypes are kept in their own map, *not* in the inline-type map, because
  an alias is not a structure.
- **`PackageTypeResolution` now records whether it came from metadata.** The visitor layer still
  accepts the jsonb fallback — it is mid-expression and must emit something — but a signature must
  not, because the other side of the `CREATE OR REPLACE` may guess differently and the disagreement
  surfaces as `cannot change return type`, the silent-overwrite family.
- **`%TYPE` anchors keep their anchor and gain the table's schema.** `semester_def.nr%TYPE` →
  `academic.semester_def.nr%TYPE`. Verified against the archive that PostgreSQL accepts `%TYPE` in a
  signature here: 3,162 failures whose SQL contains `%type` report `relation "X" does not exist`,
  i.e. the parser accepted it and name resolution failed. `%ROWTYPE` is deliberately left
  unresolved — 15 failures report `syntax error at or near "rowtype"`, so it is *not* accepted, and
  its jsonb spelling is a declaration-site decision this layer does not make.
- **`ALL_ARGUMENTS.TYPE_SUBNAME` is read.** Without it a package type composes to
  `owner.package`, naming a package as though it were a type. With it, the pair composes to
  `package.type` — the spelling the source uses, and the one `TransformationContext` already
  resolves.
- **The package-private extraction path no longer splits a `%TYPE` type_spec** on the dot, which
  dropped the `%TYPE` and turned "the type of this column" into a type that does not exist.

Tests: +41, suite 1,925 → 1,966, all green. `PostgresFunctionStubTypeResolutionTest` drives the
real signature generator against hand-built metadata; `PackageBodyParseRefusalTest` pins the
recovery-debris mechanism, so if ANTLR ever stops inventing that variable the test says so rather
than silently passing.

### 15.6 What this does not fix, and what to measure next

**Package types are still unplaceable in the stub job**, because that job has no `PackageContext` at
all — so they are now *refused with a named error* instead of emitted as a broken name. The
implementation job is not much better: it builds a context only for the package it is currently
transforming, so a reference to *another* package's type resolves differently depending on
processing order. **Both sides should read package spec types and subtypes from one index built
during step 22 extraction and stored in `StateService`.** That is the next item, worth ~4,000–5,000
stub failures, and it also removes an order-dependent answer from step 28.

Also found and deliberately not fixed:

- **`getExistingPostgresFunctions` keys on `pg_proc.proname`**, so on a re-run into a non-empty
  database every overload after the first is silently skipped — the exact pre-check CLAUDE.md warns
  against re-adding. It did not fire on these runs because the target starts empty.
- **`TypeMethodImplementationResult` keys its buckets by name in a `Map` while counting
  separately**, so the 2026-08-30 report lists 1,110 implemented + 220 errors against counts of
  1,335 + 255. **260 overloaded type methods have no recoverable per-object record** — the archive
  is lossy for exactly the objects hardest to reason about.
- **`resolvePackageQualifiedType`'s unknown-package warning goes to `System.err`**, which reaches no
  report and no log.

**Reading the next run.** Step 23 should move sharply off 62% — the `PL/SQL BOOLEAN` mapping alone
addresses 9,516 — and a fall there should pull view implementation up from 63% without anything in
the view transformer changing. Step 28's blocked count should fall by roughly the 29,705 attributed
to helper type resolution, *and its runtime should rise*, because those members will be genuinely
attempted for the first time. New named errors will appear in the stub job (`cannot resolve Oracle
type 'X'`) and in the package errors (`Package body failed to parse: …`); both are re-attributions,
not regressions. If the boundary-scanner defect is still untouched, expect ~1,950 `<EOF>` parse
errors and ~1,070 refused packages to persist unchanged — that is the number that ranks Phase 2b.

---

## 16. Package spec type index — implemented 2026-09-01

§15.6's next item. **The types a package declares are now extracted once, at step 22, and read by
every job that has to place a `pkg.type` reference.**

### 16.1 What was wrong

Two jobs need the same answer and neither had it.

- **The stub job had no `PackageContext` at all** — it builds signatures, not bodies, so it never
  had a reason to construct one. Every `pkg.type` was therefore unplaceable, and after §15.5 that
  meant *refused*: correct, loud, and still no stub.
- **The implementation job had one, for the wrong scope.** It builds a context for the package it
  is currently transforming and caches it, so a reference to *another* package's type resolved only
  if that package happened to have been processed earlier in the same schema. `type.string`
  resolved to `text` or fell back to `jsonb` **depending on processing order** — an answer that
  changes between runs and cannot be reconstructed from the report.

This is the "two routes to one construct" shape again, in its worst form: not two routes giving
different answers consistently, but one route giving different answers at different times.

### 16.2 The shape

`ALL_SOURCE` is streamed a second time for `type = 'PACKAGE'`, one spec at a time exactly as the
body pass does, each spec parsed and reduced to a `PackageTypeMetadata`:

- **subtype name → the Oracle type it aliases**, so `type.string` is `VARCHAR2` and emits `text`;
- **the names of RECORD / TABLE OF / VARRAY / INDEX BY declarations**, because every one of them is
  `jsonb` and a signature needs nothing more.

It is stored in `StateService`, indexed by `MetadataIndexBuilder` into `TransformationIndices`, and
read by `TransformationContext.resolvePackageQualifiedType`. **Both jobs get it without either job
changing**, which is the point: they build indices through the same builder, so there is no way for
one to have the index and the other not.

Three decisions worth keeping:

- **No field lists.** A structured type is carried as a name. A signature needs only `jsonb`, and
  carrying `InlineTypeDefinition` into `core/` would invert the module dependency —
  `core/` has no reference to `transformer/` and this must not be the first. The consequence is
  explicit: a variable declared as *another* package's RECORD still gets `jsonb` without an
  initializer, exactly as before. Only the enclosing package's own `PackageContext` carries fields,
  and only a declaration site needs them.
- **An unparseable spec is skipped, not fatal.** This pass builds an index for other jobs; a
  package missing from it degrades to precisely the pre-index behaviour — unplaceable, and the
  caller refuses by name. Failing extraction here would take the whole function job down for the
  wrapped packages. Contrast §15.5 item 4, where the parse result was about to be *used*: there,
  refusing is mandatory. **The rule is not "always throw on a parse error", it is "never build on
  one".**
- **The existing `PackageContext` path is left in front of the index.** For the package being
  transformed the two agree — both come from the same spec through the same extractor — and the
  local context additionally carries field lists. The index answers everything it cannot.

### 16.3 What it does not do

**Step 28 still queries and parses each package spec itself**, because it needs package *variables*
and the index deliberately carries only types. That is a duplicated parse, not a second source of
truth: the redundancy costs time, it cannot produce a different answer. Removing it means indexing
variables too, which is a larger change and a different question — variables drive helper
generation, which is transformation state rather than metadata.

Also unchanged: a bare **cross-schema** type reference, and Oracle **collection** types, which have
no composite type in the target at all (item 4).

### 16.4 Measuring it

Tests +17, suite 1,966 → 1,983. Verified by mutation: forcing the index lookup to return null kills
6 of them, including all three "same answer whatever else is loaded" cases.

**On the next run**, expect the stub job's `cannot resolve Oracle type 'X'` errors to fall by
roughly the package-qualified share — `type.*`, `uicomponent.*`, `global.*`, `typ.*` and friends,
about 4,000–5,000 of the 17,339 type-resolution failures §15.2 measured. Step 22 will take somewhat
longer, since it now parses every package spec in the database rather than none; the parse is small
relative to the body scanning that dominates that step, but it is not free. Step 28's package
errors should lose their `schema "type" does not exist` family.

**If the stub count does not move**, the thing to check first is whether the specs parsed at all:
the log line `Indexed package spec types for schema X: N packages declare types, M specs did not
parse` is per schema, and a large M means the spec grammar, not the index, is the constraint.
