package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitRelationalExpression {
  public static String v(
      PlSqlParser.Relational_expressionContext ctx, PostgresCodeBuilder b) {

    // Grammar: relational_expression relational_operator relational_expression | compound_expression

    // Check for relational operator (=, !=, <, >, <=, >=)
    if (ctx.relational_operator() != null) {
      // Binary relational expression
      java.util.List<PlSqlParser.Relational_expressionContext> operands = ctx.relational_expression();
      if (operands == null || operands.size() != 2) {
        throw new TransformationException("Relational expression must have exactly 2 operands");
      }

      String left = b.visit(operands.get(0));
      String operator = visitRelationalOperator(ctx.relational_operator());
      String right = b.visit(operands.get(1));

      return left + " " + operator + " " + right;
    }

    // Visit compound_expression (the simple case)
    PlSqlParser.Compound_expressionContext compoundCtx = ctx.compound_expression();
    if (compoundCtx == null) {
      throw new TransformationException("Relational expression missing compound_expression");
    }

    return b.visit(compoundCtx);
  }

  private static String visitRelationalOperator(PlSqlParser.Relational_operatorContext ctx) {
    // Grammar: '=' | (NOT_EQUAL_OP | '<' '>' | '!' '=' | '^' '=') | ('<' | '>') '='?

    // Return the raw text of the operator (most are compatible with PostgreSQL)
    String opText = ctx.getText();

    // Transform Oracle-specific operators to PostgreSQL equivalents
    switch (opText) {
      case "!=":
      case "<>":
      case "^=":  // Oracle != variant
        return "<>";  // PostgreSQL standard inequality
      case "=":
      case "<":
      case ">":
      case "<=":
      case ">=":
        return opText;  // Compatible as-is
      default:
        return opText;  // Pass through (may fail in PostgreSQL if incompatible)
    }
  }
}
