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
    // Track position for view column type casting (when no explicit alias exists)
    List<String> elements = new ArrayList<>();
    List<PlSqlParser.Select_list_elementsContext> selectListElements = ctx.select_list_elements();
    for (int i = 0; i < selectListElements.size(); i++) {
      // Set position in context (for position-based type casting in VisitSelectListElement)
      if (b.getContext() != null && b.getContext().isViewTransformation()) {
        b.getContext().setCurrentSelectListPosition(i);
      }

      String element = b.visit(selectListElements.get(i));
      elements.add(element);
    }

    // Reset position after SELECT list completes
    if (b.getContext() != null && b.getContext().isViewTransformation()) {
      b.getContext().setCurrentSelectListPosition(-1);
    }

    return String.join(" , ", elements);
  }
}
