package me.christianrobert.orapgsync.table.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for table-related operations.
 * Handles table metadata extraction from Oracle/PostgreSQL and table creation in PostgreSQL.
 */
@ApplicationScoped
@Path("/api/tables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TableResource {

    private static final Logger log = LoggerFactory.getLogger(TableResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleTables() {
        return startJob("ORACLE", "TABLE_METADATA", "Oracle table metadata extraction");
    }

    @POST
    @Path("/postgres/extract")
    public Response extractPostgresTables() {
        return startJob("POSTGRES", "TABLE_METADATA", "PostgreSQL table metadata extraction");
    }

    @POST
    @Path("/postgres/create")
    public Response createPostgresTables() {
        return startJob("POSTGRES", "TABLE_CREATION", "PostgreSQL table creation");
    }

    /**
     * Generic method to start any table-related job.
     */
    private Response startJob(String database, String operationType, String friendlyName) {
        log.info("Starting {} job via REST API", friendlyName);

        try {
            Job<?> job = jobRegistry.createJob(database, operationType)
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("No job available for %s %s operation", database, operationType)));

            String jobId = jobService.submitJob(job);

            Map<String, Object> result = Map.of(
                    "status", "success",
                    "jobId", jobId,
                    "message", friendlyName + " job started successfully"
            );

            log.info("{} job started with ID: {}", friendlyName, jobId);
            return Response.ok(result).build();

        } catch (Exception e) {
            log.error("Failed to start {} job", friendlyName, e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "message", "Failed to start " + friendlyName + ": " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    /**
     * Generate summary for table metadata extraction results.
     */
    public static Map<String, Object> generateTableMetadataSummary(List<TableMetadata> tableMetadata) {
        Map<String, Integer> schemaTableCounts = new HashMap<>();
        Map<String, Object> tableDetails = new HashMap<>();

        int totalColumns = 0;
        int totalConstraints = 0; // Note: Constraints are extracted but NOT created (Step A: tables only)

        for (TableMetadata table : tableMetadata) {
            String schema = table.getSchema();
            schemaTableCounts.put(schema, schemaTableCounts.getOrDefault(schema, 0) + 1);

            totalColumns += table.getColumns().size();
            totalConstraints += table.getConstraints().size(); // Tracked for future Step C

            // Store individual table info
            // Note: constraintCount is the number of constraints EXTRACTED, not CREATED
            Map<String, Object> tableInfo = Map.of(
                    "schema", table.getSchema(),
                    "name", table.getTableName(),
                    "columnCount", table.getColumns().size()
                    // Intentionally NOT including constraintCount to avoid confusion
                    // Constraints will be created in Step C (after data transfer)
            );

            String tableKey = schema + "." + table.getTableName();
            tableDetails.put(tableKey, tableInfo);
        }

        return Map.of(
                "totalTables", tableMetadata.size(),
                "totalColumns", totalColumns,
                // Note: totalConstraints represents extracted constraints, not created ones
                // They will be created in a future step (Step C) after data transfer
                "totalConstraintsExtracted", totalConstraints,
                "schemaTableCounts", schemaTableCounts,
                "tables", tableDetails
        );
    }

    /**
     * Generate summary for table creation results.
     */
    public static Map<String, Object> generateTableCreationSummary(TableCreationResult tableResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();
        Map<String, Object> unmappedDefaultDetails = new HashMap<>();

        // Created tables details
        for (String tableName : tableResult.getCreatedTables()) {
            createdDetails.put(tableName, Map.of(
                    "tableName", tableName,
                    "status", "created",
                    "timestamp", tableResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped tables details
        for (String tableName : tableResult.getSkippedTables()) {
            skippedDetails.put(tableName, Map.of(
                    "tableName", tableName,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (TableCreationResult.TableCreationError error : tableResult.getErrors()) {
            errorDetails.put(error.getTableName(), Map.of(
                    "tableName", error.getTableName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        // Unmapped default value warnings
        for (TableCreationResult.UnmappedDefaultWarning warning : tableResult.getUnmappedDefaults()) {
            String key = warning.getTableName() + "." + warning.getColumnName();
            unmappedDefaultDetails.put(key, Map.of(
                    "tableName", warning.getTableName(),
                    "columnName", warning.getColumnName(),
                    "oracleDefault", warning.getOracleDefault(),
                    "note", warning.getNote()
            ));
        }

        // Use a Map builder that accepts more than 10 entries
        Map<String, Object> result = new HashMap<>();
        result.put("totalProcessed", tableResult.getTotalProcessed());
        result.put("createdCount", tableResult.getCreatedCount());
        result.put("skippedCount", tableResult.getSkippedCount());
        result.put("errorCount", tableResult.getErrorCount());
        result.put("unmappedDefaultCount", tableResult.getUnmappedDefaultCount());
        result.put("isSuccessful", tableResult.isSuccessful());
        result.put("executionTimestamp", tableResult.getExecutionTimestamp());
        result.put("createdTables", createdDetails);
        result.put("skippedTables", skippedDetails);
        result.put("errors", errorDetails);
        result.put("unmappedDefaults", unmappedDefaultDetails);

        return result;
    }
}
