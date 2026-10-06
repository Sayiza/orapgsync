package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for PostgreSQL SQL validation tests using Testcontainers.
 *
 * <p>This class provides infrastructure for testing SQL transformations against
 * a real PostgreSQL database. It starts a PostgreSQL container, provides helper
 * methods for SQL execution and result verification, and ensures proper cleanup.
 *
 * <p><b>Philosophy</b>: Integration tests should be comprehensive, testing multiple
 * features together rather than isolated micro-tests. Each test should validate
 * real-world scenarios end-to-end.
 *
 * <p><b>Test Flow</b>:
 * <ol>
 *   <li>PostgreSQL container starts once per test class (reused across methods)</li>
 *   <li>Each test method creates schema + data in {@code @BeforeEach}</li>
 *   <li>Transform Oracle SQL → PostgreSQL SQL</li>
 *   <li>Execute transformed SQL on PostgreSQL</li>
 *   <li>Assert result set matches expectations</li>
 *   <li>Cleanup happens automatically in {@code @AfterEach}</li>
 * </ol>
 *
 * <p><b>Usage Example</b>:
 * <pre>{@code
 * class MyValidationTest extends PostgresSqlValidationTestBase {
 *     @BeforeEach
 *     void setupTestData() throws SQLException {
 *         executeUpdate("""
 *             CREATE SCHEMA hr;
 *             CREATE TABLE hr.employees (emp_id INT, name TEXT);
 *             INSERT INTO hr.employees VALUES (1, 'Alice'), (2, 'Bob');
 *             """);
 *     }
 *
 *     @Test
 *     void testTransformation() throws SQLException {
 *         String oracleSql = "SELECT emp_id, name FROM employees";
 *         TransformationResult result = transformSql(oracleSql, "hr");
 *
 *         assertTrue(result.isSuccess());
 *         List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());
 *         assertEquals(2, rows.size());
 *     }
 * }
 * }</pre>
 */
@Testcontainers
public abstract class PostgresSqlValidationTestBase {

