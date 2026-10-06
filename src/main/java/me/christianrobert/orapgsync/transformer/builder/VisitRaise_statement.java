package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Visitor helper for raise_statement grammar rule (PL/SQL exception raising).
 *
 * <p>Oracle and PostgreSQL have <strong>identical syntax</strong> for basic RAISE statements.
 *
 * <p><strong>Grammar:</strong>
 * <pre>
 * raise_statement
 *     : RAISE exception_name?
 * </pre>
 *
 * <p><strong>Two Forms:</strong>
 * <ol>
 *   <li><strong>Re-raise current exception:</strong> {@code RAISE;} (no exception name)
 *     <ul>
 *       <li>Only valid inside EXCEPTION handler</li>
 *       <li>Re-raises the currently handled exception</li>
 *       <li>Syntax identical in Oracle and PostgreSQL</li>
 *     </ul>
 *   </li>
 *   <li><strong>Raise named exception:</strong> {@code RAISE exception_name;}
 *     <ul>
 *       <li>Raises a user-defined exception</li>
 *       <li>Phase 1: Only supports standard exceptions</li>
 *       <li>Phase 3: Will support user-defined exceptions with error code mapping</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><strong>Implementation Scope:</strong>
 * <ul>
 *   <li>✅ Re-raise: {@code RAISE;} → {@code RAISE;} (pass-through)</li>
 *   <li>✅ Raise standard exception: {@code RAISE NO_DATA_FOUND;} → {@code RAISE no_data_found;}</li>
 *   <li>✅ User-defined exceptions: {@code RAISE invalid_salary;} → {@code RAISE EXCEPTION USING ERRCODE = 'P0001';} (Phase 3.1)</li>
 *   <li>✅ RAISE_APPLICATION_ERROR: Handled separately in VisitCall_statement (Phase 2)</li>
 * </ul>
 *
 * <p><strong>Example Transformations:</strong>
 * <pre>
 * -- Re-raise current exception
 * -- Oracle & PostgreSQL (identical)
 * EXCEPTION
 *   WHEN OTHERS THEN
 *     log_error();
 *     RAISE;  -- Re-raise
 * END;
 *
 * -- Raise standard exception
 * -- Oracle
 * IF v_count = 0 THEN
 *   RAISE NO_DATA_FOUND;
 * END IF;
 *
 * -- PostgreSQL
 * IF v_count = 0 THEN
 *   RAISE no_data_found;
 * END IF;
 * </pre>
 *
 * <p><strong>Future Enhancements (Phase 3):</strong>
 * <pre>
 * -- User-defined exception
 * -- Oracle
 * DECLARE
 *   invalid_salary EXCEPTION;
 *   PRAGMA EXCEPTION_INIT(invalid_salary, -20001);
 * BEGIN
 *   RAISE invalid_salary;
 * END;
 *
 * -- PostgreSQL (future Phase 3 transformation)
 * BEGIN
 *   RAISE EXCEPTION 'invalid_salary' USING ERRCODE = 'P0001';
 * END;
 * </pre>
 */
public class VisitRaise_statement {

    public static String v(PlSqlParser.Raise_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Raise_statementContext cannot be null");
        }

        // Check if exception name is provided
        PlSqlParser.Exception_nameContext exceptionNameCtx = ctx.exception_name();

        if (exceptionNameCtx == null) {
            // Re-raise current exception: RAISE;
            // Syntax is identical in Oracle and PostgreSQL
            return "RAISE";
        }

        // Raise named exception: RAISE exception_name;
        String oracleExceptionName = exceptionNameCtx.getText();

        // PHASE 3.1: Check if this is a user-defined exception
        // User-defined exceptions are registered in the exception context
        String errorCode = b.lookupExceptionErrorCode(oracleExceptionName);

        if (errorCode != null) {
            // User-defined exception found in context
            // Transform to: RAISE EXCEPTION USING ERRCODE = 'P0001';
            // Note: PostgreSQL RAISE EXCEPTION doesn't preserve the exception "name"
            // in the same way Oracle does, but the ERRCODE allows catching it
            return "RAISE EXCEPTION USING ERRCODE = '" + errorCode + "'";
        }

        // Standard Oracle exception (not user-defined)
        // Map to PostgreSQL standard exception name
        String postgresExceptionName = mapStandardExceptionName(oracleExceptionName);

        return "RAISE " + postgresExceptionName;
    }

    /**
     * Maps a standard Oracle exception name to its PostgreSQL equivalent.
     *
     * <p>Uses the same mapping as VisitException_handler to ensure consistency.
     * <ul>
     *   <li>Standard exceptions with name changes: ZERO_DIVIDE → division_by_zero</li>
     *   <li>Standard exceptions same name: NO_DATA_FOUND → no_data_found</li>
     *   <li>Unknown: Convert to lowercase (fallback)</li>
     * </ul>
     *
     * <p>Note: This method should ONLY be called for standard Oracle exceptions.
     * User-defined exceptions are handled separately via exception context lookup.
     *
     * @param oracleExceptionName Oracle exception name from AST
     * @return PostgreSQL exception name
     */
    private static String mapStandardExceptionName(String oracleExceptionName) {
        // Use the exception mapping from VisitException_handler
        // This is duplicated here to avoid circular dependencies between visitor helpers
        // In the future, this could be extracted to a shared ExceptionNameMapper utility

        // Handle standard Oracle exceptions with name changes
        String upperName = oracleExceptionName.toUpperCase();

        return switch (upperName) {
            case "ZERO_DIVIDE" -> "division_by_zero";
            case "VALUE_ERROR", "INVALID_NUMBER" -> "invalid_text_representation";
            case "DUP_VAL_ON_INDEX" -> "unique_violation";
            case "INVALID_CURSOR" -> "invalid_cursor_state";
            case "CURSOR_ALREADY_OPEN" -> "duplicate_cursor";
            case "TIMEOUT_ON_RESOURCE" -> "lock_not_available";
            case "LOGIN_DENIED" -> "invalid_authorization_specification";
            case "NOT_LOGGED_ON" -> "connection_does_not_exist";
            case "PROGRAM_ERROR" -> "internal_error";
            case "STORAGE_ERROR" -> "out_of_memory";
            case "ROWTYPE_MISMATCH" -> "datatype_mismatch";
            case "COLLECTION_IS_NULL" -> "null_value_not_allowed";
            case "SUBSCRIPT_BEYOND_COUNT", "SUBSCRIPT_OUTSIDE_LIMIT" -> "array_subscript_error";
            case "TRANSACTION_BACKED_OUT" -> "transaction_rollback";
            case "FOREIGN_KEY_VIOLATION" -> "foreign_key_violation";
            case "CHECK_VIOLATION" -> "check_violation";
            // Standard exceptions with same name (just lowercase)
            case "NO_DATA_FOUND" -> "no_data_found";
            case "TOO_MANY_ROWS" -> "too_many_rows";
            // User-defined exceptions or unknown: just lowercase
            default -> oracleExceptionName.toLowerCase();
        };
    }
}
