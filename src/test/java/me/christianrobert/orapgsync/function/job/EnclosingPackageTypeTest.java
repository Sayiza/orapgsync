package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.function.service.OracleFunctionExtractorTestSupport;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageHelperGenerator;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bare type names resolved against the enclosing package (ROADMAP P1.5).
 *
 * <p>Inside a package, Oracle looks a type name up in the package itself first — its spec and its
 * body — so code writes {@code gSlots SlotCache;} and {@code p_label Label} with no qualifier.
 * Nothing here looked there. Body {@code TYPE}/{@code SUBTYPE} declarations were not even extracted,
 * so a private variable of a private type refused its whole package; and a bare name was never
 * searched for in the enclosing package, so a package-private member's signature, read off the
 * body, could not be stubbed.
 *
 * <p>Everything runs on the production path: step 22's body scan stores the reduced body, the spec
 * indexer reads it back, {@link MetadataIndexBuilder} builds the indices, and the implementation
 * side builds its live context with {@link PackageContextExtractor} as step 28 does. The two sides
 * have to agree, and the last tests check that they do.
 */
@Testcontainers
class EnclosingPackageTypeTest {

    private static final String SPEC = """
            PACKAGE shelf AS
              SUBTYPE Label IS VARCHAR2(80);
              SUBTYPE CodeRef IS item_codes.code%TYPE;
              SUBTYPE KindLabel IS kindlib.label;
              TYPE HoldRec IS RECORD (code VARCHAR2(20), qty NUMBER);
              FUNCTION describe_code(p_code CodeRef) RETURN Label;
            END shelf;
            """;

    private static final String KINDLIB_SPEC = """
            PACKAGE kindlib AS
              SUBTYPE label IS VARCHAR2(4000);
            END kindlib;
            """;

    private static final String COUNT_SLOTS = """
            FUNCTION count_slots(p_slots SlotCache, p_label Label) RETURN Tally IS
            BEGIN
              gHits := gHits + 1;
              gLast := p_label;
              RETURN gHits;
            END count_slots;""";

    private static final String BODY = """
            PACKAGE BODY shelf AS
              TYPE SlotCache IS TABLE OF VARCHAR2(200) INDEX BY VARCHAR2(40);
              SUBTYPE Tally IS PLS_INTEGER;
              gSlots SlotCache;
              gHits Tally := 0;
              gLast Label;
              gCode CodeRef;
              gKind KindLabel;
              gHold HoldRec;

              FUNCTION describe_code(p_code CodeRef) RETURN Label IS
              BEGIN
                RETURN p_code;
              END describe_code;

            """ + COUNT_SLOTS + """

            END shelf;
            """;

    /** What steps 10, 22 and the index builder leave behind. */
    private static final class Pipeline {
        final StateService state = new StateService();
        final List<FunctionMetadata> privateMembers;
        final TransformationIndices indices;

        Pipeline() {
            TableMetadata codes = new TableMetadata("LENDING", "ITEM_CODES");
            codes.addColumn(new ColumnMetadata("CODE", "VARCHAR2", 20, null, null, false, null));
            state.setOracleTableMetadata(List.of(codes));

            privateMembers = OracleFunctionExtractorTestSupport.extractPrivateMembers(
                    "shelf", BODY, "lending", List.of(describeCode()), state);
            assertTrue(OracleFunctionExtractorTestSupport.indexPackageTypes(
                    "lending", "shelf", SPEC, state));
            assertTrue(OracleFunctionExtractorTestSupport.indexPackageTypes(
                    "lending", "kindlib", KINDLIB_SPEC, state));
            indices = MetadataIndexBuilder.build(state, List.of("LENDING"));
        }

        FunctionMetadata member(String name) {
            return privateMembers.stream().filter(f -> f.getObjectName().equalsIgnoreCase(name))
                    .findFirst().orElseThrow(() -> new AssertionError("no private member " + name));
        }

        /** The one public member of the package, as {@code ALL_PROCEDURES} reports it. */
        private static FunctionMetadata describeCode() {
            FunctionMetadata f = new FunctionMetadata("lending", "describe_code", "FUNCTION");
            f.setPackageName("shelf");
            return f;
        }

        /** The package context step 28 builds: spec, then the reduced body's declarations. */
        PackageContext liveContext() {
            AntlrParser parser = new AntlrParser();
            PackageContextExtractor extractor = new PackageContextExtractor(parser);
            PackageContext context = extractor.extractContext("lending", "shelf",
                    "CREATE OR REPLACE " + SPEC);
            ParseResult body = parser.parsePackageBody(state.getReducedPackageBody("lending", "shelf"));
            assertFalse(body.hasErrors(), () -> "reduced body must parse: " + body.getErrors());
            extractor.extractBodyDeclarations((PlSqlParser.Create_package_bodyContext) body.getTree(),
                    context);
            return context;
        }

