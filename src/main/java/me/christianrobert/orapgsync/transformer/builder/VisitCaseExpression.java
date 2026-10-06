package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

/**
 * Visitor helper for case_expression grammar rule.
 *
 * <p>Oracle and PostgreSQL have nearly identical CASE expression syntax!
 *
 * <p>Grammar rules:
 * <pre>
 * case_expression
 *     : searched_case_expression
 *     | simple_case_expression
 *
 * simple_case_expression
 *     : CASE expression case_when_part_expression+ case_else_part_expression? END CASE?
 *
 * searched_case_expression
 *     : CASE case_when_part_expression+ case_else_part_expression? END CASE?
 *
 * case_when_part_expression
 *     : WHEN expression THEN expression
 *
 * case_else_part_expression
 *     : ELSE expression
 * </pre>
 *
 * <p>Key differences:
 * <ul>
 *   <li>Oracle: Allows "END CASE" or just "END"</li>
 *   <li>PostgreSQL: Only allows "END" (not "END CASE")</li>
 *   <li>Solution: Always output "END" without "CASE" keyword</li>
 * </ul>
 *
 * <p>Simple CASE (already implemented via DECODE transformation):
 * <pre>
 * Oracle:     CASE deptno WHEN 10 THEN 'Sales' WHEN 20 THEN 'IT' ELSE 'Other' END
 * PostgreSQL: CASE deptno WHEN 10 THEN 'Sales' WHEN 20 THEN 'IT' ELSE 'Other' END
 * </pre>
 *
 * <p>Searched CASE (what we implement here):
 * <pre>
 * Oracle:     CASE WHEN sal > 5000 THEN 'High' WHEN sal > 2000 THEN 'Medium' ELSE 'Low' END
 * PostgreSQL: CASE WHEN sal > 5000 THEN 'High' WHEN sal > 2000 THEN 'Medium' ELSE 'Low' END
 * </pre>
 */
public class VisitCaseExpression {

  public static String v(PlSqlParser.Case_expressionContext ctx, PostgresCodeBuilder b) {
    if (ctx == null) {
      throw new IllegalArgumentException("Case_expressionContext cannot be null");
    }

    // Check which type of CASE expression we have
    PlSqlParser.Searched_case_expressionContext searchedCtx = ctx.searched_case_expression();
    if (searchedCtx != null) {
      return buildSearchedCase(searchedCtx, b);
    }

    PlSqlParser.Simple_case_expressionContext simpleCtx = ctx.simple_case_expression();
    if (simpleCtx != null) {
      return buildSimpleCase(simpleCtx, b);
    }

    throw new TransformationException("CASE expression has no recognized type (searched or simple)");
  }

  /**
   * Builds searched CASE expression.
   *
   * <p>Format: CASE WHEN condition1 THEN result1 WHEN condition2 THEN result2 ... ELSE default END
   */
  private static String buildSearchedCase(
      PlSqlParser.Searched_case_expressionContext ctx, PostgresCodeBuilder b) {

    StringBuilder result = new StringBuilder("CASE");

    // Process WHEN/THEN pairs
    List<PlSqlParser.Case_when_part_expressionContext> whenParts = ctx.case_when_part_expression();
    if (whenParts == null || whenParts.isEmpty()) {
      throw new TransformationException("CASE expression missing WHEN clauses");
    }

    for (PlSqlParser.Case_when_part_expressionContext whenPart : whenParts) {
      List<PlSqlParser.ExpressionContext> expressions = whenPart.expression();
      if (expressions == null || expressions.size() != 2) {
        throw new TransformationException(
            "WHEN clause requires exactly 2 expressions (condition and result)");
      }

      String condition = b.visit(expressions.get(0)); // WHEN condition
      String thenResult = b.visit(expressions.get(1)); // THEN result

      result.append(" WHEN ");
      result.append(condition);
      result.append(" THEN ");
      result.append(thenResult);
    }

    // Process ELSE clause if present
    PlSqlParser.Case_else_part_expressionContext elseCtx = ctx.case_else_part_expression();
    if (elseCtx != null) {
      PlSqlParser.ExpressionContext elseExpr = elseCtx.expression();
      if (elseExpr != null) {
        String elseResult = b.visit(elseExpr);
        result.append(" ELSE ");
        result.append(elseResult);
      }
    }

    // Always use "END" without "CASE" keyword (PostgreSQL requires this)
    result.append(" END");

    return result.toString();
  }

  /**
   * Builds simple CASE expression.
   *
   * <p>Format: CASE expr WHEN value1 THEN result1 WHEN value2 THEN result2 ... ELSE default END
   *
   * <p>Note: This is the same format that DECODE transforms to, but CASE expressions can also
   * appear directly in Oracle SQL.
   */
  private static String buildSimpleCase(
      PlSqlParser.Simple_case_expressionContext ctx, PostgresCodeBuilder b) {

    StringBuilder result = new StringBuilder("CASE ");

    // Get the expression to evaluate (the "selector" expression)
    PlSqlParser.ExpressionContext selectorExpr = ctx.expression();
    if (selectorExpr == null) {
      throw new TransformationException("Simple CASE expression missing selector expression");
    }

    String selector = b.visit(selectorExpr);
    result.append(selector);

    // Process WHEN/THEN pairs
    List<PlSqlParser.Case_when_part_expressionContext> whenParts = ctx.case_when_part_expression();
    if (whenParts == null || whenParts.isEmpty()) {
      throw new TransformationException("CASE expression missing WHEN clauses");
    }

    for (PlSqlParser.Case_when_part_expressionContext whenPart : whenParts) {
      List<PlSqlParser.ExpressionContext> expressions = whenPart.expression();
      if (expressions == null || expressions.size() != 2) {
        throw new TransformationException(
            "WHEN clause requires exactly 2 expressions (value and result)");
      }

      String whenValue = b.visit(expressions.get(0)); // WHEN value
      String thenResult = b.visit(expressions.get(1)); // THEN result

      result.append(" WHEN ");
      result.append(whenValue);
      result.append(" THEN ");
      result.append(thenResult);
    }

    // Process ELSE clause if present
    PlSqlParser.Case_else_part_expressionContext elseCtx = ctx.case_else_part_expression();
    if (elseCtx != null) {
      PlSqlParser.ExpressionContext elseExpr = elseCtx.expression();
      if (elseExpr != null) {
        String elseResult = b.visit(elseExpr);
        result.append(" ELSE ");
        result.append(elseResult);
      }
    }

    // Always use "END" without "CASE" keyword (PostgreSQL requires this)
    result.append(" END");

    return result.toString();
  }
}
