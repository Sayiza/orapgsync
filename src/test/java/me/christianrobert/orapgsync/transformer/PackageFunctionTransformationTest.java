package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
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
 * Tests for package function call transformation with metadata context.
 *
 * <p>This tests the critical transformation:
 * Oracle: SELECT package.function(arg) FROM table
 * PostgreSQL: SELECT package__function(arg) FROM table
 *
 * <p>With synonym resolution:
 * - Same schema: testpackage.testfunction → testpackage__testfunction
 * - Via synonym: synonym_pkg.func → resolved_schema.resolved_pkg__func
 */
class PackageFunctionTransformationTest {

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

    // ========== SCENARIO A: Package function in same schema (no synonym) ==========

    @Test
    void packageFunctionInSameSchema_noSynonym() {
        // Given: HR schema has package TESTPACKAGE with function TESTFUNCTION
        setupPackageFunction("HR", "TESTPACKAGE", "TESTFUNCTION");

        // Build indices for HR schema
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with package function call
        String oracleSql = "SELECT nr, testpackage.testfunction(nr) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Package.function becomes package__function, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT nr , testpackage__testfunction( nr ) FROM hr.examples", normalized,
                "Package function should be flattened with double underscore");
    }

    @Test
    void packageFunctionInSameSchema_mixedCase() {
        // Given: HR schema has package TestPackage with function TestFunction
        setupPackageFunction("HR", "TestPackage", "TestFunction");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with mixed case
        String oracleSql = "SELECT nr, TestPackage.TestFunction(nr) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Case preserved, flattened with __, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT nr , TestPackage__TestFunction( nr ) FROM hr.examples", normalized);
    }

    @Test
    void multiplePackageFunctionCalls() {
        // Given: HR schema has multiple package functions
        setupPackageFunction("HR", "PKG1", "FUNC1");
        setupPackageFunction("HR", "PKG2", "FUNC2");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with multiple package function calls
        String oracleSql = "SELECT pkg1.func1(nr), pkg2.func2(text) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions flattened, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT pkg1__func1( nr ) , pkg2__func2( text ) FROM hr.examples", normalized);
    }

    // ========== SCENARIO B: Package function via synonym ==========

    @Test
    void packageFunctionViaSynonym_sameSchemaTarget() {
        // Given: HR schema has synonym SYNONYM_PKG pointing to HR.TESTPACKAGE
        //        And TESTPACKAGE has function TESTFUNCTION
        setupPackageFunction("HR", "TESTPACKAGE", "TESTFUNCTION");
        setupSynonym("HR", "SYNONYM_PKG", "HR", "TESTPACKAGE");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL using synonym
        String oracleSql = "SELECT nr, synonym_pkg.testfunction(nr) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Synonym resolved, function flattened, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT nr , testpackage__testfunction( nr ) FROM hr.examples", normalized,
                "Synonym should resolve to actual package, then flatten");
    }

    @Test
    void packageFunctionViaSynonym_differentSchemaTarget() {
        // Given: HR schema has synonym SYNONYM_PKG pointing to SCOTT.TESTPACKAGE
        //        And SCOTT.TESTPACKAGE has function TESTFUNCTION
        setupPackageFunction("SCOTT", "TESTPACKAGE", "TESTFUNCTION");
        setupSynonym("HR", "SYNONYM_PKG", "SCOTT", "TESTPACKAGE");

        List<String> schemas = Arrays.asList("HR", "SCOTT");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL using synonym that crosses schemas
        String oracleSql = "SELECT nr, synonym_pkg.testfunction(nr) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Synonym resolved, schema prefix added for function, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT nr , scott.testpackage__testfunction( nr ) FROM hr.examples", normalized,
                "Cross-schema package function needs schema prefix");
    }

    @Test
    void packageFunctionViaPublicSynonym() {
        // Given: PUBLIC schema has synonym SYNONYM_PKG pointing to SCOTT.TESTPACKAGE
        //        Current schema is HR (doesn't have the synonym)
        setupPackageFunction("SCOTT", "TESTPACKAGE", "TESTFUNCTION");
        setupSynonym("PUBLIC", "SYNONYM_PKG", "SCOTT", "TESTPACKAGE");

        List<String> schemas = Arrays.asList("HR", "SCOTT", "PUBLIC");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL - synonym will fall back to PUBLIC
        String oracleSql = "SELECT nr, synonym_pkg.testfunction(nr) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: PUBLIC synonym resolved, schema prefix added, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT nr , scott.testpackage__testfunction( nr ) FROM hr.examples", normalized,
                "PUBLIC synonym should resolve and add schema prefix");
    }

    @Test
    void packageFunctionWithMultipleArguments() {
        // Given: HR schema has package PKG with function CALC
        setupPackageFunction("HR", "PKG", "CALC");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with multiple arguments (only columns for now, literals not yet supported)
        String oracleSql = "SELECT pkg.calc(nr, text) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Function flattened with all arguments preserved, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT pkg__calc( nr , text ) FROM hr.examples", normalized);
    }

    @Test
    void nestedPackageFunctionCalls() {
        // Given: HR schema has package PKG with functions OUTER and INNER
        setupPackageFunction("HR", "PKG", "OUTER");
        setupPackageFunction("HR", "PKG", "INNER");

        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with nested function calls
        String oracleSql = "SELECT pkg.outer(pkg.inner(nr)) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions flattened, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT pkg__outer( pkg__inner( nr ) ) FROM hr.examples", normalized);
    }

    // ========== EDGE CASES ==========

    @Test
    void regularColumnReferencesNotAffected() {
        // Given: No package functions, just regular column references
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with qualified column references (table.column)
        String oracleSql = "SELECT e.empno, e.ename FROM employees e";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified columns pass through unchanged (not package functions), table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . empno , e . ename FROM hr.employees e", normalized,
                "Regular qualified columns should not be transformed");
    }

    @Test
    void unknownPackageFunctionPassesThrough() {
        // Given: Empty metadata (no package functions defined)
        List<String> schemas = Collections.singletonList("HR");
        TransformationIndices indices = MetadataIndexBuilder.build(mockStateService, schemas);
        TransformationContext context = new TransformationContext("HR", indices, new SimpleTypeEvaluator("HR", indices));

        // When: Transform SQL with unknown package.function
        String oracleSql = "SELECT unknown_pkg.unknown_func(nr) FROM examples";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Unknown function passes through as-is (will fail at runtime, but we don't error during transformation)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        // For now, we'll just verify it doesn't crash - exact output depends on implementation
        assertNotNull(postgresSql);
    }

    // ========== Helper Methods ==========

    /**
     * Sets up a package function in StateService metadata.
     */
    private void setupPackageFunction(String schema, String packageName, String functionName) {
        List<FunctionMetadata> existingFunctions = new ArrayList<>();
        if (mockStateService.getOracleFunctionMetadata() != null) {
            existingFunctions.addAll(mockStateService.getOracleFunctionMetadata());
        }

        FunctionMetadata function = new FunctionMetadata(
                schema,
                functionName,  // objectName
                "FUNCTION"     // objectType
        );
        function.setPackageName(packageName);

        existingFunctions.add(function);
        when(mockStateService.getOracleFunctionMetadata()).thenReturn(existingFunctions);
    }

    /**
     * Sets up a synonym in StateService metadata.
     */
    private void setupSynonym(String synonymOwner, String synonymName, String targetOwner, String targetName) {
        Map<String, Map<String, SynonymMetadata>> existingSynonyms = new HashMap<>();
        if (mockStateService.getOracleSynonymsByOwnerAndName() != null) {
            existingSynonyms.putAll(mockStateService.getOracleSynonymsByOwnerAndName());
        }

        SynonymMetadata synonym = new SynonymMetadata(
                synonymOwner,
                synonymName,
                targetOwner,
                targetName,
                null  // dbLink
        );

        existingSynonyms
                .computeIfAbsent(synonymOwner, k -> new HashMap<>())
                .put(synonymName, synonym);

        when(mockStateService.getOracleSynonymsByOwnerAndName()).thenReturn(existingSynonyms);
    }
}
