package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Maps an Oracle regular expression {@code match_param} to the equivalent PostgreSQL flags string.
 *
 * <p>Both engines accept a flags string, but they do not agree on what an <em>empty</em> one means,
 * so passing Oracle's value through unchanged is wrong in the default case — the most common case
 * of all. The disagreement is about newlines:
 *
 * <ul>
 *   <li><b>Oracle default:</b> {@code .} (and a negated bracket expression) does not match a
 *       newline; {@code ^}/{@code $} anchor to the whole string.</li>
 *   <li><b>PostgreSQL default:</b> {@code .} <em>does</em> match a newline; {@code ^}/{@code $}
 *       anchor to the whole string.</li>
 * </ul>
 *
 * <p>PostgreSQL spells Oracle's default as the {@code p} flag ("partial newline-sensitive"), so
 * every mapping below emits exactly one newline flag rather than relying on either default. The
 * two Oracle newline parameters are independent dimensions of one PostgreSQL flag:
 *
 * <table border="1">
 *   <caption>Newline flag mapping</caption>
 *   <tr><th>Oracle {@code n} (dot matches newline)</th>
 *       <th>Oracle {@code m} (per-line anchors)</th>
 *       <th>PostgreSQL</th></tr>
 *   <tr><td>no</td>  <td>no</td>  <td>{@code p}</td></tr>
 *   <tr><td>yes</td> <td>no</td>  <td><i>(none — PostgreSQL's default)</i></td></tr>
 *   <tr><td>no</td>  <td>yes</td> <td>{@code n}</td></tr>
 *   <tr><td>yes</td> <td>yes</td> <td>{@code w}</td></tr>
 * </table>
 *
 * <p>{@code i} (case-insensitive), {@code c} (case-sensitive) and {@code x} (ignore whitespace)
 * mean the same thing in both engines and are passed through in source order. Order matters
 * because both engines resolve contradictory values by letting the last one win, so preserving
 * it preserves the semantics of {@code 'ci'} versus {@code 'ic'}.
 *
 * <p>This class deliberately has no notion of quoting: it takes and returns the raw flag
 * characters, and the caller decides how to render them as a SQL literal.
 */
public final class OracleRegexFlags {

  private OracleRegexFlags() {
  }

  /**
   * Converts Oracle {@code match_param} flag characters to PostgreSQL flag characters.
   *
   * @param oracleMatchParam the raw Oracle flag characters without surrounding quotes; {@code null}
   *                         or empty means Oracle's default behaviour
   * @return the equivalent PostgreSQL flag characters, without surrounding quotes; never empty,
   *         because Oracle's default itself requires an explicit flag
   * @throws TransformationException if a character is not a valid Oracle match parameter, which
   *                                 Oracle itself rejects too
   */
  public static String toPostgres(String oracleMatchParam) {
    StringBuilder passThrough = new StringBuilder();
    boolean dotMatchesNewline = false;  // Oracle 'n'
    boolean perLineAnchors = false;     // Oracle 'm'

    if (oracleMatchParam != null) {
      for (char c : oracleMatchParam.toCharArray()) {
        switch (c) {
          case 'i':
          case 'c':
          case 'x':
            passThrough.append(c);
            break;
          case 'n':
            dotMatchesNewline = true;
            break;
          case 'm':
            perLineAnchors = true;
            break;
          default:
            throw new TransformationException(
                "Unknown Oracle regular expression match parameter '" + c + "' in '"
                + oracleMatchParam + "'. Valid parameters are i, c, n, m and x.");
        }
      }
    }

    return passThrough.append(newlineFlag(dotMatchesNewline, perLineAnchors)).toString();
  }

  private static String newlineFlag(boolean dotMatchesNewline, boolean perLineAnchors) {
    if (dotMatchesNewline) {
      return perLineAnchors ? "w" : "";
    }
    return perLineAnchors ? "n" : "p";
  }
}
