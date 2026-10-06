package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.builder.VisitOrderByClause;
import me.christianrobert.orapgsync.transformer.builder.VisitOverClause;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.List;

/**
 * Transforms Oracle string aggregation - {@code LISTAGG} and the legacy {@code WM_CONCAT} -
 * to PostgreSQL {@code STRING_AGG}.
 *
 * <pre>
 * LISTAGG(m, d) WITHIN GROUP (ORDER BY o)
 *     → STRING_AGG( ( m )::text , d ORDER BY o )
 *
 * LISTAGG(m, d) WITHIN GROUP (ORDER BY o) OVER (PARTITION BY p)
 *     → STRING_AGG( ( m )::text , d )
 *       OVER ( PARTITION BY p ORDER BY o ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING )
 *
 * WM_CONCAT(m)
 *     → STRING_AGG( ( m )::text , ',' )
 * </pre>
 *
 * <h3>Why the explicit frame in the analytic form</h3>
 *
 * <p>PostgreSQL rejects aggregate {@code ORDER BY} inside a window function
 * (<i>"aggregate ORDER BY is not implemented for window functions"</i>), so Oracle's
 * {@code WITHIN GROUP} ordering has to move into the {@code OVER} clause. That move is only
 * correct with an explicit full frame. PostgreSQL's default frame is
 * {@code RANGE UNBOUNDED PRECEDING AND CURRENT ROW}, which silently turns the aggregate into a
 * <i>running</i> concatenation - one growing prefix per row instead of the whole partition on
 * every row. {@code ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING} restores Oracle's
 * semantics while still honouring the ordering.</p>
 *
 * <h3>The {@code ::text} cast</h3>
 *
 * <p>PostgreSQL resolves overloads by exact argument type and offers no implicit
 * {@code numeric} → {@code text} coercion, so {@code LISTAGG(empno, ',')} would fail with
 * <i>"function string_agg(numeric, unknown) does not exist"</i>. The measure is therefore always
 * cast. On a measure that is already {@code text} the cast is a no-op, and the same reasoning
 * produced the {@code ::integer} casts in {@link StringFunctionTransformer}.</p>
 *
 * <p>The cast is <b>omitted under {@code DISTINCT}</b> - see below.</p>
 *
 * <h3>Deliberate limitations</h3>
 *
 * <ul>
 *   <li><b>{@code ON OVERFLOW ERROR} is dropped.</b> It exists only because Oracle caps
 *       {@code VARCHAR2} at 4000/32767 bytes; PostgreSQL {@code text} is unbounded, so there is
 *       nothing to raise. Dropping it lets PostgreSQL succeed where Oracle would have failed -
 *       a relaxation, not a change of result. It is also Oracle's default, so rejecting it would
 *       block essentially every LISTAGG.</li>
 *   <li><b>{@code ON OVERFLOW TRUNCATE} is rejected.</b> A view that spells this out expects
 *       overflow to happen, so dropping it would change the output on exactly the rows that
 *       motivated it. PostgreSQL has no equivalent, and emulating one (truncate to 4000
 *       <i>bytes</i>, append the indicator, append the count of omitted values) depends on the
 *       source database's {@code MAX_STRING_SIZE}.</li>
 *   <li><b>{@code DISTINCT} with an order key other than the measure is rejected</b>, because
 *       PostgreSQL requires every aggregate {@code ORDER BY} expression to appear in the
 *       argument list. Detecting it here gives a message that names LISTAGG; PostgreSQL's own
 *       error arrives at {@code CREATE VIEW} with no hint of where it came from.</li>
 *   <li><b>{@code DISTINCT} combined with {@code OVER} is rejected</b> - PostgreSQL has no window
 *       form of it at all (<i>"DISTINCT is not implemented for window functions"</i>).</li>
 *   <li><b>The measure is emitted uncast under {@code DISTINCT}.</b> The argument-list rule above
 *       compares parsed expressions, so a cast measure would need an identically cast order key -
 *       and {@code ORDER BY (empno)::text} sorts {@code 1,11,3} where Oracle sorts
 *       {@code 1,3,11}. Rather than silently reorder, the cast is left off: a text measure (the
 *       normal case) works exactly, and a non-text measure fails loudly at {@code CREATE VIEW}
 *       on overload resolution.</li>
 *   <li><b>Ordering follows the PostgreSQL database collation</b>, not Oracle's NLS collation, so
 *       a run may differ for non-ASCII data. This applies to every {@code ORDER BY} the
 *       transformer emits and is not specific to LISTAGG.</li>
 * </ul>
 */
public class ListaggTransformer {

  /** WM_CONCAT has no delimiter argument; Oracle always separates with a comma. */
  private static final String WM_CONCAT_DELIMITER = "','";

