package me.christianrobert.orapgsync.transformation.integration;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.transformation.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformation.context.TransformationIndices;
import me.christianrobert.orapgsync.transformation.context.TransformationResult;
import me.christianrobert.orapgsync.transformation.service.ViewTransformationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests simulating the perspective of a view migration job.
 *
 * <p>These tests verify the complete workflow:
 * 1. StateService provides Oracle metadata (tables, synonyms, etc.)
 * 2. MetadataIndexBuilder builds transformation indices from StateService
 * 3. ViewTransformationService transforms Oracle SQL to PostgreSQL SQL
 * 4. Synonym resolution works correctly (current schema → PUBLIC fallback)
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
            parserField.set(transformationService, new me.christianrobert.orapgsync.transformation.parser.AntlrParser());
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject parser", e);
        }
    }

    /**
     * Tests basic synonym resolution: synonym in current schema.
     *
     * <p>Scenario:
     * - Schema HR has synonym EMP pointing to SCOTT.EMPLOYEES
     * - View SQL: SELECT empno FROM emp
     * - Expected: Synonym resolved, transformation succeeds
     */
    @Test
    void viewReferencingSynonymInCurrentSchema() {
        // Given: HR schema has synonym EMP → SCOTT.EMPLOYEES
        setupTableMetadata("SCOTT", "EMPLOYEES", "EMPNO", "NUMBER");
        setupSynonymMetadata("HR", "EMP", "SCOTT", "EMPLOYEES");

        // When: Job builds indices and transforms view SQL
        List<String> schemas = Arrays.asList("HR", "SCOTT");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(), "Transformation should succeed");
        assertEquals("SELECT empno FROM emp", result.getPostgresSql());
        assertNull(result.getErrorMessage());
    }

    /**
     * Tests PUBLIC synonym resolution fallback.
     *
     * <p>Scenario:
     * - No synonym in current schema HR
     * - PUBLIC schema has synonym DUAL pointing to SYS.DUAL
     * - View SQL: SELECT dummy FROM dual
     * - Expected: Falls back to PUBLIC synonym, transformation succeeds
     */
    @Test
    void viewReferencingPublicSynonym() {
        // Given: PUBLIC schema has synonym DUAL → SYS.DUAL
        setupTableMetadata("SYS", "DUAL", "DUMMY", "VARCHAR2");
        setupSynonymMetadata("PUBLIC", "DUAL", "SYS", "DUAL");

        // When: Job builds indices and transforms view SQL
        List<String> schemas = Arrays.asList("HR", "SYS", "PUBLIC");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT dummy FROM dual";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds (PUBLIC synonym resolved)
        assertTrue(result.isSuccess(), "Transformation should succeed");
        assertEquals("SELECT dummy FROM dual", result.getPostgresSql());
        assertNull(result.getErrorMessage());
    }

    /**
     * Tests synonym priority: current schema before PUBLIC.
     *
     * <p>Scenario:
     * - HR schema has synonym EMP pointing to HR.EMPLOYEES
     * - PUBLIC schema also has synonym EMP pointing to SCOTT.EMPLOYEES
     * - View SQL: SELECT empno FROM emp
     * - Expected: Current schema synonym takes priority
     */
    @Test
    void currentSchemaSynonymTakesPriorityOverPublic() {
        // Given: Both HR and PUBLIC have EMP synonyms
        setupTableMetadata("HR", "EMPLOYEES", "EMPNO", "NUMBER");
        setupTableMetadata("SCOTT", "EMPLOYEES", "EMPNO", "NUMBER");
        setupSynonymMetadata("HR", "EMP", "HR", "EMPLOYEES");
        setupSynonymMetadata("PUBLIC", "EMP", "SCOTT", "EMPLOYEES");

        // When: Job builds indices and transforms view SQL in HR schema
        List<String> schemas = Arrays.asList("HR", "SCOTT", "PUBLIC");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds using HR synonym (not PUBLIC)
        assertTrue(result.isSuccess(), "Transformation should succeed");
        assertEquals("SELECT empno FROM emp", result.getPostgresSql());
        assertNull(result.getErrorMessage());
    }

    /**
     * Tests multi-column view with synonym.
     *
     * <p>Scenario:
     * - HR schema with EMP → SCOTT.EMPLOYEES
     * - View SQL: SELECT empno, ename, sal FROM emp
     * - Expected: Synonym resolved correctly for multi-column select
     */
    @Test
    void viewReferencingMultipleSynonyms() {
        // Given: EMP synonym in HR schema pointing to SCOTT.EMPLOYEES
        setupTableMetadata("SCOTT", "EMPLOYEES", "EMPNO", "NUMBER");
        setupSynonymMetadata("HR", "EMP", "SCOTT", "EMPLOYEES");

        // When: Job builds indices and transforms view SQL
        List<String> schemas = Arrays.asList("HR", "SCOTT");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT empno, ename, sal FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(), "Transformation should succeed with message: " +
                   (result.getErrorMessage() != null ? result.getErrorMessage() : "no error"));
        assertEquals("SELECT empno, ename, sal FROM emp", result.getPostgresSql());
        assertNull(result.getErrorMessage());
    }

    /**
     * Tests job workflow with empty metadata.
     *
     * <p>Scenario:
     * - No tables, no synonyms in StateService
     * - View SQL: SELECT empno FROM emp
     * - Expected: Transformation still works (no metadata needed for simple pass-through)
     */
    @Test
    void jobWithEmptyMetadata() {
        // Given: Empty StateService (no tables, no synonyms)
        when(mockStateService.getOracleTableMetadata()).thenReturn(Collections.emptyList());
        when(mockStateService.getOracleSynonymsByOwnerAndName()).thenReturn(Collections.emptyMap());

        // When: Job builds empty indices and transforms view SQL
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);

        String oracleSql = "SELECT empno FROM emp";
        TransformationResult result = transformationService.transformViewSql(oracleSql, "HR", indices);

        // Then: Transformation still succeeds (simple pass-through doesn't need metadata)
        assertTrue(result.isSuccess(), "Transformation should succeed");
        assertEquals("SELECT empno FROM emp", result.getPostgresSql());
    }

    /**
     * Tests that job correctly filters by schemas during index building.
     *
     * <p>Scenario:
     * - StateService has tables in multiple schemas
     * - Job requests indices for only HR and SCOTT schemas
     * - Expected: Only requested schemas are indexed
     */
    @Test
    void jobBuildsIndicesForRequestedSchemasOnly() {
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

    // ========== Helper Methods ==========

    /**
     * Sets up mock table metadata in StateService.
     */
    private void setupTableMetadata(String schema, String tableName, String columnName, String dataType) {
        List<TableMetadata> existingTables = new ArrayList<>();
        if (mockStateService.getOracleTableMetadata() != null) {
            existingTables.addAll(mockStateService.getOracleTableMetadata());
        }

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

        TableMetadata table = new TableMetadata(schema, tableName);
        table.addColumn(column);

        existingTables.add(table);
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
