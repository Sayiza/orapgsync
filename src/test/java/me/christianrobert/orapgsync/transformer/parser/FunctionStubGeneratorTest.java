package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FunctionStubGenerator.
 *
 * Verifies stub generation creates valid stubs that:
 * - Preserve the signature
 * - Replace body with minimal implementation
 * - Are much smaller than original (for parsing performance)
 * - Can be parsed by ANTLR
 */
class FunctionStubGeneratorTest {

    private final FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();
    private final FunctionStubGenerator generator = new FunctionStubGenerator();

    @Test
    void generateStub_function() {
        String input = """
            FUNCTION get_salary(emp_id NUMBER) RETURN NUMBER IS
              v_base NUMBER;
              v_bonus NUMBER;
            BEGIN
              SELECT base_sal INTO v_base FROM employees WHERE id = emp_id;
              v_bonus := v_base * 0.1;
              RETURN v_base + v_bonus;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(1, segments.getFunctionCount());

        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());

        String stub = generator.generateStub(fullSource, segment);

        // Verify stub structure
        assertTrue(stub.contains("FUNCTION get_salary(emp_id NUMBER) RETURN NUMBER"));
        assertTrue(stub.contains("BEGIN"));
        assertTrue(stub.contains("RETURN NULL;"));
        assertTrue(stub.contains("END;"));

        // Verify stub is much smaller
        assertTrue(stub.length() < fullSource.length() / 2, "Stub should be significantly smaller");

        // Verify no original body content
        assertFalse(stub.contains("v_base"));
        assertFalse(stub.contains("SELECT"));
    }

    @Test
    void generateStub_procedure() {
        String input = """
            PROCEDURE update_salary(emp_id NUMBER, new_sal NUMBER) IS
              v_old_sal NUMBER;
            BEGIN
              SELECT salary INTO v_old_sal FROM employees WHERE id = emp_id;
              UPDATE employees SET salary = new_sal WHERE id = emp_id;
              COMMIT;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(1, segments.getFunctionCount());

        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());

        String stub = generator.generateStub(fullSource, segment);

        // Verify stub structure (procedures use RETURN; not RETURN NULL;)
        assertTrue(stub.contains("PROCEDURE update_salary(emp_id NUMBER, new_sal NUMBER)"));
        assertTrue(stub.contains("BEGIN"));
        assertTrue(stub.contains("RETURN;"));
        assertTrue(stub.contains("END;"));

        // Verify no RETURN NULL (that's for functions)
        assertFalse(stub.contains("RETURN NULL"));

        // Verify no original body content
        assertFalse(stub.contains("UPDATE"));
        assertFalse(stub.contains("COMMIT"));
    }

    @Test
    void generateStub_complexSignature() {
        String input = """
            FUNCTION process_data(
              p_emp_id IN NUMBER,
              p_dept_id IN NUMBER DEFAULT 10,
              p_name IN OUT VARCHAR2,
              p_result OUT NUMBER
            ) RETURN BOOLEAN IS
              v_temp NUMBER;
            BEGIN
              -- Complex logic here (100 lines)
              RETURN TRUE;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(1, segments.getFunctionCount());

        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());

        String stub = generator.generateStub(fullSource, segment);

        // Verify full signature is preserved
        assertTrue(stub.contains("process_data"));
        assertTrue(stub.contains("p_emp_id"));
        assertTrue(stub.contains("p_dept_id"));
        assertTrue(stub.contains("p_name"));
        assertTrue(stub.contains("p_result"));
        assertTrue(stub.contains("RETURN BOOLEAN"));

        // Verify body is replaced
        assertTrue(stub.contains("RETURN NULL;"));
        assertFalse(stub.contains("v_temp"));
    }

    @Test
    void generateStub_noParameters() {
        String input = """
            FUNCTION get_counter RETURN NUMBER IS
            BEGIN
              RETURN g_counter;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(1, segments.getFunctionCount());

        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());

        String stub = generator.generateStub(fullSource, segment);

        // Verify stub for parameterless function
        assertTrue(stub.contains("FUNCTION get_counter RETURN NUMBER"));
        assertTrue(stub.contains("RETURN NULL;"));
        assertFalse(stub.contains("g_counter")); // No reference to package variable
    }

    @Test
    void generateStub_sizeReduction() {
        // Create a large function (simulating real-world 800-line function)
        StringBuilder largeFunction = new StringBuilder();
        largeFunction.append("FUNCTION big_func(p NUMBER) RETURN NUMBER IS\n");
        largeFunction.append("  v_result NUMBER := 0;\n");
        for (int i = 0; i < 100; i++) {
            largeFunction.append("  v_temp").append(i).append(" NUMBER;\n");
        }
        largeFunction.append("BEGIN\n");
        for (int i = 0; i < 100; i++) {
            largeFunction.append("  v_temp").append(i).append(" := ").append(i).append(";\n");
            largeFunction.append("  v_result := v_result + v_temp").append(i).append(";\n");
        }
        largeFunction.append("  RETURN v_result;\n");
        largeFunction.append("END;");

        String input = largeFunction.toString();
        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);
        assertEquals(1, segments.getFunctionCount());

        PackageSegments.FunctionSegment segment = segments.getFunctions().get(0);
        String fullSource = cleaned.substring(segment.getStartPos(), segment.getEndPos());

        String stub = generator.generateStub(fullSource, segment);

        // Verify dramatic size reduction
        int originalSize = fullSource.length();
        int stubSize = stub.length();
        double reduction = 100.0 * (1.0 - (double) stubSize / originalSize);

        assertTrue(reduction > 90, "Stub should be >90% smaller than original (actual: " + String.format("%.1f%%", reduction) + ")");

        // Verify stub is still valid
        assertTrue(stub.contains("FUNCTION big_func(p NUMBER) RETURN NUMBER"));
        assertTrue(stub.contains("RETURN NULL;"));
    }
}
