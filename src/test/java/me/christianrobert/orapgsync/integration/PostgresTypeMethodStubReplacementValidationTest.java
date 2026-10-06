package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodKind;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.typemethod.service.TypeMethodStubSqlBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two-phase migration for type methods, executed against a real PostgreSQL — plan Phase 5.
 *
 * <p>Step 25 creates a stub, step 29 replaces it with {@code CREATE OR REPLACE FUNCTION}. String
 * comparison can show the two signatures are equal (see {@code TypeMethodStubSignatureAgreementTest})
 * but only PostgreSQL can confirm what equality <em>means</em> here: that the second statement
 * replaces the first rather than creating a second overload beside it or refusing outright.
 *
 * <p>Every test therefore asserts {@code exactly one function of this name exists} after both
 * statements. That is the assertion the 2026-08-27 run needed: 449 methods failed with
 * {@code cannot change routine kind} because step 25 had created a PROCEDURE, and no test executed
 * both halves in sequence to notice.
 */
public class PostgresTypeMethodStubReplacementValidationTest extends PostgresSqlValidationTestBase {

    private static final String TYPE = "demo_item_t";

    @BeforeEach
    void setupSchemaAndIndices() throws SQLException {
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("""
            CREATE TYPE hr.demo_item_t AS (
                item_code   text,
                qty         numeric,
                unit_price  numeric
            )
            """);

        // demo_item_t is a migrated object type, so parameters typed with it come out qualified.
        indices = new TransformationIndices(
                new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashMap<>(),
                Map.of("hr.demo_item_t",
                        Map.of("item_code", "VARCHAR2", "qty", "NUMBER", "unit_price", "NUMBER")),
                Set.of("hr.demo_item_t"));
    }

    // ---------- helpers ----------

    private static TypeMethodMetadata method(TypeMethodKind kind, String name) {
        TypeMethodMetadata m = new TypeMethodMetadata("hr", TYPE, name, "PUBLIC");
        m.setKind(kind);
        m.setMethodNo("1");
        m.setInstantiable("YES");
        return m;
    }

    private static TypeMethodParameter param(String name, int position, String type, String mode) {
        TypeMethodParameter p = new TypeMethodParameter(name, position, type);
        p.setInOut(mode);
        return p;
    }

    /** Creates the stub, then replaces it exactly as step 29 does. */
    private void createStubThenImplement(TypeMethodMetadata metadata, String oracleSource)
            throws SQLException {
        String stubSql = TypeMethodStubSqlBuilder.createStubSql(metadata);
        try {
            executeUpdate(stubSql);
        } catch (SQLException e) {
            throw new AssertionError("stub DDL is not valid PostgreSQL:\n" + stubSql, e);
        }

        TransformationResult transformed =
                transformationService.transformTypeMethod(oracleSource, "hr", TYPE, indices);
        assertTrue(transformed.isSuccess(),
                "transformation failed: " + transformed.getErrorMessage());

        try {
            executeUpdate(transformed.getPostgresSql());
        } catch (SQLException e) {
            throw new AssertionError(
                    "the implementation did not replace the stub.\n--- stub ---\n" + stubSql
                            + "\n--- implementation ---\n" + transformed.getPostgresSql(), e);
        }
    }

    /**
     * How many functions of this name exist in {@code hr}. Two means the implementation created an
     * overload beside the stub instead of replacing it — which leaves the stub reachable and
     * returning NULL, with nothing reporting a problem.
     */
    private int functionCount(String functionName) throws SQLException {
        List<Map<String, Object>> rows = executeQuery(
                "SELECT count(*) AS n FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'hr' AND p.proname = '" + functionName + "'");
        return ((Number) rows.get(0).get("n")).intValue();
    }

    private Object scalar(String sql) throws SQLException {
        List<Map<String, Object>> rows = executeQuery(sql);
        assertEquals(1, rows.size(), "expected exactly one row from: " + sql);
        return rows.get(0).values().iterator().next();
    }

    // ---------- member function ----------

