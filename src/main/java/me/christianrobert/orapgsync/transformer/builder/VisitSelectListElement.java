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

    // Special case: ROWNUM pseudocolumn
    // Transform: SELECT ROWNUM → SELECT row_number() OVER () AS rownum
    // Transform: SELECT ROWNUM AS rn → SELECT row_number() OVER () AS rn
    if (isRownumExpression(exprCtx)) {
      String windowFunction = "row_number( ) OVER ( )";

      // Handle column alias
      PlSqlParser.Column_aliasContext aliasCtx = ctx.column_alias();
      if (aliasCtx != null) {
        // User provided alias - use it
        String alias = buildColumnAlias(aliasCtx);
        return windowFunction + " " + alias;
      } else {
        // No alias - add "AS rownum" to preserve column name
        return windowFunction + " AS rownum";
      }
    }

    // Regular expression - visit normally
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

  /**
   * Checks if an expression is the ROWNUM pseudocolumn.
   *
   * <p>Proper AST navigation to detect simple ROWNUM identifier.
   * This follows the same pattern as RownumAnalyzer.
   *
   * <p>Expression hierarchy:
   * expression → logical_expression → unary_logical_expression →
   * multiset_expression → relational_expression → compound_expression →
   * concatenation → model_expression → unary_expression → atom →
   * general_element → general_element_part → id_expression
   *
   * @param ctx The expression context
   * @return True if this expression is ROWNUM (case-insensitive)
   */
  private static boolean isRownumExpression(PlSqlParser.ExpressionContext ctx) {
    if (ctx == null) {
      return false;
    }

    // Navigate: expression → logical_expression
    PlSqlParser.Logical_expressionContext logicalExpr = ctx.logical_expression();
    if (logicalExpr == null) {
      return false;
    }

    // logical_expression can have multiple children for AND/OR
    // We want simple expression only (no AND/OR)
    if (logicalExpr.AND() != null || logicalExpr.OR() != null) {
      return false;
    }

    // logical_expression → unary_logical_expression
    PlSqlParser.Unary_logical_expressionContext unaryLogicalExpr = logicalExpr.unary_logical_expression();
    if (unaryLogicalExpr == null) {
      return false;
    }

    // unary_logical_expression → multiset_expression
    PlSqlParser.Multiset_expressionContext multisetExpr = unaryLogicalExpr.multiset_expression();
    if (multisetExpr == null) {
      return false;
    }

    // multiset_expression → relational_expression
    PlSqlParser.Relational_expressionContext relationalExpr = multisetExpr.relational_expression();
    if (relationalExpr == null) {
      return false;
    }

    // Must be simple relational_expression (no operators)
    if (relationalExpr.relational_operator() != null) {
      return false;
    }

    // relational_expression → compound_expression
    PlSqlParser.Compound_expressionContext compoundExpr = relationalExpr.compound_expression();
    if (compoundExpr == null) {
      return false;
    }

    // compound_expression → concatenation list
    java.util.List<PlSqlParser.ConcatenationContext> concats = compoundExpr.concatenation();
    if (concats == null || concats.size() != 1) {
      // Multiple concatenations mean || operator
      return false;
    }

    // concatenation → model_expression
    PlSqlParser.Model_expressionContext modelExpr = concats.get(0).model_expression();
    if (modelExpr == null) {
      return false;
    }

    // model_expression → unary_expression
    PlSqlParser.Unary_expressionContext unaryExpr = modelExpr.unary_expression();
    if (unaryExpr == null) {
      return false;
    }

    // Must not have unary operators (+, -, PRIOR, etc.)
    // Check if first child is an operator token
    if (unaryExpr.getChildCount() > 0) {
      String firstChild = unaryExpr.getChild(0).getText();
      if (firstChild.equals("+") || firstChild.equals("-")) {
        return false;
      }
    }

    // Check for PRIOR, CONNECT_BY_ROOT, NEW, DISTINCT, ALL
    if (unaryExpr.PRIOR() != null || unaryExpr.CONNECT_BY_ROOT() != null ||
        unaryExpr.NEW() != null || unaryExpr.DISTINCT() != null || unaryExpr.ALL() != null) {
      return false;
    }

    // unary_expression → atom
    PlSqlParser.AtomContext atom = unaryExpr.atom();
    if (atom == null) {
      return false;
    }

    // atom → general_element (not constant, not expression_list, etc.)
    PlSqlParser.General_elementContext generalElem = atom.general_element();
    if (generalElem == null) {
      return false;
    }

    // general_element → general_element_part list
    java.util.List<PlSqlParser.General_element_partContext> parts = generalElem.general_element_part();
    if (parts == null || parts.size() != 1) {
      // Multiple parts means qualified identifier (schema.table.column)
      return false;
    }

    // Get the single part
    PlSqlParser.General_element_partContext part = parts.get(0);

    // Must not have function arguments
    if (part.function_argument() != null && !part.function_argument().isEmpty()) {
      return false;
    }

    // Get id_expression
    PlSqlParser.Id_expressionContext idExpr = part.id_expression();
    if (idExpr == null) {
      return false;
    }

    // Check the identifier text
    String identifier = idExpr.getText().toUpperCase();
    return identifier.equals("ROWNUM");
  }
}
