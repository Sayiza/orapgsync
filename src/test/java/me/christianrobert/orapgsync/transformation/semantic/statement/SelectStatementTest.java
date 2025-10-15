package me.christianrobert.orapgsync.transformation.semantic.statement;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SelectStatement semantic node.
 */
class SelectStatementTest {

    private final TransformationContext context = new TransformationContext("test_schema");

    @Test
    void simpleSingleColumnSelect() {
        List<Identifier> columns = Collections.singletonList(new Identifier("empno"));
        TableReference table = new TableReference("emp");

        SelectStatement select = new SelectStatement(columns, table);
        String result = select.toPostgres(context);

        assertEquals("SELECT empno FROM emp", result);
    }

    @Test
    void simpleMultiColumnSelect() {
        List<Identifier> columns = Arrays.asList(
                new Identifier("empno"),
                new Identifier("ename")
        );
        TableReference table = new TableReference("emp");

        SelectStatement select = new SelectStatement(columns, table);
        String result = select.toPostgres(context);

        assertEquals("SELECT empno, ename FROM emp", result);
    }

    @Test
    void selectThreeColumns() {
        List<Identifier> columns = Arrays.asList(
                new Identifier("empno"),
                new Identifier("ename"),
                new Identifier("sal")
        );
        TableReference table = new TableReference("emp");

        SelectStatement select = new SelectStatement(columns, table);
        String result = select.toPostgres(context);

        assertEquals("SELECT empno, ename, sal FROM emp", result);
    }

    @Test
    void selectWithTableAlias() {
        List<Identifier> columns = Collections.singletonList(new Identifier("empno"));
        TableReference table = new TableReference("employees", "e");

        SelectStatement select = new SelectStatement(columns, table);
        String result = select.toPostgres(context);

        assertEquals("SELECT empno FROM employees e", result);
    }

    @Test
    void nullColumnsThrowsException() {
        TableReference table = new TableReference("emp");
        assertThrows(IllegalArgumentException.class, () -> new SelectStatement(null, table));
    }

    @Test
    void emptyColumnsThrowsException() {
        TableReference table = new TableReference("emp");
        assertThrows(IllegalArgumentException.class, () -> new SelectStatement(Collections.emptyList(), table));
    }

    @Test
    void nullTableThrowsException() {
        List<Identifier> columns = Collections.singletonList(new Identifier("empno"));
        assertThrows(IllegalArgumentException.class, () -> new SelectStatement(columns, null));
    }

    @Test
    void gettersReturnCorrectValues() {
        List<Identifier> columns = Arrays.asList(
                new Identifier("empno"),
                new Identifier("ename")
        );
        TableReference table = new TableReference("emp");

        SelectStatement select = new SelectStatement(columns, table);

        assertEquals(2, select.getSelectColumns().size());
        assertEquals("empno", select.getSelectColumns().get(0).getName());
        assertEquals("ename", select.getSelectColumns().get(1).getName());
        assertEquals("emp", select.getFromTable().getTableName());
    }

    @Test
    void gettersReturnUnmodifiableList() {
        List<Identifier> columns = Collections.singletonList(new Identifier("empno"));
        TableReference table = new TableReference("emp");

        SelectStatement select = new SelectStatement(columns, table);
        List<Identifier> returned = select.getSelectColumns();

        assertThrows(UnsupportedOperationException.class, () -> returned.add(new Identifier("test")));
    }

    @Test
    void toStringIncludesColumnAndTableInfo() {
        List<Identifier> columns = Arrays.asList(
                new Identifier("empno"),
                new Identifier("ename")
        );
        TableReference table = new TableReference("emp");

        SelectStatement select = new SelectStatement(columns, table);
        String str = select.toString();

        assertTrue(str.contains("2"));  // column count
        assertTrue(str.contains("emp")); // table name
    }
}
