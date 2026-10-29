package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.function.service.FunctionSourcePairing;
import me.christianrobert.orapgsync.function.service.OracleFunctionExtractorTestSupport;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Overloaded package members each get their <em>own</em> body (ROADMAP P1.8).
 *
 * <p>Package member sources used to be stored keyed by name, so every overload was transformed
 * from the last body of its name: same-kind overloads all reported "implemented" with one body,
 * and a function and a procedure sharing a name were each handed the other's source. Private
 * overloads collapsed to one, and a private overload of a public name was dropped as "public".
 *
 * <p>The body below declares the public overloads of {@code label} in a different order from
 * their dictionary overload numbers, which is common in practice and is why pairing by position
 * would be wrong. The last test creates stub and implementation in PostgreSQL and <b>calls</b>
 * every overload: each must answer with its own body's value, and exactly one function must exist
 * per signature.
 */
@Testcontainers
class PackageOverloadSourceTest {

    private static final String BODY = """
            PACKAGE BODY render IS
              FUNCTION tag(p_value NUMBER) RETURN VARCHAR2 IS
              BEGIN
                RETURN 'tag number';
              END tag;

              FUNCTION tag(p_value DATE) RETURN VARCHAR2 IS
              BEGIN
                RETURN 'tag date';
              END tag;

              FUNCTION label(p_value DATE) RETURN VARCHAR2 IS
              BEGIN
                RETURN 'label date';
              END label;

              FUNCTION label(p_value VARCHAR2) RETURN VARCHAR2 IS
              BEGIN
                RETURN 'label text';
              END label;

              FUNCTION label(p_value NUMBER) RETURN VARCHAR2 IS
              BEGIN
                RETURN 'label number';
              END label;

              PROCEDURE note(p_text VARCHAR2) IS
              BEGIN
                NULL;
              END note;

              FUNCTION note RETURN VARCHAR2 IS
              BEGIN
                RETURN 'note function';
              END note;

              FUNCTION width(p_value VARCHAR2) RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END width;

              FUNCTION width(p_value VARCHAR2, p_pad NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN 2;
              END width;

              FUNCTION code(p_value item_codes.code%TYPE) RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END code;

              FUNCTION code(p_value item_codes.qty%TYPE) RETURN NUMBER IS
              BEGIN
                RETURN 2;
              END code;
            END render;
            """;

    /** One overload as {@code ALL_PROCEDURES} + {@code ALL_ARGUMENTS} report it. */
    private static FunctionMetadata overload(String name, String kind, int overloadNumber, String returnType,
                                             String... parameters) {
        FunctionMetadata f = new FunctionMetadata("lending", name, kind);
        f.setPackageName("render");
        f.setOverloadNumber(overloadNumber);
        f.setReturnDataType(returnType);
        for (int i = 0; i < parameters.length; i += 2) {
            f.addParameter(new FunctionParameter(parameters[i], i / 2 + 1, parameters[i + 1], "IN"));
        }
        return f;
    }

    // Dictionary order: overload numbers follow the specification, not the body.
    private final FunctionMetadata labelNumber = overload("label", "FUNCTION", 1, "VARCHAR2", "p_value", "NUMBER");
    private final FunctionMetadata labelText = overload("label", "FUNCTION", 2, "VARCHAR2", "p_value", "VARCHAR2");
    private final FunctionMetadata labelDate = overload("label", "FUNCTION", 3, "VARCHAR2", "p_value", "DATE");
    private final FunctionMetadata noteProcedure = overload("note", "PROCEDURE", 1, null, "p_text", "VARCHAR2");
    private final FunctionMetadata noteFunction = overload("note", "FUNCTION", 2, "VARCHAR2");
    private final FunctionMetadata width = overload("width", "FUNCTION", 0, "NUMBER", "p_value", "VARCHAR2");
    private final FunctionMetadata codeText = overload("code", "FUNCTION", 1, "NUMBER", "p_value", "VARCHAR2");
    private final FunctionMetadata codeNumber = overload("code", "FUNCTION", 2, "NUMBER", "p_value", "NUMBER");

    private final StateService state = new StateService();
    private List<FunctionMetadata> privateMembers;

    @BeforeEach
    void extract() {
        privateMembers = OracleFunctionExtractorTestSupport.extractPrivateMembers("render", BODY, "lending",
                List.of(labelNumber, labelText, labelDate, noteProcedure, noteFunction, width, codeText, codeNumber),
                state);
    }

    private String source(FunctionMetadata f) throws SQLException {
        return PostgresFunctionImplementationJob.packageMemberSource(state, f);
    }

    private FunctionMetadata privateMember(String name, int parameterCount) {
        return privateMembers.stream()
                .filter(f -> f.getObjectName().equals(name) && f.getParameters().size() == parameterCount)
                .findFirst().orElseThrow(() -> new AssertionError("no private " + name + "/" + parameterCount
                        + " in " + privateMembers));
    }

    // ========== extraction ==========

    @Test
    @DisplayName("Overloads declared in a different order from the specification each get their own body")
    void overloadsInAnotherOrder() throws SQLException {
        assertTrue(source(labelNumber).contains("'label number'"), () -> sourceOrError(labelNumber));
        assertTrue(source(labelText).contains("'label text'"), () -> sourceOrError(labelText));
        assertTrue(source(labelDate).contains("'label date'"), () -> sourceOrError(labelDate));
    }

    @Test
    @DisplayName("A function and a procedure of one name each get their own body")
    void functionAndProcedureOfOneName() throws SQLException {
        assertTrue(source(noteProcedure).startsWith("PROCEDURE note"), () -> sourceOrError(noteProcedure));
        assertTrue(source(noteFunction).contains("'note function'"), () -> sourceOrError(noteFunction));
    }

