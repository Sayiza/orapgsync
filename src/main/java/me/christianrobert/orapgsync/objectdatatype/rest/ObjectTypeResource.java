package me.christianrobert.orapgsync.objectdatatype.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for object type (composite type) operations.
 * Handles object type extraction from Oracle/PostgreSQL and object type creation in PostgreSQL.
 */
@ApplicationScoped
@Path("/api/objects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ObjectTypeResource {

    private static final Logger log = LoggerFactory.getLogger(ObjectTypeResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleObjectTypes() {
        return startJob("ORACLE", "OBJECT_DATATYPE", "Oracle object data type extraction");
    }

    @POST
    @Path("/postgres/extract")
    public Response extractPostgresObjectTypes() {
        return startJob("POSTGRES", "OBJECT_DATATYPE", "PostgreSQL object data type extraction");
    }

    @POST
    @Path("/postgres/create")
    public Response createPostgresObjectTypes() {
        return startJob("POSTGRES", "OBJECT_TYPE_CREATION", "PostgreSQL object type creation");
    }

    /**
     * Generic method to start any object type-related job.
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
     * Generate summary for object type metadata extraction results.
     */
    public static Map<String, Object> generateObjectDataTypeSummary(List<ObjectDataTypeMetaData> objectDataTypes) {
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

    /**
     * Generate summary for object type creation results.
     */
    public static Map<String, Object> generateObjectTypeCreationSummary(ObjectTypeCreationResult objectTypeResult) {
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
}
