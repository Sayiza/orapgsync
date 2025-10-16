package me.christianrobert.orapgsync.transformation.service;

import me.christianrobert.orapgsync.transformation.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformation.context.TransformationIndices;
import me.christianrobert.orapgsync.transformation.context.TransformationResult;
import me.christianrobert.orapgsync.transformation.parser.AntlrParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ViewTransformationService.
 * Tests the complete end-to-end transformation pipeline.
 *
 * These tests validate:
 * - Complete pipeline: Oracle SQL → Parse → Transform → PostgreSQL SQL
 * - Error handling for invalid SQL
 * - Real-world SQL patterns
 * - Metadata indices integration
 *
 * Note: Currently creates service manually. Can be converted to @QuarkusTest
 * when CDI integration testing is needed.
 */
class ViewTransformationServiceTest {

    private ViewTransformationService transformationService;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        // Manually create service and inject dependencies
        transformationService = new ViewTransformationService();
        transformationService.parser = new AntlrParser();

        // Create empty indices for simple tests (no metadata needed)
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== SUCCESS CASES ==========

    @Test
    void transformSimpleSingleColumnSelect() {
        String oracleSql = "SELECT empno FROM emp";
        String expectedPostgres = "SELECT empno FROM emp";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess(), "Transformation should succeed");
        assertEquals(expectedPostgres, result.getPostgresSql());
        assertEquals(oracleSql, result.getOracleSql());
        assertNull(result.getErrorMessage());
    }

    @Test
    void transformSimpleTwoColumnSelect() {
        String oracleSql = "SELECT empno, ename FROM emp";
        String expectedPostgres = "SELECT empno, ename FROM emp";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformMultipleColumnsSelect() {
        String oracleSql = "SELECT empno, ename, sal, deptno FROM employees";
        String expectedPostgres = "SELECT empno, ename, sal, deptno FROM employees";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectWithTableAlias() {
        String oracleSql = "SELECT empno, ename FROM employees e";
        String expectedPostgres = "SELECT empno, ename FROM employees e";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectWithVariousWhitespace() {
        String oracleSql = "SELECT   empno  ,  ename   FROM   employees";
        String expectedPostgres = "SELECT empno, ename FROM employees";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectWithNewlines() {
        String oracleSql = """
            SELECT empno,
                   ename,
                   sal
            FROM employees""";
        String expectedPostgres = "SELECT empno, ename, sal FROM employees";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectUppercaseKeywords() {
        String oracleSql = "SELECT EMPNO, ENAME FROM EMPLOYEES";
        String expectedPostgres = "SELECT EMPNO, ENAME FROM EMPLOYEES";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectLowercaseKeywords() {
        String oracleSql = "select empno, ename from employees";
        String expectedPostgres = "SELECT empno, ename FROM employees";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectMixedCase() {
        String oracleSql = "Select EmpNo, EName From Employees";
        String expectedPostgres = "SELECT EmpNo, EName FROM Employees";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectWithUnderscoresInNames() {
        String oracleSql = "SELECT emp_no, emp_name, dept_id FROM employee_table";
        String expectedPostgres = "SELECT emp_no, emp_name, dept_id FROM employee_table";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    @Test
    void transformSelectWithNumbers() {
        String oracleSql = "SELECT col1, col2, col3 FROM table1";
        String expectedPostgres = "SELECT col1, col2, col3 FROM table1";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertEquals(expectedPostgres, result.getPostgresSql());
    }

    // ========== ERROR CASES ==========

    @Test
    void nullSqlReturnsFailure() {
        TransformationResult result = transformationService.transformViewSql(null, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("cannot be null"));
    }

    @Test
    void emptySqlReturnsFailure() {
        TransformationResult result = transformationService.transformViewSql("", "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("cannot be null or empty"));
    }

    @Test
    void whitespaceOnlySqlReturnsFailure() {
        TransformationResult result = transformationService.transformViewSql("   ", "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void nullSchemaReturnsFailure() {
        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, null, emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Schema cannot be null"));
    }

    @Test
    void emptySchemaReturnsFailure() {
        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void invalidSqlReturnsFailure() {
        String invalidSql = "THIS IS NOT SQL";

        TransformationResult result = transformationService.transformViewSql(invalidSql, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertEquals(invalidSql, result.getOracleSql());
    }

    @Test
    void incompleteSqlReturnsFailure() {
        String incompleteSql = "SELECT empno FROM";

        TransformationResult result = transformationService.transformViewSql(incompleteSql, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    // ========== UNSUPPORTED FEATURES (Current Limitations) ==========

    @Test
    void selectStarNotYetSupported() {
        String oracleSql = "SELECT * FROM emp";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        // This should fail in minimal implementation
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("SELECT * not supported"));
    }

    @Test
    void whereClauseNotYetSupported() {
        String oracleSql = "SELECT empno FROM emp WHERE deptno = 10";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        // This will parse but query_block has WHERE clause which we don't handle yet
        // For now, just verify it doesn't crash - behavior will improve in future phases
        assertNotNull(result);
    }

    // ========== RESULT OBJECT VALIDATION ==========

    @Test
    void successResultHasCorrectState() {
        String oracleSql = "SELECT empno FROM emp";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertNotNull(result.getPostgresSql());
        assertNull(result.getErrorMessage());
        assertEquals(oracleSql, result.getOracleSql());
    }

    @Test
    void failureResultHasCorrectState() {
        String invalidSql = "INVALID SQL";

        TransformationResult result = transformationService.transformViewSql(invalidSql, "hr", emptyIndices);

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertNull(result.getPostgresSql());
        assertNotNull(result.getErrorMessage());
        assertEquals(invalidSql, result.getOracleSql());
    }

    @Test
    void resultToStringIsInformative() {
        String oracleSql = "SELECT empno FROM emp";

        TransformationResult result = transformationService.transformViewSql(oracleSql, "hr", emptyIndices);

        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("success") || str.contains("true"));
    }

    // ========== SERVICE STATE ==========

    @Test
    void serviceIsCreated() {
        assertNotNull(transformationService, "ViewTransformationService should be created");
    }

    @Test
    void multipleCallsWork() {
        // Verify service is stateless and can handle multiple transformations
        String sql1 = "SELECT empno FROM emp";
        String sql2 = "SELECT deptno, dname FROM dept";

        TransformationResult result1 = transformationService.transformViewSql(sql1, "hr", emptyIndices);
        TransformationResult result2 = transformationService.transformViewSql(sql2, "hr", emptyIndices);

        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());
        assertEquals("SELECT empno FROM emp", result1.getPostgresSql());
        assertEquals("SELECT deptno, dname FROM dept", result2.getPostgresSql());
    }
}
