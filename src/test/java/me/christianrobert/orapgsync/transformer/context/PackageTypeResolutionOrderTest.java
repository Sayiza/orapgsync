package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code pkg.type} resolves to the same thing whatever else happens to be loaded.
 *
 * <p>The implementation job builds a {@link PackageContext} for the package it is currently
 * transforming and caches it, so before the step 22 index existed, whether {@code kindlib.label}
 * resolved to {@code text} or fell back to {@code jsonb} depended on whether package {@code type}
 * had already been processed in that schema — an answer that changed with processing order and
 * could not be reproduced from the report. These tests drive the same question through three
 * different context populations and require one answer.
 */
class PackageTypeResolutionOrderTest {

    private static TransformationIndices indicesWithPackageTypes() {
        PackageTypeMetadata typePkg = new PackageTypeMetadata("hr", "kindlib");
        typePkg.addSubtype("label", "VARCHAR2(4000)");
        typePkg.addSubtype("short_label", "label");
        typePkg.addStructuredType("row_list");

        return new TransformationIndices(
                Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(),
                Map.of(typePkg.getQualifiedName(), typePkg));
    }

    /** @param loaded package contexts the job happens to have built by now */
    private static TransformationContext contextWith(PackageContext... loaded) {
        TransformationIndices indices = indicesWithPackageTypes();
        Map<String, PackageContext> cache = new HashMap<>();
        for (PackageContext ctx : loaded) {
            cache.put(ctx.getCacheKey(), ctx);
        }
        return new TransformationContext("hr", indices,
                new SimpleTypeEvaluator("hr", indices), cache, null, "caller_pkg");
    }

    private static PackageContext otherPackage(String name) {
        PackageContext ctx = new PackageContext("hr", name);
        ctx.addSubtype("unrelated", "NUMBER");
        return ctx;
    }

    @Test
    @DisplayName("Resolves with no package context loaded at all — the stub job's situation")
    void resolvesWithNoContextLoaded() {
        assertEquals("text", TypeResolver.toPostgres("kindlib.label", contextWith()));
    }

    @Test
    @DisplayName("Resolves the same when an unrelated package has been processed first")
    void resolvesTheSameWithUnrelatedContextLoaded() {
        assertEquals("text",
                TypeResolver.toPostgres("kindlib.label", contextWith(otherPackage("some_other_pkg"))));
    }

    @Test
    @DisplayName("Resolves the same when the declaring package's own context is loaded")
    void resolvesTheSameWithTheDeclaringContextLoaded() {
        PackageContext typePkg = new PackageContext("hr", "kindlib");
        typePkg.addSubtype("label", "VARCHAR2(4000)");

        assertEquals("text", TypeResolver.toPostgres("kindlib.label", contextWith(typePkg)));
    }

    @Test
    @DisplayName("A subtype chain resolves through the index")
    void subtypeChainResolvesThroughTheIndex() {
        assertEquals("text", TypeResolver.toPostgres("kindlib.short_label", contextWith()));
    }

    @Test
    @DisplayName("An indexed structured type is jsonb")
    void structuredTypeResolvesToJsonb() {
        assertEquals("jsonb", TypeResolver.toPostgres("kindlib.row_list", contextWith()));
    }

    @Test
    @DisplayName("An indexed type is placed by metadata, so a signature may use it")
    void indexedTypeCountsAsPlaced() {
        assertEquals("text", TypeResolver.toPostgresOrNull("kindlib.label", contextWith()),
                "toPostgresOrNull is what signature-building callers use; an indexed type must "
                        + "not read as a guess");
    }

    @Test
    @DisplayName("An undeclared type of a known package is still a guess, and refusable")
    void undeclaredTypeIsStillAGuess() {
        assertNull(TypeResolver.toPostgresOrNull("kindlib.never_declared", contextWith()),
                "knowing the package must not become a licence to guess the type");
        assertEquals("jsonb", TypeResolver.toPostgres("kindlib.never_declared", contextWith()),
                "the visitor layer still gets an answer, because it has nowhere to put a failure");
    }

    @Test
    @DisplayName("An entirely unknown package is still a guess")
    void unknownPackageIsStillAGuess() {
        assertNull(TypeResolver.toPostgresOrNull("no_such_pkg.thing", contextWith()));
    }
}
