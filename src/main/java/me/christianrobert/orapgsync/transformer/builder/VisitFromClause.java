package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinCondition;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
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

    // Check if we have outer join context
    OuterJoinContext outerJoinCtx = b.getOuterJoinContext();
    if (outerJoinCtx == null || !outerJoinCtx.isFor(ctx) || !outerJoinCtx.hasOuterJoins()) {
      // No outer joins - leave as implicit joins (comma-separated tables)
      // This is perfectly valid PostgreSQL syntax for INNER JOINs
      return String.join(", ", tableRefs);
    }

    // We have outer joins - generate ANSI JOIN syntax
    return generateAnsiJoinSyntax(ctx, outerJoinCtx, tableRefs, b);
  }

  /**
   * Generates the join tree for a FROM clause with Oracle (+) outer joins.
   *
   * <p>Items keep their FROM order. An outer-joined item becomes {@code LEFT JOIN item ON ...};
   * one listed before an item its condition references is moved to just after the last such
   * item. That is the only reordering, and since a bare {@code SELECT *} returns columns in FROM
   * order — and both a view wrapper and {@code SELECT * INTO} read them by position — a bare
   * {@code *} over a reordered FROM is refused.
   *
   * <p>Other items join with {@code CROSS JOIN} while a later ON still references something
   * before them, and with a comma once nothing does. A comma cannot be used throughout:
   * PostgreSQL scopes {@code ON} to its own join tree, so {@code FROM a, c LEFT JOIN b ON a.k = b.k}
   * fails. The condition-free remainder stays comma-separated, so the planner's
   * {@code join_collapse_limit} is not reached on account of tables no ON needs.
   *
   * <p>Example (Oracle: {@code FROM a, c, d, h WHERE a.c = c.nr AND a.d = d.nr AND d.h = h.nr(+)}):
   * <pre>
   * FROM a, c, d LEFT JOIN h ON d.h = h.nr WHERE a.c = c.nr AND a.d = d.nr
   * </pre>
   */
  private static String generateAnsiJoinSyntax(PlSqlParser.From_clauseContext fromCtx, OuterJoinContext ctx,
                                                List<String> tableRefs, PostgresCodeBuilder builder) {
    int n = tableRefs.size();
    if (ctx.getAllTables().size() != n) {
      throw new TransformationException("Mismatch between table count in outer join context and transformed refs");
    }

    // Emission order: the first item in FROM order that can be emitted, repeatedly
    List<Integer> order = new ArrayList<>();
    boolean[] emitted = new boolean[n];
    while (order.size() < n) {
      int pick = -1;
      for (int i = 0; i < n && pick < 0; i++) {
        if (emitted[i]) {
          continue;
        }
        OuterJoinCondition join = ctx.getOuterJoin(i);
        if (join == null || join.getDependencies().stream().allMatch(d -> emitted[d])) {
          pick = i;
        }
      }
      if (pick < 0) {
        throw new TransformationException("Oracle outer join (+): tables are outer-joined to each other "
            + "(Oracle rejects it too, ORA-01416): " + fromCtx.getText());
      }
      order.add(pick);
      emitted[pick] = true;
    }

    boolean reordered = false;
    for (int k = 0; k < n; k++) {
      reordered |= order.get(k) != k;
    }
    if (reordered && selectsBareAsterisk(fromCtx)) {
      throw new TransformationException("Oracle outer join (+): an outer-joined table precedes the table it joins to, "
          + "so its join changes the FROM order, and SELECT * would return the columns in a different order");
    }

    // A comma may precede position k only if no ON at k or later references a position before k
    int[] position = new int[n];
    for (int k = 0; k < n; k++) {
      position[order.get(k)] = k;
    }
    int[] earliestReferenceFrom = new int[n + 1];
    earliestReferenceFrom[n] = Integer.MAX_VALUE;
    for (int k = n - 1; k >= 0; k--) {
      int earliest = Integer.MAX_VALUE;
      OuterJoinCondition join = ctx.getOuterJoin(order.get(k));
      if (join != null) {
        for (int dependency : join.getDependencies()) {
          earliest = Math.min(earliest, position[dependency]);
        }
      }
      earliestReferenceFrom[k] = Math.min(earliest, earliestReferenceFrom[k + 1]);
    }

    StringBuilder result = new StringBuilder(tableRefs.get(order.get(0)));
    for (int k = 1; k < n; k++) {
      int item = order.get(k);
      OuterJoinCondition join = ctx.getOuterJoin(item);
      if (join != null) {
        result.append(" LEFT JOIN ").append(tableRefs.get(item))
            .append(" ON ").append(join.getCombinedConditions(builder));
      } else if (earliestReferenceFrom[k] >= k) {
        result.append(", ").append(tableRefs.get(item));
      } else {
        result.append(" CROSS JOIN ").append(tableRefs.get(item));
      }
    }
    return result.toString();
  }

  private static boolean selectsBareAsterisk(PlSqlParser.From_clauseContext fromCtx) {
    if (fromCtx.getParent() instanceof PlSqlParser.Query_blockContext queryBlock) {
      PlSqlParser.Selected_listContext selected = queryBlock.selected_list();
      return selected != null && selected.select_list_elements().isEmpty();
    }
    return false;
  }
}
