package me.christianrobert.orapgsync.transformer.typemethod;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transformation tests for object type methods — type-method plan Phase 2.
 *
 * <p>Drives the real {@code AntlrParser.parseTypeMethod} and the real visitor layer, so these
 * cover the entry point whose absence made orchestration step 29 implement nothing while
 * reporting success.
 *
 * <p>Two groups, and the second matters as much as the first: the rejection tests assert that
 * untransformable constructs <b>throw</b>. Under the plan's total-dispatch rule an unhandled
 * construct is a bug rather than a gap, so "does it fail loudly" is a correctness property here,
 * not defensive decoration.
 */
class TypeMethodTransformationTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        indices = new TransformationIndices(
                new HashMap<>(),
                new HashMap<>(),
                new HashSet<>(),
                new HashMap<>(),
                // The type's attributes. A constructor's prologue seeds SELF with one NULL per
                // attribute, so the transformer genuinely needs them -- and an implicit attribute
                // reference is resolved against this same index.
                java.util.Map.of("hr.demo_item_t", java.util.Map.of(
                        "item_code", "VARCHAR2", "qty", "NUMBER", "unit_price", "NUMBER")),
                // demo_item_t is a migrated object type, so parameters typed with it must come out
                // schema-qualified as the composite type PostgresObjectTypeCreationJob created.
                java.util.Set.of("hr.demo_item_t")
        );
    }

    private String transform(String typeMethodSource) {
        return transform(typeMethodSource, indices);
    }

    private String transform(String typeMethodSource, TransformationIndices indices) {
        ParseResult parseResult = parser.parseTypeMethod(typeMethodSource);
        if (parseResult.hasErrors()) {
            fail("Parse failed: " + parseResult.getErrors());
        }
        TransformationContext context = new TransformationContext(
                "hr",
                indices,
                new SimpleTypeEvaluator("hr", indices),
                null,
                null,
                null,
                "demo_item_t");
        return new PostgresCodeBuilder(context).visit(parseResult.getTree());
    }

    private String flat(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    // ========== member and static methods ==========

    @Test
    @DisplayName("MEMBER FUNCTION takes SELF as its first parameter, by value")
    void memberFunction_selfIsFirstParameter() {
        String result = flat(transform("""
                MEMBER FUNCTION line_total RETURN number IS
                BEGIN
                  RETURN 1;
                END;
                """));

        assertTrue(result.contains("CREATE OR REPLACE FUNCTION hr.demo_item_t__line_total(self hr.demo_item_t)"),
                "got: " + result);
        assertTrue(result.contains("RETURNS numeric"), "got: " + result);
    }

    @Test
    @DisplayName("MEMBER PROCEDURE takes SELF as INOUT, because Oracle lets it mutate the instance")
    void memberProcedure_selfIsInout() {
        String result = flat(transform("""
                MEMBER PROCEDURE add_qty(p_qty number) IS
                BEGIN
                  NULL;
                END;
                """));

        assertTrue(result.contains("hr.demo_item_t__add_qty(INOUT self hr.demo_item_t, p_qty numeric)"),
                "got: " + result);
        // One INOUT parameter and no OUT parameters, so PostgreSQL dictates the return type.
        assertTrue(result.contains("RETURNS hr.demo_item_t"), "got: " + result);
    }

    @Test
    @DisplayName("STATIC FUNCTION has no SELF at all")
    void staticFunction_hasNoSelf() {
        String result = flat(transform("""
                STATIC FUNCTION make_code(p_raw varchar2) RETURN varchar2 IS
                BEGIN
                  RETURN p_raw;
                END;
                """));

        assertTrue(result.contains("hr.demo_item_t__make_code(p_raw text)"), "got: " + result);
        assertFalse(result.contains("self"), "static method must not take self, got: " + result);
    }

    @Test
    @DisplayName("STATIC PROCEDURE returns void")
    void staticProcedure_returnsVoid() {
        String result = flat(transform("""
                STATIC PROCEDURE log_it(p_msg varchar2) IS
                BEGIN
                  NULL;
                END;
                """));

        assertTrue(result.contains("RETURNS void"), "got: " + result);
    }

    @Test
    @DisplayName("OUT NOCOPY drops the hint and keeps the mode")
    void outNocopyParameter_dropsHint() {
        String result = flat(transform("""
                MEMBER PROCEDURE apply_discount(p_pct number, p_new_total OUT NOCOPY number) IS
                BEGIN
                  NULL;
                END;
                """));

        assertTrue(result.contains("OUT p_new_total numeric"), "got: " + result);
        assertFalse(result.toUpperCase().contains("NOCOPY"),
                "NOCOPY is a hint with no PostgreSQL equivalent and must be dropped, got: " + result);
    }

    // ========== object type names are schema-qualified, on every route ==========

    /**
     * An object type must come out schema-qualified wherever it is written, because that is the
     * name {@code PostgresObjectTypeCreationJob} gave the composite type. An unqualified emission
     * resolves only when {@code search_path} happens to cover the schema, which during migration it
     * does not.
     *
     * <p>Phase 2 established this for {@code type_elements_parameter} and for the return type. The
     * <b>declare section</b> is a third route to the same construct, and the 2026-08-27 run shows it
     * answers differently: {@code type "linkurl" does not exist}, 184 times, dominated by a method
     * naming its own type. The same gap hits standalone and package functions, which share this
     * visitor — the {@code table_element} blind-spot shape again, one rule over.
     */
    @Test
    @DisplayName("A local variable of an object type is schema-qualified")
    void localVariableOfObjectType_isQualified() {
        String result = flat(transform("""
                MEMBER FUNCTION copy_of RETURN number IS
                  l_copy demo_item_t;
                BEGIN
                  RETURN 1;
                END;
                """));

        assertTrue(result.contains("l_copy hr.demo_item_t;"),
                "a local of a migrated object type must name the composite type the DDL migration "
                        + "created, got: " + result);
    }

    /**
     * The control for the test above, and the reason the diagnosis is a declare-section bug rather
     * than a return-type one: the fluent-setter shape that dominates the failing methods
     * ({@code linkurl.withBase}, {@code url.withCallback}) returns its own type, and that route
     * already qualifies. Without this test the two routes are indistinguishable from the log alone.
     */
    @Test
    @DisplayName("A return type naming the object type is schema-qualified")
    void returnTypeOfObjectType_isQualified() {
        String result = flat(transform("""
                MEMBER FUNCTION with_qty(p_qty number) RETURN demo_item_t IS
                BEGIN
                  RETURN self;
                END;
                """));

        assertTrue(result.contains("RETURNS hr.demo_item_t"), "got: " + result);
    }

    // ========== constructors ==========

    @Test
    @DisplayName("CONSTRUCTOR keeps the Oracle type name and returns its own type")
    void constructor_keepsTypeName() {
        String result = flat(transform("""
                CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2) RETURN SELF AS RESULT IS
                BEGIN
                  RETURN;
                END;
                """));

        assertTrue(result.contains("CREATE OR REPLACE FUNCTION hr.demo_item_t(p_code text)"),
                "constructor keeps the type name so undetected call sites still resolve, got: " + result);
        assertTrue(result.contains("RETURNS hr.demo_item_t"), "got: " + result);
    }

    @Test
    @DisplayName("An explicit SELF IN OUT slot is not rendered as an ordinary parameter")
    void constructor_explicitSelfIsNotAParameter() {
        String result = flat(transform("""
                CONSTRUCTOR FUNCTION demo_item_t(SELF IN OUT NOCOPY demo_item_t,
                                                 p_code varchar2,
                                                 p_qty number) RETURN SELF AS RESULT IS
                BEGIN
                  RETURN;
                END;
                """));

        // Same signature as the implicit form: SELF becomes an injected local, never a parameter.
        assertTrue(result.contains("hr.demo_item_t(p_code text, p_qty numeric)"), "got: " + result);
        assertFalse(result.contains("self hr.demo_item_t,"),
                "explicit SELF must not become a parameter, got: " + result);
    }

    // ========== SELF: Phase 3 ==========

    /**
     * The assignment used to become {@code self := jsonb_set(self, '{qty}', …)}, which PL/pgSQL
     * accepts at CREATE time and fails on at call time — so the job reported the method implemented
     * and it broke in production. {@code self} being in scope was not enough:
     * {@code VisitAssignment_statement} had to be made to ask.
     */
    @Test
    @DisplayName("SELF field assignment is a plain composite assignment, not jsonb_set")
    void selfFieldAssignment_isNotJsonb() {
        String result = flat(transform("""
                MEMBER PROCEDURE add_qty(p_qty number) IS
                BEGIN
                  self.qty := nvl(self.qty, 0) + p_qty;
                END;
                """));

        assertFalse(result.contains("jsonb_set"), "got: " + result);
        assertTrue(result.contains("self . qty := COALESCE( self . qty , 0 ) + p_qty"), "got: " + result);
    }

    @Test
    @DisplayName("A bare attribute name resolves to SELF — Oracle's implicit receiver")
    void bareAttribute_resolvesToSelf() {
        String result = flat(transform("""
                MEMBER FUNCTION gross RETURN number IS
                BEGIN
                  RETURN qty * unit_price;
                END;
                """));

        assertTrue(result.contains("RETURN self.qty * self.unit_price"), "got: " + result);
    }

    @Test
    @DisplayName("A bare attribute works as an assignment target too")
    void bareAttribute_asAssignmentTarget() {
        String result = flat(transform("""
                MEMBER PROCEDURE reset IS
                BEGIN
                  qty := 0;
                END;
                """));

        assertTrue(result.contains("self.qty := 0"), "got: " + result);
    }

    /**
     * {@code table_element} is the alternative the grammar names <b>first</b> for the argument of
     * {@code TO_CHAR}, {@code TO_DATE} and {@code COALESCE}, so an attribute reference there never
     * reaches {@code VisitGeneralElement}. Both routes must answer the same — the blind-spot rule
     * in CLAUDE.md.
     */
    @Test
    @DisplayName("A bare attribute resolves the same through the table_element route")
    void bareAttribute_resolvesThroughTableElement() {
        String result = flat(transform("""
                MEMBER FUNCTION label RETURN varchar2 IS
                BEGIN
                  RETURN to_char(qty);
                END;
                """));

        assertTrue(result.contains("self.qty"),
                "TO_CHAR's argument arrives via table_element and must resolve too, got: " + result);
    }

    @Test
    @DisplayName("A parameter of the same name shadows the attribute, as in Oracle")
    void parameterShadowsAttribute() {
        String result = flat(transform("""
                MEMBER FUNCTION echo(qty number) RETURN number IS
                BEGIN
                  RETURN qty;
                END;
                """));

        assertTrue(result.contains("RETURN qty;"), "got: " + result);
        assertFalse(result.contains("self.qty"), "the parameter must win, got: " + result);
    }

    /**
     * A STATIC method has no instance, so a bare name cannot be an attribute of one. Rewriting it
     * would invent a receiver that does not exist; leaving it alone lets PL/pgSQL say so.
     */
    @Test
    @DisplayName("A STATIC method never resolves a bare name to SELF")
    void staticMethod_doesNotResolveAttributes() {
        String result = flat(transform("""
                STATIC FUNCTION stray RETURN number IS
                BEGIN
                  RETURN qty;
                END;
                """));

        assertFalse(result.contains("self"), "a static method has no instance, got: " + result);
    }

    @Test
    @DisplayName("A name that is not an attribute is left exactly as it was")
    void unknownBareName_isUntouched() {
        String result = flat(transform("""
                MEMBER FUNCTION stray RETURN number IS
                  v_local number;
                BEGIN
                  RETURN v_local;
                END;
                """));

        assertTrue(result.contains("RETURN v_local;"), "got: " + result);
        assertFalse(result.contains("self.v_local"), "got: " + result);
    }

    // ========== element assignment on a SELF attribute: plan section 12.2 ==========

    /**
     * Indices where the type carries a <b>collection-typed attribute</b>, as every type behind the
     * 2026-08-27 §12.2 failures does ({@code paramset.mslots}, {@code row_list.vrowstash},
     * {@code propmap}'s four {@code pm_*_array} attributes).
     */
    private String transformWithCollectionAttribute(String typeMethodSource) {
        TransformationIndices withCollection = new TransformationIndices(
                new HashMap<>(),
                new HashMap<>(),
                new HashSet<>(),
                new HashMap<>(),
                java.util.Map.of("hr.demo_item_t", java.util.Map.of(
                        "item_code", "VARCHAR2", "qty", "NUMBER", "unit_price", "NUMBER",
                        "tags", "TEXTLIST3K")),
                java.util.Set.of("hr.demo_item_t"));
        return flat(transform(typeMethodSource, withCollection));
    }

    /**
     * {@code tags(1) := …} on an attribute must <b>throw</b>, not become {@code jsonb_set}.
     *
     * <p>This is the §12.2 failure — 43 methods reported {@code "<attr>" is not a known variable} —
     * but resolving the name is only half the fix, and the wrong half on its own.
     * {@code tryTransformCollectionElementAssignment} emitted the base name verbatim from the parse
     * tree <i>and</i> assumed it was jsonb. Rewriting the name to {@code self.tags} while keeping
     * {@code jsonb_set} would trade a loud CREATE-time error for
     * {@code jsonb_set(composite, …) does not exist} at <i>call</i> time — exactly the silent
     * corruption Phase 3 fixed one branch over.
     *
     * <p><b>An object type attribute is never an inline type.</b> {@code RECORD} and
     * {@code TABLE OF} are declared inside packages and routines; an attribute always names a
     * schema-level Oracle type. So the jsonb assumption can never hold here, and since Oracle
     * collection types have no PostgreSQL representation, the honest outcome is a named rejection.
     */
    @Test
    @DisplayName("Element assignment to a collection attribute is rejected, not emitted as jsonb_set")
    void collectionElementAssignmentOnAttribute_isRejected() {
        TransformationException e = assertThrows(TransformationException.class, () ->
                transformWithCollectionAttribute("""
                        MEMBER PROCEDURE add_tag(p_tag varchar2) IS
                        BEGIN
                          tags(1) := p_tag;
                        END;
                        """));

        assertTrue(e.getMessage().contains("tags"),
                "the rejection must name the attribute, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("TEXTLIST3K"),
                "the rejection must name the attribute's Oracle type, so the next run's log says "
                        + "which types need a representation, got: " + e.getMessage());
    }

    /**
     * The control that keeps the rejection narrow: a local of a genuinely inline collection type is
     * jsonb, and its element assignment must keep working exactly as before. Only positive metadata
     * — "this name is an attribute of the current type" — changes behaviour.
     */
    @Test
    @DisplayName("Element assignment to a local inline collection is still jsonb_set")
    void collectionElementAssignmentOnInlineLocal_isUnchanged() {
        String result = transformWithCollectionAttribute("""
                MEMBER PROCEDURE fill IS
                  TYPE t_nums IS TABLE OF number INDEX BY binary_integer;
                  v_nums t_nums;
                BEGIN
                  v_nums(1) := 100;
                END;
                """);

        assertTrue(result.contains("v_nums := jsonb_set( v_nums , '{ 0 }' , to_jsonb( 100 ) )"),
                "an inline collection local is jsonb and must be untouched, got: " + result);
    }

    // ========== constructors: Phase 3 ==========

    @Test
    @DisplayName("A constructor declares SELF as a local and returns it from a bare RETURN")
    void constructor_injectsSelfAndReturnsIt() {
        String result = flat(transform("""
                CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2) RETURN SELF AS RESULT IS
                BEGIN
                  self.item_code := p_code;
                  RETURN;
                END;
                """));

        assertTrue(result.contains("DECLARE self hr.demo_item_t := ROW(NULL, NULL, NULL)::hr.demo_item_t;"),
                "got: " + result);
        assertTrue(result.contains("RETURN self;"), "got: " + result);
    }

    /**
     * The rewrite belongs to the constructor, not to every {@code RETURN;} — a member procedure's
     * bare RETURN still means "return nothing", and its function has OUT parameters, where PL/pgSQL
     * forbids returning an expression.
     */
    @Test
    @DisplayName("A bare RETURN outside a constructor is left alone")
    void bareReturn_outsideConstructor_isUnchanged() {
        String result = flat(transform("""
                MEMBER PROCEDURE stop_early IS
                BEGIN
                  RETURN;
                END;
                """));

        assertTrue(result.contains("RETURN;"), "got: " + result);
        assertFalse(result.contains("RETURN self;"), "got: " + result);
    }

    // ========== MAP / ORDER ==========

    @Test
    @DisplayName("MAP MEMBER FUNCTION becomes an ordinary member function")
    void mapMember_becomesOrdinaryFunction() {
        String result = flat(transform("""
                MAP MEMBER FUNCTION sort_key RETURN number IS
                BEGIN
                  RETURN 1;
                END;
                """));

        assertTrue(result.contains("hr.demo_item_t__sort_key(self hr.demo_item_t)"), "got: " + result);
    }

    @Test
    @DisplayName("ORDER MEMBER FUNCTION is implemented, not deferred")
    void orderMember_isImplemented() {
        String result = flat(transform("""
                ORDER MEMBER FUNCTION compare_to(p_other demo_item_t) RETURN number IS
                BEGIN
                  RETURN 0;
                END;
                """));

        assertTrue(result.contains("hr.demo_item_t__compare_to(self hr.demo_item_t, p_other hr.demo_item_t)"),
                "got: " + result);
    }

    // ========== rejections (plan section 3.2) ==========

    @Test
    @DisplayName("OVERRIDING is rejected on the subprog_decl_in_type route")
    void overriding_rejectedViaModifierClause() {
        // The ordinary form: ANTLR takes the lower-numbered alternative, so this never reaches
        // overriding_subprogram_spec. Guarding only that rule would let this through.
        TransformationException e = assertThrows(TransformationException.class, () -> transform("""
                OVERRIDING MEMBER FUNCTION describe RETURN varchar2 IS
                BEGIN
                  RETURN 'x';
                END;
                """));
        assertTrue(e.getMessage().contains("OVERRIDING"), "got: " + e.getMessage());
    }

    @Test
    @DisplayName("OVERRIDING is rejected on the overriding_subprogram_spec route too")
    void overriding_rejectedViaOverridingSpec() {
        // RETURN SELF AS RESULT is accepted only by overriding_function_spec -- the second route.
        TransformationException e = assertThrows(TransformationException.class, () -> transform("""
                OVERRIDING MEMBER FUNCTION describe RETURN SELF AS RESULT IS
                BEGIN
                  RETURN;
                END;
                """));
        assertTrue(e.getMessage().contains("OVERRIDING"), "got: " + e.getMessage());
    }

    @Test
    @DisplayName("A Java call_spec body is rejected by an explicit check, since it parses clean")
    void javaCallSpec_isRejected() {
        TransformationException e = assertThrows(TransformationException.class, () -> transform(
                "MEMBER FUNCTION describe RETURN varchar2 IS LANGUAGE JAVA NAME 'Foo.bar()';"));
        assertTrue(e.getMessage().contains("call spec"), "got: " + e.getMessage());
    }

    @Test
    @DisplayName("REF in a parameter type is rejected")
    void refParameter_isRejected() {
        TransformationException e = assertThrows(TransformationException.class, () -> transform("""
                MEMBER FUNCTION describe(p_ref REF demo_item_t) RETURN varchar2 IS
                BEGIN
                  RETURN 'x';
                END;
                """));
        assertTrue(e.getMessage().contains("REF"), "got: " + e.getMessage());
    }

    @Test
    @DisplayName("An ODCI interface method is rejected")
    void odciMethod_isRejected() {
        TransformationException e = assertThrows(TransformationException.class, () -> transform("""
                STATIC FUNCTION ODCIAggregateInitialize(sctx IN OUT demo_item_t) RETURN number IS
                BEGIN
                  RETURN 1;
                END;
                """));
        assertTrue(e.getMessage().contains("ODCI"), "got: " + e.getMessage());
    }
}
