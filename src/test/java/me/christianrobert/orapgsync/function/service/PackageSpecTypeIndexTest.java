package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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
        assertTrue(index("kindlib", "  SUBTYPE label IS VARCHAR2(4000);\n  SUBTYPE flag IS VARCHAR2(1);"));

        PackageTypeMetadata types = stateService.getPackageTypes("MAIN_APP", "kindlib");
        assertNotNull(types);
        assertEquals("VARCHAR2(4000)", types.getSubtypes().get("label"));
        assertEquals("VARCHAR2(1)", types.getSubtypes().get("flag"));
    }

    /**
     * ROADMAP P1.3. The grammar took a length only as a literal or a bare name, so a spec sizing its
     * subtypes by another package's constant did not parse at all — and the package that does this
     * is typically the one defining the most widely used subtypes, so every {@code pkg.subtype}
     * reference into it was unplaceable.
     */
    @Test
    @DisplayName("A subtype sized by a package-qualified constant is indexed and resolves to its base type")
    void subtypeWithConstantLengthIsIndexed() {
        assertTrue(index("units", """
                  TEXT_CAP constant pls_integer := Size_Limits.TEXT_CAP;
                  subtype Label is varchar2(Size_Limits.TEXT_CAP CHAR);
                  subtype Tag   is varchar2(main_app.Size_Limits.TAG_LEN);
                  subtype Price is number(Size_Limits.PRICE_DIGITS, 2);
                  type LabelList is table of Label index by pls_integer;"""),
                "the spec must parse");

        PackageTypeMetadata types = stateService.getPackageTypes("MAIN_APP", "units");
        assertNotNull(types);
        assertEquals(Set.of("label", "tag", "price"), types.getSubtypes().keySet());

        TransformationIndices indices = new TransformationIndices(
                Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(),
                Map.of(types.getQualifiedName(), types));
        TransformationContext context = new TransformationContext(
                "main_app", indices, new SimpleTypeEvaluator("main_app", indices));

        assertEquals("text", TypeResolver.toPostgresOrNull("Units.Label", context));
        assertEquals("text", TypeResolver.toPostgresOrNull("units.tag", context));
        assertEquals("numeric", TypeResolver.toPostgresOrNull("units.price", context));
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
        index("Kindlib", "  SUBTYPE label IS VARCHAR2(10);");

        assertNotNull(stateService.getPackageTypes("main_app", "kindlib"));
        assertNotNull(stateService.getPackageTypes("MAIN_APP", "KINDLIB"));
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
