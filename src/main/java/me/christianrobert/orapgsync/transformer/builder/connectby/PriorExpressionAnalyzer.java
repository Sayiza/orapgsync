package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes CONNECT BY condition to extract PRIOR expression.
 *
 * <p>Oracle PRIOR syntax:</p>
 * <pre>
 * CONNECT BY PRIOR emp_id = manager_id       -- PRIOR on left
 * CONNECT BY manager_id = PRIOR emp_id       -- PRIOR on right
 * CONNECT BY PRIOR e.emp_id = e.manager_id   -- With table alias
 * </pre>
 *
 * <p>PRIOR is a unary operator in Oracle's expression grammar:</p>
 * <pre>
 * unary_expression:
 *   | PRIOR unary_expression
 *   | ...
 * </pre>
 *
 * <p>Analysis strategy:</p>
 * <ul>
 *   <li>Walk CONNECT BY condition AST</li>
 *   <li>Find relational_expression with comparison operator (=, typically)</li>
 *   <li>Check which side has PRIOR unary operator</li>
 *   <li>Extract column expressions from both sides</li>
 * </ul>
 */
public class PriorExpressionAnalyzer {

  /**
   * Analyzes CONNECT BY condition to extract PRIOR expression.
   *
   * @param condition The CONNECT BY condition
   * @return PriorExpression with analyzed structure
   * @throws TransformationException if PRIOR not found or unsupported pattern
   */
  public static PriorExpression analyze(PlSqlParser.ConditionContext condition) {
    if (condition == null) {
      throw new TransformationException("CONNECT BY condition is null");
    }

    // Find all relational expressions with comparison operators
    RelationalExpressionFinder finder = new RelationalExpressionFinder();
    finder.visit(condition);

    List<RelationalComparisonInfo> comparisons = finder.getComparisons();

    if (comparisons.isEmpty()) {
      throw new TransformationException(
          "CONNECT BY condition must contain a comparison (e.g., PRIOR col1 = col2). " +
          "Found: " + condition.getText()
      );
    }

    // Find the comparison with PRIOR
    RelationalComparisonInfo priorComparison = null;
    for (RelationalComparisonInfo comp : comparisons) {
      if (comp.leftHasPrior || comp.rightHasPrior) {
        priorComparison = comp;
        break;
      }
    }

    if (priorComparison == null) {
      throw new TransformationException(
          "CONNECT BY condition must contain PRIOR operator. " +
          "Found: " + condition.getText()
      );
    }

    // Extract PRIOR expression details
    boolean priorOnLeft = priorComparison.leftHasPrior;
    String priorColumnExpr;
    String childColumnExpr;

    if (priorOnLeft) {
      priorColumnExpr = extractColumnExpression(priorComparison.leftExpr);
      childColumnExpr = extractColumnExpression(priorComparison.rightExpr);
    } else {
      priorColumnExpr = extractColumnExpression(priorComparison.rightExpr);
      childColumnExpr = extractColumnExpression(priorComparison.leftExpr);
    }

    return new PriorExpression(priorOnLeft, priorColumnExpr, childColumnExpr);
  }

