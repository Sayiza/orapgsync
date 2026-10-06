package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinCondition;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.TableInfo;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    if (outerJoinCtx == null || !outerJoinCtx.hasOuterJoins()) {
      // No outer joins - leave as implicit joins (comma-separated tables)
      // This is perfectly valid PostgreSQL syntax for INNER JOINs
      return String.join(", ", tableRefs);
    }

    // We have outer joins - generate ANSI JOIN syntax
    return generateAnsiJoinSyntax(outerJoinCtx, tableRefs, b);
  }

  /**
   * Generates ANSI JOIN syntax from outer join context.
   *
   * <p>Algorithm:
   * <ol>
   *   <li>Start with the first table (root)</li>
   *   <li>For each outer join, append the appropriate JOIN clause</li>
   *   <li>Keep remaining tables as comma-separated (implicit joins)</li>
   * </ol>
   *
   * <p>Example:
   * <pre>
   * FROM a, b, c
   * WHERE a.field1 = b.field1(+)
   *   AND TRUNC(b.field2) = TRUNC(c.field2(+))
   *
   * Becomes:
   * FROM a LEFT JOIN b ON a.field1 = b.field1
   *          LEFT JOIN c ON DATE_TRUNC('day', b.field2)::DATE = DATE_TRUNC('day', c.field2)::DATE
   * </pre>
   *
   * @param ctx Outer join context with AST nodes
   * @param tableRefs Table references
   * @param builder PostgresCodeBuilder for transforming AST nodes
   * @return ANSI JOIN syntax with all transformations applied
   */
  private static String generateAnsiJoinSyntax(OuterJoinContext ctx, List<String> tableRefs,
                                                PostgresCodeBuilder builder) {
    List<TableInfo> allTables = ctx.getAllTables();
    List<OuterJoinCondition> outerJoins = ctx.getOuterJoins();

    if (allTables.isEmpty()) {
      throw new TransformationException("No tables found in outer join context");
    }

    if (tableRefs.size() != allTables.size()) {
      throw new TransformationException("Mismatch between table count in context and transformed refs");
    }

    // Build a map from table key to transformed table reference
    // tableRefs are in the same order as allTables (both from table_ref_list)
    Map<String, String> keyToTransformedRef = new java.util.HashMap<>();
    for (int i = 0; i < allTables.size(); i++) {
      keyToTransformedRef.put(allTables.get(i).getKey(), tableRefs.get(i));
    }

    // Track which tables have been joined
    Set<String> joinedTables = new HashSet<>();

    // Start with the first table (use transformed reference with schema qualification)
    TableInfo firstTable = allTables.get(0);
    StringBuilder result = new StringBuilder();
    result.append(keyToTransformedRef.get(firstTable.getKey()));
    joinedTables.add(firstTable.getKey());

    // Add outer joins in order
    for (OuterJoinCondition join : outerJoins) {
      String table1Key = join.getTableKey1();
      String table2Key = join.getTableKey2();

      // Determine which table to join (the one not yet joined)
      TableInfo tableToJoin = null;
      String baseTableKey = null;

      if (joinedTables.contains(table1Key) && !joinedTables.contains(table2Key)) {
        // Join table2 to table1
        tableToJoin = ctx.getTable(table2Key);
        baseTableKey = table1Key;
      } else if (joinedTables.contains(table2Key) && !joinedTables.contains(table1Key)) {
        // Join table1 to table2
        tableToJoin = ctx.getTable(table1Key);
        baseTableKey = table2Key;
      } else if (!joinedTables.contains(table1Key) && !joinedTables.contains(table2Key)) {
        // Neither table joined yet - this shouldn't happen with proper join ordering
        // For now, just join table2
        tableToJoin = ctx.getTable(table2Key);
        baseTableKey = table1Key;
      }

      if (tableToJoin != null) {
        // Add the JOIN clause
        String joinType = join.getJoinType() == OuterJoinCondition.JoinType.LEFT ? "LEFT JOIN" : "RIGHT JOIN";
        result.append(" ").append(joinType).append(" ");
        // Use transformed table reference (with schema qualification)
        result.append(keyToTransformedRef.get(tableToJoin.getKey()));
        // Transform join conditions (converts Oracle functions to PostgreSQL and removes (+))
        result.append(" ON ").append(join.getCombinedConditions(builder));

        joinedTables.add(tableToJoin.getKey());
      }
    }

    // Add any remaining tables as comma-separated (implicit joins)
    for (TableInfo table : allTables) {
      if (!joinedTables.contains(table.getKey())) {
        // Use transformed table reference (with schema qualification)
        result.append(", ").append(keyToTransformedRef.get(table.getKey()));
        joinedTables.add(table.getKey());
      }
    }

    return result.toString();
  }
}
