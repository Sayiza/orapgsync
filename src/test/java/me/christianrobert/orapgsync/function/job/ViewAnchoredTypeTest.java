package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code %TYPE} anchors on a <em>view</em> (ROADMAP P1.4).
 *
 * <p>Oracle resolves {@code rel.col%TYPE} against tables and views alike, but the column index the
 * transformer resolved anchors against held tables only. An anchor on a view — directly, through a
 * synonym, or schema-qualified — was therefore refused in a stub signature, and passed through bare
 * by the visitor layer, where PostgreSQL rejected it as {@code relation "x" does not exist}: in the
 * signature of the implementation as much as in a {@code DECLARE}. Both sides now resolve it to the
 * qualified anchor, which PostgreSQL accepts because the view's stub (step 20) exists before any
 * function stub (step 23).
 *
 * <p>The metadata is fed through {@link MetadataIndexBuilder#build} from {@link StateService}, the
 * production path, so a view that is not indexed fails these tests.
 */
@Testcontainers
class ViewAnchoredTypeTest {

    /** academic owns a view; lending reaches it through a private synonym, the way Oracle code does. */
    private static TransformationIndices indices() {
        StateService state = new StateService();

        ViewMetadata view = new ViewMetadata("ACADEMIC", "TERM_LIST_V");
        view.addColumn(new ColumnMetadata("NR", "NUMBER", null, 10, 0, false, null));
        view.addColumn(new ColumnMetadata("TITLE", "VARCHAR2", 200, null, null, true, null));
        state.setOracleViewMetadata(List.of(view));

        state.setOracleSynonyms(List.of(
                new SynonymMetadata("LENDING", "TERMS_V", "ACADEMIC", "TERM_LIST_V", null)));

        return MetadataIndexBuilder.build(state, List.of("ACADEMIC", "LENDING"));
    }

    private static String stub(FunctionMetadata f) {
        PostgresFunctionStubCreationJob job = new PostgresFunctionStubCreationJob();
        job.useIndices(indices());
        return job.generateCreateFunctionStubSQL(f);
    }

    private static String implementation(String schema, String oracleSource) {
        ParseResult parsed = new AntlrParser().parseFunctionBody(oracleSource);
        assertFalse(parsed.hasErrors(), () -> "parse failed: " + parsed.getErrors());
        TransformationIndices indices = indices();
        TransformationContext context = new TransformationContext(
                schema, indices, new SimpleTypeEvaluator(schema, indices));
        return new PostgresCodeBuilder(context).visit(parsed.getTree());
    }

    /** What the private-member extractor hands the stub job: upper-cased, anchors kept. */
    private static FunctionMetadata termTitleMetadata() {
        FunctionMetadata f = new FunctionMetadata("lending", "term_title", "FUNCTION");
        f.setReturnDataType("ACADEMIC.TERM_LIST_V.TITLE%TYPE");
        f.addParameter(new FunctionParameter("p_nr", 1, "TERMS_V.NR%TYPE", "IN"));
        return f;
    }

    private static final String TERM_TITLE_SOURCE = """
            FUNCTION term_title(p_nr IN terms_v.nr%TYPE) RETURN academic.term_list_v.title%TYPE IS
              v_title terms_v.title%TYPE;
            BEGIN
              SELECT title INTO v_title FROM academic.term_list_v WHERE nr = p_nr;
              RETURN v_title;
            END;
            """;

    private static String parameterList(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        return flat.substring(flat.indexOf('(') + 1, flat.indexOf(") RETURNS "));
    }

    private static String returnType(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        String afterReturns = flat.substring(flat.indexOf(") RETURNS ") + ") RETURNS ".length());
        return afterReturns.substring(0, afterReturns.indexOf(' '));
    }

    // ========== stub ==========

    @Test
    @DisplayName("Stub: an anchor on a view in the routine's own schema is qualified")
    void stubOwnSchemaView() {
        FunctionMetadata f = new FunctionMetadata("academic", "check_term", "FUNCTION");
        f.setReturnDataType("NUMBER");
        f.addParameter(new FunctionParameter("p_nr", 1, "TERM_LIST_V.NR%TYPE", "IN"));

        assertTrue(stub(f).contains("p_nr academic.term_list_v.nr%TYPE"), stub(f));
    }

    @Test
    @DisplayName("Stub: an anchor through a synonym on a view, and a schema-qualified one, are qualified")
    void stubSynonymAndQualifiedView() {
        String sql = stub(termTitleMetadata());

        assertEquals("p_nr academic.term_list_v.nr%TYPE", parameterList(sql), sql);
        assertEquals("academic.term_list_v.title%TYPE", returnType(sql), sql);
    }

    @Test
    @DisplayName("Stub: a column the view does not have is still refused by name")
    void stubUnknownViewColumnIsRefused() {
        FunctionMetadata f = new FunctionMetadata("academic", "check_term", "FUNCTION");
        f.setReturnDataType("NUMBER");
        f.addParameter(new FunctionParameter("p_x", 1, "TERM_LIST_V.NO_SUCH_COLUMN%TYPE", "IN"));

        PostgresFunctionStubCreationJob.TypeResolutionException e = assertThrows(
                PostgresFunctionStubCreationJob.TypeResolutionException.class, () -> stub(f));
        assertTrue(e.getMessage().contains("TERM_LIST_V.NO_SUCH_COLUMN%TYPE"), e.getMessage());
    }

    // ========== implementation ==========

    @Test
    @DisplayName("Implementation: signature and DECLARE anchors on a view are qualified, not passed through")
    void implementationQualifiesViewAnchors() {
        String sql = implementation("lending", TERM_TITLE_SOURCE);

        assertEquals("p_nr academic.term_list_v.nr%TYPE", parameterList(sql), sql);
        assertEquals("academic.term_list_v.title%TYPE", returnType(sql), sql);
        assertTrue(sql.contains("v_title academic.term_list_v.title%TYPE"), sql);
        assertFalse(sql.matches("(?s).*[^.]terms_v\\.(nr|title)%TYPE.*"),
                "the synonym must not reach PostgreSQL, which has none: " + sql);
    }

    @Test
    @DisplayName("Agreement: stub and implementation produce the same signature")
    void stubAndImplementationAgree() {
        String stubSql = stub(termTitleMetadata());
        String implSql = implementation("lending", TERM_TITLE_SOURCE);

        assertEquals(parameterList(stubSql), parameterList(implSql));
        assertEquals(returnType(stubSql), returnType(implSql));
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
        execute("DROP SCHEMA IF EXISTS academic CASCADE");
        execute("CREATE SCHEMA academic");
        execute("CREATE SCHEMA lending");
        // The view stub, as step 20 creates it: typed columns, no rows.
        execute("CREATE VIEW academic.term_list_v AS SELECT NULL::numeric AS nr, NULL::text AS title WHERE false");
    }

    @AfterEach
    void close() throws SQLException {
        execute("DROP SCHEMA IF EXISTS lending CASCADE");
        execute("DROP SCHEMA IF EXISTS academic CASCADE");
        connection.close();
    }

    private void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("The stub is accepted against the view stub, the implementation replaces it, and the call works")
    void stubThenImplementationThenCall() throws SQLException {
        execute(stub(termTitleMetadata()));
        execute(implementation("lending", TERM_TITLE_SOURCE));

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                     + "ON n.oid = p.pronamespace WHERE n.nspname = 'lending' AND p.proname = 'term_title'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "replaced, not added beside the stub");
        }

        // The real view, as step 27 replaces the stub.
        execute("CREATE OR REPLACE VIEW academic.term_list_v AS "
                + "SELECT 7::numeric AS nr, 'Winter term'::text AS title");

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT lending.term_title(7)")) {
            assertTrue(rs.next());
            assertEquals("Winter term", rs.getString(1));
        }
    }
}
