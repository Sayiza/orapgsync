package me.christianrobert.orapgsync.trigger.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerImplementationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for trigger-related operations.
 * Handles trigger extraction, implementation, and verification.
 */
@ApplicationScoped
@Path("/api/triggers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TriggerResource {

    private static final Logger log = LoggerFactory.getLogger(TriggerResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleTriggers() {
        return startJob("ORACLE", "TRIGGER", "Oracle trigger extraction");
    }

    @POST
    @Path("/postgres/implementation/create")
    public Response createTriggerImplementation() {
        return startJob("POSTGRES", "TRIGGER_IMPLEMENTATION",
            "PostgreSQL trigger implementation");
    }

    @POST
    @Path("/postgres/implementation/verify")
    public Response verifyTriggerImplementation() {
        return startJob("POSTGRES", "TRIGGER_VERIFICATION",
            "PostgreSQL trigger verification");
    }

    /**
     * Generic method to start any trigger-related job.
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
     * Generate summary for trigger metadata extraction results.
     */
    public static Map<String, Object> generateTriggerSummary(List<TriggerMetadata> triggers) {
        Map<String, Integer> schemaTriggerCounts = new HashMap<>();
        long beforeTriggers = 0;
        long afterTriggers = 0;
        long insteadOfTriggers = 0;
        long rowLevelTriggers = 0;
        long statementLevelTriggers = 0;

        for (TriggerMetadata trigger : triggers) {
            String schema = trigger.getSchema();
            schemaTriggerCounts.put(schema, schemaTriggerCounts.getOrDefault(schema, 0) + 1);

            if (trigger.getTriggerType() != null) {
                if (trigger.getTriggerType().contains("BEFORE")) {
                    beforeTriggers++;
                } else if (trigger.getTriggerType().contains("AFTER")) {
                    afterTriggers++;
                } else if (trigger.getTriggerType().contains("INSTEAD OF")) {
                    insteadOfTriggers++;
                }
            }

            if ("ROW".equalsIgnoreCase(trigger.getTriggerLevel())) {
                rowLevelTriggers++;
            } else if ("STATEMENT".equalsIgnoreCase(trigger.getTriggerLevel())) {
                statementLevelTriggers++;
            }
        }

        return Map.of(
                "totalTriggers", triggers.size(),
                "beforeTriggers", beforeTriggers,
                "afterTriggers", afterTriggers,
                "insteadOfTriggers", insteadOfTriggers,
                "rowLevelTriggers", rowLevelTriggers,
                "statementLevelTriggers", statementLevelTriggers,
                "schemaTriggerCounts", schemaTriggerCounts,
                "message", String.format("Extraction completed: %d triggers (%d BEFORE, %d AFTER, %d INSTEAD OF) from %d schemas",
                        triggers.size(), beforeTriggers, afterTriggers, insteadOfTriggers, schemaTriggerCounts.size())
        );
    }

    /**
     * Generate summary for trigger implementation results.
     */
    public static Map<String, Object> generateTriggerImplementationSummary(
            TriggerImplementationResult triggerImplResult) {
        Map<String, Object> implementedDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Implemented triggers details
        for (Map.Entry<String, TriggerMetadata> entry : triggerImplResult.getImplementedTriggers().entrySet()) {
            TriggerMetadata trigger = entry.getValue();
            implementedDetails.put(entry.getKey(), Map.of(
                    "triggerName", trigger.getDisplayName(),
                    "status", "implemented"
            ));
        }

        // Skipped triggers details
        for (Map.Entry<String, TriggerMetadata> entry : triggerImplResult.getSkippedTriggers().entrySet()) {
            TriggerMetadata trigger = entry.getValue();
            skippedDetails.put(entry.getKey(), Map.of(
                    "triggerName", trigger.getDisplayName(),
                    "status", "skipped",
                    "reason", "PL/SQL transformation not yet implemented"
            ));
        }

        // Error details
        for (Map.Entry<String, TriggerImplementationResult.ErrorInfo> entry :
                triggerImplResult.getErrors().entrySet()) {
            TriggerImplementationResult.ErrorInfo error = entry.getValue();
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("triggerName", error.getTriggerName());
            errorMap.put("status", "error");
            errorMap.put("error", error.getError());
            if (error.getSql() != null) {
                errorMap.put("sql", error.getSql());
            }
            errorDetails.put(entry.getKey(), errorMap);
        }

        return Map.of(
                "implementedCount", triggerImplResult.getImplementedCount(),
                "skippedCount", triggerImplResult.getSkippedCount(),
                "errorCount", triggerImplResult.getErrorCount(),
                "isSuccessful", triggerImplResult.isSuccessful(),
                "implementedTriggers", implementedDetails,
                "skippedTriggers", skippedDetails,
                "errors", errorDetails
        );
    }
}
