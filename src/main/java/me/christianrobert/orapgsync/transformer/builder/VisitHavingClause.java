package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Transforms Oracle HAVING clause to PostgreSQL.
 *
 * <p><b>Key Insight:</b> Oracle and PostgreSQL have identical HAVING syntax.
 * HAVING filters groups after aggregation, just like WHERE filters rows before aggregation.
 *
 * <p><b>Strategy:</b> Delegate to condition visitor (same as WHERE clause).
 * All the transformation logic for conditions (AND, OR, comparisons, etc.) is reused.
 *
 * <p><b>Examples:</b>
 * <pre>
 * -- Simple HAVING
 * HAVING COUNT(*) > 5
 *
 * -- Complex HAVING
 * HAVING COUNT(*) > 5 AND AVG(salary) > 50000
 *
 * -- HAVING with OR
 * HAVING COUNT(*) > 10 OR SUM(salary) > 100000
 * </pre>
 *
 * <p><b>Grammar structure:</b>
 * <pre>
 * having_clause
 *     : HAVING condition
 * </pre>
 */
public class VisitHavingClause {

  public static String v(PlSqlParser.Having_clauseContext ctx, PostgresCodeBuilder b) {

    // Extract the condition
    PlSqlParser.ConditionContext conditionCtx = ctx.condition();
    if (conditionCtx == null) {
      throw new IllegalStateException("HAVING clause missing condition");
    }

    // Delegate to condition visitor - reuses all existing WHERE clause logic
    // This handles:
    // - Comparisons: COUNT(*) > 5
    // - Logical operators: COUNT(*) > 5 AND AVG(salary) > 50000
    // - Complex nested conditions
    // - All expression types (aggregates, columns, literals, etc.)
    String condition = b.visit(conditionCtx);

    return "HAVING " + condition;
  }
}
