package me.christianrobert.orapgsync.config.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.config.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Path("/api/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigRestService {

    private static final Logger log = LoggerFactory.getLogger(ConfigRestService.class);

    @Inject
    ConfigService configService;

    @GET
    public Response getConfiguration() {
        log.info("Getting configuration");

        Map<String, Object> config = configService.getAllConfiguration();
        return Response.ok(config).build();
    }

    @POST
    public Response saveConfiguration(Map<String, Object> config) {
        log.info("Saving configuration with {} entries", config.size());

        try {
            configService.updateConfiguration(config);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Configuration saved successfully");

            return Response.ok(response).build();
        } catch (Exception e) {
            log.error("Error saving configuration", e);

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Failed to save configuration: " + e.getMessage());

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }

    @GET
    @Path("/{key}")
    public Response getConfigValue(@PathParam("key") String key) {
        log.debug("Getting config value for key: {}", key);

        Object value = configService.getConfigValue(key);
        if (value == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Configuration key not found: " + key))
                    .build();
        }

        return Response.ok(Map.of("key", key, "value", value)).build();
    }

    @PUT
    @Path("/{key}")
    public Response setConfigValue(@PathParam("key") String key, Map<String, Object> body) {
        log.debug("Setting config value for key: {}", key);

        if (!body.containsKey("value")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Request body must contain 'value' field"))
                    .build();
        }

        Object value = body.get("value");
        configService.setConfigValue(key, value);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Configuration value updated successfully");
        response.put("key", key);
        response.put("value", value);

        return Response.ok(response).build();
    }

    @POST
    @Path("/reset")
    public Response resetConfiguration() {
        log.info("Resetting configuration to defaults");

        try {
            configService.resetToDefaults();

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Configuration reset to defaults successfully");

            return Response.ok(response).build();
        } catch (Exception e) {
            log.error("Error resetting configuration", e);

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Failed to reset configuration: " + e.getMessage());

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }
}