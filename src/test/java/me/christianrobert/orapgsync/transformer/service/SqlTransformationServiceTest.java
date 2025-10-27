package me.christianrobert.orapgsync.transformer.service;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TransformationService (direct AST approach).
 * Tests the complete end-to-end transformation pipeline.
 *
 * These tests validate:
 * - Complete pipeline: Oracle SQL → Parse → Direct AST Transform → PostgreSQL SQL
 * - Error handling for invalid SQL
 * - Real-world SQL patterns
 * - Metadata indices integration
 *
 * Note: Currently creates service manually. Can be converted to @QuarkusTest
 * when CDI integration testing is needed.
 */
class SqlTransformationServiceTest {

    private TransformationService transformationService;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        // Manually create service and inject dependencies
        transformationService = new TransformationService();
        transformationService.parser = new AntlrParser();

        // Create empty indices for simple tests (no metadata needed)
        emptyIndices = new TransformationIndices(
                new HashMap<>(),  // tableColumns
                new HashMap<>(),  // typeMethods
                new HashSet<>(),  // packageFunctions
                new HashMap<>()   // synonyms
        );
    }

    // ========== SUCCESS CASES ==========

    @Test
    void transformSimpleSingleColumnSelect() {
        String oracleSql = "SELECT empno FROM emp";
        String expectedPostgres = "SELECT empno FROM hr.emp";  // Qualified with schema

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Normalize whitespace for comparison
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
        assertEquals(oracleSql, result.getOracleSql());
        assertNull(result.getErrorMessage());
    }

    @Test
    void transformSimpleTwoColumnSelect() {
        String oracleSql = "SELECT empno, ename FROM emp";
        String expectedPostgres = "SELECT empno , ename FROM hr.emp";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformMultipleColumnsSelect() {
        String oracleSql = "SELECT empno, ename, sal, deptno FROM employees";
        String expectedPostgres = "SELECT empno , ename , sal , deptno FROM hr.employees";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectWithTableAlias() {
        String oracleSql = "SELECT empno, ename FROM employees e";
        String expectedPostgres = "SELECT empno , ename FROM hr.employees e";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectWithVariousWhitespace() {
        String oracleSql = "SELECT   empno  ,  ename   FROM   employees";
        String expectedPostgres = "SELECT empno , ename FROM hr.employees";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectWithNewlines() {
        String oracleSql = """
            SELECT empno,
                   ename,
                   sal
            FROM employees""";
        String expectedPostgres = "SELECT empno , ename , sal FROM hr.employees";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectUppercaseKeywords() {
        String oracleSql = "SELECT EMPNO, ENAME FROM EMPLOYEES";
        String expectedPostgres = "SELECT EMPNO , ENAME FROM hr.employees";  // Table name lowercased by qualification

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectLowercaseKeywords() {
        String oracleSql = "select empno, ename from employees";
        String expectedPostgres = "SELECT empno , ename FROM hr.employees";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectMixedCase() {
        String oracleSql = "Select EmpNo, EName From Employees";
        String expectedPostgres = "SELECT EmpNo , EName FROM hr.employees";  // Table name lowercased by qualification

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectWithUnderscoresInNames() {
        String oracleSql = "SELECT emp_no, emp_name, dept_id FROM employee_table";
        String expectedPostgres = "SELECT emp_no , emp_name , dept_id FROM hr.employee_table";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void transformSelectWithNumbers() {
        String oracleSql = "SELECT col1, col2, col3 FROM table1";
        String expectedPostgres = "SELECT col1 , col2 , col3 FROM hr.table1";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    // ========== ERROR CASES ==========

    @Test
    void nullSqlReturnsFailure() {
        TransformationResult result = transformationService.transformSql(null, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("cannot be null"));
    }

    @Test
    void emptySqlReturnsFailure() {
        TransformationResult result = transformationService.transformSql("", "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("cannot be null or empty"));
    }

    @Test
    void whitespaceOnlySqlReturnsFailure() {
        TransformationResult result = transformationService.transformSql("   ", "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void nullSchemaReturnsFailure() {
        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformSql(oracleSql, null, emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Schema cannot be null"));
    }

    @Test
    void emptySchemaReturnsFailure() {
        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformSql(oracleSql, "", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void nullIndicesReturnsFailure() {
        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformSql(oracleSql, "hr", null);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("indices cannot be null"));
    }

    @Test
    void invalidSqlReturnsFailure() {
        String invalidSql = "THIS IS NOT SQL";

        TransformationResult result = transformationService.transformSql(invalidSql, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertEquals(invalidSql, result.getOracleSql());
    }

    @Test
    void incompleteSqlReturnsFailure() {
        String incompleteSql = "SELECT empno FROM";

        TransformationResult result = transformationService.transformSql(incompleteSql, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    // ========== RESULT OBJECT VALIDATION ==========

    @Test
    void successResultHasCorrectState() {
        String oracleSql = "SELECT empno FROM emp";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertNotNull(result.getPostgresSql());
        assertNull(result.getErrorMessage());
        assertEquals(oracleSql, result.getOracleSql());
    }

    @Test
    void failureResultHasCorrectState() {
        String invalidSql = "INVALID SQL";

        TransformationResult result = transformationService.transformSql(invalidSql, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertNull(result.getPostgresSql());
        assertNotNull(result.getErrorMessage());
        assertEquals(invalidSql, result.getOracleSql());
    }

    @Test
    void resultToStringIsInformative() {
        String oracleSql = "SELECT empno FROM emp";

        TransformationResult result = transformationService.transformSql(oracleSql, "hr", emptyIndices);

        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("success") || str.contains("true"));
    }

    // ========== SERVICE STATE ==========

    @Test
    void serviceIsCreated() {
        assertNotNull(transformationService, "TransformationService should be created");
    }

    @Test
    void multipleCallsWork() {
        // Verify service is stateless and can handle multiple transformations
        String sql1 = "SELECT empno FROM emp";
        String sql2 = "SELECT deptno, dname FROM dept";

        TransformationResult result1 = transformationService.transformSql(sql1, "hr", emptyIndices);
        TransformationResult result2 = transformationService.transformSql(sql2, "hr", emptyIndices);

        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());

        String normalized1 = result1.getPostgresSql().trim().replaceAll("\\s+", " ");
        String normalized2 = result2.getPostgresSql().trim().replaceAll("\\s+", " ");

        assertEquals("SELECT empno FROM hr.emp", normalized1);
        assertEquals("SELECT deptno , dname FROM hr.dept", normalized2);
    }
}
