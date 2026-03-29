package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for Oracle HTP (Hypertext Procedures) package.
 * <p>
 * Strategy: Use temporary tables for HTTP buffer storage.
 * The buffer accumulates output during procedure execution and can be retrieved
 * by the web gateway after execution completes.
 * <p>
 * Buffer design:
 * - temp_htp_buffer: Stores output fragments in sequence order
 * - ON COMMIT DROP ensures cleanup after transaction ends
 * - Quarkus gateway calls htp__init() before and htp__get_buffer() after procedure execution
 */
public class HtpImpl {

    /**
     * Initialize the HTP buffer.
     * Called by the web gateway at the start of each request.
     * Creates temp table if not exists and clears any existing content.
     */
    public static String getInit() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__init()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Create temp table for HTTP output buffer
                -- Using ON COMMIT PRESERVE ROWS so it persists across statements in auto-commit mode
                CREATE TEMP TABLE IF NOT EXISTS temp_htp_buffer (
                    seq_num SERIAL PRIMARY KEY,
                    content TEXT NOT NULL
                ) ON COMMIT PRESERVE ROWS;

                -- Clear any existing content (idempotent for multiple inits in same transaction)
                TRUNCATE temp_htp_buffer;

                -- Reset sequence to ensure consistent ordering
                ALTER SEQUENCE temp_htp_buffer_seq_num_seq RESTART WITH 1;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__init() IS
            'Initialize HTP buffer - called by web gateway at request start. Creates/clears temp_htp_buffer table.';
            """;
    }

    /**
     * Get the accumulated buffer contents.
     * Called by the web gateway after procedure execution to retrieve the HTTP response body.
     */
    public static String getGetBuffer() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__get_buffer()
            RETURNS TEXT
            LANGUAGE plpgsql
            AS $$
            DECLARE
                result TEXT := '';
            BEGIN
                -- Check if temp table exists using exception handling
                BEGIN
                    -- Concatenate all buffer contents in sequence order
                    SELECT COALESCE(string_agg(content, '' ORDER BY seq_num), '')
                    INTO result
                    FROM temp_htp_buffer;
                EXCEPTION
                    WHEN undefined_table THEN
                        -- Table doesn't exist yet, return empty string
                        RETURN '';
                END;

                RETURN result;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__get_buffer() IS
            'Retrieve accumulated HTP buffer contents - called by web gateway after procedure execution.';
            """;
    }

    /**
     * HTP.P - Primary output procedure (95% of usage).
     * Appends text with a newline to the buffer.
     */
    public static String getP() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__p(p_text TEXT)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Ensure buffer exists
                CREATE TEMP TABLE IF NOT EXISTS temp_htp_buffer (
                    seq_num SERIAL PRIMARY KEY,
                    content TEXT NOT NULL
                ) ON COMMIT PRESERVE ROWS;

                -- Append text with newline
                INSERT INTO temp_htp_buffer (content) VALUES (COALESCE(p_text, '') || chr(10));
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__p(TEXT) IS
            'Oracle HTP.P equivalent - appends text with newline to HTTP buffer.';
            """;
    }

    /**
     * HTP.PRN - Output without newline.
     * Appends text to the buffer without adding a newline.
     */
    public static String getPrn() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__prn(p_text TEXT)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Ensure buffer exists
                CREATE TEMP TABLE IF NOT EXISTS temp_htp_buffer (
                    seq_num SERIAL PRIMARY KEY,
                    content TEXT NOT NULL
                ) ON COMMIT PRESERVE ROWS;

                -- Append text without newline
                INSERT INTO temp_htp_buffer (content) VALUES (COALESCE(p_text, ''));
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__prn(TEXT) IS
            'Oracle HTP.PRN equivalent - appends text without newline to HTTP buffer.';
            """;
    }

    /**
     * HTP.PRINT - Alias for HTP.P.
     * Included for compatibility with code that uses PRINT instead of P.
     */
    public static String getPrint() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__print(p_text TEXT)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                PERFORM oracle_compat.htp__p(p_text);
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__print(TEXT) IS
            'Oracle HTP.PRINT equivalent - alias for htp__p (appends text with newline).';
            """;
    }

    /**
     * HTP.NL - Output a newline only.
     */
    public static String getNl() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__nl()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Ensure buffer exists
                CREATE TEMP TABLE IF NOT EXISTS temp_htp_buffer (
                    seq_num SERIAL PRIMARY KEY,
                    content TEXT NOT NULL
                ) ON COMMIT PRESERVE ROWS;

                -- Append newline only
                INSERT INTO temp_htp_buffer (content) VALUES (chr(10));
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__nl() IS
            'Oracle HTP.NL equivalent - appends a newline to HTTP buffer.';
            """;
    }

    /**
     * HTP.LINE - Output a horizontal rule.
     * Included as a commonly used simple HTML helper.
     */
    public static String getLine() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__line()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                PERFORM oracle_compat.htp__p('<hr>');
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__line() IS
            'Oracle HTP.LINE equivalent - outputs <hr> tag.';
            """;
    }

    /**
     * HTP.BR - Output a line break.
     * Included as a commonly used simple HTML helper.
     */
    public static String getBr() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.htp__br()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                PERFORM oracle_compat.htp__prn('<br>');
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.htp__br() IS
            'Oracle HTP.BR equivalent - outputs <br> tag.';
            """;
    }
}
