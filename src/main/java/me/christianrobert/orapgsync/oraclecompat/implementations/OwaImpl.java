package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for Oracle OWA (Oracle Web Agent) core functions.
 * <p>
 * These functions handle request context initialization (CGI environment variables)
 * that the web gateway sets up before calling the target procedure.
 * <p>
 * CGI environment design:
 * - temp_cgi_env: Stores CGI variables as key-value pairs
 * - ON COMMIT DROP ensures cleanup after transaction ends
 * - Quarkus gateway calls owa__init_cgi_env() with request context as JSON
 */
public class OwaImpl {

    /**
     * Initialize CGI environment from JSON.
     * Called by the web gateway at the start of each request.
     * Creates temp table and populates with CGI variables from the HTTP request.
     */
    public static String getInitCgiEnv() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa__init_cgi_env(p_env JSONB)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Create temp table for CGI environment variables
                CREATE TEMP TABLE IF NOT EXISTS temp_cgi_env (
                    var_name TEXT PRIMARY KEY,
                    var_value TEXT
                ) ON COMMIT PRESERVE ROWS;

                -- Clear any existing content
                TRUNCATE temp_cgi_env;

                -- Populate from JSON object
                INSERT INTO temp_cgi_env (var_name, var_value)
                SELECT UPPER(key), value::TEXT
                FROM jsonb_each_text(p_env);
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa__init_cgi_env(JSONB) IS
            'Initialize CGI environment - called by web gateway with request context as JSON. Example: {"REQUEST_METHOD": "GET", "QUERY_STRING": "id=1"}';
            """;
    }

    /**
     * Initialize the complete request context (buffer + CGI env).
     * Convenience function that combines htp__init() and owa__init_cgi_env().
     */
    public static String getInitRequest() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa__init_request(p_cgi_env JSONB DEFAULT '{}'::JSONB)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Initialize HTP buffer
                PERFORM oracle_compat.htp__init();

                -- Initialize CGI environment
                PERFORM oracle_compat.owa__init_cgi_env(p_cgi_env);
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa__init_request(JSONB) IS
            'Initialize complete request context (HTP buffer + CGI environment). Convenience function combining htp__init() and owa__init_cgi_env().';
            """;
    }
}
