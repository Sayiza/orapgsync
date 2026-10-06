package me.christianrobert.orapgsync.table.job;

import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
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

class PostgresTableCreationJobTest {

    private PostgresConnectionService postgresConnectionService;
    private StateService stateService;
    private Connection mockConnection;
    private PreparedStatement mockPreparedStatement;
    private ResultSet mockResultSet;
    private PostgresTableCreationJob tableCreationJob;
    private me.christianrobert.orapgsync.core.tools.TableMetadataNormalizer tableMetadataNormalizer;

    private Consumer<JobProgress> progressCallback;
    private List<JobProgress> progressUpdates;

    @BeforeEach
    void setUp() throws Exception {
        // Create mocks
        postgresConnectionService = mock(PostgresConnectionService.class);
        stateService = mock(StateService.class);
        tableMetadataNormalizer = mock(me.christianrobert.orapgsync.core.tools.TableMetadataNormalizer.class);
        mockConnection = mock(Connection.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        mockResultSet = mock(ResultSet.class);

        // Create the job instance and inject dependencies manually
        tableCreationJob = new PostgresTableCreationJob();
        injectDependency(tableCreationJob, "postgresConnectionService", postgresConnectionService);
        injectDependency(tableCreationJob, "stateService", stateService);
        injectDependency(tableCreationJob, "tableMetadataNormalizer", tableMetadataNormalizer);

        // Configure tableMetadataNormalizer to return input as-is (identity function for tests)
        when(tableMetadataNormalizer.normalizeTableMetadata(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

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
        assertEquals("POSTGRES", tableCreationJob.getTargetDatabase());
    }

    @Test
    void testGetWriteOperationType() {
        assertEquals("TABLE_CREATION", tableCreationJob.getWriteOperationType());
    }

    @Test
    void testGetResultType() {
        assertEquals(TableCreationResult.class, tableCreationJob.getResultType());
    }

    @Test
    void testJobTypeIdentifier() {
        assertEquals("POSTGRES_TABLE_CREATION", tableCreationJob.getJobTypeIdentifier());
    }

    @Test
    void testExecute_NoOracleTables() throws Exception {
        // Arrange
        when(stateService.getOracleTableMetadata()).thenReturn(new ArrayList<>());

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

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
        verify(stateService).setTableCreationResult(result);
    }

    @Test
    void testExecute_NoValidOracleTables() throws Exception {
        // Arrange - all tables are filtered out by UserExcluder
        List<TableMetadata> systemTables = Arrays.asList(
                createTable("SYS", "SYSTEM_TABLE"),
                createTable("SYSTEM", "ADMIN_TABLE")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(systemTables);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify no database connection was attempted since no valid tables
        verify(postgresConnectionService, never()).getConnection();
        verify(stateService).setTableCreationResult(result);
    }

    @Test
    void testExecute_AllTablesAlreadyExist() throws Exception {
        // Arrange
        List<TableMetadata> oracleTables = Arrays.asList(
                createTable("HR", "EMPLOYEES"),
                createTable("SALES", "CUSTOMERS"),
                createTable("INVENTORY", "PRODUCTS")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing PostgreSQL tables query
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);

        // Mock that all Oracle tables already exist in PostgreSQL
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString("schema_name")).thenReturn("hr", "sales", "inventory");
        when(mockResultSet.getString("table_name")).thenReturn("employees", "customers", "products");

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getCreatedCount());
        assertEquals(3, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify skipped tables
        List<String> skippedTables = result.getSkippedTables();
        assertEquals(3, skippedTables.size());
        assertTrue(skippedTables.containsAll(Arrays.asList("HR.EMPLOYEES", "SALES.CUSTOMERS", "INVENTORY.PRODUCTS")));

        verify(stateService).setTableCreationResult(result);
    }

    @Test
    void testExecute_CreateNewTables() throws Exception {
        // Arrange
        List<TableMetadata> oracleTables = Arrays.asList(
                createTable("HR", "EMPLOYEES"),
                createTable("SALES", "CUSTOMERS"),
                createTable("INVENTORY", "PRODUCTS")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing PostgreSQL tables query - only EMPLOYEES exists
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(true, false);
        when(existingTablesRs.getString("schema_name")).thenReturn("hr");
        when(existingTablesRs.getString("table_name")).thenReturn("employees");

        // Mock table creation statements
        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate()).thenReturn(1);

        // Mock foreign key constraint statements
        PreparedStatement fkStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("ALTER TABLE"))).thenReturn(fkStmt);
        when(fkStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.getCreatedCount()); // CUSTOMERS and PRODUCTS created
        assertEquals(1, result.getSkippedCount());  // EMPLOYEES skipped
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify created and skipped tables
        List<String> createdTables = result.getCreatedTables();
        assertEquals(2, createdTables.size());
        assertTrue(createdTables.containsAll(Arrays.asList("SALES.CUSTOMERS", "INVENTORY.PRODUCTS")));

        List<String> skippedTables = result.getSkippedTables();
        assertEquals(1, skippedTables.size());
        assertTrue(skippedTables.contains("HR.EMPLOYEES"));

        // Verify CREATE TABLE statements were executed
        verify(createTableStmt, times(2)).executeUpdate();
        verify(stateService).setTableCreationResult(result);
    }

    @Test
    void testExecute_WithTableCreationErrors() throws Exception {
        // Arrange
        List<TableMetadata> oracleTables = Arrays.asList(
                createTable("HR", "EMPLOYEES"),
                createTable("SALES", "CUSTOMERS")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing PostgreSQL tables
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(false);

        // Mock table creation - first fails, second succeeds (natural order: HR.EMPLOYEES then SALES.CUSTOMERS)
        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate())
                .thenThrow(new SQLException("Permission denied")) // EMPLOYEES fails (processed first)
                .thenReturn(1); // CUSTOMERS succeeds (processed second)

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getCreatedCount()); // CUSTOMERS created
        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getErrorCount());   // EMPLOYEES failed
        assertFalse(result.isSuccessful());

