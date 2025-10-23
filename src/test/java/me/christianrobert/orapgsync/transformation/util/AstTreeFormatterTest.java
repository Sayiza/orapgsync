package me.christianrobert.orapgsync.transformation.util;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AstTreeFormatter - verifies AST tree formatting for debugging.
 */
class AstTreeFormatterTest {

    @Test
    void formatSimpleSelectStatement() {
        String sql = "SELECT emp_id, emp_name FROM employees WHERE dept_id = 10";

        // Parse SQL
        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);
        PlSqlParser.Select_statementContext tree = parser.select_statement();

        // Format tree
        String formatted = AstTreeFormatter.format(tree);

        System.out.println("=== Formatted AST Tree ===");
        System.out.println(formatted);
        System.out.println("=========================");

        // Verify formatting contains expected elements
        assertNotNull(formatted, "Formatted tree should not be null");
        assertFalse(formatted.isEmpty(), "Formatted tree should not be empty");
        assertTrue(formatted.contains("Select_statement"), "Should contain rule name");
        assertTrue(formatted.contains("\"emp_id\"") || formatted.contains("emp_id"),
                "Should contain column name");
        assertTrue(formatted.contains("\"employees\"") || formatted.contains("employees"),
                "Should contain table name");
        assertTrue(formatted.contains("WHERE") || formatted.contains("\"WHERE\""),
                "Should contain WHERE keyword");
    }

    @Test
    void formatConnectByStatement() {
        String sql = "SELECT emp_id, SYS_CONNECT_BY_PATH(emp_name, '/') as path " +
                     "FROM employees " +
                     "START WITH manager_id IS NULL " +
                     "CONNECT BY PRIOR emp_id = manager_id";

        // Parse SQL
        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);
        PlSqlParser.Select_statementContext tree = parser.select_statement();

        // Format tree
        String formatted = AstTreeFormatter.format(tree);

        System.out.println("=== Formatted CONNECT BY Tree ===");
        System.out.println(formatted);
        System.out.println("================================");

        // Verify formatting contains CONNECT BY elements
        assertNotNull(formatted);
        assertTrue(formatted.contains("SYS_CONNECT_BY_PATH") ||
                   formatted.contains("sys_connect_by_path"),
                "Should contain SYS_CONNECT_BY_PATH function");
        assertTrue(formatted.contains("CONNECT") || formatted.contains("\"CONNECT\""),
                "Should contain CONNECT keyword");
    }

    @Test
    void formatNullTree() {
        String formatted = AstTreeFormatter.format(null);
        assertEquals("(null tree)", formatted, "Should handle null tree gracefully");
    }

    @Test
    void formatTreeWithIndentation() {
        String sql = "SELECT * FROM dual";

        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);
        PlSqlParser.Select_statementContext tree = parser.select_statement();

        String formatted = AstTreeFormatter.format(tree);

        System.out.println("=== Tree with Indentation ===");
        System.out.println(formatted);
        System.out.println("============================");

        // Verify indentation (should have lines starting with spaces)
        assertTrue(formatted.contains("  "), "Should contain indentation");

        // Should have multiple lines
        String[] lines = formatted.split("\n");
        assertTrue(lines.length > 3, "Should have multiple lines (more than 3)");
    }
}
