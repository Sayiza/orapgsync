package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms Oracle ORDER BY clause to PostgreSQL.
 *
 * <p><b>Key Difference:</b> NULL ordering defaults differ between Oracle and PostgreSQL for DESC:
 * <ul>
 *   <li>Oracle: ORDER BY col DESC → NULLs come FIRST (highest)</li>
 *   <li>PostgreSQL: ORDER BY col DESC → NULLs come LAST (lowest)</li>
 * </ul>
 *
 * <p><b>Solution:</b> Add explicit NULLS FIRST to DESC columns without explicit NULL ordering:
 * <pre>
 * Oracle:     ORDER BY empno DESC
 * PostgreSQL: ORDER BY empno DESC NULLS FIRST
 * </pre>
 *
 * <p><b>No transformation needed for:</b>
 * <ul>
 *   <li>ASC (both databases have same default: NULLS LAST)</li>
 *   <li>Explicit NULLS FIRST/LAST (same syntax in both databases)</li>
 * </ul>
 *
 * <p><b>Grammar structure:</b>
 * <pre>
 * order_by_clause
 *     : ORDER SIBLINGS? BY order_by_elements (',' order_by_elements)*
 *
 * order_by_elements
 *     : expression (ASC | DESC)? (NULLS (FIRST | LAST))?
 * </pre>
 */
public class VisitOrderByClause {

  public static String v(PlSqlParser.Order_by_clauseContext ctx, PostgresCodeBuilder b) {

    // Extract all order_by_elements
    List<PlSqlParser.Order_by_elementsContext> elements = ctx.order_by_elements();
    if (elements == null || elements.isEmpty()) {
      return "ORDER BY"; // Shouldn't happen in valid SQL, but handle gracefully
    }

    // Transform each order by element
    List<String> transformedElements = new ArrayList<>();
    for (PlSqlParser.Order_by_elementsContext element : elements) {
      transformedElements.add(transformOrderByElement(element, b));
    }

    // Build ORDER BY clause
    return "ORDER BY " + String.join(" , ", transformedElements);
  }

  /**
   * Transforms a single ORDER BY element.
   *
   * <p>Handles:
   * <ul>
   *   <li>Expression (column, position, function, etc.) - delegates to expression visitor</li>
   *   <li>ASC/DESC direction</li>
   *   <li>NULLS FIRST/LAST clause</li>
   * </ul>
   *
   * <p><b>Critical transformation:</b> If DESC without explicit NULLS clause, add NULLS FIRST
   */
  private static String transformOrderByElement(
      PlSqlParser.Order_by_elementsContext element, PostgresCodeBuilder b) {

    // 1. Transform the expression (column name, position, function call, etc.)
    PlSqlParser.ExpressionContext expressionCtx = element.expression();
    if (expressionCtx == null) {
      throw new IllegalStateException("ORDER BY element missing expression");
    }
    String expression = b.visit(expressionCtx);

    // 2. Detect ASC/DESC direction
    boolean hasAsc = element.ASC() != null;
    boolean hasDesc = element.DESC() != null;

    // 3. Detect explicit NULLS FIRST/LAST
    boolean hasNullsClause = element.NULLS() != null;
    boolean hasFirst = element.FIRST() != null;
    boolean hasLast = element.LAST() != null;

    // 4. Build the transformed ORDER BY element
    StringBuilder result = new StringBuilder();
    result.append(expression);

    // Add ASC/DESC if present
    if (hasAsc) {
      result.append(" ASC");
    } else if (hasDesc) {
      result.append(" DESC");
    }
    // If neither ASC nor DESC specified, defaults to ASC (same in both databases)

    // Handle NULLS clause
    if (hasNullsClause) {
      // Explicit NULLS clause present - pass through as-is
      result.append(" NULLS ");
      if (hasFirst) {
        result.append("FIRST");
      } else if (hasLast) {
        result.append("LAST");
      }
    } else if (hasDesc) {
      // DESC without explicit NULLS clause - add NULLS FIRST to match Oracle behavior
      // Oracle default: DESC → NULLS FIRST
      // PostgreSQL default: DESC → NULLS LAST
      // Solution: Explicitly add NULLS FIRST
      result.append(" NULLS FIRST");
    }
    // For ASC (or no direction), both databases default to NULLS LAST, so no transformation needed

    return result.toString();
  }
}
