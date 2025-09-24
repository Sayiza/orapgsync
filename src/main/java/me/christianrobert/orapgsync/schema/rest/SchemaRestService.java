package me.christianrobert.orapgsync.schema.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.schema.service.OracleSchemaService;
import me.christianrobert.orapgsync.schema.service.PostgresSchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/api/schemas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchemaRestService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRestService.class);

    @Inject
    OracleSchemaService oracleSchemaService;

    @Inject
    PostgresSchemaService postgresSchemaService;

    @GET
    @Path("/oracle")
    public Response getOracleSchemas() {
        log.info("Getting Oracle schemas via REST API");

        try {
            Map<String, Object> result = oracleSchemaService.getSchemas();

            if ("success".equals(result.get("status"))) {
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(result)
                        .build();
            }

        } catch (Exception e) {
            log.error("Unexpected error while getting Oracle schemas", e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "schemas", java.util.List.of(),
                    "count", 0,
                    "message", "Unexpected error: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/postgres")
    public Response getPostgresSchemas() {
        log.info("Getting PostgreSQL schemas via REST API");

        try {
            Map<String, Object> result = postgresSchemaService.getSchemas();

            if ("success".equals(result.get("status"))) {
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(result)
                        .build();
            }

        } catch (Exception e) {
            log.error("Unexpected error while getting PostgreSQL schemas", e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "schemas", java.util.List.of(),
                    "count", 0,
                    "message", "Unexpected error: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/status")
    public Response getSchemasStatus() {
        log.debug("Getting schema availability status for both databases");

        Map<String, Object> status = Map.of(
                "oracle", Map.of(
                        "hasSchemas", oracleSchemaService.hasSchemas(),
                        "message", oracleSchemaService.hasSchemas() ?
                                "Oracle schemas are available" :
                                "No Oracle schemas available or connection issue"
                ),
                "postgres", Map.of(
                        "hasSchemas", postgresSchemaService.hasSchemas(),
                        "message", postgresSchemaService.hasSchemas() ?
                                "PostgreSQL schemas are available" :
                                "No PostgreSQL schemas available or connection issue"
                )
        );

        return Response.ok(status).build();
    }
}