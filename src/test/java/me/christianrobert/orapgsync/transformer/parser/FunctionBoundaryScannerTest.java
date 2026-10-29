package me.christianrobert.orapgsync.transformer.parser;

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
 * - END LOOP, END IF, END CASE handling
 * - Comments (handled automatically by lexer)
 * - Edge cases
 */
class FunctionBoundaryScannerTest {

    private final FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();

    // ========== Basic tests ==========

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

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

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount());
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);

        // Verify we can extract the function using the positions
        String extracted = input.substring(func.getStartPos(), func.getEndPos());
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

        PackageSegments segments = scanner.scanPackageBody(input);

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

    // ========== END LOOP / END IF / END CASE tests ==========

    @Test
    void scan_functionWithEndLoop() {
        // Critical test: END LOOP should NOT be counted as closing a BEGIN block
        String input = """
            FUNCTION process_items RETURN NUMBER IS
              v_total NUMBER := 0;
            BEGIN
              FOR i IN 1..10 LOOP
                v_total := v_total + i;
              END LOOP;
              RETURN v_total;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function (END LOOP should not close function)");
        assertEquals("process_items", segments.getFunctions().get(0).getName());

        // Verify positions
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        String extracted = input.substring(func.getStartPos(), func.getEndPos());
        assertTrue(extracted.contains("END LOOP"), "Should include END LOOP in function body");
        assertTrue(extracted.contains("RETURN v_total"), "Should include full function body");
    }

    @Test
    void scan_functionWithEndIf() {
        // END IF should NOT be counted as closing a BEGIN block
        String input = """
            FUNCTION check_value(p_val NUMBER) RETURN VARCHAR2 IS
            BEGIN
              IF p_val > 0 THEN
                RETURN 'positive';
              ELSIF p_val < 0 THEN
                RETURN 'negative';
              ELSE
                RETURN 'zero';
              END IF;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function (END IF should not close function)");
        assertEquals("check_value", segments.getFunctions().get(0).getName());

        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        String extracted = input.substring(func.getStartPos(), func.getEndPos());
        assertTrue(extracted.contains("END IF"), "Should include END IF in function body");
    }

    @Test
    void scan_functionWithEndCase() {
        // END CASE should NOT be counted as closing a BEGIN block
        String input = """
            FUNCTION grade_score(p_score NUMBER) RETURN CHAR IS
              v_grade CHAR(1);
            BEGIN
              CASE
                WHEN p_score >= 90 THEN v_grade := 'A';
                WHEN p_score >= 80 THEN v_grade := 'B';
                WHEN p_score >= 70 THEN v_grade := 'C';
                ELSE v_grade := 'F';
              END CASE;
              RETURN v_grade;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function (END CASE should not close function)");
        assertEquals("grade_score", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_functionWithMultipleLoops() {
        // Multiple LOOP/END LOOP pairs should all be handled correctly
        String input = """
            FUNCTION nested_loops RETURN NUMBER IS
              v_total NUMBER := 0;
            BEGIN
              FOR i IN 1..5 LOOP
                FOR j IN 1..5 LOOP
                  v_total := v_total + 1;
                END LOOP;
              END LOOP;

              WHILE v_total < 100 LOOP
                v_total := v_total + 10;
              END LOOP;

              RETURN v_total;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 function with multiple loops");
        assertEquals("nested_loops", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_functionWithMixedControlStructures() {
        // Mix of IF, LOOP, CASE, and BEGIN blocks
        String input = """
            FUNCTION complex_control RETURN NUMBER IS
              v_result NUMBER := 0;
            BEGIN
              IF TRUE THEN
                BEGIN
                  FOR i IN 1..10 LOOP
                    CASE
                      WHEN i < 5 THEN v_result := v_result + 1;
                      ELSE v_result := v_result + 2;
                    END CASE;
                  END LOOP;
                END;
              END IF;
              RETURN v_result;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should handle mixed control structures");
        assertEquals("complex_control", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_nestedFunctionWithLoops() {
        // Nested function that also uses loops
        String input = """
            FUNCTION outer_func RETURN NUMBER IS
              FUNCTION inner_func RETURN NUMBER IS
              BEGIN
                FOR i IN 1..5 LOOP
                  NULL;
                END LOOP;
                RETURN 1;
              END inner_func;
            BEGIN
              FOR j IN 1..10 LOOP
                NULL;
              END LOOP;
              RETURN inner_func() + 2;
            END outer_func;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 outer function");
        assertEquals("outer_func", segments.getFunctions().get(0).getName());

        // Verify the full function is captured
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        String extracted = input.substring(func.getStartPos(), func.getEndPos());
        assertTrue(extracted.contains("inner_func"), "Should include nested function");
        assertTrue(extracted.contains("END outer_func"), "Should end with outer function's END");
    }

    @Test
    void scan_multipleFunctionsWithLoops() {
        // Multiple package-level functions, each with loops
        String input = """
            FUNCTION func1 RETURN NUMBER IS
            BEGIN
              FOR i IN 1..5 LOOP
                NULL;
              END LOOP;
              RETURN 1;
            END;

            FUNCTION func2 RETURN NUMBER IS
            BEGIN
              WHILE TRUE LOOP
                EXIT;
              END LOOP;
              RETURN 2;
            END;

            PROCEDURE proc1 IS
            BEGIN
              LOOP
                EXIT WHEN TRUE;
              END LOOP;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(3, segments.getFunctionCount(), "Should find all 3 functions/procedures");
        assertEquals("func1", segments.getFunctions().get(0).getName());
        assertEquals("func2", segments.getFunctions().get(1).getName());
        assertEquals("proc1", segments.getFunctions().get(2).getName());
    }

    // ========== Comment handling tests ==========

    @Test
    void scan_withComments_noPreprocessingNeeded() {
        // Verify that comments are handled automatically by the lexer
        String input = """
            -- This is a comment
            FUNCTION get_value RETURN NUMBER IS
              /* Multi-line
                 comment here */
              v_result NUMBER;
            BEGIN
              -- Another comment
              v_result := 100; -- Inline comment
              RETURN v_result;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find function despite comments");
        assertEquals("get_value", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_commentsContainingKeywords() {
        // Keywords inside comments should be ignored
        String input = """
            FUNCTION test_func RETURN NUMBER IS
            BEGIN
              -- END; FUNCTION PROCEDURE BEGIN LOOP IF CASE
              /* END
                 FUNCTION
                 PROCEDURE */
              RETURN 1;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should ignore keywords in comments");
        assertEquals("test_func", segments.getFunctions().get(0).getName());
    }

    // ========== Advanced nested function tests ==========

    @Test
    void scan_deeplyNestedFunctions() {
        // Multiple levels of nested functions
        String input = """
            FUNCTION level1 RETURN NUMBER IS
              FUNCTION level2 RETURN NUMBER IS
                FUNCTION level3 RETURN NUMBER IS
                BEGIN
                  RETURN 3;
                END level3;
              BEGIN
                RETURN level3() + 2;
              END level2;
            BEGIN
              RETURN level2() + 1;
            END level1;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find only outermost function");
        assertEquals("level1", segments.getFunctions().get(0).getName());

        // Verify all levels are included
        PackageSegments.FunctionSegment func = segments.getFunctions().get(0);
        String extracted = input.substring(func.getStartPos(), func.getEndPos());
        assertTrue(extracted.contains("level2"), "Should include level2");
        assertTrue(extracted.contains("level3"), "Should include level3");
    }

    @Test
    void scan_nestedProcedure() {
        // Nested procedure (not function)
        String input = """
            PROCEDURE outer_proc IS
              PROCEDURE inner_proc IS
              BEGIN
                NULL;
              END inner_proc;
            BEGIN
              inner_proc;
            END outer_proc;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find 1 outer procedure");
        PackageSegments.FunctionSegment proc = segments.getFunctions().get(0);
        assertEquals("outer_proc", proc.getName());
        assertTrue(proc.isProcedure());
    }

    @Test
    void scan_nestedFunctionForwardDeclaration() {
        // Nested function with forward declaration inside
        String input = """
            FUNCTION outer RETURN NUMBER IS
              -- Forward declaration for inner2
              FUNCTION inner2 RETURN NUMBER;

              FUNCTION inner1 RETURN NUMBER IS
              BEGIN
                RETURN inner2;
              END inner1;

              FUNCTION inner2 RETURN NUMBER IS
              BEGIN
                RETURN 2;
              END inner2;
            BEGIN
              RETURN inner1 + inner2;
            END outer;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should find only outer function");
        assertEquals("outer", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_caseExpressionVsCaseStatement() {
        // CASE expression (no END CASE) vs CASE statement (has END CASE)
        String input = """
            FUNCTION test_case RETURN NUMBER IS
              v_val NUMBER;
              v_result NUMBER;
            BEGIN
              -- CASE expression (no END CASE)
              v_result := CASE v_val WHEN 1 THEN 10 WHEN 2 THEN 20 ELSE 0 END;

              -- CASE statement (has END CASE)
              CASE v_val
                WHEN 1 THEN v_result := 100;
                WHEN 2 THEN v_result := 200;
              END CASE;

              RETURN v_result;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should handle both CASE forms");
        assertEquals("test_case", segments.getFunctions().get(0).getName());
        // The count alone passed while the segment ended at the CASE expression's END.
        assertTrue(text(input, segments.getFunctions().get(0)).trim().endsWith("RETURN v_result;\nEND;"),
                "segment must run to the function's own END");
    }

    @Test
    void scan_exceptionBlock() {
        // EXCEPTION is part of BEGIN...END, not a separate block
        String input = """
            FUNCTION safe_divide(a NUMBER, b NUMBER) RETURN NUMBER IS
            BEGIN
              RETURN a / b;
            EXCEPTION
              WHEN ZERO_DIVIDE THEN
                RETURN NULL;
              WHEN OTHERS THEN
                RAISE;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should handle EXCEPTION block");
        assertEquals("safe_divide", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_labeledBlocks() {
        // Labeled BEGIN blocks
        String input = """
            FUNCTION with_labels RETURN NUMBER IS
            BEGIN
              <<outer_block>>
              BEGIN
                <<inner_block>>
                BEGIN
                  NULL;
                END inner_block;
              END outer_block;
              RETURN 1;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount(), "Should handle labeled blocks");
        assertEquals("with_labels", segments.getFunctions().get(0).getName());
    }

    @Test
    void scan_quotedIdentifier() {
        // Quoted identifier that looks like keyword
        String input = """
            FUNCTION "END" RETURN NUMBER IS
            BEGIN
              RETURN 1;
            END;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        // Note: The function name will be "END" (with quotes)
        assertEquals(1, segments.getFunctionCount(), "Should handle quoted identifier");
    }

    // ========== Extent tests (ROADMAP §6.A1) ==========
    //
    // Every test below asserts the extracted TEXT. A truncated segment leaves a tail with no FUNCTION
    // keyword in it, so the segment count still tallies -- only the extent shows the defect.

    private final AntlrParser parser = new AntlrParser();
    private final PackageBodyReducer reducer = new PackageBodyReducer();

    private static String text(String input, PackageSegments.FunctionSegment segment) {
        return input.substring(segment.getStartPos(), segment.getEndPos());
    }

    /**
     * Feeds the scan through what production does with it: every segment through the routine
     * parser (step 28) and the reduced body through the package-body parser (package context).
     */
    private void assertScanParses(String input, PackageSegments segments) {
        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            String source = text(input, segment);
            ParseResult result = segment.isFunction()
                    ? parser.parseFunctionBody(source)
                    : parser.parseProcedureBody(source);
            assertFalse(result.hasErrors(),
                    () -> segment.getName() + " does not parse: " + result.getErrorMessage() + "\n" + source);
        }
        String reduced = reducer.removeAllFunctions(input, segments);
        ParseResult reducedResult = parser.parsePackageBody(reduced);
        assertFalse(reducedResult.hasErrors(),
                () -> "reduced body does not parse: " + reducedResult.getErrorMessage() + "\n" + reduced);
    }

    @Test
    void extent_caseExpressionClosedByBareEnd() {
        // The reproduced defect: the function ended at the CASE expression's END, and everything
        // after it -- here `end icon_for;` and the whole next function's tail -- became debris in
        // the reduced body.
        String input = """
            CREATE OR REPLACE PACKAGE BODY ui_assets AS
              g_default VARCHAR2(20) := 'plain';

              FUNCTION icon_for(p_kind VARCHAR2) RETURN VARCHAR2 IS
              BEGIN
                RETURN CASE p_kind WHEN 'warn' THEN 'triangle' ELSE 'circle' END;
              END icon_for;

              PROCEDURE reset_icons IS
              BEGIN
                g_default := 'plain';
              END reset_icons;
            END ui_assets;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertEquals("FUNCTION icon_for(p_kind VARCHAR2) RETURN VARCHAR2 IS\n"
                        + "  BEGIN\n"
                        + "    RETURN CASE p_kind WHEN 'warn' THEN 'triangle' ELSE 'circle' END;\n"
                        + "  END icon_for;",
                text(input, segments.getFunctions().get(0)));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END reset_icons;"));
        assertScanParses(input, segments);
    }

    @Test
    void extent_caseExpressionInsideIfAndAssignment() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY grading AS
              FUNCTION band(p_score NUMBER) RETURN VARCHAR2 IS
                v_band VARCHAR2(10);
              BEGIN
                IF p_score > 0 THEN
                  v_band := CASE WHEN p_score > 90 THEN 'A' WHEN p_score > 50 THEN 'B' ELSE 'C' END;
                ELSE
                  v_band := 'none';
                END IF;
                RETURN v_band;
              END band;

              FUNCTION passed(p_score NUMBER) RETURN BOOLEAN IS
              BEGIN
                RETURN p_score > 50;
              END passed;
            END grading;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("RETURN v_band;\n  END band;"));
        assertEquals("passed", segments.getFunctions().get(1).getName());
        assertScanParses(input, segments);
    }

    @Test
    void extent_blockInsideCaseExpressionBranchOfCaseStatement() {
        // A CASE statement whose branch holds a BEGIN block and a CASE expression: independent
        // counters charge one of these ENDs to the wrong opener.
        String input = """
            CREATE OR REPLACE PACKAGE BODY dispatch AS
              PROCEDURE route(p_code NUMBER, p_out OUT VARCHAR2) IS
              BEGIN
                CASE p_code
                  WHEN 1 THEN
                    BEGIN
                      p_out := CASE WHEN p_code > 0 THEN 'pos' ELSE 'neg' END;
                    EXCEPTION
                      WHEN OTHERS THEN p_out := NULL;
                    END;
                  ELSE
                    p_out := 'other';
                END CASE;
              END route;

              PROCEDURE noop IS
              BEGIN
                NULL;
              END noop;
            END dispatch;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("END CASE;\n  END route;"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END noop;"));
        assertScanParses(input, segments);
    }

    @Test
    void extent_caseExpressionsInDeclareSection() {
        // CASE ... END in a default value and in a cursor query: no BEGIN is open there yet.
        String input = """
            CREATE OR REPLACE PACKAGE BODY reports AS
              FUNCTION headline(p_mode NUMBER) RETURN VARCHAR2 IS
                v_label VARCHAR2(10) := CASE WHEN p_mode = 1 THEN 'one' ELSE 'many' END;
                CURSOR c_rows IS
                  SELECT CASE WHEN status = 'X' THEN 1 ELSE 0 END AS flag FROM report_rows;
              BEGIN
                RETURN v_label;
              END headline;

              PROCEDURE refresh IS
              BEGIN
                NULL;
              END refresh;
            END reports;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("RETURN v_label;\n  END headline;"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END refresh;"));
        assertScanParses(input, segments);
    }

    @Test
    void extent_subprogramDeclaredInNestedDeclareBlock() {
        // A local procedure declared in a nested DECLARE block. A "nested subprograms seen" counter
        // charged it to the outer function's final END, so the function ran on into the next one.
        String input = """
            CREATE OR REPLACE PACKAGE BODY batch_jobs AS
              PROCEDURE run_all IS
              BEGIN
                DECLARE
                  PROCEDURE step(p_n NUMBER) IS
                  BEGIN
                    NULL;
                  END step;
                BEGIN
                  step(1);
                END;
              END run_all;

              PROCEDURE run_none IS
              BEGIN
                NULL;
              END run_none;
            END batch_jobs;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("END;\n  END run_all;"));
        assertEquals("run_none", segments.getFunctions().get(1).getName());
        assertScanParses(input, segments);
    }

    @Test
    void extent_functionKeywordInStatementIsNotADeclaration() {
        // A column named FUNCTION, aliased with AS, in a statement section. It looked like a nested
        // declaration (IS/AS before the semicolon) and swallowed the routine's END.
        String input = """
            CREATE OR REPLACE PACKAGE BODY catalog_api AS
              FUNCTION kind_of(p_id NUMBER) RETURN VARCHAR2 IS
                v_kind VARCHAR2(30);
              BEGIN
                SELECT e.function AS kind INTO v_kind FROM catalog_entries e WHERE e.id = p_id;
                RETURN v_kind;
              END kind_of;

              FUNCTION count_all RETURN NUMBER IS
              BEGIN
                RETURN 0;
              END count_all;
            END catalog_api;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("RETURN v_kind;\n  END kind_of;"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END count_all;"));
    }

    @Test
    void extent_callSpecMemberEndsAtItsSemicolon() {
        // No BEGIN/END: hunting for one consumed the next function. Recorded, so the transformer
        // can reject it by name.
        String input = """
            CREATE OR REPLACE PACKAGE BODY crypto_bridge AS
              FUNCTION digest(p_text VARCHAR2) RETURN VARCHAR2
              AS LANGUAGE JAVA NAME 'demo.Digest.of(java.lang.String) return java.lang.String';

              FUNCTION digest_len RETURN NUMBER IS
              BEGIN
                RETURN 64;
              END digest_len;
            END crypto_bridge;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        PackageSegments.FunctionSegment callSpec = segments.getFunctions().get(0);
        assertEquals("FUNCTION digest(p_text VARCHAR2) RETURN VARCHAR2\n"
                        + "  AS LANGUAGE JAVA NAME 'demo.Digest.of(java.lang.String) return java.lang.String';",
                text(input, callSpec));
        assertTrue(input.substring(callSpec.getBodyStartPos()).startsWith("LANGUAGE JAVA"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END digest_len;"));
        assertScanParses(input, segments);
    }

    @Test
    void extent_caseExpressionAsLoopBoundIsFollowedByLoop() {
        // The bound's CASE expression ends with a bare END immediately followed by the FOR loop's
        // LOOP keyword. Read as END LOOP, the CASE stayed open and the procedure ran to the end of
        // the package, swallowing every member after it.
        String input = """
            CREATE OR REPLACE PACKAGE BODY paging AS
              PROCEDURE render(p_all BOOLEAN, p_n NUMBER) IS
              BEGIN
                FOR i IN CASE WHEN p_all THEN 1 ELSE p_n END .. CASE WHEN p_all THEN 10 ELSE p_n END LOOP
                  NULL;
                END LOOP;
                WHILE p_n > CASE WHEN p_all THEN 0 ELSE 1 END LOOP
                  EXIT;
                END LOOP;
              END render;

              PROCEDURE after_render IS
              BEGIN
                NULL;
              END after_render;
            END paging;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("END LOOP;\n  END render;"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END after_render;"));
        assertScanParses(input, segments);
    }

    @Test
    void extent_endAsColumnAliasIsNotABlockEnd() {
        // Oracle accepts END as a column alias. Taken for the block's END, it ended the function
        // inside its own cursor query and left the rest of the body in the reduced package.
        String input = """
            CREATE OR REPLACE PACKAGE BODY org_lookup AS
              FUNCTION org_rows(p_filter NUMBER) RETURN SYS_REFCURSOR IS
                c SYS_REFCURSOR;
              BEGIN
                OPEN c FOR
                  SELECT org_id, CASE WHEN p_filter = org_id THEN 'x' END, NULL END
                    FROM org_units
                   ORDER BY org_id;
                RETURN c;
              END org_rows;

              FUNCTION org_count RETURN NUMBER IS
              BEGIN
                RETURN 0;
              END org_count;
            END org_lookup;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(2, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("RETURN c;\n  END org_rows;"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("END org_count;"));
    }

    @Test
    void extent_keywordAfterPeriodIsAName() {
        // Columns named BEGIN / END, qualified: `b.begin` opened a block that never closed, and the
        // last function swallowed the package's own END.
        String input = """
            CREATE OR REPLACE PACKAGE BODY absences AS
              FUNCTION is_absent(p_day DATE) RETURN VARCHAR2 IS
              BEGIN
                FOR r IN (SELECT COUNT(*) cnt FROM leave_periods b
                           WHERE p_day BETWEEN b.begin AND b.end) LOOP
                  IF r.cnt > 0 THEN
                    RETURN 'Y';
                  END IF;
                END LOOP;
                RETURN 'N';
              END is_absent;
            END absences;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(1, segments.getFunctionCount());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("RETURN 'N';\n  END is_absent;"));
        assertTrue(reducer.removeAllFunctions(input, segments).trim().endsWith("END absences;"));
    }

    @Test
    void extent_everyConstructTogetherThroughTheParser() {
        // One package exercising every boundary case above in sequence, so a mis-scan of any one
        // of them shows up as debris in the reduced body or an <EOF> in the next segment.
        String input = """
            CREATE OR REPLACE PACKAGE BODY mixed_bag AS
              TYPE t_ids IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
              g_ids t_ids;
              g_mode NUMBER := CASE WHEN 1 = 1 THEN 1 ELSE 0 END;

              FUNCTION helper(p NUMBER) RETURN NUMBER;

              FUNCTION classify(p NUMBER) RETURN VARCHAR2 IS
                FUNCTION local_sign(q NUMBER) RETURN NUMBER IS
                BEGIN
                  RETURN CASE WHEN q < 0 THEN -1 WHEN q > 0 THEN 1 ELSE 0 END;
                END local_sign;
              BEGIN
                FOR i IN 1 .. 3 LOOP
                  CASE local_sign(p)
                    WHEN 1 THEN NULL;
                    ELSE g_ids(i) := CASE i WHEN 1 THEN 10 ELSE 20 END;
                  END CASE;
                END LOOP;
                <<blk>>
                DECLARE
                  v NUMBER := helper(p);
                BEGIN
                  IF v > 0 THEN
                    RETURN 'positive';
                  END IF;
                END blk;
                RETURN CASE local_sign(p) WHEN 0 THEN 'zero' ELSE 'negative' END;
              END classify;

              FUNCTION helper(p NUMBER) RETURN NUMBER IS
              BEGIN
                RETURN p * g_mode;
              END helper;

            BEGIN
              g_mode := CASE WHEN g_mode IS NULL THEN 0 ELSE g_mode END;
            END mixed_bag;
            """;

        PackageSegments segments = scanner.scanPackageBody(input);

        assertEquals(List.of("classify", "helper"),
                segments.getFunctions().stream().map(PackageSegments.FunctionSegment::getName).toList());
        assertTrue(text(input, segments.getFunctions().get(0)).endsWith("ELSE 'negative' END;\n  END classify;"));
        assertTrue(text(input, segments.getFunctions().get(1)).endsWith("RETURN p * g_mode;\n  END helper;"));
        assertScanParses(input, segments);
    }
}
