package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.query.FromClause;

import java.util.ArrayList;
import java.util.List;

public class VisitFromClause {
  public static SemanticNode v(PlSqlParser.From_clauseContext ctx, SemanticTreeBuilder b) {

    PlSqlParser.Table_ref_listContext tableRefListCtx = ctx.table_ref_list();
    if (tableRefListCtx == null) {
      throw new TransformationException("FROM clause missing table_ref_list");
    }

    // Visit each table_ref child
    List<TableReference> tableRefs = new ArrayList<>();
    for (PlSqlParser.Table_refContext tableRefCtx : tableRefListCtx.table_ref()) {
      TableReference tableRef = (TableReference) b.visit(tableRefCtx);
      tableRefs.add(tableRef);
    }

    if (tableRefs.isEmpty()) {
      throw new TransformationException("FROM clause has no table references");
    }

    // Minimal implementation: only single table supported
    if (tableRefs.size() > 1) {
      throw new TransformationException("Multiple tables in FROM clause not supported in minimal implementation");
    }

    return new FromClause(tableRefs);
  }
}
