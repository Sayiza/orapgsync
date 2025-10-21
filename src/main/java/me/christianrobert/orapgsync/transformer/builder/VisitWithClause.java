package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.cte.CteRecursionAnalyzer;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

/**
 * Transforms Oracle WITH clause to PostgreSQL.
 *
 * <p>Key differences:</p>
 * <ul>
 *   <li>Oracle: WITH cte AS (...) - no RECURSIVE keyword needed</li>
 *   <li>PostgreSQL: WITH RECURSIVE cte AS (...) - RECURSIVE keyword required for recursive CTEs</li>
 *   <li>Oracle: Supports inline PL/SQL functions in WITH clause</li>
 *   <li>PostgreSQL: Does NOT support inline functions</li>
 * </ul>
 *
 * <p>Transformation strategy:</p>
 * <ul>
 *   <li>Non-recursive CTEs: Pass-through (syntax identical)</li>
 *   <li>Recursive CTEs: Detect and add RECURSIVE keyword</li>
 *   <li>Inline PL/SQL: Throw exception (not supported in PostgreSQL)</li>
 * </ul>
 */
public class VisitWithClause {

  public static String v(PlSqlParser.With_clauseContext ctx, PostgresCodeBuilder b) {
    // 1. Check for inline PL/SQL functions/procedures (Oracle-specific feature)
    // Grammar: WITH (function_body | procedure_body)* with_factoring_clause ...
    if (!ctx.function_body().isEmpty() || !ctx.procedure_body().isEmpty()) {
      throw new TransformationException(
          "Inline PL/SQL functions/procedures in WITH clause are not supported in PostgreSQL. "
              + "Oracle allows: WITH FUNCTION my_func(...) IS ... BEGIN ... END; cte AS (...) "
              + "PostgreSQL requires: Create the function separately first, then use it in the CTE. "
              + "Manual migration required for this view."
      );
    }

    StringBuilder result = new StringBuilder("WITH ");

    // 2. Detect if any CTE is recursive
    // PostgreSQL requires RECURSIVE keyword if ANY CTE is recursive
    // (handles both self-recursive and mutually recursive CTEs)
    boolean hasRecursiveCte = false;
    List<PlSqlParser.With_factoring_clauseContext> ctes = ctx.with_factoring_clause();

    for (PlSqlParser.With_factoring_clauseContext cte : ctes) {
      // Only check subquery_factoring_clause (standard CTEs)
      // Skip subav_factoring_clause (analytic views - rare, not yet supported)
      if (cte.subquery_factoring_clause() != null) {
        if (CteRecursionAnalyzer.isRecursive(cte.subquery_factoring_clause())) {
          hasRecursiveCte = true;
          break; // Found one, no need to check others
        }
      }
    }

    // 3. Add RECURSIVE keyword if needed
    if (hasRecursiveCte) {
      result.append("RECURSIVE ");
    }

    // 4. Process each CTE (with_factoring_clause)
    for (int i = 0; i < ctes.size(); i++) {
      if (i > 0) {
        result.append(", ");
      }
      result.append(VisitWithFactoringClause.v(ctes.get(i), b));
    }

    return result.toString();
  }
}
