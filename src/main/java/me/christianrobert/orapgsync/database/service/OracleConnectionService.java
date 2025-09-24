package me.christianrobert.orapgsync.database.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.config.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class OracleConnectionService {

    private static final Logger log = LoggerFactory.getLogger(OracleConnectionService.class);

    @Inject
    ConfigService configService;

    public Map<String, Object> testConnection() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("Testing Oracle database connection...");

            String url = configService.getConfigValueAsString("oracle.url");
            String user = configService.getConfigValueAsString("oracle.user");
            String password = configService.getConfigValueAsString("oracle.password");

            if (url == null || user == null || password == null) {
                throw new IllegalStateException("Oracle connection parameters not configured");
            }

            long startTime = System.currentTimeMillis();

            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                DatabaseMetaData metaData = connection.getMetaData();

                long connectionTime = System.currentTimeMillis() - startTime;

                result.put("status", "success");
                result.put("connected", true);
                result.put("message", "Successfully connected to Oracle database");
                result.put("connectionTimeMs", connectionTime);
                result.put("databaseProductName", metaData.getDatabaseProductName());
                result.put("databaseProductVersion", metaData.getDatabaseProductVersion());
                result.put("driverName", metaData.getDriverName());
                result.put("driverVersion", metaData.getDriverVersion());
                result.put("url", metaData.getURL());
                result.put("userName", metaData.getUserName());

                log.info("Oracle connection test successful - Connected in {}ms", connectionTime);
            }

        } catch (SQLException e) {
            log.error("Oracle connection test failed with SQL error", e);
            result.put("status", "error");
            result.put("connected", false);
            result.put("message", "Database connection failed: " + e.getMessage());
            result.put("errorCode", e.getErrorCode());
            result.put("sqlState", e.getSQLState());
        } catch (Exception e) {
            log.error("Oracle connection test failed with error", e);
            result.put("status", "error");
            result.put("connected", false);
            result.put("message", "Connection test failed: " + e.getMessage());
        }

        return result;
    }

    public Connection getConnection() throws SQLException {
        String url = configService.getConfigValueAsString("oracle.url");
        String user = configService.getConfigValueAsString("oracle.user");
        String password = configService.getConfigValueAsString("oracle.password");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException("Oracle connection parameters not configured");
        }

        log.debug("Creating Oracle database connection to: {}", url);
        return DriverManager.getConnection(url, user, password);
    }

    public boolean isConfigured() {
        String url = configService.getConfigValueAsString("oracle.url");
        String user = configService.getConfigValueAsString("oracle.user");
        String password = configService.getConfigValueAsString("oracle.password");

        return url != null && !url.trim().isEmpty() &&
               user != null && !user.trim().isEmpty() &&
               password != null && !password.trim().isEmpty();
    }
}