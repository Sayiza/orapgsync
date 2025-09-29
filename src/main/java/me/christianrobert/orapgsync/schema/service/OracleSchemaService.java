package me.christianrobert.orapgsync.schema.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
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
public class OracleSchemaService {

    private static final Logger log = LoggerFactory.getLogger(OracleSchemaService.class);

    @Inject
    OracleConnectionService oracleConnectionService;

    @Inject
    StateService stateService;

    public Map<String, Object> getSchemas() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("Fetching Oracle schemas...");

            if (!oracleConnectionService.isConfigured()) {
                throw new IllegalStateException("Oracle connection not configured");
            }

            List<String> schemas = fetchSchemas();

            // Save to global state
            stateService.updateOracleSchemaNames(schemas);
            log.debug("Saved {} Oracle schemas to global state", schemas.size());

            result.put("status", "success");
            result.put("schemas", schemas);
            result.put("count", schemas.size());
            result.put("message", "Successfully retrieved Oracle schemas");

            log.info("Found {} Oracle schemas", schemas.size());

        } catch (SQLException e) {
            log.error("SQL error while fetching Oracle schemas", e);
            result.put("status", "error");
            result.put("schemas", new ArrayList<>());
            result.put("count", 0);
            result.put("message", "Database error: " + e.getMessage());
            result.put("errorCode", e.getErrorCode());
            result.put("sqlState", e.getSQLState());
        } catch (Exception e) {
            log.error("Error while fetching Oracle schemas", e);
            result.put("status", "error");
            result.put("schemas", new ArrayList<>());
            result.put("count", 0);
            result.put("message", "Error retrieving schemas: " + e.getMessage());
        }

        return result;
    }

    private List<String> fetchSchemas() throws SQLException {
        List<String> result = new ArrayList<>();

        try (Connection connection = oracleConnectionService.getConnection()) {
            String sql = "SELECT username FROM all_users ORDER BY username";

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    String username = rs.getString("username");
                    if (!UserExcluder.is2BeExclueded(username)) {
                        result.add(username);
                    } else {
                        log.debug("Excluding Oracle schema: {}", username);
                    }
                }
            }
        }

        log.debug("Extracted {} non-excluded schemas from Oracle", result.size());
        return result;
    }

    public boolean hasSchemas() {
        try {
            List<String> schemas = fetchSchemas();
            return !schemas.isEmpty();
        } catch (Exception e) {
            log.warn("Could not check for Oracle schemas", e);
            return false;
        }
    }
}