package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Transforms Oracle string functions to PostgreSQL equivalents.
 *
 * <p>Handles transformations for:
 * <ul>
 *   <li>INSTR(str, substr[, pos[, occ]]) → POSITION(substr IN str) or more complex</li>
 *   <li>LPAD(str, len[, pad]) → LPAD(str, len[, pad]) (pass-through)</li>
 *   <li>RPAD(str, len[, pad]) → RPAD(str, len[, pad]) (pass-through)</li>
 *   <li>TRANSLATE(str, from, to) → TRANSLATE(str, from, to) (pass-through)</li>
 *   <li>REGEXP_REPLACE / REGEXP_SUBSTR / REGEXP_INSTR / REGEXP_COUNT / REGEXP_LIKE →
 *       the same-named PostgreSQL function, mapped positionally — see
 *       {@link #transformRegexpFunction}</li>
 *   <li>SUBSTR(str, pos[, len]) → SUBSTRING(str FROM pos [FOR len]) (future)</li>
 *   <li>TRIM(...) → TRIM(...) with syntax adjustments (future)</li>
 * </ul>
 */
public class StringFunctionTransformer {

  /**
   * Main entry point for string function transformations.
   *
   * @param functionName Function name (INSTR, LPAD, RPAD, TRANSLATE, REGEXP_REPLACE, etc.)
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
      // The Oracle REGEXP family maps positionally onto PostgreSQL 15+ equivalents.
      // The array is the Oracle default for each positional integer parameter.
      case "REGEXP_REPLACE":
        return transformRegexpFunction("REGEXP_REPLACE", 3, new String[]{"1", "0"}, false, partCtx, b);
      case "REGEXP_SUBSTR":
        return transformRegexpFunction("REGEXP_SUBSTR", 2, new String[]{"1", "1"}, true, partCtx, b);
      case "REGEXP_INSTR":
        return transformRegexpFunction("REGEXP_INSTR", 2, new String[]{"1", "1", "0"}, true, partCtx, b);
      case "REGEXP_COUNT":
        return transformRegexpFunction("REGEXP_COUNT", 2, new String[]{"1"}, false, partCtx, b);
      case "REGEXP_LIKE":
        return transformRegexpFunction("REGEXP_LIKE", 2, new String[]{}, false, partCtx, b);
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
    String positionExpr = transformArgument(args.get(2), b);
    String occurrenceExpr = transformArgument(args.get(3), b);

    // Check for the common case: position=1, occurrence=1
    // This is equivalent to the simple 2-arg form
    String positionText = args.get(2).getText().trim();
    String occurrenceText = args.get(3).getText().trim();

    if ("1".equals(positionText) && "1".equals(occurrenceText)) {
      // INSTR(str, substr, 1, 1) → POSITION(substr IN str)
      return "POSITION( " + substringExpr + " IN " + stringExpr + " )";
    }

    // Complex case with non-default values
    // This requires either a custom function or complex regex
    // For now, call a custom PostgreSQL function that should be created
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

  /**
   * Transforms an Oracle REGEXP function to its PostgreSQL equivalent.
   *
   * <p><b>PostgreSQL 15 added {@code regexp_count()}, {@code regexp_instr()},
   * {@code regexp_like()} and {@code regexp_substr()} with deliberately Oracle-compatible
   * signatures,</b> and extended {@code regexp_replace()} with the Oracle-shaped
   * {@code (string, pattern, replacement, start, N, flags)} overload. Every member of the family
   * therefore maps argument-for-argument, and none of Oracle's optional parameters needs to be
   * rejected:
   *
   * <pre>
   * REGEXP_REPLACE(source, pattern, replace [, position [, occurrence [, match_param]]])
   * REGEXP_SUBSTR (source, pattern [, position [, occurrence [, match_param [, subexpr]]]])
   * REGEXP_INSTR  (source, pattern [, position [, occurrence [, return_opt [, match_param [, subexpr]]]]])
   * REGEXP_COUNT  (source, pattern [, position [, match_param]])
   * REGEXP_LIKE   (source, pattern [, match_param])
   * </pre>
   *
   * <p>They share one shape, which is what this method encodes: some leading string arguments,
   * then a run of positional integer parameters each with an Oracle default, then the flags, then
   * an optional trailing {@code subexpr}.
   *
   * <p>Two adjustments are applied to every member:
   *
   * <ul>
   *   <li><b>Flags are mapped, never passed through</b> — see {@link OracleRegexFlags}. Oracle and
   *       PostgreSQL disagree on the default newline handling, so the flags argument is
   *       <em>always</em> emitted. That in turn forces the preceding optional arguments to be
   *       emitted at their Oracle defaults, which is what {@code integerDefaults} supplies.</li>
   *   <li><b>Integer arguments are cast</b> — see {@link #asIntegerArgument(String)}.</li>
   * </ul>
   *
   * @param functionName    the PostgreSQL function to emit; the names happen to match Oracle's
   * @param textArgCount    number of leading string arguments (2, or 3 for REGEXP_REPLACE)
   * @param integerDefaults Oracle's default for each positional integer parameter, in order
   * @param allowsSubexpr   whether a trailing {@code subexpr} argument is accepted
   */
  private static String transformRegexpFunction(
      String functionName,
      int textArgCount,
      String[] integerDefaults,
      boolean allowsSubexpr,
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    int flagsIndex = textArgCount + integerDefaults.length;
    int maxArgs = flagsIndex + 1 + (allowsSubexpr ? 1 : 0);

    if (args.size() < textArgCount || args.size() > maxArgs) {
      throw new TransformationException(
          functionName + " requires " + textArgCount + "-" + maxArgs + " arguments, found: "
          + args.size());
    }

    StringBuilder sql = new StringBuilder(functionName).append("( ");

    for (int i = 0; i < textArgCount; i++) {
      if (i > 0) {
        sql.append(" , ");
      }
      sql.append(transformArgument(args.get(i), b));
    }

    for (int i = 0; i < integerDefaults.length; i++) {
      int argIndex = textArgCount + i;
      sql.append(" , ").append(argIndex < args.size()
          ? asIntegerArgument(transformArgument(args.get(argIndex), b))
          : integerDefaults[i]);
    }

    String oracleFlags = flagsIndex < args.size()
        ? extractMatchParameter(args.get(flagsIndex), functionName)
        : null;
    sql.append(" , '").append(OracleRegexFlags.toPostgres(oracleFlags)).append("'");

    if (allowsSubexpr && args.size() > flagsIndex + 1) {
      sql.append(" , ").append(asIntegerArgument(transformArgument(args.get(flagsIndex + 1), b)));
    }

    return sql.append(" )").toString();
  }

  // ==================== Helper Methods ====================

  /** Matches an argument that is already an integer literal and so needs no cast. */
  private static final Pattern INTEGER_LITERAL = Pattern.compile("\\d+");

  /**
   * Renders an already-transformed expression as an {@code integer} argument.
   *
   * <p>PostgreSQL picks a function overload by exact argument type and offers no implicit
   * {@code numeric} to {@code integer} cast, while Oracle's positional regexp parameters are
   * plain numbers. An unadorned column or variable reference would therefore fail overload
   * resolution, so everything that is not already an integer literal gets an explicit cast.
   */
  private static String asIntegerArgument(String expr) {
    String trimmed = expr.trim();
    if (INTEGER_LITERAL.matcher(trimmed).matches()) {
      return trimmed;
    }
    return "( " + trimmed + " )::integer";
  }

  /**
   * Reads an Oracle {@code match_parameter} argument as raw flag characters.
   *
   * <p>The flags have to be mapped rather than passed through ({@link OracleRegexFlags}), and a
   * mapping can only be done at transformation time, so a non-literal argument cannot be
   * supported. Passing such an expression through unmapped would silently change the matching
   * semantics, so it is rejected instead.
   */
  private static String extractMatchParameter(
      PlSqlParser.ArgumentContext argCtx, String functionName) {

    String text = argCtx.getText().trim();
    if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
      return text.substring(1, text.length() - 1);
    }

    throw new TransformationException(
        functionName + " match_parameter must be a string literal so it can be mapped to "
        + "PostgreSQL regex flags (the two engines disagree on default newline handling), found: "
        + text);
  }

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
