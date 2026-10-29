package me.christianrobert.orapgsync.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StateService package function storage functionality.
 *
 * Verifies the storage methods used by package segmentation:
 * - storePackageFunctions()
 * - getPackageFunctionSource() — one body of a name, by its position in the package body
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
        Map<String, List<String>> fullSources = Map.of(
                "get_salary", List.of("FUNCTION get_salary(...) IS BEGIN ... END;"),
                "calc_bonus", List.of("FUNCTION calc_bonus(...) IS BEGIN ... END;"));
        String reducedBody = "CREATE PACKAGE BODY hr.emp_pkg AS g_counter INTEGER; END;";

        stateService.storePackageFunctions("HR", "EMP_PKG", fullSources, reducedBody);

        String getSalaryFull = stateService.getPackageFunctionSource("HR", "EMP_PKG", "get_salary", 0);
        assertNotNull(getSalaryFull);
        assertTrue(getSalaryFull.contains("FUNCTION get_salary"));

        String calcBonusFull = stateService.getPackageFunctionSource("HR", "EMP_PKG", "calc_bonus", 0);
        assertNotNull(calcBonusFull);
        assertTrue(calcBonusFull.contains("FUNCTION calc_bonus"));

        assertEquals(reducedBody, stateService.getReducedPackageBody("HR", "EMP_PKG"));
    }

    /** Overloads share a name; each body stays retrievable by its position, none overwrites another. */
    @Test
    void overloadedName_everyBodyIsKept() {
        stateService.storePackageFunctions("HR", "FMT_PKG", Map.of("fmt", List.of(
                "FUNCTION fmt(p NUMBER) RETURN VARCHAR2 IS BEGIN RETURN 'number'; END;",
                "FUNCTION fmt(p DATE) RETURN VARCHAR2 IS BEGIN RETURN 'date'; END;")), "");

        assertTrue(stateService.getPackageFunctionSource("HR", "FMT_PKG", "fmt", 0).contains("'number'"));
        assertTrue(stateService.getPackageFunctionSource("HR", "FMT_PKG", "fmt", 1).contains("'date'"));
        assertNull(stateService.getPackageFunctionSource("HR", "FMT_PKG", "fmt", 2));
        assertNull(stateService.getPackageFunctionSource("HR", "FMT_PKG", "fmt", -1),
                "an unpaired member (ordinal -1) must not get any body of its name");
    }

    @Test
    void getPackageFunctionSource_caseInsensitive() {
        stateService.storePackageFunctions("HR", "TEST_PKG",
                Map.of("get_value", List.of("FUNCTION get_value() IS BEGIN RETURN 42; END;")), "");

        assertNotNull(stateService.getPackageFunctionSource("HR", "TEST_PKG", "get_value", 0));
        assertNotNull(stateService.getPackageFunctionSource("hr", "test_pkg", "get_value", 0));
        assertNotNull(stateService.getPackageFunctionSource("Hr", "Test_Pkg", "GET_VALUE", 0));
    }

    @Test
    void getPackageFunctionSource_notFound() {
        assertNull(stateService.getPackageFunctionSource("HR", "NONEXISTENT", "func", 0),
                "Should return null for non-existent package");

        stateService.storePackageFunctions("HR", "TEST_PKG",
                Map.of("get_value", List.of("FUNCTION get_value() IS ...")), "");

        assertNull(stateService.getPackageFunctionSource("HR", "TEST_PKG", "nonexistent_func", 0),
                "Should return null for non-existent function");
    }

    @Test
    void clearPackageFunctionStorage_clearsAllMaps() {
        stateService.storePackageFunctions("HR", "PKG1", Map.of("func1", List.of("FUNCTION func1() IS ...")), "body1");
        stateService.storePackageFunctions("HR", "PKG2", Map.of("func2", List.of("FUNCTION func2() IS ...")), "body2");

        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG1", "func1", 0));
        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG2", "func2", 0));
        assertNotNull(stateService.getReducedPackageBody("HR", "PKG1"));

        stateService.clearPackageFunctionStorage();

        assertNull(stateService.getPackageFunctionSource("HR", "PKG1", "func1", 0));
        assertNull(stateService.getPackageFunctionSource("HR", "PKG2", "func2", 0));
        assertNull(stateService.getReducedPackageBody("HR", "PKG1"));
        assertNull(stateService.getReducedPackageBody("HR", "PKG2"));
    }

    @Test
    void resetState_clearsPackageFunctionStorage() {
        stateService.storePackageFunctions("HR", "PKG", Map.of("func", List.of("FUNCTION func() IS ...")), "body");

        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG", "func", 0));

        stateService.resetState();

        assertNull(stateService.getPackageFunctionSource("HR", "PKG", "func", 0));
        assertNull(stateService.getReducedPackageBody("HR", "PKG"));
    }

    @Test
    void storePackageFunctions_multiplePackages() {
        stateService.storePackageFunctions("HR", "PKG1", Map.of("func1", List.of("FUNCTION func1() IS ...")), "body1");
        stateService.storePackageFunctions("SALES", "PKG2", Map.of("func2", List.of("FUNCTION func2() IS ...")), "body2");

        assertNotNull(stateService.getPackageFunctionSource("HR", "PKG1", "func1", 0));
        assertNotNull(stateService.getPackageFunctionSource("SALES", "PKG2", "func2", 0));

        assertNull(stateService.getPackageFunctionSource("HR", "PKG1", "func2", 0));
        assertNull(stateService.getPackageFunctionSource("SALES", "PKG2", "func1", 0));
    }
}