        TransformationContext implementationContext(PackageContext live) {
            Map<String, PackageContext> cache = new HashMap<>();
            cache.put("lending.shelf", live);
            return new TransformationContext("lending", indices,
                    new SimpleTypeEvaluator("lending", indices), cache, null, "shelf");
        }

        String stub(FunctionMetadata f) {
            PostgresFunctionStubCreationJob job = new PostgresFunctionStubCreationJob();
            job.useIndices(indices);
            return job.generateCreateFunctionStubSQL(f);
        }

        List<String> helpers(PackageContext live) {
            return new PackageHelperGenerator().generateHelperSql(live, implementationContext(live));
        }

        String implementation(PackageContext live, String memberSource) {
            ParseResult parsed = new AntlrParser().parseFunctionBody(memberSource);
            assertFalse(parsed.hasErrors(), () -> "parse failed: " + parsed.getErrors());
            Map<String, PackageContext> cache = new HashMap<>();
            cache.put("lending.shelf", live);
            TransformationContext context = new TransformationContext("lending", indices,
                    new SimpleTypeEvaluator("lending", indices), cache, "count_slots", "shelf");
            return new PostgresCodeBuilder(context).visit(parsed.getTree());
        }
    }

    private static String flat(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    private static String parameterList(String sql) {
        String f = flat(sql);
        return f.substring(f.indexOf('(') + 1, f.indexOf(") RETURNS "));
    }

    private static String returnType(String sql) {
        String f = flat(sql);
        String after = f.substring(f.indexOf(") RETURNS ") + ") RETURNS ".length());
        return after.substring(0, after.indexOf(' '));
    }

    /** The RETURNS type of the helper function with the given name. */
    private static String helperReturnType(List<String> helpers, String functionName) {
        String sql = helpers.stream().filter(h -> h.contains(functionName + "("))
                .findFirst().orElseThrow(() -> new AssertionError("no helper " + functionName));
        return returnType(sql);
    }

    // ========== extraction ==========

    @Test
    @DisplayName("Body TYPE and SUBTYPE declarations are extracted into the package context")
    void bodyTypesAreExtracted() {
        PackageContext live = new Pipeline().liveContext();

        assertTrue(live.hasType("SlotCache"));
        assertEquals("PLS_INTEGER", live.getSubtypeBaseType("tally"));
        assertTrue(live.hasVariable("gSlots"));
    }

    @Test
    @DisplayName("Step 22 indexes body declarations as body declarations, beside the spec's")
    void bodyTypesAreIndexedApart() {
        PackageTypeMetadata shelf = new Pipeline().indices.getPackageTypes("lending.shelf");

        assertTrue(shelf.getBodyStructuredTypes().contains("slotcache"));
        assertEquals("PLS_INTEGER", shelf.getBodySubtypes().get("tally"));
        assertFalse(shelf.getStructuredTypes().contains("slotcache"));
        assertTrue(shelf.getStructuredTypes().contains("holdrec"));
        assertEquals("VARCHAR2(80)", shelf.getSubtypes().get("label"));
    }

    // ========== package variable helpers (step 28's package context) ==========

    @Test
    @DisplayName("Helpers: variables of body and spec types, named bare, are all placed")
    void helpersPlaceBareTypes() {
        Pipeline p = new Pipeline();
        List<String> helpers = p.helpers(p.liveContext());

        assertEquals("jsonb", helperReturnType(helpers, "shelf__get_gslots"), "body INDEX BY");
        assertEquals("integer", helperReturnType(helpers, "shelf__get_ghits"), "body subtype");
        assertEquals("text", helperReturnType(helpers, "shelf__get_glast"), "spec subtype");
        assertEquals("jsonb", helperReturnType(helpers, "shelf__get_ghold"), "spec record");
        assertEquals("lending.item_codes.code%TYPE", helperReturnType(helpers, "shelf__get_gcode"),
                "a subtype of an anchor resolves as the anchor");
        assertEquals("text", helperReturnType(helpers, "shelf__get_gkind"),
                "a subtype of another package's subtype follows it there");
    }

    @Test
    @DisplayName("Helpers: a body-declared collection still gets its initializer")
    void bodyCollectionIsInitialized() {
        Pipeline p = new Pipeline();
        List<String> helpers = p.helpers(p.liveContext());
        String init = helpers.stream().filter(h -> h.contains("shelf__initialize()"))
                .findFirst().orElseThrow();

        assertTrue(init.contains("'lending.shelf.gslots', '{}'"), init);
    }

    // ========== resolution scope ==========

    @Test
    @DisplayName("Body declarations are visible from inside the package only")
    void bodyTypesArePrivate() {
        Pipeline p = new Pipeline();
        TransformationContext inside = new TransformationContext("lending", p.indices,
                new SimpleTypeEvaluator("lending", p.indices), null, null, "shelf");
        TransformationContext outside = new TransformationContext("lending", p.indices,
                new SimpleTypeEvaluator("lending", p.indices), null, null, "kindlib");

        assertEquals("jsonb", TypeResolver.toPostgresOrNull("SlotCache", inside));
        assertEquals("jsonb", TypeResolver.toPostgresOrNull("shelf.SlotCache", inside));
        assertNull(TypeResolver.toPostgresOrNull("SlotCache", outside));
        assertNull(TypeResolver.toPostgresOrNull("shelf.SlotCache", outside));
        assertEquals("jsonb", TypeResolver.toPostgresOrNull("shelf.HoldRec", outside),
                "spec declarations stay public");
    }

    @Test
    @DisplayName("Outside any package, a bare name is not looked up in one")
    void standaloneCodeIsUnaffected() {
        Pipeline p = new Pipeline();
        TransformationContext standalone = new TransformationContext("lending", p.indices,
                new SimpleTypeEvaluator("lending", p.indices));

        assertNull(TypeResolver.toPostgresOrNull("Label", standalone));
    }

    // ========== stub ==========

    @Test
    @DisplayName("Stub: a private member's bare body and spec types are placed")
    void stubPlacesBareTypes() {
        Pipeline p = new Pipeline();
        String sql = p.stub(p.member("count_slots"));

        assertEquals("p_slots jsonb, p_label text", parameterList(sql), sql);
        assertEquals("integer", returnType(sql), sql);
    }

    @Test
    @DisplayName("Stub: a bare name the package does not declare is still refused by name")
    void stubRefusesUndeclaredBareName() {
        Pipeline p = new Pipeline();
        FunctionMetadata f = new FunctionMetadata("lending", "check_slot", "FUNCTION");
        f.setPackageName("shelf");
        f.setReturnDataType("NUMBER");
        f.addParameter(new me.christianrobert.orapgsync.core.job.model.function.FunctionParameter(
                "p_x", 1, "NOSUCHTYPE", "IN"));

        PostgresFunctionStubCreationJob.TypeResolutionException e = assertThrows(
                PostgresFunctionStubCreationJob.TypeResolutionException.class, () -> p.stub(f));
        assertTrue(e.getMessage().contains("NOSUCHTYPE"), e.getMessage());
    }

    // ========== implementation and agreement ==========

    @Test
    @DisplayName("Agreement: stub and implementation produce the same signature")
    void stubAndImplementationAgree() {
        Pipeline p = new Pipeline();
        String stubSql = p.stub(p.member("count_slots"));
        String implSql = p.implementation(p.liveContext(), COUNT_SLOTS);

        assertEquals(parameterList(stubSql), parameterList(implSql), implSql);
        assertEquals(returnType(stubSql), returnType(implSql), implSql);
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
        execute("CREATE TABLE lending.item_codes (code text NOT NULL)");
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

    private String query(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("Helpers are created, the implementation replaces the stub, and the call works")
    void helpersStubImplementationCall() throws SQLException {
        Pipeline p = new Pipeline();
        PackageContext live = p.liveContext();

        for (String helper : p.helpers(live)) {
            execute(helper);
        }
        execute(p.stub(p.member("count_slots")));
        execute(p.implementation(live, COUNT_SLOTS));

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                     + "ON n.oid = p.pronamespace WHERE n.nspname = 'lending' "
                     + "AND p.proname = 'shelf__count_slots'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "replaced, not added beside the stub");
        }

        // Package state is transaction-local, so the calls share one transaction.
        connection.setAutoCommit(false);
        try {
            assertEquals("1", query("SELECT lending.shelf__count_slots('{}'::jsonb, 'shelf A')"));
            assertEquals("2", query("SELECT lending.shelf__count_slots('{}'::jsonb, 'shelf B')"));
            assertEquals("shelf B", query("SELECT lending.shelf__get_glast()"));
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }
}
