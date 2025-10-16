package me.christianrobert.orapgsync.transformation.semantic.statement;

import me.christianrobert.orapgsync.transformation.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;
import me.christianrobert.orapgsync.transformation.semantic.query.*;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectOnlyStatement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SelectStatement semantic node.
 */
class SelectStatementTest {

    private final TransformationContext context = new TransformationContext("test_schema", MetadataIndexBuilder.buildEmpty());

    @Test
    void simpleSingleColumnSelect() {
        SelectStatement select = buildSelect(
                Collections.singletonList("empno"),
                "emp",
                null
        );

        String result = select.toPostgres(context);

        assertEquals("SELECT empno FROM emp", result);
    }

    @Test
    void simpleMultiColumnSelect() {
        SelectStatement select = buildSelect(
                Arrays.asList("empno", "ename"),
                "emp",
                null
        );

        String result = select.toPostgres(context);

        assertEquals("SELECT empno, ename FROM emp", result);
    }

    @Test
    void selectThreeColumns() {
        SelectStatement select = buildSelect(
                Arrays.asList("empno", "ename", "sal"),
                "emp",
                null
        );

        String result = select.toPostgres(context);

        assertEquals("SELECT empno, ename, sal FROM emp", result);
    }

    @Test
    void selectWithTableAlias() {
        SelectStatement select = buildSelect(
                Collections.singletonList("empno"),
                "employees",
                "e"
        );

        String result = select.toPostgres(context);

        assertEquals("SELECT empno FROM employees e", result);
    }

    @Test
    void nullSelectOnlyStatementThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new SelectStatement(null));
    }

    @Test
    void gettersReturnCorrectValues() {
        SelectStatement select = buildSelect(
                Arrays.asList("empno", "ename"),
                "emp",
                null
        );

        assertNotNull(select.getSelectOnlyStatement());
        assertNotNull(select.getSelectOnlyStatement().getSubquery());
        assertNotNull(select.getSelectOnlyStatement().getSubquery().getBasicElements());
        assertNotNull(select.getSelectOnlyStatement().getSubquery().getBasicElements().getQueryBlock());
        assertEquals(2, select.getSelectOnlyStatement().getSubquery().getBasicElements().getQueryBlock().getSelectedList().getElements().size());
        assertEquals("emp", select.getSelectOnlyStatement().getSubquery().getBasicElements().getQueryBlock().getFromClause().getTableReferences().get(0).getTableName());
    }

    @Test
    void toStringIncludesStructureInfo() {
        SelectStatement select = buildSelect(
                Arrays.asList("empno", "ename"),
                "emp",
                null
        );

        String str = select.toString();

        assertTrue(str.contains("SelectStatement"));
        assertTrue(str.contains("SelectOnlyStatement"));
    }

    // ========== Helper Methods ==========

    /**
     * Helper to build a SelectStatement for testing.
     */
    private SelectStatement buildSelect(java.util.List<String> columnNames, String tableName, String tableAlias) {
        // Build SELECT list elements
        java.util.List<SelectListElement> elements = new java.util.ArrayList<>();
        for (String colName : columnNames) {
            elements.add(new SelectListElement(new Identifier(colName)));
        }
        SelectedList selectedList = new SelectedList(elements);

        // Build FROM clause
        TableReference tableRef = new TableReference(tableName, tableAlias);
        FromClause fromClause = new FromClause(tableRef);

        // Build QueryBlock
        QueryBlock queryBlock = new QueryBlock(selectedList, fromClause);

        // Build SubqueryBasicElements
        SubqueryBasicElements basicElements = new SubqueryBasicElements(queryBlock);

        // Build Subquery
        Subquery subquery = new Subquery(basicElements);

        // Build SelectOnlyStatement
        SelectOnlyStatement selectOnlyStatement = new SelectOnlyStatement(subquery);

        // Build SelectStatement
        return new SelectStatement(selectOnlyStatement);
    }
}
