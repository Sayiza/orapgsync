package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

/**
 * Transforms a single CTE definition.
 *
 * <p>Grammar: query_name paren_column_list? AS '(' subquery ')'</p>
 *
 * <p>Syntax is identical in Oracle and PostgreSQL - pass-through transformation.</p>
 *
 * <p>Examples:</p>
 * <pre>
 * -- Without column list:
 * dept_totals AS (SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id)
 *
 * -- With column list:
 * dept_totals (dept_id, emp_count) AS (SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id)
 * </pre>
 */
public class VisitSubqueryFactoringClause {

  public static String v(PlSqlParser.Subquery_factoring_clauseContext ctx, PostgresCodeBuilder b) {
    StringBuilder result = new StringBuilder();

    // 1. CTE name
    String cteName = ctx.query_name().getText();

    // Register CTE name in context to prevent schema qualification
    // CTEs are temporary named result sets that don't belong to any schema
    TransformationContext context = b.getContext();
    if (context != null) {
      context.registerCTE(cteName);
    }

    result.append(cteName);

    // 2. Optional column list: (col1, col2, col3)
    if (ctx.paren_column_list() != null) {
      result.append(" ");
      // paren_column_list already includes parentheses - pass through as-is
      // Syntax is identical in Oracle and PostgreSQL
      result.append(ctx.paren_column_list().getText());
    }

    // 3. AS keyword
    result.append(" AS (");

    // 4. Subquery (CRITICAL: This is recursively transformed!)
    // All our existing transformations apply:
    // - Schema qualification
    // - Synonym resolution
    // - Type methods, package functions
    // - Outer joins, ORDER BY, GROUP BY, etc.
    //
    // Visit the CTE subquery
    result.append(b.visit(ctx.subquery()));

    result.append(")");

    return result.toString();
  }
}
