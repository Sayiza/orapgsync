package me.christianrobert.orapgsync.database.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/api/database/test")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectionTestResource {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTestResource.class);

    @Inject
    OracleConnectionService oracleConnectionService;

    @Inject
    PostgresConnectionService postgresConnectionService;

    @GET
    @Path("/oracle")
    public Response testOracleConnection() {
        log.info("Testing Oracle database connection via REST API");

        try {
            Map<String, Object> result = oracleConnectionService.testConnection();

            if ("success".equals(result.get("status"))) {
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(result)
                        .build();
            }

        } catch (Exception e) {
            log.error("Unexpected error during Oracle connection test", e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "connected", false,
                    "message", "Unexpected error: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/postgres")
    public Response testPostgresConnection() {
        log.info("Testing PostgreSQL database connection via REST API");

        try {
            Map<String, Object> result = postgresConnectionService.testConnection();

            if ("success".equals(result.get("status"))) {
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(result)
                        .build();
            }

        } catch (Exception e) {
            log.error("Unexpected error during PostgreSQL connection test", e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "connected", false,
                    "message", "Unexpected error: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/status")
    public Response getConnectionStatus() {
        log.debug("Getting connection status for both databases");

        Map<String, Object> status = Map.of(
                "oracle", Map.of(
                        "configured", oracleConnectionService.isConfigured(),
                        "configurationStatus", oracleConnectionService.isConfigured() ?
                                "Connection parameters are configured" :
                                "Connection parameters are missing or incomplete"
                ),
                "postgres", Map.of(
                        "configured", postgresConnectionService.isConfigured(),
                        "configurationStatus", postgresConnectionService.isConfigured() ?
                                "Connection parameters are configured" :
                                "Connection parameters are missing or incomplete"
                )
        );

        return Response.ok(status).build();
    }
}