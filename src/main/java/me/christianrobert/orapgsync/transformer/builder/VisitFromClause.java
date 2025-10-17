package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

public class VisitFromClause {
  public static String v(PlSqlParser.From_clauseContext ctx, PostgresCodeBuilder b) {

    PlSqlParser.Table_ref_listContext tableRefListCtx = ctx.table_ref_list();
    if (tableRefListCtx == null) {
      throw new TransformationException("FROM clause missing table_ref_list");
    }

    // Visit each table_ref child
    List<String> tableRefs = new ArrayList<>();
    for (PlSqlParser.Table_refContext tableRefCtx : tableRefListCtx.table_ref()) {

      tableRefs.add(b.visit(tableRefCtx));
    }

    if (tableRefs.isEmpty()) {
      throw new TransformationException("FROM clause has no table references");
    }

    // Minimal implementation: only single table supported
    if (tableRefs.size() > 1) {
      throw new TransformationException("Multiple tables in FROM clause not supported in minimal implementation");
    }

    return String.join(", ", tableRefs);
  }
}
