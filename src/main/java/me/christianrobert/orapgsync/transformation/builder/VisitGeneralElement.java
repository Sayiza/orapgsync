package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.GeneralElement;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;

public class VisitGeneralElement {
  public static SemanticNode v(PlSqlParser.General_elementContext ctx, SemanticTreeBuilder b) {

    // Check for parenthesized general_element
    if (ctx.LEFT_PAREN() != null) {
      throw new TransformationException(
          "Parenthesized general_element not yet supported in minimal implementation");
    }

    // Check for dotted path: general_element ('.' general_element_part)+
    // If there's a PERIOD token, we have dot navigation
    if (ctx.PERIOD() != null && !ctx.PERIOD().isEmpty()) {
      throw new TransformationException(
          "Dot navigation not yet supported in minimal implementation - "
              + "this is where transformation logic for package.function, table.column, "
              + "type.method will be implemented using metadata disambiguation");
    }

    // Simple case: single general_element_part
    // For now, use getText() to create an Identifier
    // This is where the simplification finally happens (at level 8, not level 1!)
    //
    // Future: Will analyze general_element_part to detect:
    // - Function calls (has function_argument*)
    // - Simple identifiers (no arguments)
    // And will check metadata for Oracle-specific functions like NVL
    java.util.List<PlSqlParser.General_element_partContext> parts = ctx.general_element_part();
    if (parts == null || parts.isEmpty()) {
      throw new TransformationException("General element missing general_element_part");
    }
    PlSqlParser.General_element_partContext partCtx = parts.get(0);

    // Check if this is a function call (has function_argument*)
    if (partCtx.function_argument() != null && !partCtx.function_argument().isEmpty()) {
      throw new TransformationException(
          "Function calls not yet supported in minimal implementation - "
              + "this is where NVL→COALESCE transformation will be implemented");
    }

    // Simple identifier - use getText()
    String text = partCtx.getText();
    Identifier identifier = new Identifier(text);

    return new GeneralElement(identifier);
  }
}
