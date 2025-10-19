package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitSelectListElement {
  public static String v(PlSqlParser.Select_list_elementsContext ctx, PostgresCodeBuilder b) {

    // select_list_elements: tableview_name '.' ASTERISK | expression (column_alias)?

    if (ctx.ASTERISK() != null) {
      // table.* syntax - get table name and build "table . *"
      PlSqlParser.Tableview_nameContext tableCtx = ctx.tableview_name();
      if (tableCtx == null) {
        throw new TransformationException("SELECT table.* missing table name");
      }

      // Get the table/alias name
      String tableName = tableCtx.getText();
      return tableName + " . *";
    }

    // Regular expression (column, function call, etc.)
    PlSqlParser.ExpressionContext exprCtx = ctx.expression();
    if (exprCtx == null) {
      throw new TransformationException("Select list element missing expression");
    }

    String expression = b.visit(exprCtx);

    // Handle column alias if present
    PlSqlParser.Column_aliasContext aliasCtx = ctx.column_alias();
    if (aliasCtx != null) {
      String alias = buildColumnAlias(aliasCtx);
      return expression + " " + alias;
    }

    return expression;
  }

  /**
   * Builds column alias from column_alias context.
   *
   * Grammar: column_alias : AS? (identifier | quoted_string) | AS
   *
   * Examples:
   * - AS total → AS total
   * - total → AS total
   * - AS "Total Amount" → AS "Total Amount"
   * - "Total Amount" → AS "Total Amount"
   */
  private static String buildColumnAlias(PlSqlParser.Column_aliasContext ctx) {
    if (ctx == null) {
      return "";
    }

    // Check if AS keyword is present
    boolean hasAs = ctx.AS() != null;

    // Get identifier or quoted_string
    PlSqlParser.IdentifierContext identifierCtx = ctx.identifier();
    PlSqlParser.Quoted_stringContext quotedStringCtx = ctx.quoted_string();

    if (identifierCtx != null) {
      // AS identifier or just identifier
      String alias = identifierCtx.getText();
      return hasAs ? "AS " + alias : "AS " + alias;
    } else if (quotedStringCtx != null) {
      // AS quoted_string or just quoted_string
      String alias = quotedStringCtx.getText();
      return hasAs ? "AS " + alias : "AS " + alias;
    } else if (hasAs) {
      // Just AS alone (edge case from grammar: "| AS")
      // This is unusual but grammar allows it - just return AS
      return "AS";
    }

    return "";
  }
}
