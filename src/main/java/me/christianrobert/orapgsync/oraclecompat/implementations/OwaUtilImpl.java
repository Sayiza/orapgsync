package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for Oracle OWA_UTIL package.
 * <p>
 * OWA_UTIL provides utility functions for Oracle web applications,
 * primarily for accessing CGI environment variables and HTTP utilities.
 */
public class OwaUtilImpl {

    /**
     * OWA_UTIL.GET_CGI_ENV - Get a CGI environment variable.
     * Retrieves the value of a CGI variable from the temp_cgi_env table.
     * Returns NULL if the variable is not set.
     */
    public static String getGetCgiEnv() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa_util__get_cgi_env(p_name TEXT)
            RETURNS TEXT
            LANGUAGE plpgsql
            AS $$
            DECLARE
                result TEXT;
            BEGIN
                -- Look up the variable (case-insensitive)
                BEGIN
                    SELECT var_value INTO result
                    FROM temp_cgi_env
                    WHERE var_name = UPPER(p_name);
                EXCEPTION
                    WHEN undefined_table THEN
                        -- Table doesn't exist yet, return NULL
                        RETURN NULL;
                END;

                RETURN result;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa_util__get_cgi_env(TEXT) IS
            'Oracle OWA_UTIL.GET_CGI_ENV equivalent - retrieves CGI environment variable (REQUEST_METHOD, QUERY_STRING, SERVER_NAME, etc.)';
            """;
    }

    /**
     * OWA_UTIL.HTTP_HEADER_CLOSE - Close HTTP headers.
     * In Oracle, this outputs a blank line to separate headers from body.
     * For our implementation, this is mostly a no-op since we handle headers separately.
     */
    public static String getHttpHeaderClose() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa_util__http_header_close()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- In Oracle this outputs a blank line to separate headers from body.
                -- For the PostgreSQL/Quarkus implementation, headers are handled separately,
                -- so this is effectively a no-op. We output a blank line for compatibility.
                PERFORM oracle_compat.htp__nl();
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa_util__http_header_close() IS
            'Oracle OWA_UTIL.HTTP_HEADER_CLOSE equivalent - closes HTTP headers section.';
            """;
    }

    /**
     * OWA_UTIL.MIME_HEADER - Set content type header.
     * For now this is a stub - full header support will be added in a later phase.
     */
    public static String getMimeHeader() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa_util__mime_header(
                p_content_type TEXT DEFAULT 'text/html',
                p_close_header BOOLEAN DEFAULT TRUE
            )
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- TODO: Full header support will be added in Phase 5
                -- For now, store the content type in a session variable for the gateway to retrieve
                PERFORM set_config('oracle_compat.content_type', p_content_type, false);

                IF p_close_header THEN
                    PERFORM oracle_compat.owa_util__http_header_close();
                END IF;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa_util__mime_header(TEXT, BOOLEAN) IS
            'Oracle OWA_UTIL.MIME_HEADER equivalent - sets Content-Type header. Full header support planned for Phase 5.';
            """;
    }

    /**
     * OWA_UTIL.REDIRECT_URL - Redirect to another URL.
     * For now this is a stub - full redirect support will be added in Phase 5.
     */
    public static String getRedirectUrl() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa_util__redirect_url(
                p_url TEXT,
                p_close_header BOOLEAN DEFAULT TRUE
            )
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- TODO: Full redirect support will be added in Phase 5
                -- For now, store the redirect URL in a session variable for the gateway to retrieve
                PERFORM set_config('oracle_compat.redirect_url', p_url, false);

                IF p_close_header THEN
                    PERFORM oracle_compat.owa_util__http_header_close();
                END IF;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa_util__redirect_url(TEXT, BOOLEAN) IS
            'Oracle OWA_UTIL.REDIRECT_URL equivalent - redirects to another URL. Full redirect support planned for Phase 5.';
            """;
    }

    /**
     * OWA_UTIL.PRINT_CGI_ENV - Debug function to print all CGI variables.
     * Useful for debugging and testing.
     */
    public static String getPrintCgiEnv() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.owa_util__print_cgi_env()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            DECLARE
                rec RECORD;
                has_rows BOOLEAN := FALSE;
            BEGIN
                -- Print all CGI variables
                BEGIN
                    PERFORM oracle_compat.htp__p('<table border="1">');
                    PERFORM oracle_compat.htp__p('<tr><th>Variable</th><th>Value</th></tr>');

                    FOR rec IN SELECT var_name, var_value FROM temp_cgi_env ORDER BY var_name
                    LOOP
                        has_rows := TRUE;
                        PERFORM oracle_compat.htp__p('<tr><td>' || rec.var_name || '</td><td>' ||
                                                     COALESCE(rec.var_value, '(null)') || '</td></tr>');
                    END LOOP;

                    IF NOT has_rows THEN
                        PERFORM oracle_compat.htp__p('<tr><td colspan="2">No CGI variables</td></tr>');
                    END IF;

                    PERFORM oracle_compat.htp__p('</table>');
                EXCEPTION
                    WHEN undefined_table THEN
                        PERFORM oracle_compat.htp__p('No CGI environment initialized.');
                END;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.owa_util__print_cgi_env() IS
            'Oracle OWA_UTIL.PRINT_CGI_ENV equivalent - outputs all CGI variables as HTML table. Useful for debugging.';
            """;
    }
}
