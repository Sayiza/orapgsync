package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

public class VisitSelectedList {
  public static String v(PlSqlParser.Selected_listContext ctx, PostgresCodeBuilder b) {

    if (ctx.ASTERISK() != null) {
      // SELECT * - not supported in minimal implementation
      throw new TransformationException("SELECT * not supported in minimal implementation");
    }

    // Visit each select_list_elements child
    List<String> elements = new ArrayList<>();
    for (PlSqlParser.Select_list_elementsContext elementCtx : ctx.select_list_elements()) {
      String element = b.visit(elementCtx);
      elements.add(element);
    }

    return String.join(" , ", elements);
  }
}
