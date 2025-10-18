package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinAnalyzer;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitQueryBlock {
  public static String v(PlSqlParser.Query_blockContext ctx, PostgresCodeBuilder b) {

    // Get FROM and WHERE clauses
    PlSqlParser.From_clauseContext fromClauseCtx = ctx.from_clause();
    if (fromClauseCtx == null) {
      throw new TransformationException("Query block missing from_clause (FROM DUAL not yet supported in minimal implementation)");
    }

    PlSqlParser.Where_clauseContext whereCtx = ctx.where_clause();

    // PHASE 1: ANALYSIS - Scan FROM and WHERE to detect outer joins
    // This must happen BEFORE visiting FROM/WHERE to prepare transformation context
    OuterJoinContext outerJoinContext = OuterJoinAnalyzer.analyze(fromClauseCtx, whereCtx);

    // Push the outer join context onto the stack for this query level
    // This handles nested queries (subqueries) - each query gets its own context
    b.pushOuterJoinContext(outerJoinContext);

    try {
      // PHASE 2: TRANSFORMATION - Visit clauses with prepared context

      // IMPORTANT: Visit FROM clause FIRST to register table aliases
      // before processing SELECT list (which may reference those aliases for type methods)
      // The FROM visitor will now use outerJoinContext to generate ANSI JOIN syntax
      String fromClause = b.visit(fromClauseCtx);

      // Extract SELECT list - use visitor pattern
      PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
      if (selectedListCtx == null) {
        throw new TransformationException("Query block missing selected_list");
      }
      String selectedList = b.visit(selectedListCtx);

      // Build the SELECT statement
      StringBuilder result = new StringBuilder();
      result.append("SELECT ").append(selectedList).append(" FROM ").append(fromClause);

      // Extract WHERE clause (if present)
      // The WHERE visitor will now use outerJoinContext to filter out (+) conditions
      if (whereCtx != null) {
        String whereClause = b.visit(whereCtx);
        if (whereClause != null && !whereClause.trim().isEmpty()) {
          result.append(" ").append(whereClause);
        }
      }

      return result.toString();

    } finally {
      // Always pop the context when leaving this query level (even if exception occurs)
      // This ensures nested subqueries don't corrupt parent query contexts
      b.popOuterJoinContext();
    }
  }
}
