package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Oracle parameter defaults, on both sides of the stub → implementation replacement (ROADMAP P1.2).
 *
 * <p>Neither side used to emit {@code DEFAULT}, so every Oracle call that omits a defaulted argument
 * — 30% of the view failures on the 2026-10-24 run — found no PostgreSQL function to resolve to.
 * Both sides now emit it, under one rule ({@code ParameterDefaults}): the stub writes
 * {@code DEFAULT NULL}, since the dictionary does not hold the value, and the implementation writes
 * the transformed expression. That only works because {@code CREATE OR REPLACE} may change a
 * default's value but refuses to drop one; the execution tests prove it rather than assume it.
 */
@Testcontainers
class FunctionParameterDefaultTest {

    private static FunctionMetadata function(String name, String returnType) {
        FunctionMetadata f = new FunctionMetadata("lending", name, returnType == null ? "PROCEDURE" : "FUNCTION");
        f.setReturnDataType(returnType);
        return f;
    }

    private static FunctionParameter param(String name, int position, String mode, boolean defaulted) {
        FunctionParameter p = new FunctionParameter(name, position, "NUMBER", mode);
        p.setDefaulted(defaulted);
        return p;
    }

    private static String stub(FunctionMetadata f) {
        PostgresFunctionStubCreationJob job = new PostgresFunctionStubCreationJob();
        job.useIndices(MetadataIndexBuilder.buildEmpty());
        return job.generateCreateFunctionStubSQL(f);
    }

    private static String implementation(String oracleSource, boolean procedure) {
        AntlrParser parser = new AntlrParser();
        ParseResult parsed = procedure
                ? parser.parseProcedureBody(oracleSource)
                : parser.parseFunctionBody(oracleSource);
        assertFalse(parsed.hasErrors(), () -> "parse failed: " + parsed.getErrors());
        TransformationIndices indices = MetadataIndexBuilder.buildEmpty();
        TransformationContext context = new TransformationContext(
                "lending", indices, new SimpleTypeEvaluator("lending", indices));
        return new PostgresCodeBuilder(context).visit(parsed.getTree());
    }

    /** The parameter list, whitespace-normalised: what sits between the name and RETURNS. */
    private static String parameterList(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        return flat.substring(flat.indexOf('(') + 1, flat.indexOf(") RETURNS "));
    }

    /** The same list with every default removed — the part PostgreSQL identifies the function by. */
    private static String withoutDefaults(String parameterList) {
        return parameterList.replaceAll(" DEFAULT [^,]+", "");
    }

    // ========== the fee function, used by both sides ==========

    private static final String FEE_SOURCE = """
            FUNCTION late_fee(p_days NUMBER, p_rate NUMBER DEFAULT 0.5, p_cap NUMBER := 10) RETURN NUMBER IS
            BEGIN
              RETURN LEAST(p_days * p_rate, p_cap);
            END;
            """;

    private static FunctionMetadata feeMetadata() {
        FunctionMetadata f = function("late_fee", "NUMBER");
        f.addParameter(param("p_days", 1, "IN", false));
        f.addParameter(param("p_rate", 2, "IN", true));
        f.addParameter(param("p_cap", 3, "IN", true));
        return f;
    }

    // ========== stub ==========

    @Test
    @DisplayName("Stub: a defaulted parameter gets DEFAULT NULL")
    void stubEmitsDefaultNull() {
        assertEquals("p_days numeric, p_rate numeric DEFAULT NULL, p_cap numeric DEFAULT NULL",
                parameterList(stub(feeMetadata())));
    }

    @Test
    @DisplayName("Stub: a default before a required parameter is dropped — PostgreSQL would reject it")
    void stubDropsNonTrailingDefault() {
        FunctionMetadata f = function("renew", "NUMBER");
        f.addParameter(param("p_item", 1, "IN", true));
        f.addParameter(param("p_days", 2, "IN", false));
        f.addParameter(param("p_note", 3, "IN", true));

        assertEquals("p_item numeric, p_days numeric, p_note numeric DEFAULT NULL",
                parameterList(stub(f)));
    }

