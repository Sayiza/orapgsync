package me.christianrobert.orapgsync.objectdatatype.job;

import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.table.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class OracleObjectDataTypeExtractionJob implements Job<List<ObjectDataTypeMetaData>> {

    private static final Logger log = LoggerFactory.getLogger(OracleObjectDataTypeExtractionJob.class);

    private final String jobId;

    private StateService stateService;
    private OracleConnectionService oracleConnectionService;
    private ConfigService configService;

    public OracleObjectDataTypeExtractionJob() {
        this.jobId = "oracle-object-datatype-extraction-" + UUID.randomUUID().toString();
    }

    public void setStateService(StateService stateService) {
        this.stateService = stateService;
    }

    public void setOracleConnectionService(OracleConnectionService oracleConnectionService) {
        this.oracleConnectionService = oracleConnectionService;
    }

    public void setConfigService(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public String getJobType() {
        return "ORACLE_OBJECT_DATATYPE_EXTRACTION";
    }

    @Override
    public String getDescription() {
        return "Extract object data types from Oracle database and store in global state";
    }

    @Override
    public CompletableFuture<List<ObjectDataTypeMetaData>> execute(Consumer<JobProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performExtraction(progressCallback);
            } catch (Exception e) {
                log.error("Object data type extraction failed", e);
                throw new RuntimeException("Object data type extraction failed: " + e.getMessage(), e);
            }
        });
    }

    private List<ObjectDataTypeMetaData> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting Oracle object data type extraction");

        if (!oracleConnectionService.isConfigured()) {
            updateProgress(progressCallback, -1, "Failed", "Oracle connection not configured");
            throw new IllegalStateException("Oracle connection not configured");
        }

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        try (Connection connection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            // Check configuration settings
            boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
            String testSchema = configService.getConfigValueAsString("do.only-test-schema");

            updateProgress(progressCallback, 15, "Building query", "Determining schemas to process based on configuration");

            String sql;
            if (doAllSchemas) {
                sql = """
                    SELECT x.owner, x.object_name
                    FROM all_objects x
                    WHERE x.object_type = 'TYPE'
                      AND x.owner NOT IN ('SYS', 'SYSTEM', 'CTXSYS', 'DBSNMP', 'EXFSYS', 'LBACSYS', 'MDSYS', 'MGMT_VIEW', 'OLAPSYS', 'ORDDATA', 'OWBSYS', 'ORDPLUGINS', 'ORDSYS', 'OUTLN', 'SI_INFORMTN_SCHEMA', 'SYS', 'SYSMAN', 'SYSTEM', 'TSMSYS', 'WK_TEST', 'WKPROXY', 'WMSYS', 'XDB', 'APEX_040000', 'APEX_PUBLIC_USER', 'DIP', 'FLOWS_30000', 'FLOWS_FILES', 'MDDATA', 'ORACLE_OCM', 'XS$NULL', 'SPATIAL_CSW_ADMIN_USR', 'SPATIAL_WFS_ADMIN_USR', 'PUBLIC')
                      AND x.object_name IN (
                        SELECT DISTINCT y.data_type
                        FROM all_tab_cols y
                        WHERE y.data_type_owner IS NOT NULL
                          AND y.data_type_owner NOT IN ('SYS', 'SYSTEM', 'CTXSYS', 'DBSNMP', 'EXFSYS', 'LBACSYS', 'MDSYS', 'MGMT_VIEW', 'OLAPSYS', 'ORDDATA', 'OWBSYS', 'ORDPLUGINS', 'ORDSYS', 'OUTLN', 'SI_INFORMTN_SCHEMA', 'SYS', 'SYSMAN', 'SYSTEM', 'TSMSYS', 'WK_TEST', 'WKPROXY', 'WMSYS', 'XDB', 'APEX_040000', 'APEX_PUBLIC_USER', 'DIP', 'FLOWS_30000', 'FLOWS_FILES', 'MDDATA', 'ORACLE_OCM', 'XS$NULL', 'SPATIAL_CSW_ADMIN_USR', 'SPATIAL_WFS_ADMIN_USR', 'PUBLIC')
                      )
                    ORDER BY x.owner, x.object_name
                    """;
            } else {
                if (testSchema == null || testSchema.trim().isEmpty()) {
                    updateProgress(progressCallback, -1, "Configuration error", "Test schema not configured but do.all-schemas is false");
                    throw new IllegalStateException("Test schema not configured but do.all-schemas is false");
                }
                sql = """
                    SELECT x.owner, x.object_name
                    FROM all_objects x
                    WHERE x.object_type = 'TYPE'
                      AND x.owner = ?
                      AND x.object_name IN (
                        SELECT DISTINCT y.data_type
                        FROM all_tab_cols y
                        WHERE y.data_type_owner = ?
                      )
                    ORDER BY x.owner, x.object_name
                    """;
            }

            updateProgress(progressCallback, 20, "Executing query", "Fetching object data types from Oracle");

            Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema = new HashMap<>();

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                if (!doAllSchemas && testSchema != null) {
                    stmt.setString(1, testSchema.toUpperCase());
                    stmt.setString(2, testSchema.toUpperCase());
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    int processedCount = 0;
                    updateProgress(progressCallback, 30, "Processing results", "Extracting object data type metadata");

                    while (rs.next()) {
                        String owner = rs.getString("owner");
                        String objectName = rs.getString("object_name");

                        // Apply UserExcluder logic for consistency
                        if (UserExcluder.is2BeExclueded(owner, "TYPE")) {
                            log.debug("Excluding Oracle schema for object data types: {}", owner);
                            continue;
                        }

                        // Create object data type metadata with empty variables list (since we're just getting basic info)
                        ObjectDataTypeMetaData objectDataType = createObjectDataTypeMetaData(owner, objectName, new ArrayList<>());
                        objectDataTypesBySchema.computeIfAbsent(owner, k -> new ArrayList<>()).add(objectDataType);

                        processedCount++;
                        if (processedCount % 10 == 0) {
                            int progress = 30 + (processedCount * 50 / Math.max(1, processedCount + 50)); // Estimate progress
                            updateProgress(progressCallback, progress, "Processing results",
                                String.format("Processed %d object data types", processedCount));
                        }
                    }

                    updateProgress(progressCallback, 80, "Processing completed",
                        String.format("Found %d object data types", processedCount));
                }
            }

            // Save to global state - flatten the map to a single list
            List<ObjectDataTypeMetaData> allObjectDataTypes = objectDataTypesBySchema.values().stream()
                    .flatMap(List::stream)
                    .toList();

            updateProgress(progressCallback, 90, "Storing results", "Saving object data types to global state");
            stateService.updateOracleObjectDataTypeMetaData(allObjectDataTypes);

            updateProgress(progressCallback, 95, "Preparing summary", "Generating extraction summary");

            String summaryMessage = String.format(
                "Extraction completed: %d object data types from %d schemas",
                allObjectDataTypes.size(),
                objectDataTypesBySchema.size());

            updateProgress(progressCallback, 100, "Completed", summaryMessage);

            log.info("Object data type extraction completed successfully: {} object data types from {} schemas",
                    allObjectDataTypes.size(), objectDataTypesBySchema.size());

            return allObjectDataTypes;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Object data type extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private ObjectDataTypeMetaData createObjectDataTypeMetaData(String schema, String typeName, List<ObjectDataTypeVariable> variables) {
        // Using reflection to create ObjectDataTypeMetaData since it lacks setters
        // This is a workaround - ideally the model should have setters or a builder
        try {
            ObjectDataTypeMetaData objectDataType = new ObjectDataTypeMetaData();
            java.lang.reflect.Field nameField = ObjectDataTypeMetaData.class.getDeclaredField("name");
            java.lang.reflect.Field schemaField = ObjectDataTypeMetaData.class.getDeclaredField("schema");
            java.lang.reflect.Field variablesField = ObjectDataTypeMetaData.class.getDeclaredField("variables");

            nameField.setAccessible(true);
            schemaField.setAccessible(true);
            variablesField.setAccessible(true);

            nameField.set(objectDataType, typeName);
            schemaField.set(objectDataType, schema);
            variablesField.set(objectDataType, new ArrayList<>(variables));

            return objectDataType;
        } catch (Exception e) {
            log.error("Failed to create ObjectDataTypeMetaData for {}.{}", schema, typeName, e);
            throw new RuntimeException("Failed to create ObjectDataTypeMetaData", e);
        }
    }

}