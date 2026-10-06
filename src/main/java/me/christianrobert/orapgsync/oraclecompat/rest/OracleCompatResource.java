package me.christianrobert.orapgsync.oraclecompat.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.service.JobRegistry;
import me.christianrobert.orapgsync.core.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * REST resource for Oracle compatibility layer operations.
 * Handles installation and verification of PostgreSQL equivalents for Oracle built-in packages.
 */
@ApplicationScoped
@Path("/api/oracle-compat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OracleCompatResource {

    private static final Logger log = LoggerFactory.getLogger(OracleCompatResource.class);

    @Inject
    JobService jobService;

    @Inject
    JobRegistry jobRegistry;

    @POST
    @Path("/postgres/install")
    public Response installOracleCompatLayer() {
        return startJob("POSTGRES", "ORACLE_COMPAT_INSTALLATION", "PostgreSQL Oracle compatibility installation");
    }

    @POST
    @Path("/postgres/verify")
    public Response verifyOracleCompatLayer() {
        return startJob("POSTGRES", "ORACLE_COMPAT_VERIFICATION", "PostgreSQL Oracle compatibility verification");
    }

    /**
     * Generic method to start any Oracle compatibility-related job.
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
}
