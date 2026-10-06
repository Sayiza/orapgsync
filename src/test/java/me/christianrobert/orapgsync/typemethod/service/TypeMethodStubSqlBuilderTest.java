package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodKind;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stub DDL generation — plan Phase 5.
 *
 * <p>Every assertion here is ultimately about one property: the stub's signature must be the one the
 * transformer will later replace. {@code TypeMethodStubSignatureAgreementTest} checks that against
 * the emitter directly; these tests pin the individual rules that make it true, and the shape of the
 * body, which the emitter has no say in.
 */
class TypeMethodStubSqlBuilderTest {

    // ---------- fixtures ----------

    private static TypeMethodMetadata method(TypeMethodKind kind, String name) {
        TypeMethodMetadata m = new TypeMethodMetadata("hr", "demo_item_t", name, "PUBLIC");
        m.setKind(kind);
        m.setMethodNo("1");
        m.setInstantiable("YES");
        return m;
    }

    private static TypeMethodMetadata function(TypeMethodKind kind, String name, String returnType) {
        TypeMethodMetadata m = method(kind, name);
        m.setReturnDataType(returnType);
        return m;
    }

    private static TypeMethodParameter param(String name, int position, String type, String mode) {
        TypeMethodParameter p = new TypeMethodParameter(name, position, type);
        p.setInOut(mode);
        return p;
    }

    private static TypeMethodParameter objectParam(String name, int position,
                                                   String owner, String typeName, String mode) {
        TypeMethodParameter p = new TypeMethodParameter(name, position, owner + "." + typeName);
        p.setInOut(mode);
        p.setCustomDataType(true);
        p.setDataTypeOwner(owner);
        p.setDataTypeName(typeName);
        return p;
    }

    private static String flat(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    // ---------- routine kind ----------

    /**
     * The 449-failure case. A method classified as a procedure was emitted as
     * {@code CREATE OR REPLACE PROCEDURE} while the transformer emits a FUNCTION, so step 29 failed
     * on every one of them with {@code cannot change routine kind}.
     */
    @Test
    @DisplayName("a procedure is still created as a FUNCTION")
    void procedureIsCreatedAsFunction() {
        String sql = TypeMethodStubSqlBuilder.createStubSql(
                method(TypeMethodKind.STATIC_PROCEDURE, "describe_all"));

        assertTrue(sql.startsWith("CREATE OR REPLACE FUNCTION hr.demo_item_t__describe_all("), sql);
        assertFalse(sql.contains("CREATE OR REPLACE PROCEDURE"), sql);
        assertTrue(flat(sql).contains("RETURNS void"), sql);
    }

    // ---------- SELF ----------

    @Test
    @DisplayName("MEMBER FUNCTION takes SELF by value as its first parameter")
    void memberFunctionSelfIsByValue() {
        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(
                function(TypeMethodKind.MEMBER_FUNCTION, "line_total", "NUMBER")));

        assertTrue(sql.contains("hr.demo_item_t__line_total(self hr.demo_item_t)"), sql);
        assertTrue(sql.contains("RETURNS numeric"), sql);
    }

