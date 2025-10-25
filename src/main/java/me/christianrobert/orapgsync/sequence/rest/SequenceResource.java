package me.christianrobert.orapgsync.sequence.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceCreationResult;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for sequence-related operations.
 * Handles sequence extraction from Oracle/PostgreSQL and sequence creation in PostgreSQL.
 */
@ApplicationScoped
@Path("/api/sequences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SequenceResource {

    private static final Logger log = LoggerFactory.getLogger(SequenceResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleSequences() {
        return startJob("ORACLE", "SEQUENCE", "Oracle sequence extraction");
    }

    @POST
    @Path("/postgres/extract")
    public Response extractPostgresSequences() {
        return startJob("POSTGRES", "SEQUENCE", "PostgreSQL sequence extraction");
    }

    @POST
    @Path("/postgres/create")
    public Response createPostgresSequences() {
        return startJob("POSTGRES", "SEQUENCE_CREATION", "PostgreSQL sequence creation");
    }

    /**
     * Generic method to start any sequence-related job.
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
     * Generate summary for sequence metadata extraction results.
     */
    public static Map<String, Object> generateSequenceSummary(List<SequenceMetadata> sequences) {
        Map<String, Integer> schemaSequenceCounts = new HashMap<>();

        for (SequenceMetadata sequence : sequences) {
            String schema = sequence.getSchema();
            schemaSequenceCounts.put(schema, schemaSequenceCounts.getOrDefault(schema, 0) + 1);
        }

        return Map.of(
                "totalSequences", sequences.size(),
                "schemaSequenceCounts", schemaSequenceCounts,
                "message", String.format("Extraction completed: %d sequences from %d schemas",
                        sequences.size(), schemaSequenceCounts.size())
        );
    }

    /**
     * Generate summary for sequence creation results.
     */
    public static Map<String, Object> generateSequenceCreationSummary(SequenceCreationResult sequenceResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created sequences details
        for (String sequenceName : sequenceResult.getCreatedSequences()) {
            createdDetails.put(sequenceName, Map.of(
                    "sequenceName", sequenceName,
                    "status", "created",
                    "timestamp", sequenceResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped sequences details
        for (String sequenceName : sequenceResult.getSkippedSequences()) {
            skippedDetails.put(sequenceName, Map.of(
                    "sequenceName", sequenceName,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (SequenceCreationResult.SequenceCreationError error : sequenceResult.getErrors()) {
            errorDetails.put(error.getSequenceName(), Map.of(
                    "sequenceName", error.getSequenceName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", sequenceResult.getTotalProcessed(),
                "createdCount", sequenceResult.getCreatedCount(),
                "skippedCount", sequenceResult.getSkippedCount(),
                "errorCount", sequenceResult.getErrorCount(),
                "isSuccessful", sequenceResult.isSuccessful(),
                "executionTimestamp", sequenceResult.getExecutionTimestamp(),
                "createdSequences", createdDetails,
                "skippedSequences", skippedDetails,
                "errors", errorDetails
        );
    }
}
