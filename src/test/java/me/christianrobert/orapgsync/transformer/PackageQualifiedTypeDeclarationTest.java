package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for package-qualified type declarations in variable declarations.
 *
 * <p>Validates that Oracle package.type syntax is correctly transformed to
 * PostgreSQL schema.package__type (flattened) naming convention.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>puDocProcess.Process → co_sys_libs.pudocprocess__process</li>
 *   <li>HTP.PageType → oracle_compat.htp__pagetype</li>
 *   <li>emp_pkg.emp_rec (RECORD type) → jsonb</li>
 * </ul>
 */
class PackageQualifiedTypeDeclarationTest {

    private AntlrParser parser;
    private Map<String, PackageContext> packageContextCache;
    private TransformationIndices indices;
    private SimpleTypeEvaluator typeEvaluator;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        packageContextCache = new HashMap<>();

        // Set up a package context with an inline type (RECORD)
        PackageContext empPkgContext = new PackageContext("co_sys_libs", "emp_pkg");

        // Add a RECORD type to the package
        InlineTypeDefinition empRecordType = new InlineTypeDefinition(
            "emp_rec",
            TypeCategory.RECORD,
            null,  // No element type
            Collections.emptyList(),  // Empty fields (simplified for test)
            ConversionStrategy.JSONB,
            null,  // No size limit
            null   // No index key type
        );
        empPkgContext.addType(empRecordType);
        packageContextCache.put("co_sys_libs.emp_pkg", empPkgContext);

        // Set up another package without any inline types (for composite type testing)
        PackageContext docProcessContext = new PackageContext("co_sys_libs", "pudocprocess");
        packageContextCache.put("co_sys_libs.pudocprocess", docProcessContext);

        // Create indices with some object types to test disambiguation
        Set<String> objectTypeNames = new HashSet<>();
        objectTypeNames.add("co_sys_libs.address_type");  // Schema-qualified object type
        objectTypeNames.add("hr.employee_type");

        // Create synonyms map: in_framework.pudocprocess -> co_sys_libs.pudocprocess
        // This simulates the real-world scenario where a package in another schema
        // is accessed via a synonym
        Map<String, Map<String, String>> synonyms = new HashMap<>();

        // Public synonyms (accessible from any schema)
        Map<String, String> publicSynonyms = new HashMap<>();
        publicSynonyms.put("pudocprocess", "co_sys_libs.pudocprocess");
        synonyms.put("public", publicSynonyms);

        indices = new TransformationIndices(
            Collections.emptyMap(),  // tableColumns
            Collections.emptyMap(),  // typeMethods
            Collections.emptySet(),  // packageFunctions
            synonyms,                // synonyms - now includes pudocprocess -> co_sys_libs.pudocprocess
            Collections.emptyMap(),  // typeFieldTypes
            objectTypeNames          // objectTypeNames
        );

