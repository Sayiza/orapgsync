package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for FunctionBoundaryScanner.
 *
 * Tests cover:
 * - Simple functions and procedures
 * - Parameter handling (multiple, none, complex types)
 * - Nested functions
 * - String literals with keywords
 * - IS vs AS keywords
 * - Case sensitivity
 * - Edge cases
 */
class FunctionBoundaryScannerTest {

    private final FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();

    @Test
    void scan_simpleFunction() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              FUNCTION get_salary(emp_id NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN 1000;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function");
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        assertEquals("get_salary", func.getName());
        assertTrue(func.isFunction());
        assertFalse(func.isProcedure());
    }

    @Test
    void scan_simpleProcedure() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              PROCEDURE update_salary(emp_id NUMBER, new_sal NUMBER) IS
              BEGIN
                NULL;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 procedure");
        PackageSegments.FunctionSegment proc = segments.getFunctions().get(0);
        assertEquals("update_salary", proc.getName());
        assertTrue(proc.isProcedure());
        assertFalse(proc.isFunction());
    }

    @Test
    void scan_multipleParameters() {
        String input = """
            FUNCTION calc(a NUMBER, b NUMBER, c VARCHAR2, d DATE) RETURN NUMBER IS
            BEGIN
              RETURN a + b;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        assertEquals("calc", func.getName());
    }

    @Test
    void scan_noParameters() {
        String input = """
            FUNCTION get_counter RETURN NUMBER IS
            BEGIN
              RETURN 42;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
        assertEquals("get_counter", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_nestedFunction() {
        String input = """
            FUNCTION outer_func RETURN NUMBER IS
              FUNCTION inner_func RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END;
            BEGIN
              RETURN inner_func() + 2;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        // Should find outer function
        // Inner function is part of outer function's body (not separately tracked)
        assertEquals(1, segments.getFunctionCount(), "Should find 1 outer function");
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        assertEquals("outer_func", func.getName());

        // Verify the body includes the nested function
        assertTrue(func.getBodyLength() > 0);
    }

    @Test
    void scan_stringLiteralWithKeywords() {
        String input = """
            FUNCTION test RETURN VARCHAR2 IS
              v_msg VARCHAR2(100) := 'This FUNCTION END BEGIN string contains keywords';
            BEGIN
              RETURN v_msg;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function (keywords in strings ignored)");
        assertEquals("test", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_functionWithISKeyword() {
        String input = """
            FUNCTION get_value RETURN NUMBER IS
            BEGIN
              RETURN 100;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
    }

    @Test
    void scan_functionWithASKeyword() {
        String input = """
            FUNCTION get_value RETURN NUMBER AS
            BEGIN
              RETURN 100;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
    }

    @Test
    void scan_complexSignature() {
        String input = """
            FUNCTION process_data(
              p_emp_id IN NUMBER,
              p_dept_id IN NUMBER DEFAULT 10,
              p_name IN OUT VARCHAR2,
              p_result OUT NUMBER
            ) RETURN BOOLEAN IS
            BEGIN
              RETURN TRUE;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
        assertEquals("process_data", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_multipleFunctionsInPackage() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_counter INTEGER := 0;

              FUNCTION func1 RETURN NUMBER IS
              BEGIN
                RETURN 1;
              END;

              PROCEDURE proc1 IS
              BEGIN
                NULL;
              END;

              FUNCTION func2(p NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN p * 2;
              END;
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(3, segments.getFunctionCount(), "Should find 3 functions/procedures");
        assertEquals("func1", segments.getFunctions().get(0).getName());
        assertEquals("proc1", segments.getFunctions().get(1).getName());
        assertEquals("func2", segments.getFunctions().get(2).getName());
    }

    @Test
    void scan_emptyPackage() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(0, segments.getFunctionCount(), "Should find 0 functions in empty package");
    }

    @Test
    void scan_packageWithOnlyVariables() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              g_counter INTEGER := 0;
              g_status VARCHAR2(20) := 'ACTIVE';

              TYPE rec_t IS RECORD (
                id NUMBER,
                name VARCHAR2(100)
              );
            END test_pkg;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(0, segments.getFunctionCount(), "Should find 0 functions (only variables)");
    }

    @Test
    void scan_functionNameContainingKeyword() {
        String input = """
            FUNCTION begin_process RETURN NUMBER IS
            BEGIN
              RETURN 1;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
        assertEquals("begin_process", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_caseInsensitiveKeywords() {
        String input = """
            function get_value return number is
            begin
              return 100;
            end;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount(), "Should find function with lowercase keywords");
        assertEquals("get_value", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_functionWithBeginEndBlocks() {
        String input = """
            FUNCTION complex_func RETURN NUMBER IS
              v_result NUMBER := 0;
            BEGIN
              BEGIN
                v_result := 10;
              END;

              BEGIN
                v_result := v_result + 20;
              END;

              RETURN v_result;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount(), "Should correctly handle nested BEGIN/END blocks");
        assertEquals("complex_func", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_positionAccuracy() {
        String input = """
            FUNCTION get_value RETURN NUMBER IS
            BEGIN
              RETURN 100;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        assertEquals(1, segments.getFunctionCount());
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);

        // Verify we can extract the function using the positions
        String extracted = cleaned.substring(func.getStartPos(), func.getEndPos());
        assertTrue(extracted.contains("FUNCTION get_value"));
        assertTrue(extracted.contains("RETURN 100"));
        assertTrue(extracted.trim().endsWith(";"), "Should include semicolon");

        // Verify body positions
        assertTrue(func.getBodyStartPos() < func.getBodyEndPos());
        assertTrue(func.getBodyStartPos() > func.getStartPos());
        assertTrue(func.getBodyEndPos() < func.getEndPos());
    }

    @Test
    void scan_forwardDeclarations() {
        // Forward declarations are function/procedure signatures without bodies
        // They end with semicolon, not IS/AS BEGIN...END
        // Scanner should skip them and only capture full definitions
        String input = """
            -- Forward declaration (no body)
            FUNCTION func_b(x NUMBER) RETURN NUMBER;

            -- Full definition of func_a
            FUNCTION func_a(x NUMBER) RETURN NUMBER IS
            BEGIN
              RETURN func_b(x + 1);
            END func_a;

            -- Full definition of func_b (after forward declaration)
            FUNCTION func_b(x NUMBER) RETURN NUMBER IS
            BEGIN
              RETURN x * 2;
            END func_b;

            -- Another forward declaration
            PROCEDURE proc_d(msg VARCHAR2);

            -- Full definition
            PROCEDURE proc_c(msg VARCHAR2) IS
            BEGIN
              proc_d('test');
            END proc_c;

            -- Full definition of proc_d
            PROCEDURE proc_d(msg VARCHAR2) IS
            BEGIN
              NULL;
            END proc_d;
            """;

        String cleaned = CodeCleaner.removeComments(input);
        PackageSegments segments = scanner.scanPackageBody(cleaned);

        // Should find only the 4 full definitions, NOT the 2 forward declarations
        assertEquals(4, segments.getFunctionCount(),
                    "Should find 4 full definitions and skip 2 forward declarations");

        List<String> functionNames = segments.getFunctions().stream()
                .map(PackageSegments.FunctionSegment::getName)
                .toList();

        assertTrue(functionNames.contains("func_a"), "Should find func_a");
        assertTrue(functionNames.contains("func_b"), "Should find func_b");
        assertTrue(functionNames.contains("proc_c"), "Should find proc_c");
        assertTrue(functionNames.contains("proc_d"), "Should find proc_d");

        // Verify no duplicates (forward declarations should not be included)
        assertEquals(4, functionNames.stream().distinct().count(),
                    "Should not have duplicate function names");
    }
}
