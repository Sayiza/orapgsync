package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for deeply nested function calls to verify O(n) complexity after fix.
 *
 * <p>Before the fix, 65 nested replace() calls caused exponential 2^65 complexity.
 * After the fix, it should complete in O(n) time.</p>
 */
class TypeAnalysisNestedFunctionsTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up empty indices (these tests don't need metadata)
        indices = new TransformationIndices(
                new HashMap<>(), // tableColumns
                new HashMap<>(), // typeMethods
                new HashSet<>(), // packageFunctions
                new HashMap<>(), // synonyms
                Collections.emptyMap(), // typeFieldTypes
                Collections.emptySet()  // objectTypeNames
        );
    }

    /**
     * Tests 30 nested replace() calls complete quickly.
     *
     * <p>Before fix: Would hang forever (2^30 = 1 billion+ operations)</p>
     * <p>After fix: Should complete in < 1 second</p>
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDeeplyNestedReplaceCalls_30Levels() {
        // Generate 30 nested replace calls
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 30; i++) {
            sql.append("replace(");
        }
        sql.append("text");
        for (int i = 0; i < 30; i++) {
            sql.append(", 'a', 'b')");
        }
        sql.append(" FROM dual");

        // Parse and run type analysis
        var parseResult = parser.parseSelectStatement(sql.toString());
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("TEST", indices, typeCache);

        long startTime = System.currentTimeMillis();
        visitor.visit(parseResult.getTree());
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("30 nested replace() - Duration: " + duration + " ms, Types cached: " + typeCache.size());

        // Should complete in < 5 seconds (generous timeout for slow CI machines)
        assertTrue(duration < 5000, "Should complete in < 5 seconds, took: " + duration + " ms");
        assertTrue(typeCache.size() > 0, "Should have cached some types");
    }

    /**
     * Tests 50 nested replace() calls complete quickly.
     *
     * <p>Before fix: Would hang forever (2^50 = 1 quadrillion+ operations)</p>
     * <p>After fix: Should complete in < 1 second</p>
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDeeplyNestedReplaceCalls_50Levels() {
        // Generate 50 nested replace calls
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 50; i++) {
            sql.append("replace(");
        }
        sql.append("text");
        for (int i = 0; i < 50; i++) {
            sql.append(", 'x', 'y')");
        }
        sql.append(" FROM dual");

        // Parse and run type analysis
        var parseResult = parser.parseSelectStatement(sql.toString());
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("TEST", indices, typeCache);

        long startTime = System.currentTimeMillis();
        visitor.visit(parseResult.getTree());
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("50 nested replace() - Duration: " + duration + " ms, Types cached: " + typeCache.size());

        assertTrue(duration < 5000, "Should complete in < 5 seconds, took: " + duration + " ms");
    }

    /**
     * Tests the exact pattern from the user's bug report - 65 nested replace() calls.
     *
     * <p>This matches the htmldiacode function that converts special characters to HTML entities.</p>
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDeeplyNestedReplaceCalls_65Levels_ExactBugPattern() {
        // Generate 65 nested replace calls (matches the bug report)
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 65; i++) {
            sql.append("replace(");
        }
        sql.append("text");
        for (int i = 0; i < 65; i++) {
            sql.append(", 'char" + i + "', '&entity" + i + ";')");
        }
        sql.append(" FROM dual");

        // Parse and run type analysis
        var parseResult = parser.parseSelectStatement(sql.toString());
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("TEST", indices, typeCache);

        long startTime = System.currentTimeMillis();
        visitor.visit(parseResult.getTree());
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("65 nested replace() - Duration: " + duration + " ms, Types cached: " + typeCache.size());

        // Should complete in < 10 seconds even on slow machines
        assertTrue(duration < 10000, "Should complete in < 10 seconds, took: " + duration + " ms");
    }

    /**
     * Tests nested arithmetic operations also work correctly.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDeeplyNestedArithmeticOperations() {
        // Generate deeply nested arithmetic: ((((1 + 2) + 3) + 4) + ... + 40)
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 40; i++) {
            sql.append("(");
        }
        sql.append("1");
        for (int i = 2; i <= 41; i++) {
            sql.append(" + ").append(i).append(")");
        }
        sql.append(" FROM dual");

        var parseResult = parser.parseSelectStatement(sql.toString());
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("TEST", indices, typeCache);

        long startTime = System.currentTimeMillis();
        visitor.visit(parseResult.getTree());
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("40 nested arithmetic - Duration: " + duration + " ms, Types cached: " + typeCache.size());

        assertTrue(duration < 5000, "Should complete in < 5 seconds, took: " + duration + " ms");
    }
}
