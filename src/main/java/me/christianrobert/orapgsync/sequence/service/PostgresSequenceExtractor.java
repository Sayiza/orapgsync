package me.christianrobert.orapgsync.sequence.service;

import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for extracting sequence metadata from PostgreSQL database.
 */
public class PostgresSequenceExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgresSequenceExtractor.class);

    /**
     * Extracts all sequences for the specified schemas from PostgreSQL.
     *
     * @param postgresConn PostgreSQL database connection
     * @param schemas List of schema names to extract sequences from
     * @return List of SequenceMetadata objects
     * @throws SQLException if database operations fail
     */
    public static List<SequenceMetadata> extractAllSequences(Connection postgresConn, List<String> schemas) throws SQLException {
        List<SequenceMetadata> sequenceMetadataList = new ArrayList<>();

        for (String schema : schemas) {
            if (UserExcluder.is2BeExclueded(schema)) {
                continue;
            }

            List<SequenceMetadata> schemaSequences = fetchSequencesForSchema(postgresConn, schema);
            sequenceMetadataList.addAll(schemaSequences);

            log.info("Extracted {} sequences from PostgreSQL schema {}", schemaSequences.size(), schema);
        }

        return sequenceMetadataList;
    }

    /**
     * Fetches all sequences for a single schema.
     *
     * @param postgresConn PostgreSQL database connection
     * @param schema Schema name
     * @return List of SequenceMetadata for the schema
     * @throws SQLException if database operations fail
     */
    private static List<SequenceMetadata> fetchSequencesForSchema(Connection postgresConn, String schema) throws SQLException {
        List<SequenceMetadata> sequences = new ArrayList<>();

        // Query both information_schema.sequences and pg_sequences to get complete metadata
        // information_schema has min/max values, pg_sequences has cache_size and last_value
        String sql = """
            SELECT s.sequence_name,
                   s.minimum_value,
                   s.maximum_value,
                   s.increment,
                   s.cycle_option,
                   ps.cache_size,
                   ps.last_value
            FROM information_schema.sequences s
            JOIN pg_sequences ps ON s.sequence_name = ps.sequencename
                                AND s.sequence_schema = ps.schemaname
            WHERE s.sequence_schema = ?
            ORDER BY s.sequence_name
            """;

        try (PreparedStatement ps = postgresConn.prepareStatement(sql)) {
            ps.setString(1, schema.toLowerCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sequenceName = rs.getString("sequence_name").toLowerCase();
                    SequenceMetadata metadata = new SequenceMetadata(schema.toLowerCase(), sequenceName);

                    // Extract metadata - use BigInteger to handle large values
                    String minValueStr = rs.getString("minimum_value");
                    String maxValueStr = rs.getString("maximum_value");
                    String lastValueStr = rs.getString("last_value");

                    metadata.setMinValue(minValueStr != null ? new BigInteger(minValueStr) : null);
                    metadata.setMaxValue(maxValueStr != null ? new BigInteger(maxValueStr) : null);
                    metadata.setIncrementBy(rs.getInt("increment"));
                    metadata.setCurrentValue(lastValueStr != null ? new BigInteger(lastValueStr) : null);
                    metadata.setCacheSize(rs.getInt("cache_size"));

                    // Convert PostgreSQL cycle_option to Oracle-style flag
                    String cycleOption = rs.getString("cycle_option");
                    metadata.setCycleFlag("YES".equals(cycleOption) ? "Y" : "N");

                    // PostgreSQL doesn't have ORDER flag (always unordered)
                    metadata.setOrderFlag("N");

                    sequences.add(metadata);

                    log.debug("Extracted PostgreSQL sequence: {}", metadata);
                }
            }
        }

        return sequences;
    }
}
