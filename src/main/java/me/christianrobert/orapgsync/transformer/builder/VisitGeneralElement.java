package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.functions.DateFunctionTransformer;
import me.christianrobert.orapgsync.transformer.builder.functions.StringFunctionTransformer;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;

import java.util.ArrayList;
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
   * 1. Check for sequence pseudo-column (seq.NEXTVAL, seq.CURRVAL)
   * 2. If last part has function arguments → function call
   *    - Check metadata: is this a type member method (table.col.method)?
   *    - Check metadata: is this a package function (package.function)?
   *    - Transform accordingly
   * 3. Otherwise → column reference (table.column)
   *    - Pass through as-is: table.column
   */
  private static String handleDotNavigation(
      PlSqlParser.General_elementContext ctx,
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    // Check for sequence NEXTVAL/CURRVAL calls FIRST (before function call check)
    // Pattern: sequence_name.NEXTVAL or schema.sequence_name.NEXTVAL
    if (isSequenceCall(parts)) {
      return handleSequenceCall(parts, b);
    }

    // Get the last part to check if it's a function call
    PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
    boolean isFunctionCall = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();

    if (isFunctionCall) {
      // Could be:
      // 1. Type member method: table.col.method() or table.col.method1().method2()
      // 2. Package function: package.function() or schema.package.function()

      // Try to disambiguate using metadata
      TransformationContext context = b.getContext();
      if (context != null && parts.size() >= 3) {
        // Could be type member method: alias.col.method()
        String firstPart = parts.get(0).id_expression().getText();
        String secondPart = parts.get(1).id_expression().getText();

        // 1. Is firstPart a table alias?
        String tableName = context.resolveAlias(firstPart);
        if (tableName != null) {
          // 2. Is secondPart a column of custom type?
          // Need to qualify table name with schema for lookup
          String qualifiedTable = qualifyTableName(tableName, context);
          TransformationIndices.ColumnTypeInfo typeInfo = context.getColumnType(qualifiedTable, secondPart);

          if (typeInfo != null && typeInfo.isCustomType()) {
            // 3. Does the type have the method we're calling?
            String methodName = parts.get(2).id_expression().getText();
            if (context.hasTypeMethod(typeInfo.getQualifiedType(), methodName)) {
              // IT'S A TYPE MEMBER METHOD!
              return handleTypeMemberMethod(parts, b, context);
            }
          }
        }
      }

      // Not a type member method - assume package function
      return handlePackageFunctionCall(parts, b);
    } else {
      // COLUMN REFERENCE: table.column or table.column.subfield
      return handleQualifiedColumn(parts, b);
    }
  }

  /**
   * Checks if the dot navigation is a sequence pseudo-column call (NEXTVAL or CURRVAL).
   *
   * <p>Oracle sequence syntax:
   * - sequence_name.NEXTVAL (2 parts)
   * - sequence_name.CURRVAL (2 parts)
   * - schema.sequence_name.NEXTVAL (3 parts)
   * - schema.sequence_name.CURRVAL (3 parts)
   *
   * <p>Detection criteria:
   * - At least 2 parts
   * - Last part is NEXTVAL or CURRVAL (case-insensitive)
   * - Last part has NO function arguments (sequence pseudo-columns don't use parentheses)
   *
   * @param parts The dot-separated parts
   * @return true if this is a sequence NEXTVAL/CURRVAL call
   */
  private static boolean isSequenceCall(List<PlSqlParser.General_element_partContext> parts) {
    if (parts.size() < 2) {
      return false;
    }

    // Last part should be NEXTVAL or CURRVAL (no function arguments)
    PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);

    // Safeguard: Sequence pseudo-columns NEVER have parentheses
    // This prevents confusion with package functions named "nextval" or "currval"
    if (lastPart.function_argument() != null && !lastPart.function_argument().isEmpty()) {
      return false; // Has arguments, not a pseudo-column
    }

    String lastIdentifier = lastPart.id_expression().getText().toUpperCase();
    return "NEXTVAL".equals(lastIdentifier) || "CURRVAL".equals(lastIdentifier);
  }

  /**
   * Handles Oracle sequence pseudo-column calls and transforms them to PostgreSQL function calls.
   *
   * <p>Transformations:
   * - Oracle: sequence_name.NEXTVAL → PostgreSQL: nextval('schema.sequence_name')
   * - Oracle: sequence_name.CURRVAL → PostgreSQL: currval('schema.sequence_name')
   * - Oracle: schema.sequence_name.NEXTVAL → PostgreSQL: nextval('schema.sequence_name')
   *
   * <p>Schema qualification:
   * - Unqualified sequences are automatically qualified with current schema
   * - Synonym resolution is applied if TransformationContext is available
   * - Cross-schema sequences preserve schema prefix
   *
   * @param parts The dot-separated parts (2 or 3 elements)
   * @param b PostgreSQL code builder
   * @return Transformed PostgreSQL function call
   */
  private static String handleSequenceCall(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    // Extract operation (NEXTVAL or CURRVAL)
    String operation = parts.get(parts.size() - 1).id_expression().getText().toLowerCase();

    // Extract sequence name path (all parts except last)
    List<String> sequencePath = parts.subList(0, parts.size() - 1).stream()
        .map(part -> part.id_expression().getText())
        .collect(Collectors.toList());

    // Apply transformation with metadata context
    TransformationContext context = b.getContext();
    if (context != null) {
      return transformSequenceCallWithMetadata(sequencePath, operation, context);
    } else {
      // No context available - simple transformation without metadata
      return transformSequenceCallSimple(sequencePath, operation);
    }
  }

  /**
   * Transforms sequence call using metadata context for synonym resolution and schema handling.
   *
   * @param sequencePath Sequence name parts (1 or 2 elements: [sequence] or [schema, sequence])
   * @param operation "nextval" or "currval"
   * @param context Transformation context
   * @return PostgreSQL function call: operation('qualified.sequence')
   */
  private static String transformSequenceCallWithMetadata(
      List<String> sequencePath,
      String operation,
      TransformationContext context) {

    String currentSchema = context.getCurrentSchema().toLowerCase();
    String sequenceName;

    if (sequencePath.size() == 1) {
      // Single-part: sequence_name.NEXTVAL
      String seqName = sequencePath.get(0);

      // Check if it's a synonym
      String resolved = context.resolveSynonym(seqName);
      if (resolved != null) {
        // Synonym resolved to "schema.sequence" - use as-is
        sequenceName = resolved.toLowerCase();
      } else {
        // Not a synonym - qualify with current schema
        sequenceName = currentSchema + "." + seqName.toLowerCase();
      }

    } else if (sequencePath.size() == 2) {
      // Two-part: schema.sequence_name.NEXTVAL
      String schema = sequencePath.get(0).toLowerCase();
      String seqName = sequencePath.get(1).toLowerCase();
      sequenceName = schema + "." + seqName;

    } else {
      throw new TransformationException(
          "Sequence call with more than 2 path parts not supported: " + sequencePath);
    }

    // Transform to PostgreSQL function call
    return operation + "('" + sequenceName + "')";
  }

  /**
   * Transforms sequence call without metadata (simple heuristic).
   *
   * @param sequencePath Sequence name parts (1 or 2 elements: [sequence] or [schema, sequence])
   * @param operation "nextval" or "currval"
   * @return PostgreSQL function call: operation('qualified.sequence')
   */
  private static String transformSequenceCallSimple(
      List<String> sequencePath,
      String operation) {

    // Build qualified sequence name from path parts
    String sequenceName = sequencePath.stream()
        .map(String::toLowerCase)
        .collect(Collectors.joining("."));

    // Transform to PostgreSQL function call
    return operation + "('" + sequenceName + "')";
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
      String upperFunctionName = functionName.toUpperCase();

      // Check if this is a date/time function that needs transformation
      switch (upperFunctionName) {
        case "ADD_MONTHS":
        case "MONTHS_BETWEEN":
        case "LAST_DAY":
        case "TRUNC":
        case "ROUND":
          return DateFunctionTransformer.transform(upperFunctionName, partCtx, b);
        case "INSTR":
        case "LPAD":
        case "RPAD":
        case "TRANSLATE":
          return StringFunctionTransformer.transform(upperFunctionName, partCtx, b);
        default:
          // Not a special function - proceed with normal handling
          break;
      }

      String arguments = getFunctionArguments(partCtx, b);

      // Apply schema qualification if context available
      // Oracle implicit schema resolution: unqualified function → current schema
      // PostgreSQL uses search_path which may not include the current schema
      TransformationContext context = b.getContext();
      if (context != null && !functionName.contains(".")) {
        // Unqualified function → qualify with current schema
        // Note: Built-in PostgreSQL functions (like COALESCE, UPPER, etc.) are in pg_catalog
        // which is always in search_path, so this is safe
        functionName = context.getCurrentSchema().toLowerCase() + "." + functionName.toLowerCase();
      }

      return functionName + arguments;
    }

    // Check for Oracle pseudo-columns and special constants that need transformation
    String identifier = partCtx.getText();
    String upperIdentifier = identifier.toUpperCase();

    // SYSDATE → CURRENT_TIMESTAMP
    // Oracle: SYSDATE returns current date and time
    // PostgreSQL: CURRENT_TIMESTAMP is the equivalent
    if ("SYSDATE".equals(upperIdentifier)) {
      return "CURRENT_TIMESTAMP";
    }

    // Simple identifier - use getText()
    return partCtx.getText();
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

  /**
   * Handles type member method calls: table.col.method() or chained calls.
   *
   * <p>Transformation (PostgreSQL has no member methods, so we use flattened functions):
   * - Oracle: emp.address.get_street() → PostgreSQL: address_type__get_street(emp.address)
   * - Oracle: emp.address.get_full().upper() → PostgreSQL: address_type__upper(address_type__get_full(emp.address))
   *
   * <p>The object instance becomes the first parameter to the flattened function.
   */
  private static String handleTypeMemberMethod(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b,
      TransformationContext context) {

    if (parts.size() < 3) {
      throw new TransformationException(
          "Type member method call requires at least 3 parts: alias.column.method");
    }

    // For simple case: alias.column.method()
    // parts[0] = alias (e.g., emp)
    // parts[1] = column (e.g., address)
    // parts[2] = method() (e.g., get_street())

    // For chained case: alias.column.method1().method2()
    // parts[0] = alias
    // parts[1] = column
    // parts[2] = method1()
    // parts[3] = method2()
    // etc.

    // Build the transformation recursively for chained calls
    return buildTypeMemberMethodChain(parts, 0, parts.size(), b, context);
  }

  /**
   * Recursively builds type member method chains as nested function calls.
   *
   * <p>Pattern: typename__method(instance_expr, ...args)
   * <p>Chained: typename2__method2(typename1__method1(instance, args1), args2)
   *
   * @param parts All parts of the dot notation
   * @param startIdx Starting index in parts array
   * @param endIdx Ending index (exclusive) in parts array
   * @param b PostgreSQL code builder
   * @param context Transformation context
   * @return Transformed PostgreSQL expression
   */
  private static String buildTypeMemberMethodChain(
      List<PlSqlParser.General_element_partContext> parts,
      int startIdx,
      int endIdx,
      PostgresCodeBuilder b,
      TransformationContext context) {

    if (endIdx - startIdx < 3) {
      throw new TransformationException("Type member method chain too short");
    }

    // Find the last method call in this chain segment
    int lastMethodIdx = endIdx - 1;
    PlSqlParser.General_element_partContext lastPart = parts.get(lastMethodIdx);

    if (lastPart.function_argument() == null || lastPart.function_argument().isEmpty()) {
      throw new TransformationException("Expected method call at end of chain");
    }

    // Check if there are chained method calls before this one
    // Look for the previous method call (if any)
    int prevMethodIdx = -1;
    for (int i = lastMethodIdx - 1; i >= startIdx + 2; i--) {
      if (parts.get(i).function_argument() != null && !parts.get(i).function_argument().isEmpty()) {
        prevMethodIdx = i;
        break;
      }
    }

    if (prevMethodIdx != -1) {
      // CHAINED CALL: recursively handle the previous method call
      // e.g., emp.address.get_full().upper()
      // Transform to: address_type__upper(address_type__get_full(emp.address))

      // First, get the inner call
      String innerCall = buildTypeMemberMethodChain(parts, startIdx, prevMethodIdx + 1, b, context);

      // Get the type name for the outer method (from the previous method's return type)
      // For now, we'll use the same type as the column (simplified - doesn't handle type changes)
      String typeName = getTypeNameForMethod(parts, startIdx, context);

      // Get the last method details
      String methodName = getFunctionName(lastPart);
      List<String> methodArgs = getMethodArguments(lastPart, b);

      // Build outer call: typename__method(innerCall, args...)
      StringBuilder result = new StringBuilder();
      result.append(typeName).append("__").append(methodName.toLowerCase());
      result.append("( ");
      result.append(innerCall);
      if (!methodArgs.isEmpty()) {
        result.append(" , ").append(String.join(" , ", methodArgs));
      }
      result.append(" )");

      return result.toString();

    } else {
      // SIMPLE CALL: alias.column.method()
      // Build object reference: alias.column
      StringBuilder objectPath = new StringBuilder();
      for (int i = startIdx; i < lastMethodIdx; i++) {
        if (i > startIdx) {
          objectPath.append(" . ");
        }
        objectPath.append(parts.get(i).id_expression().getText());
      }

      // Get type name for the method
      String typeName = getTypeNameForMethod(parts, startIdx, context);

      // Get method details
      String methodName = getFunctionName(lastPart);
      List<String> methodArgs = getMethodArguments(lastPart, b);

      // Build: typename__method(instance, args...)
      StringBuilder result = new StringBuilder();
      result.append(typeName).append("__").append(methodName.toLowerCase());
      result.append("( ");
      result.append(objectPath);
      if (!methodArgs.isEmpty()) {
        result.append(" , ").append(String.join(" , ", methodArgs));
      }
      result.append(" )");

      return result.toString();
    }
  }

  /**
   * Gets the type name for a method call.
   * Looks up the column type from metadata.
   */
  private static String getTypeNameForMethod(
      List<PlSqlParser.General_element_partContext> parts,
      int startIdx,
      TransformationContext context) {

    // parts[startIdx] = alias (e.g., emp)
    // parts[startIdx + 1] = column (e.g., address)

    String firstPart = parts.get(startIdx).id_expression().getText();
    String secondPart = parts.get(startIdx + 1).id_expression().getText();

    // Resolve alias to table name
    String tableName = context.resolveAlias(firstPart);
    if (tableName == null) {
      throw new TransformationException(
          "Cannot resolve alias '" + firstPart + "' for type member method");
    }

    // Get column type info
    String qualifiedTable = qualifyTableName(tableName, context);
    TransformationIndices.ColumnTypeInfo typeInfo = context.getColumnType(qualifiedTable, secondPart);

    if (typeInfo == null || !typeInfo.isCustomType()) {
      throw new TransformationException(
          "Column '" + secondPart + "' is not a custom type");
    }

    // Return just the type name (without schema for now - will be lowercase)
    return typeInfo.getTypeName().toLowerCase();
  }

  /**
   * Extracts method arguments (excluding the SELF parameter which is the instance).
   */
  private static List<String> getMethodArguments(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      return new ArrayList<>();
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.isEmpty()) {
      return new ArrayList<>();
    }

    // Transform each argument
    return arguments.stream()
        .map(arg -> transformArgument(arg, b))
        .collect(Collectors.toList());
  }

  /**
   * Qualifies a table name with schema if needed.
   *
   * @param tableName Table name (may or may not have schema)
   * @param context Transformation context
   * @return Qualified table name "schema.table"
   */
  private static String qualifyTableName(String tableName, TransformationContext context) {
    // If table name already has schema (contains dot), return as-is
    if (tableName.contains(".")) {
      return tableName.toLowerCase();
    }

    // Otherwise, prepend current schema
    return context.getCurrentSchema().toLowerCase() + "." + tableName.toLowerCase();
  }
}
