package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for object type member method transformation.
 *
 * <p>Critical transformation for user-defined types (PostgreSQL has no member methods):
 * Oracle: SELECT emp.address.get_street() FROM employees emp
 * PostgreSQL: SELECT address_type__get_street(emp.address) FROM employees emp
 *
 * <p>The object instance becomes the first parameter to the flattened function.
 *
 * <p>Also tests chained method calls:
 * Oracle: emp.address.get_full().upper()
 * PostgreSQL: address_type__upper(address_type__get_full(emp.address))
 */
class TypeMemberMethodTransformationTest {

    private AntlrParser parser;
    private StateService mockStateService;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        mockStateService = Mockito.mock(StateService.class);

        // Default empty returns
        when(mockStateService.getOracleTableMetadata()).thenReturn(new ArrayList<>());
        when(mockStateService.getOracleSynonymsByOwnerAndName()).thenReturn(new HashMap<>());
        when(mockStateService.getOracleTypeMethodMetadata()).thenReturn(new ArrayList<>());
        when(mockStateService.getOracleFunctionMetadata()).thenReturn(new ArrayList<>());
    }

    // ========== SIMPLE TYPE MEMBER METHOD TESTS ==========

    @Test
    void simpleTypeMemberMethod() {
        // Given: HR schema has EMPLOYEES table with ADDRESS column of ADDRESS_TYPE
        //        And ADDRESS_TYPE has GET_STREET() method
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_STREET");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with type member method
        String oracleSql = "SELECT emp.address.get_street() FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: PostgreSQL uses flattened function call with instance as first parameter
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("address_type__get_street( emp . address )") ||
                   normalized.contains("address_type__get_street(emp.address)"),
                "Type member method should be flattened function call. Got: " + normalized);
    }

    @Test
    void typeMemberMethodWithArguments() {
        // Given: Type method that takes arguments
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_PART");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with type member method with arguments
        String oracleSql = "SELECT emp.address.get_part(empno) FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Arguments should be preserved (instance first, then original arguments)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("address_type__get_part( emp . address , empno )") ||
                   normalized.contains("address_type__get_part(emp.address, empno)"),
                "Type member method arguments should be preserved. Got: " + normalized);
    }

    @Test
    void multipleTypeMemberMethodsInSameQuery() {
        // Given: Table with multiple custom type columns
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "CONTACT", "HR", "CONTACT_TYPE");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_STREET");
        setupTypeMethod("HR", "CONTACT_TYPE", "GET_EMAIL");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with multiple type member methods
        String oracleSql = "SELECT emp.address.get_street(), emp.contact.get_email() FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both methods should be transformed to flattened function calls
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("address_type__get_street( emp . address )") ||
                   normalized.contains("address_type__get_street(emp.address)"),
                "First type method should be transformed. Got: " + normalized);
        assertTrue(normalized.contains("contact_type__get_email( emp . contact )") ||
                   normalized.contains("contact_type__get_email(emp.contact)"),
                "Second type method should be transformed. Got: " + normalized);
    }

    // ========== CHAINED METHOD CALL TESTS ==========

    @Test
    void chainedTypeMemberMethods() {
        // Given: Address type has get_full() method that returns another custom type
        //        And that type has upper() method
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_FULL");
        setupTypeMethod("HR", "ADDRESS_TYPE", "UPPER");  // Simplified - assume same type

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with chained method calls
        String oracleSql = "SELECT emp.address.get_full().upper() FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Chained methods should use nested function calls
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("address_type__upper( address_type__get_full( emp . address ) )") ||
                   normalized.contains("address_type__upper(address_type__get_full(emp.address))"),
                "Chained methods should be nested function calls. Got: " + normalized);
    }

    @Test
    void tripleChainedMethodCalls() {
        // Given: Three chained method calls
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "DATA", "HR", "DATA_TYPE");
        setupTypeMethod("HR", "DATA_TYPE", "METHOD1");
        setupTypeMethod("HR", "DATA_TYPE", "METHOD2");
        setupTypeMethod("HR", "DATA_TYPE", "METHOD3");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with triple chain
        String oracleSql = "SELECT emp.data.method1().method2().method3() FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should have nested parentheses for all calls
        assertNotNull(postgresSql);
        assertTrue(postgresSql.contains("method1"));
        assertTrue(postgresSql.contains("method2"));
        assertTrue(postgresSql.contains("method3"));
    }

    // ========== DISAMBIGUATION TESTS ==========

    @Test
    void typeMemberMethodVsPackageFunction_differentPatterns() {
        // Given: Both type method (3 parts) and package function (2 parts) exist
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_STREET");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Use both in same query
        // Type method: emp.address.get_street() (3 parts, alias.column.method)
        // Package function would be: pkg.func() (2 parts)
        String oracleSql = "SELECT emp.address.get_street() FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Type method should use flattened function with instance parameter (not just package__function)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("address_type__get_street( emp . address )") ||
                   normalized.contains("address_type__get_street(emp.address)"),
                "Should be recognized as type method. Got: " + normalized);
        assertFalse(normalized.contains("address__get_street("),
                "Should NOT be flattened like a package function (needs typename prefix)");
    }

    @Test
    void regularColumnNotTransformed() {
        // Given: Table with custom type column
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_STREET");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Reference column without method call
        String oracleSql = "SELECT emp.address FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Plain column reference should not be wrapped in parentheses
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("emp . address"),
                "Plain column reference should not be transformed. Got: " + normalized);
        assertFalse(normalized.contains("( emp.address )"),
                "Should not add parentheses for non-method access");
    }

    // ========== MIXED TYPE AND BUILT-IN COLUMNS ==========

    @Test
    void mixedCustomAndBuiltInTypes() {
        // Given: Table with both custom type and built-in type columns
        setupTableWithCustomTypeColumn("HR", "EMPLOYEES", "ADDRESS", "HR", "ADDRESS_TYPE");
        setupTableWithBuiltInTypeColumn("HR", "EMPLOYEES", "EMPNO", "NUMBER");
        setupTableWithBuiltInTypeColumn("HR", "EMPLOYEES", "ENAME", "VARCHAR2");
        setupTypeMethod("HR", "ADDRESS_TYPE", "GET_STREET");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Mix type methods and regular columns
        String oracleSql = "SELECT emp.empno, emp.address.get_street(), emp.ename FROM employees emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Type method transformed to flattened function, regular columns not transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("emp . empno"),
                "Built-in column should not be transformed");
        assertTrue(normalized.contains("address_type__get_street( emp . address )") ||
                   normalized.contains("address_type__get_street(emp.address)"),
                "Type method should be transformed to flattened function");
        assertTrue(normalized.contains("emp . ename"),
                "Built-in column should not be transformed");
    }

    // ========== Helper Methods ==========

    /**
     * Sets up a table with a custom type column.
     */
    private void setupTableWithCustomTypeColumn(
            String tableSchema,
            String tableName,
            String columnName,
            String typeOwner,
            String typeName) {

        List<TableMetadata> existingTables = new ArrayList<>();
        if (mockStateService.getOracleTableMetadata() != null) {
            existingTables.addAll(mockStateService.getOracleTableMetadata());
        }

        // Check if table already exists
        TableMetadata existingTable = existingTables.stream()
                .filter(t -> t.getSchema().equalsIgnoreCase(tableSchema)
                        && t.getTableName().equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);

        if (existingTable != null) {
            // Add column to existing table
            ColumnMetadata column = new ColumnMetadata(
                    columnName,
                    typeName,
                    typeOwner,
                    null,  // characterLength
                    null,  // numericPrecision
                    null,  // numericScale
                    true,  // nullable (boolean)
                    null   // defaultValue
            );
            existingTable.getColumns().add(column);
        } else {
            // Create new table
            TableMetadata table = new TableMetadata(tableSchema, tableName);

            ColumnMetadata column = new ColumnMetadata(
                    columnName,
                    typeName,
                    typeOwner,
                    null,  // characterLength
                    null,  // numericPrecision
                    null,  // numericScale
                    true,  // nullable (boolean)
                    null   // defaultValue
            );
            table.getColumns().add(column);

            existingTables.add(table);
        }

        when(mockStateService.getOracleTableMetadata()).thenReturn(existingTables);
    }

    /**
     * Sets up a table with a built-in type column (for testing mixed scenarios).
     */
    private void setupTableWithBuiltInTypeColumn(
            String tableSchema,
            String tableName,
            String columnName,
            String builtInType) {

        List<TableMetadata> existingTables = new ArrayList<>();
        if (mockStateService.getOracleTableMetadata() != null) {
            existingTables.addAll(mockStateService.getOracleTableMetadata());
        }

        // Check if table already exists
        TableMetadata existingTable = existingTables.stream()
                .filter(t -> t.getSchema().equalsIgnoreCase(tableSchema)
                        && t.getTableName().equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);

        if (existingTable != null) {
            // Add column to existing table
            ColumnMetadata column = new ColumnMetadata(
                    columnName,
                    builtInType,
                    null,  // typeOwner is null for built-in types
                    null,  // characterLength
                    null,  // numericPrecision
                    null,  // numericScale
                    true,  // nullable (boolean)
                    null   // defaultValue
            );
            existingTable.getColumns().add(column);
        } else {
            // Create new table
            TableMetadata table = new TableMetadata(tableSchema, tableName);

            ColumnMetadata column = new ColumnMetadata(
                    columnName,
                    builtInType,
                    null,  // typeOwner is null for built-in types
                    null,  // characterLength
                    null,  // numericPrecision
                    null,  // numericScale
                    true,  // nullable (boolean)
                    null   // defaultValue
            );
            table.getColumns().add(column);

            existingTables.add(table);
        }

        when(mockStateService.getOracleTableMetadata()).thenReturn(existingTables);
    }

    /**
     * Sets up a type method in StateService metadata.
     */
    private void setupTypeMethod(String typeSchema, String typeName, String methodName) {
        List<TypeMethodMetadata> existingMethods = new ArrayList<>();
        if (mockStateService.getOracleTypeMethodMetadata() != null) {
            existingMethods.addAll(mockStateService.getOracleTypeMethodMetadata());
        }

        TypeMethodMetadata method = new TypeMethodMetadata(
                typeSchema,
                typeName,
                methodName,
                "FUNCTION"  // methodType (could be FUNCTION or PROCEDURE)
        );

        existingMethods.add(method);
        when(mockStateService.getOracleTypeMethodMetadata()).thenReturn(existingMethods);
    }
}
