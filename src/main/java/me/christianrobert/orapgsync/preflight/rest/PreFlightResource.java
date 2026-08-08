package me.christianrobert.orapgsync.preflight.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityFinding;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityReport;
import me.christianrobert.orapgsync.core.job.model.preflight.ConstructStat;
import me.christianrobert.orapgsync.core.job.model.preflight.FailureStat;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.preflight.service.CompatibilityReportAggregator;
import me.christianrobert.orapgsync.transformer.analysis.DetectedConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for the pre-flight compatibility report.
 *
 * <p>The aggregate endpoint stays small no matter how many objects were analysed; per-object
 * findings are served separately and filtered, so the frontend never has to pull thousands of
 * rows to show a summary.</p>
 */
@ApplicationScoped
@Path("/api/preflight")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PreFlightResource {

    private static final Logger log = LoggerFactory.getLogger(PreFlightResource.class);

    private static final int DEFAULT_FINDING_LIMIT = 200;

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @Inject
    StateService stateService;

    @Inject
    CompatibilityReportAggregator aggregator;

    /**
     * Starts the analysis job. Requires extracted Oracle views in state; touches no database.
     */
    @POST
    @Path("/oracle/analyze")
    public Response analyze() {
        log.info("Starting pre-flight compatibility analysis via REST API");

        try {
            Job<?> job = jobRegistry.createJob("ORACLE", "COMPATIBILITY_REPORT")
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No job available for ORACLE COMPATIBILITY_REPORT operation"));

            String jobId = jobService.submitJob(job);

            return Response.ok(Map.of(
                    "status", "success",
                    "jobId", jobId,
                    "message", "Pre-flight compatibility analysis started"
            )).build();

        } catch (Exception e) {
            log.error("Failed to start pre-flight compatibility analysis", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "status", "error",
                            "message", "Failed to start analysis: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Aggregated report: status counts plus constructs ranked by how many failing objects
     * they appear in.
     */
    @GET
    @Path("/report")
    public Response getReport() {
        List<CompatibilityFinding> findings = stateService.getCompatibilityFindings();

        if (findings == null || findings.isEmpty()) {
            return Response.ok(Map.of(
                    "status", "empty",
                    "message", "No compatibility report available. Run the analysis first."
            )).build();
        }

        CompatibilityReport report = aggregator.aggregate(findings);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("generatedAt", report.getGeneratedAt().toString());
        body.put("analyzedObjectCount", report.getAnalyzedObjectCount());
        body.put("failureCount", report.getFailureCount());

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        report.getStatusCounts().forEach((status, count) -> statusCounts.put(status.name(), count));
        body.put("statusCounts", statusCounts);

        body.put("constructs", report.getConstructs().stream().map(this::toMap).toList());
        body.put("silentLosses", report.getSilentLosses().stream().map(ConstructStat::getConstructId).toList());
        body.put("failureGroups", report.getFailureGroups().stream().map(this::toMap).toList());

        return Response.ok(body).build();
    }

    /**
     * Per-object findings, filtered so the frontend can load only what it displays.
     *
     * @param status    optional status filter (OK, OK_WITH_WARNINGS, PARSE_ERROR, TRUNCATED_PARSE,
     *                  TRANSFORM_ERROR, NO_SOURCE)
     * @param construct optional construct id filter (e.g. PIVOT)
     * @param signature optional failure signature filter, as returned in the report's failure
     *                  groups; selects exactly the objects behind one group
     * @param limit     maximum number of findings returned
     * @param offset    number of matching findings to skip
     */
    @GET
    @Path("/report/findings")
    public Response getFindings(@QueryParam("status") String status,
                                @QueryParam("construct") String construct,
                                @QueryParam("signature") String signature,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("offset") Integer offset) {

        List<CompatibilityFinding> findings = stateService.getCompatibilityFindings();
        if (findings == null) {
            findings = List.of();
        }

        List<CompatibilityFinding> matching = new ArrayList<>();
        for (CompatibilityFinding finding : findings) {
            if (status != null && !status.isBlank() && !finding.getStatus().name().equalsIgnoreCase(status)) {
                continue;
            }
            if (construct != null && !construct.isBlank()
                    && finding.getConstructs().stream().noneMatch(c -> c.id().equalsIgnoreCase(construct))) {
                continue;
            }
            if (signature != null && !signature.isBlank()
                    && !signature.equals(aggregator.normalizeFailureMessage(finding.getErrorMessage()))) {
                continue;
            }
            matching.add(finding);
        }

        int effectiveOffset = offset != null && offset > 0 ? offset : 0;
        int effectiveLimit = limit != null && limit > 0 ? limit : DEFAULT_FINDING_LIMIT;

        List<Map<String, Object>> page = matching.stream()
                .skip(effectiveOffset)
                .limit(effectiveLimit)
                .map(this::toMap)
                .toList();

        return Response.ok(Map.of(
                "status", "success",
                "totalMatching", matching.size(),
                "offset", effectiveOffset,
                "limit", effectiveLimit,
                "findings", page
        )).build();
    }

    private Map<String, Object> toMap(ConstructStat stat) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("constructId", stat.getConstructId());
        map.put("displayName", stat.getDisplayName());
        map.put("support", stat.getSupport().name());
        map.put("note", stat.getNote());
        map.put("occurrences", stat.getOccurrences());
        map.put("failingObjectCount", stat.getFailingObjectCount());
        map.put("passingObjectCount", stat.getPassingObjectCount());
        map.put("silentLoss", stat.isSilentLoss());
        map.put("failingExamples", stat.getFailingExamples());
        map.put("passingExamples", stat.getPassingExamples());
        return map;
    }

    private Map<String, Object> toMap(FailureStat stat) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", stat.getStatus().name());
        map.put("signature", stat.getSignature());
        map.put("objectCount", stat.getObjectCount());
        map.put("exampleMessage", stat.getExampleMessage());
        map.put("examples", stat.getExamples());
        return map;
    }

    private Map<String, Object> toMap(CompatibilityFinding finding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("objectType", finding.getObjectType());
        map.put("schema", finding.getSchema());
        map.put("objectName", finding.getObjectName());
        map.put("qualifiedName", finding.getQualifiedName());
        map.put("status", finding.getStatus().name());
        map.put("errorMessage", finding.getErrorMessage());
        map.put("constructs", finding.getConstructs().stream().map(this::toMap).toList());
        return map;
    }

    private Map<String, Object> toMap(DetectedConstruct construct) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", construct.id());
        map.put("displayName", construct.displayName());
        map.put("support", construct.support().name());
        map.put("line", construct.line());
        map.put("snippet", construct.snippet());
        map.put("note", construct.note());
        return map;
    }
}
