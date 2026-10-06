package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

/**
 * Visitor helper for subquery grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * subquery
 *     : subquery_basic_elements subquery_operation_part*
 *
 * subquery_operation_part
 *     : (UNION ALL? | INTERSECT | MINUS) subquery_basic_elements
 * </pre>
 *
 * <p>Oracle vs PostgreSQL set operation differences:
 * <ul>
 *   <li>UNION: Identical (removes duplicates)</li>
 *   <li>UNION ALL: Identical (keeps duplicates)</li>
 *   <li>INTERSECT: Identical (returns common rows)</li>
 *   <li>MINUS: Oracle-specific → Transform to EXCEPT in PostgreSQL</li>
 * </ul>
 */
public class VisitSubquery {
  public static String v(PlSqlParser.SubqueryContext ctx, PostgresCodeBuilder b) {
    // subquery_basic_elements subquery_operation_part*

    // Visit basic elements (required)
    PlSqlParser.Subquery_basic_elementsContext basicElementsCtx = ctx.subquery_basic_elements();
    if (basicElementsCtx == null) {
      throw new TransformationException("Subquery missing subquery_basic_elements");
    }

    StringBuilder result = new StringBuilder();
    result.append(b.visit(basicElementsCtx));

    // Visit operation parts (UNION/INTERSECT/MINUS) if present
    List<PlSqlParser.Subquery_operation_partContext> operationParts = ctx.subquery_operation_part();
    if (operationParts != null && !operationParts.isEmpty()) {
      for (PlSqlParser.Subquery_operation_partContext opCtx : operationParts) {
        result.append(" ");
        result.append(transformSetOperation(opCtx, b));
      }
    }

    return result.toString();
  }

  /**
   * Transforms a set operation (UNION/INTERSECT/MINUS).
   *
   * <p>Grammar: (UNION ALL? | INTERSECT | MINUS) subquery_basic_elements
   */
  private static String transformSetOperation(PlSqlParser.Subquery_operation_partContext ctx, PostgresCodeBuilder b) {
    StringBuilder result = new StringBuilder();

    // Determine the set operation keyword
    if (ctx.UNION() != null) {
      result.append("UNION");
      // Check for ALL modifier
      if (ctx.ALL() != null) {
        result.append(" ALL");
      }
    } else if (ctx.INTERSECT() != null) {
      result.append("INTERSECT");
    } else if (ctx.MINUS() != null) {
      // Oracle MINUS → PostgreSQL EXCEPT
      result.append("EXCEPT");
    } else {
      throw new TransformationException("Unknown set operation in subquery_operation_part");
    }

    // Transform the subquery_basic_elements
    PlSqlParser.Subquery_basic_elementsContext basicElementsCtx = ctx.subquery_basic_elements();
    if (basicElementsCtx == null) {
      throw new TransformationException("Set operation missing subquery_basic_elements");
    }

    result.append(" ");
    result.append(b.visit(basicElementsCtx));

    return result.toString();
  }
}
