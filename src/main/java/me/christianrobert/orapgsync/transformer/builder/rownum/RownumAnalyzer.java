package me.christianrobert.orapgsync.transformer.builder.rownum;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Analyzes WHERE clause to identify ROWNUM patterns for LIMIT optimization.
 *
 * <p>This analyzer performs proper AST walking to detect simple ROWNUM patterns:
 * <ul>
 *   <li>{@code WHERE ROWNUM <= 10} → {@code LIMIT 10}</li>
 *   <li>{@code WHERE ROWNUM < 10} → {@code LIMIT 9}</li>
 *   <li>{@code WHERE dept = 10 AND ROWNUM <= 5} → {@code WHERE dept = 10 LIMIT 5}</li>
 * </ul>
 *
 * <p>Pattern detection rules:
 * <ul>
 *   <li>ROWNUM must be compared with {@code <=} or {@code <} operator</li>
 *   <li>The other operand must be a numeric constant</li>
 *   <li>ROWNUM can be combined with other conditions using AND</li>
 *   <li>ROWNUM with OR is rejected (requires complex transformation)</li>
 * </ul>
 *
 * <p>This analyzer follows the same pattern as OuterJoinAnalyzer: proper AST walking,
 * no getText() shortcuts, structural pattern matching.
 */
public class RownumAnalyzer {

    /**
     * Analyzes WHERE clause for ROWNUM patterns.
     *
     * @param whereCtx WHERE clause context (may be null)
     * @return RownumContext with analysis results
     */
    public static RownumContext analyze(PlSqlParser.Where_clauseContext whereCtx) {
        if (whereCtx == null) {
            return RownumContext.noRownum();
        }

        if (whereCtx.CURRENT() != null) {
            // CURRENT OF cursor - not relevant for ROWNUM
            return RownumContext.noRownum();
        }

        PlSqlParser.ConditionContext conditionCtx = whereCtx.condition();
        if (conditionCtx == null) {
            return RownumContext.noRownum();
        }

        // Use a visitor to traverse the condition tree and find ROWNUM comparisons
        RownumConditionVisitor visitor = new RownumConditionVisitor();
        visitor.visit(conditionCtx);

        return visitor.getResult();
    }

    /**
     * Visitor that traverses WHERE clause conditions looking for ROWNUM comparisons.
     */
    private static class RownumConditionVisitor extends PlSqlParserBaseVisitor<Void> {

        private RownumContext result = RownumContext.noRownum();
        private boolean hasOr = false;

        public RownumContext getResult() {
            // If we found OR anywhere in the condition tree, reject the pattern
            if (hasOr) {
                return RownumContext.noRownum();
            }
            return result;
        }

        @Override
        public Void visitLogical_expression(PlSqlParser.Logical_expressionContext ctx) {
            // Grammar: logical_expression : unary_logical_expression
            //                              | logical_expression AND logical_expression
            //                              | logical_expression OR logical_expression

            // Check for OR - this makes ROWNUM pattern complex
            if (ctx.OR() != null) {
                hasOr = true;
                // Don't traverse further - we'll reject this pattern
                return null;
            }

            // Continue traversing (AND is fine, or single expression)
            return visitChildren(ctx);
        }

        @Override
        public Void visitRelational_expression(PlSqlParser.Relational_expressionContext ctx) {
            // Grammar: relational_expression : relational_expression relational_operator relational_expression
            //                                 | compound_expression

            // We're looking for: ROWNUM <= N or ROWNUM < N
            // This is a binary relational expression with an operator

            if (ctx.relational_operator() == null) {
                // Not a comparison - continue traversing
                return visitChildren(ctx);
            }

            java.util.List<PlSqlParser.Relational_expressionContext> operands = ctx.relational_expression();
            if (operands == null || operands.size() != 2) {
                // Not a binary comparison - continue traversing
                return visitChildren(ctx);
            }

            // Check the operator
            String operator = ctx.relational_operator().getText();
            if (!operator.equals("<=") && !operator.equals("<") &&
                !operator.equals(">=") && !operator.equals(">")) {
                // Not a relevant operator for ROWNUM - continue traversing
                return visitChildren(ctx);
            }

            PlSqlParser.Relational_expressionContext left = operands.get(0);
            PlSqlParser.Relational_expressionContext right = operands.get(1);

            // Check if left side is ROWNUM
            if (isRownumIdentifier(left)) {
                Integer limitValue = extractNumericConstant(right);
                if (limitValue != null) {
                    result = createLimitContext(operator, limitValue, false);
                    return null; // Found it, stop searching
                }
            }

            // Check if right side is ROWNUM (reversed comparison: 10 >= ROWNUM)
            if (isRownumIdentifier(right)) {
                Integer limitValue = extractNumericConstant(left);
                if (limitValue != null) {
                    result = createLimitContext(operator, limitValue, true);
                    return null; // Found it, stop searching
                }
            }

            // Not a ROWNUM comparison - continue traversing
            return visitChildren(ctx);
        }