    @Test
    @DisplayName("Private overloads are all extracted, numbered apart, with their own bodies")
    void privateOverloads() throws SQLException {
        FunctionMetadata tagNumber = privateMembers.stream()
                .filter(f -> f.getObjectName().equals("tag") && f.getParameters().get(0).getDataType().equals("NUMBER"))
                .findFirst().orElseThrow();
        FunctionMetadata tagDate = privateMembers.stream()
                .filter(f -> f.getObjectName().equals("tag") && f.getParameters().get(0).getDataType().equals("DATE"))
                .findFirst().orElseThrow();

        assertTrue(source(tagNumber).contains("'tag number'"));
        assertTrue(source(tagDate).contains("'tag date'"));
        assertNotEquals(tagNumber.getOverloadNumber(), tagDate.getOverloadNumber());
        assertTrue(tagNumber.getOverloadNumber() > 0 && tagDate.getOverloadNumber() > 0);
    }

    @Test
    @DisplayName("A private overload of a public name is extracted, not dropped as public")
    void privateOverloadOfPublicName() throws SQLException {
        assertTrue(source(width).contains("RETURN 1;"));

        FunctionMetadata padded = privateMember("width", 2);
        assertTrue(padded.isPackagePrivate());
        assertTrue(source(padded).contains("RETURN 2;"));
        assertNotEquals(width.getOverloadNumber(), padded.getOverloadNumber());
    }

    @Test
    @DisplayName("Overloads whose bodies cannot be told apart are refused by name, never guessed")
    void indistinguishableOverloadsAreRefused() {
        // Both bodies anchor their parameter (%TYPE), so neither can be shown to be the VARCHAR2 or
        // the NUMBER overload without resolving the anchor.
        for (FunctionMetadata f : List.of(codeText, codeNumber)) {
            assertEquals(-1, f.getSourceOrdinal());
            assertNotNull(f.getSourcePairingError());
            SQLException e = assertThrows(SQLException.class, () -> source(f));
            assertTrue(e.getMessage().contains("no identifiable source"), e::getMessage);
        }
        // ...and their bodies are not passed off as private members either.
        assertTrue(privateMembers.stream().noneMatch(f -> f.getObjectName().equals("code")));
    }

    @Test
    @DisplayName("The only body of a non-overloaded member is its source even when its header does not parse")
    void onlyBodyIsPairedEvenUnparsed() {
        FunctionMetadata only = overload("only", "FUNCTION", 0, "NUMBER", "p_value", "NUMBER");
        java.util.List<FunctionMetadata> unparsedHeader = new java.util.ArrayList<>();
        unparsedHeader.add(null);

        FunctionSourcePairing.Result result = FunctionSourcePairing.pair(List.of(only), unparsedHeader);

        assertEquals(0, result.ordinals()[0]);
        assertNull(result.errors()[0]);
    }

    private String sourceOrError(FunctionMetadata f) {
        try {
            return source(f);
        } catch (SQLException e) {
            return e.getMessage();
        }
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

    private String query(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private int functionCount(String name) throws SQLException {
        return Integer.parseInt(query("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = 'lending' AND p.proname = '" + name + "'"));
    }

    private static TransformationService transformationService() {
        TransformationService service = new TransformationService();
        try {
            java.lang.reflect.Field parser = TransformationService.class.getDeclaredField("parser");
            parser.setAccessible(true);
            parser.set(service, new AntlrParser());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return service;
    }

    @Test
    @DisplayName("Stub, then implementation, of every overload: one function per signature, each answers with its own body")
    void everyOverloadAnswersWithItsOwnBody() throws SQLException {
        TransformationIndices indices = MetadataIndexBuilder.build(state, List.of("LENDING"));
        PostgresFunctionStubCreationJob stubJob = new PostgresFunctionStubCreationJob();
        stubJob.useIndices(indices);
        TransformationService transformer = transformationService();

        List<FunctionMetadata> members = List.of(labelNumber, labelText, labelDate, noteProcedure, noteFunction,
                privateMember("tag", 1), privateMembers.stream().filter(f -> f.getObjectName().equals("tag"))
                        .filter(f -> f.getParameters().get(0).getDataType().equals("DATE")).findFirst().orElseThrow());

        for (FunctionMetadata f : members) {
            execute(stubJob.generateCreateFunctionStubSQL(f));
        }
        for (FunctionMetadata f : members) {
            TransformationResult result = f.isFunction()
                    ? transformer.transformFunction(source(f), "lending", indices, new HashMap<>(), f.getObjectName(), "render")
                    : transformer.transformProcedure(source(f), "lending", indices, new HashMap<>(), f.getObjectName(), "render");
            assertTrue(result.isSuccess(), () -> f.getDisplayName() + ": " + result.getErrorMessage());
            execute(result.getPostgresSql());
        }

        assertEquals(3, functionCount("render__label"), "each overload replaced its own stub, none added beside");
        assertEquals(2, functionCount("render__note"));
        assertEquals(2, functionCount("render__tag"));

        assertEquals("label number", query("SELECT lending.render__label(1::numeric)"));
        assertEquals("label text", query("SELECT lending.render__label('x'::text)"));
        assertEquals("label date", query("SELECT lending.render__label(localtimestamp(0))"));
        assertEquals("note function", query("SELECT lending.render__note()"));
        execute("SELECT lending.render__note('x'::text)");
        assertEquals("tag number", query("SELECT lending.render__tag(1::numeric)"));
        assertEquals("tag date", query("SELECT lending.render__tag(localtimestamp(0))"));
    }
}
