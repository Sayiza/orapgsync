package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.MultisetExpression;

public class VisitMultisetExpression {
  public static SemanticNode v(
      PlSqlParser.Multiset_expressionContext ctx, SemanticTreeBuilder b) {

    // Check for MULTISET operator (EXCEPT/INTERSECT/UNION)
    if (ctx.MULTISET() != null) {
      throw new TransformationException(
          "MULTISET operations (EXCEPT/INTERSECT/UNION) not yet supported in minimal implementation");
    }

    // Visit relational_expression
    PlSqlParser.Relational_expressionContext relationalCtx = ctx.relational_expression();
    if (relationalCtx == null) {
      throw new TransformationException("Multiset expression missing relational_expression");
    }

    SemanticNode relationalExpression = b.visit(relationalCtx);

    // Check for MEMBER/SUBMULTISET suffix
    // The grammar uses: multiset_type = NOT? (MEMBER | SUBMULTISET)
    if (ctx.MEMBER() != null || ctx.SUBMULTISET() != null) {
      throw new TransformationException(
          "MEMBER/SUBMULTISET operations not yet supported in minimal implementation");
    }

    return new MultisetExpression(relationalExpression);
  }
}
