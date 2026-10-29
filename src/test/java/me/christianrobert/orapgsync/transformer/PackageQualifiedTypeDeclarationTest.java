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
 *   <li>packageName.typeName → schemaName.packageName.typeName</li>
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
        PackageContext empPkgContext = new PackageContext("test_schema_name", "emp_pkg");

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
        packageContextCache.put("test_schema_name.emp_pkg", empPkgContext);

        // Set up another package without any inline types (for composite type testing)
        PackageContext docProcessContext = new PackageContext("test_schema_name", "docflow");
        packageContextCache.put("test_schema_name.docflow", docProcessContext);

        // Create indices with some object types to test disambiguation
        Set<String> objectTypeNames = new HashSet<>();
        objectTypeNames.add("test_schema_name.address_type");  // Schema-qualified object type
        objectTypeNames.add("hr.employee_type");

        // Create synonyms map: doc_fw.docflow -> test_schema_name.docflow
        // This simulates the real-world scenario where a package in another schema
        // is accessed via a synonym
        Map<String, Map<String, String>> synonyms = new HashMap<>();

        // Public synonyms (accessible from any schema)
        Map<String, String> publicSynonyms = new HashMap<>();
        publicSynonyms.put("docflow", "test_schema_name.docflow");
        synonyms.put("public", publicSynonyms);

        indices = new TransformationIndices(
            Collections.emptyMap(),  // tableColumns
            Collections.emptyMap(),  // typeMethods
            Collections.emptySet(),  // packageFunctions
            synonyms,                // synonyms - now includes docflow -> test_schema_name.docflow
            Collections.emptyMap(),  // typeFieldTypes
            objectTypeNames          // objectTypeNames
        );

        typeEvaluator = new SimpleTypeEvaluator("test_schema_name", indices);
    }

    /**
     * Test: Package-qualified type declaration defaults to jsonb.
     *
     * <p>When a package type is not found in PackageContext (e.g., SUBTYPE, unextracted type),
     * it defaults to jsonb following the "all complex types to jsonb" strategy.</p>
     *
     * <p>Oracle: vProcess packageName.typeName;</p>
     * <p>PostgreSQL: vprocess jsonb;</p>
     */
    @Test
    void packageQualifiedType_defaultsToJsonb() {
        String plsql = """
            PROCEDURE runprocess(pBatchRef NUMBER) IS
              vProcess packageName.typeName;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "runprocess", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Procedure ===");
        System.out.println(postgresSql);
        System.out.println("============================");

        // Package type not in PackageContext should default to jsonb
        assertTrue(postgresSql.toLowerCase().contains("vprocess jsonb"),
            "Unresolved package type should default to jsonb");
        assertFalse(postgresSql.toLowerCase().contains("docflow__process"),
            "Should not contain flattened package__type (that's for functions, not types)");
    }

    /**
     * Test: a collection type Oracle's own OWA package declares (ROADMAP P1.7).
     *
     * <p>Oracle: vNames OWA.VC_ARR;  (TABLE OF VARCHAR2(32000) INDEX BY BINARY_INTEGER)</p>
     * <p>PostgreSQL: vnames jsonb := '{}'::jsonb; — as any package's associative array.
     * It used to name oracle_compat.owa__vc_arr, which nothing creates.</p>
     */
    @Test
    void oracleCompatPackageType_isDeclaredCollection() {
        String plsql = """
            PROCEDURE test_proc IS
              vNames OWA.VC_ARR;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed OWA Type ===");
        System.out.println(postgresSql);
        System.out.println("============================");

        assertTrue(postgresSql.toLowerCase().contains("vnames jsonb := '{}'::jsonb"),
            "OWA.VC_ARR is an associative array: jsonb with an empty-object initializer");
        assertFalse(postgresSql.toLowerCase().contains("oracle_compat"),
            "the compatibility layer creates no types");
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
            "test_schema_name", indices, typeEvaluator,
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
     * <p>Oracle: vAddr test_schema_name.ADDRESS_TYPE;</p>
     * <p>PostgreSQL: vaddr test_schema_name.address_type; (NOT flattened)</p>
     */
    @Test
    void schemaQualifiedObjectType_notFlattened() {
        String plsql = """
            PROCEDURE test_proc IS
              vAddr test_schema_name.ADDRESS_TYPE;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Object Type ===");
        System.out.println(postgresSql);
        System.out.println("==============================");

        // Schema-qualified object type should NOT be flattened (no double underscore)
        assertTrue(postgresSql.toLowerCase().contains("test_schema_name.address_type"),
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
            "test_schema_name", indices, typeEvaluator,
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
     * Test: types of several Oracle compat packages, and one that no Oracle package declares.
     *
     * <p>UTL_FILE.FILE_TYPE is the handle the compat layer's utl_file__fopen returns as integer.
     * HTP.PageType is not a type HTP declares, so it is not guessed into existence: the visitor
     * layer gets the jsonb any undeclared package type gets.</p>
     */
    @Test
    void multipleOracleCompatPackageTypes() {
        String plsql = """
            PROCEDURE test_proc IS
              vLines DBMS_OUTPUT.CHARARR;
              vNums dbms_utility.number_array;
              vFile UTL_FILE.FILE_TYPE;
              vPage HTP.PageType;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Multiple Oracle Compat Types ===");
        System.out.println(postgresSql);
        System.out.println("================================================");

        String sql = postgresSql.toLowerCase();
        assertTrue(sql.contains("vlines jsonb := '{}'::jsonb"), "DBMS_OUTPUT.CHARARR");
        assertTrue(sql.contains("vnums jsonb := '{}'::jsonb"), "DBMS_UTILITY.NUMBER_ARRAY, any case");
        assertTrue(sql.contains("vfile integer"), "UTL_FILE.FILE_TYPE is the compat layer's handle");
        assertTrue(sql.contains("vpage jsonb;"), "an undeclared type gets the plain jsonb fallback");
        assertFalse(sql.contains("oracle_compat"), "the compatibility layer creates no types");
    }

    /**
     * Test: Case insensitivity for package names - all default to jsonb.
     */
    @Test
    void caseInsensitivePackageName() {
        String plsql = """
            PROCEDURE test_proc IS
              vProcess packageName.typeName;
              vProcess2 packageName.typeName;
              vProcess3 packageName.typeName;
            BEGIN
              NULL;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "test_proc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Transformed Case Variations ===");
        System.out.println(postgresSql);
        System.out.println("===================================");

        // All case variations should result in jsonb (unresolved package types)
        String lowered = postgresSql.toLowerCase();
        int count = countOccurrences(lowered, "jsonb");
        assertEquals(3, count, "All three declarations should default to jsonb");
    }

    /**
     * Test: Full procedure transformation matches expected output.
     *
     * <p>This is the original example from the issue.</p>
     * <p>Variable types default to jsonb, function calls are flattened.</p>
     */
    @Test
    void fullProcedure_matchesExpectedOutput() {
        String plsql = """
            PROCEDURE RUNPROCESS(pBatchRef NUMBER) IS
              vProcess packageName.typeName;
            BEGIN
              vProcess := DocFlow.startRunByNr(pBatchRef);
              DocFlow.execute(vProcess);
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "runprocess", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Full Procedure Transformation ===");
        System.out.println(postgresSql);
        System.out.println("=====================================");

        // Variable declaration should use jsonb (package type not in PackageContext)
        assertTrue(postgresSql.toLowerCase().contains("vprocess jsonb"),
            "Variable type should default to jsonb");

        // Function calls should still be flattened (existing functionality)
        assertTrue(postgresSql.toLowerCase().contains("docflow__startrunbynr"),
            "Function call should be flattened");
        assertTrue(postgresSql.toLowerCase().contains("docflow__execute"),
            "Procedure call should be flattened");
    }

    /**
     * Test: Synonym resolution from a different schema - still defaults to jsonb.
     *
     * <p>Even with synonym resolution, unresolved package types default to jsonb.
     * The synonym resolution is still used for PackageContext lookup, but if the
     * type isn't found, we default to jsonb.</p>
     *
     * <p>Oracle (doc_fw schema): vProcess packageName.typeName;</p>
     * <p>Synonym: DocFlow -> test_schema_name.docflow</p>
     * <p>Expected PostgreSQL: vprocess jsonb; (type not in PackageContext)</p>
     */
    @Test
    void synonymResolution_fromDifferentSchema_defaultsToJsonb() {
        String plsql = """
            PROCEDURE RUNPROCESS(pBatchRef NUMBER) IS
              vProcess packageName.typeName;
            BEGIN
              vProcess := DocFlow.startRunByNr(pBatchRef);
              DocFlow.execute(vProcess);
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        // Use doc_fw as the current schema (NOT test_schema_name)
        // The synonym should resolve DocFlow -> test_schema_name.docflow
        SimpleTypeEvaluator docFwTypeEvaluator = new SimpleTypeEvaluator("doc_fw", indices);

        TransformationContext context = new TransformationContext(
            "doc_fw", indices, docFwTypeEvaluator,
            packageContextCache, "runprocess", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Synonym Resolution from doc_fw ===");
        System.out.println(postgresSql);
        System.out.println("=============================================");

        // Variable declaration should default to jsonb (type not in PackageContext)
        assertTrue(postgresSql.toLowerCase().contains("vprocess jsonb"),
            "Unresolved package type should default to jsonb");

        // Function calls should still use synonym-resolved schema
        assertTrue(postgresSql.toLowerCase().contains("test_schema_name.docflow__startrunbynr"),
            "Function call should use schema from synonym resolution");
    }

    // ========== Parameter Type Tests ==========

    /**
     * Test: Function parameter with package-qualified type defaults to jsonb.
     *
     * <p>Oracle: FUNCTION process_doc(pDoc DocFlow.Document) RETURN NUMBER</p>
     * <p>PostgreSQL: FUNCTION process_doc(pdoc jsonb) RETURNS numeric</p>
     */
    @Test
    void parameterWithPackageQualifiedType_defaultsToJsonb() {
        String plsql = """
            FUNCTION process_doc(pDoc DocFlow.Document) RETURN NUMBER IS
            BEGIN
              RETURN 1;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "process_doc", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Parameter with Package Type ===");
        System.out.println(postgresSql);
        System.out.println("===================================");

        // Parameter type should default to jsonb
        assertTrue(postgresSql.toLowerCase().contains("pdoc jsonb"),
            "Parameter with package type should default to jsonb");
    }

    /**
     * Test: Function parameter with Oracle compat package type.
     *
     * <p>Oracle: FUNCTION render_page(name_array OWA.VC_ARR) RETURN NUMBER</p>
     * <p>PostgreSQL: FUNCTION render_page(name_array jsonb) RETURNS numeric</p>
     */
    @Test
    void parameterWithOracleCompatType_isJsonb() {
        String plsql = """
            FUNCTION render_page(name_array OWA.VC_ARR) RETURN NUMBER IS
            BEGIN
              RETURN 1;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "render_page", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Parameter with Oracle Compat Type ===");
        System.out.println(postgresSql);
        System.out.println("=========================================");

        assertTrue(postgresSql.toLowerCase().contains("name_array jsonb"),
            "OWA.VC_ARR parameter is jsonb");
    }

    // ========== Return Type Tests ==========

    /**
     * Test: Function return type with package-qualified type defaults to jsonb.
     *
     * <p>Oracle: FUNCTION get_document(pId NUMBER) RETURN DocFlow.Document</p>
     * <p>PostgreSQL: FUNCTION get_document(pid numeric) RETURNS jsonb</p>
     */
    @Test
    void returnTypeWithPackageQualifiedType_defaultsToJsonb() {
        String plsql = """
            FUNCTION get_document(pId NUMBER) RETURN DocFlow.Document IS
            BEGIN
              RETURN NULL;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "get_document", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Return Type with Package Type ===");
        System.out.println(postgresSql);
        System.out.println("=====================================");

        // Return type should default to jsonb
        assertTrue(postgresSql.toLowerCase().contains("returns jsonb"),
            "Return type with package type should default to jsonb");
    }

    /**
     * Test: Function return type with Oracle compat package type.
     *
     * <p>Oracle: FUNCTION create_page RETURN HTP.HTBUF_ARR</p>
     * <p>PostgreSQL: FUNCTION create_page() RETURNS jsonb</p>
     */
    @Test
    void returnTypeWithOracleCompatType_isJsonb() {
        String plsql = """
            FUNCTION create_page RETURN HTP.HTBUF_ARR IS
            BEGIN
              RETURN NULL;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "create_page", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Return Type with Oracle Compat Type ===");
        System.out.println(postgresSql);
        System.out.println("===========================================");

        assertTrue(postgresSql.toLowerCase().contains("returns jsonb"),
            "HTP.HTBUF_ARR return type is jsonb");
    }

    /**
     * Test: Function with both parameter and return type as package types.
     *
     * <p>Oracle: FUNCTION process(pDoc DocFlow.Document) RETURN DocFlow.Result</p>
     * <p>PostgreSQL: FUNCTION process(pdoc jsonb) RETURNS jsonb</p>
     */
    @Test
    void fullFunctionWithPackageTypes_allDefaultToJsonb() {
        String plsql = """
            FUNCTION process_and_return(pDoc DocFlow.Document) RETURN DocFlow.Result IS
              vTemp DocFlow.TempData;
            BEGIN
              RETURN NULL;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        TransformationContext context = new TransformationContext(
            "test_schema_name", indices, typeEvaluator,
            packageContextCache, "process_and_return", null
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("=== Full Function with Package Types ===");
        System.out.println(postgresSql);
        System.out.println("========================================");

        // All package types should default to jsonb
        assertTrue(postgresSql.toLowerCase().contains("pdoc jsonb"),
            "Parameter type should default to jsonb");
        assertTrue(postgresSql.toLowerCase().contains("returns jsonb"),
            "Return type should default to jsonb");
        assertTrue(postgresSql.toLowerCase().contains("vtemp jsonb"),
            "Variable type should default to jsonb");
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
