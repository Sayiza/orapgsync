package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Visitor helper for exception_handler grammar rule (PL/SQL exception handling).
 *
 * <p>Oracle and PostgreSQL have similar EXCEPTION handling syntax with key differences:
 *
 * <p><strong>Grammar:</strong>
 * <pre>
 * exception_handler
 *     : WHEN exception_name (OR exception_name)* THEN seq_of_statements
 * </pre>
 *
 * <p><strong>Key Differences:</strong>
 * <ul>
 *   <li>Exception name casing:
 *     <ul>
 *       <li>Oracle: UPPER_CASE (e.g., NO_DATA_FOUND, TOO_MANY_ROWS)</li>
 *       <li>PostgreSQL: lower_case (e.g., no_data_found, too_many_rows)</li>
 *     </ul>
 *   </li>
 *   <li>Exception name mapping:
 *     <ul>
 *       <li>Some Oracle exceptions map to different PostgreSQL names</li>
 *       <li>Example: ZERO_DIVIDE → division_by_zero</li>
 *       <li>Example: DUP_VAL_ON_INDEX → unique_violation</li>
 *     </ul>
 *   </li>
 *   <li>WHEN OTHERS remains "OTHERS" (uppercase) in both databases</li>
 * </ul>
 *
 * <p><strong>Example Transformation:</strong>
 * <pre>
 * -- Oracle
 * EXCEPTION
 *   WHEN NO_DATA_FOUND THEN
 *     DBMS_OUTPUT.PUT_LINE('Not found');
 *   WHEN TOO_MANY_ROWS OR VALUE_ERROR THEN
 *     DBMS_OUTPUT.PUT_LINE('Data error');
 *   WHEN OTHERS THEN
 *     DBMS_OUTPUT.PUT_LINE('Error: ' || SQLERRM);
 *
 * -- PostgreSQL
 * EXCEPTION
 *   WHEN no_data_found THEN
 *     PERFORM oracle_compat.dbms_output__put_line('Not found');
 *   WHEN too_many_rows OR invalid_text_representation THEN
 *     PERFORM oracle_compat.dbms_output__put_line('Data error');
 *   WHEN OTHERS THEN
 *     PERFORM oracle_compat.dbms_output__put_line('Error: ' || SQLERRM);
 * </pre>
 */
public class VisitException_handler {

    /**
     * Maps Oracle standard exception names to PostgreSQL exception names.
     *
     * <p>This map handles:
     * <ul>
     *   <li>Case conversion (Oracle UPPER → PostgreSQL lower)</li>
     *   <li>Name differences (Oracle ZERO_DIVIDE → PostgreSQL division_by_zero)</li>
     *   <li>Complete mapping of 20+ standard Oracle exceptions</li>
     * </ul>
     *
     * <p>Note: OTHERS is a special case and remains uppercase in both databases.
     */
    private static final Map<String, String> EXCEPTION_NAME_MAP = createExceptionNameMap();

