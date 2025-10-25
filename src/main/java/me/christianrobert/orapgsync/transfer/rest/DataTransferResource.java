package me.christianrobert.orapgsync.transfer.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for data transfer operations.
 * Handles row count extraction, synonym extraction, and bulk data transfer.
 */
@ApplicationScoped
@Path("/api/transfer")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataTransferResource {

    private static final Logger log = LoggerFactory.getLogger(DataTransferResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/row-counts")
    public Response extractOracleRowCounts() {
        return startJob("ORACLE", "ROW_COUNT", "Oracle row count extraction");
    }

    @POST
    @Path("/postgres/row-counts")
    public Response extractPostgresRowCounts() {
        return startJob("POSTGRES", "ROW_COUNT", "PostgreSQL row count extraction");
    }

    @POST
    @Path("/oracle/synonyms")
    public Response extractOracleSynonyms() {
        return startJob("ORACLE", "SYNONYM", "Oracle synonym extraction");
    }

    @POST
    @Path("/postgres/execute")
    public Response executeDataTransfer() {
        return startJob("POSTGRES", "DATA_TRANSFER", "Data transfer from Oracle to PostgreSQL");
    }

    /**
     * Generic method to start any data transfer-related job.
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
     * Generate summary for row count extraction results.
     */
    public static Map<String, Object> generateRowCountSummary(List<RowCountMetadata> rowCounts) {
        Map<String, Integer> schemaTableCounts = new HashMap<>();
        Map<String, Long> schemaRowCounts = new HashMap<>();
        Map<String, Object> tableDetails = new HashMap<>();

        long totalRows = 0;
        int errorTables = 0;

        for (RowCountMetadata rowCount : rowCounts) {
            String schema = rowCount.getSchema();
            schemaTableCounts.put(schema, schemaTableCounts.getOrDefault(schema, 0) + 1);

            if (rowCount.getRowCount() >= 0) {
                totalRows += rowCount.getRowCount();
                schemaRowCounts.put(schema, schemaRowCounts.getOrDefault(schema, 0L) + rowCount.getRowCount());
            } else {
                errorTables++;
            }

            // Store individual table row count info
            Map<String, Object> tableInfo = Map.of(
                    "schema", rowCount.getSchema(),
                    "tableName", rowCount.getTableName(),
                    "rowCount", rowCount.getRowCount(),
                    "extractionTimestamp", rowCount.getExtractionTimestamp()
            );

            String tableKey = schema + "." + rowCount.getTableName();
            tableDetails.put(tableKey, tableInfo);
        }

        return Map.of(
                "totalTables", rowCounts.size(),
                "totalRows", totalRows,
                "errorTables", errorTables,
                "schemaTableCounts", schemaTableCounts,
                "schemaRowCounts", schemaRowCounts,
                "tables", tableDetails
        );
    }

    /**
     * Generate summary for synonym extraction results.
     */
    public static Map<String, Object> generateSynonymSummary(List<?> synonyms) {
        return Map.of(
                "totalSynonyms", synonyms.size(),
                "message", String.format("Extraction completed: %d synonyms found", synonyms.size())
        );
    }

    /**
     * Generate summary for data transfer results.
     */
    public static Map<String, Object> generateDataTransferSummary(DataTransferResult transferResult) {
        Map<String, Object> transferredDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Transferred tables details
        for (String tableName : transferResult.getTransferredTables()) {
            transferredDetails.put(tableName, Map.of(
                    "tableName", tableName,
                    "status", "transferred",
                    "timestamp", transferResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped tables details
        for (String tableName : transferResult.getSkippedTables()) {
            skippedDetails.put(tableName, Map.of(
                    "tableName", tableName,
                    "status", "skipped",
                    "reason", "no data or already transferred"
            ));
        }

        // Error details
        for (DataTransferResult.DataTransferError error : transferResult.getErrors()) {
            errorDetails.put(error.getTableName(), Map.of(
                    "tableName", error.getTableName(),
                    "status", "error",
                    "error", error.getErrorMessage()
            ));
        }

        return Map.of(
                "totalProcessed", transferResult.getTotalProcessed(),
                "transferredCount", transferResult.getTransferredCount(),
                "skippedCount", transferResult.getSkippedCount(),
                "errorCount", transferResult.getErrorCount(),
                "totalRowsTransferred", transferResult.getTotalRowsTransferred(),
                "isSuccessful", transferResult.isSuccessful(),
                "executionTimestamp", transferResult.getExecutionTimestamp(),
                "transferredTables", transferredDetails,
                "skippedTables", skippedDetails,
                "errors", errorDetails
        );
    }
}
