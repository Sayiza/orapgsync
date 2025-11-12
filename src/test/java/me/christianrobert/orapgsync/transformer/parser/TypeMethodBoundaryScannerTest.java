package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.MethodType;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.TypeMethodSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeMethodBoundaryScannerTest {

    private final TypeMethodBoundaryScanner scanner = new TypeMethodBoundaryScanner();

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
        assertTrue(method.isFunction());
        assertTrue(method.isStaticMethod());
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
        assertTrue(method.isFunction());
        assertTrue(method.isMapMethod());
    }

    @Test
    void scan_orderMemberFunction() {
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
        assertTrue(method.isFunction());
        assertTrue(method.isOrderMethod());
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
        assertTrue(method.isFunction());
        assertTrue(method.isConstructor());
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

        // Check first method
        TypeMethodSegment method1 = segments.getMethods().get(0);
        assertEquals("get_salary", method1.getName());
        assertEquals(MethodType.MEMBER_FUNCTION, method1.getMethodType());

        // Check second method
        TypeMethodSegment method2 = segments.getMethods().get(1);
        assertEquals("create_employee", method2.getName());
        assertEquals(MethodType.STATIC_FUNCTION, method2.getMethodType());

        // Check third method
        TypeMethodSegment method3 = segments.getMethods().get(2);
        assertEquals("update_salary", method3.getName());
        assertEquals(MethodType.MEMBER_PROCEDURE, method3.getMethodType());
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

        // Both should have same name but different signatures
        TypeMethodSegment method1 = segments.getMethods().get(0);
        TypeMethodSegment method2 = segments.getMethods().get(1);

        assertEquals("calculate_bonus", method1.getName());
        assertEquals("calculate_bonus", method2.getName());
        assertEquals(MethodType.MEMBER_FUNCTION, method1.getMethodType());
        assertEquals(MethodType.MEMBER_FUNCTION, method2.getMethodType());

        // Different positions
        assertNotEquals(method1.getStartPos(), method2.getStartPos());
    }

    @Test
    void scan_stringLiteralWithKeywords() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION get_description RETURN VARCHAR2 IS
                    v_text VARCHAR2(100) := 'This MEMBER FUNCTION returns STATIC data';
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
        assertEquals(MethodType.MEMBER_FUNCTION, method.getMethodType());
    }

    @Test
    void scan_noMethods() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        assertEquals(0, segments.getMethods().size());
    }

    @Test
    void scan_caseInsensitiveKeywords() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                member function get_salary return number is
                begin
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

        TypeMethodSegment method2 = segments.getMethods().get(1);
        assertEquals("Create_Employee", method2.getName());
        assertEquals(MethodType.STATIC_FUNCTION, method2.getMethodType());
    }
}
