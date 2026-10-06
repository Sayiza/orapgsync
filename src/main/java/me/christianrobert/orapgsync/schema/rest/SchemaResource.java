package me.christianrobert.orapgsync.schema.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.schema.SchemaCreationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for schema-related operations.
 * Handles schema extraction from Oracle/PostgreSQL and schema creation in PostgreSQL.
 */
@ApplicationScoped
@Path("/api/schemas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchemaResource {

    private static final Logger log = LoggerFactory.getLogger(SchemaResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleSchemas() {
        return startJob("ORACLE", "SCHEMA", "Oracle schema extraction");
    }

    @POST
    @Path("/postgres/extract")
    public Response extractPostgresSchemas() {
        return startJob("POSTGRES", "SCHEMA", "PostgreSQL schema extraction");
    }

    @POST
    @Path("/postgres/create")
    public Response createPostgresSchemas() {
        return startJob("POSTGRES", "SCHEMA_CREATION", "PostgreSQL schema creation");
    }

    /**
     * Generic method to start any schema-related job.
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
     * Generate summary for schema extraction results.
     */
    public static Map<String, Object> generateSchemaExtractionSummary(List<String> schemas) {
        return Map.of(
                "totalSchemas", schemas.size(),
                "message", String.format("Extraction completed: %d schemas found", schemas.size())
        );
    }

    /**
     * Generate summary for schema creation results.
     */
    public static Map<String, Object> generateSchemaCreationSummary(SchemaCreationResult schemaResult) {
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
}
