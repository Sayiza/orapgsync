package me.christianrobert.orapgsync.index.service;

import me.christianrobert.orapgsync.core.job.model.index.IndexKeyPart;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.core.job.model.index.IndexOutcome;
import me.christianrobert.orapgsync.core.job.model.index.IndexSignature;
import me.christianrobert.orapgsync.core.job.model.index.PostgresIndexCatalog;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner is where every decision that needs shared state is made, single-threaded, before any
 * statement runs. These tests pin the decisions themselves; execution is covered separately by
 * {@link ParallelIndexCreationServiceTest}.
 */
class IndexCreationPlannerTest {

    private IndexCreationPlanner planner;

    /** Transforms nothing unless a test says otherwise, so "cannot transform" is the default path. */
    private static class StubExpressionTransformer extends IndexExpressionTransformer {
        private final Map<String, String> translations;

        StubExpressionTransformer(Map<String, String> translations) {
            this.translations = translations;
        }

        @Override
        public Optional<String> transform(String oracleExpression, String schema, String table,
                                          TransformationIndices indices) {
            return Optional.ofNullable(translations.get(oracleExpression));
        }
    }

    @BeforeEach
    void setUp() {
        planner = new IndexCreationPlanner();
        planner.indexExpressionTransformer = new StubExpressionTransformer(Map.of());
    }

    private void withTranslations(Map<String, String> translations) {
        planner.indexExpressionTransformer = new StubExpressionTransformer(translations);
    }

    private static IndexMetadata index(String name, boolean unique, IndexKeyPart... keys) {
        return new IndexMetadata("hr", "emp", name, unique, IndexMetadata.TYPE_NORMAL, List.of(keys));
    }

    private static IndexKeyPart col(String name) {
        return IndexKeyPart.ofColumn(name, false);
    }

    private static PostgresIndexCatalog catalogWith(List<IndexSignature> signatures,
                                                    Set<String> indexNamesOnEmp,
                                                    Set<String> relationNames) {
        return new PostgresIndexCatalog(
                signatures.isEmpty() ? Map.of() : Map.of("hr.emp", signatures),
                indexNamesOnEmp.isEmpty() ? Map.of() : Map.of("hr.emp", indexNamesOnEmp),
                relationNames.isEmpty() ? Map.of() : Map.of("hr", relationNames));
    }

    private static PostgresIndexCatalog emptyCatalog() {
        return PostgresIndexCatalog.empty();
    }

    private IndexCreationPlanner.IndexPlan plan(List<IndexMetadata> indexes, PostgresIndexCatalog catalog) {
        return planner.plan(indexes, catalog, null);
    }

    @Test
    @DisplayName("a plain column index becomes a CREATE INDEX statement")
    void plainColumnIndex() {
        IndexCreationPlanner.IndexPlan result =
                plan(List.of(index("emp_dept_ix", false, col("dept_id"))), emptyCatalog());

        assertEquals(1, result.statements().size());
        assertEquals("CREATE INDEX emp_dept_ix ON hr.emp (dept_id)", result.statements().get(0).sql());
    }

    @Test
    void uniqueIndexProducesUniqueStatement() {
        IndexCreationPlanner.IndexPlan result =
                plan(List.of(index("emp_email_uix", true, col("email"))), emptyCatalog());

        assertEquals("CREATE UNIQUE INDEX emp_email_uix ON hr.emp (email)",
                result.statements().get(0).sql());
    }

    @Test
    void compositeIndexKeepsKeyOrderAndDirection() {
        IndexMetadata composite = index("emp_ix", false,
                col("dept_id"), IndexKeyPart.ofColumn("hire_date", true));

        IndexCreationPlanner.IndexPlan result = plan(List.of(composite), emptyCatalog());

        assertEquals("CREATE INDEX emp_ix ON hr.emp (dept_id, hire_date DESC)",
                result.statements().get(0).sql());
    }

    @Test
    @DisplayName("a reserved word column is quoted")
    void reservedWordColumnIsQuoted() {
        IndexCreationPlanner.IndexPlan result =
                plan(List.of(index("emp_end_ix", false, col("end"))), emptyCatalog());

        assertTrue(result.statements().get(0).sql().contains("(\"end\")"),
                "reserved word must be quoted: " + result.statements().get(0).sql());
    }

    @Test
    @DisplayName("an index PostgreSQL already has by signature is skipped, whatever it is called")
    void equivalentExistingIndexIsSkipped() {
        IndexSignature existing = IndexSignature.of("hr", "emp", false, List.of(col("dept_id")));
        PostgresIndexCatalog catalog = catalogWith(List.of(existing), Set.of("some_other_name"), Set.of());

        IndexCreationPlanner.IndexPlan result =
                plan(List.of(index("emp_dept_ix", false, col("dept_id"))), catalog);

        assertEquals(0, result.statements().size());
        assertEquals(1, result.decidedOutcomes().size());
        assertEquals(IndexOutcome.Status.SKIPPED, result.decidedOutcomes().get(0).status());
    }

    @Test
    @DisplayName("an index of the same name on the same table is skipped - the re-run guard")
    void sameNameOnSameTableIsSkipped() {
        // Expression text cannot be compared reliably across the two databases, so a function-based
        // index this migration already created is recognised by its preserved name instead.
        PostgresIndexCatalog catalog = catalogWith(List.of(), Set.of("emp_upper_ix"), Set.of("emp_upper_ix"));
        withTranslations(Map.of("UPPER(\"NAME\")", "upper(name)"));

        IndexMetadata functionBased = new IndexMetadata("hr", "emp", "emp_upper_ix", false,
                "FUNCTION-BASED NORMAL", List.of(IndexKeyPart.ofExpression("UPPER(\"NAME\")", false)));

        IndexCreationPlanner.IndexPlan result = plan(List.of(functionBased), catalog);

        assertEquals(0, result.statements().size());
        assertEquals(IndexOutcome.Status.SKIPPED, result.decidedOutcomes().get(0).status());
    }

