package me.christianrobert.orapgsync.schema.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PostgresSchemaService {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaService.class);

    @Inject
    PostgresConnectionService postgresConnectionService;

    @Inject
    StateService stateService;

    public Map<String, Object> getSchemas() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("Fetching PostgreSQL schemas...");

            if (!postgresConnectionService.isConfigured()) {
                throw new IllegalStateException("PostgreSQL connection not configured");
            }

            List<String> schemas = fetchSchemas();

            // Save to global state
            stateService.updatePostgresSchemaNames(schemas);
            log.debug("Saved {} PostgreSQL schemas to global state", schemas.size());

            result.put("status", "success");
            result.put("schemas", schemas);
            result.put("count", schemas.size());
            result.put("message", "Successfully retrieved PostgreSQL schemas");

            log.info("Found {} PostgreSQL schemas", schemas.size());

        } catch (SQLException e) {
            log.error("SQL error while fetching PostgreSQL schemas", e);
            result.put("status", "error");
            result.put("schemas", new ArrayList<>());
            result.put("count", 0);
            result.put("message", "Database error: " + e.getMessage());
            result.put("errorCode", e.getErrorCode());
            result.put("sqlState", e.getSQLState());
        } catch (Exception e) {
            log.error("Error while fetching PostgreSQL schemas", e);
            result.put("status", "error");
            result.put("schemas", new ArrayList<>());
            result.put("count", 0);
            result.put("message", "Error retrieving schemas: " + e.getMessage());
        }

        return result;
    }

    private List<String> fetchSchemas() throws SQLException {
        List<String> result = new ArrayList<>();

        try (Connection connection = postgresConnectionService.getConnection()) {
            // Query PostgreSQL system catalogs for schema names
            String sql = """
                SELECT schema_name
                FROM information_schema.schemata
                WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
                ORDER BY schema_name
                """;

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    String schemaName = rs.getString("schema_name");
                    // Apply same exclusion logic as Oracle for consistency
                    if (!UserExcluder.is2BeExclueded(schemaName.toUpperCase())) {
                        result.add(schemaName);
                    } else {
                        log.debug("Excluding PostgreSQL schema: {}", schemaName);
                    }
                }
            }
        }

        log.debug("Extracted {} non-excluded schemas from PostgreSQL", result.size());
        return result;
    }

    public boolean hasSchemas() {
        try {
            List<String> schemas = fetchSchemas();
            return !schemas.isEmpty();
        } catch (Exception e) {
            log.warn("Could not check for PostgreSQL schemas", e);
            return false;
        }
    }
}