package me.christianrobert.orapgsync.web.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.web.model.WebGatewayGenerationResult;
import me.christianrobert.orapgsync.web.service.WebGatewayGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoints for web gateway project generation.
 */
@Path("/api/web-gateway")
@Produces(MediaType.APPLICATION_JSON)
public class WebGatewayResource {

    private static final Logger log = LoggerFactory.getLogger(WebGatewayResource.class);

    @Inject
    WebGatewayGenerator generator;

    @Inject
    ConfigService configService;

    /**
     * Generate the web gateway project.
     * <p>
     * POST /api/web-gateway/generate
     * <p>
     * Generates the Quarkus web gateway project to the configured output directory.
     * The directory must be empty or not exist.
     *
     * @return Generation result with list of created files
     */
    @POST
    @Path("/generate")
    public Response generate() {
        log.info("Web gateway generation requested");

        try {
            WebGatewayGenerationResult result = generator.generate();

            if (result.isSuccess()) {
                log.info("Web gateway generated successfully: {} files", result.getFileCount());
                return Response.ok(result).build();
            } else {
                log.warn("Web gateway generation completed with errors: {}", result.getErrors());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(result)
                        .build();
            }

        } catch (IllegalStateException e) {
            // Directory not empty
            log.warn("Generation failed: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("type", "DIRECTORY_NOT_EMPTY");
            return Response.status(Response.Status.CONFLICT)
                    .entity(error)
                    .build();

        } catch (Exception e) {
            log.error("Web gateway generation failed", e);
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("type", "GENERATION_ERROR");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(error)
                    .build();
        }
    }

    /**
     * Get current web gateway configuration.
     * <p>
     * GET /api/web-gateway/config
     */
    @GET
    @Path("/config")
    public Response getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("outputPath", configService.getConfigValueAsString("web-gateway.output-path"));
        config.put("dadName", configService.getConfigValueAsString("web-gateway.dad-name"));
        config.put("urlPrefix", configService.getConfigValueAsString("web-gateway.url-prefix"));
        config.put("serverPort", configService.getConfigValueAsString("web-gateway.server-port"));
        config.put("packageName", configService.getConfigValueAsString("java.generated-package-name"));

        return Response.ok(config).build();
    }

    /**
     * Update web gateway configuration.
     * <p>
     * PUT /api/web-gateway/config
     */
    @PUT
    @Path("/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, String> newConfig) {
        log.info("Updating web gateway configuration: {}", newConfig);

        if (newConfig.containsKey("outputPath")) {
            configService.setConfigValue("web-gateway.output-path", newConfig.get("outputPath"));
        }
        if (newConfig.containsKey("dadName")) {
            configService.setConfigValue("web-gateway.dad-name", newConfig.get("dadName"));
        }
        if (newConfig.containsKey("urlPrefix")) {
            configService.setConfigValue("web-gateway.url-prefix", newConfig.get("urlPrefix"));
        }
        if (newConfig.containsKey("serverPort")) {
            configService.setConfigValue("web-gateway.server-port", newConfig.get("serverPort"));
        }

        return Response.ok().build();
    }
}
