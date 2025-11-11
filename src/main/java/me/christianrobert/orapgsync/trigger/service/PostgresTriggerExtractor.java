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
 * Service for extracting trigger metadata from PostgreSQL database.
 * Extracts trigger definitions from PostgreSQL system catalogs (pg_trigger, pg_proc, etc.).
 */
public class PostgresTriggerExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgresTriggerExtractor.class);

    /**
     * Extracts all triggers for the specified schemas from PostgreSQL.
     *
     * @param pgConnection PostgreSQL database connection
     * @param schemas List of schema names to extract triggers from
     * @return List of TriggerMetadata objects
     * @throws SQLException if database operations fail
     */
    public static List<TriggerMetadata> extractAllTriggers(Connection pgConnection, List<String> schemas)
            throws SQLException {
        List<TriggerMetadata> triggerMetadataList = new ArrayList<>();

        for (String schema : schemas) {
            if (UserExcluder.is2BeExclueded(schema)) {
                continue;
            }

            List<TriggerMetadata> schemaTriggers = extractTriggersForSchema(pgConnection, schema);
            triggerMetadataList.addAll(schemaTriggers);
            log.info("Extracted {} triggers from PostgreSQL schema {}", schemaTriggers.size(), schema);
        }

        return triggerMetadataList;
    }

    /**
     * Extracts all triggers for a single schema.
     *
     * @param pgConnection PostgreSQL database connection
     * @param schema Schema name
     * @return List of TriggerMetadata for schema
     * @throws SQLException if database operations fail
     */
    private static List<TriggerMetadata> extractTriggersForSchema(Connection pgConnection, String schema)
            throws SQLException {
        List<TriggerMetadata> triggers = new ArrayList<>();

        // PostgreSQL stores trigger metadata in pg_trigger table
        // We need to join with pg_class (for table info), pg_namespace (for schema),
        // and pg_proc (for function source code)
        String sql = """
            SELECT
                n.nspname AS schema_name,
                t.tgname AS trigger_name,
                c.relname AS table_name,
                t.tgtype AS trigger_type_bits,
                t.tgenabled AS trigger_enabled,
                pg_get_triggerdef(t.oid) AS trigger_definition,
                p.proname AS function_name,
                pg_get_functiondef(p.oid) AS function_definition
            FROM pg_trigger t
                JOIN pg_class c ON t.tgrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                LEFT JOIN pg_proc p ON t.tgfoid = p.oid
            WHERE n.nspname = ?
              AND NOT t.tgisinternal
            ORDER BY t.tgname
            """;

        try (PreparedStatement ps = pgConnection.prepareStatement(sql)) {
            ps.setString(1, schema);

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
        String schema = rs.getString("schema_name");
        String triggerName = rs.getString("trigger_name");
        String tableName = rs.getString("table_name");
        int tgtype = rs.getInt("trigger_type_bits");
        String tgenabled = rs.getString("trigger_enabled");
        String triggerDef = rs.getString("trigger_definition");
        String functionName = rs.getString("function_name");
        String functionDef = rs.getString("function_definition");

        // Create metadata object
        TriggerMetadata metadata = new TriggerMetadata(schema, triggerName, tableName);

        // Decode trigger type from bitmask
        decodeTriggerType(tgtype, metadata);

        // Decode trigger event from bitmask
        decodeTriggerEvent(tgtype, metadata);

        // Decode trigger level from bitmask
        decodeTriggerLevel(tgtype, metadata);

        // Set status based on tgenabled
        String status = decodeTriggerStatus(tgenabled);
        metadata.setStatus(status);

        // Extract WHEN clause from trigger definition if present
        String whenClause = extractWhenClause(triggerDef);
        if (whenClause != null) {
            metadata.setWhenClause(whenClause);
        }

        // Store PostgreSQL DDL for manual review
        // This enables the frontend to display the actual DDL for inspection
        if (functionDef != null && !functionDef.isEmpty()) {
            metadata.setPostgresFunctionDdl(functionDef);
        }
        if (triggerDef != null && !triggerDef.isEmpty()) {
            metadata.setPostgresTriggerDdl(triggerDef);
        }

        log.debug("Extracted trigger: {}.{} on {}.{} ({}) [function: {}]",
            schema, triggerName, schema, tableName, metadata.getTriggerType(), functionName);

        return metadata;
    }

    /**
     * Decodes PostgreSQL trigger type bits to determine timing.
     *
     * PostgreSQL tgtype bit meanings:
     * - Bit 0 (1): ROW level (if not set, STATEMENT level)
     * - Bit 1 (2): BEFORE
     * - Bit 2 (4): INSERT
     * - Bit 3 (8): DELETE
     * - Bit 4 (16): UPDATE
     * - Bit 6 (64): INSTEAD OF
     *
     * @param tgtype PostgreSQL trigger type bitmask
     * @param metadata TriggerMetadata to update
     */
    private static void decodeTriggerType(int tgtype, TriggerMetadata metadata) {
        // Check bit 6 for INSTEAD OF (value 64)
        if ((tgtype & 64) != 0) {
            metadata.setTriggerType("INSTEAD OF");
        }
        // Check bit 1 for BEFORE (value 2)
        else if ((tgtype & 2) != 0) {
            metadata.setTriggerType("BEFORE");
        }
        // Otherwise it's AFTER
        else {
            metadata.setTriggerType("AFTER");
        }
    }

    /**
     * Decodes PostgreSQL trigger event bits.
     *
     * @param tgtype PostgreSQL trigger type bitmask
     * @param metadata TriggerMetadata to update
     */
    private static void decodeTriggerEvent(int tgtype, TriggerMetadata metadata) {
        List<String> events = new ArrayList<>();

        // Check bit 2 for INSERT (value 4)
        if ((tgtype & 4) != 0) {
            events.add("INSERT");
        }

        // Check bit 3 for DELETE (value 8)
        if ((tgtype & 8) != 0) {
            events.add("DELETE");
        }

        // Check bit 4 for UPDATE (value 16)
        if ((tgtype & 16) != 0) {
            events.add("UPDATE");
        }

        // Join events with " OR "
        if (events.isEmpty()) {
            metadata.setTriggerEvent("UNKNOWN");
        } else {
            metadata.setTriggerEvent(String.join(" OR ", events));
        }
    }

    /**
     * Decodes PostgreSQL trigger level from bits.
     *
     * @param tgtype PostgreSQL trigger type bitmask
     * @param metadata TriggerMetadata to update
     */
    private static void decodeTriggerLevel(int tgtype, TriggerMetadata metadata) {
        // Check bit 0 for ROW level (value 1)
        if ((tgtype & 1) != 0) {
            metadata.setTriggerLevel("ROW");
        } else {
            metadata.setTriggerLevel("STATEMENT");
        }
    }

    /**
     * Decodes PostgreSQL trigger status.
     *
     * PostgreSQL tgenabled values:
     * - 'O' = Enabled (origin)
     * - 'D' = Disabled
     * - 'R' = Enabled (replica)
     * - 'A' = Enabled (always)
     *
     * @param tgenabled PostgreSQL tgenabled value
     * @return Status string
     */
    private static String decodeTriggerStatus(String tgenabled) {
        if (tgenabled == null || tgenabled.isEmpty()) {
            return "UNKNOWN";
        }

        switch (tgenabled.charAt(0)) {
            case 'O':
            case 'R':
            case 'A':
                return "ENABLED";
            case 'D':
                return "DISABLED";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Extracts WHEN clause from PostgreSQL trigger definition.
     *
     * PostgreSQL pg_get_triggerdef() returns something like:
     * "CREATE TRIGGER trigger_name ... WHEN (condition) EXECUTE FUNCTION ..."
     *
     * We need to extract the condition part.
     *
     * @param triggerDef Full trigger definition
     * @return WHEN clause condition, or null if no WHEN clause
     */
    private static String extractWhenClause(String triggerDef) {
        if (triggerDef == null || triggerDef.isEmpty()) {
            return null;
        }

        // Look for "WHEN (" in the trigger definition
        String upperDef = triggerDef.toUpperCase();
        int whenIndex = upperDef.indexOf("WHEN (");

        if (whenIndex == -1) {
            return null;
        }

        // Find the matching closing parenthesis
        int startParen = triggerDef.indexOf('(', whenIndex + 5);
        if (startParen == -1) {
            return null;
        }

        // Find matching closing parenthesis
        int depth = 1;
        int i = startParen + 1;
        while (i < triggerDef.length() && depth > 0) {
            char c = triggerDef.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }

        if (depth == 0) {
            // Extract the condition (between parentheses)
            return triggerDef.substring(startParen + 1, i - 1).trim();
        }

        return null;
    }
}