    /**
     * Oracle gives a member procedure {@code SELF IN OUT} implicitly — that is what lets
     * {@code obj.set_x(1)} modify {@code obj}. Emitting plain {@code self} changes the function's
     * result type, so the implementation's CREATE OR REPLACE fails on the return type rather than
     * replacing the stub.
     */
    @Test
    @DisplayName("MEMBER PROCEDURE takes SELF as INOUT, and returns the type because of it")
    void memberProcedureSelfIsInout() {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_PROCEDURE, "add_qty");
        m.addParameter(param("p_qty", 2, "NUMBER", "IN"));

        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(m));

        assertTrue(sql.contains("hr.demo_item_t__add_qty(INOUT self hr.demo_item_t, p_qty numeric)"), sql);
        assertTrue(sql.contains("RETURNS hr.demo_item_t"), sql);
    }

    @Test
    @DisplayName("STATIC methods have no SELF at all")
    void staticMethodHasNoSelf() {
        TypeMethodMetadata m = function(TypeMethodKind.STATIC_FUNCTION, "make_code", "VARCHAR2");
        m.addParameter(param("p_raw", 1, "VARCHAR2", "IN"));

        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(m));

        assertTrue(sql.contains("hr.demo_item_t__make_code(p_raw text)"), sql);
        assertFalse(sql.contains("self"), sql);
    }

    // ---------- constructors ----------

    /**
     * Constructors keep the Oracle type name and rely on native overloading (plan section 5). The
     * stub used {@code getPostgresName()} like every other method, producing
     * {@code demo_item_t__demo_item_t} — a name the transformer never emits, so the stub was dead
     * and the implementation created a second, unstubbed function.
     */
    @Test
    @DisplayName("a constructor is named after its type, not type__method")
    void constructorIsNamedAfterItsType() {
        TypeMethodMetadata m = method(TypeMethodKind.CONSTRUCTOR, "demo_item_t");
        m.setCustomReturnType(true);
        m.setReturnTypeOwner("hr");
        m.setReturnTypeName("demo_item_t");
        m.addParameter(param("p_code", 2, "VARCHAR2", "IN"));

        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(m));

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION hr.demo_item_t(p_code text)"), sql);
        assertTrue(sql.contains("RETURNS hr.demo_item_t"), sql);
        assertFalse(sql.contains("demo_item_t__demo_item_t"), sql);
        // SELF is an injected local in a constructor, never a parameter.
        assertFalse(sql.contains("self hr.demo_item_t"), sql);
    }

    // ---------- RETURNS derivation for procedures ----------

    @Test
    @DisplayName("a static procedure with one OUT parameter returns that parameter's type")
    void staticProcedureWithOneOutParameter() {
        TypeMethodMetadata m = method(TypeMethodKind.STATIC_PROCEDURE, "compute");
        m.addParameter(param("p_in", 1, "NUMBER", "IN"));
        m.addParameter(param("p_out", 2, "VARCHAR2", "OUT"));

        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(m));

        assertTrue(sql.contains("hr.demo_item_t__compute(p_in numeric, OUT p_out text)"), sql);
        assertTrue(sql.contains("RETURNS text"), sql);
    }

    @Test
    @DisplayName("several OUT parameters make it RETURNS RECORD")
    void severalOutParametersReturnRecord() {
        TypeMethodMetadata m = method(TypeMethodKind.MEMBER_PROCEDURE, "apply_discount");
        m.addParameter(param("p_pct", 2, "NUMBER", "IN"));
        m.addParameter(param("p_new_total", 3, "NUMBER", "OUT"));

        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(m));

        // INOUT self counts as an output, so this method has two.
        assertTrue(sql.contains("RETURNS RECORD"), sql);
    }

    /**
     * PL/pgSQL rejects {@code RETURN <expr>} in a function with OUT parameters and requires a bare
     * {@code RETURN} for {@code RETURNS void}. A stub that gets this wrong does not compile, so the
     * signature work would be undone by the body.
     */
    @Test
    @DisplayName("the stub body suits its own signature")
    void stubBodyMatchesTheSignature() {
        assertTrue(flat(TypeMethodStubSqlBuilder.createStubSql(
                        function(TypeMethodKind.MEMBER_FUNCTION, "line_total", "NUMBER")))
                        .contains("BEGIN RETURN NULL;"),
                "a plain function may return an expression");

        assertTrue(flat(TypeMethodStubSqlBuilder.createStubSql(
                        method(TypeMethodKind.MEMBER_PROCEDURE, "add_qty")))
                        .contains("BEGIN RETURN;"),
                "INOUT self is an output parameter, so RETURN takes no expression");

        assertTrue(flat(TypeMethodStubSqlBuilder.createStubSql(
                        method(TypeMethodKind.STATIC_PROCEDURE, "noop")))
                        .contains("BEGIN RETURN;"),
                "RETURNS void takes a bare RETURN");
    }

    // ---------- identity signatures and collisions ----------

    @Test
    @DisplayName("the identity signature carries input types only, so an OUT parameter cannot distinguish two methods")
    void identitySignatureExcludesOutParameters() {
        TypeMethodMetadata withOut = method(TypeMethodKind.STATIC_PROCEDURE, "compute");
        withOut.addParameter(param("p_in", 1, "NUMBER", "IN"));
        withOut.addParameter(param("p_out", 2, "VARCHAR2", "OUT"));

        assertEquals("hr.demo_item_t__compute(numeric)",
                TypeMethodStubSqlBuilder.identitySignature(withOut));
    }

    /**
     * Oracle distinguishes VARCHAR2 from CHAR; the type mapping does not. Left alone, the second
     * {@code CREATE OR REPLACE} silently overwrites the first and one method ends up holding the
     * other's body — no error anywhere.
     */
    @Test
    @DisplayName("overloads that collapse onto one PostgreSQL signature are detected")
    void collidingOverloadsAreDetected() {
        TypeMethodMetadata varchar2 = function(TypeMethodKind.STATIC_FUNCTION, "make_code", "VARCHAR2");
        varchar2.addParameter(param("p_raw", 1, "VARCHAR2", "IN"));
        varchar2.setMethodNo("1");

        TypeMethodMetadata chr = function(TypeMethodKind.STATIC_FUNCTION, "make_code", "VARCHAR2");
        chr.addParameter(param("p_raw", 1, "CHAR", "IN"));
        chr.setMethodNo("2");

        List<TypeMethodStubSqlBuilder.SignatureCollision> collisions =
                TypeMethodStubSqlBuilder.findSignatureCollisions(List.of(varchar2, chr));

        assertEquals(1, collisions.size(), "expected one collision group");
        assertEquals(2, collisions.get(0).methods().size());
        assertEquals("hr.demo_item_t__make_code(text)", collisions.get(0).signature());
        // The message must name both Oracle methods; "there is a collision" is not actionable.
        assertTrue(collisions.get(0).describe().contains("method_no 1"), collisions.get(0).describe());
        assertTrue(collisions.get(0).describe().contains("method_no 2"), collisions.get(0).describe());
    }

    @Test
    @DisplayName("genuinely distinct overloads do not collide")
    void distinctOverloadsDoNotCollide() {
        TypeMethodMetadata noArg = function(TypeMethodKind.MEMBER_FUNCTION, "calculate_bonus", "NUMBER");
        TypeMethodMetadata oneArg = function(TypeMethodKind.MEMBER_FUNCTION, "calculate_bonus", "NUMBER");
        oneArg.setMethodNo("2");
        oneArg.addParameter(param("p_multiplier", 2, "NUMBER", "IN"));

        assertTrue(TypeMethodStubSqlBuilder.findSignatureCollisions(List.of(noArg, oneArg)).isEmpty());
    }

    @Test
    @DisplayName("a member method and a static method of the same name do not collide")
    void memberAndStaticOfTheSameNameDoNotCollide() {
        TypeMethodMetadata member = function(TypeMethodKind.MEMBER_FUNCTION, "label", "VARCHAR2");
        TypeMethodMetadata statik = function(TypeMethodKind.STATIC_FUNCTION, "label", "VARCHAR2");
        statik.setMethodNo("2");

        // The member method carries SELF as its first argument, so the signatures differ.
        assertTrue(TypeMethodStubSqlBuilder.findSignatureCollisions(List.of(member, statik)).isEmpty());
    }

    // ---------- object type parameters ----------

    @Test
    @DisplayName("an object type parameter is schema-qualified, matching the migrated composite type")
    void objectTypeParameterIsQualified() {
        TypeMethodMetadata m = function(TypeMethodKind.ORDER_FUNCTION, "compare_to", "NUMBER");
        m.addParameter(objectParam("p_other", 2, "hr", "demo_item_t", "IN"));

        String sql = flat(TypeMethodStubSqlBuilder.createStubSql(m));

        assertTrue(sql.contains(
                "hr.demo_item_t__compare_to(self hr.demo_item_t, p_other hr.demo_item_t)"), sql);
    }
}
