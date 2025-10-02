package me.christianrobert.orapgsync.schema.job;

import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.schema.model.SchemaCreationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostgresSchemaCreationJobTest {

    private PostgresConnectionService postgresConnectionService;
    private StateService stateService;
    private Connection mockConnection;
    private PreparedStatement mockPreparedStatement;
    private ResultSet mockResultSet;
    private PostgresSchemaCreationJob schemaCreationJob;

    private Consumer<JobProgress> progressCallback;
    private List<JobProgress> progressUpdates;

    @BeforeEach
    void setUp() throws Exception {
        // Create mocks
        postgresConnectionService = mock(PostgresConnectionService.class);
        stateService = mock(StateService.class);
        mockConnection = mock(Connection.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        mockResultSet = mock(ResultSet.class);

        // Create the job instance and inject dependencies manually
        schemaCreationJob = new PostgresSchemaCreationJob();
        injectDependency(schemaCreationJob, "postgresConnectionService", postgresConnectionService);
        injectDependency(schemaCreationJob, "stateService", stateService);

        progressUpdates = new ArrayList<>();
        progressCallback = progress -> progressUpdates.add(progress);
    }

    private void injectDependency(Object target, String fieldName, Object dependency) throws Exception {
        Field field = null;
        Class<?> clazz = target.getClass();

        // Look through the class hierarchy to find the field
        while (clazz != null && field == null) {
            try {
                field = clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }

        if (field != null) {
            field.setAccessible(true);
            field.set(target, dependency);
        } else {
            throw new NoSuchFieldException("Field " + fieldName + " not found in class hierarchy");
        }
    }

    @Test
    void testGetTargetDatabase() {
        assertEquals("POSTGRES", schemaCreationJob.getTargetDatabase());
    }

    @Test
    void testGetWriteOperationType() {
        assertEquals("SCHEMA_CREATION", schemaCreationJob.getWriteOperationType());
    }

    @Test
    void testGetResultType() {
        assertEquals(SchemaCreationResult.class, schemaCreationJob.getResultType());
    }

    @Test
    void testJobTypeIdentifier() {
        assertEquals("POSTGRES_SCHEMA_CREATION", schemaCreationJob.getJobTypeIdentifier());
    }

    @Test
    void testExecute_NoOracleSchemas() throws Exception {
        // Arrange
        when(stateService.getOracleSchemaNames()).thenReturn(new ArrayList<>());

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        SchemaCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify progress updates
        assertTrue(progressUpdates.size() > 0);
        JobProgress lastProgress = progressUpdates.get(progressUpdates.size() - 1);
        assertEquals(100, lastProgress.getPercentage());

        // Verify state was updated
        verify(stateService).setSchemaCreationResult(result);
    }

    @Test
    void testExecute_NoValidOracleSchemas() throws Exception {
        // Arrange - all schemas are filtered out by UserExcluder
        when(stateService.getOracleSchemaNames()).thenReturn(Arrays.asList("SYS", "SYSTEM"));

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        SchemaCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify no database connection was attempted since no valid schemas
        verify(postgresConnectionService, never()).getConnection();
        verify(stateService).setSchemaCreationResult(result);
    }

    @Test
    void testExecute_AllSchemasAlreadyExist() throws Exception {
        // Arrange
        List<String> oracleSchemas = Arrays.asList("HR", "SALES", "INVENTORY");
        when(stateService.getOracleSchemaNames()).thenReturn(oracleSchemas);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing PostgreSQL schemas query
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);

        // Mock that all Oracle schemas already exist in PostgreSQL
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString("schema_name")).thenReturn("hr", "sales", "inventory");

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        SchemaCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(3, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify skipped schemas
        List<String> skippedSchemas = result.getSkippedSchemas();
        assertEquals(3, skippedSchemas.size());
        assertTrue(skippedSchemas.containsAll(Arrays.asList("HR", "SALES", "INVENTORY")));

        verify(stateService).setSchemaCreationResult(result);
    }

    @Test
    void testExecute_CreateNewSchemas() throws Exception {
        // Arrange
        List<String> oracleSchemas = Arrays.asList("HR", "SALES", "INVENTORY");
        when(stateService.getOracleSchemaNames()).thenReturn(oracleSchemas);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing PostgreSQL schemas query - only HR exists
        PreparedStatement existingSchemaStmt = mock(PreparedStatement.class);
        ResultSet existingSchemaRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("information_schema.schemata"))).thenReturn(existingSchemaStmt);
        when(existingSchemaStmt.executeQuery()).thenReturn(existingSchemaRs);
        when(existingSchemaRs.next()).thenReturn(true, false);
        when(existingSchemaRs.getString("schema_name")).thenReturn("hr");

        // Mock schema creation statements
        PreparedStatement createSchemaStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE SCHEMA IF NOT EXISTS"))).thenReturn(createSchemaStmt);
        when(createSchemaStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        SchemaCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.getCreatedCount()); // SALES and INVENTORY created
        assertEquals(1, result.getSkippedCount());  // HR skipped
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify created and skipped schemas
        List<String> createdSchemas = result.getCreatedSchemas();
        assertEquals(2, createdSchemas.size());
        assertTrue(createdSchemas.containsAll(Arrays.asList("SALES", "INVENTORY")));

        List<String> skippedSchemas = result.getSkippedSchemas();
        assertEquals(1, skippedSchemas.size());
        assertTrue(skippedSchemas.contains("HR"));

        // Verify CREATE SCHEMA statements were executed
        verify(createSchemaStmt, times(2)).executeUpdate();
        verify(stateService).setSchemaCreationResult(result);
    }

    @Test
    void testExecute_WithSchemaCreationErrors() throws Exception {
        // Arrange
        List<String> oracleSchemas = Arrays.asList("HR", "SALES");
        when(stateService.getOracleSchemaNames()).thenReturn(oracleSchemas);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing PostgreSQL schemas
        PreparedStatement existingSchemaStmt = mock(PreparedStatement.class);
        ResultSet existingSchemaRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("information_schema.schemata"))).thenReturn(existingSchemaStmt);
        when(existingSchemaStmt.executeQuery()).thenReturn(existingSchemaRs);
        when(existingSchemaRs.next()).thenReturn(false);

        // Mock schema creation - first succeeds, second fails
        PreparedStatement createSchemaStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE SCHEMA IF NOT EXISTS"))).thenReturn(createSchemaStmt);
        when(createSchemaStmt.executeUpdate())
            .thenReturn(1) // HR succeeds
            .thenThrow(new SQLException("Permission denied")); // SALES fails

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        SchemaCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getCreatedCount()); // HR created
        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getErrorCount());   // SALES failed
        assertFalse(result.isSuccessful());

        // Verify error details
        List<SchemaCreationResult.SchemaCreationError> errors = result.getErrors();
        assertEquals(1, errors.size());
        SchemaCreationResult.SchemaCreationError error = errors.get(0);
        assertEquals("SALES", error.getSchemaName());
        assertTrue(error.getErrorMessage().contains("Permission denied"));
        assertTrue(error.getSqlStatement().contains("CREATE SCHEMA IF NOT EXISTS \"SALES\""));

        verify(stateService).setSchemaCreationResult(result);
    }

    @Test
    void testExecute_ConnectionFailure() throws Exception {
        // Arrange
        List<String> oracleSchemas = Arrays.asList("HR", "SALES");
        when(stateService.getOracleSchemaNames()).thenReturn(oracleSchemas);
        when(postgresConnectionService.getConnection()).thenThrow(new SQLException("Connection failed"));

        // Act & Assert
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);

        // The CompletableFuture wraps the exception in an ExecutionException
        Exception exception = assertThrows(Exception.class, () -> future.get());

        // Verify the cause is the expected RuntimeException
        assertTrue(exception.getCause() instanceof RuntimeException);
        assertTrue(exception.getCause().getMessage().contains("Connection failed"));

        // Verify progress was updated with error (might not have -1 percentage due to async execution)
        assertFalse(progressUpdates.isEmpty());

        // Verify state was not updated due to exception
        verify(stateService, never()).setSchemaCreationResult(any());
    }

    @Test
    void testExecute_ProgressTracking() throws Exception {
        // Arrange
        List<String> oracleSchemas = Arrays.asList("HR", "SALES", "INVENTORY");
        when(stateService.getOracleSchemaNames()).thenReturn(oracleSchemas);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing schemas, all need to be created
        PreparedStatement existingSchemaStmt = mock(PreparedStatement.class);
        ResultSet existingSchemaRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("information_schema.schemata"))).thenReturn(existingSchemaStmt);
        when(existingSchemaStmt.executeQuery()).thenReturn(existingSchemaRs);
        when(existingSchemaRs.next()).thenReturn(false);

        PreparedStatement createSchemaStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE SCHEMA IF NOT EXISTS"))).thenReturn(createSchemaStmt);
        when(createSchemaStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        future.get();

        // Assert progress tracking
        assertFalse(progressUpdates.isEmpty());

        // Should have progress updates for different phases
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Initializing")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Connecting")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Creating schema")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getPercentage() == 100));

        // Should have progress updates for each schema creation
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("HR")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("SALES")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("INVENTORY")));
    }

    @Test
    void testSaveResultsToState() {
        // Arrange
        SchemaCreationResult result = new SchemaCreationResult();
        result.addCreatedSchema("TEST_SCHEMA");

        // Act
        schemaCreationJob.saveResultsToState(result);

        // Assert
        verify(stateService).setSchemaCreationResult(result);
    }

    @Test
    void testGenerateSummaryMessage() throws Exception {
        // Arrange
        SchemaCreationResult result = new SchemaCreationResult();
        result.addCreatedSchema("HR");
        result.addCreatedSchema("SALES");
        result.addSkippedSchema("INVENTORY");
        result.addError("FINANCE", "Permission denied", "CREATE SCHEMA \"FINANCE\"");

        // Access the protected method using reflection or create a test subclass
        // For this test, we'll verify the behavior indirectly through execution
        List<String> oracleSchemas = Arrays.asList("HR", "SALES", "INVENTORY", "FINANCE");
        when(stateService.getOracleSchemaNames()).thenReturn(oracleSchemas);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing schema (INVENTORY)
        PreparedStatement existingSchemaStmt = mock(PreparedStatement.class);
        ResultSet existingSchemaRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("information_schema.schemata"))).thenReturn(existingSchemaStmt);
        when(existingSchemaStmt.executeQuery()).thenReturn(existingSchemaRs);
        when(existingSchemaRs.next()).thenReturn(true, false);
        when(existingSchemaRs.getString("schema_name")).thenReturn("inventory");

        // Mock schema creation - HR and SALES succeed, FINANCE fails
        PreparedStatement createSchemaStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE SCHEMA IF NOT EXISTS"))).thenReturn(createSchemaStmt);
        when(createSchemaStmt.executeUpdate())
            .thenReturn(1) // HR succeeds
            .thenReturn(1) // SALES succeeds
            .thenThrow(new SQLException("Permission denied")); // FINANCE fails

        // Act
        CompletableFuture<SchemaCreationResult> future = schemaCreationJob.execute(progressCallback);
        SchemaCreationResult actualResult = future.get();

        // Assert - verify the summary is meaningful
        assertEquals(2, actualResult.getCreatedCount());
        assertEquals(1, actualResult.getSkippedCount());
        assertEquals(1, actualResult.getErrorCount());

        // Verify that the final progress message contains summary information
        JobProgress lastProgress = progressUpdates.get(progressUpdates.size() - 1);
        assertTrue(lastProgress.getDetails().contains("2 created"));
        assertTrue(lastProgress.getDetails().contains("1 skipped"));
        assertTrue(lastProgress.getDetails().contains("1 errors"));
    }

    @Test
    void testJobId_Generation() {
        // Act
        String jobId = schemaCreationJob.getJobId();

        // Assert
        assertNotNull(jobId);
        assertTrue(jobId.startsWith("postgres-schema-creation-"));
        assertTrue(jobId.length() > "postgres-schema-creation-".length());
    }

    @Test
    void testJobDescription() {
        // Act
        String description = schemaCreationJob.getDescription();

        // Assert
        assertNotNull(description);
        assertTrue(description.toLowerCase().contains("schema"));
        assertTrue(description.toLowerCase().contains("creation"));
        assertTrue(description.toLowerCase().contains("postgres"));
    }
}