    private static Map<String, String> createExceptionNameMap() {
        Map<String, String> map = new HashMap<>();

        // Standard Oracle exceptions (UPPER_CASE) → PostgreSQL (lower_case)
        // Most common exceptions (top priority)
        map.put("NO_DATA_FOUND", "no_data_found");           // SELECT INTO returns 0 rows
        map.put("TOO_MANY_ROWS", "too_many_rows");           // SELECT INTO returns >1 row
        map.put("ZERO_DIVIDE", "division_by_zero");          // Division by zero (NAME CHANGE)
        map.put("VALUE_ERROR", "invalid_text_representation"); // Type conversion error (NAME CHANGE)
        map.put("INVALID_NUMBER", "invalid_text_representation"); // Invalid number format (NAME CHANGE)

        // Constraint violations
        map.put("DUP_VAL_ON_INDEX", "unique_violation");     // Unique constraint violation (NAME CHANGE)
        map.put("FOREIGN_KEY_VIOLATION", "foreign_key_violation"); // FK constraint violation
        map.put("CHECK_VIOLATION", "check_violation");       // Check constraint violation

        // Cursor-related exceptions
        map.put("INVALID_CURSOR", "invalid_cursor_state");   // Invalid cursor operation (NAME CHANGE)
        map.put("CURSOR_ALREADY_OPEN", "duplicate_cursor");  // Cursor already open (NAME CHANGE)

        // Resource and connection exceptions
        map.put("TIMEOUT_ON_RESOURCE", "lock_not_available"); // Lock timeout (NAME CHANGE)
        map.put("LOGIN_DENIED", "invalid_authorization_specification"); // Login failed (NAME CHANGE)
        map.put("NOT_LOGGED_ON", "connection_does_not_exist"); // Not connected (NAME CHANGE)

        // Internal errors
        map.put("PROGRAM_ERROR", "internal_error");          // Internal error (NAME CHANGE)
        map.put("STORAGE_ERROR", "out_of_memory");           // Out of memory (NAME CHANGE)

        // Type and collection errors
        map.put("ROWTYPE_MISMATCH", "datatype_mismatch");    // Row type mismatch (NAME CHANGE)
        map.put("COLLECTION_IS_NULL", "null_value_not_allowed"); // Collection is null (NAME CHANGE)
        map.put("SUBSCRIPT_BEYOND_COUNT", "array_subscript_error"); // Array index out of bounds (NAME CHANGE)
        map.put("SUBSCRIPT_OUTSIDE_LIMIT", "array_subscript_error"); // Array index invalid (NAME CHANGE)

        // Transaction exceptions
        map.put("TRANSACTION_BACKED_OUT", "transaction_rollback"); // Transaction rolled back (NAME CHANGE)

        // Note: OTHERS is NOT in this map - it's handled separately as a special case

        return map;
    }

    public static String v(PlSqlParser.Exception_handlerContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Exception_handlerContext cannot be null");
        }

        StringBuilder result = new StringBuilder();

        // Get all exception names from the WHEN clause
        List<PlSqlParser.Exception_nameContext> exceptionNames = ctx.exception_name();
        if (exceptionNames == null || exceptionNames.isEmpty()) {
            throw new TransformationException("WHEN clause missing exception name");
        }

        // Build WHEN clause
        result.append("  WHEN ");

        // Process each exception name (may be multiple with OR)
        for (int i = 0; i < exceptionNames.size(); i++) {
            if (i > 0) {
                result.append(" OR ");
            }

            PlSqlParser.Exception_nameContext exceptionNameCtx = exceptionNames.get(i);
            String oracleExceptionName = exceptionNameCtx.getText();

            // Transform exception name using mapping table
            String postgresExceptionName = mapExceptionName(oracleExceptionName);
            result.append(postgresExceptionName);
        }

        result.append(" THEN\n");

        // Process statements in the handler
        PlSqlParser.Seq_of_statementsContext statementsCtx = ctx.seq_of_statements();
        if (statementsCtx != null) {
            String statements = b.visit(statementsCtx);
            result.append(statements);
        }

        return result.toString();
    }

    /**
     * Maps an Oracle exception name to its PostgreSQL equivalent.
     *
     * <p>Handles:
     * <ul>
     *   <li>Standard exceptions via lookup table</li>
     *   <li>OTHERS special case (remains uppercase)</li>
     *   <li>Unknown exceptions (convert to lowercase, warn in future)</li>
     * </ul>
     *
     * @param oracleExceptionName Oracle exception name (e.g., "NO_DATA_FOUND", "OTHERS")
     * @return PostgreSQL exception name (e.g., "no_data_found", "OTHERS")
     */
    private static String mapExceptionName(String oracleExceptionName) {
        // Special case: OTHERS remains uppercase in PostgreSQL
        if ("OTHERS".equalsIgnoreCase(oracleExceptionName)) {
            return "OTHERS";
        }

        // Check mapping table for standard exceptions
        String mapped = EXCEPTION_NAME_MAP.get(oracleExceptionName.toUpperCase());
        if (mapped != null) {
            return mapped;
        }

        // Unknown exception - convert to lowercase (user-defined or future Oracle exception)
        // PostgreSQL user-defined exceptions are typically lowercase
        // This handles both user-defined exceptions and future Oracle standard exceptions gracefully
        return oracleExceptionName.toLowerCase();
    }
}
