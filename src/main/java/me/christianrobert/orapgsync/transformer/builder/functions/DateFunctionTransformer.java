package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

import static me.christianrobert.orapgsync.transformer.builder.functions.FunctionHeuristics.*;

/**
 * Transforms Oracle date/time functions to PostgreSQL equivalents.
 *
 * <p>Handles transformations for:
 * <ul>
 *   <li>ADD_MONTHS(date, n) → date + INTERVAL 'n months'</li>
 *   <li>MONTHS_BETWEEN(date1, date2) → EXTRACT(YEAR FROM AGE(...)) * 12 + EXTRACT(MONTH FROM AGE(...))</li>
 *   <li>LAST_DAY(date) → (DATE_TRUNC('MONTH', date) + INTERVAL '1 month' - INTERVAL '1 day')::DATE</li>
 *   <li>TRUNC(date[, format]) → DATE_TRUNC(field, date)::DATE</li>
 *   <li>ROUND(date[, format]) → CASE WHEN + DATE_TRUNC (conditional rounding)</li>
 * </ul>
 *
 * <p>For TRUNC and ROUND, uses heuristics to distinguish date operations from numeric operations.
 * See {@link FunctionHeuristics} for disambiguation logic.
 */
public class DateFunctionTransformer {

  /**
   * Main entry point for date function transformations.
   *
   * @param functionName Function name (ADD_MONTHS, TRUNC, ROUND, etc.)
   * @param partCtx Function call context from ANTLR
   * @param b PostgreSQL code builder for recursive transformations
   * @return Transformed PostgreSQL SQL
   */
  public static String transform(
      String functionName,
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    switch (functionName.toUpperCase()) {
      case "ADD_MONTHS":
        return transformAddMonths(partCtx, b);
      case "MONTHS_BETWEEN":
        return transformMonthsBetween(partCtx, b);
      case "LAST_DAY":
        return transformLastDay(partCtx, b);
      case "TRUNC":
        return transformTrunc(partCtx, b);
      case "ROUND":
        return transformRound(partCtx, b);
      default:
        throw new TransformationException("Unsupported date function: " + functionName);
    }
  }

  /**
   * Transforms Oracle ADD_MONTHS to PostgreSQL date + INTERVAL.
   *
   * <p>Oracle: ADD_MONTHS(date, n)
   * <p>PostgreSQL: date + INTERVAL 'n months'
   *
   * <p>Example:
   * <ul>
   *   <li>ADD_MONTHS(hire_date, 6) → hire_date + INTERVAL '6 months'</li>
   *   <li>ADD_MONTHS(hire_date, -3) → hire_date + INTERVAL '-3 months'</li>
   * </ul>
   */
  private static String transformAddMonths(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);
    if (args.size() != 2) {
      throw new TransformationException(
          "ADD_MONTHS requires exactly 2 arguments (date, months), found: " + args.size());
    }

    String dateExpr = transformArgument(args.get(0), b);
    String monthsExpr = transformArgument(args.get(1), b);

