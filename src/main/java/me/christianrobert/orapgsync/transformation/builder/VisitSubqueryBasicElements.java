package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.QueryBlock;
import me.christianrobert.orapgsync.transformation.semantic.query.SubqueryBasicElements;

public class VisitSubqueryBasicElements {
  public static SemanticNode v(PlSqlParser.Subquery_basic_elementsContext ctx, SemanticTreeBuilder b) {
    // query_block | LEFT_PAREN subquery RIGHT_PAREN

    // Check for query_block (normal case)
    PlSqlParser.Query_blockContext queryBlockCtx = ctx.query_block();
    if (queryBlockCtx != null) {
      QueryBlock queryBlock = (QueryBlock) b.visit(queryBlockCtx);
      return new SubqueryBasicElements(queryBlock);
    }

    // Check for parenthesized subquery
    PlSqlParser.SubqueryContext nestedSubqueryCtx = ctx.subquery();
    if (nestedSubqueryCtx != null) {
      throw new TransformationException("Parenthesized subqueries not yet supported");
    }

    throw new TransformationException("Subquery basic elements missing query_block");
  }
}
