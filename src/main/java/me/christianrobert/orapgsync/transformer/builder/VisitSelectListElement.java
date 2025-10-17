package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;

public class VisitSelectListElement {
  public static String v(PlSqlParser.Select_list_elementsContext ctx, PostgresCodeBuilder b) {

    if (ctx.ASTERISK() != null) {
      // table.* syntax not supported yet
      throw new TransformationException("SELECT table.* not supported in minimal implementation");
    }

    // Visit expression child
    PlSqlParser.ExpressionContext exprCtx = ctx.expression();
    if (exprCtx == null) {
      throw new TransformationException("Select list element missing expression");
    }
    return b.visit(exprCtx);

    // Future: handle column_alias from ctx.column_alias()

  }
}
