package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.Subquery;
import me.christianrobert.orapgsync.transformation.semantic.query.SubqueryBasicElements;

import java.util.List;

public class VisitSubquery {
  public static SemanticNode v(PlSqlParser.SubqueryContext ctx, SemanticTreeBuilder b) {
    // subquery_basic_elements subquery_operation_part*

    // Visit basic elements (required)
    PlSqlParser.Subquery_basic_elementsContext basicElementsCtx = ctx.subquery_basic_elements();
    if (basicElementsCtx == null) {
      throw new TransformationException("Subquery missing subquery_basic_elements");
    }

    SubqueryBasicElements basicElements = (SubqueryBasicElements) b.visit(basicElementsCtx);

    // Visit operation parts (UNION/INTERSECT/MINUS) if present
    List<PlSqlParser.Subquery_operation_partContext> operationParts = ctx.subquery_operation_part();
    if (operationParts != null && !operationParts.isEmpty()) {
      throw new TransformationException("Set operations (UNION/INTERSECT/MINUS) not yet supported");
    }

    return new Subquery(basicElements);
  }
}
