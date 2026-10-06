package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting constant literals.
 * Handles: numeric, quoted_string, NULL, TRUE, FALSE, DATE literals, TIMESTAMP literals.
 */
public class VisitConstant {
  public static String v(PlSqlParser.ConstantContext ctx, PostgresCodeBuilder b) {

    // Numeric literals (integers, floats)
    if (ctx.numeric() != null) {
      return ctx.numeric().getText();
    }

    // Quoted string literals
    if (ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
      return ctx.quoted_string(0).getText();
    }

    // NULL literal
    if (ctx.NULL_() != null) {
      return "NULL";
    }

    // Boolean literals
    if (ctx.TRUE() != null) {
      return "TRUE";
    }
    if (ctx.FALSE() != null) {
      return "FALSE";
    }

    // DATE literal: DATE 'YYYY-MM-DD'
    if (ctx.DATE() != null && ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
      String dateStr = ctx.quoted_string(0).getText();
      return "DATE " + dateStr;
    }

    // TIMESTAMP literal: TIMESTAMP 'YYYY-MM-DD HH:MI:SS'
    if (ctx.TIMESTAMP() != null) {
      // Basic TIMESTAMP support - just pass through for now
      // Oracle: TIMESTAMP '2024-01-01 12:00:00'
      // PostgreSQL: Same syntax (compatible)
      return ctx.getText();
    }

    // INTERVAL literal (complex - defer for now)
    if (ctx.INTERVAL() != null) {
      throw new TransformationException(
          "INTERVAL literals not yet supported - needs complex transformation");
    }

    // DBTIMEZONE, SESSIONTIMEZONE, MINVALUE, MAXVALUE
    if (ctx.DBTIMEZONE() != null) {
      // Oracle DBTIMEZONE → PostgreSQL current_setting('TIMEZONE')
      return "current_setting('TIMEZONE')";
    }
    if (ctx.SESSIONTIMEZONE() != null) {
      // Oracle SESSIONTIMEZONE → PostgreSQL current_setting('TIMEZONE')
      return "current_setting('TIMEZONE')";
    }
    if (ctx.MINVALUE() != null) {
      return ctx.getText(); // Pass through
    }
    if (ctx.MAXVALUE() != null) {
      return ctx.getText(); // Pass through
    }

    // Unknown constant type
    throw new TransformationException(
        "Unsupported constant type: " + ctx.getText());
  }
}
