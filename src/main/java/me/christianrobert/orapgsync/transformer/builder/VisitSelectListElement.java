package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitSelectListElement {
  public static String v(PlSqlParser.Select_list_elementsContext ctx, PostgresCodeBuilder b) {

    // select_list_elements: tableview_name '.' ASTERISK | expression (column_alias)?

    if (ctx.ASTERISK() != null) {
      // table.* syntax - get table name and build "table . *"
      PlSqlParser.Tableview_nameContext tableCtx = ctx.tableview_name();
      if (tableCtx == null) {
        throw new TransformationException("SELECT table.* missing table name");
      }

      // Get the table/alias name
      String tableName = tableCtx.getText();
      return tableName + " . *";
    }

    // Regular expression (column, function call, etc.)
    PlSqlParser.ExpressionContext exprCtx = ctx.expression();
    if (exprCtx == null) {
      throw new TransformationException("Select list element missing expression");
    }
    return b.visit(exprCtx);

    // Future: handle column_alias from ctx.column_alias()

  }
}
