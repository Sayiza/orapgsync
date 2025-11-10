package me.christianrobert.orapgsync.typemethod.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodStubCreationResult;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodImplementationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for type method-related operations.
 * Handles type method extraction, stub creation, and stub verification.
 */
@ApplicationScoped
@Path("/api/type-methods")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TypeMethodResource {

    private static final Logger log = LoggerFactory.getLogger(TypeMethodResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleTypeMethods() {
        return startJob("ORACLE", "TYPE_METHOD", "Oracle type method extraction");
    }

    @POST
    @Path("/postgres/stubs/create")
    public Response createPostgresTypeMethodStubs() {
        return startJob("POSTGRES", "TYPE_METHOD_STUB_CREATION", "PostgreSQL type method stub creation");
    }

    @POST
    @Path("/postgres/stubs/verify")
    public Response verifyPostgresTypeMethodStubs() {
        return startJob("POSTGRES", "TYPE_METHOD_STUB_VERIFICATION", "PostgreSQL type method stub verification");
    }

    @POST
    @Path("/postgres/implementation/create")
    public Response createTypeMethodImplementation() {
        return startJob("POSTGRES", "TYPE_METHOD_IMPLEMENTATION",
            "PostgreSQL type method implementation");
    }

    @POST
    @Path("/postgres/implementation/verify")
    public Response verifyTypeMethodImplementation() {
        return startJob("POSTGRES", "TYPE_METHOD_IMPLEMENTATION_VERIFICATION",
            "PostgreSQL type method implementation verification");
    }

    /**
     * Generic method to start any type method-related job.
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
     * Generate summary for type method metadata extraction results.
     */
    public static Map<String, Object> generateTypeMethodSummary(List<TypeMethodMetadata> typeMethods) {
        Map<String, Integer> schemaTypeMethodCounts = new HashMap<>();
        int totalParameters = 0;
        long memberMethodCount = 0;
        long staticMethodCount = 0;
        long functionCount = 0;
        long procedureCount = 0;

        for (TypeMethodMetadata method : typeMethods) {
            String schema = method.getSchema();
            schemaTypeMethodCounts.put(schema, schemaTypeMethodCounts.getOrDefault(schema, 0) + 1);
            totalParameters += method.getParameters().size();

            if (method.isMemberMethod()) {
                memberMethodCount++;
            } else if (method.isStaticMethod()) {
                staticMethodCount++;
            }

            if (method.isFunction()) {
                functionCount++;
            } else if (method.isProcedure()) {
                procedureCount++;
            }
        }

        return Map.of(
                "totalTypeMethods", typeMethods.size(),
                "totalParameters", totalParameters,
                "memberMethodCount", memberMethodCount,
                "staticMethodCount", staticMethodCount,
                "functionCount", functionCount,
                "procedureCount", procedureCount,
                "schemaTypeMethodCounts", schemaTypeMethodCounts,
                "message", String.format("Extraction completed: %d type methods (%d member, %d static, %d functions, %d procedures) from %d schemas",
                        typeMethods.size(), memberMethodCount, staticMethodCount, functionCount, procedureCount, schemaTypeMethodCounts.size())
        );
    }

    /**
     * Generate summary for type method stub creation results.
     */
    public static Map<String, Object> generateTypeMethodStubCreationSummary(TypeMethodStubCreationResult typeMethodStubResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created type method stubs details
        for (String methodName : typeMethodStubResult.getCreatedMethods()) {
            createdDetails.put(methodName, Map.of(
                    "methodName", methodName,
                    "status", "created",
                    "timestamp", typeMethodStubResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped type method stubs details
        for (String methodName : typeMethodStubResult.getSkippedMethods()) {
            skippedDetails.put(methodName, Map.of(
                    "methodName", methodName,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (Map.Entry<String, String> entry : typeMethodStubResult.getErrors().entrySet()) {
            String methodName = entry.getKey();
            String error = entry.getValue();
            String sql = typeMethodStubResult.getFailedSqlStatements().get(methodName);

            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("methodName", methodName);
            errorInfo.put("status", "error");
            errorInfo.put("error", error);
            if (sql != null) {
                errorInfo.put("sql", sql);
            }
            errorDetails.put(methodName, errorInfo);
        }

        return Map.of(
                "totalProcessed", typeMethodStubResult.getTotalProcessed(),
                "createdCount", typeMethodStubResult.getCreatedCount(),
                "skippedCount", typeMethodStubResult.getSkippedCount(),
                "errorCount", typeMethodStubResult.getErrorCount(),
                "isSuccessful", !typeMethodStubResult.hasErrors(),
                "executionTimestamp", java.time.LocalDateTime.now().toString(),
                "createdMethods", createdDetails,
                "skippedMethods", skippedDetails,
                "errors", errorDetails
        );
    }

    /**
     * Generate summary for type method implementation results.
     */
    public static Map<String, Object> generateTypeMethodImplementationSummary(
            TypeMethodImplementationResult typeMethodImplResult) {
        Map<String, Object> implementedDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Implemented type methods details
        for (Map.Entry<String, TypeMethodMetadata> entry : typeMethodImplResult.getImplementedTypeMethods().entrySet()) {
            TypeMethodMetadata method = entry.getValue();
            implementedDetails.put(entry.getKey(), Map.of(
                    "methodName", method.getQualifiedName(),
                    "status", "implemented"
            ));
        }

        // Skipped type methods details
        for (Map.Entry<String, TypeMethodMetadata> entry : typeMethodImplResult.getSkippedTypeMethods().entrySet()) {
            TypeMethodMetadata method = entry.getValue();
            skippedDetails.put(entry.getKey(), Map.of(
                    "methodName", method.getQualifiedName(),
                    "status", "skipped",
                    "reason", "PL/SQL transformation not yet implemented"
            ));
        }

        // Error details
        for (Map.Entry<String, TypeMethodImplementationResult.ErrorInfo> entry :
                typeMethodImplResult.getErrors().entrySet()) {
            TypeMethodImplementationResult.ErrorInfo error = entry.getValue();
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("typeMethodName", error.getTypeMethodName());
            errorMap.put("status", "error");
            errorMap.put("error", error.getError());
            if (error.getSql() != null) {
                errorMap.put("sql", error.getSql());
            }
            errorDetails.put(entry.getKey(), errorMap);
        }

        return Map.of(
                "implementedCount", typeMethodImplResult.getImplementedCount(),
                "skippedCount", typeMethodImplResult.getSkippedCount(),
                "errorCount", typeMethodImplResult.getErrorCount(),
                "isSuccessful", typeMethodImplResult.isSuccessful(),
                "implementedMethods", implementedDetails,
                "skippedMethods", skippedDetails,
                "errors", errorDetails
        );
    }
}
