package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Package Segmentation feature.
 *
 * Tests the complete pipeline:
 * 1. Extract package body from Oracle (simulated)
 * 2. Scan with FunctionBoundaryScanner
 * 3. Generate stubs with FunctionStubGenerator
 * 4. Generate reduced body with PackageBodyReducer
 * 5. Store in StateService
 * 6. Retrieve and verify
 *
 * These tests validate the end-to-end workflow that happens during:
 * - OracleFunctionExtractionJob (extraction + storage)
 * - PostgresFunctionImplementationJob (retrieval + transformation)
 */
class PackageSegmentationIntegrationTest {

    private StateService stateService;
    private FunctionBoundaryScanner scanner;
    private FunctionStubGenerator stubGenerator;
    private PackageBodyReducer reducer;

    @BeforeEach
    void setUp() {
        stateService = new StateService();
        scanner = new FunctionBoundaryScanner();
        stubGenerator = new FunctionStubGenerator();
        reducer = new PackageBodyReducer();
    }

    @Test
    void endToEndPipeline_simplePackage() {
        // Simulate package body from Oracle ALL_SOURCE
        String oraclePackageBody = """
            CREATE OR REPLACE PACKAGE BODY hr.emp_pkg AS
              -- Package variable
              g_counter INTEGER := 0;

              -- Function 1
              FUNCTION get_salary(emp_id NUMBER) RETURN NUMBER IS
                v_sal NUMBER;
              BEGIN
                SELECT salary INTO v_sal FROM employees WHERE id = emp_id;
                RETURN v_sal;
              END get_salary;

              -- Function 2
              FUNCTION calc_bonus(emp_id NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN get_salary(emp_id) * 0.1;
              END calc_bonus;

              -- Procedure
              PROCEDURE update_salary(emp_id NUMBER, new_sal NUMBER) IS
              BEGIN
                UPDATE employees SET salary = new_sal WHERE id = emp_id;
                g_counter := g_counter + 1;
              END update_salary;
            END emp_pkg;
            """;

        // STEP 1: Clean source (remove comments)
        String cleaned = CodeCleaner.removeComments(oraclePackageBody);

        // STEP 2: Scan function boundaries
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(3, segments.getFunctionCount(), "Should find 3 functions/procedures");

        // STEP 3: Extract full functions and generate stubs
        Map<String, String> fullSources = new java.util.HashMap<>();
        Map<String, String> stubSources = new java.util.HashMap<>();

        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());
            fullSources.put(segment.getName().toLowerCase(), fullSource);

