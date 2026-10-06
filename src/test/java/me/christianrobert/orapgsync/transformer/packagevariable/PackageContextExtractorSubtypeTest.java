package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Package-level {@code SUBTYPE} extraction and resolution.
 *
 * <p>A subtype is an alias for another type, and the grammar has carried
 * {@code subtype_declaration} in {@code package_obj_spec} all along — but the extractor read only
 * variables and TYPE declarations, so every {@code pkg.subtype} reference fell through
 * {@code resolvePackageQualifiedType} to its "not found, default to jsonb" branch. {@code jsonb}
 * for what is really {@code VARCHAR2} is a different type with a different set of operators, and
 * it was answered identically by the transformer and by the stub job, so the two agreed on the
 * wrong type rather than disagreeing on the right one.
 */
class PackageContextExtractorSubtypeTest {

    private PackageContextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PackageContextExtractor(new AntlrParser());
    }

    private PackageContext extract(String declarations) {
        String spec = "CREATE OR REPLACE PACKAGE type AS\n" + declarations + "\nEND type;\n";
        return extractor.extractContext("HR", "type", spec);
    }

    // ========== Extraction ==========

    @Test
    void extractsScalarSubtype() {
        PackageContext ctx = extract("  SUBTYPE string IS VARCHAR2(4000);");

        assertTrue(ctx.hasSubtype("string"));
        assertEquals("VARCHAR2(4000)", ctx.getSubtypeBaseType("string"));
    }

    @Test
    void subtypeLookupIsCaseInsensitive() {
        PackageContext ctx = extract("  SUBTYPE Flag IS VARCHAR2(1);");

        assertTrue(ctx.hasSubtype("FLAG"));
        assertTrue(ctx.hasSubtype("flag"));
    }

    @Test
    void subtypesAreNotInlineTypes() {
        PackageContext ctx = extract("  SUBTYPE string IS VARCHAR2(4000);");

        assertFalse(ctx.hasType("string"),
                "A subtype is an alias, not a structure; keeping it out of the inline-type map is "
                        + "what stops it being answered with jsonb");
        assertEquals(1, ctx.getSubtypes().size());
        assertEquals(0, ctx.getTypes().size());
    }

    @Test
    void extractsSubtypeAlongsideVariablesAndTypes() {
        PackageContext ctx = extract("""
                  SUBTYPE string IS VARCHAR2(4000);
                  TYPE rec_t IS RECORD ( a NUMBER, b VARCHAR2(10) );
                  gCounter NUMBER := 0;
                """);

        assertTrue(ctx.hasSubtype("string"));
        assertTrue(ctx.hasType("rec_t"));
        assertTrue(ctx.hasVariable("gCounter"));
    }

    @Test
    void extractsRangeConstrainedSubtypeDroppingTheRange() {
        PackageContext ctx = extract("  SUBTYPE small_int IS PLS_INTEGER RANGE 1 .. 10;");

        assertTrue(ctx.hasSubtype("small_int"));
        assertEquals("PLS_INTEGER", ctx.getSubtypeBaseType("small_int"),
                "PostgreSQL has nowhere to put a RANGE on a signature, so only the base type is kept");
    }

    // ========== Resolution through TypeResolver ==========

    private TransformationContext contextWith(PackageContext pkg) {
        TransformationIndices indices = MetadataIndexBuilder.buildEmpty();
        Map<String, PackageContext> cache = new HashMap<>();
        cache.put(pkg.getCacheKey(), pkg);
        return new TransformationContext("hr", indices,
                new SimpleTypeEvaluator("hr", indices), cache, null, pkg.getPackageName());
    }

    @Test
    void scalarSubtypeResolvesToItsBaseTypeNotJsonb() {
        PackageContext pkg = extract("  SUBTYPE string IS VARCHAR2(4000);");

        assertEquals("text", TypeResolver.toPostgres("type.string", contextWith(pkg)),
                "type.string is VARCHAR2, so it must reach PostgreSQL as text");
    }

    @Test
    void numericSubtypeResolvesToNumeric() {
        PackageContext pkg = extract("  SUBTYPE nr IS NUMBER(10);");

        assertEquals("numeric", TypeResolver.toPostgres("type.nr", contextWith(pkg)));
    }

    @Test
    void subtypeChainResolvesThroughToTheBuiltIn() {
        PackageContext pkg = extract("""
                  SUBTYPE string IS VARCHAR2(4000);
                  SUBTYPE short_string IS string;
                """);

        assertEquals("text", TypeResolver.toPostgres("type.short_string", contextWith(pkg)),
                "An alias of an alias resolves one hop at a time");
    }

    @Test
    void subtypeOfARecordStillResolvesToJsonb() {
        PackageContext pkg = extract("""
                  TYPE rec_t IS RECORD ( a NUMBER );
                  SUBTYPE rec_alias IS rec_t;
                """);

        assertEquals("jsonb", TypeResolver.toPostgres("type.rec_alias", contextWith(pkg)),
                "A subtype naming a structure is still a structure");
    }

    @Test
    void unknownPackageTypeStillFallsBackToJsonb() {
        PackageContext pkg = extract("  SUBTYPE string IS VARCHAR2(4000);");

        assertEquals("jsonb", TypeResolver.toPostgres("type.never_declared", contextWith(pkg)),
                "Subtype extraction must not change the answer for types it does not cover");
    }
}
