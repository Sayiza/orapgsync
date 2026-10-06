package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting WHERE clauses.
 */
public class VisitWhereClause {
  public static String v(PlSqlParser.Where_clauseContext ctx, PostgresCodeBuilder b) {
    // Grammar: WHERE (CURRENT OF cursor_name | condition)

    if (ctx.CURRENT() != null) {
      throw new TransformationException("CURRENT OF cursor not yet supported");
    }

    PlSqlParser.ConditionContext conditionCtx = ctx.condition();
    if (conditionCtx == null) {
      throw new TransformationException("WHERE clause missing condition");
    }

    // Check if we have outer join context with conditions to filter
    OuterJoinContext outerJoinCtx = b.getOuterJoinContext();
    if (outerJoinCtx != null && outerJoinCtx.hasOuterJoins()) {
      // We have outer joins - use the filtered WHERE clause from context
      // Pass the builder to transform AST nodes (converts Oracle functions to PostgreSQL)
      String filteredWhere = outerJoinCtx.buildWhereClause(b);
      if (filteredWhere != null && !filteredWhere.trim().isEmpty()) {
        return "WHERE " + filteredWhere;
      }
      // No regular conditions left - return empty string
      return "";
    }

    // No outer joins - use original behavior
    String condition = b.visit(conditionCtx);

    // Check if condition is null (all conditions were filtered - e.g., only ROWNUM)
    if (condition == null || condition.trim().isEmpty()) {
      return "";  // No WHERE clause needed
    }

    return "WHERE " + condition;
  }
}
