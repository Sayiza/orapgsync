package me.christianrobert.orapgsync.transformer.type.helpers;

import me.christianrobert.orapgsync.antlr.PlSqlParser.ConstantContext;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static helper for resolving constant (literal) types.
 *
 * <p>Handles all literal types from Phase 1:</p>
 * <ul>
 *   <li>Numeric literals (42, 3.14)</li>
 *   <li>String literals ('hello')</li>
 *   <li>Date literals (DATE '2024-01-01')</li>
 *   <li>Timestamp literals (TIMESTAMP '2024-01-01 12:00:00')</li>
 *   <li>NULL literal</li>
 *   <li>Boolean literals (TRUE, FALSE)</li>
 * </ul>
 *
 * <p>Pattern: Static helper following PostgresCodeBuilder architecture.</p>
 */
public final class ResolveConstant {

    private static final Logger log = LoggerFactory.getLogger(ResolveConstant.class);

    private ResolveConstant() {
        // Static utility class - prevent instantiation
    }

    /**
     * Resolves the type of a constant literal.
     *
     * <p>Order matters: DATE/TIMESTAMP keywords must be checked before quoted_string
     * to avoid misclassifying them as plain strings.</p>
     *
     * @param ctx Constant context from ANTLR parser
     * @return TypeInfo representing the literal's type
     */
    public static TypeInfo resolve(ConstantContext ctx) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        // DATE literal: DATE 'YYYY-MM-DD' - CHECK FIRST before quoted_string!
        if (ctx.DATE() != null) {
            log.trace("Found DATE literal: {}", ctx.getText());
            return TypeInfo.DATE;
        }

        // TIMESTAMP literal - CHECK BEFORE quoted_string!
        if (ctx.TIMESTAMP() != null) {
            log.trace("Found TIMESTAMP literal: {}", ctx.getText());
            return TypeInfo.TIMESTAMP;
        }

        // Numeric literals (integers, floats)
        if (ctx.numeric() != null) {
            log.trace("Found numeric literal: {}", ctx.getText());
            return TypeInfo.NUMERIC;
        }

        // String literals (plain strings without DATE/TIMESTAMP keyword)
        if (ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
            log.trace("Found string literal: {}", ctx.getText());
            return TypeInfo.TEXT;
        }

        // NULL literal
        if (ctx.NULL_() != null) {
            log.trace("Found NULL literal");
            return TypeInfo.NULL_TYPE;
        }

        // Boolean literals
        if (ctx.TRUE() != null || ctx.FALSE() != null) {
            log.trace("Found boolean literal: {}", ctx.getText());
            return TypeInfo.BOOLEAN;
        }

        // Other constants (DBTIMEZONE, etc.) - treat as unknown for now
        log.trace("Unknown constant type: {}", ctx.getText());
        return TypeInfo.UNKNOWN;
    }
}