        // Verify error details
        List<TableCreationResult.TableCreationError> errors = result.getErrors();
        assertEquals(1, errors.size());
        TableCreationResult.TableCreationError error = errors.get(0);
        assertEquals("HR.EMPLOYEES", error.getTableName());
        assertTrue(error.getErrorMessage().contains("Permission denied"));
        assertTrue(error.getSqlStatement().contains("CREATE TABLE"));

        verify(stateService).setTableCreationResult(result);
    }

    @Test
    void testExecute_ConnectionFailure() throws Exception {
        // Arrange
        List<TableMetadata> oracleTables = Arrays.asList(
                createTable("HR", "EMPLOYEES"),
                createTable("SALES", "CUSTOMERS")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenThrow(new SQLException("Connection failed"));

        // Act & Assert
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);

        // The CompletableFuture wraps the exception in an ExecutionException
        Exception exception = assertThrows(Exception.class, () -> future.get());

        // Verify the cause is the expected RuntimeException
        assertTrue(exception.getCause() instanceof RuntimeException);
        assertTrue(exception.getCause().getMessage().contains("Connection failed"));

        // Verify progress was updated with error
        assertFalse(progressUpdates.isEmpty());

        // Verify state was not updated due to exception
        verify(stateService, never()).setTableCreationResult(any());
    }

    @Test
    void testExecute_ProgressTracking() throws Exception {
        // Arrange
        List<TableMetadata> oracleTables = Arrays.asList(
                createTable("HR", "EMPLOYEES"),
                createTable("SALES", "CUSTOMERS"),
                createTable("INVENTORY", "PRODUCTS")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing tables, all need to be created
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(false);

        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        future.get();

        // Assert progress tracking
        assertFalse(progressUpdates.isEmpty());

        // Should have progress updates for different phases
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Initializing")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Connecting")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("Creating table")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getPercentage() == 90)); // Changed from 100 since we stop at "Creation complete"

        // Should have progress updates for each table creation
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("CUSTOMERS")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("EMPLOYEES")));
        assertTrue(progressUpdates.stream().anyMatch(p -> p.getCurrentTask().contains("PRODUCTS")));
    }

    @Test
    void testSaveResultsToState() {
        // Arrange
        TableCreationResult result = new TableCreationResult();
        result.addCreatedTable("TEST.TEST_TABLE");

        // Act
        tableCreationJob.saveResultsToState(result);

        // Assert
        verify(stateService).setTableCreationResult(result);
    }

    @Test
    void testGenerateSummaryMessage() throws Exception {
        // Arrange
        List<TableMetadata> oracleTables = Arrays.asList(
                createTable("HR", "EMPLOYEES"),
                createTable("SALES", "CUSTOMERS"),
                createTable("INVENTORY", "PRODUCTS"),
                createTable("FINANCE", "BUDGET")
        );
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock existing table (PRODUCTS)
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(true, false);
        when(existingTablesRs.getString("schema_name")).thenReturn("inventory");
        when(existingTablesRs.getString("table_name")).thenReturn("products");

        // Mock table creation - HR and SALES succeed, FINANCE fails (alphabetical order: BUDGET, CUSTOMERS, EMPLOYEES)
        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate())
                .thenThrow(new SQLException("Permission denied")) // BUDGET fails
                .thenReturn(1) // CUSTOMERS succeeds
                .thenReturn(1); // EMPLOYEES succeeds

        PreparedStatement fkStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("ALTER TABLE"))).thenReturn(fkStmt);
        when(fkStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult actualResult = future.get();

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
        String jobId = tableCreationJob.getJobId();

        // Assert
        assertNotNull(jobId);
        assertTrue(jobId.startsWith("postgres-table-creation-"));
        assertTrue(jobId.length() > "postgres-table-creation-".length());
    }

    @Test
    void testJobDescription() {
        // Act
        String description = tableCreationJob.getDescription();

        // Assert
        assertNotNull(description);
        assertTrue(description.toLowerCase().contains("table"));
        assertTrue(description.toLowerCase().contains("creation"));
        assertTrue(description.toLowerCase().contains("postgres"));
    }

    @Test
    void testExecute_WithForeignKeyConstraints() throws Exception {
        // Arrange
        TableMetadata parentTable = createTable("HR", "DEPARTMENTS");
        TableMetadata childTable = createTableWithForeignKey("HR", "EMPLOYEES", "DEPARTMENTS");

        List<TableMetadata> oracleTables = Arrays.asList(parentTable, childTable);
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing tables
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(false);

        // Mock table creation (FK constraints are no longer created during table creation)
        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify only CREATE TABLE statements were executed (no FK constraints yet)
        verify(createTableStmt, times(2)).executeUpdate();
    }

    private TableMetadata createTable(String schema, String tableName) {
        TableMetadata table = new TableMetadata(schema, tableName);

        // Add some sample columns
        table.addColumn(new ColumnMetadata("ID", "NUMBER", null, 10, 0, false, null));
        table.addColumn(new ColumnMetadata("NAME", "VARCHAR2", 100, null, null, true, null));
        table.addColumn(new ColumnMetadata("CREATED_DATE", "DATE", null, null, null, true, null));

        // Add primary key constraint
        ConstraintMetadata pk = new ConstraintMetadata("PK_" + tableName, ConstraintMetadata.PRIMARY_KEY);
        pk.addColumnName("ID");
        table.addConstraint(pk);

        return table;
    }

    private TableMetadata createTableWithForeignKey(String schema, String tableName, String referencedTable) {
        TableMetadata table = createTable(schema, tableName);

        // Add department_id column
        table.addColumn(new ColumnMetadata("DEPARTMENT_ID", "NUMBER", null, 10, 0, true, null));

        // Add foreign key constraint
        ConstraintMetadata fk = new ConstraintMetadata("FK_" + tableName + "_DEPT", ConstraintMetadata.FOREIGN_KEY, schema, referencedTable);
        fk.addColumnName("DEPARTMENT_ID");
        fk.addReferencedColumnName("ID");
        table.addConstraint(fk);

        return table;
    }

    @Test
    void testExecute_AnydataColumnConvertsToJsonb() throws Exception {
        // Arrange - Create a table with ANYDATA column (SYS.ANYDATA is a complex Oracle system type)
        // This test simulates the metadata as extracted by OracleTableExtractor (with SYS owner preserved)
        TableMetadata table = new TableMetadata("HR", "MESSAGES");

        // Add regular columns (no owner = built-in types)
        table.addColumn(new ColumnMetadata("ID", "NUMBER", null, 10, 0, false, null));
        table.addColumn(new ColumnMetadata("MESSAGE_TEXT", "VARCHAR2", 4000, null, null, true, null));

        // Add ANYDATA column (SYS owner preserved - as extracted from Oracle)
        // After fix: OracleTableExtractor keeps dataTypeOwner="sys" instead of setting it to null
        table.addColumn(new ColumnMetadata("PAYLOAD", "ANYDATA", "SYS", null, null, null, true, null));

        // Add another complex Oracle system type - AQ$ type
        table.addColumn(new ColumnMetadata("QUEUE_MSG", "AQ$_JMS_TEXT_MESSAGE", "SYS", null, null, null, true, null));

        // Add a user-defined type (should use composite type, not jsonb)
        table.addColumn(new ColumnMetadata("ADDRESS", "ADDRESS_TYPE", "HR", null, null, null, true, null));

        List<TableMetadata> oracleTables = Arrays.asList(table);
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing tables
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(false);

        // Capture the CREATE TABLE SQL statement
        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert - table was created successfully
        assertNotNull(result);
        assertEquals(1, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify the CREATE TABLE statement was called
        verify(createTableStmt, times(1)).executeUpdate();

        // Capture and verify the SQL statement contains jsonb for ANYDATA and AQ$ types
        verify(mockConnection, times(1)).prepareStatement(argThat(sql -> {
            String sqlLower = sql.toLowerCase();
            System.out.println("Generated SQL: " + sql);

            // Verify table structure
            boolean hasCorrectTable = sqlLower.contains("create table hr.messages");

            // Verify ANYDATA is converted to jsonb
            boolean hasAnydataAsJsonb = sqlLower.contains("payload jsonb");

            // Verify AQ$ type is converted to jsonb
            boolean hasAqAsJsonb = sqlLower.contains("queue_msg jsonb");

            // Verify user-defined type uses composite type (schema.typename)
            boolean hasUserTypeAsComposite = sqlLower.contains("address hr.address_type");

            // Verify regular columns are converted correctly
            boolean hasIdAsNumeric = sqlLower.contains("id numeric");
            boolean hasMessageAsText = sqlLower.contains("message_text text");

            System.out.println("Table check: " + hasCorrectTable);
            System.out.println("ANYDATA -> jsonb: " + hasAnydataAsJsonb);
            System.out.println("AQ$ -> jsonb: " + hasAqAsJsonb);
            System.out.println("User type -> composite: " + hasUserTypeAsComposite);
            System.out.println("ID -> numeric: " + hasIdAsNumeric);
            System.out.println("Message -> text: " + hasMessageAsText);

            return hasCorrectTable && hasAnydataAsJsonb && hasAqAsJsonb &&
                   hasUserTypeAsComposite && hasIdAsNumeric && hasMessageAsText;
        }));
    }

    @Test
    void testExecute_AnydataWithPublicOwnerConvertsToJsonb() throws Exception {
        // Arrange - Test PUBLIC owner scenario (Oracle PUBLIC synonyms for SYS types)
        // In many Oracle databases, there are PUBLIC synonyms/grants for SYS types like ANYDATA
        // This causes the extraction to return owner="public" instead of owner="sys"
        TableMetadata table = new TableMetadata("USER_ROBERT", "ANYTEST");

        // Add regular column
        table.addColumn(new ColumnMetadata("NR", "NUMBER", null, 10, 0, false, null));

        // Add ANYDATA column with PUBLIC owner (due to Oracle PUBLIC synonym)
        // This is the real-world scenario from the user's database
        table.addColumn(new ColumnMetadata("ANYBOY", "ANYDATA", "PUBLIC", null, null, null, true, null));

        List<TableMetadata> oracleTables = Arrays.asList(table);
        when(stateService.getOracleTableMetadata()).thenReturn(oracleTables);
        when(postgresConnectionService.getConnection()).thenReturn(mockConnection);

        // Mock no existing tables
        PreparedStatement existingTablesStmt = mock(PreparedStatement.class);
        ResultSet existingTablesRs = mock(ResultSet.class);
        when(mockConnection.prepareStatement(contains("pg_tables"))).thenReturn(existingTablesStmt);
        when(existingTablesStmt.executeQuery()).thenReturn(existingTablesRs);
        when(existingTablesRs.next()).thenReturn(false);

        // Capture the CREATE TABLE SQL statement
        PreparedStatement createTableStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(contains("CREATE TABLE"))).thenReturn(createTableStmt);
        when(createTableStmt.executeUpdate()).thenReturn(1);

        // Act
        CompletableFuture<TableCreationResult> future = tableCreationJob.execute(progressCallback);
        TableCreationResult result = future.get();

        // Assert - table was created successfully
        assertNotNull(result);
        assertEquals(1, result.getCreatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.isSuccessful());

        // Verify the CREATE TABLE statement was called
        verify(createTableStmt, times(1)).executeUpdate();

        // Capture and verify the SQL statement contains jsonb for PUBLIC.ANYDATA
        verify(mockConnection, times(1)).prepareStatement(argThat(sql -> {
            String sqlLower = sql.toLowerCase();
            System.out.println("Generated SQL (PUBLIC owner): " + sql);

            // Verify table structure
            boolean hasCorrectTable = sqlLower.contains("create table user_robert.anytest");

            // Verify ANYDATA with PUBLIC owner is converted to jsonb (not public.anydata!)
            boolean hasAnydataAsJsonb = sqlLower.contains("anyboy jsonb");

            // Verify it does NOT contain public.anydata (the bug scenario)
            boolean doesNotHavePublicAnydata = !sqlLower.contains("public.anydata");

            // Verify regular column
            boolean hasNrAsNumeric = sqlLower.contains("nr numeric");

            System.out.println("Table check: " + hasCorrectTable);
            System.out.println("PUBLIC.ANYDATA -> jsonb: " + hasAnydataAsJsonb);
            System.out.println("Does NOT contain 'public.anydata': " + doesNotHavePublicAnydata);
            System.out.println("NR -> numeric: " + hasNrAsNumeric);

            return hasCorrectTable && hasAnydataAsJsonb && doesNotHavePublicAnydata && hasNrAsNumeric;
        }));
    }
}