    @Test
    @DisplayName("MEMBER FUNCTION: the implementation replaces the stub and computes for real")
    void memberFunctionStubIsReplaced() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_FUNCTION, "line_total");
        m.setReturnDataType("NUMBER");
        m.setSelfParamMode("IN");

        // The stub answers NULL before the implementation lands -- that is what makes it a stub.
        executeUpdate(TypeMethodStubSqlBuilder.createStubSql(m));
        assertNull(scalar("SELECT hr.demo_item_t__line_total(ROW('a', 3, 10)::hr.demo_item_t)"),
                "the stub should return NULL");

        createStubThenImplement(m, """
                MEMBER FUNCTION line_total RETURN number IS
                BEGIN
                  RETURN nvl(self.qty, 0) * nvl(self.unit_price, 0);
                END;
                """);

        assertEquals(1, functionCount("demo_item_t__line_total"),
                "the implementation must replace the stub, not add an overload");
        assertEquals(0, ((Number) scalar(
                "SELECT hr.demo_item_t__line_total(ROW('a', 3, 10)::hr.demo_item_t) - 30")).intValue(),
                "the implemented function should multiply qty by unit_price");
    }

    // ---------- member procedure: the 449-failure shape ----------

    /**
     * The exact shape that failed 449 times. The stub was a PROCEDURE and the implementation a
     * FUNCTION, so PostgreSQL refused with {@code cannot change routine kind}; and even as a
     * FUNCTION, plain {@code self} against {@code INOUT self} changes the result type and is
     * refused too.
     */
    @Test
    @DisplayName("MEMBER PROCEDURE: INOUT self survives from stub to implementation")
    void memberProcedureStubIsReplaced() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_PROCEDURE, "note_price");
        m.setSelfParamMode("IN OUT");
        m.addParameter(param("p_note", 2, "VARCHAR2", "IN"));
        m.addParameter(param("p_out", 3, "VARCHAR2", "OUT"));

        // Two outputs -- INOUT self and p_out -- so this also exercises the RETURNS RECORD path.
        createStubThenImplement(m, """
                MEMBER PROCEDURE note_price(p_note varchar2, p_out OUT varchar2) IS
                BEGIN
                  p_out := p_note || self.item_code;
                END;
                """);

        assertEquals(1, functionCount("demo_item_t__note_price"));

        // SELF goes in and comes back out through the INOUT parameter, unchanged here.
        // A function with OUT parameters already names its result columns.
        List<Map<String, Object>> rows = executeQuery(
                "SELECT * FROM hr.demo_item_t__note_price(ROW('abc', 3, 10)::hr.demo_item_t, 'note-')");
        assertEquals(1, rows.size());
        assertEquals("note-abc", rows.get(0).get("p_out"));
        assertEquals("(abc,3,10)", String.valueOf(rows.get(0).get("self")));
    }

    /**
     * Composite field assignment on SELF — Phase 3, and the failure shape that most needed
     * executing rather than reading.
     *
     * <p>Until 2026-08-27 this emitted {@code self := jsonb_set(self, '{qty}', …)} against a
     * composite, because {@code VisitAssignment_statement} transformed <em>any</em> dotted left-hand
     * side without consulting the scope tracker. Registering {@code self} in Phase 2 did not fix it,
     * although plan section 4 said it would — the visitor never asked.
     *
     * <p><b>PL/pgSQL resolves function bodies at call time</b>, so the CREATE succeeded and the job
     * reported the method implemented; only a call revealed it. That is why this test calls the
     * function rather than stopping at the CREATE.
     */
    @Test
    @DisplayName("MEMBER PROCEDURE assigning to SELF: the field assignment actually mutates it")
    void memberProcedureAssigningToSelf() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_PROCEDURE, "add_qty");
        m.setSelfParamMode("IN OUT");
        m.addParameter(param("p_qty", 2, "NUMBER", "IN"));

        createStubThenImplement(m, """
                MEMBER PROCEDURE add_qty(p_qty number) IS
                BEGIN
                  self.qty := nvl(self.qty, 0) + nvl(p_qty, 0);
                END;
                """);

        assertEquals(1, functionCount("demo_item_t__add_qty"));

        Object qty = scalar(
                "SELECT (hr.demo_item_t__add_qty(ROW('a', 3, 10)::hr.demo_item_t, 4)).qty");
        assertEquals(7, ((Number) qty).intValue(),
                "the member procedure should have modified SELF and returned it");
    }

    // ---------- Phase 3: implicit SELF and the constructor ----------

    /**
     * Oracle grants a member body implicit access to its type's attributes, so {@code qty} with no
     * prefix means {@code self.qty}. PostgreSQL has no implicit receiver, and PL/pgSQL rejects the
     * function at CREATE time with {@code "qty" is not a known variable} — around 50 failures on
     * the 2026-08-27 run.
     */
    @Test
    @DisplayName("implicit SELF: a bare attribute name resolves to the instance")
    void implicitSelfAttributeReference() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_FUNCTION, "gross");
        m.setReturnDataType("NUMBER");
        m.setSelfParamMode("IN");

        createStubThenImplement(m, """
                MEMBER FUNCTION gross RETURN number IS
                BEGIN
                  RETURN qty * unit_price;
                END;
                """);

        assertEquals(30, ((Number) scalar(
                "SELECT hr.demo_item_t__gross(ROW('a', 3, 10)::hr.demo_item_t)")).intValue());
    }

    /**
     * A parameter of the same name shadows the attribute in Oracle, and must here too — otherwise
     * the method silently reads the instance where the caller passed a value.
     */
    @Test
    @DisplayName("implicit SELF: a parameter of the same name wins")
    void parameterShadowsAttribute() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_FUNCTION, "echo_qty");
        m.setReturnDataType("NUMBER");
        m.setSelfParamMode("IN");
        m.addParameter(param("qty", 2, "NUMBER", "IN"));

        createStubThenImplement(m, """
                MEMBER FUNCTION echo_qty(qty number) RETURN number IS
                BEGIN
                  RETURN qty;
                END;
                """);

        assertEquals(99, ((Number) scalar(
                "SELECT hr.demo_item_t__echo_qty(ROW('a', 3, 10)::hr.demo_item_t, 99)")).intValue(),
                "the parameter must win over the same-named attribute");
    }

    /**
     * A local variable typed with the method's own object type, end to end.
     *
     * <p>The largest single cause of failure on the 2026-08-27 run: <b>184 methods</b> failed with
     * {@code type "X" does not exist}, where X was almost always the method's own type, written
     * unqualified. {@code VisitVariable_declaration} sent the type straight to {@code TypeConverter},
     * which has no metadata and returns unrecognised names unchanged — so the composite type
     * {@code hr.demo_item_t} was referenced as bare {@code demo_item_t}, resolvable only if
     * {@code search_path} happened to cover the schema.
     *
     * <p>A string-comparison test pins the emitted name, but only execution proves that name is the
     * one the DDL migration actually created — which is the whole claim.
     */
    @Test
    @DisplayName("a local of the method's own object type resolves against the migrated composite")
    void localVariableOfOwnObjectType() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_FUNCTION, "doubled_qty");
        m.setReturnDataType("NUMBER");
        m.setSelfParamMode("IN");

        createStubThenImplement(m, """
                MEMBER FUNCTION doubled_qty RETURN number IS
                  l_copy demo_item_t;
                BEGIN
                  l_copy := self;
                  l_copy.qty := l_copy.qty * 2;
                  RETURN l_copy.qty;
                END;
                """);

        assertEquals(1, functionCount("demo_item_t__doubled_qty"));
        assertEquals(6, ((Number) scalar(
                "SELECT hr.demo_item_t__doubled_qty(ROW('a', 3, 10)::hr.demo_item_t)")).intValue(),
                "the local must be a real composite of the migrated type, not a bare name");
    }

    /**
     * The constructor, end to end: SELF is an injected local rather than a parameter, and a bare
     * {@code RETURN;} returns it. Without the first, PL/pgSQL refuses the CREATE with
     * {@code "self" is not a known variable} (66 failures); without the second, with
     * {@code missing expression at or near ";"} (33 failures).
     */
    @Test
    @DisplayName("CONSTRUCTOR: SELF is an injected local and a bare RETURN returns it")
    void constructorPopulatesAndReturnsSelf() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.CONSTRUCTOR, TYPE);
        m.setSelfParamMode("IN OUT");
        m.setCustomReturnType(true);
        m.setReturnTypeOwner("hr");
        m.setReturnTypeName(TYPE);
        m.addParameter(param("p_code", 2, "VARCHAR2", "IN"));
        m.addParameter(param("p_qty", 3, "NUMBER", "IN"));

        createStubThenImplement(m, """
                CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2, p_qty number)
                    RETURN SELF AS RESULT IS
                BEGIN
                  self.item_code := upper(p_code);
                  self.qty := nvl(p_qty, 0);
                  RETURN;
                END;
                """);

        assertEquals(1, functionCount(TYPE));
        assertEquals("(ABC,7,)", String.valueOf(scalar("SELECT hr.demo_item_t('abc', 7)")),
                "the constructor should return the instance it populated");
    }

    /**
     * The seed in the constructor prologue, settled by execution — the open question in plan
     * section 4. PostgreSQL is happy to assign to a field of an <em>uninitialised</em> composite, so
     * the seed is not needed for the ordinary case. It is needed for this one: a constructor that
     * takes a path assigning nothing must still return an instance, as Oracle does, not NULL.
     */
    @Test
    @DisplayName("CONSTRUCTOR: an unassigned path still returns an instance, not NULL")
    void constructorWithoutAssignmentReturnsAnInstance() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.CONSTRUCTOR, TYPE);
        m.setSelfParamMode("IN OUT");
        m.setCustomReturnType(true);
        m.setReturnTypeOwner("hr");
        m.setReturnTypeName(TYPE);
        m.addParameter(param("p_code", 2, "VARCHAR2", "IN"));

        createStubThenImplement(m, """
                CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2) RETURN SELF AS RESULT IS
                BEGIN
                  IF p_code IS NOT NULL THEN
                    self.item_code := p_code;
                  END IF;
                  RETURN;
                END;
                """);

        assertEquals("(,,)", String.valueOf(scalar("SELECT hr.demo_item_t(NULL)")),
                "an all-NULL instance, not a NULL composite -- this is what the ROW(NULL, ...) "
                        + "seed in the prologue buys");
    }

    // ---------- static methods ----------

    @Test
    @DisplayName("STATIC FUNCTION: no SELF, stub replaced")
    void staticFunctionStubIsReplaced() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.STATIC_FUNCTION, "make_code");
        m.setReturnDataType("VARCHAR2");
        m.addParameter(param("p_raw", 1, "VARCHAR2", "IN"));

        createStubThenImplement(m, """
                STATIC FUNCTION make_code(p_raw varchar2) RETURN varchar2 IS
                BEGIN
                  RETURN upper(p_raw);
                END;
                """);

        assertEquals(1, functionCount("demo_item_t__make_code"));
        assertEquals("ABC", scalar("SELECT hr.demo_item_t__make_code('abc')"));
    }

    /**
     * {@code RETURNS void} with a bare {@code RETURN} in the stub body. PL/pgSQL rejects
     * {@code RETURN NULL} here, so this also pins the stub-body rule.
     */
    @Test
    @DisplayName("STATIC PROCEDURE: RETURNS void, stub replaced")
    void staticProcedureStubIsReplaced() throws SQLException {
        executeUpdate("CREATE TABLE hr.audit_log (message text)");

        TypeMethodMetadata m = method(TypeMethodKind.STATIC_PROCEDURE, "log_it");
        m.addParameter(param("p_message", 1, "VARCHAR2", "IN"));

        createStubThenImplement(m, """
                STATIC PROCEDURE log_it(p_message varchar2) IS
                BEGIN
                  INSERT INTO audit_log (message) VALUES (p_message);
                END;
                """);

        assertEquals(1, functionCount("demo_item_t__log_it"));
        executeUpdate("SELECT hr.demo_item_t__log_it('hello')");
        assertEquals("hello", scalar("SELECT message FROM hr.audit_log"));
    }

    // ---------- overloads ----------

    /**
     * Two overloads must produce two functions. The removed "already exists" pre-check was keyed on
     * the bare name, so from the second run on, everything after the first overload was reported as
     * already created and never appeared.
     */
    @Test
    @DisplayName("overloads coexist, and re-running stub creation is idempotent")
    void overloadsCoexistAndStubCreationIsRepeatable() throws SQLException {
        TypeMethodMetadata noArg = method(TypeMethodKind.MEMBER_FUNCTION, "calculate_bonus");
        noArg.setReturnDataType("NUMBER");
        noArg.setSelfParamMode("IN");

        TypeMethodMetadata oneArg = method(TypeMethodKind.MEMBER_FUNCTION, "calculate_bonus");
        oneArg.setMethodNo("2");
        oneArg.setReturnDataType("NUMBER");
        oneArg.setSelfParamMode("IN");
        oneArg.addParameter(param("p_multiplier", 2, "NUMBER", "IN"));

        executeUpdate(TypeMethodStubSqlBuilder.createStubSql(noArg));
        executeUpdate(TypeMethodStubSqlBuilder.createStubSql(oneArg));
        assertEquals(2, functionCount("demo_item_t__calculate_bonus"),
                "both overloads should exist as distinct functions");

        // A second run must replace both in place rather than duplicating or skipping them.
        executeUpdate(TypeMethodStubSqlBuilder.createStubSql(noArg));
        executeUpdate(TypeMethodStubSqlBuilder.createStubSql(oneArg));
        assertEquals(2, functionCount("demo_item_t__calculate_bonus"));
    }

    // ---------- constructor ----------

    /**
     * The constructor <em>stub</em> is valid and callable under the type's own name. Replacing it is
     * Phase 3 work: the body needs the injected {@code self} local and the {@code RETURN;} rewrite,
     * without which PL/pgSQL rejects it at CREATE time. This test pins the half that is done, so the
     * naming decision (plan section 5) is executed rather than only asserted as a string.
     */
    @Test
    @DisplayName("CONSTRUCTOR: the stub is created under the type's own name")
    void constructorStubUsesTheTypeName() throws SQLException {
        TypeMethodMetadata m = method(TypeMethodKind.CONSTRUCTOR, TYPE);
        m.setSelfParamMode("IN OUT");
        m.setCustomReturnType(true);
        m.setReturnTypeOwner("hr");
        m.setReturnTypeName(TYPE);
        m.addParameter(param("p_code", 2, "VARCHAR2", "IN"));

        executeUpdate(TypeMethodStubSqlBuilder.createStubSql(m));

        assertEquals(1, functionCount(TYPE),
                "the constructor must be created as hr.demo_item_t, not hr.demo_item_t__demo_item_t");
        assertEquals(0, functionCount(TYPE + "__" + TYPE));
        assertNull(scalar("SELECT hr.demo_item_t('abc')"), "the constructor stub returns NULL");
    }
}
