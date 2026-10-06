package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms Oracle bitwise functions to their {@code oracle_compat} equivalents.
 *
 * <p>Currently just {@code BITAND}, which is the only bit operation Oracle exposes as a SQL
 * built-in — BITOR and BITXOR do not exist there, they are written as arithmetic over BITAND or
 * reached through UTL_RAW.
 *
 * <h2>Why this does not emit {@code &} directly</h2>
 * PostgreSQL's {@code &} has no {@code numeric} overload and Oracle NUMBER migrates to
 * {@code numeric}, so the operands need conversion — and Oracle's truncation rule differs from what
 * a cast does. That conversion lives in {@code oracle_compat.bitand} rather than being spelled out
 * at each call site; the function is a single-expression SQL function, so PostgreSQL inlines it and
 * the emitted call costs what the expression would have. Rationale and the measurements:
 * {@link me.christianrobert.orapgsync.oraclecompat.implementations.BitandImpl}.
 *
 * <h2>Both arguments are arbitrary expressions</h2>
 * {@code BITAND} has no dedicated grammar rule — it is a non-reserved keyword reaching
 * {@code general_element_part : id_expression function_argument*}, so each argument is a full
 * {@code expression} and must be visited recursively rather than pattern-matched. In practice they
 * are bit-mask columns and package constants as often as literals.
 */
public class BitwiseFunctionTransformer {

  /**
   * Main entry point for bitwise function transformations.
   *
   * @param functionName Function name (BITAND)
   * @param partCtx      Function call context from ANTLR
   * @param b            PostgreSQL code builder, for transforming the arguments
   * @return Transformed PostgreSQL SQL
   */
  public static String transform(
      String functionName,
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    switch (functionName.toUpperCase()) {
      case "BITAND":
        return transformBitand(partCtx, b);
      default:
        throw new TransformationException("Unsupported bitwise function: " + functionName);
    }
  }

  /**
   * {@code BITAND(expr1, expr2)} → {@code oracle_compat.bitand(expr1, expr2)}.
   *
   * <p>Oracle's BITAND takes exactly two arguments. A call with any other count is refused rather
   * than passed through: it would otherwise reach PostgreSQL as a call to a function that does not
   * exist, which inside a PL/pgSQL body is not detected until the function is first called in
   * production.
   */
  private static String transformBitand(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);

    if (args.size() != 2) {
      throw new TransformationException(
          "BITAND requires exactly 2 arguments, found: " + args.size());
    }

    List<String> transformedArgs = new ArrayList<>();
    for (PlSqlParser.ArgumentContext arg : args) {
      transformedArgs.add(transformArgument(arg, b));
    }

    return "oracle_compat.bitand( " + String.join(" , ", transformedArgs) + " )";
  }

  /**
   * Extracts the argument list from a function call context.
   */
  private static List<PlSqlParser.ArgumentContext> extractFunctionArguments(
      PlSqlParser.General_element_partContext partCtx) {

    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      return new ArrayList<>();
    }

    List<PlSqlParser.ArgumentContext> arguments = funcArgCtxList.get(0).argument();
    if (arguments == null) {
      return new ArrayList<>();
    }

    return arguments;
  }

  /**
   * Transforms one argument expression to PostgreSQL.
   */
  private static String transformArgument(
      PlSqlParser.ArgumentContext argCtx,
      PostgresCodeBuilder b) {

    // argument: (id_expression '=' '>')? expression
    if (argCtx.expression() != null) {
      return b.visit(argCtx.expression());
    }

    return argCtx.getText();
  }
}