    // Build: date + INTERVAL 'n months'
    return dateExpr + " + INTERVAL '" + monthsExpr + " months'";
  }

  /**
   * Transforms Oracle MONTHS_BETWEEN to PostgreSQL AGE/EXTRACT.
   *
   * <p>Oracle: MONTHS_BETWEEN(date1, date2)
   * <p>PostgreSQL: (EXTRACT(YEAR FROM AGE(date1, date2)) * 12 + EXTRACT(MONTH FROM AGE(date1, date2)))
   *
   * <p>Example:
   * <ul>
   *   <li>MONTHS_BETWEEN(end_date, start_date) → (EXTRACT(YEAR FROM AGE(end_date, start_date)) * 12 + EXTRACT(MONTH FROM AGE(end_date, start_date)))</li>
   * </ul>
   */
  private static String transformMonthsBetween(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);
    if (args.size() != 2) {
      throw new TransformationException(
          "MONTHS_BETWEEN requires exactly 2 arguments (date1, date2), found: " + args.size());
    }

    String date1Expr = transformArgument(args.get(0), b);
    String date2Expr = transformArgument(args.get(1), b);

    // Build: (EXTRACT(YEAR FROM AGE(date1, date2)) * 12 + EXTRACT(MONTH FROM AGE(date1, date2)))
    return "( EXTRACT( YEAR FROM AGE( " + date1Expr + " , " + date2Expr + " ) ) * 12 + " +
           "EXTRACT( MONTH FROM AGE( " + date1Expr + " , " + date2Expr + " ) ) )";
  }

  /**
   * Transforms Oracle LAST_DAY to PostgreSQL DATE_TRUNC + INTERVAL.
   *
   * <p>Oracle: LAST_DAY(date)
   * <p>PostgreSQL: (DATE_TRUNC('MONTH', date) + INTERVAL '1 month' - INTERVAL '1 day')::DATE
   *
   * <p>Example:
   * <ul>
   *   <li>LAST_DAY(hire_date) → (DATE_TRUNC('MONTH', hire_date) + INTERVAL '1 month' - INTERVAL '1 day')::DATE</li>
   * </ul>
   */
  private static String transformLastDay(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);
    if (args.size() != 1) {
      throw new TransformationException(
          "LAST_DAY requires exactly 1 argument (date), found: " + args.size());
    }

    String dateExpr = transformArgument(args.get(0), b);

    // Build: (DATE_TRUNC('MONTH', date) + INTERVAL '1 month' - INTERVAL '1 day')::DATE
    return "( DATE_TRUNC( 'MONTH' , " + dateExpr + " ) + INTERVAL '1 month' - INTERVAL '1 day' )::DATE";
  }

  /**
   * Transforms Oracle TRUNC(date) to PostgreSQL DATE_TRUNC.
   *
   * <p>Oracle TRUNC is overloaded for both numbers and dates:
   * <ul>
   *   <li>Numeric: TRUNC(123.456, 2) → 123.45 (PostgreSQL has this)</li>
   *   <li>Date: TRUNC(hire_date, 'MM') → first day of month (needs DATE_TRUNC)</li>
   * </ul>
   *
   * <p>Heuristic to distinguish:
   * <ul>
   *   <li>If 2nd arg is date format string ('MM', 'YYYY', etc.) → Date TRUNC</li>
   *   <li>If 1st arg contains date expression (SYSDATE, TO_DATE, etc.) → Date TRUNC</li>
   *   <li>Otherwise → Numeric TRUNC (pass through)</li>
   * </ul>
   *
   * <p>PostgreSQL transformation: DATE_TRUNC(field, date)::DATE
   *
   * <p>Examples:
   * <ul>
   *   <li>TRUNC(hire_date) → DATE_TRUNC('day', hire_date)::DATE</li>
   *   <li>TRUNC(hire_date, 'MONTH') → DATE_TRUNC('month', hire_date)::DATE</li>
   *   <li>TRUNC(123.456, 2) → TRUNC(123.456, 2) (pass through)</li>
   * </ul>
   */
  private static String transformTrunc(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() < 1 || args.size() > 2) {
      // More than 2 args or no args: Not a valid date TRUNC, pass through
      return "TRUNC" + getFunctionArguments(partCtx, b);
    }

    // Apply heuristic to determine if this is date or numeric TRUNC
    boolean isDateTrunc = false;

    if (args.size() == 2) {
      // Check if 2nd argument looks like a date format string
      String secondArgText = args.get(1).getText(); // UNTRANSFORMED
      if (isDateFormatString(secondArgText)) {
        isDateTrunc = true;
      }
    } else if (args.size() == 1) {
      // Check if 1st argument looks like a date expression
      String firstArgText = args.get(0).getText(); // UNTRANSFORMED
      if (containsDateExpression(firstArgText)) {
        isDateTrunc = true;
      }
      // Otherwise: default to numeric TRUNC (safer)
    }

    if (!isDateTrunc) {
      // Numeric TRUNC: transform with type-aware cast
      // Get the first argument (the expression to truncate)
      PlSqlParser.ArgumentContext firstArgCtx = args.get(0);
      PlSqlParser.ExpressionContext firstArgExpr = firstArgCtx.expression();

      if (firstArgExpr == null) {
        // No expression found, pass through unchanged
        return "TRUNC" + getFunctionArguments(partCtx, b);
      }

      String transformedExpr = b.visit(firstArgExpr);

      // Use type evaluator to determine if cast is needed
      me.christianrobert.orapgsync.transformer.type.TypeInfo exprType =
          b.getContext().getTypeEvaluator().getType(firstArgExpr);

      // If type is known and numeric, no cast needed
      // If type is unknown or non-numeric, add defensive cast
      String argWithCast;
      if (exprType.isNumeric()) {
        argWithCast = transformedExpr;  // No cast needed
      } else {
        argWithCast = transformedExpr + "::numeric";  // Defensive cast
      }

      // Build TRUNC function with optional precision
      StringBuilder result = new StringBuilder("TRUNC( ");
      result.append(argWithCast);

      // Add second argument (precision) if present
      if (args.size() > 1) {
        result.append(" , ");
        result.append(b.visit(args.get(1)));
      }

      result.append(" )");
      return result.toString();
    }

    // Date TRUNC: transform to DATE_TRUNC
    String dateExpr = transformArgument(args.get(0), b);
    String format = "day"; // Default: truncate to day

    if (args.size() == 2) {
      // Extract format string and map it to PostgreSQL field
      String formatArg = transformArgument(args.get(1), b);
      // Remove quotes if present
      String formatStr = formatArg.replaceAll("^'|'$", "").toUpperCase();

      format = mapOracleDateFormatToPostgres(formatStr);
    }

    // Build: DATE_TRUNC('field', date)::DATE
    return "DATE_TRUNC( '" + format + "' , " + dateExpr + " )::DATE";
  }

  /**
   * Transforms Oracle ROUND(date) to PostgreSQL DATE_TRUNC + conditional logic.
   *
   * <p>Oracle ROUND is overloaded for both numbers and dates:
   * <ul>
   *   <li>Numeric: ROUND(123.456, 2) → 123.46 (PostgreSQL has this)</li>
   *   <li>Date: ROUND(hire_date, 'MM') → rounds to nearest month (needs complex logic)</li>
   * </ul>
   *
   * <p>Heuristic to distinguish:
   * <ul>
   *   <li>If 2nd arg is date format string ('MM', 'YYYY', etc.) → Date ROUND</li>
   *   <li>If 1st arg contains date expression (SYSDATE, TO_DATE, etc.) → Date ROUND</li>
   *   <li>Otherwise → Numeric ROUND (pass through)</li>
   * </ul>
   *
   * <p>Date ROUND logic:
   * <ul>
   *   <li>ROUND to day: If time >= noon, round up to next day, else truncate to midnight</li>
   *   <li>ROUND to month: If day >= 16, round up to next month, else truncate to first of month</li>
   *   <li>ROUND to year: If month >= July, round up to next year, else truncate to Jan 1</li>
   * </ul>
   *
   * <p>PostgreSQL transformation uses CASE WHEN with DATE_TRUNC:
   * <pre>
   * CASE
   *   WHEN EXTRACT(field FROM date) >= threshold
   *   THEN DATE_TRUNC(unit, date) + INTERVAL '1 unit'
   *   ELSE DATE_TRUNC(unit, date)
   * END::DATE
   * </pre>
   *
   * <p>Examples:
   * <ul>
   *   <li>ROUND(hire_date, 'MM') → CASE WHEN EXTRACT(DAY...) >= 16 THEN ... END</li>
   *   <li>ROUND(123.456, 2) → ROUND(123.456, 2) (pass through)</li>
   * </ul>
   */
  private static String transformRound(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() < 1 || args.size() > 2) {
      // More than 2 args or no args: Not a valid date ROUND, pass through
      return "ROUND" + getFunctionArguments(partCtx, b);
    }

    // Apply heuristic to determine if this is date or numeric ROUND
    boolean isDateRound = false;

    if (args.size() == 2) {
      // Check if 2nd argument looks like a date format string
      String secondArgText = args.get(1).getText(); // UNTRANSFORMED
      if (isDateFormatString(secondArgText)) {
        isDateRound = true;
      }
    } else if (args.size() == 1) {
      // Check if 1st argument looks like a date expression
      String firstArgText = args.get(0).getText(); // UNTRANSFORMED
      if (containsDateExpression(firstArgText)) {
        isDateRound = true;
      }
      // Otherwise: default to numeric ROUND (safer)
    }

    if (!isDateRound) {
      // Numeric ROUND: pass through unchanged
      return "ROUND" + getFunctionArguments(partCtx, b);
    }

    // Date ROUND: transform to CASE WHEN with DATE_TRUNC
    String dateExpr = transformArgument(args.get(0), b);
    String format = "DD"; // Default: round to day

    if (args.size() == 2) {
      // Extract format string
      String formatArg = transformArgument(args.get(1), b);
      // Remove quotes if present
      format = formatArg.replaceAll("^'|'$", "").toUpperCase();
    }

    return buildDateRoundExpression(dateExpr, format);
  }

  /**
   * Builds the CASE WHEN expression for date rounding.
   *
   * <p>Different formats have different thresholds:
   * <ul>
   *   <li>Day (DD): noon (12:00) - EXTRACT(HOUR FROM date) >= 12</li>
   *   <li>Month (MM): 16th day - EXTRACT(DAY FROM date) >= 16</li>
   *   <li>Year (YYYY): July 1st - EXTRACT(MONTH FROM date) >= 7</li>
   *   <li>Quarter (Q): 2nd month of quarter - complex logic</li>
   * </ul>
   */
  private static String buildDateRoundExpression(String dateExpr, String oracleFormat) {
    String pgFormat = mapOracleDateFormatToPostgres(oracleFormat);
    String extractField;
    int threshold;
    String intervalUnit;

    // Determine threshold based on format
    switch (pgFormat) {
      case "day":
        extractField = "HOUR";
        threshold = 12;
        intervalUnit = "day";
        break;
      case "month":
        extractField = "DAY";
        threshold = 16;
        intervalUnit = "month";
        break;
      case "year":
        extractField = "MONTH";
        threshold = 7; // July
        intervalUnit = "year";
        break;
      case "quarter":
        extractField = "MONTH";
        threshold = 2; // 2nd month of quarter (approximate)
        intervalUnit = "quarter";
        break;
      case "hour":
        extractField = "MINUTE";
        threshold = 30;
        intervalUnit = "hour";
        break;
      case "minute":
        extractField = "SECOND";
        threshold = 30;
        intervalUnit = "minute";
        break;
      default:
        // Unknown format: default to day rounding
        extractField = "HOUR";
        threshold = 12;
        intervalUnit = "day";
        break;
    }

    // Build: CASE WHEN EXTRACT(field FROM date) >= threshold
    //             THEN DATE_TRUNC('unit', date) + INTERVAL '1 unit'
    //             ELSE DATE_TRUNC('unit', date)
    //        END::DATE
    return "CASE " +
           "WHEN EXTRACT( " + extractField + " FROM " + dateExpr + " ) >= " + threshold + " " +
           "THEN DATE_TRUNC( '" + pgFormat + "' , " + dateExpr + " ) + INTERVAL '1 " + intervalUnit + "' " +
           "ELSE DATE_TRUNC( '" + pgFormat + "' , " + dateExpr + " ) " +
           "END::DATE";
  }

  /**
   * Maps Oracle date format strings to PostgreSQL DATE_TRUNC fields.
   */
  private static String mapOracleDateFormatToPostgres(String oracleFormat) {
    switch (oracleFormat) {
      case "DD":
      case "DDD":
      case "J":
        return "day";
      case "MONTH":
      case "MM":
      case "MON":
        return "month";
      case "YEAR":
      case "YYYY":
      case "YY":
      case "RRRR":
      case "RR":
        return "year";
      case "Q":
        return "quarter";
      case "HH":
      case "HH12":
      case "HH24":
        return "hour";
      case "MI":
        return "minute";
      case "SS":
        return "second";
      default:
        // Unknown format - use day as default
        return "day";
    }
  }

  // ==================== Helper Methods (delegate to VisitGeneralElement) ====================

  /**
   * Extracts arguments from a function_argument list.
   */
  private static List<PlSqlParser.ArgumentContext> extractFunctionArguments(
      PlSqlParser.General_element_partContext partCtx) {

    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      return new ArrayList<>();
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null) {
      return new ArrayList<>();
    }

    return arguments;
  }

  /**
   * Transforms a single argument (an expression or named parameter).
   */
  private static String transformArgument(
      PlSqlParser.ArgumentContext argCtx,
      PostgresCodeBuilder b) {

    // argument: (id_expression '=' '>')? expression
    if (argCtx.expression() != null) {
      return b.visit(argCtx.expression());
    }

    // Fallback: just get the text
    return argCtx.getText();
  }

  /**
   * Extracts and transforms function arguments into a formatted string.
   */
  private static String getFunctionArguments(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      return "( )";
    }

    // There should be exactly one function_argument context (which contains all arguments)
    PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);

    // function_argument: '(' (argument (',' argument)*)? ')' keep_clause?
    // Get all argument contexts
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.isEmpty()) {
      return "( )";
    }

    // Transform each argument
    List<String> transformedArgs = new ArrayList<>();
    for (PlSqlParser.ArgumentContext arg : arguments) {
      transformedArgs.add(transformArgument(arg, b));
    }

    return "( " + String.join(" , ", transformedArgs) + " )";
  }
}
