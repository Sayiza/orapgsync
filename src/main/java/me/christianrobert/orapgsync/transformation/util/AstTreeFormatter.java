package me.christianrobert.orapgsync.transformation.util;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Formats ANTLR parse trees into human-readable, indented text representation.
 *
 * <p>Useful for debugging and understanding how SQL is parsed by the grammar.</p>
 *
 * <p>Example output:</p>
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
    if (tree == null) {
      return "(null tree)";
    }
    StringBuilder sb = new StringBuilder();
    formatNode(tree, 0, sb);
    return sb.toString();
  }

  /**
   * Recursively formats a parse tree node.
   *
   * @param tree Current node
   * @param depth Indentation depth
   * @param sb StringBuilder to append to
   */
  private static void formatNode(ParseTree tree, int depth, StringBuilder sb) {
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

      sb.append("\n");

      // Recurse to children
      for (int i = 0; i < ctx.getChildCount(); i++) {
        formatNode(ctx.getChild(i), depth + 1, sb);
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
}
