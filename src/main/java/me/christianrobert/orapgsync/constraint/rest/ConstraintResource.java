package me.christianrobert.orapgsync.constraint.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.FKIndexCreationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for constraint-related operations.
 * Handles constraint source state, verification, creation, and FK index creation.
 */
@ApplicationScoped
@Path("/api/constraints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConstraintResource {

    private static final Logger log = LoggerFactory.getLogger(ConstraintResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/source-state")
    public Response extractOracleConstraintSourceState() {
        return startJob("ORACLE", "CONSTRAINT_SOURCE_STATE", "Oracle constraint source state extraction");
    }

    @POST
    @Path("/postgres/verify")
    public Response verifyPostgresConstraints() {
        return startJob("POSTGRES", "CONSTRAINT_VERIFICATION", "PostgreSQL constraint verification");
    }

    @POST
    @Path("/postgres/create")
    public Response createPostgresConstraints() {
        return startJob("POSTGRES", "CONSTRAINT_CREATION", "PostgreSQL constraint creation");
    }

    @POST
    @Path("/postgres/fk-indexes/create")
    public Response createPostgresFKIndexes() {
        return startJob("POSTGRES", "FK_INDEX_CREATION", "PostgreSQL FK index creation");
    }

    /**
     * Generic method to start any constraint-related job.
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
     * Generate summary for constraint metadata extraction/verification results.
     */
    public static Map<String, Object> generateConstraintSummary(List<ConstraintMetadata> constraints) {
        Map<String, Integer> schemaConstraintCounts = new HashMap<>();
        Map<String, Integer> typeConstraintCounts = new HashMap<>();

        for (ConstraintMetadata constraint : constraints) {
            String schema = constraint.getSchema();
            schemaConstraintCounts.put(schema, schemaConstraintCounts.getOrDefault(schema, 0) + 1);

            String typeName = constraint.getConstraintTypeName();
            typeConstraintCounts.put(typeName, typeConstraintCounts.getOrDefault(typeName, 0) + 1);
        }

        return Map.of(
                "totalConstraints", constraints.size(),
                "schemaConstraintCounts", schemaConstraintCounts,
                "typeConstraintCounts", typeConstraintCounts,
                "message", String.format("Extraction completed: %d constraints from %d schemas",
                        constraints.size(), schemaConstraintCounts.size())
        );
    }

    /**
     * Generate summary for constraint creation results.
     */
    public static Map<String, Object> generateConstraintCreationSummary(ConstraintCreationResult constraintResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created constraints details
        for (ConstraintCreationResult.ConstraintInfo constraint : constraintResult.getCreatedConstraints()) {
            String key = constraint.getTableName() + "." + constraint.getConstraintName();
            createdDetails.put(key, Map.of(
                    "tableName", constraint.getTableName(),
                    "constraintName", constraint.getConstraintName(),
                    "constraintType", constraint.getConstraintTypeName(),
                    "status", "created",
                    "timestamp", constraintResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped constraints details
        for (ConstraintCreationResult.ConstraintInfo constraint : constraintResult.getSkippedConstraints()) {
            String key = constraint.getTableName() + "." + constraint.getConstraintName();
            skippedDetails.put(key, Map.of(
                    "tableName", constraint.getTableName(),
                    "constraintName", constraint.getConstraintName(),
                    "constraintType", constraint.getConstraintTypeName(),
                    "status", "skipped",
                    "reason", constraint.getReason() != null ? constraint.getReason() : "already exists"
            ));
        }

        // Error details
        for (ConstraintCreationResult.ConstraintCreationError error : constraintResult.getErrors()) {
            String key = error.getTableName() + "." + error.getConstraintName();
            errorDetails.put(key, Map.of(
                    "tableName", error.getTableName(),
                    "constraintName", error.getConstraintName(),
                    "constraintType", error.getConstraintTypeName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", constraintResult.getTotalProcessed(),
                "createdCount", constraintResult.getCreatedCount(),
                "skippedCount", constraintResult.getSkippedCount(),
                "errorCount", constraintResult.getErrorCount(),
                "isSuccessful", constraintResult.isSuccessful(),
                "executionTimestamp", constraintResult.getExecutionTimestamp(),
                "createdConstraints", createdDetails,
                "skippedConstraints", skippedDetails,
                "errors", errorDetails
        );
    }

    /**
     * Generate summary for FK index creation results.
     */
    public static Map<String, Object> generateFKIndexCreationSummary(FKIndexCreationResult fkIndexResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created indexes details
        for (FKIndexCreationResult.IndexInfo index : fkIndexResult.getCreatedIndexes()) {
            String key = index.getTableName() + "." + index.getIndexName();
            createdDetails.put(key, Map.of(
                    "tableName", index.getTableName(),
                    "indexName", index.getIndexName(),
                    "columns", index.getColumnsDisplay(),
                    "status", "created",
                    "timestamp", fkIndexResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped indexes details
        for (FKIndexCreationResult.IndexInfo index : fkIndexResult.getSkippedIndexes()) {
            String key = index.getTableName() + "." + index.getIndexName();
            skippedDetails.put(key, Map.of(
                    "tableName", index.getTableName(),
                    "indexName", index.getIndexName(),
                    "columns", index.getColumnsDisplay(),
                    "status", "skipped",
                    "reason", index.getReason() != null ? index.getReason() : "already exists"
            ));
        }

        // Error details
        for (FKIndexCreationResult.IndexCreationError error : fkIndexResult.getErrors()) {
            String key = error.getTableName() + "." + error.getIndexName();
            errorDetails.put(key, Map.of(
                    "tableName", error.getTableName(),
                    "indexName", error.getIndexName(),
                    "columns", error.getColumnsDisplay(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", fkIndexResult.getTotalProcessed(),
                "createdCount", fkIndexResult.getCreatedCount(),
                "skippedCount", fkIndexResult.getSkippedCount(),
                "errorCount", fkIndexResult.getErrorCount(),
                "isSuccessful", fkIndexResult.isSuccessful(),
                "executionTimestamp", fkIndexResult.getExecutionTimestamp(),
                "createdIndexes", createdDetails,
                "skippedIndexes", skippedDetails,
                "errors", errorDetails
        );
    }
}
