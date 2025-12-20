package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

public class VisitSelectedList {
  public static String v(PlSqlParser.Selected_listContext ctx, PostgresCodeBuilder b) {

    // selected_list: '*' | select_list_elements (',' select_list_elements)*

    if (ctx.ASTERISK() != null) {
      // SELECT * - pass through as-is
      return "*";
    }

    // Visit each select_list_elements child
    List<String> elements = new ArrayList<>();
    List<PlSqlParser.Select_list_elementsContext> selectListElements = ctx.select_list_elements();
    for (PlSqlParser.Select_list_elementsContext element : selectListElements) {
      elements.add(b.visit(element));
    }

    return String.join(" , ", elements);
  }
}
