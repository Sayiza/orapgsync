package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for DBMS_UTILITY package.
 * <p>
 * Provides utility functions for error handling, timing, and formatting.
 */
public class DbmsUtilityImpl {

    public static String getFormatErrorStack() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_utility__format_error_stack()
            RETURNS TEXT
            LANGUAGE plpgsql
            AS $$
            DECLARE
                error_message TEXT;
                error_detail TEXT;
            BEGIN
                GET STACKED DIAGNOSTICS
                    error_message = MESSAGE_TEXT,
                    error_detail = PG_EXCEPTION_DETAIL;

                RETURN 'ERROR: ' || COALESCE(error_message, 'No error') ||
                       CASE WHEN error_detail IS NOT NULL THEN E'\\nDETAIL: ' || error_detail ELSE '' END;
            EXCEPTION
                WHEN OTHERS THEN
                    RETURN 'ERROR: Unable to retrieve error stack';
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_utility__format_error_stack() IS
            'Oracle DBMS_UTILITY.FORMAT_ERROR_STACK equivalent - returns current exception message';
            """;
    }

    public static String getFormatErrorBacktrace() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_utility__format_error_backtrace()
            RETURNS TEXT
            LANGUAGE plpgsql
            AS $$
            DECLARE
                error_context TEXT;
            BEGIN
                GET STACKED DIAGNOSTICS error_context = PG_EXCEPTION_CONTEXT;
                RETURN COALESCE(error_context, 'No backtrace available');
            EXCEPTION
                WHEN OTHERS THEN
                    RETURN 'ERROR: Unable to retrieve error backtrace';
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_utility__format_error_backtrace() IS
            'Oracle DBMS_UTILITY.FORMAT_ERROR_BACKTRACE equivalent - returns exception context (partial)';
            """;
    }

    public static String getGetTime() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_utility__get_time()
            RETURNS INTEGER
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RETURN EXTRACT(EPOCH FROM clock_timestamp())::INTEGER * 100;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_utility__get_time() IS
            'Oracle DBMS_UTILITY.GET_TIME equivalent - returns centiseconds since epoch';
            """;
    }
}
