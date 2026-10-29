package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Type emission in the function stub job.
 *
 * <p>The job used to build types itself — {@code TypeConverter.toPostgre()} for built-ins and a
 * raw {@code owner + "." + typeName} for everything else — which is the "new type-emission site
 * calls TypeResolver, not TypeConverter" rule broken in a DDL job rather than in the visitor
 * layer. On the 2026-08-30 full run that produced 28,362 of 74,428 stub failures, 17,339 of them
 * type-name resolution.
 *
 * <p>These tests drive the real {@code generateCreateFunctionStubSQL} against hand-built metadata,
 * so restoring any of the direct calls fails them.
 */
class PostgresFunctionStubTypeResolutionTest {

    private PostgresFunctionStubCreationJob job;

    @BeforeEach
    void setUp() {
        job = new PostgresFunctionStubCreationJob();
        job.useIndices(indices());
    }

    /**
     * A miniature of the real corpus: one migrated object type owned by {@code common_types}, a public
     * synonym pointing at it, one table with a typed column, and two packages declaring types —
     * a subtype and a record, the two shapes step 22 indexes.
     */
    private static TransformationIndices indices() {
        PackageTypeMetadata typePkg = new PackageTypeMetadata("lending", "kindlib");
        typePkg.addSubtype("label", "VARCHAR2(4000)");

        PackageTypeMetadata componentPkg = new PackageTypeMetadata("lending", "uicomponent");
        componentPkg.addStructuredType("component");

        return new TransformationIndices(
                Map.of("academic.semester_def", Map.of(
                        "nr", new TransformationIndices.ColumnTypeInfo("NUMBER", null))),
                Map.of(),
                Set.of(),
                Map.of("public", Map.of("numberlist", "common_types.numberlist")),
                Map.of(),
                Set.of("common_types.numberlist", "common_types.i18ntext"),
                Set.of(),
                Set.of(),
                Map.of(typePkg.getQualifiedName(), typePkg,
                        componentPkg.getQualifiedName(), componentPkg));
    }

    private static FunctionMetadata function(String schema, String name) {
        FunctionMetadata f = new FunctionMetadata(schema, name, "FUNCTION");
        f.setReturnDataType("NUMBER");
        return f;
    }

    private static FunctionParameter param(String name, String dataType) {
        return new FunctionParameter(name, 1, dataType, "IN");
    }

    private static FunctionParameter customParam(String name, String owner, String typeName) {
        FunctionParameter p = new FunctionParameter(name, 1, "OBJECT", "IN");
        p.setCustomDataType(true);
        p.setDataTypeOwner(owner);
        p.setDataTypeName(typeName);
        return p;
    }

    // ========== PL/SQL BOOLEAN ==========

