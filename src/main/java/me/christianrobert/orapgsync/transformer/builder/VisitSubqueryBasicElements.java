package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitSubqueryBasicElements {
  public static String v(PlSqlParser.Subquery_basic_elementsContext ctx, PostgresCodeBuilder b) {
    // query_block | LEFT_PAREN subquery RIGHT_PAREN

    // Check for query_block (normal case)
    PlSqlParser.Query_blockContext queryBlockCtx = ctx.query_block();
    if (queryBlockCtx != null) {
      return b.visit(queryBlockCtx);
    }

    // Check for parenthesized subquery (e.g., Oracle view definitions wrapped in parentheses)
    PlSqlParser.SubqueryContext nestedSubqueryCtx = ctx.subquery();
    if (nestedSubqueryCtx != null) {
      // Recursively visit the nested subquery and wrap in parentheses
      // This handles cases like: CREATE VIEW v AS (SELECT col1, col2 FROM table1)
      String nestedSql = b.visit(nestedSubqueryCtx);
      return "(" + nestedSql + ")";
    }

    throw new TransformationException("Subquery basic elements missing query_block");
  }
}
