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
 * Names with {@code #} or {@code $}, which Oracle allows unquoted and PostgreSQL does not
 * (ROADMAP P1.13).
 *
 * <p>References to such a name were already quoted by {@code IdentifierHelper.emit}, but the
 * declaration sites lower-cased the raw text, so {@code i# INTEGER} reached PostgreSQL as
 * {@code i# integer;} — a syntax error that rejected the whole routine. Parameters must also
 * agree with the stub, which names them through {@code PostgresIdentifierNormalizer}: a
 * {@code CREATE OR REPLACE} that renames a parameter is refused.
 */
@Testcontainers
class SpecialCharacterIdentifierTest {

    private static final String SOURCE = """
            FUNCTION shelf_total(p_max# NUMBER, p_step$ NUMBER DEFAULT 1) RETURN NUMBER IS
              i# INTEGER := 0;
              total# NUMBER := 0;
              CURSOR c# (p_lim# NUMBER) IS SELECT n FROM lending.slots WHERE n <= p_lim#;
            BEGIN
              FOR j# IN 1..p_max# LOOP
                total# := total# + j# * p_step$;
              END LOOP;
              FOR r# IN c#(p_max#) LOOP
                i# := i# + r#.n;
              END LOOP;
              RETURN total# + i#;
            END;
            """;

    /** As ALL_ARGUMENTS reports the function: upper-case names, the special characters kept. */
    private static FunctionMetadata metadata() {
        FunctionMetadata f = new FunctionMetadata("lending", "shelf_total", "FUNCTION");
        f.setReturnDataType("NUMBER");
        f.addParameter(new FunctionParameter("P_MAX#", 1, "NUMBER", "IN"));
        FunctionParameter step = new FunctionParameter("P_STEP$", 2, "NUMBER", "IN");
        step.setDefaulted(true);
        f.addParameter(step);
        return f;
    }

    private static String stub() {
        PostgresFunctionStubCreationJob job = new PostgresFunctionStubCreationJob();
        job.useIndices(MetadataIndexBuilder.buildEmpty());
        return job.generateCreateFunctionStubSQL(metadata());
    }

    private static String implementation() {
        ParseResult parsed = new AntlrParser().parseFunctionBody(SOURCE);
        assertFalse(parsed.hasErrors(), () -> "parse failed: " + parsed.getErrors());
        TransformationIndices indices = MetadataIndexBuilder.buildEmpty();
        TransformationContext context = new TransformationContext(
                "lending", indices, new SimpleTypeEvaluator("lending", indices));
        return new PostgresCodeBuilder(context).visit(parsed.getTree());
    }

    private static String parameterList(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        return flat.substring(flat.indexOf('(') + 1, flat.indexOf(") RETURNS "))
                .replaceAll(" DEFAULT [^,]+", "");
    }

    @Test
    @DisplayName("Declarations quote the name exactly as references to it are quoted")
    void declarationsAreQuoted() {
        String sql = implementation();
        assertAll(
                () -> assertTrue(sql.contains("\"i#\" integer"), sql),
                () -> assertTrue(sql.contains("\"total#\" numeric"), sql),
                () -> assertTrue(sql.contains("\"c#\" CURSOR (\"p_lim#\""), sql),
                () -> assertTrue(sql.contains("FOR \"j#\" IN"), sql),
                () -> assertTrue(sql.contains("FOR \"r#\" IN \"c#\""), sql),
                () -> assertFalse(sql.matches("(?s).*[^\"]\\b[a-z_]+[#$][^\"].*"),
                        () -> "a name with # or $ reached PostgreSQL unquoted:\n" + sql));
    }

    @Test
    @DisplayName("Stub and implementation name the parameters identically")
    void parameterNamesAgreeWithStub() {
        assertEquals(parameterList(stub()), parameterList(implementation()));
    }

    // ========== execution ==========

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
        execute("CREATE TABLE lending.slots (n numeric)");
        execute("INSERT INTO lending.slots VALUES (1), (2), (3), (10)");
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

    @Test
    @DisplayName("The implementation replaces the stub and is called")
    void replacesStubAndRuns() throws SQLException {
        execute(stub());
        execute(implementation());

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                     + "ON n.oid = p.pronamespace WHERE n.nspname = 'lending' AND p.proname = 'shelf_total'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "replaced, not added beside the stub");
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT lending.shelf_total(3), lending.shelf_total(3, 2)")) {
            rs.next();
            // 1+2+3 = 6, plus the slots up to 3: 1+2+3 = 6
            assertEquals(0, new BigDecimal("12").compareTo(rs.getBigDecimal(1)));
            // the step doubles the loop sum: 12, plus 6
            assertEquals(0, new BigDecimal("18").compareTo(rs.getBigDecimal(2)));
        }
    }
}
