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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Types declared by Oracle's own packages, {@code OWA.VC_ARR} above all (ROADMAP P1.7).
 *
 * <p>They used to resolve to {@code oracle_compat.owa__vc_arr}, a type nothing creates, so the stub
 * of every flexible-parameter entry point failed with {@code schema "oracle_compat" does not
 * exist}. They now resolve as any package's associative array does, to {@code jsonb}, on both
 * sides of the {@code CREATE OR REPLACE}.
 *
 * <p>The body deliberately reads no element. Element access on an integer-keyed associative
 * array is broken for every such collection, not only these (ROADMAP §6.P1.7), and a parameter of
 * a collection type is not registered as one; neither is what this test is about.
 */
@Testcontainers
class CompatPackageTypeTest {

    private static final String SOURCE = """
            FUNCTION join_pairs(name_array OWA.VC_ARR, value_array OWA.VC_ARR) RETURN VARCHAR2 IS
              v_lens DBMS_UTILITY.NUMBER_ARRAY;
              v_file UTL_FILE.FILE_TYPE;
            BEGIN
              IF name_array IS NULL OR value_array IS NULL THEN
                RETURN 'none';
              END IF;
              RETURN 'some';
            END;
            """;

    /** As ALL_ARGUMENTS reports it: TYPE_NAME the package, TYPE_SUBNAME the type. */
    private static FunctionMetadata metadata() {
        FunctionMetadata f = new FunctionMetadata("lending", "join_pairs", "FUNCTION");
        f.setReturnDataType("VARCHAR2");
        f.addParameter(vcArr("NAME_ARRAY", 1));
        f.addParameter(vcArr("VALUE_ARRAY", 2));
        return f;
    }

    private static FunctionParameter vcArr(String name, int position) {
        FunctionParameter p = new FunctionParameter(name, position, "PL/SQL TABLE", "IN");
        p.setCustomDataType(true);
        p.setDataTypeOwner("owa");
        p.setDataTypeName("vc_arr");
        return p;
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
        return flat.substring(flat.indexOf('(') + 1, flat.indexOf(") RETURNS "));
    }

    @Test
    @DisplayName("Stub and implementation both take jsonb, and neither names oracle_compat")
    void signaturesAgree() {
        String stub = stub();
        assertEquals("name_array jsonb, value_array jsonb", parameterList(stub));
        assertEquals(parameterList(stub), parameterList(implementation()));
        assertFalse(stub.contains("oracle_compat"), stub);
        assertFalse(implementation().contains("oracle_compat"), implementation());
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
    @DisplayName("The implementation replaces the stub and is called")
    void replacesStubAndRuns() throws SQLException {
        execute(stub());
        execute(implementation());

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                     + "ON n.oid = p.pronamespace WHERE n.nspname = 'lending' AND p.proname = 'join_pairs'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "replaced, not added beside the stub");
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT lending.join_pairs(NULL, NULL), "
                     + "lending.join_pairs('{\"0\": \"shelf\"}'::jsonb, '{\"0\": \"north\"}'::jsonb)")) {
            rs.next();
            assertEquals("none", rs.getString(1));
            assertEquals("some", rs.getString(2));
        }
    }
}
