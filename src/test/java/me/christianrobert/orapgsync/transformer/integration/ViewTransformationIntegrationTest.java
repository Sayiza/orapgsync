package me.christianrobert.orapgsync.transformer.integration;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.service.ViewTransformationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests simulating the perspective of a view migration job (direct AST approach).
 *
 * <p>These tests verify the complete workflow:
 * 1. StateService provides Oracle metadata (tables, synonyms, etc.)
 * 2. MetadataIndexBuilder builds transformation indices from StateService
 * 3. ViewTransformationService transforms Oracle SQL to PostgreSQL SQL
 * 4. Direct AST transformation (no intermediate semantic tree)
 *
 * <p>This mirrors how an actual job (e.g., PostgresViewImplementationJob)
 * would use the transformation infrastructure.
 */
class ViewTransformationIntegrationTest {

    private StateService mockStateService;
    private ViewTransformationService transformationService;

    @BeforeEach
    void setUp() {
        mockStateService = Mockito.mock(StateService.class);
        transformationService = new ViewTransformationService();

        // Set up default empty returns for all StateService methods
        when(mockStateService.getOracleTableMetadata()).thenReturn(new ArrayList<>());
        when(mockStateService.getOracleSynonymsByOwnerAndName()).thenReturn(new HashMap<>());
        when(mockStateService.getOracleTypeMethodMetadata()).thenReturn(new ArrayList<>());
        when(mockStateService.getOracleFunctionMetadata()).thenReturn(new ArrayList<>());

        // Manually inject parser using reflection (package-private field)
        try {
            java.lang.reflect.Field parserField = ViewTransformationService.class.getDeclaredField("parser");
            parserField.setAccessible(true);
            parserField.set(transformationService, new AntlrParser());
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject parser", e);
        }
    }

    /**
     * Tests basic transformation with empty metadata.
     *
     * <p>Scenario:
     * - No tables, no synonyms in StateService
     * - View SQL: SELECT empno, ename FROM emp
     * - Expected: Transformation still works (no metadata needed for simple pass-through)
     */
    @Test
    void viewTransformationWithEmptyMetadata() {
        // Given: Empty StateService (no tables, no synonyms)
        when(mockStateService.getOracleTableMetadata()).thenReturn(Collections.emptyList());
        when(mockStateService.getOracleSynonymsByOwnerAndName()).thenReturn(Collections.emptyMap());

        // When: Job builds empty indices and transforms view SQL
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT empno, ename FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds (simple pass-through doesn't need metadata)
        assertTrue(result.isSuccess(), "Transformation should succeed");
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM emp", normalized);
    }

    /**
     * Tests transformation with table metadata present.
     *
     * <p>Scenario:
     * - StateService has table metadata for HR.EMPLOYEES
     * - View SQL: SELECT empno, ename FROM employees
     * - Expected: Transformation succeeds (metadata available but not needed for simple SELECT)
     */
    @Test
    void viewTransformationWithTableMetadata() {
        // Given: HR schema has EMPLOYEES table
        setupTableMetadata("HR", "EMPLOYEES", "EMPNO", "NUMBER");
        setupTableMetadata("HR", "EMPLOYEES", "ENAME", "VARCHAR2");

        // When: Job builds indices and transforms view SQL
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT empno, ename FROM employees";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(), "Transformation should succeed");
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM employees", normalized);
        assertNull(result.getErrorMessage());
    }

    /**
     * Tests job workflow with multiple schemas.
     *
     * <p>Scenario:
     * - StateService has tables in multiple schemas
     * - Job requests indices for only HR and SCOTT schemas
     * - Expected: Transformation works for both schemas
     */
    @Test
    void jobBuildsIndicesForMultipleSchemas() {
        // Given: Tables in multiple schemas
        setupTableMetadata("HR", "EMPLOYEES", "EMPNO", "NUMBER");
        setupTableMetadata("SCOTT", "EMPLOYEES", "EMPNO", "NUMBER");
        setupTableMetadata("OE", "ORDERS", "ORDER_ID", "NUMBER");

        // When: Job builds indices for only HR and SCOTT
        List<String> schemas = Arrays.asList("HR", "SCOTT");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        // Then: Indices built successfully
        assertNotNull(indices);

        // And: Can transform SQL in included schemas
        String oracleSql = "SELECT empno FROM employees";
        TransformationResult hrResult = transformationService.transformViewSql(oracleSql, "HR", indices);
        assertTrue(hrResult.isSuccess());

        TransformationResult scottResult = transformationService.transformViewSql(oracleSql, "SCOTT", indices);
        assertTrue(scottResult.isSuccess());
    }

    /**
     * Tests transformation with different column counts.
     *
     * <p>Scenario:
     * - Views with 1, 2, 3, and 4 columns
     * - Expected: All transform correctly
     */
    @Test
    void viewsWithDifferentColumnCounts() {
        // Given: Table with multiple columns
        setupTableMetadata("HR", "EMPLOYEES", "EMPNO", "NUMBER");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        // Single column
        String sql1 = "SELECT empno FROM employees";
        TransformationResult result1 = transformationService.transformViewSql(sql1, "HR", indices);
        assertTrue(result1.isSuccess());

        // Two columns
        String sql2 = "SELECT empno, ename FROM employees";
        TransformationResult result2 = transformationService.transformViewSql(sql2, "HR", indices);
        assertTrue(result2.isSuccess());

        // Three columns
        String sql3 = "SELECT empno, ename, sal FROM employees";
        TransformationResult result3 = transformationService.transformViewSql(sql3, "HR", indices);
        assertTrue(result3.isSuccess());

        // Four columns
        String sql4 = "SELECT empno, ename, sal, deptno FROM employees";
        TransformationResult result4 = transformationService.transformViewSql(sql4, "HR", indices);
        assertTrue(result4.isSuccess());
    }

    /**
     * Tests transformation with table aliases.
     *
     * <p>Scenario:
     * - View SQL uses table alias: SELECT empno FROM employees e
     * - Expected: Alias preserved in output
     */
    @Test
    void viewWithTableAlias() {
        // Given: Table metadata
        setupTableMetadata("HR", "EMPLOYEES", "EMPNO", "NUMBER");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        // When: Transform SQL with table alias
        String oracleSql = "SELECT empno FROM employees e";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds with alias preserved
        assertTrue(result.isSuccess());
        String normalized = result.getPostgresSql().trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno FROM employees e", normalized);
    }

    /**
     * Tests service is stateless across multiple transformations.
     */
    @Test
    void multipleTransformationsSucceed() {
        // Given: Empty metadata
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        // When: Transform multiple different queries
        String sql1 = "SELECT empno FROM emp";
        String sql2 = "SELECT deptno, dname FROM dept";
        String sql3 = "SELECT job, sal FROM jobs";

        TransformationResult result1 = transformationService.transformViewSql(sql1, "HR", indices);
        TransformationResult result2 = transformationService.transformViewSql(sql2, "HR", indices);
        TransformationResult result3 = transformationService.transformViewSql(sql3, "HR", indices);

        // Then: All transformations succeed independently
        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());
        assertTrue(result3.isSuccess());

        String normalized1 = result1.getPostgresSql().trim().replaceAll("\\s+", " ");
        String normalized2 = result2.getPostgresSql().trim().replaceAll("\\s+", " ");
        String normalized3 = result3.getPostgresSql().trim().replaceAll("\\s+", " ");

        assertEquals("SELECT empno FROM emp", normalized1);
        assertEquals("SELECT deptno , dname FROM dept", normalized2);
        assertEquals("SELECT job , sal FROM jobs", normalized3);
    }

