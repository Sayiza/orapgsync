package me.christianrobert.orapgsync.trigger.service;

import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for extracting trigger metadata from Oracle database.
 * Extracts trigger definitions from ALL_TRIGGERS data dictionary view.
 */
public class OracleTriggerExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleTriggerExtractor.class);

    /**
     * Extracts all triggers for the specified schemas from Oracle.
     *
     * @param oracleConn Oracle database connection
     * @param schemas List of schema names to extract triggers from
     * @return List of TriggerMetadata objects
     * @throws SQLException if database operations fail
     */
    public static List<TriggerMetadata> extractAllTriggers(Connection oracleConn, List<String> schemas)
            throws SQLException {
        List<TriggerMetadata> triggerMetadataList = new ArrayList<>();

        for (String schema : schemas) {
            if (UserExcluder.is2BeExclueded(schema)) {
                continue;
            }

            List<TriggerMetadata> schemaTriggers = extractTriggersForSchema(oracleConn, schema);
            triggerMetadataList.addAll(schemaTriggers);
            log.info("Extracted {} triggers from Oracle schema {}", schemaTriggers.size(), schema);
        }

        return triggerMetadataList;
    }

    /**
     * Extracts all triggers for a single schema.
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @return List of TriggerMetadata for schema
     * @throws SQLException if database operations fail
     */
    private static List<TriggerMetadata> extractTriggersForSchema(Connection oracleConn, String schema)
            throws SQLException {
        List<TriggerMetadata> triggers = new ArrayList<>();

        String sql = """
            SELECT
                owner,
                trigger_name,
                table_owner,
                table_name,
                trigger_type,
                triggering_event,
                status,
                trigger_body,
                when_clause,
                description,
                base_object_type
            FROM all_triggers
            WHERE owner = ?
              AND base_object_type = 'TABLE'
            ORDER BY trigger_name
            """;

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        TriggerMetadata trigger = extractSingleTrigger(rs);
                        triggers.add(trigger);
                    } catch (Exception e) {
                        String triggerName = rs.getString("trigger_name");
                        log.error("Failed to extract trigger: {}.{}", schema, triggerName, e);
                        // Continue with next trigger
                    }
                }
            }
        }

        return triggers;
    }

    /**
     * Extracts a single trigger from the result set.
     *
     * @param rs ResultSet positioned at a trigger row
     * @return TriggerMetadata object
     * @throws SQLException if database operations fail
     */
    private static TriggerMetadata extractSingleTrigger(ResultSet rs) throws SQLException {
        String schema = rs.getString("owner").toLowerCase();
        String triggerName = rs.getString("trigger_name").toLowerCase();
        String tableOwner = rs.getString("table_owner");
        String tableName = rs.getString("table_name").toLowerCase();
        String triggerType = rs.getString("trigger_type");
        String triggeringEvent = rs.getString("triggering_event");
        String status = rs.getString("status");
        String triggerBody = rs.getString("trigger_body");
        String whenClause = rs.getString("when_clause");
        String description = rs.getString("description");

        // Create metadata object
        TriggerMetadata metadata = new TriggerMetadata(schema, triggerName, tableName);

        // Parse trigger type (e.g., "BEFORE EACH ROW" -> type="BEFORE", level="ROW")
        parseTriggerType(triggerType, metadata);

        // Parse triggering event (e.g., "INSERT OR UPDATE")
        metadata.setTriggerEvent(parseTriggerEvent(triggeringEvent));

        // Set status
        metadata.setStatus(status);

        // Clean and set trigger body
        String cleanedBody = cleanTriggerBody(triggerBody, description);
        metadata.setTriggerBody(cleanedBody);

        // Clean and set WHEN clause
        if (whenClause != null && !whenClause.trim().isEmpty()) {
            String cleanedWhen = cleanWhenClause(whenClause);
            metadata.setWhenClause(cleanedWhen);
        }

        log.debug("Extracted trigger: {}.{} on {}.{} ({})",
            schema, triggerName, schema, tableName, metadata.getTriggerType());

        return metadata;
    }

    /**
     * Parses Oracle TRIGGER_TYPE field into type and level.
     *
     * Oracle formats:
     * - "BEFORE STATEMENT"
     * - "BEFORE EACH ROW"
     * - "AFTER STATEMENT"
     * - "AFTER EACH ROW"
     * - "INSTEAD OF"
     * - "COMPOUND"
     *
     * @param triggerType Oracle trigger_type value
     * @param metadata TriggerMetadata to update
     */
    private static void parseTriggerType(String triggerType, TriggerMetadata metadata) {
        if (triggerType == null) {
            log.warn("Trigger type is null for trigger: {}", metadata.getTriggerName());
            return;
        }

        String upperType = triggerType.toUpperCase().trim();

        // Parse timing (BEFORE/AFTER/INSTEAD OF)
        if (upperType.startsWith("BEFORE")) {
            metadata.setTriggerType("BEFORE");
        } else if (upperType.startsWith("AFTER")) {
            metadata.setTriggerType("AFTER");
        } else if (upperType.startsWith("INSTEAD OF")) {
            metadata.setTriggerType("INSTEAD OF");
        } else if (upperType.startsWith("COMPOUND")) {
            metadata.setTriggerType("COMPOUND");
            log.warn("Compound trigger detected: {}. Compound triggers require special handling.",
                metadata.getQualifiedName());
        } else {
            log.warn("Unknown trigger type: {} for trigger: {}", triggerType, metadata.getTriggerName());
            metadata.setTriggerType(upperType);
        }

        // Parse level (ROW/STATEMENT)
        if (upperType.contains("EACH ROW")) {
            metadata.setTriggerLevel("ROW");
        } else if (upperType.contains("STATEMENT")) {
            metadata.setTriggerLevel("STATEMENT");
        } else if (upperType.startsWith("INSTEAD OF")) {
            // INSTEAD OF triggers are always row-level
            metadata.setTriggerLevel("ROW");
        } else {
            // Default to statement level if not specified
            metadata.setTriggerLevel("STATEMENT");
        }
    }

    /**
     * Parses Oracle TRIGGERING_EVENT field.
     *
     * Oracle formats:
     * - "INSERT"
     * - "UPDATE"
     * - "DELETE"
     * - "INSERT OR UPDATE"
     * - "UPDATE OR DELETE"
     * - "INSERT OR UPDATE OR DELETE"
     *
     * @param triggeringEvent Oracle triggering_event value
     * @return Normalized event string
     */
    private static String parseTriggerEvent(String triggeringEvent) {
        if (triggeringEvent == null) {
            return "UNKNOWN";
        }

        // Oracle event strings are already in the correct format
        // Just normalize to uppercase and trim
        return triggeringEvent.toUpperCase().trim();
    }

    /**
     * Cleans the WHEN clause by removing the "WHEN (" prefix and ")" suffix if present.
     *
     * Oracle stores WHEN clauses in various formats:
     * - "NEW.salary > 1000"
     * - "WHEN (NEW.salary > 1000)"
     * - "(NEW.salary > 1000)"
     *
     * We want to store just the condition expression: "NEW.salary > 1000"
     *
     * @param whenClause Oracle when_clause value
     * @return Cleaned condition expression
     */
    private static String cleanWhenClause(String whenClause) {
        if (whenClause == null || whenClause.trim().isEmpty()) {
            return null;
        }

        String cleaned = whenClause.trim();

        // Remove "WHEN (" prefix if present (case-insensitive)
        boolean removedWhenPrefix = false;
        if (cleaned.toUpperCase().startsWith("WHEN (")) {
            cleaned = cleaned.substring(6).trim(); // Remove "WHEN ("
            removedWhenPrefix = true;
        } else if (cleaned.toUpperCase().startsWith("WHEN(")) {
            cleaned = cleaned.substring(5).trim(); // Remove "WHEN("
            removedWhenPrefix = true;
        }

        // If we removed WHEN prefix, also remove the matching closing parenthesis at the end
        if (removedWhenPrefix && cleaned.endsWith(")")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        // Remove leading/trailing parentheses (for cases without WHEN prefix)
        while (cleaned.startsWith("(") && cleaned.endsWith(")") && !removedWhenPrefix) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return cleaned;
    }

    /**
     * Cleans the trigger body by removing CREATE OR REPLACE wrapper if present.
     *
     * Oracle stores trigger bodies in two places:
     * 1. trigger_body - Just the BEGIN...END block
     * 2. description - Full DDL including CREATE OR REPLACE TRIGGER...
     *
     * We prefer trigger_body, but if it's missing or incomplete, we fall back to
     * parsing the description field to extract the body.
     *
     * @param triggerBody Oracle trigger_body value (PL/SQL block)
     * @param description Oracle description value (full DDL)
     * @return Cleaned PL/SQL body
     */
    private static String cleanTriggerBody(String triggerBody, String description) {
        // First, try to use trigger_body if available
        if (triggerBody != null && !triggerBody.trim().isEmpty()) {
            String cleaned = triggerBody.trim();

            // If it starts with BEGIN, it's already clean
            if (cleaned.toUpperCase().startsWith("BEGIN")) {
                return cleaned;
            }

            // If it starts with DECLARE, keep it
            if (cleaned.toUpperCase().startsWith("DECLARE")) {
                return cleaned;
            }

            return cleaned;
        }

        // Fall back to extracting body from description (full DDL)
        if (description != null && !description.trim().isEmpty()) {
            return extractBodyFromDdl(description);
        }

        // If both are empty, return empty string
        return "";
    }

    /**
     * Extracts the trigger body from full DDL statement.
     *
     * Looks for BEGIN keyword and extracts everything from BEGIN to the final END.
     *
     * @param ddl Full CREATE TRIGGER DDL
     * @return Extracted body (BEGIN...END block or DECLARE...BEGIN...END block)
     */
    private static String extractBodyFromDdl(String ddl) {
        if (ddl == null || ddl.trim().isEmpty()) {
            return "";
        }

        String upperDdl = ddl.toUpperCase();

        // Find BEGIN keyword (trigger body starts here)
        int beginIndex = upperDdl.indexOf("BEGIN");
        if (beginIndex == -1) {
            // Try to find DECLARE (some triggers have DECLARE section)
            int declareIndex = upperDdl.indexOf("DECLARE");
            if (declareIndex != -1) {
                // Extract from DECLARE to end
                return ddl.substring(declareIndex).trim();
            }
            // No BEGIN or DECLARE found, return as-is
            return ddl.trim();
        }

        // Extract from BEGIN to end of DDL
        String body = ddl.substring(beginIndex).trim();

        // Remove trailing semicolon and trigger name if present
        // Oracle DDL often ends with: "END trigger_name;"
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }

        return body;
    }
}
