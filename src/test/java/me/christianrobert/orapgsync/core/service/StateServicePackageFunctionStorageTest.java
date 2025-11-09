package me.christianrobert.orapgsync.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StateService package function storage functionality.
 *
 * Verifies the new storage methods added for package segmentation optimization:
 * - storePackageFunctions()
 * - getPackageFunctionSource()
 * - getAllPackageFunctionStubs()
 * - getReducedPackageBody()
 * - clearPackageFunctionStorage()
 * - resetState() clears package function storage
 */
class StateServicePackageFunctionStorageTest {

    private StateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new StateService();
    }

    @Test
    void storeAndRetrievePackageFunctions_success() {
        // Prepare test data
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_salary", "FUNCTION get_salary(...) IS BEGIN ... END;");
        fullSources.put("calc_bonus", "FUNCTION calc_bonus(...) IS BEGIN ... END;");

        Map<String, String> stubSources = new HashMap<>();
        stubSources.put("get_salary", "FUNCTION get_salary(...) IS BEGIN RETURN NULL; END;");
        stubSources.put("calc_bonus", "FUNCTION calc_bonus(...) IS BEGIN RETURN NULL; END;");

        String reducedBody = "CREATE PACKAGE BODY hr.emp_pkg AS g_counter INTEGER; END;";

        // Store
        stateService.storePackageFunctions("HR", "EMP_PKG", fullSources, stubSources, reducedBody);

        // Verify full sources
        String getSalaryFull = stateService.getPackageFunctionSource("HR", "EMP_PKG", "get_salary");
        assertNotNull(getSalaryFull);
        assertTrue(getSalaryFull.contains("FUNCTION get_salary"));
        assertFalse(getSalaryFull.contains("RETURN NULL"), "Full source should not be a stub");

        String calcBonusFull = stateService.getPackageFunctionSource("HR", "EMP_PKG", "calc_bonus");
        assertNotNull(calcBonusFull);
        assertTrue(calcBonusFull.contains("FUNCTION calc_bonus"));

        // Verify stubs
        Map<String, String> retrievedStubs = stateService.getAllPackageFunctionStubs("HR", "EMP_PKG");
        assertEquals(2, retrievedStubs.size());
        assertTrue(retrievedStubs.get("get_salary").contains("RETURN NULL"));
        assertTrue(retrievedStubs.get("calc_bonus").contains("RETURN NULL"));

        // Verify reduced body
        String retrievedReduced = stateService.getReducedPackageBody("HR", "EMP_PKG");
        assertEquals(reducedBody, retrievedReduced);
    }

    @Test
    void getPackageFunctionSource_caseInsensitive() {
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_value", "FUNCTION get_value() IS BEGIN RETURN 42; END;");

        stateService.storePackageFunctions("HR", "TEST_PKG", fullSources, new HashMap<>(), "");

        // Test different case combinations
        assertNotNull(stateService.getPackageFunctionSource("HR", "TEST_PKG", "get_value"));
        assertNotNull(stateService.getPackageFunctionSource("hr", "test_pkg", "get_value"));
        assertNotNull(stateService.getPackageFunctionSource("Hr", "Test_Pkg", "GET_VALUE"));
    }

    @Test
    void getPackageFunctionSource_notFound() {
        // Non-existent package
        String result = stateService.getPackageFunctionSource("HR", "NONEXISTENT", "func");
        assertNull(result, "Should return null for non-existent package");

        // Existing package, non-existent function
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("get_value", "FUNCTION get_value() IS ...");
        stateService.storePackageFunctions("HR", "TEST_PKG", fullSources, new HashMap<>(), "");

        String result2 = stateService.getPackageFunctionSource("HR", "TEST_PKG", "nonexistent_func");
        assertNull(result2, "Should return null for non-existent function");
    }

    @Test
    void getAllPackageFunctionStubs_emptyWhenNotFound() {
        Map<String, String> stubs = stateService.getAllPackageFunctionStubs("HR", "NONEXISTENT");
        assertNotNull(stubs, "Should return empty map, not null");
        assertTrue(stubs.isEmpty(), "Should return empty map for non-existent package");
    }

    @Test
    void clearPackageFunctionStorage_clearsAllMaps() {
        // Store data in multiple packages
        Map<String, String> fullSources1 = new HashMap<>();
        fullSources1.put("func1", "FUNCTION func1() IS ...");
        stateService.storePackageFunctions("HR", "PKG1", fullSources1, new HashMap<>(), "body1");

        Map<String, String> fullSources2 = new HashMap<>();
        fullSources2.put("func2", "FUNCTION func2() IS ...");
        stateService.storePackageFunctions("HR", "PKG2", fullSources2, new HashMap<>(), "body2");

        // Verify data exists
        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG1", "func1"));
        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG2", "func2"));
        assertNotNull(stateService.getReducedPackageBody("HR", "PKG1"));

        // Clear storage
        stateService.clearPackageFunctionStorage();

        // Verify all data cleared
        assertNull(stateService.getPackageFunctionSource("HR", "PKG1", "func1"));
        assertNull(stateService.getPackageFunctionSource("HR", "PKG2", "func2"));
        assertNull(stateService.getReducedPackageBody("HR", "PKG1"));
        assertNull(stateService.getReducedPackageBody("HR", "PKG2"));
    }

    @Test
    void resetState_clearsPackageFunctionStorage() {
        // Store data
        Map<String, String> fullSources = new HashMap<>();
        fullSources.put("func", "FUNCTION func() IS ...");
        stateService.storePackageFunctions("HR", "PKG", fullSources, new HashMap<>(), "body");

        // Verify data exists
        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG", "func"));

        // Reset state
        stateService.resetState();

        // Verify package function storage cleared
        assertNull(stateService.getPackageFunctionSource("HR", "PKG", "func"));
        assertNull(stateService.getReducedPackageBody("HR", "PKG"));
    }

    @Test
    void storePackageFunctions_multiplePackages() {
        // Store functions from different packages
        Map<String, String> fullSources1 = new HashMap<>();
        fullSources1.put("func1", "FUNCTION func1() IS ...");

        Map<String, String> fullSources2 = new HashMap<>();
        fullSources2.put("func2", "FUNCTION func2() IS ...");

        stateService.storePackageFunctions("HR", "PKG1", fullSources1, new HashMap<>(), "body1");
        stateService.storePackageFunctions("SALES", "PKG2", fullSources2, new HashMap<>(), "body2");

        // Verify both packages stored correctly
        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG1", "func1"));
        assertNotNull(stateService.getPackageFunctionSource("SALES", "PKG2", "func2"));

        // Verify no cross-contamination
        assertNull(stateService.getPackageFunctionSource("HR", "PKG1", "func2"));
        assertNull(stateService.getPackageFunctionSource("SALES", "PKG2", "func1"));
    }
}
