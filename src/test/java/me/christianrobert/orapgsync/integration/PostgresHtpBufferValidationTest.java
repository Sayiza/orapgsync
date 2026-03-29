package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.oraclecompat.catalog.OracleBuiltinCatalog;
import me.christianrobert.orapgsync.oraclecompat.model.OracleBuiltinFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HTP (Hypertext Procedures) buffer functions.
 *
 * <p>Tests verify the complete mod_plsql buffer workflow:
 * <ol>
 *   <li>Initialize buffer with htp__init()</li>
 *   <li>Output content with htp__p(), htp__prn(), etc.</li>
 *   <li>Retrieve buffer with htp__get_buffer()</li>
 * </ol>
 *
 * <p>This simulates what the Quarkus web gateway will do:
 * init → call procedure → get buffer → return HTTP response
 */
public class PostgresHtpBufferValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    @Override
    void setup() throws SQLException {
        super.setup();

        // Create oracle_compat schema and install HTP/OWA functions
        installOracleCompatFunctions();
    }

    /**
     * Installs all oracle_compat functions needed for testing.
     */
    private void installOracleCompatFunctions() throws SQLException {
        // Create schema
        executeUpdate("CREATE SCHEMA IF NOT EXISTS oracle_compat");

        // Install functions from catalog
        OracleBuiltinCatalog catalog = new OracleBuiltinCatalog();

        for (OracleBuiltinFunction func : catalog.getAllFunctions()) {
            if (func.getSqlDefinition() != null) {
                String packageName = func.getPackageName();
                // Only install HTP, OWA, and OWA_UTIL functions for this test
                if (packageName.equals("HTP") || packageName.equals("OWA") || packageName.equals("OWA_UTIL")) {
                    executeUpdate(func.getSqlDefinition());
                }
            }
        }
    }

    // ========== BUFFER INITIALIZATION ==========

    @Test
    void htpInit_createsBuffer() throws SQLException {
        // When: Initialize buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // Then: Buffer should exist and be empty
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertRowCount(1, rows);
        assertEquals("", rows.get(0).get("content"));
    }

    @Test
    void htpInit_clearsExistingContent() throws SQLException {
        // Given: Buffer with existing content
        executeUpdate("SELECT oracle_compat.htp__init()");
        executeUpdate("SELECT oracle_compat.htp__p('existing content')");

        // When: Re-initialize buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // Then: Buffer should be empty
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("", rows.get(0).get("content"));
    }

    // ========== HTP.P - PRIMARY OUTPUT ==========

    @Test
    void htpP_outputsTextWithNewline() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output text with htp__p
        executeUpdate("SELECT oracle_compat.htp__p('Hello World')");

        // Then: Buffer contains text with newline
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("Hello World\n", rows.get(0).get("content"));
    }

    @Test
    void htpP_multipleOutputs() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Multiple htp__p calls
        executeUpdate("SELECT oracle_compat.htp__p('<html>')");
        executeUpdate("SELECT oracle_compat.htp__p('<body>')");
        executeUpdate("SELECT oracle_compat.htp__p('Hello')");
        executeUpdate("SELECT oracle_compat.htp__p('</body>')");
        executeUpdate("SELECT oracle_compat.htp__p('</html>')");

        // Then: All output concatenated in order
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("<html>\n<body>\nHello\n</body>\n</html>\n", rows.get(0).get("content"));
    }

    @Test
    void htpP_handlesNullAsEmpty() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output NULL
        executeUpdate("SELECT oracle_compat.htp__p(NULL)");

        // Then: Should output empty string with newline
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("\n", rows.get(0).get("content"));
    }

    // ========== HTP.PRN - OUTPUT WITHOUT NEWLINE ==========

    @Test
    void htpPrn_outputsWithoutNewline() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output text with htp__prn
        executeUpdate("SELECT oracle_compat.htp__prn('no newline')");

        // Then: Buffer contains text without newline
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("no newline", rows.get(0).get("content"));
    }

    @Test
    void htpPrn_multipleOutputsConcatenate() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Multiple htp__prn calls
        executeUpdate("SELECT oracle_compat.htp__prn('Hello')");
        executeUpdate("SELECT oracle_compat.htp__prn(' ')");
        executeUpdate("SELECT oracle_compat.htp__prn('World')");

        // Then: All output concatenated without newlines
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("Hello World", rows.get(0).get("content"));
    }

    // ========== HTP.PRINT - ALIAS FOR HTP.P ==========

    @Test
    void htpPrint_sameAsP() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output with htp__print (alias for htp__p)
        executeUpdate("SELECT oracle_compat.htp__print('test')");

        // Then: Same as htp__p (with newline)
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("test\n", rows.get(0).get("content"));
    }

    // ========== HTP.NL - NEWLINE ONLY ==========

    @Test
    void htpNl_outputsNewlineOnly() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output newline
        executeUpdate("SELECT oracle_compat.htp__prn('line1')");
        executeUpdate("SELECT oracle_compat.htp__nl()");
        executeUpdate("SELECT oracle_compat.htp__prn('line2')");

        // Then: Newline inserted between text
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("line1\nline2", rows.get(0).get("content"));
    }

    // ========== HTP.BR - LINE BREAK ==========

    @Test
    void htpBr_outputsBreakTag() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output br tag
        executeUpdate("SELECT oracle_compat.htp__prn('before')");
        executeUpdate("SELECT oracle_compat.htp__br()");
        executeUpdate("SELECT oracle_compat.htp__prn('after')");

        // Then: <br> tag inserted
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("before<br>after", rows.get(0).get("content"));
    }

    // ========== HTP.LINE - HORIZONTAL RULE ==========

    @Test
    void htpLine_outputsHrTag() throws SQLException {
        // Given: Initialized buffer
        executeUpdate("SELECT oracle_compat.htp__init()");

        // When: Output hr tag
        executeUpdate("SELECT oracle_compat.htp__line()");

        // Then: <hr> tag with newline
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("<hr>\n", rows.get(0).get("content"));
    }

    // ========== CGI ENVIRONMENT ==========

    @Test
    void owaInitCgiEnv_setsVariables() throws SQLException {
        // Given: CGI environment JSON
        String cgiJson = "{\"REQUEST_METHOD\": \"GET\", \"QUERY_STRING\": \"id=123\", \"REMOTE_ADDR\": \"127.0.0.1\"}";

        // When: Initialize CGI environment
        executeUpdate("SELECT oracle_compat.owa__init_cgi_env('" + cgiJson + "'::jsonb)");

        // Then: Variables can be retrieved
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.owa_util__get_cgi_env('REQUEST_METHOD') AS method");
        assertEquals("GET", rows.get(0).get("method"));

        rows = executeQuery(
            "SELECT oracle_compat.owa_util__get_cgi_env('QUERY_STRING') AS qs");
        assertEquals("id=123", rows.get(0).get("qs"));

        rows = executeQuery(
            "SELECT oracle_compat.owa_util__get_cgi_env('REMOTE_ADDR') AS addr");
        assertEquals("127.0.0.1", rows.get(0).get("addr"));
    }

    @Test
    void owaUtilGetCgiEnv_caseInsensitive() throws SQLException {
        // Given: CGI environment
        executeUpdate("SELECT oracle_compat.owa__init_cgi_env('{\"REQUEST_METHOD\": \"POST\"}'::jsonb)");

        // When: Query with different case
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.owa_util__get_cgi_env('request_method') AS method");

        // Then: Should still find it (case-insensitive lookup)
        assertEquals("POST", rows.get(0).get("method"));
    }

    @Test
    void owaUtilGetCgiEnv_returnsNullForUnknown() throws SQLException {
        // Given: CGI environment with some variables
        executeUpdate("SELECT oracle_compat.owa__init_cgi_env('{\"REQUEST_METHOD\": \"GET\"}'::jsonb)");

        // When: Query unknown variable
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.owa_util__get_cgi_env('NONEXISTENT') AS value");

        // Then: Returns NULL
        assertNull(rows.get(0).get("value"));
    }

    // ========== COMBINED WORKFLOW ==========

    @Test
    void fullRequestWorkflow_simulatesWebGateway() throws SQLException {
        // This test simulates what the Quarkus web gateway will do

        // Step 1: Initialize request context (buffer + CGI env)
        executeUpdate("SELECT oracle_compat.owa__init_request('{\"REQUEST_METHOD\": \"GET\", \"QUERY_STRING\": \"name=World\"}'::jsonb)");

        // Step 2: "Procedure" generates HTML using HTP calls
        // (In real usage, this would be a single function call)
        executeUpdate("SELECT oracle_compat.htp__p('<html>')");
        executeUpdate("SELECT oracle_compat.htp__p('<head><title>Test</title></head>')");
        executeUpdate("SELECT oracle_compat.htp__p('<body>')");

        // Get name from "query string" (simulating OWA_UTIL usage)
        List<Map<String, Object>> nameRows = executeQuery(
            "SELECT oracle_compat.owa_util__get_cgi_env('QUERY_STRING') AS qs");
        String queryString = (String) nameRows.get(0).get("qs");
        String name = queryString.split("=")[1]; // Extract "World" from "name=World"

        executeUpdate("SELECT oracle_compat.htp__p('<h1>Hello " + name + "!</h1>')");
        executeUpdate("SELECT oracle_compat.htp__p('</body>')");
        executeUpdate("SELECT oracle_compat.htp__p('</html>')");

        // Step 3: Extract buffer (what gateway sends as HTTP response)
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS html");
        String html = (String) rows.get(0).get("html");

        // Verify complete HTML
        assertTrue(html.contains("<html>"));
        assertTrue(html.contains("<title>Test</title>"));
        assertTrue(html.contains("<h1>Hello World!</h1>"));
        assertTrue(html.contains("</html>"));
    }

    @Test
    void htpGetBuffer_beforeInit_returnsEmpty() throws SQLException {
        // When: Get buffer without initialization
        // (temp table doesn't exist yet)
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");

        // Then: Returns empty string (not error)
        assertEquals("", rows.get(0).get("content"));
    }

    @Test
    void htpP_autoCreatesBuffer() throws SQLException {
        // When: Call htp__p without explicit init
        // (should auto-create buffer table)
        executeUpdate("SELECT oracle_compat.htp__p('auto created')");

        // Then: Buffer contains the content
        List<Map<String, Object>> rows = executeQuery(
            "SELECT oracle_compat.htp__get_buffer() AS content");
        assertEquals("auto created\n", rows.get(0).get("content"));
    }
}
