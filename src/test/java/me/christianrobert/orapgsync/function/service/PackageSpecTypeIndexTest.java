package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extraction of the types each package specification declares.
 *
 * <p>Built at step 22 so that every later job places a {@code pkg.type} reference the same way.
 * Before it existed the function stub job had no package context at all — so no package type could
 * be placed in any signature — and the implementation job built a context only for the package it
 * was currently transforming, which made the answer for another package's type depend on which
 * packages had been processed first.
 */
class PackageSpecTypeIndexTest {

    private StateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new StateService();
    }

    /** ALL_SOURCE holds the spec without CREATE OR REPLACE, exactly as passed here. */
    private boolean index(String packageName, String specBody) {
        return OracleFunctionExtractor.indexPackageSpecTypes(
                "MAIN_APP", packageName, "PACKAGE " + packageName + " AS\n" + specBody + "\nEND "
                        + packageName + ";", stateService);
    }

    @Test
    @DisplayName("Subtypes are indexed with the Oracle type they alias")
    void subtypesAreIndexed() {
        assertTrue(index("type", "  SUBTYPE string IS VARCHAR2(4000);\n  SUBTYPE flag IS VARCHAR2(1);"));

        PackageTypeMetadata types = stateService.getPackageTypes("MAIN_APP", "type");
        assertNotNull(types);
        assertEquals("VARCHAR2(4000)", types.getSubtypes().get("string"));
        assertEquals("VARCHAR2(1)", types.getSubtypes().get("flag"));
    }

    @Test
    @DisplayName("Structured types are indexed by name only")
    void structuredTypesAreIndexedByName() {
        assertTrue(index("uicomponent", """
                  TYPE component IS RECORD ( id NUMBER, label VARCHAR2(100) );
                  TYPE components IS TABLE OF NUMBER;
                """));

        PackageTypeMetadata types = stateService.getPackageTypes("MAIN_APP", "uicomponent");
        assertNotNull(types);
        assertTrue(types.getStructuredTypes().contains("component"));
        assertTrue(types.getStructuredTypes().contains("components"));
        assertTrue(types.getSubtypes().isEmpty(),
                "a RECORD is not an alias and must not land in the subtype map");
    }

    @Test
    @DisplayName("Lookup is case-insensitive on both schema and package")
    void lookupIsCaseInsensitive() {
        index("Type", "  SUBTYPE string IS VARCHAR2(10);");

        assertNotNull(stateService.getPackageTypes("main_app", "type"));
        assertNotNull(stateService.getPackageTypes("MAIN_APP", "TYPE"));
    }

    @Test
    @DisplayName("A package declaring no types is not stored, so a lookup fails rather than "
            + "returning an empty answer")
    void packageWithoutTypesIsNotStored() {
        assertTrue(index("plain", "  FUNCTION f RETURN NUMBER;"));

        assertNull(stateService.getPackageTypes("MAIN_APP", "plain"));
        assertTrue(stateService.getAllPackageTypes().isEmpty());
    }

    @Test
    @DisplayName("A spec that does not parse is skipped, not fatal")
    void unparseableSpecIsSkipped() {
        boolean parsed = OracleFunctionExtractor.indexPackageSpecTypes(
                "MAIN_APP", "lecture_util", "PACKAGE lecture_util wrapped\na000000\nb2\n", stateService);

        assertFalse(parsed, "the wrapped package cannot be parsed");
        assertNull(stateService.getPackageTypes("MAIN_APP", "lecture_util"));
        // The point: extraction continues. A consumer that needed one of these types still reports
        // it by name, which is strictly better than taking the whole function job down here.
    }

    @Test
    @DisplayName("Nothing is stored for an empty spec")
    void emptySpecStoresNothing() {
        assertTrue(OracleFunctionExtractor.indexPackageSpecTypes(
                "MAIN_APP", "empty", "   ", stateService));

        assertTrue(stateService.getAllPackageTypes().isEmpty());
    }
}
