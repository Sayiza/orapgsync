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
 *   <li>REGEXP_REPLACE(str, pattern, replacement[, position[, occurrence[, flags]]]) → REGEXP_REPLACE(str, pattern, replacement, 'g')</li>
 *   <li>REGEXP_SUBSTR(str, pattern[, position[, occurrence[, flags[, subexpr]]]]) → (REGEXP_MATCH(str, pattern))[1]</li>
 *   <li>REGEXP_INSTR(str, pattern[, position[, occurrence[, return_opt[, flags[, subexpr]]]]]) → REGEXP_INSTR(...) (native since PostgreSQL 15)</li>
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
      case "REGEXP_REPLACE":
        return transformRegexpReplace(partCtx, b);
      case "REGEXP_SUBSTR":
        return transformRegexpSubstr(partCtx, b);
      case "REGEXP_INSTR":
        return transformRegexpInstr(partCtx, b);
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
   * Transforms Oracle REGEXP_REPLACE to PostgreSQL REGEXP_REPLACE with 'g' flag.
   *
   * <p>Oracle REGEXP_REPLACE syntax:
   * <pre>
   * REGEXP_REPLACE(source_string, pattern, replace_string
   *                [, position [, occurrence [, match_parameter]]])
   * </pre>
   *
   * <p>PostgreSQL REGEXP_REPLACE syntax:
   * <pre>
   * REGEXP_REPLACE(source, pattern, replacement [, flags])
   * </pre>
   *
   * <p><b>Key Difference:</b> Oracle replaces all occurrences by default (when occurrence=0, which is default).
   * PostgreSQL only replaces the first occurrence unless 'g' (global) flag is specified.
   *
   * <p><b>Transformation Strategy:</b>
   * <ul>
   *   <li>2-3 args: Simple transformation, add 'g' flag for global replacement</li>
   *   <li>4+ args: Extract position/occurrence parameters and determine if 'g' flag is needed</li>
   *   <li>If occurrence > 1: Cannot be directly translated (would need custom function)</li>
   * </ul>
   *
   * <h3>Examples:</h3>
   * <pre>
   * -- Simple (3 args) - Global replace
   * REGEXP_REPLACE(text, '[0-9]', 'X') → REGEXP_REPLACE(text, '[0-9]', 'X', 'g')
   *
   * -- With match parameter (6 args)
   * REGEXP_REPLACE(text, 'a', 'A', 1, 0, 'i') → REGEXP_REPLACE(text, 'a', 'A', 'gi')
   * </pre>
   */
  private static String transformRegexpReplace(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() < 3 || args.size() > 6) {
      throw new TransformationException(
          "REGEXP_REPLACE requires 3-6 arguments (source, pattern, replacement[, position[, occurrence[, flags]]]), found: " + args.size());
    }

    String sourceExpr = transformArgument(args.get(0), b);
    String patternExpr = transformArgument(args.get(1), b);
    String replacementExpr = transformArgument(args.get(2), b);

    // Determine flags
    String flags = "g"; // Default: global replacement

    if (args.size() >= 6) {
      // Oracle match_parameter (flags) - 6th argument
      // Extract flags from Oracle format (e.g., 'i' for case-insensitive)
      String oracleFlags = args.get(5).getText().trim();
      // Remove quotes if present
      if (oracleFlags.startsWith("'") && oracleFlags.endsWith("'")) {
        oracleFlags = oracleFlags.substring(1, oracleFlags.length() - 1);
      }
      // Add 'g' if not already present (Oracle default is global)
      if (!oracleFlags.contains("g")) {
        flags = "g" + oracleFlags;
      } else {
        flags = oracleFlags;
      }
    }

    if (args.size() >= 5) {
      // Oracle occurrence parameter - 5th argument
      String occurrenceText = args.get(4).getText().trim();
      // If occurrence is 1, only replace first match (don't use 'g')
      if ("1".equals(occurrenceText)) {
        flags = flags.replace("g", "");
        if (flags.isEmpty()) {
          // No flags, but PostgreSQL REGEXP_REPLACE requires at least empty string if we want to be explicit
          // However, we can just omit the flags parameter
          return "REGEXP_REPLACE( " + sourceExpr + " , " + patternExpr + " , " + replacementExpr + " )";
        }
      } else if (!"0".equals(occurrenceText)) {
        // occurrence > 1: This is complex and not directly supported
        // Would need a custom function or loop logic
        throw new TransformationException(
            "REGEXP_REPLACE with occurrence > 1 is not supported. " +
            "Occurrence parameter: " + occurrenceText + ". " +
            "Consider creating a custom PostgreSQL function for this use case.");
      }
    }

    if (args.size() >= 4) {
      // Oracle position parameter - 4th argument
      // PostgreSQL doesn't support starting position directly
      // Would need SUBSTRING workaround, but this is rare
      String positionText = args.get(3).getText().trim();
      if (!"1".equals(positionText)) {
        throw new TransformationException(
            "REGEXP_REPLACE with position != 1 is not supported. " +
            "Position parameter: " + positionText + ". " +
            "Consider using SUBSTRING to extract the relevant portion first.");
      }
    }

    // Build PostgreSQL REGEXP_REPLACE with flags
    if (!flags.isEmpty()) {
      return "REGEXP_REPLACE( " + sourceExpr + " , " + patternExpr + " , " + replacementExpr + " , '" + flags + "' )";
    } else {
      return "REGEXP_REPLACE( " + sourceExpr + " , " + patternExpr + " , " + replacementExpr + " )";
    }
  }

  /**
   * Transforms Oracle REGEXP_SUBSTR to PostgreSQL (REGEXP_MATCH())[1].
   *
   * <p>Oracle REGEXP_SUBSTR syntax:
   * <pre>
   * REGEXP_SUBSTR(source_string, pattern
   *               [, position [, occurrence [, match_parameter [, subexpr]]]])
   * </pre>
   *
   * <p>PostgreSQL REGEXP_MATCH syntax:
   * <pre>
   * REGEXP_MATCH(string, pattern [, flags])
   * </pre>
   *
   * <p><b>Key Difference:</b> Oracle returns a string, PostgreSQL returns an array.
   * We extract the first element with [1].
   *
   * <p><b>Transformation Strategy:</b>
   * <ul>
   *   <li>2 args: Simple transformation to (REGEXP_MATCH(str, pattern))[1]</li>
   *   <li>3+ args: Extract position/occurrence parameters</li>
   *   <li>If position > 1 or occurrence > 1: Complex, may require SUBSTRING or custom function</li>
   * </ul>
   *
   * <h3>Examples:</h3>
   * <pre>
   * -- Simple (2 args)
   * REGEXP_SUBSTR(email, '[^@]+') → (REGEXP_MATCH(email, '[^@]+'))[1]
   *
   * -- With flags (5 args)
   * REGEXP_SUBSTR(text, '[a-z]+', 1, 1, 'i') → (REGEXP_MATCH(text, '[a-z]+', 'i'))[1]
   * </pre>
   */
  private static String transformRegexpSubstr(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() < 2 || args.size() > 6) {
      throw new TransformationException(
          "REGEXP_SUBSTR requires 2-6 arguments (source, pattern[, position[, occurrence[, flags[, subexpr]]]]), found: " + args.size());
    }

    String sourceExpr = transformArgument(args.get(0), b);
    String patternExpr = transformArgument(args.get(1), b);

    // Determine flags
    String flags = "";

    if (args.size() >= 5) {
      // Oracle match_parameter (flags) - 5th argument
      String oracleFlags = args.get(4).getText().trim();
      // Remove quotes if present
      if (oracleFlags.startsWith("'") && oracleFlags.endsWith("'")) {
        flags = oracleFlags;  // Keep quotes for PostgreSQL
      } else {
        flags = "'" + oracleFlags + "'";
      }
    }

    if (args.size() >= 4) {
      // Oracle occurrence parameter - 4th argument
      String occurrenceText = args.get(3).getText().trim();
      if (!"1".equals(occurrenceText)) {
        throw new TransformationException(
            "REGEXP_SUBSTR with occurrence != 1 is not supported. " +
            "Occurrence parameter: " + occurrenceText + ". " +
            "Consider extracting multiple matches with REGEXP_MATCHES (note: returns a set).");
      }
    }

    if (args.size() >= 3) {
      // Oracle position parameter - 3rd argument
      String positionText = args.get(2).getText().trim();
      if (!"1".equals(positionText)) {
        throw new TransformationException(
            "REGEXP_SUBSTR with position != 1 is not supported. " +
            "Position parameter: " + positionText + ". " +
            "Consider using SUBSTRING to extract the relevant portion first.");
      }
    }

    // Build PostgreSQL (REGEXP_MATCH())[1]
    if (!flags.isEmpty()) {
      return "( REGEXP_MATCH( " + sourceExpr + " , " + patternExpr + " , " + flags + " ) )[1]";
    } else {
      return "( REGEXP_MATCH( " + sourceExpr + " , " + patternExpr + " ) )[1]";
    }
  }

  /**
   * Transforms Oracle REGEXP_INSTR to PostgreSQL REGEXP_INSTR.
   *
   * <p>Oracle REGEXP_INSTR syntax:
   * <pre>
   * REGEXP_INSTR(source_string, pattern
   *              [, position [, occurrence [, return_option [, match_parameter [, subexpr]]]]])
   * </pre>
   *
   * <p>PostgreSQL REGEXP_INSTR syntax (PostgreSQL 15 and later):
   * <pre>
   * REGEXP_INSTR(string, pattern
   *              [, start [, N [, endoption [, flags [, subexpr]]]]])
   * </pre>
   *
   * <p>The two signatures are argument-for-argument identical, so every parameter maps
   * positionally and none of Oracle's optional parameters need to be rejected. Only two
   * adjustments are required:
   *
   * <ul>
   *   <li><b>Flags,</b> because the engines disagree on the default newline handling — see
   *       {@link OracleRegexFlags}. The flags argument is therefore always emitted, which forces
   *       the preceding optional arguments to be emitted at their Oracle defaults.</li>
   *   <li><b>Integer casts,</b> because PostgreSQL resolves overloads by exact type and has no
   *       implicit numeric-to-integer cast. A transformed Oracle expression is typically
   *       {@code numeric}, which would fail with "function regexp_instr(text, text, numeric) does
   *       not exist".</li>
   * </ul>
   *
   * <h3>Examples:</h3>
   * <pre>
   * REGEXP_INSTR(email, '@')
   *   → REGEXP_INSTR( email , '@' , 1 , 1 , 0 , 'p' )
   *
   * REGEXP_INSTR(text, '[0-9]+', 5, 2, 1, 'i')
   *   → REGEXP_INSTR( text , '[0-9]+' , 5 , 2 , 1 , 'ip' )
   * </pre>
   */
  private static String transformRegexpInstr(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() < 2 || args.size() > 7) {
      throw new TransformationException(
          "REGEXP_INSTR requires 2-7 arguments (source, pattern[, position[, occurrence"
          + "[, return_option[, match_parameter[, subexpr]]]]]), found: " + args.size());
    }

    String sourceExpr = transformArgument(args.get(0), b);
    String patternExpr = transformArgument(args.get(1), b);

    // Oracle defaults: position 1, occurrence 1, return_option 0 (start of match).
    String positionExpr = args.size() >= 3 ? asIntegerArgument(transformArgument(args.get(2), b)) : "1";
    String occurrenceExpr = args.size() >= 4 ? asIntegerArgument(transformArgument(args.get(3), b)) : "1";
    String returnOptionExpr = args.size() >= 5 ? asIntegerArgument(transformArgument(args.get(4), b)) : "0";

    String flags = OracleRegexFlags.toPostgres(
        args.size() >= 6 ? extractMatchParameter(args.get(5), "REGEXP_INSTR") : null);

    StringBuilder sql = new StringBuilder("REGEXP_INSTR( ")
        .append(sourceExpr).append(" , ")
        .append(patternExpr).append(" , ")
        .append(positionExpr).append(" , ")
        .append(occurrenceExpr).append(" , ")
        .append(returnOptionExpr).append(" , ")
        .append("'").append(flags).append("'");

    if (args.size() >= 7) {
      sql.append(" , ").append(asIntegerArgument(transformArgument(args.get(6), b)));
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
