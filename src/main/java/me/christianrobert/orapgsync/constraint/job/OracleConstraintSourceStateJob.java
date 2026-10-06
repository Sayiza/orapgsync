package me.christianrobert.orapgsync.constraint.job;

import jakarta.enterprise.context.Dependent;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Oracle Constraint Source State Job.
 *
 * This job extracts constraint metadata from Oracle table metadata that is already
 * loaded in the StateService. It does NOT query the Oracle database directly.
 *
 * This is a deviation from the typical extraction pattern because:
 * - Constraints are already extracted as part of table metadata extraction
 * - We just need to aggregate and filter them for display
 * - The result is NOT saved to StateService (display only)
 *
 * The job:
 * 1. Gets Oracle table metadata from StateService
 * 2. Extracts all constraints from the tables
 * 3. Filters out NOT NULL constraints (already applied during table creation)
 * 4. Filters out system schema constraints
 * 5. Returns the list for frontend display
 */
@Dependent
public class OracleConstraintSourceStateJob extends AbstractDatabaseExtractionJob<ConstraintMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleConstraintSourceStateJob.class);

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "CONSTRAINT_SOURCE_STATE";
    }

    @Override
    public Class<ConstraintMetadata> getResultType() {
        return ConstraintMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<ConstraintMetadata> results) {
        // Do NOT save to state - this is display-only
        // Constraints are already stored as part of table metadata
    }

    @Override
    protected List<ConstraintMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting Oracle constraint source state extraction");

        // Get Oracle table metadata from state
        List<TableMetadata> oracleTables = stateService.getOracleTableMetadata();

        if (oracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No tables found",
                    "No Oracle table metadata found in state. Please extract Oracle tables first.");
            log.warn("No Oracle tables found in state for constraint extraction");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 10, "Analyzing tables",
                String.format("Found %d Oracle tables", oracleTables.size()));

        // Extract all constraints from tables
        List<ConstraintMetadata> allConstraints = new ArrayList<>();
        int processedTables = 0;

        for (TableMetadata table : oracleTables) {
            // Filter valid schemas (exclude system schemas)
            if (filterValidSchemas(List.of(table.getSchema())).isEmpty()) {
                continue;
            }

            // Extract constraints from this table (skip NOT NULL - already applied)
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (!constraint.isNotNullConstraint()) {
                    // Set schema and tableName for standalone constraint display
                    constraint.setSchema(table.getSchema());
                    constraint.setTableName(table.getTableName());
                    allConstraints.add(constraint);
                }
            }

            processedTables++;
            if (processedTables % 100 == 0) {
                int progressPercentage = 10 + (processedTables * 80 / oracleTables.size());
                updateProgress(progressCallback, progressPercentage,
                        String.format("Processing table %d/%d", processedTables, oracleTables.size()),
                        String.format("Found %d constraints so far", allConstraints.size()));
            }
        }

        updateProgress(progressCallback, 90, "Finalizing",
                String.format("Extracted %d constraints from %d tables", allConstraints.size(), processedTables));

        // Count by type for summary
        long pkCount = allConstraints.stream().filter(ConstraintMetadata::isPrimaryKey).count();
        long fkCount = allConstraints.stream().filter(ConstraintMetadata::isForeignKey).count();
        long uniqueCount = allConstraints.stream().filter(ConstraintMetadata::isUniqueConstraint).count();
        long checkCount = allConstraints.stream().filter(ConstraintMetadata::isCheckConstraint).count();

        log.info("Extracted {} constraints from {} Oracle tables: {} PK, {} FK, {} Unique, {} Check",
                allConstraints.size(), processedTables, pkCount, fkCount, uniqueCount, checkCount);

        updateProgress(progressCallback, 100, "Complete",
                String.format("Extracted %d constraints: %d PK, %d FK, %d Unique, %d Check",
                        allConstraints.size(), pkCount, fkCount, uniqueCount, checkCount));

        return allConstraints;
    }

    @Override
    protected String generateSummaryMessage(List<ConstraintMetadata> result) {
        long pkCount = result.stream().filter(ConstraintMetadata::isPrimaryKey).count();
        long fkCount = result.stream().filter(ConstraintMetadata::isForeignKey).count();
        long uniqueCount = result.stream().filter(ConstraintMetadata::isUniqueConstraint).count();
        long checkCount = result.stream().filter(ConstraintMetadata::isCheckConstraint).count();

        return String.format("Extracted %d Oracle constraints: %d PK, %d FK, %d Unique, %d Check",
                result.size(), pkCount, fkCount, uniqueCount, checkCount);
    }
}
