package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.function.service.OracleFunctionExtractorTestSupport;
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
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routine names that are not plain identifiers: a member Oracle names {@code "skip all"}, or
 * {@code next#}, which Oracle allows unquoted (ROADMAP P1.7, and the routine-name half of P1.13).
 *
 * <p>The flattened name was built by concatenation, {@code shelf_ops__"skip all"} or
 * {@code shelf_ops__next#}, a syntax error on both sides. A private member also kept its
 * delimiters from the parse, so its name never matched the one the dictionary gives.
 */
@Testcontainers
class QuotedRoutineNameTest {

    private static final String SKIP_ALL = """
            FUNCTION "skip all" RETURN NUMBER IS
            BEGIN
              RETURN 7;
            END "skip all";
            """;

    private static final String NEXT = """
            FUNCTION next# RETURN NUMBER IS
            BEGIN
              RETURN 8;
            END next#;
            """;

    private static final String BODY = "PACKAGE BODY shelf_ops AS\n" + SKIP_ALL + "\n" + NEXT
            + "\nEND shelf_ops;\n";

    private static List<FunctionMetadata> privateMembers() {
        return OracleFunctionExtractorTestSupport.extractPrivateMembers(
                "shelf_ops", BODY, "lending", List.of(), new StateService());
    }

    private static FunctionMetadata member(String name) {
        return privateMembers().stream().filter(f -> f.getObjectName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no private member " + name + " in "
                        + privateMembers().stream().map(FunctionMetadata::getObjectName).toList()));
    }

    private static String stub(FunctionMetadata f) {
        PostgresFunctionStubCreationJob job = new PostgresFunctionStubCreationJob();
        job.useIndices(MetadataIndexBuilder.buildEmpty());
        return job.generateCreateFunctionStubSQL(f);
    }

    private static String implementation(String source) {
        ParseResult parsed = new AntlrParser().parseFunctionBody(source);
        assertFalse(parsed.hasErrors(), () -> "parse failed: " + parsed.getErrors());
        TransformationIndices indices = MetadataIndexBuilder.buildEmpty();
        TransformationContext context = new TransformationContext("lending", indices,
                new SimpleTypeEvaluator("lending", indices), new HashMap<>(), null, "shelf_ops");
        return new PostgresCodeBuilder(context).visit(parsed.getTree());
    }

    private static String routineName(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        int start = flat.indexOf("FUNCTION ") + "FUNCTION ".length();
        return flat.substring(start, flat.indexOf("()", start));
    }

    @Test
    @DisplayName("A private member's name is stored without its delimiters, as the dictionary has it")
    void privateMemberNamesAreBare() {
        assertEquals("skip all", member("skip all").getObjectName());
        assertEquals("next#", member("next#").getObjectName());
    }

    @Test
    @DisplayName("Stub and implementation quote the flattened name as a whole, identically")
    void namesAgree() {
        assertEquals("lending.\"shelf_ops__skip all\"", routineName(stub(member("skip all"))));
        assertEquals(routineName(stub(member("skip all"))), routineName(implementation(SKIP_ALL)));
        assertEquals("lending.\"shelf_ops__next#\"", routineName(stub(member("next#"))));
        assertEquals(routineName(stub(member("next#"))), routineName(implementation(NEXT)));
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
    @DisplayName("Each implementation replaces its stub and is called")
    void replacesStubsAndRuns() throws SQLException {
        execute(stub(member("skip all")));
        execute(stub(member("next#")));
        execute(implementation(SKIP_ALL));
        execute(implementation(NEXT));

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                     + "ON n.oid = p.pronamespace WHERE n.nspname = 'lending'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "replaced, not added beside the stubs");
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT lending.\"shelf_ops__skip all\"(), lending.\"shelf_ops__next#\"()")) {
            rs.next();
            assertEquals(0, new BigDecimal("7").compareTo(rs.getBigDecimal(1)));
            assertEquals(0, new BigDecimal("8").compareTo(rs.getBigDecimal(2)));
        }
    }
}
