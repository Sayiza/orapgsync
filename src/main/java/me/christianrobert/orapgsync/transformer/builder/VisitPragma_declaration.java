package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL PRAGMA declarations.
 *
 * <p>Transforms Oracle PRAGMA declarations to PostgreSQL equivalents or comments.
 * Currently supports PRAGMA EXCEPTION_INIT for linking user-defined exceptions
 * to Oracle error codes. Other pragmas are ignored.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * PRAGMA EXCEPTION_INIT(exception_name, error_code) ';'
 *
 * Examples:
 * PRAGMA EXCEPTION_INIT(invalid_salary, -20001);
 * PRAGMA EXCEPTION_INIT(validation_error, -20055);
 * PRAGMA EXCEPTION_INIT(business_rule_violation, -20999);
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * -- PRAGMA EXCEPTION_INIT(invalid_salary, -20001); (Mapped to SQLSTATE 'P0001')
 *
 * PostgreSQL uses SQLSTATE codes instead of numeric error codes.
 * The transformer maps Oracle error codes to PostgreSQL SQLSTATE:
 * - Oracle -20001 → PostgreSQL 'P0001'
 * - Oracle -20055 → PostgreSQL 'P0055'
 * - Oracle -20999 → PostgreSQL 'P0999'
 * Formula: ERRCODE = 'P' + LPAD(abs(oracle_code) - 20000, 4, '0')
 * </pre>
 *
 * <h3>Other Supported Pragmas:</h3>
 * <ul>
 *   <li>SERIALLY_REUSABLE - Ignored (not applicable in PostgreSQL)</li>
 *   <li>AUTONOMOUS_TRANSACTION - Ignored (requires dblink extension)</li>
 *   <li>INLINE - Ignored (optimizer hint)</li>
 *   <li>RESTRICT_REFERENCES - Ignored (purity analysis)</li>
 *   <li>DEPRECATE - Ignored (informational only)</li>
 * </ul>
 *
 * <h3>Transformations Applied:</h3>
 * <ul>
 *   <li>PRAGMA EXCEPTION_INIT: Link exception to SQLSTATE code</li>
 *   <li>Other pragmas: Return empty string (silently ignore)</li>
 *   <li>Return commented PRAGMA with mapped SQLSTATE (informational)</li>
 * </ul>
 */
public class VisitPragma_declaration {

    /**
     * Transforms PRAGMA declaration to PostgreSQL equivalent or comment.
     *
     * @param ctx PRAGMA declaration parse tree context
     * @param b PostgresCodeBuilder instance (provides exception context)
     * @return Commented PRAGMA declaration (EXCEPTION_INIT) or empty string (other pragmas)
     */
    public static String v(PlSqlParser.Pragma_declarationContext ctx, PostgresCodeBuilder b) {
        // Check if this is PRAGMA EXCEPTION_INIT
        if (ctx.EXCEPTION_INIT() != null) {
            return handleExceptionInit(ctx, b);
        }

        // Other pragmas are not supported in PostgreSQL - silently ignore
        // SERIALLY_REUSABLE, AUTONOMOUS_TRANSACTION, INLINE, RESTRICT_REFERENCES, DEPRECATE
        return "";  // Return empty string (no output)
    }

    /**
     * Handles PRAGMA EXCEPTION_INIT by linking exception to Oracle error code.
     *
     * @param ctx PRAGMA declaration parse tree context
     * @param b PostgresCodeBuilder instance (provides exception context)
     * @return Commented PRAGMA declaration with mapped SQLSTATE code
     */
    private static String handleExceptionInit(PlSqlParser.Pragma_declarationContext ctx, PostgresCodeBuilder b) {
        // STEP 1: Extract exception name
        // PRAGMA EXCEPTION_INIT(exception_name, -20001)
        PlSqlParser.Exception_nameContext exceptionNameCtx = ctx.exception_name();
        if (exceptionNameCtx == null) {
            return "-- PRAGMA EXCEPTION_INIT: Missing exception name\n";
        }

        String exceptionName = exceptionNameCtx.getText();

        // STEP 2: Extract Oracle error code
        // numeric_negative: MINUS_SIGN numeric
        PlSqlParser.Numeric_negativeContext numericNegCtx = ctx.numeric_negative();
        if (numericNegCtx == null) {
            return "-- PRAGMA EXCEPTION_INIT(" + exceptionName + ", ???): Missing error code\n";
        }

        // Parse error code (e.g., "-20001")
        String errorCodeStr = numericNegCtx.getText();  // Includes minus sign
        int oracleCode;
        try {
            oracleCode = Integer.parseInt(errorCodeStr);
        } catch (NumberFormatException e) {
            return "-- PRAGMA EXCEPTION_INIT(" + exceptionName + ", " + errorCodeStr +
                   "): Invalid error code\n";
        }

        // Validate Oracle error code range (-20000 to -20999)
        if (oracleCode > -20000 || oracleCode < -20999) {
            return "-- PRAGMA EXCEPTION_INIT(" + exceptionName + ", " + oracleCode +
                   "): Warning: Error code outside valid range (-20000 to -20999)\n";
        }

        // STEP 3: Link exception to Oracle error code in exception context
        // This maps to PostgreSQL SQLSTATE: -20001 → P0001, -20055 → P0055, etc.
        b.linkExceptionToCode(exceptionName, oracleCode);

        // STEP 4: Get the assigned PostgreSQL SQLSTATE for informational comment
        String errorCode = b.lookupExceptionErrorCode(exceptionName);

        // STEP 5: Return commented PRAGMA (PostgreSQL doesn't need it)
        // The comment is informational for developers reading the generated code
        return "-- PRAGMA EXCEPTION_INIT(" + exceptionName + ", " + oracleCode +
               "); (Mapped to SQLSTATE '" + errorCode + "')\n";
    }
}
