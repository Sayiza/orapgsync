package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.SelectListElement;

public class VisitSelectListElement {
  public static SemanticNode v(PlSqlParser.Select_list_elementsContext ctx, SemanticTreeBuilder b) {

    if (ctx.ASTERISK() != null) {
      // table.* syntax not supported yet
      throw new TransformationException("SELECT table.* not supported in minimal implementation");
    }

    // Visit expression child
    PlSqlParser.ExpressionContext exprCtx = ctx.expression();
    if (exprCtx == null) {
      throw new TransformationException("Select list element missing expression");
    }
    SemanticNode expression = b.visit(exprCtx);

    // Future: handle column_alias from ctx.column_alias()

    return new SelectListElement(expression);
  }
}
