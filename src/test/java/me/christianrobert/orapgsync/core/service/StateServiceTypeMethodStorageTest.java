package me.christianrobert.orapgsync.core.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodSourceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for type method storage functionality in StateService.
 * Validates storage, retrieval, and cleanup of type method sources.
 *
 * <p>Sources are keyed by {@link TypeMethodSourceKey} — method name plus overload ordinal — because
 * Oracle allows many methods on one type to share a name. Keying by bare name silently kept only
 * the last overload stored.
 */
class StateServiceTypeMethodStorageTest {

    private StateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new StateService();
    }

    /** Convenience for the common single-overload case. */
    private static String key(String methodName) {
        return TypeMethodSourceKey.of(methodName, 0);
    }

    @Test
    void storeTypeMethodSources_success() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS\n  v_base NUMBER := 5000;\nBEGIN\n  RETURN v_base;\nEND;");
        fullSources.put(key("get_name"), "MEMBER FUNCTION get_name RETURN VARCHAR2 IS\nBEGIN\n  RETURN self.name;\nEND;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS\nBEGIN\n  RETURN NULL;\nEND;");
        stubSources.put(key("get_name"), "MEMBER FUNCTION get_name RETURN VARCHAR2 IS\nBEGIN\n  RETURN NULL;\nEND;");

        // Act
        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Assert
        String retrievedFull = stateService.getTypeMethodSource(schema, typeName, "get_salary", 0);
        assertNotNull(retrievedFull);
        assertTrue(retrievedFull.contains("v_base NUMBER"));

        Map<String, String> retrievedStubs = stateService.getAllTypeMethodStubs(schema, typeName);
        assertEquals(2, retrievedStubs.size());
        assertTrue(retrievedStubs.containsKey(key("get_salary")));
        assertTrue(retrievedStubs.containsKey(key("get_name")));
    }

    /**
     * The reason the key carries an ordinal at all: overloads must not overwrite each other.
     */
    @Test
    void storeTypeMethodSources_overloadsAreKeptDistinct() {
        // Arrange - three methods sharing one name, as Oracle permits
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put(TypeMethodSourceKey.of("add_item", 0), "MEMBER PROCEDURE add_item IS BEGIN NULL; END;");
        fullSources.put(TypeMethodSourceKey.of("add_item", 1), "MEMBER PROCEDURE add_item(p_code VARCHAR2) IS BEGIN NULL; END;");
        fullSources.put(TypeMethodSourceKey.of("add_item", 2), "MEMBER PROCEDURE add_item(p_code VARCHAR2, p_qty NUMBER) IS BEGIN NULL; END;");

        // Act
        stateService.storeTypeMethodSources("HR", "ORDER_TYPE", fullSources, new HashMap<>());

        // Assert - each overload retrievable, and distinct
        String zero = stateService.getTypeMethodSource("HR", "ORDER_TYPE", "add_item", 0);
        String one = stateService.getTypeMethodSource("HR", "ORDER_TYPE", "add_item", 1);
        String two = stateService.getTypeMethodSource("HR", "ORDER_TYPE", "add_item", 2);

        assertNotNull(zero);
        assertNotNull(one);
        assertNotNull(two);
        assertFalse(zero.contains("p_code"), "overload 0 takes no parameters");
        assertTrue(one.contains("p_code VARCHAR2)"), "overload 1 takes one parameter");
        assertTrue(two.contains("p_qty NUMBER"), "overload 2 takes two parameters");

        // And an ordinal beyond the stored set is absent rather than falling back to another
        assertNull(stateService.getTypeMethodSource("HR", "ORDER_TYPE", "add_item", 3));
    }

    @Test
    void getTypeMethodSource_exists() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        String expectedSource = "MEMBER FUNCTION calculate_bonus RETURN NUMBER IS\n  v_bonus NUMBER := 1000;\nBEGIN\n  RETURN v_bonus;\nEND;";
        fullSources.put(key("calculate_bonus"), expectedSource);

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put(key("calculate_bonus"), "MEMBER FUNCTION calculate_bonus RETURN NUMBER IS\nBEGIN\n  RETURN NULL;\nEND;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Act
        String retrievedSource = stateService.getTypeMethodSource(schema, typeName, "calculate_bonus", 0);

        // Assert
        assertNotNull(retrievedSource);
        assertEquals(expectedSource, retrievedSource);
    }

    @Test
    void getTypeMethodSource_notExists() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Act & Assert - non-existent method
        assertNull(stateService.getTypeMethodSource(schema, typeName, "non_existent_method", 0));

        // Act & Assert - non-existent type
        assertNull(stateService.getTypeMethodSource(schema, "NON_EXISTENT_TYPE", "get_salary", 0));

        // Act & Assert - non-existent schema
        assertNull(stateService.getTypeMethodSource("NON_EXISTENT_SCHEMA", typeName, "get_salary", 0));
    }

    @Test
    void clearTypeMethodStorage_clearsAllMaps() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");
        fullSources.put(key("get_name"), "MEMBER FUNCTION get_name RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");
        stubSources.put(key("get_name"), "MEMBER FUNCTION get_name RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Verify data exists
        assertNotNull(stateService.getTypeMethodSource(schema, typeName, "get_salary", 0));
        assertFalse(stateService.getAllTypeMethodStubs(schema, typeName).isEmpty());

        // Act
        stateService.clearTypeMethodStorage();

        // Assert
        assertNull(stateService.getTypeMethodSource(schema, typeName, "get_salary", 0));
        assertTrue(stateService.getAllTypeMethodStubs(schema, typeName).isEmpty());
    }

    @Test
    void resetState_clearsTypeMethodStorage() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Verify data exists
        assertNotNull(stateService.getTypeMethodSource(schema, typeName, "get_salary", 0));

        // Act
        stateService.resetState();

        // Assert
        assertNull(stateService.getTypeMethodSource(schema, typeName, "get_salary", 0));
        assertTrue(stateService.getAllTypeMethodStubs(schema, typeName).isEmpty());
    }

    @Test
    void storeTypeMethodSources_caseInsensitiveKeys() {
        // Arrange
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        // Act - store with mixed case
        stateService.storeTypeMethodSources("HR", "Employee_Type", fullSources, stubSources);

        // Assert - retrieve with different case
        assertNotNull(stateService.getTypeMethodSource("hr", "employee_type", "GET_SALARY", 0));
        assertNotNull(stateService.getTypeMethodSource("HR", "EMPLOYEE_TYPE", "get_salary", 0));
        assertNotNull(stateService.getTypeMethodSource("Hr", "Employee_Type", "Get_Salary", 0));
    }

    @Test
    void getAllTypeMethodStubs_emptyForNonExistentType() {
        // Act
        Map<String, String> stubs = stateService.getAllTypeMethodStubs("HR", "NON_EXISTENT_TYPE");

        // Assert
        assertNotNull(stubs);
        assertTrue(stubs.isEmpty());
    }

    @Test
    void storeTypeMethodSources_multipleTypes() {
        // Arrange
        Map<String, String> employeeFullSources = new HashMap<>();
        employeeFullSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> employeeStubSources = new HashMap<>();
        employeeStubSources.put(key("get_salary"), "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> addressFullSources = new HashMap<>();
        addressFullSources.put(key("get_street"), "MEMBER FUNCTION get_street RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        Map<String, String> addressStubSources = new HashMap<>();
        addressStubSources.put(key("get_street"), "MEMBER FUNCTION get_street RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        // Act
        stateService.storeTypeMethodSources("HR", "EMPLOYEE_TYPE", employeeFullSources, employeeStubSources);
        stateService.storeTypeMethodSources("HR", "ADDRESS_TYPE", addressFullSources, addressStubSources);

        // Assert
        assertNotNull(stateService.getTypeMethodSource("HR", "EMPLOYEE_TYPE", "get_salary", 0));
        assertNotNull(stateService.getTypeMethodSource("HR", "ADDRESS_TYPE", "get_street", 0));
        assertNull(stateService.getTypeMethodSource("HR", "EMPLOYEE_TYPE", "get_street", 0));
        assertNull(stateService.getTypeMethodSource("HR", "ADDRESS_TYPE", "get_salary", 0));
    }
}
