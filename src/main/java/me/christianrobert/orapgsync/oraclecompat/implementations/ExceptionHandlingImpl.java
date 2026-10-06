package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for Oracle exception handling built-in functions.
 * <p>
 * Provides compatibility layer for Oracle exception handling features:
 * - SQLCODE: Maps PostgreSQL SQLSTATE codes to Oracle error numbers
 * - RAISE_APPLICATION_ERROR: Handled via transformation to RAISE EXCEPTION (not a compatibility function)
 * <p>
 * These are standalone functions/pseudo-columns, not part of a package.
 */
public class ExceptionHandlingImpl {

    /**
     * SQLCODE() function - Maps PostgreSQL SQLSTATE to Oracle error numbers.
     * <p>
     * Oracle uses numeric error codes (e.g., -1403, -1422), while PostgreSQL uses
     * 5-character SQLSTATE codes (e.g., '02000', '21000'). This function provides
     * compatibility by mapping SQLSTATE to the nearest Oracle equivalent.
     * <p>
     * Usage in transformed code:
     * <pre>
     * -- Oracle
     * IF SQLCODE = -1403 THEN ...
     *
     * -- PostgreSQL (transformed)
     * IF oracle_compat.sqlcode() = -1403 THEN ...
     * </pre>
     * <p>
     * Mapping strategy:
     * - Standard exceptions: Exact Oracle error number (e.g., NO_DATA_FOUND = -1403)
     * - Unknown exceptions: -20000 (generic user error)
     * - Success: 0
     *
     * @return Oracle-style error code (negative number for errors, 0 for success)
     */
    public static String getSqlcode() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.sqlcode()
            RETURNS integer
            LANGUAGE plpgsql
            STABLE
            AS $$
            BEGIN
              -- Map PostgreSQL SQLSTATE to Oracle SQLCODE
              -- Standard exceptions (most common first for performance)
              RETURN CASE SQLSTATE
                -- Success
                WHEN '00000' THEN 0

                -- SELECT INTO exceptions (most common)
                WHEN '02000' THEN -1403   -- NO_DATA_FOUND
                WHEN 'P0002' THEN -1403   -- NO_DATA_FOUND (PL/pgSQL specific)
                WHEN '21000' THEN -1422   -- TOO_MANY_ROWS

                -- Arithmetic errors
                WHEN '22012' THEN -1476   -- ZERO_DIVIDE (division_by_zero)

                -- Type conversion errors
                WHEN '22P02' THEN -6502   -- VALUE_ERROR / INVALID_NUMBER (invalid_text_representation)
                WHEN '22001' THEN -6502   -- VALUE_ERROR (string_data_right_truncation)
                WHEN '22003' THEN -6502   -- VALUE_ERROR (numeric_value_out_of_range)

                -- Constraint violations
                WHEN '23505' THEN -1      -- DUP_VAL_ON_INDEX (unique_violation)
                WHEN '23503' THEN -2291   -- FOREIGN_KEY_VIOLATION (foreign_key_violation)
                WHEN '23514' THEN -2290   -- CHECK_VIOLATION (check_violation)
                WHEN '23502' THEN -1400   -- NOT NULL violation (not_null_violation)

                -- Cursor exceptions
                WHEN '24000' THEN -1001   -- INVALID_CURSOR (invalid_cursor_state)
                WHEN '42P03' THEN -6511   -- CURSOR_ALREADY_OPEN (duplicate_cursor)

                -- Lock exceptions
                WHEN '55P03' THEN -51     -- TIMEOUT_ON_RESOURCE (lock_not_available)

                -- Connection/authentication exceptions
                WHEN '28000' THEN -1017   -- LOGIN_DENIED (invalid_authorization_specification)
                WHEN '08003' THEN -1012   -- NOT_LOGGED_ON (connection_does_not_exist)
                WHEN '08006' THEN -1012   -- NOT_LOGGED_ON (connection_failure)

                -- Internal errors
                WHEN 'XX000' THEN -6501   -- PROGRAM_ERROR (internal_error)
                WHEN '53200' THEN -6500   -- STORAGE_ERROR (out_of_memory)

                -- Type/collection errors
                WHEN '42804' THEN -6504   -- ROWTYPE_MISMATCH (datatype_mismatch)
                WHEN '39004' THEN -6531   -- COLLECTION_IS_NULL (null_value_not_allowed)
                WHEN '2202E' THEN -6533   -- SUBSCRIPT_BEYOND_COUNT (array_subscript_error)

                -- Transaction exceptions
                WHEN '40001' THEN -8177   -- TRANSACTION_BACKED_OUT (serialization_failure)
                WHEN '40P01' THEN -60     -- TRANSACTION_BACKED_OUT (deadlock_detected)

                -- User-defined exceptions (P0001-P0999 range)
                -- Map back to Oracle user error range (-20001 to -20999)
                WHEN 'P0001' THEN -20001
                WHEN 'P0002' THEN -20002  -- Note: Overlap with NO_DATA_FOUND, check order
                WHEN 'P0003' THEN -20003
                WHEN 'P0004' THEN -20004
                WHEN 'P0005' THEN -20005
                -- Add more P-codes as needed (P0001-P0999 → -20001 to -20999)

                -- Default: Unknown exception → Generic user error
                ELSE -20000
              END;
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.sqlcode() IS
              'Maps PostgreSQL SQLSTATE to Oracle SQLCODE for exception handling compatibility. Returns negative error numbers for exceptions, 0 for success. Standard exceptions map to exact Oracle codes, unknown exceptions return -20000.';
            """;
    }

    /**
     * SQLERRM() is already a PostgreSQL built-in function with identical behavior to Oracle.
     * <p>
     * No compatibility function needed - direct pass-through in transformation.
     * <p>
     * Note: Oracle's SQLERRM(error_code) variant is NOT supported in PostgreSQL.
     * The transformation should detect SQLERRM(code) and add a warning comment.
     */
    // No implementation needed - SQLERRM already exists in PostgreSQL

    /**
     * RAISE_APPLICATION_ERROR is handled via AST transformation, not a compatibility function.
     * <p>
     * Transformation pattern:
     * <pre>
     * -- Oracle
     * RAISE_APPLICATION_ERROR(-20001, 'Custom error message');
     *
     * -- PostgreSQL (transformed via VisitRaise_statement)
     * RAISE EXCEPTION 'Custom error message'
     *   USING ERRCODE = 'P0001',
     *         HINT = 'Original Oracle error code: -20001';
     * </pre>
     * <p>
     * Error code mapping formula:
     * - Oracle: -20000 to -20999
     * - PostgreSQL: 'P0001' to 'P0999' (simple offset: code + 20000)
     * - Example: -20001 → 'P0001', -20055 → 'P0055', -20999 → 'P0999'
     */
    // No implementation needed - handled by visitor transformation
}
