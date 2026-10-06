package me.christianrobert.orapgsync.sequence.service;

import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for extracting sequence metadata from Oracle database.
 */
public class OracleSequenceExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleSequenceExtractor.class);

    /**
     * Extracts all sequences for the specified schemas from Oracle.
     *
     * @param oracleConn Oracle database connection
     * @param schemas List of schema names to extract sequences from
     * @return List of SequenceMetadata objects
     * @throws SQLException if database operations fail
     */
    public static List<SequenceMetadata> extractAllSequences(Connection oracleConn, List<String> schemas) throws SQLException {
        List<SequenceMetadata> sequenceMetadataList = new ArrayList<>();

        for (String schema : schemas) {
            if (UserExcluder.is2BeExclueded(schema)) {
                continue;
            }

            List<SequenceMetadata> schemaSequences = fetchSequencesForSchema(oracleConn, schema);
            sequenceMetadataList.addAll(schemaSequences);

            log.info("Extracted {} sequences from Oracle schema {}", schemaSequences.size(), schema);
        }

        return sequenceMetadataList;
    }

    /**
     * Fetches all sequences for a single schema.
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @return List of SequenceMetadata for the schema
     * @throws SQLException if database operations fail
     */
    private static List<SequenceMetadata> fetchSequencesForSchema(Connection oracleConn, String schema) throws SQLException {
        List<SequenceMetadata> sequences = new ArrayList<>();

        String sql = """
            SELECT sequence_name, min_value, max_value, increment_by,
                   last_number, cache_size, cycle_flag, order_flag
            FROM all_sequences
            WHERE sequence_owner = ?
            ORDER BY sequence_name
            """;

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sequenceName = rs.getString("sequence_name").toLowerCase();
                    SequenceMetadata metadata = new SequenceMetadata(schema.toLowerCase(), sequenceName);

                    // Extract metadata - use BigDecimal then convert to BigInteger to handle large Oracle values
                    BigDecimal minValueDecimal = rs.getBigDecimal("min_value");
                    BigDecimal maxValueDecimal = rs.getBigDecimal("max_value");
                    BigDecimal currentValueDecimal = rs.getBigDecimal("last_number");

                    metadata.setMinValue(minValueDecimal != null ? minValueDecimal.toBigInteger() : null);
                    metadata.setMaxValue(maxValueDecimal != null ? maxValueDecimal.toBigInteger() : null);
                    metadata.setIncrementBy(rs.getInt("increment_by"));
                    metadata.setCurrentValue(currentValueDecimal != null ? currentValueDecimal.toBigInteger() : null);
                    metadata.setCacheSize(rs.getInt("cache_size"));
                    metadata.setCycleFlag(rs.getString("cycle_flag")); // "Y" or "N"
                    metadata.setOrderFlag(rs.getString("order_flag")); // "Y" or "N"

                    sequences.add(metadata);

                    log.debug("Extracted sequence: {}", metadata);
                }
            }
        }

        return sequences;
    }
}
