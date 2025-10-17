package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

public class VisitSubquery {
  public static String v(PlSqlParser.SubqueryContext ctx, PostgresCodeBuilder b) {
    // subquery_basic_elements subquery_operation_part*

    // Visit basic elements (required)
    PlSqlParser.Subquery_basic_elementsContext basicElementsCtx = ctx.subquery_basic_elements();
    if (basicElementsCtx == null) {
      throw new TransformationException("Subquery missing subquery_basic_elements");
    }

    return b.visit(basicElementsCtx);

    // Visit operation parts (UNION/INTERSECT/MINUS) if present
    // TODO
  }
}
