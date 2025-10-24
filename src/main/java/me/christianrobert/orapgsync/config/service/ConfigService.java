package me.christianrobert.orapgsync.config.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final Map<String, Object> configuration = new ConcurrentHashMap<>();

    public ConfigService() {
        initializeDefaultConfiguration();
    }

    private void initializeDefaultConfiguration() {
        configuration.put("do.all-schemas", false);
        configuration.put("do.only-test-schema", "USER_ROBERT");
        configuration.put("oracle.url", "jdbc:oracle:thin:@localhost:1521:sid");
        configuration.put("oracle.user", "sys");
        configuration.put("oracle.password", "xxx");
        configuration.put("java.generated-package-name", "me.christianrobert.ora2pgsync.autogen");
        configuration.put("path.target-project-java", "/src/main/java");
        configuration.put("path.target-project-resources", "/src/main/resources");
        configuration.put("path.target-project-postgre", "/postgre/autoddl");
        configuration.put("postgre.url", "jdbc:postgresql://localhost:5432/postgres");
        configuration.put("postgre.username", "postgres");
        configuration.put("postgre.password", "xxx");

        log.info("Configuration service initialized with default values");
    }

    public Map<String, Object> getAllConfiguration() {
        return new HashMap<>(configuration);
    }

    public Object getConfigValue(String key) {
        return configuration.get(key);
    }

    public String getConfigValueAsString(String key) {
        Object value = configuration.get(key);
        return value != null ? value.toString() : null;
    }

    public Boolean getConfigValueAsBoolean(String key) {
        Object value = configuration.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }

    /**
     * Gets a configuration value as a list of strings.
     * Supports comma-separated values: "SCHEMA1,SCHEMA2,SCHEMA3"
     * Trims whitespace and filters out empty strings.
     *
     * @param key Configuration key
     * @return List of strings, or empty list if value is null/empty
     */
    public List<String> getConfigValueAsStringList(String key) {
        String value = getConfigValueAsString(key);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public void updateConfiguration(Map<String, Object> newConfig) {
        log.info("Updating configuration with {} entries", newConfig.size());

        newConfig.forEach((key, value) -> {
            Object oldValue = configuration.put(key, value);
            if (log.isDebugEnabled()) {
                log.debug("Config updated: {} = {} (was: {})", key, value, oldValue);
            }
        });

        log.info("Configuration updated successfully");
    }

    public void setConfigValue(String key, Object value) {
        Object oldValue = configuration.put(key, value);
        log.debug("Config value set: {} = {} (was: {})", key, value, oldValue);
    }

    public boolean hasConfigKey(String key) {
        return configuration.containsKey(key);
    }

    public void resetToDefaults() {
        log.info("Resetting configuration to defaults");
        configuration.clear();
        initializeDefaultConfiguration();
    }
}