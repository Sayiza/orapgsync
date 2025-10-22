package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests synonym resolution during SQL transformation.
 *
 * Oracle synonyms provide alternative names for tables/views.
 * Since PostgreSQL doesn't have synonyms, we must resolve them during transformation.
 *
 * Resolution follows Oracle rules:
 * 1. Check current schema for synonym
 * 2. Fall back to PUBLIC schema
 * 3. Use original name if not a synonym
 */
class SynonymResolutionTransformationTest {

    private AntlrParser parser;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
    }

    @Test
    void testSynonymResolution_CurrentSchema() {
        // Given: Synonym in current schema pointing to actual table
        // emp (synonym) → hr.employees (actual table)
        Map<String, Map<String, String>> synonymMap = new HashMap<>();
        Map<String, String> hrSynonyms = new HashMap<>();
        hrSynonyms.put("emp", "hr.employees");
        synonymMap.put("hr", hrSynonyms);

        TransformationIndices indices = createIndicesWithSynonyms(synonymMap);
        TransformationContext context = new TransformationContext("hr", indices, new SimpleTypeEvaluator("hr", indices));
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // When: Oracle SQL uses synonym
        String oracleSql = "SELECT emp_id, name FROM emp";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Synonym should be resolved to actual table
        assertEquals("SELECT emp_id , name FROM hr.employees", normalized,
            "Synonym should be resolved to actual qualified table name");
    }

    @Test
    void testSynonymResolution_PublicFallback() {
        // Given: Synonym in PUBLIC schema
        // emp (public synonym) → hr.employees (actual table)
        Map<String, Map<String, String>> synonymMap = new HashMap<>();
        Map<String, String> publicSynonyms = new HashMap<>();
        publicSynonyms.put("emp", "hr.employees");
        synonymMap.put("public", publicSynonyms);

        TransformationIndices indices = createIndicesWithSynonyms(synonymMap);
        TransformationContext context = new TransformationContext("sales", indices, new SimpleTypeEvaluator("sales", indices));  // Different schema
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // When: Oracle SQL uses public synonym
        String oracleSql = "SELECT emp_id, name FROM emp";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Public synonym should be resolved
        assertEquals("SELECT emp_id , name FROM hr.employees", normalized,
            "Public synonym should be resolved when not found in current schema");
    }

    @Test
    void testSynonymResolution_CurrentSchemaOverridesPublic() {
        // Given: Same synonym name in both current schema and PUBLIC
        // Current schema synonym takes precedence (Oracle behavior)
        Map<String, Map<String, String>> synonymMap = new HashMap<>();

        Map<String, String> hrSynonyms = new HashMap<>();
        hrSynonyms.put("emp", "hr.employees");  // Current schema synonym
        synonymMap.put("hr", hrSynonyms);

        Map<String, String> publicSynonyms = new HashMap<>();
        publicSynonyms.put("emp", "other.emp_table");  // Public synonym
        synonymMap.put("public", publicSynonyms);

        TransformationIndices indices = createIndicesWithSynonyms(synonymMap);
        TransformationContext context = new TransformationContext("hr", indices, new SimpleTypeEvaluator("hr", indices));
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // When: Oracle SQL uses synonym
        String oracleSql = "SELECT emp_id FROM emp";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Current schema synonym should win
        assertEquals("SELECT emp_id FROM hr.employees", normalized,
            "Current schema synonym should take precedence over PUBLIC");
    }

    @Test
    void testSynonymResolution_NotASynonym() {
        // Given: Table name that is NOT a synonym
        Map<String, Map<String, String>> synonymMap = new HashMap<>();
        synonymMap.put("hr", new HashMap<>());  // No synonyms

        TransformationIndices indices = createIndicesWithSynonyms(synonymMap);
        TransformationContext context = new TransformationContext("hr", indices, new SimpleTypeEvaluator("hr", indices));
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // When: Oracle SQL uses actual table name (unqualified)
        String oracleSql = "SELECT emp_id FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Unqualified table name should be qualified with current schema
        // This prevents "relation does not exist" errors in PostgreSQL
        assertEquals("SELECT emp_id FROM hr.employees", normalized,
            "Non-synonym table name should be qualified with current schema");
    }

    @Test
    void testSynonymResolution_WithTableAlias() {
        // Given: Synonym with table alias
        Map<String, Map<String, String>> synonymMap = new HashMap<>();
        Map<String, String> hrSynonyms = new HashMap<>();
        hrSynonyms.put("emp", "hr.employees");
        synonymMap.put("hr", hrSynonyms);

        TransformationIndices indices = createIndicesWithSynonyms(synonymMap);
        TransformationContext context = new TransformationContext("hr", indices, new SimpleTypeEvaluator("hr", indices));
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // When: Oracle SQL uses synonym with alias
        String oracleSql = "SELECT e.emp_id, e.name FROM emp e";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Synonym resolved, alias preserved
        assertEquals("SELECT e . emp_id , e . name FROM hr.employees e", normalized,
            "Synonym should be resolved and alias preserved");
    }

    @Test
    void testSynonymResolution_NoContext() {
        // Given: No transformation context set (builder without context)
        // This simulates the minimal tests that don't set up context
        PostgresCodeBuilder builder = new PostgresCodeBuilder();  // No context

        // When: Oracle SQL uses a name
        String oracleSql = "SELECT emp_id FROM emp";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Name should be preserved as-is (no resolution without context)
        assertEquals("SELECT emp_id FROM emp", normalized,
            "Without context, table name should be preserved as-is");
    }

    @Test
    void testSynonymResolution_ParenthesizedQuery() {
        // Given: Synonym in parenthesized query (Oracle view definition format)
        Map<String, Map<String, String>> synonymMap = new HashMap<>();
        Map<String, String> hrSynonyms = new HashMap<>();
        hrSynonyms.put("emp", "hr.employees");
        synonymMap.put("hr", hrSynonyms);

        TransformationIndices indices = createIndicesWithSynonyms(synonymMap);
        TransformationContext context = new TransformationContext("hr", indices, new SimpleTypeEvaluator("hr", indices));
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // When: Parenthesized Oracle SQL uses synonym
        String oracleSql = "(SELECT emp_id FROM emp)";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Synonym resolved in parenthesized query
        assertEquals("(SELECT emp_id FROM hr.employees)", normalized,
            "Synonym should be resolved in parenthesized queries");
    }

    // Helper method to create minimal TransformationIndices with just synonyms
    private TransformationIndices createIndicesWithSynonyms(Map<String, Map<String, String>> synonyms) {
        return new TransformationIndices(
            new HashMap<>(),  // No table columns needed
            new HashMap<>(),  // No type methods needed
            Set.of(),         // No package functions needed
            synonyms          // Synonyms for testing
        );
    }
}
