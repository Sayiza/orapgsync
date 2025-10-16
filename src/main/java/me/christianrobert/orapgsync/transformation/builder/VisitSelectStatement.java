package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectOnlyStatement;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectStatement;

public class VisitSelectStatement {
  public static SemanticNode v(PlSqlParser.Select_statementContext ctx, SemanticTreeBuilder b) {
    PlSqlParser.Select_only_statementContext selectOnlyCtx = ctx.select_only_statement();
    if (selectOnlyCtx == null) {
      throw new TransformationException("SELECT statement missing select_only_statement");
    }

    SelectOnlyStatement selectOnlyStatement = (SelectOnlyStatement) b.visit(selectOnlyCtx);
    return new SelectStatement(selectOnlyStatement);
  }
}