  /**
   * Transforms the {@code LISTAGG} alternative of {@code other_function}.
   *
   * <pre>
   * LISTAGG '(' (ALL | DISTINCT | UNIQUE)? argument (',' string_delimiter)? listagg_overflow_clause? ')'
   *     (WITHIN GROUP '(' order_by_clause ')')? over_clause?
   * </pre>
   */
  public static String transformListagg(
      PlSqlParser.Other_functionContext ctx, PostgresCodeBuilder b) {

    rejectOverflowTruncate(ctx.listagg_overflow_clause());

    // Oracle's UNIQUE is a synonym for DISTINCT; ALL is the default and needs no marker.
    boolean distinct = ctx.DISTINCT() != null || ctx.UNIQUE() != null;

    String measure = measureExpression(ctx.argument(), b);
    String delimiter = ctx.string_delimiter() != null
        ? delimiter(ctx.string_delimiter(), b)
        : "''";  // Oracle defaults to NULL (no separator); PostgreSQL requires the argument

    PlSqlParser.Order_by_clauseContext withinGroup = ctx.order_by_clause();
    PlSqlParser.Over_clauseContext over = ctx.over_clause();

    if (distinct) {
      return distinctAggregate(measure, delimiter, withinGroup, over, b);
    }

    String argument = "( " + measure + " )::text";
    if (over != null) {
      return "STRING_AGG( " + argument + " , " + delimiter + " ) "
          + analyticWindow(over, withinGroup, b);
    }

    String ordering = withinGroup != null ? " " + VisitOrderByClause.v(withinGroup, b) : "";
    return "STRING_AGG( " + argument + " , " + delimiter + ordering + " )";
  }

  /**
   * Transforms Oracle's undocumented {@code WM_CONCAT}, the pre-11g LISTAGG substitute.
   *
   * <p>It reaches this class through the {@code over_clause_keyword} alternative of
   * {@code other_function}, which would otherwise emit the name verbatim into SQL PostgreSQL has
   * no function for. WM_CONCAT takes no delimiter and has no {@code WITHIN GROUP}; its result
   * order was never defined, so nothing is lost by leaving the aggregate unordered.</p>
   */
  public static String transformWmConcat(
      PlSqlParser.Other_functionContext ctx, PostgresCodeBuilder b) {

    PlSqlParser.Function_argument_analyticContext argCtx = ctx.function_argument_analytic();
    List<PlSqlParser.ArgumentContext> arguments =
        argCtx != null ? argCtx.argument() : List.of();
    if (arguments.size() != 1) {
      throw new TransformationException(
          "WM_CONCAT expects exactly one argument, found " + arguments.size());
    }

    String argument = "( " + measureExpression(arguments.get(0), b) + " )::text";
    String call = "STRING_AGG( " + argument + " , " + WM_CONCAT_DELIMITER + " )";

    return ctx.over_clause() != null
        ? call + " " + analyticWindow(ctx.over_clause(), null, b)
        : call;
  }

  // ==================== Helper Methods ====================

  /**
   * Builds the {@code DISTINCT} form, which PostgreSQL constrains far more tightly than Oracle.
   *
   * <p>Both rejections here are for constructs PostgreSQL cannot express at all, so there is no
   * output that would have been merely approximate.</p>
   */
  private static String distinctAggregate(
      String measure,
      String delimiter,
      PlSqlParser.Order_by_clauseContext withinGroup,
      PlSqlParser.Over_clauseContext over,
      PostgresCodeBuilder b) {

    if (over != null) {
      throw new TransformationException(
          "LISTAGG(DISTINCT ...) with an OVER clause is not supported - PostgreSQL has no "
              + "DISTINCT form of a window aggregate");
    }

    String ordering = "";
    if (withinGroup != null) {
      requireOrderKeyMatchesMeasure(withinGroup, measure, b);
      ordering = " " + VisitOrderByClause.v(withinGroup, b);
    }

    // No ::text cast - see the class javadoc; the cast would break the argument-list rule
    // that the check above just established.
    return "STRING_AGG( DISTINCT " + measure + " , " + delimiter + ordering + " )";
  }

  /**
   * Enforces PostgreSQL's rule that an aggregate with {@code DISTINCT} may only be ordered by
   * expressions that appear in its argument list - which, for STRING_AGG, means the measure.
   *
   * <p>The comparison is on the <i>transformed</i> expressions, so it sees through anything the
   * visitors rewrite (synonym resolution, schema qualification, identifier normalisation) and
   * matches on what PostgreSQL will actually compare.</p>
   */
  private static void requireOrderKeyMatchesMeasure(
      PlSqlParser.Order_by_clauseContext withinGroup, String measure, PostgresCodeBuilder b) {

    List<PlSqlParser.Order_by_elementsContext> elements = withinGroup.order_by_elements();
    boolean matches = elements.size() == 1
        && normalize(b.visit(elements.get(0).expression())).equals(normalize(measure));

    if (!matches) {
      throw new TransformationException(
          "LISTAGG(DISTINCT ...) WITHIN GROUP (ORDER BY ...) is only supported when it is "
              + "ordered by the aggregated expression itself - PostgreSQL requires every "
              + "ORDER BY expression of a DISTINCT aggregate to appear in its argument list");
    }
  }