    /**
     * Tests error handling for invalid SQL.
     */
    @Test
    void invalidSqlReturnsFailure() {
        // Given: Empty indices
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        // When: Transform invalid SQL
        String invalidSql = "SELECT FROM WHERE";
        TransformationResult result = transformationService.transformViewSql(invalidSql, "HR", indices);

        // Then: Transformation fails with error message
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    // ========== Helper Methods ==========

    /**
     * Sets up mock table metadata in StateService.
     * This helper adds columns one at a time, so call it multiple times for the same table
     * to add multiple columns.
     */
    private void setupTableMetadata(String schema, String tableName, String columnName, String dataType) {
        List<TableMetadata> existingTables = new ArrayList<>();
        if (mockStateService.getOracleTableMetadata() != null) {
            existingTables.addAll(mockStateService.getOracleTableMetadata());
        }

        // Find existing table or create new one
        TableMetadata table = null;
        for (TableMetadata t : existingTables) {
            if (t.getSchema().equals(schema) && t.getTableName().equals(tableName)) {
                table = t;
                break;
            }
        }

        if (table == null) {
            table = new TableMetadata(schema, tableName);
            existingTables.add(table);
        }

        // Add column to table
        ColumnMetadata column = new ColumnMetadata(
                columnName,
                dataType,
                null,  // dataTypeOwner
                null,  // characterLength
                null,  // numericPrecision
                null,  // numericScale
                false, // nullable
                null   // defaultValue
        );
        table.addColumn(column);

        when(mockStateService.getOracleTableMetadata()).thenReturn(existingTables);
    }

    /**
     * Sets up mock synonym metadata in StateService.
     */
    private void setupSynonymMetadata(String synonymOwner, String synonymName, String tableOwner, String tableName) {
        Map<String, Map<String, SynonymMetadata>> existingSynonyms = new HashMap<>();
        if (mockStateService.getOracleSynonymsByOwnerAndName() != null) {
            existingSynonyms.putAll(mockStateService.getOracleSynonymsByOwnerAndName());
        }

        SynonymMetadata synonym = new SynonymMetadata(
                synonymOwner,
                synonymName,
                tableOwner,
                tableName,
                null // dbLink
        );

        existingSynonyms
                .computeIfAbsent(synonymOwner, k -> new HashMap<>())
                .put(synonymName, synonym);

        when(mockStateService.getOracleSynonymsByOwnerAndName()).thenReturn(existingSynonyms);
    }
}