    /**
     * PostgreSQL container shared across all test methods in a test class.
     * Uses Alpine-based image for faster startup (~2 seconds).
     */
    @Container
    protected static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true); // Reuse container across test classes for performance

    /** JDBC connection to PostgreSQL (created fresh for each test method) */
    protected Connection connection;

    /** SQL transformation service */
    protected TransformationService transformationService;

    /** Transformation indices (built from empty metadata by default) */
    protected TransformationIndices indices;

    /**
     * Starts the PostgreSQL container before any tests run.
     * Container is reused across test classes for performance.
     */
    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    /**
     * Sets up a fresh connection and transformation infrastructure for each test.
     * Subclasses should override this method and call {@code super.setup()} first
     * to set up test-specific schemas and data.
     *
     * @throws SQLException if database connection fails
     */
    @BeforeEach
    void setup() throws SQLException {
        // Create fresh connection for this test
        connection = DriverManager.getConnection(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );

        // Initialize SQL transformation service
        transformationService = new TransformationService();

        // Inject AntlrParser using reflection (package-private field)
        try {
            java.lang.reflect.Field parserField = TransformationService.class.getDeclaredField("parser");
            parserField.setAccessible(true);
            parserField.set(transformationService, new AntlrParser());
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject parser into TransformationService", e);
        }

        // Build empty transformation indices (tests can override with custom metadata)
        indices = MetadataIndexBuilder.buildEmpty();
    }

    /**
     * Cleans up database state after each test to ensure isolation.
     * Drops all schemas except pg_catalog and information_schema.
     *
     * @throws SQLException if cleanup fails
     */
    @AfterEach
    void cleanup() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            // Drop all user-created schemas to ensure clean state
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata " +
                     "WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')")) {

                List<String> schemas = new ArrayList<>();
                while (rs.next()) {
                    schemas.add(rs.getString("schema_name"));
                }

                for (String schema : schemas) {
                    stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            } catch (SQLException e) {
                // Log but don't fail test if cleanup fails
                System.err.println("Warning: Cleanup failed: " + e.getMessage());
            } finally {
                connection.close();
            }
        }
    }

    // ========== Transformation Helper Methods ==========

    /**
     * Transforms Oracle SQL to PostgreSQL SQL using the configured transformation service.
     *
     * @param oracleSql the Oracle SQL to transform
     * @param schema the default schema for unqualified table references
     * @return the transformation result (check {@code isSuccess()} before using)
     */
    protected TransformationResult transformSql(String oracleSql, String schema) {
        return transformationService.transformSql(oracleSql, schema, indices);
    }

    // ========== Database Execution Helper Methods ==========

    /**
     * Executes a SELECT query and returns all rows as a list of maps.
     * Each map represents one row, with column names as keys.
     *
     * <p><b>Example</b>:
     * <pre>{@code
     * List<Map<String, Object>> rows = executeQuery("SELECT emp_id, name FROM hr.employees");
     * assertEquals(2, rows.size());
     * assertEquals(1, rows.get(0).get("emp_id"));
     * assertEquals("Alice", rows.get(0).get("name"));
     * }</pre>
     *
     * @param sql the SELECT query to execute
     * @return list of rows, where each row is a map of column name → value
     * @throws SQLException if query execution fails
     */
    protected List<Map<String, Object>> executeQuery(String sql) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
        }
        return results;
    }

    /**
     * Executes a DDL or DML statement (CREATE, INSERT, UPDATE, DELETE).
     *
     * <p><b>Example</b>:
     * <pre>{@code
     * executeUpdate("CREATE SCHEMA hr");
     * executeUpdate("CREATE TABLE hr.employees (emp_id INT, name TEXT)");
     * executeUpdate("INSERT INTO hr.employees VALUES (1, 'Alice')");
     * }</pre>
     *
     * @param sql the DDL/DML statement to execute
     * @throws SQLException if execution fails
     */
    protected void executeUpdate(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Executes multiple SQL statements separated by semicolons.
     * Useful for setting up complex test data in one call.
     *
     * <p><b>Example</b>:
     * <pre>{@code
     * executeBatch("""
     *     CREATE SCHEMA hr;
     *     CREATE TABLE hr.employees (emp_id INT, name TEXT);
     *     INSERT INTO hr.employees VALUES (1, 'Alice'), (2, 'Bob');
     *     """);
     * }</pre>
     *
     * @param sql multiple SQL statements separated by semicolons
     * @throws SQLException if any statement fails
     */
    protected void executeBatch(String sql) throws SQLException {
        // Split by semicolon, trim, and execute non-empty statements
        String[] statements = sql.split(";");
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                executeUpdate(trimmed);
            }
        }
    }

    // ========== Assertion Helper Methods ==========

    /**
     * Asserts that a query result has exactly the expected number of rows.
     *
     * @param expected expected row count
     * @param rows actual rows returned from {@code executeQuery()}
     */
    protected void assertRowCount(int expected, List<Map<String, Object>> rows) {
        if (rows.size() != expected) {
            throw new AssertionError(
                String.format("Expected %d rows but got %d. Rows: %s", expected, rows.size(), rows));
        }
    }

    /**
     * Asserts that a specific row contains the expected value for a column.
     *
     * @param rows the query results
     * @param rowIndex the 0-based row index
     * @param columnName the column name
     * @param expectedValue the expected value
     */
    protected void assertColumnValue(List<Map<String, Object>> rows, int rowIndex,
                                     String columnName, Object expectedValue) {
        if (rowIndex >= rows.size()) {
            throw new AssertionError(
                String.format("Row index %d out of bounds (only %d rows)", rowIndex, rows.size()));
        }

        Map<String, Object> row = rows.get(rowIndex);
        Object actualValue = row.get(columnName);

        if (expectedValue == null) {
            if (actualValue != null) {
                throw new AssertionError(
                    String.format("Expected NULL but got '%s' for column '%s' at row %d",
                        actualValue, columnName, rowIndex));
            }
        } else if (!expectedValue.equals(actualValue)) {
            throw new AssertionError(
                String.format("Expected '%s' but got '%s' for column '%s' at row %d",
                    expectedValue, actualValue, columnName, rowIndex));
        }
    }

    /**
     * Gets the value of a column in a specific row, with type casting.
     *
     * @param rows the query results
     * @param rowIndex the 0-based row index
     * @param columnName the column name
     * @param type the expected type
     * @param <T> the type parameter
     * @return the column value cast to the specified type
     */
    @SuppressWarnings("unchecked")
    protected <T> T getColumnValue(List<Map<String, Object>> rows, int rowIndex,
                                   String columnName, Class<T> type) {
        if (rowIndex >= rows.size()) {
            throw new AssertionError(
                String.format("Row index %d out of bounds (only %d rows)", rowIndex, rows.size()));
        }

        Object value = rows.get(rowIndex).get(columnName);
        if (value == null) {
            return null;
        }

        if (!type.isAssignableFrom(value.getClass())) {
            throw new AssertionError(
                String.format("Expected type %s but got %s for column '%s' at row %d",
                    type.getName(), value.getClass().getName(), columnName, rowIndex));
        }

        return (T) value;
    }
}
