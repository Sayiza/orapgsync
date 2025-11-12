package me.christianrobert.orapgsync.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for type method storage functionality in StateService.
 * Validates storage, retrieval, and cleanup of type method sources.
 */
class StateServiceTypeMethodStorageTest {

    private StateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new StateService();
    }

    @Test
    void storeTypeMethodSources_success() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS\n  v_base NUMBER := 5000;\nBEGIN\n  RETURN v_base;\nEND;");
        fullSources.put("get_name", "MEMBER FUNCTION get_name RETURN VARCHAR2 IS\nBEGIN\n  RETURN self.name;\nEND;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS\nBEGIN\n  RETURN NULL;\nEND;");
        stubSources.put("get_name", "MEMBER FUNCTION get_name RETURN VARCHAR2 IS\nBEGIN\n  RETURN NULL;\nEND;");

        // Act
        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Assert
        String retrievedFull = stateService.getTypeMethodSource(schema, typeName, "get_salary");
        assertNotNull(retrievedFull);
        assertTrue(retrievedFull.contains("v_base NUMBER"));

        Map<String, String> retrievedStubs = stateService.getAllTypeMethodStubs(schema, typeName);
        assertEquals(2, retrievedStubs.size());
        assertTrue(retrievedStubs.containsKey("get_salary"));
        assertTrue(retrievedStubs.containsKey("get_name"));
    }

    @Test
    void getTypeMethodSource_exists() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        String expectedSource = "MEMBER FUNCTION calculate_bonus RETURN NUMBER IS\n  v_bonus NUMBER := 1000;\nBEGIN\n  RETURN v_bonus;\nEND;";
        fullSources.put("calculate_bonus", expectedSource);

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("calculate_bonus", "MEMBER FUNCTION calculate_bonus RETURN NUMBER IS\nBEGIN\n  RETURN NULL;\nEND;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Act
        String retrievedSource = stateService.getTypeMethodSource(schema, typeName, "calculate_bonus");

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
        fullSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Act & Assert - non-existent method
        assertNull(stateService.getTypeMethodSource(schema, typeName, "non_existent_method"));

        // Act & Assert - non-existent type
        assertNull(stateService.getTypeMethodSource(schema, "NON_EXISTENT_TYPE", "get_salary"));

        // Act & Assert - non-existent schema
        assertNull(stateService.getTypeMethodSource("NON_EXISTENT_SCHEMA", typeName, "get_salary"));
    }

    @Test
    void clearTypeMethodStorage_clearsAllMaps() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");
        fullSources.put("get_name", "MEMBER FUNCTION get_name RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");
        stubSources.put("get_name", "MEMBER FUNCTION get_name RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Verify data exists
        assertNotNull(stateService.getTypeMethodSource(schema, typeName, "get_salary"));
        assertFalse(stateService.getAllTypeMethodStubs(schema, typeName).isEmpty());

        // Act
        stateService.clearTypeMethodStorage();

        // Assert
        assertNull(stateService.getTypeMethodSource(schema, typeName, "get_salary"));
        assertTrue(stateService.getAllTypeMethodStubs(schema, typeName).isEmpty());
    }

    @Test
    void resetState_clearsTypeMethodStorage() {
        // Arrange
        String schema = "HR";
        String typeName = "EMPLOYEE_TYPE";
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

        // Verify data exists
        assertNotNull(stateService.getTypeMethodSource(schema, typeName, "get_salary"));

        // Act
        stateService.resetState();

        // Assert
        assertNull(stateService.getTypeMethodSource(schema, typeName, "get_salary"));
        assertTrue(stateService.getAllTypeMethodStubs(schema, typeName).isEmpty());
    }

    @Test
    void storeTypeMethodSources_caseInsensitiveKeys() {
        // Arrange
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        // Act - store with mixed case
        stateService.storeTypeMethodSources("HR", "Employee_Type", fullSources, stubSources);

        // Assert - retrieve with different case
        assertNotNull(stateService.getTypeMethodSource("hr", "employee_type", "GET_SALARY"));
        assertNotNull(stateService.getTypeMethodSource("HR", "EMPLOYEE_TYPE", "get_salary"));
        assertNotNull(stateService.getTypeMethodSource("Hr", "Employee_Type", "Get_Salary"));
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
        employeeFullSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> employeeStubSources = new HashMap<>();
        employeeStubSources.put("get_salary", "MEMBER FUNCTION get_salary RETURN NUMBER IS BEGIN RETURN NULL; END;");

        Map<String, String> addressFullSources = new HashMap<>();
        addressFullSources.put("get_street", "MEMBER FUNCTION get_street RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        Map<String, String> addressStubSources = new HashMap<>();
        addressStubSources.put("get_street", "MEMBER FUNCTION get_street RETURN VARCHAR2 IS BEGIN RETURN NULL; END;");

        // Act
        stateService.storeTypeMethodSources("HR", "EMPLOYEE_TYPE", employeeFullSources, employeeStubSources);
        stateService.storeTypeMethodSources("HR", "ADDRESS_TYPE", addressFullSources, addressStubSources);

        // Assert
        assertNotNull(stateService.getTypeMethodSource("HR", "EMPLOYEE_TYPE", "get_salary"));
        assertNotNull(stateService.getTypeMethodSource("HR", "ADDRESS_TYPE", "get_street"));
        assertNull(stateService.getTypeMethodSource("HR", "EMPLOYEE_TYPE", "get_street"));
        assertNull(stateService.getTypeMethodSource("HR", "ADDRESS_TYPE", "get_salary"));
    }
}
