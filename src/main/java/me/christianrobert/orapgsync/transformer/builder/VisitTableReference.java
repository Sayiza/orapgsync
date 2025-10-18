package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitTableReference {
  public static String v(PlSqlParser.Table_refContext ctx, PostgresCodeBuilder b) {

    // Navigate: table_ref -> table_ref_aux -> table_ref_aux_internal -> ...
    PlSqlParser.Table_ref_auxContext tableRefAux = ctx.table_ref_aux();
    if (tableRefAux == null) {
      throw new TransformationException("Table reference missing table_ref_aux");
    }

    PlSqlParser.Table_ref_aux_internalContext internal = tableRefAux.table_ref_aux_internal();
    if (internal == null) {
      throw new TransformationException("Table reference missing table_ref_aux_internal");
    }

    // ANTLR generates subclasses for labeled alternatives in the grammar
    PlSqlParser.Dml_table_expression_clauseContext dmlTable = null;

    if (internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext) {
      PlSqlParser.Table_ref_aux_internal_oneContext internalOne =
          (PlSqlParser.Table_ref_aux_internal_oneContext) internal;
      dmlTable = internalOne.dml_table_expression_clause();
    } else if (internal instanceof PlSqlParser.Table_ref_aux_internal_threContext) {
      // ONLY (table) syntax
      PlSqlParser.Table_ref_aux_internal_threContext internalThree =
          (PlSqlParser.Table_ref_aux_internal_threContext) internal;
      dmlTable = internalThree.dml_table_expression_clause();
    }

    if (dmlTable == null) {
      throw new TransformationException("Table reference type not supported in minimal implementation");
    }

    PlSqlParser.Tableview_nameContext tableviewName = dmlTable.tableview_name();
    if (tableviewName == null) {
      throw new TransformationException("Table reference missing tableview_name");
    }

    String tableName = tableviewName.getText();

    // Get transformation context once (used for both synonym resolution and alias registration)
    TransformationContext context = b.getContext();

    // Resolve synonyms to actual table names
    // Oracle synonyms don't exist in PostgreSQL, so we must resolve them during transformation
    if (context != null) {
      String resolvedName = context.resolveSynonym(tableName);
      if (resolvedName != null) {
        // Synonym resolved to actual qualified table name (schema.table)
        tableName = resolvedName;
      }
      // Otherwise keep original name (not a synonym, or context not available)
    }

    // Check for alias
    PlSqlParser.Table_aliasContext aliasCtx = tableRefAux.table_alias();
    String alias = null;
    if (aliasCtx != null) {
      alias = aliasCtx.getText();

      // Register the alias in the transformation context (if available)
      if (context != null) {
        context.registerAlias(alias, tableName);
      }

      return tableName + " " + alias;
    }

    return tableName;
  }
}
