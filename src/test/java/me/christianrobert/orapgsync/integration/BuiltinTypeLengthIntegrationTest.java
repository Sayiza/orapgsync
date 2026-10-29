package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageHelperGenerator;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Built-ins written with a length that the prefix families do not cover — {@code VARCHAR(n)},
 * {@code STRING(n)}, {@code INTEGER(n)} — in package variables, a subtype and local declarations
 * (ROADMAP P1.9).
 *
 * <p>Unmapped, a package variable of such a type refused the whole package context, blocking every
 * member, and a local declaration reached PostgreSQL verbatim ({@code type "string" does not
 * exist}). The function is created and <b>called</b>: {@code INTEGER(11)} must hold an eleven-digit
 * value, which a PostgreSQL {@code integer} cannot.
 */
class BuiltinTypeLengthIntegrationTest extends PostgresSqlValidationTestBase {

    private static final String SPEC = """
            CREATE OR REPLACE PACKAGE lending.sizes AS
              SUBTYPE short_text IS STRING(20);
              g_label VARCHAR(30) := 'shelf';
              g_big INTEGER(11) := 12345678901;
              g_short short_text := 'abc';
              FUNCTION describe RETURN VARCHAR2;
            END sizes;
            """;

    private static final String DESCRIBE = """
            FUNCTION describe RETURN VARCHAR2 IS
              v_tail string(60) := 'end';
              v_n integer(11) := 98765432109;
            BEGIN
              RETURN g_label || ':' || g_big || ':' || g_short || ':' || v_tail || ':' || v_n;
            END;
            """;

    @BeforeEach
    void setupSchema() throws SQLException {
        executeUpdate("CREATE SCHEMA lending");
    }

    @Test
    void packageVariablesSubtypeAndLocalsWithLengths() throws SQLException {
        PackageContext context = new PackageContextExtractor(new AntlrParser())
                .extractContext("lending", "sizes", SPEC);

        Map<String, PackageContext> cache = new HashMap<>();
        cache.put("lending.sizes", context);

        // As step 28 does: helper types resolve inside the package, so its own subtype is visible.
        TransformationContext typeResolution = new TransformationContext("lending", indices,
                new SimpleTypeEvaluator("lending", indices), cache, null, "sizes");
        for (String helper : new PackageHelperGenerator().generateHelperSql(context, typeResolution)) {
            executeUpdate(helper);
        }
        TransformationResult result = transformationService.transformFunction(
                DESCRIBE, "lending", indices, cache, "describe", "sizes");
        assertTrue(result.isSuccess(), result::getErrorMessage);
        String sql = result.getPostgresSql();
        assertFalse(sql.contains("string("), () -> "STRING(n) must not reach PostgreSQL:\n" + sql);
        assertFalse(sql.contains("integer("), () -> "INTEGER(n) must not reach PostgreSQL:\n" + sql);
        executeUpdate(sql);

        List<Map<String, Object>> rows = executeQuery("SELECT lending.sizes__describe() AS d");
        assertEquals("shelf:12345678901:abc:end:98765432109", rows.get(0).get("d"));
    }
}
