package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Package variable helper generation: type resolution, and the refusal to build helpers out of
 * a package body that did not parse.
 *
 * <p>Both defects blocked functions in bulk on the 2026-08-30 run rather than failing one object.
 * A package context is shared setup inside a per-item loop, so a single broken helper is recorded
 * against every member of the package: {@code PackageHelperGenerator} calling
 * {@code TypeConverter} directly cost 29,705 blocked functions, and building helpers from an
 * error-recovery tree cost a further 14,781.
 */
class PackageHelperTypeResolutionTest {

    private final PackageHelperGenerator generator = new PackageHelperGenerator();

    private static TransformationIndices indices() {
        return new TransformationIndices(
                Map.of(), Map.of(), Set.of(),
                Map.of("public", Map.of("propmap", "main_app.propmap")),
                Map.of(),
                Set.of("main_app.propmap"));
    }

    private static TransformationContext contextFor(String schema, String pkg, PackageContext ctx) {
        Map<String, PackageContext> cache = new HashMap<>();
        if (ctx != null) {
            cache.put(ctx.getCacheKey(), ctx);
        }
        TransformationIndices indices = indices();
        return new TransformationContext(schema, indices, new SimpleTypeEvaluator(schema, indices),
                cache, null, pkg);
    }

    private static PackageContext packageWith(String varName, String oracleType) {
        PackageContext ctx = new PackageContext("main_app", "courseutil");
        ctx.addVariable(new PackageContext.PackageVariable(varName, oracleType, null, false));
        return ctx;
    }

    private static String getterOf(List<String> statements, String varName) {
        return statements.stream()
                .filter(s -> s.contains("__get_" + varName + "()"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no getter generated for " + varName));
    }

    // ========== Type resolution ==========

    @Test
    @DisplayName("A migrated object type is named the way the DDL migration created it")
    void objectTypeVariableIsSchemaQualified() {
        PackageContext pkg = packageWith("gfaultmap", "PROPMAP");

        List<String> sql = generator.generateHelperSql(
                pkg, contextFor("main_app", "courseutil", pkg));

        String getter = getterOf(sql, "gfaultmap");
        assertTrue(getter.contains("RETURNS main_app.propmap"), getter);
        assertFalse(getter.contains("RETURNS propmap\n"),
                "an unqualified name resolves only if search_path happens to cover the schema, "
                        + "which during migration it does not: " + getter);
    }

    @Test
    @DisplayName("Built-in types are unaffected")
    void builtInVariableStillMaps() {
        PackageContext pkg = packageWith("gcounter", "NUMBER");

        List<String> sql = generator.generateHelperSql(
                pkg, contextFor("main_app", "courseutil", pkg));

        assertTrue(getterOf(sql, "gcounter").contains("RETURNS numeric"));
    }

    @Test
    @DisplayName("A package SUBTYPE resolves to its base type, not to jsonb")
    void subtypeVariableResolvesToBaseType() {
        PackageContext pkg = new PackageContext("main_app", "courseutil");
        pkg.addSubtype("string", "VARCHAR2(4000)");
        pkg.addVariable(new PackageContext.PackageVariable("gname", "courseutil.string", null, false));

        List<String> sql = generator.generateHelperSql(
                pkg, contextFor("main_app", "courseutil", pkg));

        assertTrue(getterOf(sql, "gname").contains("RETURNS text"), getterOf(sql, "gname"));
    }

    @Test
    @DisplayName("An unplaceable type refuses the package instead of emitting the bare name")
    void unplaceableVariableTypeIsRefused() {
        PackageContext pkg = packageWith("gcomp", "uicomponent.component");

        TransformationException e = assertThrows(TransformationException.class,
                () -> generator.generateHelperSql(pkg, contextFor("main_app", "courseutil", pkg)));

        assertTrue(e.getMessage().contains("uicomponent.component"), e.getMessage());
        assertTrue(e.getMessage().contains("gcomp"),
                "the message must name the variable, was: " + e.getMessage());
    }

    @Test
    @DisplayName("An inline RECORD is still decided at the declaration site, not by the resolver")
    void inlineRecordVariableStillBecomesJsonb() {
        PackageContext pkg = new PackageContext("main_app", "courseutil");
        pkg.addVariable(new PackageContext.PackageVariable("grec", "rec_t", null, false));
        pkg.addType(new InlineTypeDefinition(
                "rec_t", TypeCategory.RECORD, null,
                List.of(new FieldDefinition("a", "NUMBER", "numeric")),
                ConversionStrategy.JSONB, null));

        List<String> sql = generator.generateHelperSql(
                pkg, contextFor("main_app", "courseutil", pkg));

        assertTrue(getterOf(sql, "grec").contains("RETURNS jsonb"));
    }

}
