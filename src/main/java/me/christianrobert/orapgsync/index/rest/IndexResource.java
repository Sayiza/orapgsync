package me.christianrobert.orapgsync.index.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.index.IndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.core.job.model.index.IndexOutcome;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for index migration: extraction of Oracle indexes and their creation in
 * PostgreSQL.
 */
@ApplicationScoped
@Path("/api/indexes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IndexResource {

    private static final Logger log = LoggerFactory.getLogger(IndexResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/oracle/extract")
    public Response extractOracleIndexes() {
        return startJob("ORACLE", "INDEX", "Oracle index extraction");
    }

    @POST
    @Path("/postgres/create")
    public Response createPostgresIndexes() {
        return startJob("POSTGRES", "INDEX_CREATION", "PostgreSQL index creation");
    }

    private Response startJob(String database, String operationType, String friendlyName) {
        log.info("Starting {} job via REST API", friendlyName);

        try {
            Job<?> job = jobRegistry.createJob(database, operationType)
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("No job available for %s %s operation", database, operationType)));

            String jobId = jobService.submitJob(job);

            return Response.ok(Map.of(
                    "status", "success",
                    "jobId", jobId,
                    "message", friendlyName + " job started successfully"
            )).build();

        } catch (Exception e) {
            log.error("Failed to start {} job", friendlyName, e);
            return Response.serverError().entity(Map.of(
                    "status", "error",
                    "message", "Failed to start " + friendlyName + ": " + e.getMessage()
            )).build();
        }
    }

    /**
     * Summary of extracted Oracle indexes, grouped by the characteristics that decide whether
     * they can be migrated.
     */
    public static Map<String, Object> generateIndexExtractionSummary(List<IndexMetadata> indexes) {
        Map<String, Object> summary = new HashMap<>();

        long unique = indexes.stream().filter(IndexMetadata::isUnique).count();
        long functionBased = indexes.stream().filter(IndexMetadata::isFunctionBased).count();
        long bitmap = indexes.stream().filter(IndexMetadata::isBitmap).count();
        long domain = indexes.stream().filter(IndexMetadata::isDomain).count();

        List<Map<String, Object>> details = new ArrayList<>();
        for (IndexMetadata index : indexes) {
            details.add(Map.of(
                    "schema", nullSafe(index.getSchema()),
                    "tableName", nullSafe(index.getTableName()),
                    "indexName", nullSafe(index.getIndexName()),
                    "keys", nullSafe(index.getKeyDisplay()),
                    "unique", index.isUnique(),
                    "indexType", nullSafe(index.getIndexType()),
                    "functionBased", index.isFunctionBased()
            ));
        }

        summary.put("indexCount", indexes.size());
        summary.put("uniqueCount", unique);
        summary.put("functionBasedCount", functionBased);
        summary.put("bitmapCount", bitmap);
        summary.put("domainCount", domain);
        summary.put("indexes", details);

        return summary;
    }

    /**
     * Summary of index creation. Every outcome is listed with its reason, so an index that was
     * skipped or refused can be told apart from one that was never considered.
     */
    public static Map<String, Object> generateIndexCreationSummary(IndexCreationResult result) {
        Map<String, Object> summary = new HashMap<>();

        summary.put("totalProcessed", result.getTotalProcessed());
        summary.put("createdCount", result.getCreatedCount());
        summary.put("skippedCount", result.getSkippedCount());
        summary.put("unsupportedCount", result.getUnsupportedCount());
        summary.put("errorCount", result.getErrorCount());
        summary.put("isSuccessful", result.isSuccessful());
        summary.put("executionTimestamp", result.getExecutionDateTime().toString());

        summary.put("createdIndexes", toDetails(result.getCreatedIndexes()));
        summary.put("skippedIndexes", toDetails(result.getSkippedIndexes()));
        summary.put("unsupportedIndexes", toDetails(result.getUnsupportedIndexes()));
        summary.put("errors", toDetails(result.getErrors()));

        return summary;
    }

    private static List<Map<String, Object>> toDetails(List<IndexOutcome> outcomes) {
        List<Map<String, Object>> details = new ArrayList<>(outcomes.size());
        for (IndexOutcome outcome : outcomes) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("tableName", nullSafe(outcome.qualifiedTableName()));
            detail.put("indexName", nullSafe(outcome.indexName()));
            detail.put("keys", nullSafe(outcome.keyDisplay()));
            detail.put("status", outcome.status().name().toLowerCase());
            detail.put("reason", nullSafe(outcome.reason()));
            detail.put("sql", nullSafe(outcome.sqlStatement()));
            details.add(detail);
        }
        return details;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