    @Test
    @DisplayName("Stub: an OUT parameter does not break the trailing run, an IN OUT one does")
    void stubOutDoesNotBreakRun() {
        FunctionMetadata f = function("place_hold", null);
        f.addParameter(param("p_item", 1, "IN", true));
        f.addParameter(param("p_copy", 2, "IN/OUT", false)); // ALL_ARGUMENTS spells IN OUT so
        f.addParameter(param("p_days", 3, "IN", true));
        f.addParameter(param("p_hold_id", 4, "OUT", false));

        assertEquals("p_item numeric, p_copy INOUT numeric, p_days numeric DEFAULT NULL, "
                        + "p_hold_id OUT numeric",
                parameterList(stub(f)));
    }

    // ========== implementation ==========

    @Test
    @DisplayName("Implementation: both default spellings (DEFAULT and :=) become DEFAULT <expr>")
    void implementationEmitsTransformedDefault() {
        assertEquals("p_days numeric, p_rate numeric DEFAULT 0.5, p_cap numeric DEFAULT 10",
                parameterList(implementation(FEE_SOURCE, false)));
    }

    @Test
    @DisplayName("Implementation: same trailing rule as the stub, OUT parameters skipped")
    void implementationAppliesSameRule() {
        String sql = implementation("""
                PROCEDURE place_hold(p_item NUMBER DEFAULT 1, p_days NUMBER, p_note VARCHAR2 DEFAULT 'web',
                                     p_hold_id OUT NUMBER) IS
                BEGIN
                  p_hold_id := p_item;
                END;
                """, true);

        assertEquals("p_item numeric, p_days numeric, p_note text DEFAULT 'web', p_hold_id OUT numeric",
                parameterList(sql));
    }

    @Test
    @DisplayName("Agreement: stub and implementation default the same parameters")
    void stubAndImplementationAgree() {
        String stubParams = parameterList(stub(feeMetadata()));
        String implParams = parameterList(implementation(FEE_SOURCE, false));

        assertEquals(withoutDefaults(implParams), withoutDefaults(stubParams));
        assertEquals(stubParams.split(" DEFAULT ", -1).length, implParams.split(" DEFAULT ", -1).length,
                "the implementation must default every parameter the stub defaults");
    }

    // ========== execution ==========

    // CREATE OR REPLACE over the stub, then a call that omits the defaulted arguments.

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb").withUsername("test").withPassword("test").withReuse(true);

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        execute("DROP SCHEMA IF EXISTS lending CASCADE");
        execute("CREATE SCHEMA lending");
    }

    @AfterEach
    void close() throws SQLException {
        execute("DROP SCHEMA IF EXISTS lending CASCADE");
        connection.close();
    }

    private void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private BigDecimal callFee(String arguments) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT lending.late_fee(" + arguments + ")")) {
            assertTrue(rs.next());
            return rs.getBigDecimal(1);
        }
    }

    @Test
    @DisplayName("A call omitting defaulted arguments resolves against the stub")
    void stubResolvesShortCall() throws SQLException {
        execute(stub(feeMetadata()));

        assertNull(callFee("4"), "the stub returns NULL — what matters is that the call resolved");
    }

    @Test
    @DisplayName("The implementation replaces the stub, and the Oracle default applies")
    void implementationReplacesStubWithRealDefaults() throws SQLException {
        execute(stub(feeMetadata()));
        execute(implementation(FEE_SOURCE, false));

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                     + "ON n.oid = p.pronamespace WHERE n.nspname = 'lending' AND p.proname = 'late_fee'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "replaced, not added beside the stub");
        }
        assertEquals(0, new BigDecimal("2.0").compareTo(callFee("4")), "4 * 0.5, capped at 10");
        assertEquals(0, new BigDecimal("10").compareTo(callFee("40")), "the cap default applies too");
        assertEquals(0, new BigDecimal("4").compareTo(callFee("4, p_cap => 4, p_rate => 2")),
                "named notation still works");
    }
}