  /**
   * Builds the {@code OVER} clause for the analytic form, moving Oracle's {@code WITHIN GROUP}
   * ordering into the window and pinning the frame to the whole partition.
   *
   * <p>Oracle permits only a partition clause in LISTAGG's {@code OVER}, so an ordering or
   * windowing clause coming from the source is a construct this mapping has not reasoned about;
   * both are rejected rather than merged with the frame this method owns.</p>
   */
  private static String analyticWindow(
      PlSqlParser.Over_clauseContext over,
      PlSqlParser.Order_by_clauseContext withinGroup,
      PostgresCodeBuilder b) {

    if (over.HIERARCHY() != null) {
      throw new TransformationException(
          "HIERARCHY clause in window functions not yet supported");
    }
    if (over.windowing_clause() != null) {
      throw new TransformationException(
          "String aggregation with an explicit windowing clause is not supported - the frame "
              + "has to be the full partition to reproduce Oracle's semantics");
    }
    if (withinGroup != null && over.order_by_clause() != null) {
      throw new TransformationException(
          "String aggregation with ORDER BY in both WITHIN GROUP and OVER is not supported");
    }

    PlSqlParser.Order_by_clauseContext ordering =
        withinGroup != null ? withinGroup : over.order_by_clause();

    StringBuilder result = new StringBuilder("OVER ( ");
    if (over.query_partition_clause() != null) {
      result.append(VisitOverClause.partitionBy(over.query_partition_clause(), b)).append(" ");
    }
    if (ordering != null) {
      // The frame is not optional: without it PostgreSQL defaults to RANGE UNBOUNDED PRECEDING
      // AND CURRENT ROW and produces a running concatenation instead of the whole partition.
      result.append(VisitOrderByClause.v(ordering, b)).append(" ")
          .append("ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING ");
    }
    return result.append(")").toString();
  }

  /** Reads the aggregated expression out of an {@code argument} context. */
  private static String measureExpression(
      PlSqlParser.ArgumentContext arg, PostgresCodeBuilder b) {

    if (arg == null || arg.expression() == null) {
      throw new TransformationException("String aggregation missing its aggregated expression");
    }
    if (arg.identifier() != null) {
      throw new TransformationException(
          "Named arguments (identifier => expression) not supported in string aggregation");
    }
    return b.visit(arg.expression());
  }

  /**
   * Renders a {@code string_delimiter}.
   *
   * <pre>
   * string_delimiter
   *     : CHAR_STRING
   *     | string_function
   *     | string_delimiter BAR BAR string_delimiter
   *     | '(' string_delimiter ')'
   *     | id_expression
   * </pre>
   */
  private static String delimiter(
      PlSqlParser.String_delimiterContext ctx, PostgresCodeBuilder b) {

    if (ctx.CHAR_STRING() != null) {
      return ctx.CHAR_STRING().getText();
    }
    if (ctx.string_function() != null) {
      return b.visit(ctx.string_function());
    }

    List<PlSqlParser.String_delimiterContext> parts = ctx.string_delimiter();
    if (ctx.LEFT_PAREN() != null && parts.size() == 1) {
      return "( " + delimiter(parts.get(0), b) + " )";
    }
    if (parts.size() == 2) {
      return delimiter(parts.get(0), b) + " || " + delimiter(parts.get(1), b);
    }
    if (ctx.id_expression() != null) {
      return IdentifierHelper.emit(ctx.id_expression());
    }

    throw new TransformationException(
        "Unsupported LISTAGG delimiter: " + ctx.getText());
  }

  /**
   * Rejects {@code ON OVERFLOW TRUNCATE}; {@code ON OVERFLOW ERROR} needs no action because
   * PostgreSQL {@code text} cannot overflow. See the class javadoc for the reasoning.
   */
  private static void rejectOverflowTruncate(
      PlSqlParser.Listagg_overflow_clauseContext ctx) {

    if (ctx != null && ctx.TRUNCATE() != null) {
      throw new TransformationException(
          "LISTAGG ... ON OVERFLOW TRUNCATE is not supported - PostgreSQL text has no length "
              + "limit to overflow, and silently dropping the clause would change the result "
              + "for exactly the rows it was written for");
    }
  }

  /** Collapses whitespace so two renderings of the same expression compare equal. */
  private static String normalize(String expression) {
    return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
  }
}
