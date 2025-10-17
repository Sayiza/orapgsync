package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;
import java.util.stream.Collectors;

public class VisitGeneralElement {
  public static String v(PlSqlParser.General_elementContext ctx, PostgresCodeBuilder b) {

    // Check for parenthesized general_element
    if (ctx.LEFT_PAREN() != null) {
      throw new TransformationException(
          "Parenthesized general_element not yet supported in minimal implementation");
    }

    // Check for recursive structure (dot navigation):
    // general_element → general_element ('.' general_element_part)+
    PlSqlParser.General_elementContext nestedElement = ctx.general_element();
    if (nestedElement != null) {
      // DOT NAVIGATION: collect all parts recursively
      List<PlSqlParser.General_element_partContext> allParts = collectAllParts(ctx);
      if (allParts.size() > 1) {
        return handleDotNavigation(ctx, allParts, b);
      }
    }

    // Simple case: single general_element_part
    List<PlSqlParser.General_element_partContext> parts = ctx.general_element_part();
    if (parts == null || parts.isEmpty()) {
      throw new TransformationException("General element missing general_element_part");
    }

    return handleSimplePart(parts.get(0), b);
  }

  /**
   * Collects all parts from a recursive general_element structure.
   * Grammar: general_element → general_element ('.' general_element_part)+
   */
  private static List<PlSqlParser.General_element_partContext> collectAllParts(
      PlSqlParser.General_elementContext ctx) {
    List<PlSqlParser.General_element_partContext> parts = new java.util.ArrayList<>();

    // Recursively collect from nested general_element
    PlSqlParser.General_elementContext nestedElement = ctx.general_element();
    if (nestedElement != null) {
      parts.addAll(collectAllParts(nestedElement));
    }

    // Add parts from this level
    List<PlSqlParser.General_element_partContext> currentParts = ctx.general_element_part();
    if (currentParts != null) {
      parts.addAll(currentParts);
    }

    return parts;
  }

  /**
   * Handles dot navigation: first.second.third...
   *
   * <p>Disambiguation logic:
   * 1. If last part has function arguments → function call
   *    - Check metadata: is this schema.package.function?
   *    - Transform to: package__function or schema.package__function
   * 2. Otherwise → column reference (table.column)
   *    - Pass through as-is: table.column
   */
  private static String handleDotNavigation(
      PlSqlParser.General_elementContext ctx,
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    // Get the last part to check if it's a function call
    PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
    boolean isFunctionCall = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();

    if (isFunctionCall) {
      // PACKAGE FUNCTION CALL: package.function(args) or schema.package.function(args)
      return handlePackageFunctionCall(parts, b);
    } else {
      // COLUMN REFERENCE: table.column or table.column.subfield
      return handleQualifiedColumn(parts, b);
    }
  }

  /**
   * Handles package function calls: package.function(args)
   *
   * <p>Transformation:
   * - Oracle: package.function(args) → PostgreSQL: package__function(args)
   * - Oracle: schema.package.function(args) → PostgreSQL: schema.package__function(args)
   * - With synonym resolution if needed
   */
  private static String handlePackageFunctionCall(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    if (parts.size() < 2) {
      throw new TransformationException(
          "Package function call requires at least 2 parts: package.function");
    }

    // Last part is the function name (with arguments)
    PlSqlParser.General_element_partContext functionPart = parts.get(parts.size() - 1);
    String functionName = getFunctionName(functionPart);
    String arguments = getFunctionArguments(functionPart, b);

    // Previous parts form the package path (could be "package" or "schema.package")
    List<String> packagePath = parts.subList(0, parts.size() - 1).stream()
        .map(part -> part.id_expression().getText())
        .collect(Collectors.toList());

    // Apply transformation logic with metadata context
    TransformationContext context = b.getContext();
    if (context != null) {
      return transformPackageFunctionWithMetadata(packagePath, functionName, arguments, context);
    } else {
      // No context available - simple transformation without metadata
      return transformPackageFunctionSimple(packagePath, functionName, arguments);
    }
  }

