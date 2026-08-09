package me.christianrobert.orapgsync.index.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.index.IndexKeyPart;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.core.job.model.index.IndexOutcome;
import me.christianrobert.orapgsync.core.job.model.index.IndexSignature;
import me.christianrobert.orapgsync.core.job.model.index.PostgresIndexCatalog;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides, for every extracted Oracle index, whether to create it and under what name - before
 * any of them is executed.
 *
 * <h2>Why planning is separate from execution</h2>
 *
 * <p>Index creation runs on a worker pool, and name allocation is inherently stateful: two
 * indexes can want the same name, and the second must notice the first took it. Doing that inside
 * the workers would need a shared, synchronized name registry. Planning single-threaded up front
 * removes the shared state entirely - workers receive finished SQL and do nothing but execute it,
 * which is the same split the parallel data transfer uses (order first, then run).</p>
 *
 * <p>It also means the full report - what will be created, skipped and refused, and why - exists
 * before the first statement runs.</p>
 */
@ApplicationScoped
public class IndexCreationPlanner {

    private static final Logger log = LoggerFactory.getLogger(IndexCreationPlanner.class);

    /** PostgreSQL identifier length limit. */
    static final int MAX_IDENTIFIER_LENGTH = 63;

    @Inject
    IndexExpressionTransformer indexExpressionTransformer;

    /** One index that is ready to be executed. */
    public record PlannedIndex(String qualifiedTableName, String indexName, String keyDisplay,
                               String sql, String note) {
    }

    /** The plan: statements to execute, plus the outcomes already decided without executing anything. */
    public record IndexPlan(List<PlannedIndex> statements, List<IndexOutcome> decidedOutcomes) {

        public int totalIndexes() {
            return statements.size() + decidedOutcomes.size();
        }
    }

    public IndexPlan plan(List<IndexMetadata> indexes, PostgresIndexCatalog catalog,
                          TransformationIndices transformationIndices) {
        List<PlannedIndex> statements = new ArrayList<>();
        List<IndexOutcome> decided = new ArrayList<>();
        Set<String> allocatedNames = new HashSet<>();

        for (IndexMetadata index : indexes) {
            PlanStep step = planStep(index, catalog, transformationIndices, allocatedNames);
            if (step.planned() != null) {
                statements.add(step.planned());
            } else {
                decided.add(step.outcome());
            }
        }

        log.info("Index plan: {} to create, {} already decided (skipped or unsupported)",
                statements.size(), decided.size());

        return new IndexPlan(statements, decided);
    }

    /** Exactly one of the two components is non-null. */
    private record PlanStep(PlannedIndex planned, IndexOutcome outcome) {
    }

    private PlanStep planStep(IndexMetadata index, PostgresIndexCatalog catalog,
                              TransformationIndices transformationIndices, Set<String> allocatedNames) {

        String qualifiedTable = index.getQualifiedTableName();
        String keyDisplay = index.getKeyDisplay();

        if (index.isDomain()) {
            return refused(IndexOutcome.unsupported(qualifiedTable, index.getIndexName(), keyDisplay,
                    "Oracle domain index (" + index.getIndexType() + ") has no PostgreSQL equivalent"));
        }

        IndexSignature desired = index.getSignature();
        IndexSignature existing = catalog.findEquivalentOf(desired);
        if (existing != null) {
            return refused(IndexOutcome.skipped(qualifiedTable, index.getIndexName(), keyDisplay,
                    "PostgreSQL already has an equivalent index: " + existing));
        }

        if (catalog.hasIndexNamed(qualifiedTable, index.getIndexName())) {
            return refused(IndexOutcome.skipped(qualifiedTable, index.getIndexName(), keyDisplay,
                    "An index of this name already exists on the table"));
        }

        List<String> renderedKeys = new ArrayList<>(index.getKeyParts().size());
        for (IndexKeyPart part : index.getKeyParts()) {
            Optional<String> rendered = renderKey(part, index, transformationIndices);
            if (rendered.isEmpty()) {
                return refused(IndexOutcome.unsupported(qualifiedTable, index.getIndexName(), keyDisplay,
                        "Index expression could not be transformed to PostgreSQL: " + part.expression()));
            }
            renderedKeys.add(rendered.get());
        }

        String indexName = allocateName(index, catalog, allocatedNames);
        String sql = buildCreateStatement(index, indexName, renderedKeys);

        return new PlanStep(new PlannedIndex(qualifiedTable, indexName, keyDisplay, sql, noteFor(index)), null);
    }

