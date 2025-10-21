package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms Oracle string functions to PostgreSQL equivalents.
 *
 * <p>Handles transformations for:
 * <ul>
 *   <li>INSTR(str, substr[, pos[, occ]]) → POSITION(substr IN str) or more complex</li>
 *   <li>LPAD(str, len[, pad]) → LPAD(str, len[, pad]) (pass-through)</li>
 *   <li>RPAD(str, len[, pad]) → RPAD(str, len[, pad]) (pass-through)</li>
 *   <li>TRANSLATE(str, from, to) → TRANSLATE(str, from, to) (pass-through)</li>
 *   <li>SUBSTR(str, pos[, len]) → SUBSTRING(str FROM pos [FOR len]) (future)</li>
 *   <li>TRIM(...) → TRIM(...) with syntax adjustments (future)</li>
 * </ul>
 */
public class StringFunctionTransformer {

  /**
   * Main entry point for string function transformations.
   *
   * @param functionName Function name (INSTR, LPAD, RPAD, TRANSLATE, etc.)
   * @param partCtx Function call context from ANTLR
   * @param b PostgreSQL code builder for recursive transformations
   * @return Transformed PostgreSQL SQL
   */
  public static String transform(
      String functionName,
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    switch (functionName.toUpperCase()) {
      case "INSTR":
        return transformInstr(partCtx, b);
      case "LPAD":
      case "RPAD":
      case "TRANSLATE":
        // These functions have identical syntax in Oracle and PostgreSQL
        // Pass through unchanged with transformed arguments
        return transformPassThrough(functionName, partCtx, b);
      default:
        throw new TransformationException("Unsupported string function: " + functionName);
    }
  }

  /**
   * Transforms Oracle INSTR to PostgreSQL POSITION or more complex expression.
   *
   * <p>Oracle INSTR syntax:
   * <pre>
   * INSTR(string, substring [, position [, occurrence]])
   * </pre>
   *
   * <p>Transformation strategy:
   * <ul>
   *   <li><b>2 args:</b> INSTR(str, substr) → POSITION(substr IN str)</li>
   *   <li><b>3 args:</b> INSTR(str, substr, pos) → CASE WHEN with SUBSTRING + POSITION + offset</li>
   *   <li><b>4 args:</b> INSTR(str, substr, pos, occ) → Custom function call (complex)</li>
   * </ul>
   *
   * <h3>Examples:</h3>
   * <pre>
   * -- Simple (2 args)
   * INSTR(email, '@') → POSITION('@' IN email)
   *
   * -- With starting position (3 args)
   * INSTR(email, '.', 5) →
   *   CASE WHEN 5 > 0 AND 5 <= LENGTH(email)
   *        THEN POSITION('.' IN SUBSTRING(email FROM 5)) + 5 - 1
   *        ELSE 0
   *   END
   *
   * -- With occurrence (4 args) - requires custom function
   * INSTR(email, '.', 1, 2) → instr_with_occurrence(email, '.', 1, 2)
   * </pre>
   */
  private static String transformInstr(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() < 2 || args.size() > 4) {
      throw new TransformationException(
          "INSTR requires 2-4 arguments (string, substring[, position[, occurrence]]), found: " + args.size());
    }

    String stringExpr = transformArgument(args.get(0), b);
    String substringExpr = transformArgument(args.get(1), b);

    if (args.size() == 2) {
      // Simple case: INSTR(str, substr) → POSITION(substr IN str)
      return "POSITION( " + substringExpr + " IN " + stringExpr + " )";
    }

    if (args.size() == 3) {
      // With starting position: INSTR(str, substr, pos)
      String positionExpr = transformArgument(args.get(2), b);
      return buildInstrWithPosition(stringExpr, substringExpr, positionExpr);
    }

    // 4 arguments: With occurrence
    // This is complex and requires either a custom function or complex regex
    // For now, call a custom PostgreSQL function that should be created
    String positionExpr = transformArgument(args.get(2), b);
    String occurrenceExpr = transformArgument(args.get(3), b);

    return "instr_with_occurrence( " + stringExpr + " , " + substringExpr + " , " +
           positionExpr + " , " + occurrenceExpr + " )";
  }

  /**
   * Builds the CASE WHEN expression for INSTR with starting position.
   *
   * <p>Oracle INSTR with position parameter searches starting from that position.
   * PostgreSQL doesn't have a direct equivalent, so we use:
   * <pre>
   * CASE
   *   WHEN position > 0 AND position <= LENGTH(string)
   *   THEN POSITION(substring IN SUBSTRING(string FROM position)) + position - 1
   *   ELSE 0
   * END
   * </pre>
   *
   * <p>The offset adjustment (+ position - 1) is needed because:
   * <ul>
   *   <li>SUBSTRING(string FROM position) returns substring starting at position</li>
   *   <li>POSITION returns position within that substring (1-based)</li>
   *   <li>We need to convert back to position in original string</li>
   * </ul>
   *
   * @param stringExpr The string to search in
   * @param substringExpr The substring to find
   * @param positionExpr The starting position
   * @return PostgreSQL CASE WHEN expression
   */
  private static String buildInstrWithPosition(String stringExpr, String substringExpr, String positionExpr) {
    // CASE WHEN position > 0 AND position <= LENGTH(string)
    //      THEN POSITION(substring IN SUBSTRING(string FROM position)) + position - 1
    //      ELSE 0
    // END

    return "CASE " +
           "WHEN " + positionExpr + " > 0 AND " + positionExpr + " <= LENGTH( " + stringExpr + " ) " +
           "THEN POSITION( " + substringExpr + " IN SUBSTRING( " + stringExpr + " FROM " + positionExpr + " ) ) + " +
           "( " + positionExpr + " - 1 ) " +
           "ELSE 0 " +
           "END";
  }

  /**
   * Pass-through transformation for functions with identical Oracle/PostgreSQL syntax.
   *
   * <p>Handles functions where the syntax is identical between Oracle and PostgreSQL:
   * <ul>
   *   <li><b>LPAD(str, len[, pad]):</b> Left-pad string to specified length</li>
   *   <li><b>RPAD(str, len[, pad]):</b> Right-pad string to specified length</li>
   *   <li><b>TRANSLATE(str, from, to):</b> Character-by-character replacement</li>
   * </ul>
   *
   * <p>The function name and structure remain the same; only the arguments
   * are recursively transformed.
   *
   * @param functionName Function name (LPAD, RPAD, TRANSLATE)
   * @param partCtx Function call context from ANTLR
   * @param b PostgreSQL code builder for recursive transformations
   * @return PostgreSQL function call with transformed arguments
   */
  private static String transformPassThrough(
      String functionName,
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.isEmpty()) {
      throw new TransformationException(
          functionName + " requires at least 1 argument, found: 0");
    }

    // Transform all arguments
    List<String> transformedArgs = new ArrayList<>();
    for (PlSqlParser.ArgumentContext arg : args) {
      transformedArgs.add(transformArgument(arg, b));
    }

    // Build function call: FUNCTIONNAME( arg1 , arg2 , ... )
    return functionName.toUpperCase() + "( " + String.join(" , ", transformedArgs) + " )";
  }

  // ==================== Helper Methods ====================

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
}