    @Test
    @DisplayName("A package member's BOOLEAN return type does not reach PostgreSQL as 'pl/sql boolean'")
    void plsqlBooleanReturnTypeIsMapped() {
        FunctionMetadata f = function("hr", "is_active");
        f.setReturnDataType("PL/SQL BOOLEAN");

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("RETURNS boolean"), sql);
        assertFalse(sql.contains("pl/sql"),
                "the slash is what PostgreSQL rejected: " + sql);
    }

    // ========== PUBLIC synonyms ==========

    @Test
    @DisplayName("A type owned by PUBLIC is a synonym, not the public schema")
    void publicOwnedTypeResolvesThroughSynonyms() {
        FunctionMetadata f = function("admissions", "get_nrs");
        f.setCustomReturnType(true);
        f.setReturnTypeOwner("public");
        f.setReturnTypeName("numberlist");

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("RETURNS common_types.numberlist"), sql);
        assertFalse(sql.contains("public.numberlist"),
                "PUBLIC is Oracle's synonym namespace; taken literally it names a PostgreSQL "
                        + "schema the type is not in: " + sql);
    }

    // ========== %TYPE anchors ==========

    @Test
    @DisplayName("A %TYPE anchor keeps its anchor and gains the table's schema")
    void anchoredTypeIsQualifiedNotFlattened() {
        FunctionMetadata f = function("academic", "check_semester");
        f.addParameter(param("ptermdef", "semester_def.nr%TYPE"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("ptermdef academic.semester_def.nr%TYPE"), sql);
    }

    /** The private-member extractor upper-cases what it reads off the parse tree (P1.4). */
    @Test
    @DisplayName("An upper-case %TYPE anchor and an anchored return type are qualified the same way")
    void upperCaseAnchoredTypeIsQualified() {
        FunctionMetadata f = function("academic", "current_semester");
        f.setReturnDataType("SEMESTER_DEF.NR%TYPE");
        f.addParameter(param("ptermdef", "SEMESTER_DEF.NR%TYPE"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("ptermdef academic.semester_def.nr%TYPE"), sql);
        assertTrue(sql.contains("RETURNS academic.semester_def.nr%TYPE"), sql);
    }

    /** PostgreSQL does not accept %ROWTYPE in a signature; see TypeResolver. */
    @Test
    @DisplayName("A %ROWTYPE parameter is refused by name")
    void rowtypeParameterIsRefusedByName() {
        FunctionMetadata f = function("academic", "check_row");
        f.addParameter(param("prow", "SEMESTER_DEF%ROWTYPE"));

        PostgresFunctionStubCreationJob.TypeResolutionException e = assertThrows(
                PostgresFunctionStubCreationJob.TypeResolutionException.class,
                () -> job.generateCreateFunctionStubSQL(f));

        assertTrue(e.getMessage().contains("SEMESTER_DEF%ROWTYPE"), e.getMessage());
    }

    @Test
    @DisplayName("An anchor on an unknown table is refused, not emitted bare")
    void anchoredTypeOnUnknownTableIsRefused() {
        FunctionMetadata f = function("academic", "check_thing");
        f.addParameter(param("pthing", "no_such_table.nr%TYPE"));

        PostgresFunctionStubCreationJob.TypeResolutionException e = assertThrows(
                PostgresFunctionStubCreationJob.TypeResolutionException.class,
                () -> job.generateCreateFunctionStubSQL(f));

        assertTrue(e.getMessage().contains("no_such_table.nr%TYPE"),
                "the message must name the type that could not be placed, was: " + e.getMessage());
    }

    // ========== Object types ==========

    @Test
    @DisplayName("A migrated object type is emitted schema-qualified")
    void objectTypeIsQualified() {
        FunctionMetadata f = function("academic", "get_text");
        f.addParameter(customParam("ptext", "common_types", "i18ntext"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("ptext common_types.i18ntext"), sql);
    }

    @Test
    @DisplayName("An unqualified object type name is placed from metadata")
    void bareObjectTypeNameIsQualifiedFromSynonyms() {
        FunctionMetadata f = function("academic", "get_nrs");
        f.addParameter(param("pnrs", "NUMBERLIST"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("pnrs common_types.numberlist"), sql);
    }

    // ========== Refusal ==========

    @Test
    @DisplayName("An unplaceable type refuses the stub instead of emitting the bare Oracle name")
    void unplaceableTypeIsRefused() {
        FunctionMetadata f = function("lending", "get_icon");
        f.setCustomReturnType(true);
        f.setReturnTypeOwner("uicomponent");
        f.setReturnTypeName("icon");

        PostgresFunctionStubCreationJob.TypeResolutionException e = assertThrows(
                PostgresFunctionStubCreationJob.TypeResolutionException.class,
                () -> job.generateCreateFunctionStubSQL(f));

        assertTrue(e.getMessage().contains("uicomponent.icon"), e.getMessage());
        assertTrue(e.getMessage().contains("return type"),
                "the message must say where in the signature it failed, was: " + e.getMessage());
    }

    // ========== Package-declared types, from the step 22 index ==========
    //
    // This job holds no PackageContext and never will — it builds signatures, not bodies. Before
    // the index existed, every one of these was unplaceable and the stub was refused.

    @Test
    @DisplayName("Another package's SUBTYPE resolves to its base type")
    void packageSubtypeResolvesFromTheIndex() {
        FunctionMetadata f = function("lending", "get_label");
        f.addParameter(param("plabel", "kindlib.label"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("plabel text"), sql);
    }

    @Test
    @DisplayName("Another package's RECORD resolves to jsonb")
    void packageRecordResolvesFromTheIndex() {
        FunctionMetadata f = function("lending", "render");
        f.addParameter(param("pcomp", "uicomponent.component"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("pcomp jsonb"), sql);
    }

    @Test
    @DisplayName("A type the indexed package does not declare is still refused")
    void undeclaredTypeOfAKnownPackageIsStillRefused() {
        FunctionMetadata f = function("lending", "get_icon");
        f.addParameter(param("picon", "uicomponent.icon"));

        PostgresFunctionStubCreationJob.TypeResolutionException e = assertThrows(
                PostgresFunctionStubCreationJob.TypeResolutionException.class,
                () -> job.generateCreateFunctionStubSQL(f));

        assertTrue(e.getMessage().contains("uicomponent.icon"),
                "knowing the package must not become a licence to guess the type: " + e.getMessage());
    }

    @Test
    @DisplayName("Built-ins are untouched by any of this")
    void ordinaryBuiltInsStillMap() {
        FunctionMetadata f = function("hr", "get_salary");
        f.addParameter(param("pempno", "NUMBER"));

        String sql = job.generateCreateFunctionStubSQL(f);

        assertTrue(sql.contains("pempno numeric"), sql);
        assertTrue(sql.contains("RETURNS numeric"), sql);
    }
}