  /**
   * Extracts column expression from relational_expression context.
   *
   * <p>Handles:</p>
   * <ul>
   *   <li>Simple column: emp_id</li>
   *   <li>Qualified column: e.emp_id</li>
   *   <li>PRIOR unwrapping: strips PRIOR to get underlying column</li>
   * </ul>
   */
  private static String extractColumnExpression(PlSqlParser.Relational_expressionContext ctx) {
    if (ctx == null) {
      throw new TransformationException("Cannot extract column from null expression");
    }

    // Navigate: relational_expression → compound_expression → concatenation → model_expression
    PlSqlParser.Compound_expressionContext compoundExpr = ctx.compound_expression();
    if (compoundExpr == null) {
      throw new TransformationException("Invalid expression structure: missing compound_expression");
    }

    List<PlSqlParser.ConcatenationContext> concats = compoundExpr.concatenation();
    if (concats == null || concats.isEmpty()) {
      throw new TransformationException("Invalid expression structure: missing concatenation");
    }

    PlSqlParser.ConcatenationContext concat = concats.get(0);
    PlSqlParser.Model_expressionContext modelExpr = concat.model_expression();
    if (modelExpr == null) {
      throw new TransformationException("Invalid expression structure: missing model_expression");
    }

    PlSqlParser.Unary_expressionContext unaryExpr = modelExpr.unary_expression();
    if (unaryExpr == null) {
      throw new TransformationException("Invalid expression structure: missing unary_expression");
    }

    // Check if this is a PRIOR expression - if so, unwrap it
    if (unaryExpr.PRIOR() != null) {
      // Grammar: PRIOR unary_expression
      PlSqlParser.Unary_expressionContext child = unaryExpr.unary_expression();
      if (child != null) {
        unaryExpr = child;  // Unwrap PRIOR
      }
    }

    // Now extract the column from atom → general_element
    PlSqlParser.AtomContext atom = unaryExpr.atom();
    if (atom == null) {
      throw new TransformationException("Invalid expression structure: missing atom");
    }

    PlSqlParser.General_elementContext generalElem = atom.general_element();
    if (generalElem == null) {
      throw new TransformationException(
          "CONNECT BY PRIOR must reference a column. " +
          "Found: " + unaryExpr.getText()
      );
    }

    // general_element has general_element_part list (for qualified names: table.column)
    List<PlSqlParser.General_element_partContext> parts = generalElem.general_element_part();
    if (parts == null || parts.isEmpty()) {
      throw new TransformationException("Invalid column reference: no parts found");
    }

    // Build column expression (handle qualified names)
    StringBuilder columnExpr = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        columnExpr.append(".");
      }
      PlSqlParser.Id_expressionContext idExpr = parts.get(i).id_expression();
      if (idExpr != null) {
        columnExpr.append(idExpr.getText());
      }
    }

    return columnExpr.toString();
  }

  /**
   * Visitor that finds all relational comparisons in the condition tree.
   */
  private static class RelationalExpressionFinder {
    private final List<RelationalComparisonInfo> comparisons = new ArrayList<>();

    public List<RelationalComparisonInfo> getComparisons() {
      return comparisons;
    }

    public void visit(ParseTree tree) {
      if (tree == null) {
        return;
      }

      // Check if this is a relational_expression with comparison operator
      if (tree instanceof PlSqlParser.Relational_expressionContext) {
        PlSqlParser.Relational_expressionContext ctx =
            (PlSqlParser.Relational_expressionContext) tree;

        if (ctx.relational_operator() != null) {
          // This is a binary comparison
          List<PlSqlParser.Relational_expressionContext> operands = ctx.relational_expression();
          if (operands != null && operands.size() == 2) {
            String operator = ctx.relational_operator().getText();

            // Check if operands have PRIOR
            boolean leftHasPrior = hasPriorOperator(operands.get(0));
            boolean rightHasPrior = hasPriorOperator(operands.get(1));

            comparisons.add(new RelationalComparisonInfo(
                operands.get(0),
                operands.get(1),
                operator,
                leftHasPrior,
                rightHasPrior
            ));
          }
        }
      }

      // Recursively visit children
      for (int i = 0; i < tree.getChildCount(); i++) {
        visit(tree.getChild(i));
      }
    }

    /**
     * Checks if a relational_expression contains PRIOR operator.
     */
    private boolean hasPriorOperator(PlSqlParser.Relational_expressionContext ctx) {
      return findPriorInTree(ctx);
    }

    private boolean findPriorInTree(ParseTree tree) {
      if (tree == null) {
        return false;
      }

      // Check if this is a unary_expression with PRIOR
      if (tree instanceof PlSqlParser.Unary_expressionContext) {
        PlSqlParser.Unary_expressionContext unaryCtx =
            (PlSqlParser.Unary_expressionContext) tree;
        if (unaryCtx.PRIOR() != null) {
          return true;
        }
      }

      // Recursively check children
      for (int i = 0; i < tree.getChildCount(); i++) {
        if (findPriorInTree(tree.getChild(i))) {
          return true;
        }
      }

      return false;
    }
  }

  /**
   * Information about a relational comparison found in the condition tree.
   */
  private static class RelationalComparisonInfo {
    final PlSqlParser.Relational_expressionContext leftExpr;
    final PlSqlParser.Relational_expressionContext rightExpr;
    final String operator;
    final boolean leftHasPrior;
    final boolean rightHasPrior;

    RelationalComparisonInfo(
        PlSqlParser.Relational_expressionContext leftExpr,
        PlSqlParser.Relational_expressionContext rightExpr,
        String operator,
        boolean leftHasPrior,
        boolean rightHasPrior) {
      this.leftExpr = leftExpr;
      this.rightExpr = rightExpr;
      this.operator = operator;
      this.leftHasPrior = leftHasPrior;
      this.rightHasPrior = rightHasPrior;
    }
  }
}
