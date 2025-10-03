package me.christianrobert.orapgsync.core.job.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.schema.SchemaCreationResult;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.JobStatus;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JobResource {

    private static final Logger log = LoggerFactory.getLogger(JobResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/schemas/oracle/extract")
    public Response startOracleSchemaExtraction() {
        return startExtractionJob("ORACLE", "SCHEMA", "Oracle schema extraction");
    }

    @POST
    @Path("/schemas/postgres/extract")
    public Response startPostgresSchemaExtraction() {
        return startExtractionJob("POSTGRES", "SCHEMA", "PostgreSQL schema extraction");
    }

    @POST
    @Path("/tables/oracle/extract")
    public Response startOracleTableMetadataExtraction() {
        return startExtractionJob("ORACLE", "TABLE_METADATA", "Oracle table metadata extraction");
    }

    /**
     * Generic method to start any job (extraction or write).
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
     * @deprecated Use {@link #startJob(String, String, String)} instead
     */
    @Deprecated
    private Response startExtractionJob(String sourceDatabase, String extractionType, String friendlyName) {
        return startJob(sourceDatabase, extractionType, friendlyName);
    }

    @GET
    @Path("/{jobId}/status")
    public Response getJobStatus(@PathParam("jobId") String jobId) {
        log.debug("Getting job status for: {}", jobId);

        try {
            JobService.JobExecution<?> execution = jobService.getJobExecution(jobId);

            if (execution == null) {
                Map<String, Object> errorResult = Map.of(
                        "status", "error",
                        "message", "Job not found: " + jobId
                );
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(errorResult)
                        .build();
            }

            JobStatus status = execution.getStatus();
            JobProgress progress = execution.getProgress();

            Map<String, Object> result = new HashMap<>();
            result.put("jobId", jobId);
            result.put("jobType", execution.getJob().getJobType());
            result.put("status", status.name());
            result.put("isComplete", jobService.isJobComplete(jobId));

            if (progress != null) {
                Map<String, Object> progressInfo = Map.of(
                        "percentage", progress.getPercentage(),
                        "currentTask", progress.getCurrentTask(),
                        "details", progress.getDetails(),
                        "lastUpdated", progress.getLastUpdated().toString()
                );
                result.put("progress", progressInfo);
            }

            if (status == JobStatus.FAILED) {
                Exception error = jobService.getJobError(jobId);
                if (error != null) {
                    result.put("error", error.getMessage());
                }
            }

            if (execution.getStartTime() != null) {
                result.put("startTime", execution.getStartTime().toString());
            }

            if (execution.getEndTime() != null) {
                result.put("endTime", execution.getEndTime().toString());
            }

            return Response.ok(result).build();

        } catch (Exception e) {
            log.error("Error getting job status for: " + jobId, e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "message", "Error getting job status: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/{jobId}/result")
    public Response getJobResult(@PathParam("jobId") String jobId) {
        log.debug("Getting job result for: {}", jobId);

        try {
            JobService.JobExecution<?> execution = jobService.getJobExecution(jobId);

            if (execution == null) {
                Map<String, Object> errorResult = Map.of(
                        "status", "error",
                        "message", "Job not found: " + jobId
                );
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(errorResult)
                        .build();
            }

            if (!jobService.isJobComplete(jobId)) {
                Map<String, Object> errorResult = Map.of(
                        "status", "error",
                        "message", "Job is not yet complete: " + jobId
                );
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorResult)
                        .build();
            }

            if (execution.getStatus() == JobStatus.FAILED) {
                Exception error = jobService.getJobError(jobId);
                Map<String, Object> errorResult = Map.of(
                        "status", "failed",
                        "jobId", jobId,
                        "message", error != null ? error.getMessage() : "Job failed with unknown error"
                );
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorResult)
                        .build();
            }

            Object result = jobService.getJobResult(jobId);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("jobId", jobId);
            response.put("jobType", execution.getJob().getJobType());

            // Handle different job result types
            String jobType = execution.getJob().getJobType();
            if (result instanceof List<?>) {
                if (jobType.contains("SCHEMA")) {
                    // Handle schema extraction results
                    @SuppressWarnings("unchecked")
                    List<String> schemas = (List<String>) result;

                    Map<String, Object> summary = generateSchemaExtractionSummary(schemas);
                    response.put("summary", summary);
                    response.put("count", schemas.size());
                    response.put("schemas", schemas);
                } else if (jobType.contains("TABLE_METADATA")) {
                    // Handle table metadata extraction results
                    @SuppressWarnings("unchecked")
                    List<TableMetadata> tableMetadata = (List<TableMetadata>) result;

                    Map<String, Object> summary = generateTableMetadataSummary(tableMetadata);
                    response.put("summary", summary);
                    response.put("tableCount", tableMetadata.size());
                } else if (jobType.contains("OBJECT_DATATYPE")) {
                    // Handle object data type extraction results
                    @SuppressWarnings("unchecked")
                    List<ObjectDataTypeMetaData> objectDataTypes = (List<ObjectDataTypeMetaData>) result;

                    Map<String, Object> summary = generateObjectDataTypeSummary(objectDataTypes);
                    response.put("summary", summary);
                    response.put("objectDataTypeCount", objectDataTypes.size());
                    response.put("result", result); // Include raw result for frontend compatibility
                } else if (jobType.contains("ROW_COUNT")) {
                    // Handle row count extraction results
                    @SuppressWarnings("unchecked")
                    List<RowCountMetadata> rowCounts = (List<RowCountMetadata>) result;

                    Map<String, Object> summary = generateRowCountSummary(rowCounts);
                    response.put("summary", summary);
                    response.put("rowCountDataCount", rowCounts.size());
                    response.put("result", result); // Include raw result for frontend compatibility
                } else {
                    // Generic list result
                    response.put("result", result);
                }
            } else if (result instanceof SchemaCreationResult) {
                // Handle schema creation results
                SchemaCreationResult schemaResult = (SchemaCreationResult) result;

                Map<String, Object> summary = generateSchemaCreationSummary(schemaResult);
                response.put("summary", summary);
                response.put("createdCount", schemaResult.getCreatedCount());
                response.put("skippedCount", schemaResult.getSkippedCount());
                response.put("errorCount", schemaResult.getErrorCount());
                response.put("isSuccessful", schemaResult.isSuccessful());
                response.put("result", result); // Include raw result for frontend compatibility
            } else if (result instanceof ObjectTypeCreationResult) {
                // Handle object type creation results
                ObjectTypeCreationResult objectTypeResult = (ObjectTypeCreationResult) result;

                Map<String, Object> summary = generateObjectTypeCreationSummary(objectTypeResult);
                response.put("summary", summary);
                response.put("createdCount", objectTypeResult.getCreatedCount());
                response.put("skippedCount", objectTypeResult.getSkippedCount());
                response.put("errorCount", objectTypeResult.getErrorCount());
                response.put("isSuccessful", objectTypeResult.isSuccessful());
                response.put("result", result); // Include raw result for frontend compatibility
            } else if (result instanceof TableCreationResult) {
                // Handle table creation results
                TableCreationResult tableResult = (TableCreationResult) result;

                Map<String, Object> summary = generateTableCreationSummary(tableResult);
                response.put("summary", summary);
                response.put("createdCount", tableResult.getCreatedCount());
                response.put("skippedCount", tableResult.getSkippedCount());
                response.put("errorCount", tableResult.getErrorCount());
                response.put("isSuccessful", tableResult.isSuccessful());
                response.put("result", result); // Include raw result for frontend compatibility
            } else {
                response.put("result", result);
            }

            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Error getting job result for: " + jobId, e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "message", "Error getting job result: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    private Map<String, Object> generateTableMetadataSummary(List<TableMetadata> tableMetadata) {
        Map<String, Integer> schemaTableCounts = new HashMap<>();
        Map<String, Object> tableDetails = new HashMap<>();

        int totalColumns = 0;
        int totalConstraints = 0;

        for (TableMetadata table : tableMetadata) {
            String schema = table.getSchema();
            schemaTableCounts.put(schema, schemaTableCounts.getOrDefault(schema, 0) + 1);

            totalColumns += table.getColumns().size();
            totalConstraints += table.getConstraints().size();

            // Store individual table info
            Map<String, Object> tableInfo = Map.of(
                    "schema", table.getSchema(),
                    "name", table.getTableName(),
                    "columnCount", table.getColumns().size(),
                    "constraintCount", table.getConstraints().size()
            );

            String tableKey = schema + "." + table.getTableName();
            tableDetails.put(tableKey, tableInfo);
        }

        return Map.of(
                "totalTables", tableMetadata.size(),
                "totalColumns", totalColumns,
                "totalConstraints", totalConstraints,
                "schemaTableCounts", schemaTableCounts,
                "tables", tableDetails
        );
    }

    private Map<String, Object> generateObjectDataTypeSummary(List<ObjectDataTypeMetaData> objectDataTypes) {
        Map<String, Integer> schemaObjectCounts = new HashMap<>();
        Map<String, Object> objectDetails = new HashMap<>();

        int totalVariables = 0;

        for (ObjectDataTypeMetaData objectType : objectDataTypes) {
            String schema = objectType.getSchema();
            schemaObjectCounts.put(schema, schemaObjectCounts.getOrDefault(schema, 0) + 1);

            totalVariables += objectType.getVariables().size();

            // Store individual object type info
            Map<String, Object> objectInfo = Map.of(
                    "schema", objectType.getSchema(),
                    "name", objectType.getName(),
                    "variableCount", objectType.getVariables().size()
            );

            String objectKey = schema + "." + objectType.getName();
            objectDetails.put(objectKey, objectInfo);
        }

        return Map.of(
                "totalObjectDataTypes", objectDataTypes.size(),
                "totalVariables", totalVariables,
                "schemaObjectCounts", schemaObjectCounts,
                "objectDataTypes", objectDetails
        );
    }

    private Map<String, Object> generateRowCountSummary(List<RowCountMetadata> rowCounts) {
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

    private Map<String, Object> generateSchemaCreationSummary(SchemaCreationResult schemaResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created schemas details
        for (String schema : schemaResult.getCreatedSchemas()) {
            createdDetails.put(schema, Map.of(
                    "schema", schema,
                    "status", "created",
                    "timestamp", schemaResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped schemas details
        for (String schema : schemaResult.getSkippedSchemas()) {
            skippedDetails.put(schema, Map.of(
                    "schema", schema,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (SchemaCreationResult.SchemaCreationError error : schemaResult.getErrors()) {
            errorDetails.put(error.getSchemaName(), Map.of(
                    "schema", error.getSchemaName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", schemaResult.getTotalProcessed(),
                "createdCount", schemaResult.getCreatedCount(),
                "skippedCount", schemaResult.getSkippedCount(),
                "errorCount", schemaResult.getErrorCount(),
                "isSuccessful", schemaResult.isSuccessful(),
                "executionTimestamp", schemaResult.getExecutionTimestamp(),
                "createdSchemas", createdDetails,
                "skippedSchemas", skippedDetails,
                "errors", errorDetails
        );
    }

    private Map<String, Object> generateObjectTypeCreationSummary(ObjectTypeCreationResult objectTypeResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created object types details
        for (String typeName : objectTypeResult.getCreatedTypes()) {
            createdDetails.put(typeName, Map.of(
                    "typeName", typeName,
                    "status", "created",
                    "timestamp", objectTypeResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped object types details
        for (String typeName : objectTypeResult.getSkippedTypes()) {
            skippedDetails.put(typeName, Map.of(
                    "typeName", typeName,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (ObjectTypeCreationResult.ObjectTypeCreationError error : objectTypeResult.getErrors()) {
            errorDetails.put(error.getTypeName(), Map.of(
                    "typeName", error.getTypeName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", objectTypeResult.getTotalProcessed(),
                "createdCount", objectTypeResult.getCreatedCount(),
                "skippedCount", objectTypeResult.getSkippedCount(),
                "errorCount", objectTypeResult.getErrorCount(),
                "isSuccessful", objectTypeResult.isSuccessful(),
                "executionTimestamp", objectTypeResult.getExecutionTimestamp(),
                "createdTypes", createdDetails,
                "skippedTypes", skippedDetails,
                "errors", errorDetails
        );
    }

    private Map<String, Object> generateTableCreationSummary(TableCreationResult tableResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

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

        return Map.of(
                "totalProcessed", tableResult.getTotalProcessed(),
                "createdCount", tableResult.getCreatedCount(),
                "skippedCount", tableResult.getSkippedCount(),
                "errorCount", tableResult.getErrorCount(),
                "isSuccessful", tableResult.isSuccessful(),
                "executionTimestamp", tableResult.getExecutionTimestamp(),
                "createdTables", createdDetails,
                "skippedTables", skippedDetails,
                "errors", errorDetails
        );
    }

    @POST
    @Path("/tables/postgres/extract")
    public Response startPostgresTableMetadataExtraction() {
        return startExtractionJob("POSTGRES", "TABLE_METADATA", "PostgreSQL table metadata extraction");
    }

    @POST
    @Path("/objects/oracle/extract")
    public Response startOracleObjectDataTypeExtraction() {
        return startExtractionJob("ORACLE", "OBJECT_DATATYPE", "Oracle object data type extraction");
    }

    @POST
    @Path("/objects/postgres/extract")
    public Response startPostgresObjectDataTypeExtraction() {
        return startExtractionJob("POSTGRES", "OBJECT_DATATYPE", "PostgreSQL object data type extraction");
    }

    @POST
    @Path("/oracle/row_count/extract")
    public Response startOracleRowCountExtraction() {
        return startExtractionJob("ORACLE", "ROW_COUNT", "Oracle row count extraction");
    }

    @POST
    @Path("/postgres/row_count/extract")
    public Response startPostgresRowCountExtraction() {
        return startExtractionJob("POSTGRES", "ROW_COUNT", "PostgreSQL row count extraction");
    }

    @POST
    @Path("/postgres/schema/create")
    public Response startPostgresSchemaCreation() {
        return startJob("POSTGRES", "SCHEMA_CREATION", "PostgreSQL schema creation");
    }

    @POST
    @Path("/postgres/object-type/create")
    public Response startPostgresObjectTypeCreation() {
        return startJob("POSTGRES", "OBJECT_TYPE_CREATION", "PostgreSQL object type creation");
    }

    @POST
    @Path("/postgres/table/create")
    public Response startPostgresTableCreation() {
        return startJob("POSTGRES", "TABLE_CREATION", "PostgreSQL table creation");
    }

    private Map<String, Object> generateSchemaExtractionSummary(List<String> schemas) {
        return Map.of(
                "totalSchemas", schemas.size(),
                "message", String.format("Extraction completed: %d schemas found", schemas.size())
        );
    }
}