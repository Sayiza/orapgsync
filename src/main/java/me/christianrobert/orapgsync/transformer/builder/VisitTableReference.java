package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitTableReference {
  public static String v(PlSqlParser.Table_refContext ctx, PostgresCodeBuilder b) {

    // Grammar: table_ref = table_ref_aux join_clause*
    // This handles both simple table references and ANSI JOINs

    // STEP 1: Process the first table (table_ref_aux)
    PlSqlParser.Table_ref_auxContext tableRefAux = ctx.table_ref_aux();
    if (tableRefAux == null) {
      throw new TransformationException("Table reference missing table_ref_aux");
    }

    String result = processTableRefAux(tableRefAux, b);

    // STEP 2: Process ANSI JOINs (join_clause*) - zero or more
    java.util.List<PlSqlParser.Join_clauseContext> joinClauses = ctx.join_clause();
    if (joinClauses != null && !joinClauses.isEmpty()) {
      for (PlSqlParser.Join_clauseContext joinClause : joinClauses) {
        result += " " + processJoinClause(joinClause, b);
      }
    }

    return result;
  }

  /**
   * Processes table_ref_aux (single table or subquery with optional alias).
   */
  private static String processTableRefAux(PlSqlParser.Table_ref_auxContext tableRefAux, PostgresCodeBuilder b) {
    PlSqlParser.Table_ref_aux_internalContext internal = tableRefAux.table_ref_aux_internal();
    if (internal == null) {
      throw new TransformationException("Table reference missing table_ref_aux_internal");
    }

    // Handle different types of table references:
    // 1. table_ref_aux_internal_one: dml_table_expression_clause (table or subquery)
    // 2. table_ref_aux_internal_two: '(' table_ref ')' (parenthesized table ref)
    // 3. table_ref_aux_internal_thre: ONLY '(' dml_table_expression_clause ')' (ONLY syntax)

    String tableExpression;

    if (internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext) {
      PlSqlParser.Table_ref_aux_internal_oneContext internalOne =
          (PlSqlParser.Table_ref_aux_internal_oneContext) internal;
      PlSqlParser.Dml_table_expression_clauseContext dmlTable = internalOne.dml_table_expression_clause();
      tableExpression = handleDmlTableExpression(dmlTable, b);
    } else if (internal instanceof PlSqlParser.Table_ref_aux_internal_threContext) {
      // ONLY (table) syntax
      PlSqlParser.Table_ref_aux_internal_threContext internalThree =
          (PlSqlParser.Table_ref_aux_internal_threContext) internal;
      PlSqlParser.Dml_table_expression_clauseContext dmlTable = internalThree.dml_table_expression_clause();
      tableExpression = handleDmlTableExpression(dmlTable, b);
    } else {
      throw new TransformationException("Table reference type not supported: " + internal.getClass().getSimpleName());
    }

    // Check for alias (applies to both tables and subqueries)
    PlSqlParser.Table_aliasContext aliasCtx = tableRefAux.table_alias();
    if (aliasCtx != null) {
      String alias = aliasCtx.getText();

      // Register the alias in the transformation context (if available)
      TransformationContext context = b.getContext();
      if (context != null) {
        // For subqueries, we register the alias but not the "table name" (since it's a derived table)
        // For regular tables, we've already resolved the name in handleDmlTableExpression
        if (!tableExpression.startsWith("(")) {
          // Regular table reference - register alias
          context.registerAlias(alias, tableExpression);
        }
      }

      return tableExpression + " " + alias;
    }

    return tableExpression;
  }

  /**
   * Processes ANSI JOIN clause.
   *
   * <p>Grammar:
   * <pre>
   * join_clause
   *     : query_partition_clause? (CROSS | NATURAL)? (INNER | outer_join_type)? JOIN
   *       table_ref_aux query_partition_clause? (join_on_part | join_using_part)*
   *
   * outer_join_type
   *     : (FULL | LEFT | RIGHT) OUTER?
   * </pre>
   *
   * <p>Strategy: Pass through JOIN keywords, transform table and conditions.
   */
  private static String processJoinClause(PlSqlParser.Join_clauseContext ctx, PostgresCodeBuilder b) {
    StringBuilder result = new StringBuilder();

    // Build JOIN keywords
    // Pattern: [CROSS | NATURAL]? [INNER | FULL/LEFT/RIGHT [OUTER]?]? JOIN

    if (ctx.CROSS() != null) {
      result.append("CROSS ");
    }

    if (ctx.NATURAL() != null) {
      result.append("NATURAL ");
    }

    if (ctx.INNER() != null) {
      result.append("INNER ");
    }

    // Outer join type: FULL/LEFT/RIGHT [OUTER]?
    PlSqlParser.Outer_join_typeContext outerJoinType = ctx.outer_join_type();
    if (outerJoinType != null) {
      if (outerJoinType.FULL() != null) {
        result.append("FULL ");
      } else if (outerJoinType.LEFT() != null) {
        result.append("LEFT ");
      } else if (outerJoinType.RIGHT() != null) {
        result.append("RIGHT ");
      }

      if (outerJoinType.OUTER() != null) {
        result.append("OUTER ");
      }
    }

    result.append("JOIN ");

    // Process the joined table (table_ref_aux)
    PlSqlParser.Table_ref_auxContext joinedTable = ctx.table_ref_aux();
    if (joinedTable == null) {
      throw new TransformationException("JOIN clause missing table_ref_aux");
    }

    result.append(processTableRefAux(joinedTable, b));

    // Process join conditions: ON or USING clause
    // join_on_part: ON condition
    // join_using_part: USING paren_column_list

    java.util.List<PlSqlParser.Join_on_partContext> onParts = ctx.join_on_part();
    if (onParts != null && !onParts.isEmpty()) {
      for (PlSqlParser.Join_on_partContext onPart : onParts) {
        result.append(" ON ");
        PlSqlParser.ConditionContext condition = onPart.condition();
        if (condition != null) {
          result.append(b.visit(condition));
        }
      }
    }

    java.util.List<PlSqlParser.Join_using_partContext> usingParts = ctx.join_using_part();
    if (usingParts != null && !usingParts.isEmpty()) {
      for (PlSqlParser.Join_using_partContext usingPart : usingParts) {
        result.append(" USING ");
        PlSqlParser.Paren_column_listContext parenColumnList = usingPart.paren_column_list();
        if (parenColumnList != null) {
          result.append(parenColumnList.getText());
        }
      }
    }

    return result.toString();
  }

  /**
   * Handles dml_table_expression_clause which can be:
   * <ul>
   *   <li>tableview_name - Regular table reference</li>
   *   <li>'(' select_statement ')' - Subquery (inline view)</li>
   *   <li>table_collection_expression - Collection operator</li>
   *   <li>LATERAL '(' subquery ')' - Lateral subquery</li>
   * </ul>
   */
  private static String handleDmlTableExpression(
      PlSqlParser.Dml_table_expression_clauseContext dmlTable, PostgresCodeBuilder b) {

    if (dmlTable == null) {
      throw new TransformationException("dml_table_expression_clause is null");
    }

    // CASE 1: Regular table reference (tableview_name)
    PlSqlParser.Tableview_nameContext tableviewName = dmlTable.tableview_name();
    if (tableviewName != null) {
      return handleTableviewName(tableviewName, b);
    }

    // CASE 2: Subquery in FROM clause: '(' select_statement ')'
    PlSqlParser.Select_statementContext selectStatement = dmlTable.select_statement();
    if (selectStatement != null) {
      return handleSubquery(selectStatement, b);
    }

    // CASE 3: Other cases (not yet implemented)
    throw new TransformationException(
        "Unsupported dml_table_expression_clause type. Only tableview_name and subqueries are currently supported.");
  }

  /**
   * Handles regular table references (tableview_name).
   * Applies synonym resolution and schema qualification.
   */
  private static String handleTableviewName(
      PlSqlParser.Tableview_nameContext tableviewName, PostgresCodeBuilder b) {

    String tableName = tableviewName.getText();
    TransformationContext context = b.getContext();

    // Apply name resolution logic (only if context is available)
    if (context != null) {
      // STEP 1: Try to resolve as synonym first
      // Oracle synonyms don't exist in PostgreSQL, so we must resolve them during transformation
      String resolvedName = context.resolveSynonym(tableName);
      if (resolvedName != null) {
        // Synonym resolved to actual qualified table name (schema.table)
        tableName = resolvedName;
      } else {
        // STEP 2: Not a synonym - qualify unqualified names with current schema
        // Oracle implicitly uses current schema for unqualified names
        // PostgreSQL uses search_path, which can cause wrong table or "does not exist" errors
        // Solution: Explicitly qualify all unqualified table names
        if (!tableName.contains(".")) {
          // Unqualified name → qualify with current schema
          tableName = context.getCurrentSchema().toLowerCase() + "." + tableName.toLowerCase();
        }
      }
    }
    // If context not available, keep original name (e.g., in simple tests without metadata)

    return tableName;
  }

  /**
   * Handles subqueries in FROM clause (inline views).
   * Recursively transforms the SELECT statement inside the subquery.
   *
   * <p>Example:
   * <pre>
   * Oracle:     (SELECT dept_id FROM departments WHERE active = 'Y')
   * PostgreSQL: (SELECT dept_id FROM hr.departments WHERE active = 'Y')
   * </pre>
   */
  private static String handleSubquery(
      PlSqlParser.Select_statementContext selectStatement, PostgresCodeBuilder b) {

    // Recursively transform the subquery using the same PostgresCodeBuilder
    // This ensures all transformation rules (schema qualification, synonyms, etc.) apply
    String transformedSubquery = b.visit(selectStatement);

    // Wrap in parentheses (required for subqueries in FROM clause)
    return "( " + transformedSubquery + " )";
  }
}