        typeEvaluator = new SimpleTypeEvaluator("co_sys_libs", indices);
    }

    /**
     * Test: Package-qualified type declaration flattening.
     *
     * <p>Oracle: vProcess puDocProcess.Process;</p>
     * <p>PostgreSQL: vprocess co_sys_libs.pudocprocess__process;</p>
     */
    @Test
    void packageQualifiedType_flattenedWithSchema() {
        String plsql = """
            PROCEDURE docpexecute(pProcessNr NUMBER) IS
              vProcess puDocProcess.Process;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "docpexecute", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Procedure ===");
        System.out.println(postgresSql);
        System.out.println("============================");

        // Should flatten package.type to schema.package__type
        assertTrue(postgresSql.toLowerCase().contains("co_sys_libs.pudocprocess__process"),
            "Package-qualified type should be flattened to schema.package__type");
        assertFalse(postgresSql.toLowerCase().contains("pudocprocess.process"),
            "Should not contain unqualified package.type");
    }

    /**
     * Test: Oracle compatibility package type.
     *
     * <p>Oracle: vPage HTP.PageType;</p>
     * <p>PostgreSQL: vpage oracle_compat.htp__pagetype;</p>
     */
    @Test
    void oracleCompatPackageType_usesOracleCompatSchema() {
        String plsql = """
            PROCEDURE test_proc IS
              vPage HTP.PageType;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed HTP Type ===");
        System.out.println(postgresSql);
        System.out.println("============================");

        // Should use oracle_compat schema for HTP package types
        assertTrue(postgresSql.toLowerCase().contains("oracle_compat.htp__pagetype"),
            "HTP package type should use oracle_compat schema");
    }

    /**
     * Test: Package inline type (RECORD) resolves to jsonb.
     *
     * <p>Oracle: vEmp emp_pkg.emp_rec; (where emp_rec is a RECORD type in emp_pkg)</p>
     * <p>PostgreSQL: vemp jsonb := '{}'::jsonb;</p>
     */
    @Test
    void packageInlineType_resolvesToJsonb() {
        String plsql = """
            PROCEDURE test_proc IS
              vEmp emp_pkg.emp_rec;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Package RECORD Type ===");
        System.out.println(postgresSql);
        System.out.println("=======================================");

        // RECORD type from package should resolve to jsonb
        assertTrue(postgresSql.toLowerCase().contains("vemp jsonb"),
            "Package RECORD type should resolve to jsonb");
        // Should have initialization
        assertTrue(postgresSql.contains("'{}'::jsonb"),
            "Should have jsonb initialization for RECORD type");
    }

    /**
     * Test: Schema-qualified object type is NOT flattened.
     *
     * <p>Oracle: vAddr CO_SYS_LIBS.ADDRESS_TYPE;</p>
     * <p>PostgreSQL: vaddr co_sys_libs.address_type; (NOT flattened)</p>
     */
    @Test
    void schemaQualifiedObjectType_notFlattened() {
        String plsql = """
            PROCEDURE test_proc IS
              vAddr CO_SYS_LIBS.ADDRESS_TYPE;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Object Type ===");
        System.out.println(postgresSql);
        System.out.println("==============================");

        // Schema-qualified object type should NOT be flattened (no double underscore)
        assertTrue(postgresSql.toLowerCase().contains("co_sys_libs.address_type"),
            "Object type should keep schema.type format");
        assertFalse(postgresSql.contains("__"),
            "Object type should NOT have double underscore flattening");
    }

    /**
     * Test: Simple type (no dot) uses TypeConverter.
     *
     * <p>Oracle: vCount NUMBER;</p>
     * <p>PostgreSQL: vcount numeric;</p>
     */
    @Test
    void simpleType_usesTypeConverter() {
        String plsql = """
            PROCEDURE test_proc IS
              vCount NUMBER;
              vName VARCHAR2(100);
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Simple Types ===");
        System.out.println(postgresSql);
        System.out.println("================================");

        // Simple types should use TypeConverter
        assertTrue(postgresSql.toLowerCase().contains("vcount numeric"),
            "NUMBER should convert to numeric");
        assertTrue(postgresSql.toLowerCase().contains("vname text"),
            "VARCHAR2 should convert to text");
    }

    /**
     * Test: Multiple Oracle compat packages (HTP, DBMS_OUTPUT, etc.)
     */
    @Test
    void multipleOracleCompatPackageTypes() {
        String plsql = """
            PROCEDURE test_proc IS
              vPage HTP.PageType;
              vBuffer DBMS_OUTPUT.BufferType;
              vLob DBMS_LOB.LobType;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Multiple Oracle Compat Types ===");
        System.out.println(postgresSql);
        System.out.println("================================================");

        // All should use oracle_compat schema
        assertTrue(postgresSql.toLowerCase().contains("oracle_compat.htp__pagetype"),
            "HTP type should use oracle_compat schema");
        assertTrue(postgresSql.toLowerCase().contains("oracle_compat.dbms_output__buffertype"),
            "DBMS_OUTPUT type should use oracle_compat schema");
        assertTrue(postgresSql.toLowerCase().contains("oracle_compat.dbms_lob__lobtype"),
            "DBMS_LOB type should use oracle_compat schema");
    }

    /**
     * Test: Case insensitivity for package names.
     */
    @Test
    void caseInsensitivePackageName() {
        String plsql = """
            PROCEDURE test_proc IS
              vProcess PUDOCPROCESS.Process;
              vProcess2 PuDocProcess.Process;
              vProcess3 pudocprocess.process;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Case Variations ===");
        System.out.println(postgresSql);
        System.out.println("===================================");

        // All case variations should result in the same lowercase flattened type
        String lowered = postgresSql.toLowerCase();
        int count = countOccurrences(lowered, "co_sys_libs.pudocprocess__process");
        assertEquals(3, count, "All three declarations should have same flattened type");
    }

    /**
     * Test: Full procedure transformation matches expected output.
     *
     * <p>This is the original example from the issue.</p>
     */
    @Test
    void fullProcedure_matchesExpectedOutput() {
        String plsql = """
            PROCEDURE DOCPEXECUTE(pProcessNr NUMBER) IS
              vProcess puDocProcess.Process;
            BEGIN
              vProcess := puDocProcess.newProcessByNr(pProcessNr);
              puDocProcess.execute(vProcess);
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "co_sys_libs", indices, typeEvaluator,
            packageContextCache, "docpexecute", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Full Procedure Transformation ===");
        System.out.println(postgresSql);
        System.out.println("=====================================");

        // Variable declaration should use flattened type
        assertTrue(postgresSql.toLowerCase().contains("vprocess co_sys_libs.pudocprocess__process"),
            "Variable type should be flattened");

        // Function calls should also be flattened (existing functionality)
        assertTrue(postgresSql.toLowerCase().contains("pudocprocess__newprocessbynr"),
            "Function call should be flattened");
        assertTrue(postgresSql.toLowerCase().contains("pudocprocess__execute"),
            "Procedure call should be flattened");
    }

    /**
     * Test: Synonym resolution from a different schema.
     *
     * <p>This tests the actual issue scenario: procedure in in_framework uses
     * puDocProcess.Process which should resolve via synonym to co_sys_libs.pudocprocess__process</p>
     *
     * <p>Oracle (in_framework schema): vProcess puDocProcess.Process;</p>
     * <p>Synonym: puDocProcess -> co_sys_libs.pudocprocess</p>
     * <p>Expected PostgreSQL: vprocess co_sys_libs.pudocprocess__process;</p>
     */
    @Test
    void synonymResolution_fromDifferentSchema() {
        String plsql = """
            PROCEDURE DOCPEXECUTE(pProcessNr NUMBER) IS
              vProcess puDocProcess.Process;
            BEGIN
              vProcess := puDocProcess.newProcessByNr(pProcessNr);
              puDocProcess.execute(vProcess);
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        // Use in_framework as the current schema (NOT co_sys_libs)
        // The synonym should resolve puDocProcess -> co_sys_libs.pudocprocess
        SimpleTypeEvaluator inFrameworkTypeEvaluator = new SimpleTypeEvaluator("in_framework", indices);

        TransformationContext context = new TransformationContext(
            "in_framework", indices, inFrameworkTypeEvaluator,
            packageContextCache, "docpexecute", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Synonym Resolution from in_framework ===");
        System.out.println(postgresSql);
        System.out.println("=============================================");

        // Variable declaration should resolve via synonym to co_sys_libs.pudocprocess__process
        // NOT in_framework.pudocprocess__process
        assertTrue(postgresSql.toLowerCase().contains("vprocess co_sys_libs.pudocprocess__process"),
            "Variable type should use schema from synonym resolution (co_sys_libs), not current schema (in_framework)");
        assertFalse(postgresSql.toLowerCase().contains("in_framework.pudocprocess__process"),
            "Should NOT use current schema for synonym-resolved packages");
    }

    // Helper method to count occurrences of a substring
    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
