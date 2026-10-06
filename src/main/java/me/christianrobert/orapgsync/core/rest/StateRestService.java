package me.christianrobert.orapgsync.core.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.core.job.service.JobService;
import me.christianrobert.orapgsync.core.service.StateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/api/state")
@Produces(MediaType.APPLICATION_JSON)
public class StateRestService {

    private static final Logger log = LoggerFactory.getLogger(StateRestService.class);

    @Inject
    StateService stateService;

    @Inject
    JobService jobService;

    @GET
    public Response getCurrentState() {
        log.debug("Getting current application state");

        Map<String, Object> state = Map.of(
                "oracleSchemas", Map.of(
                        "count", stateService.getOracleSchemaNames().size(),
                        "schemas", stateService.getOracleSchemaNames()
                ),
                "postgresSchemas", Map.of(
                        "count", stateService.getPostgresSchemaNames().size(),
                        "schemas", stateService.getPostgresSchemaNames()
                ),
                "oracleObjectDataTypes", Map.of(
                        "count", stateService.getOracleObjectDataTypeMetaData().size(),
                        "types", stateService.getOracleObjectDataTypeMetaData().stream()
                                .map(odt -> Map.of(
                                        "schema", odt.getSchema(),
                                        "name", odt.getName(),
                                        "variableCount", odt.getVariables().size()
                                ))
                                .toList()
                )
        );

        return Response.ok(state).build();
    }

    @GET
    @Path("/reset")
    public Response resetState() {
        log.info("Resetting application state and job history");

        // Clear metadata state
        stateService.resetState();

        // Clear job execution history (critical for preventing memory leaks)
        jobService.resetJobs();

        log.info("State and job history reset completed successfully");
        return Response.ok(Map.of("message", "State and job history reset successfully")).build();
    }
}