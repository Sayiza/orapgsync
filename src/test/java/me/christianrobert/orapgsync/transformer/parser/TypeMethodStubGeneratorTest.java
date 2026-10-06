package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.TypeMethodSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypeMethodStubGeneratorTest {

    private final TypeMethodBoundaryScanner scanner = new TypeMethodBoundaryScanner();
    private final TypeMethodStubGenerator stubGenerator = new TypeMethodStubGenerator();

    @Test
    void generateStub_memberFunction() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION get_salary RETURN NUMBER IS
                    v_base NUMBER := 5000;
                    v_bonus NUMBER := 1000;
                BEGIN
                    SELECT salary INTO v_base FROM employees WHERE id = self.emp_id;
                    RETURN v_base + v_bonus;
                END get_salary;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);
        TypeMethodSegment method = segments.getMethods().get(0);

        String fullSource = cleaned.substring(method.getStartPos(), method.getEndPos());
        String stub = stubGenerator.generateStub(fullSource, method);

        // Verify stub contains signature
        assertTrue(stub.contains("MEMBER FUNCTION get_salary"));
        assertTrue(stub.contains("RETURN NUMBER"));

        // Verify stub has minimal body
        assertTrue(stub.contains("BEGIN"));
        assertTrue(stub.contains("RETURN NULL;"));
        assertTrue(stub.contains("END;"));

        // Verify stub does NOT contain original variables or logic
        assertFalse(stub.contains("v_base"));
        assertFalse(stub.contains("v_bonus"));
        assertFalse(stub.contains("SELECT"));
    }

    @Test
    void generateStub_staticProcedure() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                STATIC PROCEDURE initialize_globals IS
                    v_counter NUMBER := 0;
                BEGIN
                    UPDATE globals SET counter = v_counter;
                    COMMIT;
                END initialize_globals;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);
        TypeMethodSegment method = segments.getMethods().get(0);

        String fullSource = cleaned.substring(method.getStartPos(), method.getEndPos());
        String stub = stubGenerator.generateStub(fullSource, method);

        // Verify stub contains signature
        assertTrue(stub.contains("STATIC PROCEDURE initialize_globals"));

        // Verify stub has minimal body (RETURN for procedure, not RETURN NULL)
        assertTrue(stub.contains("BEGIN"));
        assertTrue(stub.contains("RETURN;"));
        assertTrue(stub.contains("END;"));

        // Verify stub does NOT contain original logic
        assertFalse(stub.contains("v_counter"));
        assertFalse(stub.contains("UPDATE"));
        assertFalse(stub.contains("COMMIT"));
    }

    @Test
    void generateStub_mapFunction() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MAP MEMBER FUNCTION compare RETURN NUMBER IS
                    v_result NUMBER;
                BEGIN
                    v_result := self.emp_id * 1000 + self.salary;
                    RETURN v_result;
                END compare;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);
        TypeMethodSegment method = segments.getMethods().get(0);

        String fullSource = cleaned.substring(method.getStartPos(), method.getEndPos());
        String stub = stubGenerator.generateStub(fullSource, method);

        // Verify stub contains signature
        assertTrue(stub.contains("MAP MEMBER FUNCTION compare"));
        assertTrue(stub.contains("RETURN NUMBER"));

        // Verify stub has minimal body
        assertTrue(stub.contains("RETURN NULL;"));

        // Verify stub does NOT contain original logic
        assertFalse(stub.contains("v_result"));
    }

    @Test
    void generateStub_constructor() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                CONSTRUCTOR FUNCTION employee_type(p_id NUMBER, p_name VARCHAR2)
                    RETURN SELF AS RESULT IS
                BEGIN
                    self.emp_id := p_id;
                    self.emp_name := upper(p_name);
                    self.creation_date := SYSDATE;
                    RETURN;
                END employee_type;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);
        TypeMethodSegment method = segments.getMethods().get(0);

        String fullSource = cleaned.substring(method.getStartPos(), method.getEndPos());
        String stub = stubGenerator.generateStub(fullSource, method);

        // Verify stub contains signature
        assertTrue(stub.contains("CONSTRUCTOR FUNCTION employee_type"), "Stub should contain CONSTRUCTOR FUNCTION employee_type. Actual stub:\n" + stub);
        assertTrue(stub.contains("RETURN SELF AS RESULT"));

        // Verify stub has minimal body
        assertTrue(stub.contains("BEGIN"));
        assertTrue(stub.contains("END;"));

        // Constructor uses RETURN; (returns SELF implicitly, not NULL)
        // For stub purposes, we treat it as a function (returns SELF) so RETURN NULL is fine
        // But let's check what we actually generated
        assertTrue(stub.contains("RETURN NULL;") || stub.contains("RETURN;"));

        // Verify stub does NOT contain original logic
        assertFalse(stub.contains("self.emp_id :="));
        assertFalse(stub.contains("upper"));
        assertFalse(stub.contains("SYSDATE"));
    }

    @Test
    void generateStub_overloadedMethod() {
        String typeBody = """
            CREATE OR REPLACE TYPE BODY employee_type AS
                MEMBER FUNCTION calculate_bonus RETURN NUMBER IS
                BEGIN
                    RETURN 1000;
                END calculate_bonus;

                MEMBER FUNCTION calculate_bonus(p_multiplier NUMBER) RETURN NUMBER IS
                    v_result NUMBER;
                BEGIN
                    v_result := 1000 * p_multiplier;
                    RETURN v_result;
                END calculate_bonus;
            END;
            """;

        String cleaned = CodeCleaner.removeComments(typeBody);
        TypeBodySegments segments = scanner.scanTypeBody(cleaned);

        // Test both overloaded methods
        assertEquals(2, segments.getMethods().size());

        // First overload (no parameters)
        TypeMethodSegment method1 = segments.getMethods().get(0);
        String fullSource1 = cleaned.substring(method1.getStartPos(), method1.getEndPos());
        String stub1 = stubGenerator.generateStub(fullSource1, method1);

        assertTrue(stub1.contains("MEMBER FUNCTION calculate_bonus"));
        assertTrue(stub1.contains("RETURN NULL;"));
        assertFalse(stub1.contains("p_multiplier"));

        // Second overload (with parameter)
        TypeMethodSegment method2 = segments.getMethods().get(1);
        String fullSource2 = cleaned.substring(method2.getStartPos(), method2.getEndPos());
        String stub2 = stubGenerator.generateStub(fullSource2, method2);

        assertTrue(stub2.contains("MEMBER FUNCTION calculate_bonus"));
        assertTrue(stub2.contains("p_multiplier"));
        assertTrue(stub2.contains("RETURN NULL;"));
        assertFalse(stub2.contains("v_result"));
    }
}
