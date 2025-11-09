package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PackageBodyReducer.
 *
 * Verifies that function removal:
 * - Preserves package structure
 * - Preserves variables, types, constants
 * - Removes all function/procedure implementations
 * - Results in dramatically smaller package body
 */
class PackageBodyReducerTest {

    private final FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();
    private final PackageBodyReducer reducer = new PackageBodyReducer();

    @Test
    void removeAllFunctions_singleFunction() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_counter INTEGER := 0;

              FUNCTION get_value RETURN NUMBER IS
              BEGIN
                RETURN g_counter;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Verify package structure preserved
        assertTrue(reduced.contains("CREATE OR REPLACE PACKAGE BODY test_pkg AS"));
        assertTrue(reduced.contains("END test_pkg;"));

        // Verify variable preserved
        assertTrue(reduced.contains("g_counter INTEGER := 0"));

        // Verify function removed
        assertFalse(reduced.contains("FUNCTION get_value"));
        assertFalse(reduced.contains("RETURN g_counter"));
    }

    @Test
    void removeAllFunctions_multipleFunctions() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_counter INTEGER := 0;
              g_status VARCHAR2(20) := 'ACTIVE';

              FUNCTION func1 RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END;

              FUNCTION func2 RETURN NUMBER IS
              BEGIN
                RETURN 2;
              END;

              PROCEDURE proc1 IS
              BEGIN
                NULL;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(3, segments.getFunctionCount());

        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Verify variables preserved
        assertTrue(reduced.contains("g_counter INTEGER := 0"));
        assertTrue(reduced.contains("g_status VARCHAR2(20) := 'ACTIVE'"));

        // Verify all functions removed
        assertFalse(reduced.contains("FUNCTION func1"));
        assertFalse(reduced.contains("FUNCTION func2"));
        assertFalse(reduced.contains("PROCEDURE proc1"));
        assertFalse(reduced.contains("RETURN 1"));
        assertFalse(reduced.contains("RETURN 2"));
    }

    @Test
    void removeAllFunctions_noFunctions() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_counter INTEGER := 0;
              g_status VARCHAR2(20) := 'ACTIVE';
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(0, segments.getFunctionCount());

        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Should return unchanged (no functions to remove)
        assertEquals(cleaned, reduced);
    }

    @Test
    void removeAllFunctions_functionsWithVariablesBetween() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_var1 INTEGER := 1;

              FUNCTION func1 RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END;

              g_var2 INTEGER := 2;

              FUNCTION func2 RETURN NUMBER IS
              BEGIN
                RETURN 2;
              END;

              g_var3 INTEGER := 3;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Verify all variables preserved in correct order
        assertTrue(reduced.contains("g_var1 INTEGER := 1"));
        assertTrue(reduced.contains("g_var2 INTEGER := 2"));
        assertTrue(reduced.contains("g_var3 INTEGER := 3"));

        // Verify order preserved
        int pos1 = reduced.indexOf("g_var1");
        int pos2 = reduced.indexOf("g_var2");
        int pos3 = reduced.indexOf("g_var3");
        assertTrue(pos1 < pos2 && pos2 < pos3, "Variables should maintain original order");

        // Verify functions removed
        assertFalse(reduced.contains("FUNCTION func1"));
        assertFalse(reduced.contains("FUNCTION func2"));
    }

    @Test
    void removeAllFunctions_preservesTypeDeclarations() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              TYPE rec_t IS RECORD (
                id NUMBER,
                name VARCHAR2(100)
              );

              g_rec rec_t;

              FUNCTION get_id RETURN NUMBER IS
              BEGIN
                RETURN g_rec.id;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Verify type declaration preserved
        assertTrue(reduced.contains("TYPE rec_t IS RECORD"));
        assertTrue(reduced.contains("id NUMBER"));
        assertTrue(reduced.contains("name VARCHAR2(100)"));

        // Verify variable using type preserved
        assertTrue(reduced.contains("g_rec rec_t"));

        // Verify function removed
        assertFalse(reduced.contains("FUNCTION get_id"));
    }

    @Test
    void removeAllFunctions_sizeReduction() {
        // Create package with large functions
        StringBuilder largePackage = new StringBuilder();
        largePackage.append("CREATE OR REPLACE PACKAGE BODY test_pkg AS\n");
        largePackage.append("  g_counter INTEGER := 0;\n");
        largePackage.append("  g_status VARCHAR2(20) := 'ACTIVE';\n");

        // Add 10 large functions
        for (int i = 0; i < 10; i++) {
            largePackage.append("\n  FUNCTION func").append(i).append(" RETURN NUMBER IS\n");
            largePackage.append("    v_result NUMBER := 0;\n");
            // Simulate large body (50 lines each)
            for (int j = 0; j < 50; j++) {
                largePackage.append("    v_result := v_result + ").append(j).append(";\n");
            }
            largePackage.append("    RETURN v_result;\n");
            largePackage.append("  END;\n");
        }

        largePackage.append("END test_pkg;");

        String input = largePackage.toString();
        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Calculate reduction
        int originalSize = cleaned.length();
        int reducedSize = reduced.length();
        double reductionPct = reducer.calculateReductionPercentage(originalSize, reducedSize);

        // Verify dramatic reduction (should be >95% for this test)
        assertTrue(reductionPct > 90, "Should achieve >90% size reduction (actual: " + String.format("%.1f%%", reductionPct) + ")");

        // Verify variables still present
        assertTrue(reduced.contains("g_counter"));
        assertTrue(reduced.contains("g_status"));

        // Verify all functions gone
        for (int i = 0; i < 10; i++) {
            assertFalse(reduced.contains("func" + i), "Function func" + i + " should be removed");
        }
    }

    @Test
    void estimateReducedSize_accuracy() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_var INTEGER := 0;

              FUNCTION func1 RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END;

              FUNCTION func2 RETURN NUMBER IS
              BEGIN
                RETURN 2;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        // Estimate size before reduction
        int estimatedSize = reducer.estimateReducedSize(cleaned.length(), segments);

        // Actually perform reduction
        String reduced = reducer.removeAllFunctions(cleaned, segments);

        // Verify estimate is accurate (within 5%)
        int actualSize = reduced.length();
        double error = Math.abs((double)(estimatedSize - actualSize) / actualSize);
        assertTrue(error < 0.05, "Estimate should be within 5% of actual (error: " + String.format("%.1f%%", error * 100) + ")");
    }
}
