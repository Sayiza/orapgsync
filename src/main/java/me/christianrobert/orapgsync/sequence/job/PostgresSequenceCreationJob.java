package me.christianrobert.orapgsync.sequence.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceCreationResult;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

@Dependent
public class PostgresSequenceCreationJob extends AbstractDatabaseWriteJob<SequenceCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresSequenceCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "SEQUENCE_CREATION";
    }

    @Override
    public Class<SequenceCreationResult> getResultType() {
        return SequenceCreationResult.class;
    }

    @Override
    protected void saveResultsToState(SequenceCreationResult result) {
        stateService.setSequenceCreationResult(result);
    }

    @Override
    protected SequenceCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL sequence creation process");

        // Get Oracle sequences from state
        List<SequenceMetadata> oracleSequences = getOracleSequences();
        if (oracleSequences.isEmpty()) {
            updateProgress(progressCallback, 100, "No sequences to process",
                    "No Oracle sequences found in state. Please extract Oracle sequences first.");
            log.warn("No Oracle sequences found in state for sequence creation");
            return new SequenceCreationResult();
        }

        // Filter valid sequences (exclude system schemas)
        List<SequenceMetadata> validOracleSequences = filterValidSequences(oracleSequences);

        updateProgress(progressCallback, 10, "Analyzing sequences",
                String.format("Found %d Oracle sequences, %d are valid for creation",
                        oracleSequences.size(), validOracleSequences.size()));

        SequenceCreationResult result = new SequenceCreationResult();

        if (validOracleSequences.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid sequences",
                    "No valid Oracle sequences to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL sequences
            Set<String> existingPostgresSequences = getExistingPostgresSequences(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing sequences",
                    String.format("Found %d existing PostgreSQL sequences", existingPostgresSequences.size()));

            // Determine which sequences need to be created
            List<SequenceMetadata> sequencesToCreate = new ArrayList<>();
            List<SequenceMetadata> sequencesAlreadyExisting = new ArrayList<>();

            for (SequenceMetadata sequence : validOracleSequences) {
                String qualifiedSequenceName = getQualifiedSequenceName(sequence);
                if (existingPostgresSequences.contains(qualifiedSequenceName.toLowerCase())) {
                    sequencesAlreadyExisting.add(sequence);
                } else {
                    sequencesToCreate.add(sequence);
                }
            }

            // Mark already existing sequences as skipped
            for (SequenceMetadata sequence : sequencesAlreadyExisting) {
                String qualifiedSequenceName = getQualifiedSequenceName(sequence);
                result.addSkippedSequence(qualifiedSequenceName);
                log.info("Sequence '{}' already exists in PostgreSQL, skipping", qualifiedSequenceName);
            }

            updateProgress(progressCallback, 40, "Planning creation",
                    String.format("%d sequences to create, %d already exist",
                            sequencesToCreate.size(), sequencesAlreadyExisting.size()));

            if (sequencesToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All sequences exist",
                        "All Oracle sequences already exist in PostgreSQL");
                return result;
            }

            // Create sequences
            createSequences(postgresConnection, sequencesToCreate, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d sequences, skipped %d existing, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Sequence creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<SequenceMetadata> getOracleSequences() {
        return stateService.getOracleSequenceMetadata();
    }

    private List<SequenceMetadata> filterValidSequences(List<SequenceMetadata> sequences) {
        List<SequenceMetadata> validSequences = new ArrayList<>();
        for (SequenceMetadata sequence : sequences) {
            if (!filterValidSchemas(List.of(sequence.getSchema())).isEmpty()) {
                validSequences.add(sequence);
            }
        }
        return validSequences;
    }

    private Set<String> getExistingPostgresSequences(Connection connection) throws SQLException {
        Set<String> sequences = new HashSet<>();

        String sql = """
            SELECT sequence_schema, sequence_name
            FROM information_schema.sequences
            WHERE sequence_schema NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("sequence_schema");
                String sequenceName = rs.getString("sequence_name");
                String qualifiedName = String.format("%s.%s", schemaName, sequenceName).toLowerCase();
                sequences.add(qualifiedName);
            }
        }

        log.debug("Found {} existing PostgreSQL sequences", sequences.size());
        return sequences;
    }

    private String getQualifiedSequenceName(SequenceMetadata sequence) {
        return String.format("%s.%s", sequence.getSchema(), sequence.getSequenceName());
    }

    private void createSequences(Connection connection, List<SequenceMetadata> sequences,
                                 SequenceCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalSequences = sequences.size();
        int processedSequences = 0;

        for (SequenceMetadata sequence : sequences) {
            int progressPercentage = 40 + (processedSequences * 50 / totalSequences);
            String qualifiedSequenceName = getQualifiedSequenceName(sequence);
            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating sequence: %s", qualifiedSequenceName),
                    String.format("Sequence %d of %d", processedSequences + 1, totalSequences));

            try {
                createSequence(connection, sequence);
                result.addCreatedSequence(qualifiedSequenceName);
                log.info("Successfully created PostgreSQL sequence: {}", qualifiedSequenceName);
            } catch (SQLException e) {
                String errorMessage = String.format("Failed to create sequence '%s': %s",
                        qualifiedSequenceName, e.getMessage());
                String sqlStatement = generateCreateSequenceSQL(sequence);
                result.addError(qualifiedSequenceName, errorMessage, sqlStatement);
                log.error("Failed to create sequence: {}", qualifiedSequenceName, e);
            }

            processedSequences++;
        }
    }

    private void createSequence(Connection connection, SequenceMetadata sequence) throws SQLException {
        String sql = generateCreateSequenceSQL(sequence);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private String generateCreateSequenceSQL(SequenceMetadata sequence) {
        StringBuilder sql = new StringBuilder();

        // CREATE SEQUENCE schema.sequence_name
        sql.append("CREATE SEQUENCE ");
        sql.append(sequence.getSchema().toLowerCase());
        sql.append(".");
        sql.append(sequence.getSequenceName().toLowerCase());

        // INCREMENT BY
        if (sequence.getIncrementBy() != null) {
            sql.append(" INCREMENT BY ").append(sequence.getIncrementBy());
        }

        // MINVALUE
        if (sequence.getMinValue() != null) {
            sql.append(" MINVALUE ").append(sequence.getMinValue());
        }

        // MAXVALUE
        if (sequence.getMaxValue() != null) {
            sql.append(" MAXVALUE ").append(sequence.getMaxValue());
        }

        // START WITH (use Oracle's current value to synchronize)
        if (sequence.getCurrentValue() != null) {
            sql.append(" START WITH ").append(sequence.getCurrentValue());
        }

        // CACHE
        if (sequence.getCacheSize() != null) {
            sql.append(" CACHE ").append(sequence.getCacheSize());
        }

        // CYCLE / NO CYCLE
        if ("Y".equals(sequence.getCycleFlag())) {
            sql.append(" CYCLE");
        } else {
            sql.append(" NO CYCLE");
        }

        return sql.toString();
    }

    @Override
    protected String generateSummaryMessage(SequenceCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Sequence creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s",
                    String.join(", ", result.getCreatedSequences())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s",
                    String.join(", ", result.getSkippedSequences())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
