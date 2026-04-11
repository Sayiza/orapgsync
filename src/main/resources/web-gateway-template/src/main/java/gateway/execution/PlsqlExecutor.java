package {{packageName}}.gateway.execution;

import {{packageName}}.gateway.routing.PlsqlRouter.CgiEnvironment;
import {{packageName}}.gateway.routing.PlsqlRouter.ProcedureCall;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.List;

/**
 * Executes PostgreSQL PL/pgSQL procedures that generate HTML output.
 * <p>
 * Workflow:
 * <ol>
 *   <li>Initialize HTP buffer with oracle_compat.htp__init()</li>
 *   <li>Set CGI environment with oracle_compat.owa__init_cgi_env()</li>
 *   <li>Call the target procedure</li>
 *   <li>Extract buffer with oracle_compat.htp__get_buffer()</li>
 *   <li>Check for redirect URL</li>
 * </ol>
 */
@ApplicationScoped
public class PlsqlExecutor {

    private static final Logger LOG = Logger.getLogger(PlsqlExecutor.class);

    @Inject
    AgroalDataSource dataSource;

    // Thread-local to store redirect URL from procedure execution
    private final ThreadLocal<String> redirectUrl = new ThreadLocal<>();

    /**
     * Execute a PL/pgSQL procedure and return the generated HTML.
     *
     * @param call The procedure to call
     * @param params HTTP parameters to pass to the procedure
     * @param cgiEnv CGI environment variables
     * @return The generated HTML content
     */
    public String execute(
            ProcedureCall call,
            MultivaluedMap<String, String> params,
            CgiEnvironment cgiEnv) throws SQLException {

        redirectUrl.remove();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Step 1: Initialize HTP buffer and CGI environment
                initializeRequest(conn, cgiEnv);

                // Step 2: Call the procedure
                callProcedure(conn, call, params);

                // Step 3: Check for redirect
                checkRedirect(conn);

                // Step 4: Extract buffer
                String result = extractBuffer(conn);

                // Step 5: Commit
                conn.commit();

                LOG.debugf("Procedure %s executed successfully, buffer size: %d",
                        call.getFullyQualifiedName(), result.length());

                return result;

            } catch (SQLException e) {
                conn.rollback();
                LOG.errorf(e, "Error executing procedure %s", call.getFullyQualifiedName());
                throw e;
            }
        }
    }

    /**
     * Get the redirect URL if set by the procedure.
     */
    public String getRedirectUrl() {
        return redirectUrl.get();
    }

    /**
     * Initialize HTP buffer and CGI environment.
     * <p>
     * Session state (package variables, content-type, redirect URL) is automatically
     * reset when the transaction commits because they use transaction-local storage.
     */
    private void initializeRequest(Connection conn, CgiEnvironment cgiEnv) throws SQLException {
        // Initialize buffer
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT oracle_compat.htp__init()");
        }

        // Set CGI environment
        String cgiJson = cgiEnv.toJson();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT oracle_compat.owa__init_cgi_env(?::jsonb)")) {
            ps.setString(1, cgiJson);
            ps.execute();
        }

        LOG.debugf("Initialized request with CGI env: %s", cgiJson);
    }

    /**
     * Call the target procedure with parameters.
     */
    private void callProcedure(
            Connection conn,
            ProcedureCall call,
            MultivaluedMap<String, String> params) throws SQLException {

        // Build the procedure call
        // For now, we call procedures without parameters
        // TODO: Add support for parameter passing (Phase 4)
        String sql = "SELECT " + call.getFullyQualifiedName() + "()";

        LOG.debugf("Executing: %s", sql);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Check if the procedure set a redirect URL.
     */
    private void checkRedirect(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT current_setting('oracle_compat.redirect_url', true)")) {
            if (rs.next()) {
                String url = rs.getString(1);
                if (url != null && !url.isEmpty()) {
                    redirectUrl.set(url);
                    LOG.debugf("Redirect URL detected: %s", url);
                }
            }
        }
    }

    /**
     * Extract the accumulated HTP buffer.
     */
    private String extractBuffer(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT oracle_compat.htp__get_buffer()")) {
            if (rs.next()) {
                String result = rs.getString(1);
                return result != null ? result : "";
            }
            return "";
        }
    }
}
