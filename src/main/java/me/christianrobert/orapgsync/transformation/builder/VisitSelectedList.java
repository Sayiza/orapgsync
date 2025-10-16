package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.SelectListElement;
import me.christianrobert.orapgsync.transformation.semantic.query.SelectedList;

import java.util.ArrayList;
import java.util.List;

public class VisitSelectedList {
  public static SemanticNode v(PlSqlParser.Selected_listContext ctx, SemanticTreeBuilder b) {

    if (ctx.ASTERISK() != null) {
      // SELECT * - not supported in minimal implementation
      throw new TransformationException("SELECT * not supported in minimal implementation");
    }

    // Visit each select_list_elements child
    List<SelectListElement> elements = new ArrayList<>();
    for (PlSqlParser.Select_list_elementsContext elementCtx : ctx.select_list_elements()) {
      SelectListElement element = (SelectListElement) b.visit(elementCtx);
      elements.add(element);
    }

    return new SelectedList(elements);
  }
}