  /**
   * Transforms package function using metadata context for synonym resolution and schema handling.
   */
  private static String transformPackageFunctionWithMetadata(
      List<String> packagePath,
      String functionName,
      String arguments,
      TransformationContext context) {

    String currentSchema = context.getCurrentSchema().toLowerCase();

    if (packagePath.size() == 1) {
      // Single-part path: package.function
      String packageName = packagePath.get(0);

      // Check if packageName is a synonym
      String resolved = context.resolveSynonym(packageName);
      if (resolved != null) {
        // Synonym resolved to "schema.table" - split it
        String[] parts = resolved.split("\\.");
        if (parts.length == 2) {
          String resolvedSchema = parts[0].toLowerCase();
          String resolvedPackage = parts[1];

          // Check if resolved schema is different from current schema
          if (!resolvedSchema.equals(currentSchema)) {
            // Cross-schema: need schema prefix
            return resolvedSchema + "." + resolvedPackage + "__" + functionName + arguments;
          } else {
            // Same schema: no prefix needed
            return resolvedPackage + "__" + functionName + arguments;
          }
        }
      }

      // Not a synonym - check if it's a package function in current schema
      String qualifiedName = currentSchema + "." + packageName.toLowerCase() + "." + functionName.toLowerCase();
      if (context.isPackageFunction(qualifiedName)) {
        // It's a package function in current schema - no schema prefix needed
        return packageName + "__" + functionName + arguments;
      }

      // Unknown - pass through with transformation (best guess)
      return packageName + "__" + functionName + arguments;

    } else if (packagePath.size() == 2) {
      // Two-part path: schema.package.function
      String schemaName = packagePath.get(0).toLowerCase();
      String packageName = packagePath.get(1);

      // Check if schema is different from current schema
      if (!schemaName.equals(currentSchema)) {
        // Cross-schema: keep schema prefix
        return schemaName + "." + packageName + "__" + functionName + arguments;
      } else {
        // Same schema: drop schema prefix
        return packageName + "__" + functionName + arguments;
      }

    } else {
      throw new TransformationException(
          "Package function call with more than 2 path parts not supported: " + packagePath);
    }
  }

  /**
   * Transforms package function without metadata (simple heuristic).
   */
  private static String transformPackageFunctionSimple(
      List<String> packagePath,
      String functionName,
      String arguments) {

    // Simple rule: flatten package.function to package__function
    // Keep schema prefix if present: schema.package.function → schema.package__function
    if (packagePath.size() == 1) {
      // package.function → package__function
      return packagePath.get(0) + "__" + functionName + arguments;
    } else if (packagePath.size() == 2) {
      // schema.package.function → schema.package__function
      return packagePath.get(0) + "." + packagePath.get(1) + "__" + functionName + arguments;
    } else {
      throw new TransformationException(
          "Package function call with more than 2 path parts not supported: " + packagePath);
    }
  }

  /**
   * Handles qualified column references: table.column, table.column.subfield, etc.
   * For now, pass through as-is (future: could validate against metadata).
   */
  private static String handleQualifiedColumn(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    // Build dotted path: part1.part2.part3...
    return parts.stream()
        .map(part -> part.id_expression().getText())
        .collect(Collectors.joining(" . "));
  }

  /**
   * Handles a simple general_element_part (no dots).
   */
  private static String handleSimplePart(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    // Check if this is a function call (has function_argument*)
    if (partCtx.function_argument() != null && !partCtx.function_argument().isEmpty()) {
      // Simple function call: function(args)
      String functionName = getFunctionName(partCtx);
      String arguments = getFunctionArguments(partCtx, b);
      return functionName + arguments;
    }

    // Simple identifier - use getText()
    return partCtx.getText();
  }

  /**
   * Extracts function name from a general_element_part.
   */
  private static String getFunctionName(PlSqlParser.General_element_partContext partCtx) {
    if (partCtx.id_expression() == null) {
      throw new TransformationException("Function part missing id_expression");
    }
    return partCtx.id_expression().getText();
  }

  /**
   * Extracts and transforms function arguments.
   */
  private static String getFunctionArguments(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      return "( )";
    }

    // There should be exactly one function_argument context (which contains all arguments)
    PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);

    // function_argument: '(' (argument (',' argument)*)? ')' keep_clause?
    // Get all argument contexts
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.isEmpty()) {
      return "( )";
    }

    // Transform each argument
    List<String> transformedArgs = arguments.stream()
        .map(arg -> transformArgument(arg, b))
        .collect(Collectors.toList());

    return "( " + String.join(" , ", transformedArgs) + " )";
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
