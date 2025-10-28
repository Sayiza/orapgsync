package me.christianrobert.orapgsync.transformer.type.helpers;

import me.christianrobert.orapgsync.antlr.PlSqlParser.ConcatenationContext;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
     * @param ctx Concatenation context
     * @param visitor TypeAnalysisVisitor for visiting child nodes
     * @return TypeInfo representing the result type
     */
    public static TypeInfo resolve(ConcatenationContext ctx, TypeAnalysisVisitor visitor) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        // Check for binary operators
        if (ctx.ASTERISK() != null || ctx.SOLIDUS() != null) {
            // * multiplication or / division
            return resolveArithmetic(ctx.concatenation(), visitor);
        }

        if (ctx.PLUS_SIGN() != null || ctx.MINUS_SIGN() != null) {
            // + addition or - subtraction
            // Special handling: DATE arithmetic
            return resolvePlusMinus(ctx, visitor);
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

        // No binary operator - return UNKNOWN (caller should visit model_expression)
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
     */
    private static TypeInfo resolveArithmetic(List<ConcatenationContext> operands,
                                               TypeAnalysisVisitor visitor) {
        if (operands == null || operands.size() < 2) {
            return TypeInfo.UNKNOWN;
        }

        TypeInfo left = visitor.visit(operands.get(0));
        TypeInfo right = visitor.visit(operands.get(1));

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
     */
    private static TypeInfo resolvePlusMinus(ConcatenationContext ctx,
                                             TypeAnalysisVisitor visitor) {
        List<ConcatenationContext> operands = ctx.concatenation();
        if (operands == null || operands.size() < 2) {
            return TypeInfo.UNKNOWN;
        }

        TypeInfo left = visitor.visit(operands.get(0));
        TypeInfo right = visitor.visit(operands.get(1));

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
}
