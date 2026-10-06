package me.christianrobert.orapgsync.objectdatatype.job;

import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
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

class PostgresObjectTypeCreationJobTest {

    private PostgresConnectionService postgresConnectionService;
    private StateService stateService;
    private Connection mockConnection;
    private PreparedStatement mockPreparedStatement;
    private ResultSet mockResultSet;
    private PostgresObjectTypeCreationJob objectTypeCreationJob;

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
        objectTypeCreationJob = new PostgresObjectTypeCreationJob();
        injectDependency(objectTypeCreationJob, "postgresConnectionService", postgresConnectionService);
        injectDependency(objectTypeCreationJob, "stateService", stateService);

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
        assertEquals("POSTGRES", objectTypeCreationJob.getTargetDatabase());
    }

    @Test
    void testGetWriteOperationType() {
        assertEquals("OBJECT_TYPE_CREATION", objectTypeCreationJob.getWriteOperationType());
    }

    @Test
    void testGetResultType() {
        assertEquals(ObjectTypeCreationResult.class, objectTypeCreationJob.getResultType());
    }

    @Test
    void testJobTypeIdentifier() {
        assertEquals("POSTGRES_OBJECT_TYPE_CREATION", objectTypeCreationJob.getJobTypeIdentifier());
    }

    @Test
    void testExecute_NoOracleObjectTypes() throws Exception {
        // Arrange
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(new ArrayList<>());

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        ObjectTypeCreationResult result = future.get();

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
        verify(stateService).setObjectTypeCreationResult(result);
    }

    @Test
    void testExecute_NoValidOracleObjectTypes() throws Exception {
        // Arrange - all object types are filtered out by UserExcluder
        List<ObjectDataTypeMetaData> systemObjectTypes = Arrays.asList(
                createObjectType("SYS", "SYSTEM_TYPE"),
                createObjectType("SYSTEM", "ADMIN_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(systemObjectTypes);

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        ObjectTypeCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify no database connection was attempted since no valid object types
        verify(postgresConnectionService, never()).getConnection();
        verify(stateService).setObjectTypeCreationResult(result);
    }

    @Test
    void testExecute_AllObjectTypesAlreadyExist() throws Exception {
        // Arrange
        List<ObjectDataTypeMetaData> oracleObjectTypes = Arrays.asList(
                createObjectType("HR", "EMPLOYEE_TYPE"),
                createObjectType("SALES", "CUSTOMER_TYPE"),
                createObjectType("INVENTORY", "PRODUCT_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(oracleObjectTypes);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing PostgreSQL object types query
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);

        // Mock that all Oracle object types already exist in PostgreSQL
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString("schema_name")).thenReturn("hr", "sales", "inventory");
        when(mockResultSet.getString("type_name")).thenReturn("employee_type", "customer_type", "product_type");

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        ObjectTypeCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(3, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify skipped object types
        List<String> skippedTypes = result.getSkippedTypes();
        assertEquals(3, skippedTypes.size());
        assertTrue(skippedTypes.containsAll(Arrays.asList("hr.employee_type", "sales.customer_type", "inventory.product_type")));

        verify(stateService).setObjectTypeCreationResult(result);
    }

    @Test
    void testExecute_CreateNewObjectTypes() throws Exception {
        // Arrange
        List<ObjectDataTypeMetaData> oracleObjectTypes = Arrays.asList(
                createObjectType("HR", "EMPLOYEE_TYPE"),
                createObjectType("SALES", "CUSTOMER_TYPE"),
                createObjectType("INVENTORY", "PRODUCT_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(oracleObjectTypes);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing PostgreSQL object types query - only EMPLOYEE_TYPE exists
        PreparedStatement existingTypesStmt = mock(PreparedStatement.class);
        ResultSet existingTypesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_type"))).thenReturn(existingTypesStmt);
        when(existingTypesStmt.executeQuery()).thenReturn(existingTypesRs);
        when(existingTypesRs.next()).thenReturn(true, false);
        when(existingTypesRs.getString("schema_name")).thenReturn("hr");
        when(existingTypesRs.getString("type_name")).thenReturn("employee_type");

        // Mock object type creation statements
        PreparedStatement createTypeStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TYPE"))).thenReturn(createTypeStmt);
        when(createTypeStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        ObjectTypeCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.getCreatedCount()); // CUSTOMER_TYPE and PRODUCT_TYPE created
        assertEquals(1, result.getSkippedCount());  // EMPLOYEE_TYPE skipped
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify created and skipped object types
        List<String> createdTypes = result.getCreatedTypes();
        assertEquals(2, createdTypes.size());
        assertTrue(createdTypes.containsAll(Arrays.asList("sales.customer_type", "inventory.product_type")));

        List<String> skippedTypes = result.getSkippedTypes();
        assertEquals(1, skippedTypes.size());
        assertTrue(skippedTypes.contains("hr.employee_type"));

        // Verify CREATE TYPE statements were executed
        verify(createTypeStmt, times(2)).executeUpdate();
        verify(stateService).setObjectTypeCreationResult(result);
    }

    @Test
    void testExecute_WithObjectTypeCreationErrors() throws Exception {
        // Arrange
        List<ObjectDataTypeMetaData> oracleObjectTypes = Arrays.asList(
                createObjectType("HR", "EMPLOYEE_TYPE"),
                createObjectType("SALES", "CUSTOMER_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(oracleObjectTypes);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing PostgreSQL object types
        PreparedStatement existingTypesStmt = mock(PreparedStatement.class);
        ResultSet existingTypesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_type"))).thenReturn(existingTypesStmt);
        when(existingTypesStmt.executeQuery()).thenReturn(existingTypesRs);
        when(existingTypesRs.next()).thenReturn(false);

        // Mock object type creation - first succeeds, second fails
        // Note: Types are processed alphabetically, so CUSTOMER_TYPE comes before EMPLOYEE_TYPE
        PreparedStatement createTypeStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TYPE"))).thenReturn(createTypeStmt);
        when(createTypeStmt.executeUpdate())
                .thenReturn(1) // CUSTOMER_TYPE succeeds
                .thenThrow(new SQLException("Type already exists")); // EMPLOYEE_TYPE fails

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        ObjectTypeCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getCreatedCount()); // CUSTOMER_TYPE created
        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getErrorCount());   // EMPLOYEE_TYPE failed
        assertFalse(result.isSuccessful());

        // Verify error details
        List<ObjectTypeCreationResult.ObjectTypeCreationError> errors = result.getErrors();
        assertEquals(1, errors.size());
        ObjectTypeCreationResult.ObjectTypeCreationError error = errors.get(0);
        assertEquals("hr.employee_type", error.getTypeName());
        assertTrue(error.getErrorMessage().contains("Type already exists"));
        assertTrue(error.getSqlStatement().contains("CREATE TYPE"));

        verify(stateService).setObjectTypeCreationResult(result);
    }

    @Test
    void testExecute_ConnectionFailure() throws Exception {
        // Arrange
        List<ObjectDataTypeMetaData> oracleObjectTypes = Arrays.asList(
                createObjectType("HR", "EMPLOYEE_TYPE"),
                createObjectType("SALES", "CUSTOMER_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(oracleObjectTypes);
        when(postgresConnectionService.getConnection()).thenThrow(new SQLException("Connection failed"));

        // Act & Assert
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);

        // The CompletableFuture wraps the exception in an ExecutionException
        Exception exception = assertThrows(Exception.class, () -> future.get());

        // Verify the cause is the expected RuntimeException
        assertTrue(exception.getCause() instanceof RuntimeException);
        assertTrue(exception.getCause().getMessage().contains("Connection failed"));

        // Verify progress was updated with error
        assertFalse(progressUpdates.isEmpty());

        // Verify state was not updated due to exception
        verify(stateService, never()).setObjectTypeCreationResult(any());
    }

    @Test
    void testExecute_ProgressTracking() throws Exception {
        // Arrange
        List<ObjectDataTypeMetaData> oracleObjectTypes = Arrays.asList(
                createObjectType("HR", "EMPLOYEE_TYPE"),
                createObjectType("SALES", "CUSTOMER_TYPE"),
                createObjectType("INVENTORY", "PRODUCT_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(oracleObjectTypes);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing object types, all need to be created
        PreparedStatement existingTypesStmt = mock(PreparedStatement.class);
        ResultSet existingTypesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_type"))).thenReturn(existingTypesStmt);
        when(existingTypesStmt.executeQuery()).thenReturn(existingTypesRs);
        when(existingTypesRs.next()).thenReturn(false);

        PreparedStatement createTypeStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TYPE"))).thenReturn(createTypeStmt);
        when(createTypeStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        future.get();

        // Assert progress tracking
        assertFalse(progressUpdates.isEmpty());

        // Should have progress updates for different phases
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Initializing")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Connecting")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Creating type")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getPercentage() == 100));

        // Should have progress updates for each object type creation
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("employee_type")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("customer_type")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("product_type")));
    }

    @Test
    void testSaveResultsToState() {
        // Arrange
        ObjectTypeCreationResult result = new ObjectTypeCreationResult();
        result.addCreatedType("TEST.TEST_TYPE");

        // Act
        objectTypeCreationJob.saveResultsToState(result);

        // Assert
        verify(stateService).setObjectTypeCreationResult(result);
    }

    @Test
    void testGenerateSummaryMessage() throws Exception {
        // Arrange
        List<ObjectDataTypeMetaData> oracleObjectTypes = Arrays.asList(
                createObjectType("HR", "EMPLOYEE_TYPE"),
                createObjectType("SALES", "CUSTOMER_TYPE"),
                createObjectType("INVENTORY", "PRODUCT_TYPE"),
                createObjectType("FINANCE", "BUDGET_TYPE")
        );
        when(stateService.getOracleObjectDataTypeMetaData()).thenReturn(oracleObjectTypes);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing object type (PRODUCT_TYPE)
        PreparedStatement existingTypesStmt = mock(PreparedStatement.class);
        ResultSet existingTypesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_type"))).thenReturn(existingTypesStmt);
        when(existingTypesStmt.executeQuery()).thenReturn(existingTypesRs);
        when(existingTypesRs.next()).thenReturn(true, false);
        when(existingTypesRs.getString("schema_name")).thenReturn("inventory");
        when(existingTypesRs.getString("type_name")).thenReturn("product_type");

        // Mock object type creation - HR and SALES succeed, FINANCE fails
        PreparedStatement createTypeStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TYPE"))).thenReturn(createTypeStmt);
        when(createTypeStmt.executeUpdate())
                .thenReturn(1) // EMPLOYEE_TYPE succeeds
                .thenReturn(1) // CUSTOMER_TYPE succeeds
                .thenThrow(new SQLException("Permission denied")); // BUDGET_TYPE fails

        // Act
        CompletableFuture<ObjectTypeCreationResult> future = objectTypeCreationJob.execute(progressCallback);
        ObjectTypeCreationResult actualResult = future.get();

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
        String jobId = objectTypeCreationJob.getJobId();

        // Assert
        assertNotNull(jobId);
        assertTrue(jobId.startsWith("postgres-object-type-creation-"));
        assertTrue(jobId.length() > "postgres-object-type-creation-".length());
    }

    @Test
    void testJobDescription() {
        // Act
        String description = objectTypeCreationJob.getDescription();

        // Assert
        assertNotNull(description);
        assertTrue(description.toLowerCase().contains("object"));
        assertTrue(description.toLowerCase().contains("type"));
        assertTrue(description.toLowerCase().contains("creation"));
        assertTrue(description.toLowerCase().contains("postgres"));
    }

    private ObjectDataTypeMetaData createObjectType(String schema, String name) {
        ObjectDataTypeMetaData objectType = mock(ObjectDataTypeMetaData.class);
        when(objectType.getSchema()).thenReturn(schema);
        when(objectType.getName()).thenReturn(name);

        // Create some sample variables
        List<ObjectDataTypeVariable> variables = Arrays.asList(
                new ObjectDataTypeVariable("id", "NUMBER"),
                new ObjectDataTypeVariable("name", "VARCHAR2(100)"),
                new ObjectDataTypeVariable("created_date", "DATE")
        );
        when(objectType.getVariables()).thenReturn(variables);

        return objectType;
    }
}