    private static PlanStep refused(IndexOutcome outcome) {
        return new PlanStep(null, outcome);
    }

    private Optional<String> renderKey(IndexKeyPart part, IndexMetadata index,
                                       TransformationIndices transformationIndices) {
        String direction = part.descending() ? " DESC" : "";

        if (part.column()) {
            return Optional.of(PostgresIdentifierNormalizer.normalizeIdentifier(part.expression()) + direction);
        }

        return indexExpressionTransformer
                .transform(part.expression(), index.getSchema(), index.getTableName(), transformationIndices)
                .map(expression -> "(" + expression + ")" + direction);
    }

    /**
     * Oracle index names are preserved so that a re-run is idempotent and so that a failure can
     * be traced back to something recognisable in the source schema.
     *
     * <p>Two things can force a different name. PostgreSQL caps identifiers at 63 characters, and
     * - unlike Oracle, where indexes have a namespace to themselves - it keeps indexes in the same
     * namespace as tables, views and sequences, so an Oracle index sharing a name with a table is
     * legal at the source and a collision here.</p>
     */
    String allocateName(IndexMetadata index, PostgresIndexCatalog catalog, Set<String> allocatedNames) {
        String base = truncate(index.getIndexName());
        String candidate = base;
        int attempt = 1;

        while (isTaken(candidate, index.getSchema(), catalog, allocatedNames)) {
            String suffix = "_" + (++attempt);
            candidate = truncate(base, suffix.length()) + suffix;
        }

        allocatedNames.add(qualifiedIndexName(index.getSchema(), candidate));
        return candidate;
    }

    private boolean isTaken(String candidate, String schema, PostgresIndexCatalog catalog,
                            Set<String> allocatedNames) {
        return catalog.isNameTaken(schema, candidate)
                || allocatedNames.contains(qualifiedIndexName(schema, candidate));
    }

    private static String qualifiedIndexName(String schema, String indexName) {
        return (schema + "." + indexName).toLowerCase();
    }

    private static String truncate(String name) {
        return truncate(name, 0);
    }

    /**
     * Truncates to fit the identifier limit, leaving {@code reserved} characters free. A hash of
     * the full name is appended so that two names sharing a long prefix stay distinct.
     */
    private static String truncate(String name, int reserved) {
        int limit = MAX_IDENTIFIER_LENGTH - reserved;
        if (name.length() <= limit) {
            return name;
        }
        String hash = Integer.toHexString(name.hashCode());
        return name.substring(0, limit - hash.length() - 1) + "_" + hash;
    }

    String buildCreateStatement(IndexMetadata index, String indexName, List<String> renderedKeys) {
        return "CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX "
                + PostgresIdentifierNormalizer.normalizeIdentifier(indexName)
                + " ON " + PostgresIdentifierNormalizer.normalizeIdentifier(index.getSchema())
                + "." + PostgresIdentifierNormalizer.normalizeIdentifier(index.getTableName())
                + " (" + String.join(", ", renderedKeys) + ")";
    }

    /** Records Oracle index types that are migrated as something structurally different. */
    private static String noteFor(IndexMetadata index) {
        if (index.isBitmap()) {
            return "Oracle bitmap index migrated as a B-tree index";
        }
        if (index.getIndexType() != null && index.getIndexType().toUpperCase().contains("REV")) {
            return "Oracle reverse key index migrated as a plain B-tree index";
        }
        return null;
    }
}
