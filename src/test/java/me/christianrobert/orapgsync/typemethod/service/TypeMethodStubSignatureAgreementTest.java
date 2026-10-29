package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodKind;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase 5 invariant, tested directly: <b>the stub signature and the implementation signature are
 * the same signature.</b>
 *
 * <p>Step 25 creates the stub from {@code ALL_TYPE_METHODS} rows; step 29 replaces it with
 * {@code CREATE OR REPLACE FUNCTION} built from the type body's parse tree. The two derivations
 * share no code and cannot — different inputs, different shapes — so nothing but a test keeps them
 * in step. Nothing did, and they drifted on four separate points: routine kind, SELF's mode for
 * member procedures, the RETURNS clause of a procedure, and the name of a constructor. The first
 * alone cost 449 of the 1081 failures on the 2026-08-27 run.
 *
 * <p>Each case therefore builds <em>both</em> sides from the same Oracle method and compares the
 * text up to {@code LANGUAGE plpgsql}. Only the body differs by design — that is what step 29
 * exists to replace.
 */
class TypeMethodStubSignatureAgreementTest {

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
                // Attribute list: the constructor prologue seeds SELF with one NULL per attribute.
                Map.of("hr.demo_item_t",
                        Map.of("item_code", "VARCHAR2", "qty", "NUMBER", "unit_price", "NUMBER")),
                Set.of("hr.demo_item_t"));
    }

    /** What step 29 will emit for this method. */
    private String emitted(String oracleSource) {
        ParseResult parseResult = parser.parseTypeMethod(oracleSource);
        if (parseResult.hasErrors()) {
            fail("Parse failed: " + parseResult.getErrors());
        }
        TransformationContext context = new TransformationContext(
                "hr", indices, new SimpleTypeEvaluator("hr", indices),
                null, null, null, "demo_item_t");
        return new PostgresCodeBuilder(context).visit(parseResult.getTree());
    }

    /**
     * The part PostgreSQL identifies the function by, plus the RETURNS clause it refuses to change:
     * everything before {@code LANGUAGE plpgsql}.
     */
    private String signatureOf(String sql) {
        int end = sql.indexOf("LANGUAGE plpgsql");
        assertTrue(end > 0, "no LANGUAGE plpgsql in:\n" + sql);
        return sql.substring(0, end).trim().replaceAll("\\s+", " ");
    }

    private void assertSignaturesAgree(TypeMethodMetadata method, String oracleSource) {
        String stub = signatureOf(TypeMethodStubSqlBuilder.createStubSql(method));
        String implementation = signatureOf(emitted(oracleSource));

        assertEquals(implementation, stub,
                "stub and implementation signatures differ -- step 29 would create a second "
                        + "function or fail instead of replacing the stub");
    }

    // ---------- fixtures ----------

    private static TypeMethodMetadata method(TypeMethodKind kind, String name) {
        TypeMethodMetadata m = new TypeMethodMetadata("hr", "demo_item_t", name, "PUBLIC");
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

    // ---------- the coverage matrix, both sides ----------

    @Test
    @DisplayName("MEMBER FUNCTION")
    void memberFunction() {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_FUNCTION, "line_total");
        m.setReturnDataType("NUMBER");
        m.setSelfParamMode("IN");

        assertSignaturesAgree(m, """
                MEMBER FUNCTION line_total RETURN number IS
                BEGIN
                  RETURN 1;
                END;
                """);
    }

    @Test
    @DisplayName("MEMBER FUNCTION with a parameter")
    void memberFunctionWithParameter() {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_FUNCTION, "describe");
        m.setReturnDataType("VARCHAR2");
        m.setSelfParamMode("IN");
        m.addParameter(param("p_separator", 2, "VARCHAR2", "IN"));

        assertSignaturesAgree(m, """
                MEMBER FUNCTION describe(p_separator varchar2) RETURN varchar2 IS
                BEGIN
                  RETURN p_separator;
                END;
                """);
    }

    /** The mode difference that breaks CREATE OR REPLACE on the return type. */
    @Test
    @DisplayName("MEMBER PROCEDURE — INOUT self, and the RETURNS that follows from it")
    void memberProcedure() {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_PROCEDURE, "add_qty");
        m.setSelfParamMode("IN OUT");
        m.addParameter(param("p_qty", 2, "NUMBER", "IN"));

        assertSignaturesAgree(m, """
                MEMBER PROCEDURE add_qty(p_qty number) IS
                BEGIN
                  NULL;
                END;
                """);
    }

    @Test
    @DisplayName("MEMBER PROCEDURE with an OUT parameter — two outputs, so RETURNS RECORD")
    void memberProcedureWithOutParameter() {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_PROCEDURE, "apply_discount");
        m.setSelfParamMode("IN OUT");
        m.addParameter(param("p_pct", 2, "NUMBER", "IN"));
        m.addParameter(param("p_new_total", 3, "NUMBER", "OUT"));

        assertSignaturesAgree(m, """
                MEMBER PROCEDURE apply_discount(p_pct number, p_new_total OUT NOCOPY number) IS
                BEGIN
                  NULL;
                END;
                """);
    }

    @Test
    @DisplayName("STATIC FUNCTION")
    void staticFunction() {
        TypeMethodMetadata m = method(TypeMethodKind.STATIC_FUNCTION, "make_code");
        m.setReturnDataType("VARCHAR2");
        m.addParameter(param("p_raw", 1, "VARCHAR2", "IN"));

        assertSignaturesAgree(m, """
                STATIC FUNCTION make_code(p_raw varchar2) RETURN varchar2 IS
                BEGIN
                  RETURN p_raw;
                END;
                """);
    }

    /**
     * PostgreSQL rejects a default before a required input parameter, which Oracle allows; the
     * implementation keeps only the trailing run, the same rule as the function side (P1.2).
     */
    @Test
    @DisplayName("STATIC FUNCTION — only trailing defaults survive")
    void staticFunctionDefaults() {
        String sql = emitted("""
                STATIC FUNCTION make_code(p_prefix varchar2 DEFAULT 'X', p_raw varchar2, p_len number := 8)
                  RETURN varchar2 IS
                BEGIN
                  RETURN p_prefix || p_raw;
                END;
                """);

        assertTrue(signatureOf(sql).contains("(p_prefix text, p_raw text, p_len numeric DEFAULT 8)"), sql);
    }

    @Test
    @DisplayName("STATIC PROCEDURE — no outputs at all, so RETURNS void")
    void staticProcedure() {
        TypeMethodMetadata m = method(TypeMethodKind.STATIC_PROCEDURE, "describe_all");
        m.addParameter(param("p_left", 1, "NUMBER", "IN"));

        assertSignaturesAgree(m, """
                STATIC PROCEDURE describe_all(p_left number) IS
                BEGIN
                  NULL;
                END;
                """);
    }

    /** Named after the type, per plan section 5 — the stub used to name it type__type. */
    @Test
    @DisplayName("CONSTRUCTOR — named after its type and returning it")
    void constructor() {
        TypeMethodMetadata m = method(TypeMethodKind.CONSTRUCTOR, "demo_item_t");
        m.setSelfParamMode("IN OUT");
        m.setCustomReturnType(true);
        m.setReturnTypeOwner("hr");
        m.setReturnTypeName("demo_item_t");
        m.addParameter(param("p_code", 2, "VARCHAR2", "IN"));

        assertSignaturesAgree(m, """
                CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2) RETURN SELF AS RESULT IS
                BEGIN
                  RETURN;
                END;
                """);
    }

    @Test
    @DisplayName("MAP MEMBER FUNCTION")
    void mapMemberFunction() {
        TypeMethodMetadata m = new TypeMethodMetadata("hr", "demo_item_t", "sort_key", "MAP");
        m.setKind(TypeMethodKind.MAP_FUNCTION);
        m.setMethodNo("1");
        m.setInstantiable("YES");
        m.setSelfParamMode("IN");
        m.setReturnDataType("NUMBER");

        assertSignaturesAgree(m, """
                MAP MEMBER FUNCTION sort_key RETURN number IS
                BEGIN
                  RETURN 1;
                END;
                """);
    }

    @Test
    @DisplayName("ORDER MEMBER FUNCTION with an object type parameter")
    void orderMemberFunctionWithObjectParameter() {
        TypeMethodMetadata m = new TypeMethodMetadata("hr", "demo_item_t", "compare_to", "ORDER");
        m.setKind(TypeMethodKind.ORDER_FUNCTION);
        m.setMethodNo("1");
        m.setInstantiable("YES");
        m.setSelfParamMode("IN");
        m.setReturnDataType("INTEGER");

        TypeMethodParameter other = new TypeMethodParameter("p_other", 2, "hr.demo_item_t");
        other.setInOut("IN");
        other.setCustomDataType(true);
        other.setDataTypeOwner("hr");
        other.setDataTypeName("demo_item_t");
        m.addParameter(other);

        assertSignaturesAgree(m, """
                ORDER MEMBER FUNCTION compare_to(p_other demo_item_t) RETURN integer IS
                BEGIN
                  RETURN 0;
                END;
                """);
    }
}
