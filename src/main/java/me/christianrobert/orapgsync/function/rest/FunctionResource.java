package me.christianrobert.orapgsync.function.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionStubCreationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for function/procedure-related operations.
 * Handles function extraction, stub creation, and stub verification.
 */
@ApplicationScoped
@Path("/api/functions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FunctionResource {

    private static final Logger log = LoggerFactory.getLogger(FunctionResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleFunctions() {
        return startJob("ORACLE", "FUNCTION", "Oracle function/procedure extraction");
    }

    @POST
    @Path("/postgres/stubs/create")
    public Response createPostgresFunctionStubs() {
        return startJob("POSTGRES", "FUNCTION_STUB_CREATION", "PostgreSQL function stub creation");
    }

    @POST
    @Path("/postgres/stubs/verify")
    public Response verifyPostgresFunctionStubs() {
        return startJob("POSTGRES", "FUNCTION_STUB_VERIFICATION", "PostgreSQL function stub verification");
    }

    /**
     * Generic method to start any function-related job.
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
     * Generate summary for function/procedure metadata extraction results.
     */
    public static Map<String, Object> generateFunctionSummary(List<FunctionMetadata> functions) {
        Map<String, Integer> schemaFunctionCounts = new HashMap<>();
        int totalParameters = 0;
        long functionCount = 0;
        long procedureCount = 0;
        long standaloneCount = 0;
        long packageMemberCount = 0;

        for (FunctionMetadata function : functions) {
            String schema = function.getSchema();
            schemaFunctionCounts.put(schema, schemaFunctionCounts.getOrDefault(schema, 0) + 1);
            totalParameters += function.getParameters().size();

            if (function.isFunction()) {
                functionCount++;
            } else {
                procedureCount++;
            }

            if (function.isStandalone()) {
                standaloneCount++;
            } else {
                packageMemberCount++;
            }
        }

        return Map.of(
                "totalFunctions", functions.size(),
                "totalParameters", totalParameters,
                "functionCount", functionCount,
                "procedureCount", procedureCount,
                "standaloneCount", standaloneCount,
                "packageMemberCount", packageMemberCount,
                "schemaFunctionCounts", schemaFunctionCounts,
                "message", String.format("Extraction completed: %d functions/procedures (%d functions, %d procedures) from %d schemas",
                        functions.size(), functionCount, procedureCount, schemaFunctionCounts.size())
        );
    }

    /**
     * Generate summary for function stub creation results.
     */
    public static Map<String, Object> generateFunctionStubCreationSummary(FunctionStubCreationResult functionStubResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created function stubs details
        for (String functionName : functionStubResult.getCreatedFunctions()) {
            createdDetails.put(functionName, Map.of(
                    "functionName", functionName,
                    "status", "created",
                    "timestamp", functionStubResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped function stubs details
        for (String functionName : functionStubResult.getSkippedFunctions()) {
            skippedDetails.put(functionName, Map.of(
                    "functionName", functionName,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (FunctionStubCreationResult.FunctionCreationError error : functionStubResult.getErrors()) {
            errorDetails.put(error.getFunctionName(), Map.of(
                    "functionName", error.getFunctionName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", functionStubResult.getTotalProcessed(),
                "createdCount", functionStubResult.getCreatedCount(),
                "skippedCount", functionStubResult.getSkippedCount(),
                "errorCount", functionStubResult.getErrorCount(),
                "isSuccessful", functionStubResult.isSuccessful(),
                "executionTimestamp", functionStubResult.getExecutionTimestamp(),
                "createdFunctions", createdDetails,
                "skippedFunctions", skippedDetails,
                "errors", errorDetails
        );
    }
}
