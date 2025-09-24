package me.christianrobert.orapgsync.objectdatatype.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.objectdatatype.service.OracleObjectDataTypeService;
import me.christianrobert.orapgsync.objectdatatype.service.PostgresObjectDataTypeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/api/objectdatatypes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ObjectDataTypeRestService {

    private static final Logger log = LoggerFactory.getLogger(ObjectDataTypeRestService.class);

    @Inject
    OracleObjectDataTypeService oracleObjectDataTypeService;

    @Inject
    PostgresObjectDataTypeService postgresObjectDataTypeService;

    @GET
    @Path("/oracle")
    public Response getOracleObjectDataTypes() {
        log.info("Getting Oracle object data types via REST API");

        try {
            Map<String, Object> result = oracleObjectDataTypeService.getObjectDataTypes();

            if ("success".equals(result.get("status"))) {
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(result)
                        .build();
            }

        } catch (Exception e) {
            log.error("Unexpected error while getting Oracle object data types", e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "objectDataTypesBySchema", Map.of(),
                    "totalCount", 0,
                    "message", "Unexpected error: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/postgres")
    public Response getPostgresObjectDataTypes() {
        log.info("Getting PostgreSQL object data types via REST API");

        try {
            Map<String, Object> result = postgresObjectDataTypeService.getObjectDataTypes();

            if ("success".equals(result.get("status"))) {
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(result)
                        .build();
            }

        } catch (Exception e) {
            log.error("Unexpected error while getting PostgreSQL object data types", e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "objectDataTypesBySchema", Map.of(),
                    "totalCount", 0,
                    "message", "Unexpected error: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/status")
    public Response getObjectDataTypesStatus() {
        log.debug("Getting object data type availability status for both databases");

        Map<String, Object> status = Map.of(
                "oracle", Map.of(
                        "hasObjectDataTypes", oracleObjectDataTypeService.hasObjectDataTypes(),
                        "message", oracleObjectDataTypeService.hasObjectDataTypes() ?
                                "Oracle object data types are available" :
                                "No Oracle object data types available or connection issue"
                ),
                "postgres", Map.of(
                        "hasObjectDataTypes", postgresObjectDataTypeService.hasObjectDataTypes(),
                        "message", postgresObjectDataTypeService.hasObjectDataTypes() ?
                                "PostgreSQL object data types are available" :
                                "No PostgreSQL object data types available or connection issue"
                )
        );

        return Response.ok(status).build();
    }
}