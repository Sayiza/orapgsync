package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Routines referenced without parentheses, which Oracle permits when no argument is required.
 *
 * <p>In expression position {@code pkg.func} is the same syntax as {@code table.column}, so before
 * this was handled the transformer emitted {@code pkg . func} and PostgreSQL — which reads
 * {@code a.b} as <i>column b of relation a</i> — failed with
 * {@code missing FROM-clause entry for table "pkg"}. The function looked like a table.
 *
 * <p>Covers all four shapes plus the negative cases that must keep behaving as column references.
 *
 * @see me.christianrobert.orapgsync.transformer.builder.generalelement.ParameterlessFunctionDetector
 */
class ParameterlessFunctionTransformationTest {

    private AntlrParser parser;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
    }

    // ========== The four shapes ==========

    @Test
    void packageRoutine_unqualifiedPackage_becomesFlattenedCall() {
        String result = transform("SELECT emp_pkg.get_status FROM emp",
                indices(Set.of("hr.emp_pkg.get_status"), Set.of(), Set.of("hr.emp_pkg.get_status")));

        assertEquals("SELECT hr.emp_pkg__get_status() FROM hr.emp", result);
    }

    @Test
    void packageRoutine_schemaQualified_becomesFlattenedCall() {
        String result = transform("SELECT hr.emp_pkg.get_status FROM emp",
                indices(Set.of("hr.emp_pkg.get_status"), Set.of(), Set.of("hr.emp_pkg.get_status")));

        assertEquals("SELECT hr.emp_pkg__get_status() FROM hr.emp", result);
    }

    @Test
    void standaloneRoutine_schemaQualified_becomesCall() {
        String result = transform("SELECT hr.get_today FROM emp",
                indices(Set.of(), Set.of("hr.get_today"), Set.of("hr.get_today")));

        assertEquals("SELECT hr.get_today() FROM hr.emp", result);
    }

    @Test
    void standaloneRoutine_unqualified_becomesCall() {
        String result = transform("SELECT get_today FROM emp",
                indices(Set.of(), Set.of("hr.get_today"), Set.of("hr.get_today")));

        assertEquals("SELECT hr.get_today() FROM hr.emp", result);
    }

    // ========== Negative cases: a column must stay a column ==========

    @Test
    void aliasedColumn_isNotRewritten() {
        // e is a registered alias, so e.get_status is a column reference whatever the metadata
        // says about a package of the same name.
        String result = transform("SELECT e.get_status FROM emp e",
                indices(Set.of("hr.e.get_status"), Set.of(), Set.of("hr.e.get_status")));

        assertEquals("SELECT e . get_status FROM hr.emp e", result);
    }

    @Test
    void columnOfUnaliasedTable_winsOverSameNamedFunction() {
        // The FROM relation carries no alias, so only the FROM-scope registration can establish
        // that ename is a real column. Without it this would silently become hr.ename().
        String result = transform("SELECT ename FROM emp",
                indices(Set.of(), Set.of("hr.ename"), Set.of("hr.ename")));

        assertEquals("SELECT ename FROM hr.emp", result);
    }

    @Test
    void routineRequiringArguments_isNotRewritten() {
        // Known package routine, but it takes a mandatory argument - so a bare reference cannot
        // have been a call, and must be left as the column reference it looks like.
        String result = transform("SELECT emp_pkg.get_status FROM emp",
                indices(Set.of("hr.emp_pkg.get_status"), Set.of(), Set.of()));

        assertEquals("SELECT emp_pkg . get_status FROM hr.emp", result);
    }

    @Test
    void unknownQualifiedName_isNotRewritten() {
        String result = transform("SELECT something.unknown FROM emp",
                indices(Set.of(), Set.of(), Set.of()));

        assertEquals("SELECT something . unknown FROM hr.emp", result);
    }

    @Test
    void unknownBareIdentifier_isNotRewritten() {
        String result = transform("SELECT some_column FROM emp",
                indices(Set.of(), Set.of(), Set.of()));

        assertEquals("SELECT some_column FROM hr.emp", result);
    }

    // ========== Related paths that must keep working ==========

    @Test
    void oracleCompatibilityPackage_isRoutedToCompatSchema() {
        // The documented Milestone B example. These packages carry no extracted metadata, so the
        // catalogued name is the evidence.
        String result = transform("SELECT dbms_utility.format_error_stack FROM emp",
                indices(Set.of(), Set.of(), Set.of()));

        assertEquals("SELECT oracle_compat.dbms_utility__format_error_stack() FROM hr.emp", result);
    }

    @Test
    void explicitEmptyParentheses_stillTransform() {
        String result = transform("SELECT emp_pkg.get_status() FROM emp",
                indices(Set.of("hr.emp_pkg.get_status"), Set.of(), Set.of("hr.emp_pkg.get_status")));

        assertEquals("SELECT hr.emp_pkg__get_status( ) FROM hr.emp", result);
    }

    @Test
    void quotedRoutineName_resolvesToLowerCaseMigratedName() {
        String result = transform("SELECT \"EMP_PKG\".\"GET_STATUS\" FROM emp",
                indices(Set.of("hr.emp_pkg.get_status"), Set.of(), Set.of("hr.emp_pkg.get_status")));

        assertEquals("SELECT hr.emp_pkg__get_status() FROM hr.emp", result);
    }

    @Test
    void bareRoutineInWhereClause_becomesCall() {
        String result = transform("SELECT ename FROM emp WHERE hiredate > get_today",
                indices(Set.of(), Set.of("hr.get_today"), Set.of("hr.get_today")));

        assertEquals("SELECT ename FROM hr.emp WHERE hiredate > hr.get_today()", result);
    }

    // ========== Helpers ==========

    private String transform(String oracleSql, TransformationIndices indices) {
        TransformationContext context =
                new TransformationContext("hr", indices, new SimpleTypeEvaluator("hr", indices));
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed for: " + oracleSql);

        return builder.visit(parseResult.getTree()).trim().replaceAll("\\s+", " ");
    }

    /**
     * Builds indices around a single table {@code hr.emp} plus the given routine metadata.
     */
    private TransformationIndices indices(
            Set<String> packageFunctions,
            Set<String> standaloneFunctions,
            Set<String> noArgCallable) {

        Map<String, TransformationIndices.ColumnTypeInfo> empColumns = new HashMap<>();
        empColumns.put("ename", new TransformationIndices.ColumnTypeInfo("VARCHAR2", null));
        empColumns.put("hiredate", new TransformationIndices.ColumnTypeInfo("DATE", null));
        empColumns.put("get_status", new TransformationIndices.ColumnTypeInfo("VARCHAR2", null));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("hr.emp", empColumns);

        return new TransformationIndices(
                tableColumns,
                Collections.emptyMap(),
                packageFunctions,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                standaloneFunctions,
                noArgCallable);
    }
}