            String stubSource = stubGenerator.generateStub(fullSource, segment);
            stubSources.put(segment.getName().toLowerCase(), stubSource);
        }

        assertEquals(3, fullSources.size());
        assertEquals(3, stubSources.size());

        // STEP 4: Generate reduced body (all functions removed)
        String reducedBody = reducer.removeAllFunctions(cleaned, segments);
        assertTrue(reducedBody.contains("g_counter"), "Reduced body should contain variable");
        assertFalse(reducedBody.contains("FUNCTION"), "Reduced body should not contain functions");
        assertFalse(reducedBody.contains("PROCEDURE"), "Reduced body should not contain procedures");

        // STEP 5: Store in StateService
        stateService.storePackageFunctions("HR", "EMP_PKG", fullSources, stubSources, reducedBody);

        // STEP 6: Retrieve and verify
        String getSalaryFull = stateService.getPackageFunctionSource("HR", "EMP_PKG", "get_salary");
        assertNotNull(getSalaryFull);
        assertTrue(getSalaryFull.contains("SELECT salary INTO"));

        Map<String, String> allStubs = stateService.getAllPackageFunctionStubs("HR", "EMP_PKG");
        assertEquals(3, allStubs.size());
        assertTrue(allStubs.get("get_salary").contains("RETURN NULL"));

        String retrievedReduced = stateService.getReducedPackageBody("HR", "EMP_PKG");
        assertEquals(reducedBody, retrievedReduced);
    }

    @Test
    void endToEndPipeline_largePackageWithForwardDeclarations() {
        // Simulate large package with forward declarations
        String largePackage = """
            CREATE OR REPLACE PACKAGE BODY sales.order_pkg AS
              -- Package variables
              g_tax_rate NUMBER := 0.08;
              g_discount_rate NUMBER := 0.05;

              -- Forward declarations
              FUNCTION calc_tax(amount NUMBER) RETURN NUMBER;
              FUNCTION calc_discount(amount NUMBER) RETURN NUMBER;
              PROCEDURE log_order(order_id NUMBER);

              -- Full definitions
              FUNCTION get_total(order_id NUMBER) RETURN NUMBER IS
                v_subtotal NUMBER;
                v_tax NUMBER;
                v_discount NUMBER;
              BEGIN
                SELECT sum(quantity * price) INTO v_subtotal
                FROM order_items WHERE order_id = get_total.order_id;

                v_tax := calc_tax(v_subtotal);
                v_discount := calc_discount(v_subtotal);

                RETURN v_subtotal + v_tax - v_discount;
              END get_total;

              FUNCTION calc_tax(amount NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN amount * g_tax_rate;
              END calc_tax;

              FUNCTION calc_discount(amount NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN amount * g_discount_rate;
              END calc_discount;

              PROCEDURE log_order(order_id NUMBER) IS
              BEGIN
                INSERT INTO order_log VALUES (order_id, SYSDATE);
              END log_order;

              PROCEDURE process_order(order_id NUMBER) IS
                v_total NUMBER;
              BEGIN
                v_total := get_total(order_id);
                log_order(order_id);
                COMMIT;
              END process_order;
            END order_pkg;
            """;

        // Execute pipeline
        String cleaned = CodeCleaner.removeComments(largePackage);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        // Should find 5 full definitions, skip 3 forward declarations
        assertEquals(5, segments.getFunctionCount(),
                    "Should find 5 full definitions (get_total, calc_tax, calc_discount, log_order, process_order)");

        List<String> functionNames = segments.getFunctions().stream()
                .map(PackageSegments.FunctionSegment::getName)
                .toList();

        assertTrue(functionNames.contains("get_total"));
        assertTrue(functionNames.contains("calc_tax"));
        assertTrue(functionNames.contains("calc_discount"));
        assertTrue(functionNames.contains("log_order"));
        assertTrue(functionNames.contains("process_order"));

        // No duplicates from forward declarations
        assertEquals(5, functionNames.stream().distinct().count());

        // Generate stubs and reduced body
        Map<String, String> fullSources = new java.util.HashMap<>();
        Map<String, String> stubSources = new java.util.HashMap<>();

        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());
            fullSources.put(segment.getName().toLowerCase(), fullSource);

            String stubSource = stubGenerator.generateStub(fullSource, segment);
            stubSources.put(segment.getName().toLowerCase(), stubSource);
        }

        String reducedBody = reducer.removeAllFunctions(cleaned, segments);

        // Verify reduced body contains variables
        assertTrue(reducedBody.contains("g_tax_rate"));
        assertTrue(reducedBody.contains("g_discount_rate"));
        assertFalse(reducedBody.contains("get_total"));
        assertFalse(reducedBody.contains("process_order"));

        // Store and verify retrieval
        stateService.storePackageFunctions("SALES", "ORDER_PKG", fullSources, stubSources, reducedBody);

        // Verify all functions retrievable
        assertNotNull(stateService.getPackageFunctionSource("SALES", "ORDER_PKG", "get_total"));
        assertNotNull(stateService.getPackageFunctionSource("SALES", "ORDER_PKG", "calc_tax"));
        assertNotNull(stateService.getPackageFunctionSource("SALES", "ORDER_PKG", "process_order"));

        // Verify stubs are smaller than full sources
        String fullGetTotal = fullSources.get("get_total");
        String stubGetTotal = stubSources.get("get_total");
        assertTrue(stubGetTotal.length() < fullGetTotal.length(),
                  "Stub should be smaller than full source");
    }

    @Test
    void endToEndPipeline_nestedBeginEndBlocks() {
        // Test package with nested BEGIN/END blocks
        String packageWithNesting = """
            CREATE OR REPLACE PACKAGE BODY test.complex_pkg AS
              FUNCTION process_data(data_id NUMBER) RETURN NUMBER IS
                v_result NUMBER := 0;
              BEGIN
                -- Outer block
                BEGIN
                  -- Inner block 1
                  SELECT count(*) INTO v_result FROM data WHERE id = data_id;

                  IF v_result > 0 THEN
                    BEGIN
                      -- Inner block 2
                      v_result := v_result * 2;
                    END;
                  END IF;
                EXCEPTION
                  WHEN NO_DATA_FOUND THEN
                    v_result := -1;
                END;

                RETURN v_result;
              END process_data;
            END complex_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(packageWithNesting);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function despite nested blocks");
        assertEquals("process_data", segments.getFunctions().get(0).getName());

        // Extract and verify we got the complete function
        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());
        assertTrue(fullSource.contains("EXCEPTION"));
        assertTrue(fullSource.contains("NO_DATA_FOUND"));
    }

    @Test
    void endToEndPipeline_clearStorageAfterUse() {
        // Simulate extraction job storing data
        String packageBody = """
            CREATE OR REPLACE PACKAGE BODY test.temp_pkg AS
              FUNCTION func1 RETURN NUMBER IS BEGIN RETURN 1; END;
              FUNCTION func2 RETURN NUMBER IS BEGIN RETURN 2; END;
            END temp_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(packageBody);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        Map<String, String> fullSources = new java.util.HashMap<>();
        Map<String, String> stubSources = new java.util.HashMap<>();

        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());
            fullSources.put(segment.getName().toLowerCase(), fullSource);
            String stubSource = stubGenerator.generateStub(fullSource, segment);
            stubSources.put(segment.getName().toLowerCase(), stubSource);
        }

        String reducedBody = reducer.removeAllFunctions(cleaned, segments);
        stateService.storePackageFunctions("TEST", "TEMP_PKG", fullSources, stubSources, reducedBody);

        // Verify data stored
        assertNotNull(stateService.getPackageFunctionSource("TEST", "TEMP_PKG", "func1"));
        assertNotNull(stateService.getReducedPackageBody("TEST", "TEMP_PKG"));

        // Simulate transformation job clearing storage
        stateService.clearPackageFunctionStorage();

        // Verify data cleared
        assertNull(stateService.getPackageFunctionSource("TEST", "TEMP_PKG", "func1"));
        assertNull(stateService.getReducedPackageBody("TEST", "TEMP_PKG"));
    }

    @Test
    void performanceComparison_stubVsFullSource() {
        // Demonstrate stub size reduction
        String largeFunction = """
            FUNCTION calculate_complex(
              p_param1 NUMBER,
              p_param2 VARCHAR2,
              p_param3 DATE
            ) RETURN NUMBER IS
              v_result NUMBER := 0;
              v_temp1 NUMBER;
              v_temp2 VARCHAR2(100);
              CURSOR c_data IS SELECT * FROM large_table WHERE active = 'Y';
            BEGIN
              FOR rec IN c_data LOOP
                -- 50+ lines of complex logic
                v_temp1 := rec.value1 * rec.value2;
                v_temp2 := rec.description || ' processed';
                v_result := v_result + v_temp1;

                IF v_result > 1000 THEN
                  BEGIN
                    UPDATE summary_table SET total = v_result WHERE id = rec.id;
                  EXCEPTION
                    WHEN OTHERS THEN
                      ROLLBACK;
                  END;
                END IF;
              END LOOP;

              RETURN v_result;
            EXCEPTION
              WHEN NO_DATA_FOUND THEN
                RETURN -1;
              WHEN OTHERS THEN
                RETURN -999;
            END calculate_complex;
            """;

        String cleaned = CodeCleaner.removeComments(largeFunction);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(1, segments.getFunctionCount());

        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());
        String stubSource = stubGenerator.generateStub(fullSource, segment);

        // Verify stub is significantly smaller
        int fullSize = fullSource.length();
        int stubSize = stubSource.length();
        double reduction = (1.0 - ((double) stubSize / fullSize)) * 100;

        assertTrue(reduction > 70,
                  String.format("Stub should be >70%% smaller (actual: %.1f%% reduction)", reduction));

        System.out.printf("Performance: Full=%d chars, Stub=%d chars, Reduction=%.1f%%%n",
                         fullSize, stubSize, reduction);
    }
}
