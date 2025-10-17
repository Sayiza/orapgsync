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

    // Check for parenthesized subquery
//    PlSqlParser.SubqueryContext nestedSubqueryCtx = ctx.subquery();
//    if (nestedSubqueryCtx != null) {
//      throw new TransformationException("Parenthesized subqueries not yet supported");
//    }
//
    throw new TransformationException("Subquery basic elements missing query_block");
  }
}
