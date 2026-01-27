package me.christianrobert.orapgsync.synonym.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymReplacementViewCreationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * REST resource for synonym-related operations.
 * Handles synonym replacement view creation in PostgreSQL.
 */
@ApplicationScoped
@Path("/api/synonyms")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SynonymResource {

    private static final Logger log = LoggerFactory.getLogger(SynonymResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    /**
     * Create synonym replacement views in PostgreSQL.
     * These views emulate Oracle synonym behavior for external applications.
     */
    @POST
    @Path("/postgres/replacement-views/create")
    public Response createSynonymReplacementViews() {
        return startJob("POSTGRES", "SYNONYM_REPLACEMENT_VIEW_CREATION",
                "PostgreSQL synonym replacement view creation");
    }

    /**
     * Generic method to start a synonym-related job.
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
     * Generate summary for synonym replacement view creation results.
     */
    public static Map<String, Object> generateSynonymReplacementViewCreationSummary(
            SynonymReplacementViewCreationResult result) {

        Map<String, Object> summary = new HashMap<>();
        summary.put("createdCount", result.getCreatedCount());
        summary.put("skippedCount", result.getSkippedCount());
        summary.put("errorCount", result.getErrorCount());
        summary.put("totalProcessed", result.getTotalProcessed());
        summary.put("isSuccessful", result.isSuccessful());
        summary.put("executionTimestamp", result.getExecutionDateTime().toString());
        summary.put("createdViews", result.getCreatedViews());
        summary.put("skippedSynonyms", result.getSkippedSynonyms());
        summary.put("errors", result.getErrors());

        return summary;
    }
}
