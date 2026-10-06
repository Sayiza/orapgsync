package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.MethodType;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.TypeMethodSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary detection for type bodies.
 *
 * <p><b>Every test asserts the extracted source, not just the method count.</b> That is the lesson
 * of the truncation bug fixed on 2026-08-27: the scanner closed a method at the first {@code END IF}
 * and the orphaned tail contained no modifier keyword, so exactly one segment was still found with
 * exactly the right name and type. {@code scan_orderMemberFunction} below asserted precisely those
 * things and passed against the bug for months, while every affected method reached the transformer
 * as a fragment. A count assertion cannot see a truncated body; {@link #sourceOf} can.
 */
class TypeMethodBoundaryScannerTest {

    private final TypeMethodBoundaryScanner scanner = new TypeMethodBoundaryScanner();

    /** The text the extractor will actually store — the assertion that catches truncation. */
    private static String sourceOf(TypeMethodSegment segment, String source) {
        return source.substring(segment.getStartPos(), segment.getEndPos());
    }

    private TypeBodySegments scan(String typeBody) {
        return scanner.scanTypeBody(CodeCleaner.removeComments(typeBody));
    }

    @Test
    void scan_memberFunction() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION get_salary RETURN NUMBER IS
                BEGIN
                    RETURN self.salary;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("get_salary", method.getName());
        assertEquals(MethodType.MEMBER_FUNCTION, method.getMethodType());
        assertTrue(method.isFunction());
        assertTrue(method.isMemberMethod());

        String extracted = sourceOf(method, cleaned);
        assertTrue(extracted.startsWith("MEMBER FUNCTION get_salary"), extracted);
        assertTrue(extracted.contains("RETURN self.salary;"), extracted);
        assertTrue(extracted.trim().endsWith("END;"), extracted);
    }

    @Test
    void scan_memberProcedure() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER PROCEDURE update_salary(p_new_salary NUMBER) IS
                BEGIN
                    self.salary := p_new_salary;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("update_salary", method.getName());
        assertEquals(MethodType.MEMBER_PROCEDURE, method.getMethodType());
        assertTrue(method.isProcedure());
        assertTrue(method.isMemberMethod());
        assertTrue(sourceOf(method, cleaned).contains("self.salary := p_new_salary;"));
    }

    @Test
    void scan_staticFunction() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                STATIC FUNCTION create_employee(p_name VARCHAR2) RETURN employee_type IS
                BEGIN
                    RETURN employee_type(1, p_name);
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("create_employee", method.getName());
        assertEquals(MethodType.STATIC_FUNCTION, method.getMethodType());
        assertTrue(method.isStaticMethod());
        assertTrue(sourceOf(method, cleaned).contains("RETURN employee_type(1, p_name);"));
    }

    @Test
    void scan_staticProcedure() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                STATIC PROCEDURE initialize_globals IS
                BEGIN
                    NULL;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("initialize_globals", method.getName());
        assertEquals(MethodType.STATIC_PROCEDURE, method.getMethodType());
        assertTrue(method.isProcedure());
        assertTrue(method.isStaticMethod());
    }

    @Test
    void scan_mapMemberFunction() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MAP MEMBER FUNCTION compare RETURN NUMBER IS
                BEGIN
                    RETURN self.emp_id;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("compare", method.getName());
        assertEquals(MethodType.MAP_FUNCTION, method.getMethodType());
        assertTrue(method.isMapMethod());
        assertTrue(sourceOf(method, cleaned).startsWith("MAP MEMBER FUNCTION"));
    }

    /**
     * The regression case. This method's body ends in {@code END IF; END;} — the old scanner
     * decremented on the {@code END} of {@code END IF} and recorded the method as ending there, so
     * the stored source was everything up to the first branch and the transformer reported
     * {@code mismatched input '<EOF>' expecting {'END', 'EXCEPTION'}}.
     */
    @Test
    void scan_orderMemberFunction_endIfDoesNotCloseTheMethod() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                ORDER MEMBER FUNCTION compare(p_other employee_type) RETURN NUMBER IS
                BEGIN
                    IF self.emp_id < p_other.emp_id THEN
                        RETURN -1;
                    ELSIF self.emp_id > p_other.emp_id THEN
                        RETURN 1;
                    ELSE
                        RETURN 0;
                    END IF;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("compare", method.getName());
        assertEquals(MethodType.ORDER_FUNCTION, method.getMethodType());
        assertTrue(method.isOrderMethod());

        String extracted = sourceOf(method, cleaned);
        assertTrue(extracted.contains("END IF;"), extracted);
        assertTrue(extracted.trim().endsWith("END;"), extracted);
        // The END IF must not have been counted as the method's own END: exactly one END; follows it.
        assertEquals(1, countOccurrences(extracted.substring(extracted.indexOf("END IF;")), "END;"),
                extracted);
    }

    @Test
    void scan_endLoopDoesNotCloseTheMethod() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER PROCEDURE accumulate IS
                BEGIN
                    FOR i IN 1 .. 10 LOOP
                        self.total := self.total + i;
                    END LOOP;
                    self.done := 1;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        String extracted = sourceOf(segments.getMethods().get(0), cleaned);
        assertTrue(extracted.contains("END LOOP;"), extracted);
        assertTrue(extracted.contains("self.done := 1;"), extracted);
    }

    @Test
    void scan_endCaseStatementDoesNotCloseTheMethod() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER PROCEDURE classify IS
                BEGIN
                    CASE
                        WHEN self.salary > 100 THEN self.grade := 'A';
                        ELSE self.grade := 'B';
                    END CASE;
                    self.checked := 1;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        String extracted = sourceOf(segments.getMethods().get(0), cleaned);
        assertTrue(extracted.contains("END CASE;"), extracted);
        assertTrue(extracted.contains("self.checked := 1;"), extracted);
    }

    /**
     * A CASE <em>expression</em> closes with a bare {@code END} — no {@code CASE} keyword follows it,
     * so no lookahead can tell it apart from a block END. That is why the scanner counts open CASEs
     * instead of only inspecting the token after END.
     */
    @Test
    void scan_caseExpressionEndDoesNotCloseTheMethod() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION grade RETURN VARCHAR2 IS
                    v_result VARCHAR2(10);
                BEGIN
                    v_result := CASE WHEN self.salary > 100 THEN 'high' ELSE 'low' END;
                    RETURN v_result;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        String extracted = sourceOf(segments.getMethods().get(0), cleaned);
        assertTrue(extracted.contains("RETURN v_result;"), extracted);
        assertTrue(extracted.trim().endsWith("END;"), extracted);
    }

    /**
     * The case that decides stack versus counters: a {@code BEGIN} block inside a {@code CASE}
     * branch. Two independent depth counters charge the inner block's {@code END} to the open CASE,
     * and every {@code END} after it is off by one.
     */
    @Test
    void scan_beginBlockInsideCaseBranch() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER PROCEDURE classify IS
                BEGIN
                    CASE self.grade
                        WHEN 'A' THEN
                            BEGIN
                                self.bonus := 100;
                            END;
                        ELSE
                            self.bonus := 0;
                    END CASE;
                    self.checked := 1;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        String extracted = sourceOf(segments.getMethods().get(0), cleaned);
        assertTrue(extracted.contains("END CASE;"), extracted);
        assertTrue(extracted.contains("self.checked := 1;"), extracted);
        // Getting this wrong overruns rather than truncates -- the miscounted END is repaid by
        // swallowing the type body's own END. Two END; belong to this method: the inner block's
        // and its own.
        assertEquals(2, countOccurrences(extracted, "END;"), extracted);
    }

    @Test
    void scan_nestedBeginBlock() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION safe_ratio RETURN NUMBER IS
                BEGIN
                    BEGIN
                        RETURN self.total / self.count;
                    EXCEPTION
                        WHEN ZERO_DIVIDE THEN
                            RETURN 0;
                    END;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        String extracted = sourceOf(segments.getMethods().get(0), cleaned);
        assertTrue(extracted.contains("WHEN ZERO_DIVIDE THEN"), extracted);
        assertEquals(2, countOccurrences(extracted, "END;"), extracted);
    }

    /** A local subprogram in the DECLARE section brings its own BEGIN…END. */
    @Test
    void scan_nestedLocalFunction() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION calc RETURN NUMBER IS
                    FUNCTION helper(p NUMBER) RETURN NUMBER IS
                    BEGIN
                        RETURN p * 2;
                    END;
                BEGIN
                    RETURN helper(self.salary);
                END;

                MEMBER FUNCTION get_salary RETURN NUMBER IS
                BEGIN
                    RETURN self.salary;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(2, segments.getMethods().size());
        assertEquals("calc", segments.getMethods().get(0).getName());
        assertEquals("get_salary", segments.getMethods().get(1).getName());

        String extracted = sourceOf(segments.getMethods().get(0), cleaned);
        assertTrue(extracted.contains("RETURN helper(self.salary);"), extracted);
    }

    @Test
    void scan_constructor() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                CONSTRUCTOR FUNCTION employee_type(p_id NUMBER, p_name VARCHAR2)
                    RETURN SELF AS RESULT IS
                BEGIN
                    self.emp_id := p_id;
                    self.emp_name := p_name;
                    RETURN;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("employee_type", method.getName());
        assertEquals(MethodType.CONSTRUCTOR, method.getMethodType());
        assertTrue(method.isConstructor());

        // RETURN SELF AS RESULT: the AS belongs to the return clause, so the body starts at IS.
        String extracted = sourceOf(method, cleaned);
        assertTrue(extracted.contains("self.emp_name := p_name;"), extracted);
        assertEquals("IS", cleaned.substring(method.getBodyStartPos(), method.getBodyStartPos() + 2));
    }

    /**
     * {@code OVERRIDING} is part of the segment. Recorded from {@code MEMBER} instead, the stored
     * source parses as an ordinary member method and the inheritance rejection can never fire — the
     * method is emitted as a plain function that silently drops the dispatch it exists for.
     */
    @Test
    void scan_overridingModifierIsPartOfTheSegment() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY manager_type AS
                OVERRIDING MEMBER FUNCTION get_label RETURN VARCHAR2 IS
                BEGIN
                    RETURN 'manager';
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("get_label", method.getName());
        assertEquals(MethodType.MEMBER_FUNCTION, method.getMethodType());
        assertTrue(sourceOf(method, cleaned).startsWith("OVERRIDING MEMBER FUNCTION"),
                sourceOf(method, cleaned));
    }

    @Test
    void scan_finalMemberProcedure() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                FINAL MEMBER PROCEDURE seal IS
                BEGIN
                    self.sealed := 1;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("seal", method.getName());
        assertEquals(MethodType.MEMBER_PROCEDURE, method.getMethodType());
        assertTrue(sourceOf(method, cleaned).startsWith("FINAL MEMBER PROCEDURE"));
    }

    /**
     * A Java {@code call_spec} body has no {@code BEGIN}/{@code END}. Recording it lets the
     * transformer throw its named "implemented in Java/C" rejection for this one method; the
     * assertion that matters is that the <em>following</em> method is still found, because the old
     * scanner searched on for an END and consumed it.
     */
    @Test
    void scan_callSpecBodyDoesNotDerailTheRestOfTheType() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION to_json RETURN VARCHAR2 IS
                LANGUAGE JAVA NAME 'Emp.toJson() return java.lang.String';

                MEMBER FUNCTION get_salary RETURN NUMBER IS
                BEGIN
                    RETURN self.salary;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(2, segments.getMethods().size());

        TypeMethodSegment callSpec = segments.getMethods().get(0);
        assertEquals("to_json", callSpec.getName());
        assertTrue(sourceOf(callSpec, cleaned).trim().endsWith("java.lang.String';"),
                sourceOf(callSpec, cleaned));

        TypeMethodSegment plsql = segments.getMethods().get(1);
        assertEquals("get_salary", plsql.getName());
        assertTrue(sourceOf(plsql, cleaned).contains("RETURN self.salary;"));
    }

    @Test
    void scan_multipleMethods() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION get_salary RETURN NUMBER IS
                BEGIN
                    RETURN self.salary;
                END;

                STATIC FUNCTION create_employee(p_name VARCHAR2) RETURN employee_type IS
                BEGIN
                    RETURN employee_type(1, p_name);
                END;

                MEMBER PROCEDURE update_salary(p_new_salary NUMBER) IS
                BEGIN
                    self.salary := p_new_salary;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(3, segments.getMethods().size());
        assertEquals("get_salary", segments.getMethods().get(0).getName());
        assertEquals(MethodType.MEMBER_FUNCTION, segments.getMethods().get(0).getMethodType());
        assertEquals("create_employee", segments.getMethods().get(1).getName());
        assertEquals(MethodType.STATIC_FUNCTION, segments.getMethods().get(1).getMethodType());
        assertEquals("update_salary", segments.getMethods().get(2).getName());
        assertEquals(MethodType.MEMBER_PROCEDURE, segments.getMethods().get(2).getMethodType());

        // Segments must not overlap - a stored source that swallows its neighbour pairs one
        // method's body onto another method's signature.
        for (int i = 1; i < segments.getMethods().size(); i++) {
            assertTrue(segments.getMethods().get(i - 1).getEndPos()
                            <= segments.getMethods().get(i).getStartPos(),
                    "segment " + (i - 1) + " overlaps segment " + i);
        }
    }

    @Test
    void scan_overloadedMethods() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION calculate_bonus RETURN NUMBER IS
                BEGIN
                    RETURN 1000;
                END;

                MEMBER FUNCTION calculate_bonus(p_multiplier NUMBER) RETURN NUMBER IS
                BEGIN
                    RETURN 1000 * p_multiplier;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(2, segments.getMethods().size());

        TypeMethodSegment method1 = segments.getMethods().get(0);
        TypeMethodSegment method2 = segments.getMethods().get(1);

        assertEquals("calculate_bonus", method1.getName());
        assertEquals("calculate_bonus", method2.getName());
        assertNotEquals(method1.getStartPos(), method2.getStartPos());

        // Source order decides the overload ordinal, so the no-arg form must come first.
        assertTrue(sourceOf(method1, cleaned).contains("RETURN 1000;"), sourceOf(method1, cleaned));
        assertTrue(sourceOf(method2, cleaned).contains("RETURN 1000 * p_multiplier;"),
                sourceOf(method2, cleaned));
    }

    @Test
    void scan_stringLiteralWithKeywords() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION get_description RETURN VARCHAR2 IS
                    v_text VARCHAR2(100) := 'This MEMBER FUNCTION returns STATIC data; END;';
                BEGIN
                    RETURN 'Employee with CONSTRUCTOR information';
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());

        TypeMethodSegment method = segments.getMethods().get(0);
        assertEquals("get_description", method.getName());
        assertTrue(sourceOf(method, cleaned).contains("RETURN 'Employee with CONSTRUCTOR information';"));
    }

    /** Comments are on the lexer's hidden channel, so pre-cleaning is no longer required. */
    @Test
    void scan_withCommentsInPlace() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                -- MEMBER FUNCTION commented_out RETURN NUMBER IS
                MEMBER FUNCTION get_salary RETURN NUMBER IS
                BEGIN
                    /* END; this comment must not end the method */
                    RETURN self.salary;
                END;
            END;
            """;

        TypeBodySegments segments = scanner.scanTypeBody(typeBody);

        assertEquals(1, segments.getMethods().size());
        assertEquals("get_salary", segments.getMethods().get(0).getName());
        assertTrue(sourceOf(segments.getMethods().get(0), typeBody).contains("RETURN self.salary;"));
    }

    @Test
    void scan_noMethods() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
            END;
            """;

        TypeBodySegments segments = scan(typeBody);

        assertEquals(0, segments.getMethods().size());
    }

    @Test
    void scan_caseInsensitiveKeywords() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                member function get_salary return number is
                begin
                    if self.salary is null then
                        return 0;
                    end if;
                    return self.salary;
                end;

                STATIC FUNCTION Create_Employee(p_name VARCHAR2) RETURN employee_type IS
                BEGIN
                    RETURN employee_type(1, p_name);
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(2, segments.getMethods().size());

        TypeMethodSegment method1 = segments.getMethods().get(0);
        assertEquals("get_salary", method1.getName());
        assertEquals(MethodType.MEMBER_FUNCTION, method1.getMethodType());
        assertTrue(sourceOf(method1, cleaned).contains("return self.salary;"),
                sourceOf(method1, cleaned));

        TypeMethodSegment method2 = segments.getMethods().get(1);
        assertEquals("Create_Employee", method2.getName());
        assertEquals(MethodType.STATIC_FUNCTION, method2.getMethodType());
    }

    /**
     * {@code ORDER} and {@code MAP} are ordinary keywords inside a method body. A method start is
     * only recognised when the modifier run reaches FUNCTION or PROCEDURE.
     */
    @Test
    void scan_orderByInBodyIsNotAMethodStart() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION first_name RETURN VARCHAR2 IS
                    v_name VARCHAR2(100);
                BEGIN
                    SELECT name INTO v_name FROM emp ORDER BY hired_on;
                    RETURN v_name;
                END;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(1, segments.getMethods().size());
        assertEquals("first_name", segments.getMethods().get(0).getName());
        assertTrue(sourceOf(segments.getMethods().get(0), cleaned).contains("ORDER BY hired_on;"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
