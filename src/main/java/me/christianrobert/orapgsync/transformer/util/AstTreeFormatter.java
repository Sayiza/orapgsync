package me.christianrobert.orapgsync.transformer.util;

import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.Map;

/**
 * Formats ANTLR parse trees into human-readable, indented text representation.
 *
 * <p>Useful for debugging and understanding how SQL is parsed by the grammar.</p>
 *
 * <p>Example output (without type information):</p>
 * <pre>
 * query_block
 *   SELECT
 *   selected_list
 *     select_list_elements
 *       "emp_id"
 *       ,
 *       "ename"
 *   FROM
 *   table_ref_list
 *     tableview_name
 *       "employees"
 * </pre>
 *
 * <p>Example output (with type information):</p>
 * <pre>
 * query_block
 *   SELECT
 *   selected_list [TYPE: UNKNOWN]
 *     select_list_elements [TYPE: NUMERIC, 0:5]
 *       constant [TYPE: NUMERIC, 0:1] "42"
 *       +
 *       constant [TYPE: NUMERIC, 4:5] "50"
 * </pre>
 */
public class AstTreeFormatter {

  private static final String INDENT = "  ";
  private static final int MAX_TEXT_LENGTH = 50;

  /**
   * Formats a parse tree into human-readable text.
   *
   * @param tree Root of the parse tree
   * @return Formatted string representation
   */
  public static String format(ParseTree tree) {
    return format(tree, null);
  }

  /**
   * Formats a parse tree into human-readable text with optional type information.
   *
   * <p>If a type cache is provided, type information will be appended to each node
   * in the format: [TYPE: category, startPos:stopPos]</p>
   *
   * @param tree Root of the parse tree
   * @param typeCache Optional type cache (from TypeAnalysisVisitor), may be null
   * @return Formatted string representation with type annotations
   */
  public static String format(ParseTree tree, Map<String, TypeInfo> typeCache) {
    if (tree == null) {
      return "(null tree)";
    }
    StringBuilder sb = new StringBuilder();
    formatNode(tree, 0, sb, typeCache);
    return sb.toString();
  }

  /**
   * Recursively formats a parse tree node.
   *
   * @param tree Current node
   * @param depth Indentation depth
   * @param sb StringBuilder to append to
   * @param typeCache Optional type cache for displaying type information
   */
  private static void formatNode(ParseTree tree, int depth, StringBuilder sb, Map<String, TypeInfo> typeCache) {
    // Add indentation
    for (int i = 0; i < depth; i++) {
      sb.append(INDENT);
    }

    if (tree instanceof TerminalNode) {
      // Terminal node: show token text
      TerminalNode terminal = (TerminalNode) tree;
      String text = terminal.getText();

      // Escape and truncate if needed
      String displayText = escapeAndTruncate(text);

      // Show token type name if available
      String tokenName = terminal.getSymbol().getType() >= 0
          ? getTokenName(terminal)
          : "EOF";

      sb.append("\"").append(displayText).append("\"");
      if (!tokenName.isEmpty()) {
        sb.append(" (").append(tokenName).append(")");
      }
      sb.append("\n");

    } else if (tree instanceof ParserRuleContext) {
      // Parser rule context: show rule name and recurse
      ParserRuleContext ctx = (ParserRuleContext) tree;
      String ruleName = getRuleName(ctx);

      sb.append(ruleName);

      // Show text snippet for small nodes (helpful for identification)
      if (ctx.getChildCount() <= 2) {
        String text = ctx.getText();
        if (text.length() <= 30) {
          sb.append(" [").append(escapeAndTruncate(text)).append("]");
        }
      }

      // Show type information if available
      if (typeCache != null) {
        String key = nodeKey(ctx);
        TypeInfo type = typeCache.get(key);
        if (type != null) {
          sb.append(" [TYPE: ").append(type.getCategory());
          // Include token positions for debugging
          if (ctx.start != null && ctx.stop != null) {
            sb.append(", ").append(ctx.start.getStartIndex())
              .append(":").append(ctx.stop.getStopIndex());
          }
          sb.append("]");
        }
      }

      sb.append("\n");

      // Recurse to children
      for (int i = 0; i < ctx.getChildCount(); i++) {
        formatNode(ctx.getChild(i), depth + 1, sb, typeCache);
      }

    } else {
      // Unknown node type
      sb.append("(unknown: ").append(tree.getClass().getSimpleName()).append(")\n");
    }
  }

  /**
   * Gets the rule name from a parser rule context.
   */
  private static String getRuleName(ParserRuleContext ctx) {
    String className = ctx.getClass().getSimpleName();

    // Remove "Context" suffix if present
    if (className.endsWith("Context")) {
      className = className.substring(0, className.length() - "Context".length());
    }

    return className;
  }

  /**
   * Gets the token name from a terminal node.
   */
  private static String getTokenName(TerminalNode terminal) {
    // This would require access to the parser's token names array
    // For now, return empty string
    // Could be enhanced by passing Parser instance to format() method
    return "";
  }

  /**
   * Escapes and truncates text for display.
   */
  private static String escapeAndTruncate(String text) {
    if (text == null) {
      return "";
    }

    // Escape special characters
    text = text.replace("\n", "\\n")
               .replace("\r", "\\r")
               .replace("\t", "\\t");

    // Truncate if too long
    if (text.length() > MAX_TEXT_LENGTH) {
      text = text.substring(0, MAX_TEXT_LENGTH) + "...";
    }

    return text;
  }

  /**
   * Generates a unique cache key for an AST node using token position.
   *
   * <p>This MUST match the key generation in TypeAnalysisVisitor and FullTypeEvaluator
   * for type lookups to work correctly.</p>
   *
   * @param ctx Parse tree node
   * @return Unique key string (e.g., "125:150" for tokens from position 125 to 150)
   */
  private static String nodeKey(ParserRuleContext ctx) {
    if (ctx == null || ctx.start == null || ctx.stop == null) {
      // Fallback for nodes without token info (shouldn't happen in normal parsing)
      return "unknown:" + System.identityHashCode(ctx);
    }
    return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
  }
}
