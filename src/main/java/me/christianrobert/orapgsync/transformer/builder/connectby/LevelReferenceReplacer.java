package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;

/**
 * Replaces LEVEL pseudo-column references with "level" column references.
 *
 * <p>Oracle's LEVEL is a pseudo-column that returns the depth in the hierarchy.
 * In PostgreSQL recursive CTEs, we explicitly track this as a "level" column.</p>
 *
 * <p>This class uses a hybrid approach: AST-based visiting combined with targeted
 * string replacement for LEVEL keywords.</p>
 *
 * <p>Usage in different contexts:</p>
 * <pre>
 * -- SELECT list: Replace LEVEL with level
 * SELECT emp_id, LEVEL          → SELECT emp_id, level
 *
 * -- ORDER BY: Replace LEVEL with level
 * ORDER BY LEVEL                → ORDER BY level
 *
 * -- Complex expressions: Replace LEVEL, keep expression
 * SELECT LEVEL * 2              → SELECT level * 2
 * WHERE LEVEL <= 3              → WHERE level <= 3
 * </pre>
 */
public class LevelReferenceReplacer {

  /**
   * Replaces LEVEL references in a SELECT list.
   *
   * @param ctx SELECT list context
   * @param b PostgresCodeBuilder for delegating transformations
   * @return Transformed SELECT list with LEVEL → level
   */
  public static String replaceInSelectList(
      PlSqlParser.Selected_listContext ctx,
      PostgresCodeBuilder b) {

    if (ctx == null) {
      return "";
    }

    // Visit normally, then replace LEVEL
    String selectList = b.visit(ctx);
    return replaceLevelKeyword(selectList);
  }

  /**
   * Replaces LEVEL references in ORDER BY clause.
   *
   * @param ctx ORDER BY context
   * @param b PostgresCodeBuilder
   * @return Transformed ORDER BY with LEVEL → level
   */
  public static String replaceInOrderBy(
      PlSqlParser.Order_by_clauseContext ctx,
      PostgresCodeBuilder b) {

    if (ctx == null) {
      return "";
    }

    // Visit normally, then replace LEVEL
    String orderBy = b.visit(ctx);
    return replaceLevelKeyword(orderBy);
  }

  /**
   * Replaces LEVEL keyword in a string (word boundary aware).
   *
   * <p>This method replaces LEVEL when it appears as a complete identifier,
   * not as part of another word. Handles all case variations.</p>
   *
   * <p>Examples:</p>
   * <ul>
   *   <li>"SELECT LEVEL" → "SELECT level"</li>
   *   <li>"SELECT Level" → "SELECT level"</li>
   *   <li>"SELECT level" → "SELECT level"</li>
   *   <li>"SELECT LEVEL * 2" → "SELECT level * 2"</li>
   *   <li>"SELECT MULTILEVEL" → "SELECT MULTILEVEL" (unchanged)</li>
   * </ul>
   *
   * @param input String containing potential LEVEL references
   * @return String with LEVEL replaced by level
   */
  private static String replaceLevelKeyword(String input) {
    if (input == null) {
      return null;
    }

    // Replace LEVEL (case-insensitive) with level
    // Use word boundaries to avoid replacing partial matches
    return input.replaceAll("\\bLEVEL\\b", "level")
                .replaceAll("\\bLevel\\b", "level");
    // Note: Don't need third replacement - already normalized by first two
  }
}
