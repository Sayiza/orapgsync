package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for DBMS_OUTPUT package.
 * <p>
 * Strategy: Use RAISE NOTICE for immediate output, session variables for buffering.
 */
public class DbmsOutputImpl {

    public static String getPutLine() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_output__put_line(line TEXT)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RAISE NOTICE '%', line;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_output__put_line(TEXT) IS
            'Oracle DBMS_OUTPUT.PUT_LINE equivalent - outputs text via RAISE NOTICE';
            """;
    }

    public static String getPut() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_output__put(text_fragment TEXT)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            DECLARE
                current_buffer TEXT;
            BEGIN
                -- Get current buffer from session variable
                BEGIN
                    current_buffer := current_setting('oracle_compat.dbms_output_buffer', true);
                EXCEPTION
                    WHEN OTHERS THEN
                        current_buffer := '';
                END;

                -- Append new fragment
                PERFORM set_config('oracle_compat.dbms_output_buffer', current_buffer || text_fragment, false);
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_output__put(TEXT) IS
            'Oracle DBMS_OUTPUT.PUT equivalent - buffers text without newline';
            """;
    }

    public static String getNewLine() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_output__new_line()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            DECLARE
                buffer TEXT;
            BEGIN
                -- Get and clear buffer
                BEGIN
                    buffer := current_setting('oracle_compat.dbms_output_buffer', true);
                    PERFORM set_config('oracle_compat.dbms_output_buffer', '', false);
                EXCEPTION
                    WHEN OTHERS THEN
                        buffer := '';
                END;

                -- Output buffered content
                IF buffer != '' THEN
                    RAISE NOTICE '%', buffer;
                END IF;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_output__new_line() IS
            'Oracle DBMS_OUTPUT.NEW_LINE equivalent - flushes buffer with newline';
            """;
    }

    public static String getEnable() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_output__enable(buffer_size INTEGER DEFAULT NULL)
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- No-op stub - output is always enabled in PostgreSQL
                -- Oracle uses this to enable/disable output and set buffer size
                NULL;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_output__enable(INTEGER) IS
            'Oracle DBMS_OUTPUT.ENABLE stub - no-op (output always enabled in PostgreSQL)';
            """;
    }

    public static String getDisable() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_output__disable()
            RETURNS VOID
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- No-op stub - output cannot be disabled in PostgreSQL
                NULL;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_output__disable() IS
            'Oracle DBMS_OUTPUT.DISABLE stub - no-op (output cannot be disabled in PostgreSQL)';
            """;
    }
}
