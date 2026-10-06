package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL user-defined exception declarations.
 *
 * <p>Transforms Oracle exception declarations to PostgreSQL comments (PostgreSQL doesn't
 * support named exceptions). The exception name is registered in the exception context
 * and mapped to a PostgreSQL SQLSTATE code for use in RAISE and exception handlers.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * identifier EXCEPTION ';'
 *
 * Examples:
 * invalid_salary EXCEPTION;
 * validation_error EXCEPTION;
 * business_rule_violation EXCEPTION;
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * -- exception_name EXCEPTION; (Mapped to SQLSTATE 'P9001' in exception handlers)
 *
 * PostgreSQL doesn't support named exception variables.
 * Exceptions are raised and caught by SQLSTATE codes.
 * The transformer automatically maps:
 * - User-defined exceptions → P9001, P9002, ... (auto-generated)
 * - Exceptions with PRAGMA EXCEPTION_INIT → P0001-P0999 (Oracle -20001 to -20999)
 * </pre>
 *
 * <h3>Transformations Applied:</h3>
 * <ul>
 *   <li>Register exception name in exception context</li>
 *   <li>Auto-generate PostgreSQL SQLSTATE code (P9001, P9002, ...)</li>
 *   <li>Return commented declaration (informational only)</li>
 *   <li>Exception name used in RAISE and WHEN clauses</li>
 * </ul>
 */
public class VisitException_declaration {

    /**
     * Transforms exception declaration to PostgreSQL comment and registers in context.
     *
     * @param ctx Exception declaration parse tree context
     * @param b PostgresCodeBuilder instance (provides exception context)
     * @return Commented exception declaration with mapped SQLSTATE code
     */
    public static String v(PlSqlParser.Exception_declarationContext ctx, PostgresCodeBuilder b) {
        // STEP 1: Extract exception name
        String exceptionName = ctx.identifier().getText();

        // STEP 2: Register exception in current block's exception context
        // This marks the exception as declared, but doesn't assign a code yet
        // If PRAGMA EXCEPTION_INIT is encountered later, it will provide the code
        // Otherwise, a code will be auto-generated on first use (RAISE or WHEN clause)
        b.declareException(exceptionName);

        // STEP 3: Return commented declaration (PostgreSQL doesn't need it)
        // Don't lookup the error code here - it would trigger premature auto-generation
        // The actual code assignment happens when PRAGMA is processed or on first use
        return "-- " + exceptionName + " EXCEPTION; (PostgreSQL exception declared)\n";
    }
}
