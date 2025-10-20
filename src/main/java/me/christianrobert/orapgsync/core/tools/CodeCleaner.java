package me.christianrobert.orapgsync.core.tools;

public class CodeCleaner {

  public static String removeComments(String plsqlCode) {
    StringBuilder result = new StringBuilder();
    boolean inSingleQuote = false;  // Tracks if we're inside a single-quoted string
    boolean inSingleLineComment = false;  // Tracks single-line comment (--)
    boolean inMultiLineComment = false;   // Tracks multi-line comment (/* */)

    for (int i = 0; i < plsqlCode.length(); i++) {
      char currentChar = plsqlCode.charAt(i);
      char nextChar = (i + 1 < plsqlCode.length()) ? plsqlCode.charAt(i + 1) : '\0';

      if (inSingleLineComment) {
        // Skip until newline
        if (currentChar == '\n') {
          inSingleLineComment = false;
          result.append(currentChar);
        }
        continue;
      }

      if (inMultiLineComment) {
        // Skip until end of multi-line comment
        if (currentChar == '*' && nextChar == '/') {
          inMultiLineComment = false;
          i++; // Skip the '/'
        }
        continue;
      }

      if (inSingleQuote) {
        // Inside a string, preserve everything until closing quote
        result.append(currentChar);
        if (currentChar == '\'') {
          // Handle escaped quotes
          if (nextChar == '\'') {
            result.append(nextChar);
            i++;
          } else {
            inSingleQuote = false;
          }
        }
        continue;
      }

      // Not in a comment or string
      if (currentChar == '-' && nextChar == '-') {
        inSingleLineComment = true;
        i++; // Skip the second '-'
        continue;
      }

      if (currentChar == '/' && nextChar == '*') {
        inMultiLineComment = true;
        i++; // Skip the '*'
        continue;
      }

      if (currentChar == '\'') {
        inSingleQuote = true;
        result.append(currentChar);
        continue;
      }

      // Append non-comment character
      result.append(currentChar);
    }

    return result.toString();
  }
}
