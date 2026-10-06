package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms Oracle GROUP BY clause to PostgreSQL.
 *
 * <p><b>Key Insight:</b> Oracle and PostgreSQL have nearly identical GROUP BY syntax.
 * The main difference is PostgreSQL's strict enforcement that every non-aggregated
 * column in SELECT must appear in GROUP BY (or be in an aggregate function).
 *
 * <p><b>Strategy:</b> Pass through GROUP BY as-is. Let PostgreSQL validate compliance.
 * Existing Oracle views that work are likely already compliant with proper GROUP BY rules.
 *
 * <p><b>What we handle:</b>
 * <ul>
 *   <li>Column-based grouping: {@code GROUP BY dept_id, job_id}</li>
 *   <li>Position-based grouping: {@code GROUP BY 1, 2}</li>
 *   <li>Expression-based grouping: {@code GROUP BY EXTRACT(YEAR FROM hire_date)}</li>
 *   <li>HAVING clause: {@code HAVING COUNT(*) > 5}</li>
 * </ul>
 *
 * <p><b>What we pass through:</b>
 * <ul>
 *   <li>ROLLUP, CUBE, GROUPING SETS (advanced features, less common)</li>
 * </ul>
 *
 * <p><b>Grammar structure:</b>
 * <pre>
 * group_by_clause
 *     : GROUP BY group_by_elements (',' group_by_elements)* having_clause?
 *     | having_clause (GROUP BY group_by_elements (',' group_by_elements)*)?
 *
 * group_by_elements
 *     : grouping_sets_clause
 *     | rollup_cube_clause
 *     | expression                ← Most common case
 *
 * having_clause
 *     : HAVING condition
 * </pre>
 */
public class VisitGroupByClause {

  public static String v(PlSqlParser.Group_by_clauseContext ctx, PostgresCodeBuilder b) {

    StringBuilder result = new StringBuilder();

    // Extract GROUP BY elements
    List<PlSqlParser.Group_by_elementsContext> elements = ctx.group_by_elements();
    if (elements != null && !elements.isEmpty()) {
      // Transform each GROUP BY element
      List<String> transformedElements = new ArrayList<>();
      for (PlSqlParser.Group_by_elementsContext element : elements) {
        transformedElements.add(transformGroupByElement(element, b));
      }

      // Build GROUP BY clause
      result.append("GROUP BY ").append(String.join(" , ", transformedElements));
    }

    // Extract HAVING clause (if present)
    PlSqlParser.Having_clauseContext havingCtx = ctx.having_clause();
    if (havingCtx != null) {
      String havingClause = b.visit(havingCtx);
      if (havingClause != null && !havingClause.trim().isEmpty()) {
        if (result.length() > 0) {
          result.append(" ");
        }
        result.append(havingClause);
      }
    }

    return result.toString();
  }

  /**
   * Transforms a single GROUP BY element.
   *
   * <p>Handles:
   * <ul>
   *   <li>Expression (column, position, function, etc.) - most common case</li>
   *   <li>ROLLUP, CUBE, GROUPING SETS - advanced features (pass through)</li>
   * </ul>
   */
  private static String transformGroupByElement(
      PlSqlParser.Group_by_elementsContext element, PostgresCodeBuilder b) {

    // CASE 1: Expression (column name, position, function call, etc.)
    // This is the most common case: GROUP BY dept_id, GROUP BY 1, GROUP BY EXTRACT(...)
    PlSqlParser.ExpressionContext expressionCtx = element.expression();
    if (expressionCtx != null) {
      // Delegate to expression visitor - reuses all existing transformation logic
      return b.visit(expressionCtx);
    }

    // CASE 2: ROLLUP/CUBE (advanced features)
    PlSqlParser.Rollup_cube_clauseContext rollupCubeCtx = element.rollup_cube_clause();
    if (rollupCubeCtx != null) {
      // Pass through as-is - both Oracle and PostgreSQL support ROLLUP/CUBE
      return rollupCubeCtx.getText();
    }

    // CASE 3: GROUPING SETS (advanced feature)
    PlSqlParser.Grouping_sets_clauseContext groupingSetsCtx = element.grouping_sets_clause();
    if (groupingSetsCtx != null) {
      // Pass through as-is - both Oracle and PostgreSQL support GROUPING SETS
      return groupingSetsCtx.getText();
    }

    // Should not reach here if grammar is correct
    throw new IllegalStateException("Unexpected GROUP BY element type");
  }
}
