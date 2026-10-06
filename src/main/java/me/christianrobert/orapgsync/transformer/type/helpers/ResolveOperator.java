package me.christianrobert.orapgsync.transformer.type.helpers;

import me.christianrobert.orapgsync.antlr.PlSqlParser.ConcatenationContext;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import org.antlr.v4.runtime.ParserRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Static helper for resolving operator result types.
 *
 * <p>Handles all binary operators from Phase 1:</p>
 * <ul>
 *   <li>Arithmetic operators (*, /, +, -, **, MOD)</li>
 *   <li>String concatenation (||)</li>
 *   <li>Date arithmetic (DATE + NUMBER, DATE - DATE)</li>
 * </ul>
 *
 * <p>Pattern: Static helper following PostgresCodeBuilder architecture.</p>
 */
public final class ResolveOperator {

    private static final Logger log = LoggerFactory.getLogger(ResolveOperator.class);

    private ResolveOperator() {
        // Static utility class - prevent instantiation
    }

    /**
     * Resolves the result type of a concatenation expression.
     *
     * <p>Handles all binary operators: *, /, +, -, **, MOD, ||</p>
     *
     * <p><b>IMPORTANT:</b> This method uses cache lookups instead of visitor.visit()
     * to avoid exponential re-visitation with deeply nested expressions.
     * The caller (visitConcatenation) has already called visitChildren() to populate the cache.</p>
     *
     * @param ctx Concatenation context
     * @param typeCache Type cache populated by visitChildren
     * @param visitor TypeAnalysisVisitor for generating node keys
     * @return TypeInfo representing the result type
     */
    public static TypeInfo resolve(ConcatenationContext ctx, Map<String, TypeInfo> typeCache, TypeAnalysisVisitor visitor) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        // Check for binary operators
        if (ctx.ASTERISK() != null || ctx.SOLIDUS() != null) {
            // * multiplication or / division
            return resolveArithmetic(ctx.concatenation(), typeCache, visitor);
        }

        if (ctx.PLUS_SIGN() != null || ctx.MINUS_SIGN() != null) {
            // + addition or - subtraction
            // Special handling: DATE arithmetic
            return resolvePlusMinus(ctx, typeCache, visitor);
        }

        if (ctx.DOUBLE_ASTERISK() != null) {
            // ** power operator - always returns NUMBER
            return TypeInfo.NUMERIC;
        }

        if (ctx.MOD() != null) {
            // MOD operator - always returns NUMBER
            return TypeInfo.NUMERIC;
        }

        // Check for || string concatenation
        if (ctx.BAR() != null && ctx.BAR().size() >= 2) {
            // String concatenation - always returns TEXT
            log.trace("String concatenation");
            return TypeInfo.TEXT;
        }

        // No binary operator - return UNKNOWN (caller should lookup model_expression from cache)
        return TypeInfo.UNKNOWN;
    }

    /**
     * Resolves type for arithmetic operators (*, /).
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>NUMBER * NUMBER → NUMBER</li>
     *   <li>NUMBER / NUMBER → NUMBER</li>
     *   <li>NULL in any operand → NULL_TYPE</li>
     *   <li>Otherwise → UNKNOWN</li>
     * </ul>
     *
     * <p><b>IMPORTANT:</b> Uses cache lookup instead of visitor.visit() to avoid
     * exponential re-visitation with deeply nested expressions.</p>
     */
    private static TypeInfo resolveArithmetic(List<ConcatenationContext> operands,
                                               Map<String, TypeInfo> typeCache,
                                               TypeAnalysisVisitor visitor) {
        if (operands == null || operands.size() < 2) {
            return TypeInfo.UNKNOWN;
        }

        // Lookup from cache instead of re-visiting
        TypeInfo left = lookupType(operands.get(0), typeCache, visitor);
        TypeInfo right = lookupType(operands.get(1), typeCache, visitor);

        log.trace("Arithmetic operator: {} op {}", left.getCategory(), right.getCategory());

        // NULL propagation
        if (left.isNull() || right.isNull()) {
            return TypeInfo.NULL_TYPE;
        }

        // Numeric arithmetic
        if (left.isNumeric() && right.isNumeric()) {
            return TypeInfo.NUMERIC;
        }

        // Unknown operand types
        return TypeInfo.UNKNOWN;
    }

    /**
     * Resolves type for + and - operators.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>DATE + NUMBER → DATE (add days)</li>
     *   <li>DATE - NUMBER → DATE (subtract days)</li>
     *   <li>DATE - DATE → NUMBER (days difference)</li>
     *   <li>NUMBER + NUMBER → NUMBER</li>
     *   <li>NUMBER - NUMBER → NUMBER</li>
     *   <li>NULL in any operand → NULL_TYPE</li>
     *   <li>Otherwise → UNKNOWN</li>
     * </ul>
     *
     * <p><b>IMPORTANT:</b> Uses cache lookup instead of visitor.visit() to avoid
     * exponential re-visitation with deeply nested expressions.</p>
     */
    private static TypeInfo resolvePlusMinus(ConcatenationContext ctx,
                                             Map<String, TypeInfo> typeCache,
                                             TypeAnalysisVisitor visitor) {
        List<ConcatenationContext> operands = ctx.concatenation();
        if (operands == null || operands.size() < 2) {
            return TypeInfo.UNKNOWN;
        }

        // Lookup from cache instead of re-visiting
        TypeInfo left = lookupType(operands.get(0), typeCache, visitor);
        TypeInfo right = lookupType(operands.get(1), typeCache, visitor);

        boolean isPlus = ctx.PLUS_SIGN() != null;
        log.trace("{} operator: {} {} {}", (isPlus ? "+" : "-"),
                left.getCategory(), (isPlus ? "+" : "-"), right.getCategory());

        // NULL propagation
        if (left.isNull() || right.isNull()) {
            return TypeInfo.NULL_TYPE;
        }

        // Date arithmetic
        if (left.isDate() && right.isNumeric()) {
            // DATE +/- NUMBER → DATE
            return left;  // Preserve DATE or TIMESTAMP
        }

        if (left.isDate() && right.isDate() && !isPlus) {
            // DATE - DATE → NUMBER (days difference)
            // Note: DATE + DATE is not valid
            return TypeInfo.NUMERIC;
        }

        // Numeric arithmetic
        if (left.isNumeric() && right.isNumeric()) {
            return TypeInfo.NUMERIC;
        }

        // Unknown operand types
        return TypeInfo.UNKNOWN;
    }

    /**
     * Looks up type from cache for a given context.
     *
     * <p>This is the safe alternative to visitor.visit() that avoids re-visitation.</p>
     *
     * @param ctx Parse context to lookup
     * @param typeCache Type cache populated by visitChildren
     * @param visitor Visitor for generating node keys
     * @return TypeInfo from cache, or UNKNOWN if not found
     */
    private static TypeInfo lookupType(ParserRuleContext ctx, Map<String, TypeInfo> typeCache, TypeAnalysisVisitor visitor) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }
        String key = visitor.nodeKey(ctx);
        return typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
    }
}
