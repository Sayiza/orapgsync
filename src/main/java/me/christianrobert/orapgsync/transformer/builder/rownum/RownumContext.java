package me.christianrobert.orapgsync.transformer.builder.rownum;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Context for ROWNUM transformation within a query block.
 *
 * <p>Similar to OuterJoinContext, this class encapsulates ROWNUM analysis results
 * and provides methods for filtering ROWNUM conditions during WHERE clause transformation.
 *
 * <p><b>Usage Pattern (Context Stack):</b>
 * <pre>
 * 1. VisitQueryBlock: Analyze WHERE for ROWNUM pattern
 * 2. VisitQueryBlock: Push RownumContext onto builder's stack
 * 3. VisitLogicalExpression: Check context, skip ROWNUM conditions
 * 4. VisitQueryBlock: Pop context when done
 * </pre>
 *
 * <p><b>Phase 1 Support (LIMIT optimization):</b>
 * Detects simple ROWNUM patterns that can be transformed to PostgreSQL LIMIT:
 * <ul>
 *   <li>{@code WHERE ROWNUM <= 10} → {@code LIMIT 10}</li>
 *   <li>{@code WHERE ROWNUM < 10} → {@code LIMIT 9}</li>
 *   <li>{@code WHERE dept = 10 AND ROWNUM <= 5} → {@code WHERE dept = 10 LIMIT 5}</li>
 * </ul>
 *
 * <p><b>Future Phases:</b>
 * This context can be extended to support:
 * <ul>
 *   <li>ROWNUM in SELECT list → row_number() OVER ()</li>
 *   <li>ROWNUM BETWEEN → OFFSET/LIMIT</li>
 *   <li>Complex patterns → subquery wrapper</li>
 * </ul>
 */
public class RownumContext {

    /**
     * True if WHERE clause contains simple ROWNUM limit pattern (Phase 1).
     */
    private final boolean hasSimpleLimit;

    /**
     * The limit value for LIMIT clause (e.g., 10 for "ROWNUM <= 10").
     */
    private final int limitValue;

    /**
     * The comparison operator used ("<=", "<").
     */
    private final String operator;

    /**
     * Creates a RownumContext with simple LIMIT pattern.
     *
     * @param hasSimpleLimit True if simple LIMIT detected
     * @param limitValue The limit value
     * @param operator The comparison operator
     */
    public RownumContext(boolean hasSimpleLimit, int limitValue, String operator) {
        this.hasSimpleLimit = hasSimpleLimit;
        this.limitValue = limitValue;
        this.operator = operator;
    }

    /**
     * Factory method for "no ROWNUM" case.
     */
    public static RownumContext noRownum() {
        return new RownumContext(false, 0, null);
    }

    /**
     * Factory method for simple LIMIT pattern.
     */
    public static RownumContext simpleLimit(int limitValue, String operator) {
        return new RownumContext(true, limitValue, operator);
    }

    /**
     * Returns true if a simple LIMIT pattern was detected.
     */
    public boolean hasSimpleLimit() {
        return hasSimpleLimit;
    }

    /**
     * Returns the limit value for LIMIT clause.
     */
    public int getLimitValue() {
        return limitValue;
    }

    /**
     * Returns the operator used in ROWNUM condition.
     */
    public String getOperator() {
        return operator;
    }

    /**
     * Checks if a unary_logical_expression is a ROWNUM condition that should be filtered.
     *
     * <p>This method walks the AST to determine if the expression is a ROWNUM comparison.
     * Used by VisitLogicalExpression to skip ROWNUM conditions when building WHERE clause.
     *
     * <p>Proper AST walking - no getText() shortcuts!
     *
     * @param ctx The unary_logical_expression context
     * @return True if this is a ROWNUM condition and should be filtered out
     */
    public boolean isRownumCondition(PlSqlParser.Unary_logical_expressionContext ctx) {
        if (ctx == null || !hasSimpleLimit) {
            return false;
        }

        // Navigate: unary_logical_expression → multiset_expression → relational_expression
        PlSqlParser.Multiset_expressionContext multisetExpr = ctx.multiset_expression();
        if (multisetExpr == null) {
            return false;
        }

        PlSqlParser.Relational_expressionContext relationalExpr = multisetExpr.relational_expression();
        if (relationalExpr == null) {
            return false;
        }

        // Check if this relational expression is a ROWNUM comparison
        return isRownumComparison(relationalExpr);
    }

    /**
     * Checks if a relational_expression is a ROWNUM comparison.
     *
     * <p>Looks for pattern: ROWNUM <op> constant or constant <op> ROWNUM
     * where <op> is <=, <, >=, or >
     */
    private boolean isRownumComparison(PlSqlParser.Relational_expressionContext ctx) {
        if (ctx == null) {
            return false;
        }

        // Must have a relational operator
        if (ctx.relational_operator() == null) {
            return false;
        }

        // Must have exactly 2 operands
        java.util.List<PlSqlParser.Relational_expressionContext> operands = ctx.relational_expression();
        if (operands == null || operands.size() != 2) {
            return false;
        }

        // Check if either operand is ROWNUM
        boolean leftIsRownum = isRownumIdentifier(operands.get(0));
        boolean rightIsRownum = isRownumIdentifier(operands.get(1));

        return leftIsRownum || rightIsRownum;
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
            // Not a simple identifier (might be qualified like t.rownum or function call)
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

    @Override
    public String toString() {
        if (!hasSimpleLimit) {
            return "RownumContext{noRownum}";
        }
        return String.format("RownumContext{LIMIT %d, operator=%s}", limitValue, operator);
    }
}
