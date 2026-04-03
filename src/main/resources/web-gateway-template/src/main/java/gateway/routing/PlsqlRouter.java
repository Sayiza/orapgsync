package {{packageName}}.gateway.routing;

import {{packageName}}.gateway.config.GatewayConfig;
import {{packageName}}.gateway.execution.PlsqlExecutor;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

/**
 * Routes HTTP requests to PostgreSQL PL/pgSQL procedures.
 * <p>
 * URL patterns supported:
 * <ul>
 *   <li>/pls/{dad}/{schema}.{procedure} - Fully qualified</li>
 *   <li>/pls/{dad}/{package}__{procedure} - Package procedure (flattened)</li>
 *   <li>/pls/{dad}/{procedure} - Uses default schema</li>
 * </ul>
 * <p>
 * Both GET and POST methods are supported. Parameters are passed as
 * query string (GET) or form data (POST).
 */
@Path("/pls/{dadName}")
public class PlsqlRouter {

    private static final Logger LOG = Logger.getLogger(PlsqlRouter.class);

    @Inject
    PlsqlExecutor executor;

    @Inject
    GatewayConfig config;

    /**
     * Handle GET requests.
     */
    @GET
    @Path("/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    public Response handleGet(
            @PathParam("dadName") String dadName,
            @PathParam("path") String path,
            @Context UriInfo uriInfo) {

        return handleRequest(dadName, path, uriInfo.getQueryParameters(), "GET", uriInfo);
    }

    /**
     * Handle POST requests with form data.
     */
    @POST
    @Path("/{path:.*}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response handlePost(
            @PathParam("dadName") String dadName,
            @PathParam("path") String path,
            @Context UriInfo uriInfo,
            MultivaluedMap<String, String> formParams) {

        // Merge query params and form params (form params take precedence)
        MultivaluedMap<String, String> allParams = uriInfo.getQueryParameters();
        formParams.forEach((key, values) -> allParams.put(key, values));

        return handleRequest(dadName, path, allParams, "POST", uriInfo);
    }

    /**
     * Common request handler.
     */
    private Response handleRequest(
            String dadName,
            String path,
            MultivaluedMap<String, String> params,
            String method,
            UriInfo uriInfo) {

        LOG.infof("Request: %s /pls/%s/%s", method, dadName, path);

        // Validate DAD name matches configuration
        if (!dadName.equalsIgnoreCase(config.dadName())) {
            LOG.warnf("Unknown DAD name: %s (expected: %s)", dadName, config.dadName());
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorPage("Unknown DAD: " + dadName))
                    .build();
        }

        try {
            // Parse procedure call from path
            ProcedureCall call = parsePath(path);
            LOG.debugf("Parsed procedure: %s.%s", call.schema, call.procedure);

            // Build CGI environment
            CgiEnvironment cgiEnv = buildCgiEnvironment(method, uriInfo, params);

            // Execute procedure and get HTML result
            String html = executor.execute(call, params, cgiEnv);

            // Check for redirect
            String redirectUrl = executor.getRedirectUrl();
            if (redirectUrl != null && !redirectUrl.isEmpty()) {
                LOG.debugf("Redirecting to: %s", redirectUrl);
                return Response.seeOther(java.net.URI.create(redirectUrl)).build();
            }

            return Response.ok(html).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error executing procedure: %s", path);
            return Response.serverError()
                    .entity(errorPage("Error: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Parse the URL path to extract schema and procedure name.
     * <p>
     * Handles Oracle-style naming and converts to PostgreSQL flattened format:
     * <ul>
     *   <li>schema.package.procedure → schema.package__procedure</li>
     *   <li>schema.procedure → schema.procedure</li>
     *   <li>procedure → defaultSchema.procedure</li>
     * </ul>
     */
    private ProcedureCall parsePath(String path) {
        // Remove leading/trailing slashes
        path = path.replaceAll("^/+|/+$", "");

        String[] parts = path.split("\\.");

        if (parts.length == 3) {
            // schema.package.procedure → schema.package__procedure
            String schema = parts[0];
            String procedure = parts[1] + "__" + parts[2];
            return new ProcedureCall(schema, procedure);
        } else if (parts.length == 2) {
            // schema.procedure (no package)
            return new ProcedureCall(parts[0], parts[1]);
        } else {
            // Just procedure name, use default schema
            return new ProcedureCall(config.defaultSchema(), path);
        }
    }

    /**
     * Build CGI environment variables from request.
     */
    private CgiEnvironment buildCgiEnvironment(
            String method,
            UriInfo uriInfo,
            MultivaluedMap<String, String> params) {

        CgiEnvironment env = new CgiEnvironment();

        env.put("REQUEST_METHOD", method);
        env.put("QUERY_STRING", uriInfo.getRequestUri().getRawQuery());
        env.put("REQUEST_URI", uriInfo.getRequestUri().getPath());
        env.put("SCRIPT_NAME", uriInfo.getPath());
        env.put("SERVER_NAME", uriInfo.getBaseUri().getHost());
        env.put("SERVER_PORT", String.valueOf(uriInfo.getBaseUri().getPort()));
        env.put("SERVER_PROTOCOL", "HTTP/1.1");

        // Add all parameters as CGI variables (for owa_util.get_cgi_env)
        params.forEach((key, values) -> {
            if (!values.isEmpty()) {
                env.put("PARAM_" + key.toUpperCase(), values.get(0));
            }
        });

        return env;
    }

    /**
     * Generate a simple error page.
     */
    private String errorPage(String message) {
        return """
            <!DOCTYPE html>
            <html>
            <head><title>Error</title></head>
            <body>
            <h1>Gateway Error</h1>
            <p>%s</p>
            </body>
            </html>
            """.formatted(message);
    }

    /**
     * Represents a parsed procedure call.
     */
    public static class ProcedureCall {
        public final String schema;
        public final String procedure;

        public ProcedureCall(String schema, String procedure) {
            this.schema = schema;
            this.procedure = procedure;
        }

        /**
         * Get the fully qualified function name for PostgreSQL.
         */
        public String getFullyQualifiedName() {
            return schema.toLowerCase() + "." + procedure.toLowerCase();
        }
    }

    /**
     * CGI environment variables.
     */
    public static class CgiEnvironment extends java.util.HashMap<String, String> {
        /**
         * Convert to JSON for passing to owa__init_cgi_env.
         */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Entry<String, String> entry : entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("\"").append(entry.getKey()).append("\": \"")
                  .append(entry.getValue() != null ? entry.getValue().replace("\"", "\\\"") : "")
                  .append("\"");
            }
            sb.append("}");
            return sb.toString();
        }
    }
}
