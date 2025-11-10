package me.christianrobert.orapgsync.view.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationVerificationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewStubCreationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewVerificationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for view-related operations.
 * Handles view extraction, stub creation, stub verification, implementation, and implementation verification.
 */
@ApplicationScoped
@Path("/api/views")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ViewResource {

    private static final Logger log = LoggerFactory.getLogger(ViewResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @Inject
    me.christianrobert.orapgsync.database.service.PostgresConnectionService postgresConnectionService;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleViews() {
        return startJob("ORACLE", "VIEW", "Oracle view extraction");
    }

    /**
     * Get view DDL on demand for a specific view.
     * This endpoint is used for lazy-loading view source code in the frontend.
     *
     * @param schema the schema name
     * @param viewName the view name
     * @return JSON with oracleSql (if available) and postgresSql
     */
    @GET
    @Path("/postgres/source/{schema}/{viewName}")
    public Response getViewSource(@PathParam("schema") String schema,
                                  @PathParam("viewName") String viewName) {
        log.info("Fetching view source for: {}.{}", schema, viewName);

        try (java.sql.Connection conn = postgresConnectionService.getConnection()) {
            // Get PostgreSQL view definition
            String sql = "SELECT pg_get_viewdef(quote_ident(?) || '.' || quote_ident(?), true) AS viewdef";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                ps.setString(2, viewName);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String postgresSql = rs.getString("viewdef");

                        Map<String, Object> result = Map.of(
                                "status", "success",
                                "schema", schema,
                                "viewName", viewName,
                                "postgresSql", postgresSql != null ? postgresSql : "-- View definition not available"
                        );

                        return Response.ok(result).build();
                    } else {
                        throw new IllegalArgumentException("View not found: " + schema + "." + viewName);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch view source for {}.{}", schema, viewName, e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "message", "Failed to fetch view source: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @POST
    @Path("/postgres/stubs/create")
    public Response createPostgresViewStubs() {
        return startJob("POSTGRES", "VIEW_STUB_CREATION", "PostgreSQL view stub creation");
    }

    @POST
    @Path("/postgres/stubs/verify")
    public Response verifyPostgresViewStubs() {
        return startJob("POSTGRES", "VIEW_STUB_VERIFICATION", "PostgreSQL view stub verification");
    }

    @POST
    @Path("/postgres/implementation/create")
    public Response createPostgresViewImplementation() {
        return startJob("POSTGRES", "VIEW_IMPLEMENTATION", "PostgreSQL view implementation");
    }

    @POST
    @Path("/postgres/implementation/verify")
    public Response verifyPostgresViewImplementation() {
        return startJob("POSTGRES", "VIEW_IMPLEMENTATION_VERIFICATION", "PostgreSQL view implementation verification");
    }

    @POST
    @Path("/postgres/verify")
    public Response verifyAllPostgresViews() {
        return startJob("POSTGRES", "VIEW_VERIFICATION", "PostgreSQL unified view verification");
    }

    /**
     * Generic method to start any view-related job.
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
     * Generate summary for view metadata extraction results.
     */
    public static Map<String, Object> generateViewDefinitionSummary(List<ViewMetadata> viewDefinitions) {
        Map<String, Integer> schemaViewCounts = new HashMap<>();
        int totalColumns = 0;

        for (ViewMetadata view : viewDefinitions) {
            String schema = view.getSchema();
            schemaViewCounts.put(schema, schemaViewCounts.getOrDefault(schema, 0) + 1);
            totalColumns += view.getColumns().size();
        }

        return Map.of(
                "totalViews", viewDefinitions.size(),
                "totalColumns", totalColumns,
                "schemaViewCounts", schemaViewCounts,
                "message", String.format("Extraction completed: %d views with %d columns from %d schemas",
                        viewDefinitions.size(), totalColumns, schemaViewCounts.size())
        );
    }

    /**
     * Generate summary for view stub creation results.
     */
    public static Map<String, Object> generateViewStubCreationSummary(ViewStubCreationResult viewStubResult) {
        Map<String, Object> createdDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Created view stubs details
        for (String viewName : viewStubResult.getCreatedViews()) {
            createdDetails.put(viewName, Map.of(
                    "viewName", viewName,
                    "status", "created",
                    "timestamp", viewStubResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped view stubs details
        for (String viewName : viewStubResult.getSkippedViews()) {
            skippedDetails.put(viewName, Map.of(
                    "viewName", viewName,
                    "status", "skipped",
                    "reason", "already exists"
            ));
        }

        // Error details
        for (ViewStubCreationResult.ViewCreationError error : viewStubResult.getErrors()) {
            errorDetails.put(error.getViewName(), Map.of(
                    "viewName", error.getViewName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", viewStubResult.getTotalProcessed(),
                "createdCount", viewStubResult.getCreatedCount(),
                "skippedCount", viewStubResult.getSkippedCount(),
                "errorCount", viewStubResult.getErrorCount(),
                "isSuccessful", viewStubResult.isSuccessful(),
                "executionTimestamp", viewStubResult.getExecutionTimestamp(),
                "createdViews", createdDetails,
                "skippedViews", skippedDetails,
                "errors", errorDetails
        );
    }

    /**
     * Generate summary for view implementation results.
     */
    public static Map<String, Object> generateViewImplementationSummary(ViewImplementationResult viewImplResult) {
        Map<String, Object> implementedDetails = new HashMap<>();
        Map<String, Object> skippedDetails = new HashMap<>();
        Map<String, Object> errorDetails = new HashMap<>();

        // Implemented views details
        for (String viewName : viewImplResult.getImplementedViews()) {
            implementedDetails.put(viewName, Map.of(
                    "viewName", viewName,
                    "status", "implemented",
                    "timestamp", viewImplResult.getExecutionDateTime().toString()
            ));
        }

        // Skipped views details
        for (String viewName : viewImplResult.getSkippedViews()) {
            skippedDetails.put(viewName, Map.of(
                    "viewName", viewName,
                    "status", "skipped",
                    "reason", "already implemented or no SQL available"
            ));
        }

        // Error details
        for (ViewImplementationResult.ViewImplementationError error : viewImplResult.getErrors()) {
            errorDetails.put(error.getViewName(), Map.of(
                    "viewName", error.getViewName(),
                    "status", "error",
                    "error", error.getErrorMessage(),
                    "sql", error.getSqlStatement()
            ));
        }

        return Map.of(
                "totalProcessed", viewImplResult.getTotalProcessed(),
                "implementedCount", viewImplResult.getImplementedCount(),
                "skippedCount", viewImplResult.getSkippedCount(),
                "errorCount", viewImplResult.getErrorCount(),
                "isSuccessful", viewImplResult.isSuccessful(),
                "executionTimestamp", viewImplResult.getExecutionTimestamp(),
                "implementedViews", implementedDetails,
                "skippedViews", skippedDetails,
                "errors", errorDetails
        );
    }

    /**
     * Generate summary for view implementation verification results.
     */
    public static Map<String, Object> generateViewImplementationVerificationSummary(ViewImplementationVerificationResult viewImplVerifyResult) {
        Map<String, Object> verifiedDetails = new HashMap<>();
        Map<String, Object> failedDetails = new HashMap<>();
        List<String> warnings = viewImplVerifyResult.getWarnings();

        // Verified views details
        for (ViewImplementationVerificationResult.VerifiedView verifiedView : viewImplVerifyResult.getVerifiedViews()) {
            verifiedDetails.put(verifiedView.getViewName(), Map.of(
                    "viewName", verifiedView.getViewName(),
                    "status", "verified",
                    "rowCount", verifiedView.getRowCount(),
                    "timestamp", viewImplVerifyResult.getExecutionDateTime().toString()
            ));
        }

        // Failed views details
        Map<String, String> failureReasons = viewImplVerifyResult.getFailureReasons();
        for (String viewName : viewImplVerifyResult.getFailedViews()) {
            String reason = failureReasons.getOrDefault(viewName, "Unknown failure");
            failedDetails.put(viewName, Map.of(
                    "viewName", viewName,
                    "status", "failed",
                    "reason", reason
            ));
        }

        return Map.of(
                "totalProcessed", viewImplVerifyResult.getTotalProcessed(),
                "verifiedCount", viewImplVerifyResult.getVerifiedCount(),
                "failedCount", viewImplVerifyResult.getFailedCount(),
                "warningCount", viewImplVerifyResult.getWarningCount(),
                "isSuccessful", viewImplVerifyResult.isSuccessful(),
                "executionTimestamp", viewImplVerifyResult.getExecutionTimestamp(),
                "verifiedViews", verifiedDetails,
                "failedViews", failedDetails,
                "warnings", warnings
        );
    }

    /**
     * Generate summary for unified view verification results.
     */
    public static Map<String, Object> generateViewVerificationSummary(ViewVerificationResult viewVerifyResult) {
        Map<String, Object> viewsBySchemaDetails = new HashMap<>();

        // Process views grouped by schema
        Map<String, List<ViewVerificationResult.ViewInfo>> viewsBySchema = viewVerifyResult.getViewsBySchema();
        for (Map.Entry<String, List<ViewVerificationResult.ViewInfo>> entry : viewsBySchema.entrySet()) {
            String schema = entry.getKey();
            List<ViewVerificationResult.ViewInfo> views = entry.getValue();

            List<Map<String, Object>> viewDetails = views.stream()
                    .map(view -> {
                        Map<String, Object> viewMap = new HashMap<>();
                        viewMap.put("viewName", view.getViewName());
                        viewMap.put("status", view.getStatus().toString());
                        // DDL excluded for performance - fetch on demand via /api/views/postgres/source/{schema}/{viewName}
                        if (view.getErrorMessage() != null) {
                            viewMap.put("errorMessage", view.getErrorMessage());
                        }
                        return viewMap;
                    })
                    .toList();

            viewsBySchemaDetails.put(schema, viewDetails);
        }

        return Map.of(
                "totalViews", viewVerifyResult.getTotalViews(),
                "implementedCount", viewVerifyResult.getImplementedCount(),
                "stubCount", viewVerifyResult.getStubCount(),
                "errorCount", viewVerifyResult.getErrorCount(),
                "executionTimestamp", viewVerifyResult.getExecutionDateTime(),
                "viewsBySchema", viewsBySchemaDetails
        );
    }
}