        /**
         * Checks if a relational_expression is the ROWNUM identifier.
         *
         * <p>Proper AST navigation:
         * relational_expression → compound_expression → concatenation → model_expression →
         * unary_expression → atom → general_element → general_element_part → id_expression
         */
        private boolean isRownumIdentifier(PlSqlParser.Relational_expressionContext ctx) {
            if (ctx == null) {
                return false;
            }

            // Navigate: relational_expression → compound_expression
            PlSqlParser.Compound_expressionContext compoundExpr = ctx.compound_expression();
            if (compoundExpr == null) {
                return false;
            }

            // compound_expression has concatenation list
            java.util.List<PlSqlParser.ConcatenationContext> concats = compoundExpr.concatenation();
            if (concats == null || concats.isEmpty()) {
                return false;
            }

            // Get first concatenation (for simple identifier, there's only one)
            PlSqlParser.ConcatenationContext concat = concats.get(0);

            // concatenation → model_expression
            PlSqlParser.Model_expressionContext modelExpr = concat.model_expression();
            if (modelExpr == null) {
                return false;
            }

            // model_expression → unary_expression
            PlSqlParser.Unary_expressionContext unaryExpr = modelExpr.unary_expression();
            if (unaryExpr == null) {
                return false;
            }

            // unary_expression → atom
            PlSqlParser.AtomContext atom = unaryExpr.atom();
            if (atom == null) {
                return false;
            }

            // atom → general_element
            PlSqlParser.General_elementContext generalElem = atom.general_element();
            if (generalElem == null) {
                return false;
            }

            // general_element has general_element_part list
            java.util.List<PlSqlParser.General_element_partContext> parts = generalElem.general_element_part();
            if (parts == null || parts.size() != 1) {
                // Not a simple identifier (might be qualified or function call)
                return false;
            }

            // Get the id_expression
            PlSqlParser.General_element_partContext part = parts.get(0);
            PlSqlParser.Id_expressionContext idExpr = part.id_expression();
            if (idExpr == null) {
                return false;
            }

            // Check if function arguments present (ROWNUM should not have arguments)
            if (part.function_argument() != null && !part.function_argument().isEmpty()) {
                return false;
            }

            // Finally, check the identifier text
            String identifier = idExpr.getText().toUpperCase();
            return identifier.equals("ROWNUM");
        }

        /**
         * Extracts numeric constant from a relational_expression.
         *
         * <p>Proper AST navigation to numeric constant node.
         */
        private Integer extractNumericConstant(PlSqlParser.Relational_expressionContext ctx) {
            if (ctx == null) {
                return null;
            }

            // Navigate: relational_expression → compound_expression
            PlSqlParser.Compound_expressionContext compoundExpr = ctx.compound_expression();
            if (compoundExpr == null) {
                return null;
            }

            // compound_expression → concatenation list
            java.util.List<PlSqlParser.ConcatenationContext> concats = compoundExpr.concatenation();
            if (concats == null || concats.isEmpty()) {
                return null;
            }

            PlSqlParser.ConcatenationContext concat = concats.get(0);

            // concatenation → model_expression
            PlSqlParser.Model_expressionContext modelExpr = concat.model_expression();
            if (modelExpr == null) {
                return null;
            }

            // model_expression → unary_expression
            PlSqlParser.Unary_expressionContext unaryExpr = modelExpr.unary_expression();
            if (unaryExpr == null) {
                return null;
            }

            // unary_expression → atom
            PlSqlParser.AtomContext atom = unaryExpr.atom();
            if (atom == null) {
                return null;
            }

            // atom → constant
            PlSqlParser.ConstantContext constant = atom.constant();
            if (constant == null) {
                return null;
            }

            // constant → numeric
            PlSqlParser.NumericContext numeric = constant.numeric();
            if (numeric == null) {
                return null;
            }

            // numeric → UNSIGNED_INTEGER
            TerminalNode unsignedInt = numeric.UNSIGNED_INTEGER();
            if (unsignedInt == null) {
                return null;
            }

            // Parse the integer value
            try {
                return Integer.parseInt(unsignedInt.getText());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * Creates a RownumContext for a detected LIMIT pattern.
         *
         * @param operator The comparison operator
         * @param value The numeric constant
         * @param reversed True if ROWNUM is on right side (10 >= ROWNUM)
         */
        private RownumContext createLimitContext(String operator, int value, boolean reversed) {
            // If reversed, flip the operator
            if (reversed) {
                operator = reverseOperator(operator);
            }

            // Only support <= and < operators
            if (!operator.equals("<=") && !operator.equals("<")) {
                return RownumContext.noRownum();
            }

            // Calculate limit value
            int limitValue = value;

            // Adjust for < operator: ROWNUM < 10 means first 9 rows
            if (operator.equals("<")) {
                limitValue = value - 1;
            }

            // Ensure positive limit
            if (limitValue <= 0) {
                return RownumContext.noRownum();
            }

            return RownumContext.simpleLimit(limitValue, operator);
        }

        /**
         * Reverses comparison operator for right-side ROWNUM.
         */
        private String reverseOperator(String op) {
            switch (op) {
                case ">=": return "<=";
                case ">":  return "<";
                case "<=": return ">=";
                case "<":  return ">";
                default:   return op;
            }
        }
    }
}