    @Test
    void transformableExpressionIsWrappedInParentheses() {
        withTranslations(Map.of("UPPER(\"NAME\")", "upper(name)"));

        IndexMetadata functionBased = new IndexMetadata("hr", "emp", "emp_upper_ix", false,
                "FUNCTION-BASED NORMAL", List.of(IndexKeyPart.ofExpression("UPPER(\"NAME\")", false)));

        IndexCreationPlanner.IndexPlan result = plan(List.of(functionBased), emptyCatalog());

        assertEquals("CREATE INDEX emp_upper_ix ON hr.emp ((upper(name)))",
                result.statements().get(0).sql());
    }

    @Test
    @DisplayName("an untransformable expression is reported, not guessed at")
    void untransformableExpressionIsUnsupported() {
        IndexMetadata functionBased = new IndexMetadata("hr", "emp", "emp_weird_ix", false,
                "FUNCTION-BASED NORMAL", List.of(IndexKeyPart.ofExpression("MY_PKG.SCORE(\"X\")", false)));

        IndexCreationPlanner.IndexPlan result = plan(List.of(functionBased), emptyCatalog());

        assertEquals(0, result.statements().size());
        IndexOutcome outcome = result.decidedOutcomes().get(0);
        assertEquals(IndexOutcome.Status.UNSUPPORTED, outcome.status());
        assertTrue(outcome.reason().contains("MY_PKG.SCORE"),
                "reason should name the offending expression: " + outcome.reason());
    }

    @Test
    void domainIndexIsUnsupported() {
        IndexMetadata domain = new IndexMetadata("hr", "emp", "emp_text_ix", false,
                "DOMAIN", List.of(col("resume")));

        IndexCreationPlanner.IndexPlan result = plan(List.of(domain), emptyCatalog());

        assertEquals(IndexOutcome.Status.UNSUPPORTED, result.decidedOutcomes().get(0).status());
    }

    @Test
    @DisplayName("a bitmap index is created as a B-tree and says so")
    void bitmapIndexCarriesNote() {
        IndexMetadata bitmap = new IndexMetadata("hr", "emp", "emp_flag_bix", false,
                IndexMetadata.TYPE_BITMAP, List.of(col("active_flag")));

        IndexCreationPlanner.IndexPlan result = plan(List.of(bitmap), emptyCatalog());

        assertEquals(1, result.statements().size());
        assertTrue(result.statements().get(0).note().contains("bitmap"),
                "note should record the downgrade: " + result.statements().get(0).note());
    }

    @Test
    @DisplayName("a name already used by a table is avoided - PostgreSQL shares one namespace")
    void collisionWithExistingRelationForcesRename() {
        // Legal in Oracle, where indexes have their own namespace; a collision here.
        PostgresIndexCatalog catalog = catalogWith(List.of(), Set.of(), Set.of("emp_archive"));

        IndexCreationPlanner.IndexPlan result =
                plan(List.of(index("emp_archive", false, col("dept_id"))), catalog);

        assertEquals(1, result.statements().size());
        assertNotEquals("emp_archive", result.statements().get(0).indexName());
        assertTrue(result.statements().get(0).indexName().startsWith("emp_archive_"));
    }

    @Test
    @DisplayName("two indexes wanting the same name both get created")
    void duplicateNamesWithinOneRunAreDisambiguated() {
        IndexMetadata first = new IndexMetadata("hr", "emp", "dup_ix", false,
                IndexMetadata.TYPE_NORMAL, List.of(col("a")));
        IndexMetadata second = new IndexMetadata("hr", "dept", "dup_ix", false,
                IndexMetadata.TYPE_NORMAL, List.of(col("b")));

        IndexCreationPlanner.IndexPlan result = plan(List.of(first, second), emptyCatalog());

        assertEquals(2, result.statements().size());
        assertNotEquals(result.statements().get(0).indexName(), result.statements().get(1).indexName());
    }

    @Test
    void longNamesAreTruncatedToTheIdentifierLimit() {
        String longName = "a".repeat(90);

        IndexCreationPlanner.IndexPlan result =
                plan(List.of(index(longName, false, col("dept_id"))), emptyCatalog());

        String name = result.statements().get(0).indexName();
        assertTrue(name.length() <= IndexCreationPlanner.MAX_IDENTIFIER_LENGTH,
                "name was " + name.length() + " characters: " + name);
    }

    @Test
    @DisplayName("every index is accounted for exactly once")
    void noIndexIsLost() {
        List<IndexMetadata> indexes = List.of(
                index("plain_ix", false, col("a")),
                new IndexMetadata("hr", "emp", "domain_ix", false, "DOMAIN", List.of(col("b"))),
                new IndexMetadata("hr", "emp", "expr_ix", false, "FUNCTION-BASED NORMAL",
                        List.of(IndexKeyPart.ofExpression("F(\"C\")", false))),
                index("unique_ix", true, col("d")));

        IndexCreationPlanner.IndexPlan result = plan(indexes, emptyCatalog());

        assertEquals(indexes.size(), result.totalIndexes());
    }

    @Test
    void emptyInputProducesEmptyPlan() {
        IndexCreationPlanner.IndexPlan result = plan(List.of(), emptyCatalog());

        assertEquals(0, result.totalIndexes());
    }
}
