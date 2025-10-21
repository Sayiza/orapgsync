package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Routes to the appropriate CTE type visitor.
 *
 * <p>Oracle supports two types of factoring clauses:</p>
 * <ul>
 *   <li>subquery_factoring_clause - Standard CTEs (common)</li>
 *   <li>subav_factoring_clause - Analytic views (Oracle 12c+, rare)</li>
 * </ul>
 */
public class VisitWithFactoringClause {

  public static String v(PlSqlParser.With_factoring_clauseContext ctx, PostgresCodeBuilder b) {
    // Standard CTE (most common case)
    if (ctx.subquery_factoring_clause() != null) {
      return VisitSubqueryFactoringClause.v(ctx.subquery_factoring_clause(), b);
    }

    // Analytic view factoring clause (Oracle 12c+ feature, rare)
    if (ctx.subav_factoring_clause() != null) {
      throw new TransformationException(
          "Subquery analytic view factoring clauses (Oracle 12c+) are not yet supported. "
              + "This is a rare Oracle feature for materialized analytic views. "
              + "Manual migration required."
      );
    }

    throw new TransformationException(
        "Unknown with_factoring_clause type - neither subquery nor analytic view");
  }
}
