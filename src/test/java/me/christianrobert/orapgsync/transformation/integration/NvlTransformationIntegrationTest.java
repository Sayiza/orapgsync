package me.christianrobert.orapgsync.transformation.integration;

import me.christianrobert.orapgsync.transformation.builder.SemanticTreeBuilder;
import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.context.TransformationIndices;
import me.christianrobert.orapgsync.transformation.parser.AntlrParser;
import me.christianrobert.orapgsync.transformation.parser.ParseResult;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NVL function transformation.
 * Tests the complete pipeline: Oracle SQL → ANTLR Parse → Semantic Tree → PostgreSQL SQL
 */
class NvlTransformationIntegrationTest {

    private AntlrParser parser;
    private SemanticTreeBuilder builder;
    private TransformationContext context;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        builder = new SemanticTreeBuilder();

        // Create empty metadata indices for testing
        TransformationIndices indices = new TransformationIndices(
            new HashMap<>(),  // tableColumns
            new HashMap<>(),  // typeMethods
            Collections.emptySet(),  // packageFunctions
            new HashMap<>()   // synonyms
        );

        context = new TransformationContext("test_schema", indices);
    }

    // ========== SIMPLE NVL TESTS ==========

    @Test
    @DisplayName("SELECT NVL(col1, col2) FROM table should transform correctly")
    void nvl_twoColumns() {
        String oracleSQL = "SELECT NVL(commission, bonus) FROM employees";
        String expectedPostgresSQL = "SELECT COALESCE(commission, bonus) FROM employees";

        String result = transformSQL(oracleSQL);

        assertEquals(expectedPostgresSQL, result);
    }

    // Note: These tests are disabled until literals are implemented
    // @Test
    // @DisplayName("SELECT NVL(column, 0) FROM table should transform to COALESCE")
    // void nvl_simpleColumnWithLiteral() {
    //     String oracleSQL = "SELECT NVL(salary, 0) FROM employees";
    //     String expectedPostgresSQL = "SELECT COALESCE(salary, 0) FROM employees";
    //     String result = transformSQL(oracleSQL);
    //     assertEquals(expectedPostgresSQL, result);
    // }

    // Note: These tests are disabled until dot navigation is implemented
    // @Test
    // @DisplayName("SELECT with qualified column in NVL should transform correctly")
    // void nvl_qualifiedColumnName() {
    //     String oracleSQL = "SELECT NVL(emp.salary, 0) FROM employees emp";
    //     String expectedPostgresSQL = "SELECT COALESCE(emp.salary, 0) FROM employees emp";
    //     String result = transformSQL(oracleSQL);
    //     assertEquals(expectedPostgresSQL, result);
    // }

    // ========== MULTIPLE NVL FUNCTIONS ==========

    @Test
    @DisplayName("SELECT with multiple NVL functions should transform all")
    void nvl_multipleFunctions() {
        String oracleSQL = "SELECT NVL(salary, bonus), NVL(commission, allowance) FROM employees";
        String expectedPostgresSQL = "SELECT COALESCE(salary, bonus), COALESCE(commission, allowance) FROM employees";

        String result = transformSQL(oracleSQL);

        assertEquals(expectedPostgresSQL, result);
    }

    @Test
    @DisplayName("SELECT with NVL and regular columns should transform correctly")
    void nvl_mixedWithRegularColumns() {
        String oracleSQL = "SELECT employee_id, NVL(salary, bonus), employee_name FROM employees";
        String expectedPostgresSQL = "SELECT employee_id, COALESCE(salary, bonus), employee_name FROM employees";

        String result = transformSQL(oracleSQL);

        assertEquals(expectedPostgresSQL, result);
    }

    // Note: Alias tests disabled until literals and dot navigation are implemented
    // @Test
    // @DisplayName("SELECT NVL with column alias should transform correctly")
    // void nvl_withColumnAlias() {
    //     String oracleSQL = "SELECT NVL(salary, 0) AS final_salary FROM employees";
    //     String expectedPostgresSQL = "SELECT COALESCE(salary, 0) AS final_salary FROM employees";
    //     String result = transformSQL(oracleSQL);
    //     assertEquals(expectedPostgresSQL, result);
    // }

    // @Test
    // @DisplayName("SELECT NVL with table alias should transform correctly")
    // void nvl_withTableAlias() {
    //     String oracleSQL = "SELECT NVL(e.salary, 0) FROM employees e";
    //     String expectedPostgresSQL = "SELECT COALESCE(e.salary, 0) FROM employees e";
    //     String result = transformSQL(oracleSQL);
    //     assertEquals(expectedPostgresSQL, result);
    // }

    // ========== NESTED NVL (FUTURE) ==========

    @Test
    @DisplayName("Nested NVL functions should be supported when implemented")
    void nvl_nested_willBeSupported() {
        // Oracle: SELECT NVL(NVL(commission, bonus), 0) FROM employees
        // This will work automatically once expression nesting is fully implemented
        // For now, this test documents the expected behavior

        // TODO: Implement when nested expressions are fully supported
        // String oracleSQL = "SELECT NVL(NVL(commission, bonus), 0) FROM employees";
        // String expectedPostgresSQL = "SELECT COALESCE(COALESCE(commission, bonus), 0) FROM employees";
        // String result = transformSQL(oracleSQL);
        // assertEquals(expectedPostgresSQL, result);
    }

    // ========== ERROR CASES ==========

    @Test
    @DisplayName("Invalid SQL should throw exception during parsing")
    void invalidSQL_shouldThrowException() {
        String invalidSQL = "SELECT NVL(salary FROM employees";  // Missing closing parenthesis

        assertThrows(Exception.class, () -> {
            transformSQL(invalidSQL);
        });
    }

    // ========== HELPER METHODS ==========

    /**
     * Transform Oracle SQL to PostgreSQL SQL using the full pipeline.
     */
    private String transformSQL(String oracleSQL) {
        // Parse
        ParseResult parseResult = parser.parseSelectStatement(oracleSQL);
        assertNotNull(parseResult, "Parse result should not be null");
        assertNotNull(parseResult.getTree(), "Parse tree should not be null");

        if (parseResult.hasErrors()) {
            fail("Syntax errors in Oracle SQL: " + parseResult.getErrorMessage());
        }

        // Build semantic tree
        SemanticNode semanticTree = builder.visit(parseResult.getTree());
        assertNotNull(semanticTree, "Semantic tree should not be null");

        // Transform to PostgreSQL
        String postgresSQL = semanticTree.toPostgres(context);
        assertNotNull(postgresSQL, "PostgreSQL SQL should not be null");
        assertFalse(postgresSQL.isEmpty(), "PostgreSQL SQL should not be empty");

        return postgresSQL;
    }
}
