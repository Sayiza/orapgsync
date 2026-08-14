package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.functions.DateFunctionTransformer;
import me.christianrobert.orapgsync.transformer.builder.functions.StringFunctionTransformer;
import me.christianrobert.orapgsync.transformer.builder.generalelement.CollectionMethodTransformer;
import me.christianrobert.orapgsync.transformer.builder.generalelement.DotNavigationDispatcher;
import me.christianrobert.orapgsync.transformer.builder.generalelement.GeneralElementResult;
import me.christianrobert.orapgsync.transformer.builder.generalelement.InlineTypeFieldTransformer;
import me.christianrobert.orapgsync.transformer.builder.generalelement.ObjectFieldAccessAdapter;
import me.christianrobert.orapgsync.transformer.builder.generalelement.PackageVariableTransformer;
import me.christianrobert.orapgsync.transformer.builder.generalelement.ParameterlessFunctionDetector;
import me.christianrobert.orapgsync.transformer.builder.generalelement.SequenceCallTransformer;
import me.christianrobert.orapgsync.transformer.builder.objectfield.ObjectFieldAccessTransformer;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class VisitGeneralElement {

  /**
   * Static dispatcher with registered transformers in priority order.
   * Transformers are added incrementally during refactoring.
   */
  private static final DotNavigationDispatcher DISPATCHER = createDispatcher();

  private static DotNavigationDispatcher createDispatcher() {
    DotNavigationDispatcher dispatcher = new DotNavigationDispatcher();
    // Priority order follows the plan's disambiguation logic:
    // 1. PackageVariable - pkg.g_var (before function calls - same syntax)
    dispatcher.addTransformer(new PackageVariableTransformer());
    // 2. InlineTypeField - local_rec.field (before table column)
    dispatcher.addTransformer(new InlineTypeFieldTransformer());
    // 3. CollectionMethod - v.COUNT (before generic method)
    dispatcher.addTransformer(new CollectionMethodTransformer());
    // 4. SequenceCall - seq.NEXTVAL (special - no parentheses)
    dispatcher.addTransformer(new SequenceCallTransformer());
    // 5. ObjectFieldAccess - table.col.field (before function)
    dispatcher.addTransformer(new ObjectFieldAccessAdapter());
    // Remaining: PackageVariableField, PackageCollectionAccess, FunctionCall
    // handled by fallback code in handleDotNavigation
    return dispatcher;
  }

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
   * 0. Check for package variable reference (3 patterns) - transform to getter call
   *    - Pattern 1: unqualified (variable) - uses current package
   *    - Pattern 2: package-qualified (package.variable)
   *    - Pattern 3: schema-qualified (schema.package.variable)
   * 0.5 Check for inline type field access (Phase 1B) - transform to JSON operations
   *    - Pattern: variable.field → (variable->>'field')::type
   *    - Pattern: variable.field1.field2 → variable->'field1'->>'field2'
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

    // DISPATCHER: Try registered transformers in priority order
    // Handles: PackageVariable, InlineTypeField, CollectionMethod, SequenceCall, ObjectFieldAccess
    GeneralElementResult dispatcherResult = DISPATCHER.dispatch(parts, b);
    if (dispatcherResult.isHandled()) {
      return dispatcherResult.getResult();
    }

    // FALLBACK: Handle patterns not yet extracted to transformers

    // Package variable FIELD access (Phase: Complex Types) - RHS only
    // Patterns:
    //   - 2+ parts inside package: g_rec.field (unqualified, uses current package)
    //   - 3+ parts: pkg.g_rec.field (package-qualified)
    //   - 4+ parts: schema.pkg.g_rec.field (schema-qualified)
    // Transform to: (pkg__get_g_rec()->>'field')::type
    if (!b.isInAssignmentTarget() && parts.size() >= 2) {
      String pkgFieldAccess = tryTransformPackageVariableFieldAccess(parts, b);
      if (pkgFieldAccess != null) {
        return pkgFieldAccess;
      }
    }

    // Get the last part to check if it's a function call
    PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
    boolean isFunctionCall = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();

    // STEP 0.8: Check for package variable COLLECTION element access (Phase 4: Complex Types)
    // Pattern: pkg.g_array(i) looks like a function call but is collection element access
    // Must be checked BEFORE handlePackageFunctionCall() to disambiguate
    if (isFunctionCall && !b.isInAssignmentTarget() && parts.size() >= 2) {
      String collectionAccess = tryTransformPackageVariableCollectionAccess(parts, b);
      if (collectionAccess != null) {
        return collectionAccess;
      }
    }

    if (isFunctionCall) {
      // Could be:
      // 1. Type member method: table.col.method() or table.col.method1().method2()
      // 2. Package function: package.function() or schema.package.function()

      // Try to disambiguate using metadata
      TransformationContext context = b.getContext();
      if (context != null && parts.size() >= 3) {
        // Could be type member method: alias.col.method()
        String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
        String secondPart = IdentifierHelper.unquote(parts.get(1).id_expression().getText());

        // 1. Is firstPart a table alias?
        String tableName = context.resolveAlias(firstPart);
        if (tableName != null) {
          // 2. Is secondPart a column of custom type?
          // Need to qualify table name with schema for lookup
          String qualifiedTable = qualifyTableName(tableName, context);
          TransformationIndices.ColumnTypeInfo typeInfo = context.getColumnType(qualifiedTable, secondPart);

          if (typeInfo != null && typeInfo.isCustomType()) {
            // 3. Does the type have the method we're calling?
            String methodName = IdentifierHelper.unquote(parts.get(2).id_expression().getText());
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
      // No parentheses. Oracle allows a routine needing no arguments to be written bare, which is
      // the same syntax as table.column - only metadata tells the two apart. Tried before falling
      // through to the column reading; returns null unless the metadata says otherwise.
      String parameterlessCall = ParameterlessFunctionDetector.detect(parts, b);
      if (parameterlessCall != null) {
        return parameterlessCall;
      }

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

    String lastIdentifier = IdentifierHelper.unquote(lastPart.id_expression().getText()).toUpperCase();
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
        .map(part -> IdentifierHelper.unquote(part.id_expression().getText()))
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
        .map(part -> IdentifierHelper.unquote(part.id_expression().getText()))
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

      // Check if this is an Oracle compatibility package (HTP, HTF, OWA, DBMS_OUTPUT, etc.)
      // These should use oracle_compat schema, not current schema
      if (isOracleCompatibilityPackage(packageName)) {
        return "oracle_compat." + packageName.toLowerCase() + "__" + functionName.toLowerCase() + arguments;
      }

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
        // It's a package function in current schema - add schema prefix
        // (PostgreSQL search_path may not include the migration schema)
        return currentSchema + "." + packageName + "__" + functionName + arguments;
      }

      // Unknown - pass through with transformation (best guess)
      // Add schema prefix for consistency
      return currentSchema + "." + packageName + "__" + functionName + arguments;

    } else if (packagePath.size() == 2) {
      // Two-part path: schema.package.function
      String schemaName = packagePath.get(0).toLowerCase();
      String packageName = packagePath.get(1);

      // Always keep schema prefix for consistency
      // (PostgreSQL search_path may not include the migration schema)
      return schemaName + "." + packageName + "__" + functionName + arguments;

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
   *
   * <p>Each part is normalized independently, so it matches the name the DDL migration gave the
   * column. Oracle's {@code "RUN_ID"} would otherwise reach PostgreSQL as a case-sensitive
   * quoted name and miss the {@code run_id} that was actually created.
   */
  private static String handleQualifiedColumn(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    // Build dotted path: part1.part2.part3...
    return parts.stream()
        .map(part -> IdentifierHelper.emit(part.id_expression().getText()))
        .collect(Collectors.joining(" . "));
  }

  /**
   * Handles a simple general_element_part (no dots).
   */
  private static String handleSimplePart(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b) {

    // Get transformation context for use throughout this method
    TransformationContext context = b.getContext();

    // Check if this is a function call (has function_argument*)
    if (partCtx.function_argument() != null && !partCtx.function_argument().isEmpty()) {
      // Simple function call: function(args)
      String functionName = getFunctionName(partCtx);
      String upperFunctionName = functionName.toUpperCase();

      // PHASE 1C: Check if this is a collection constructor (TABLE OF or VARRAY)
      // Oracle: num_list_t(10, 20, 30) → PostgreSQL: '[10, 20, 30]'::jsonb
      if (context != null) {
        me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition inlineType =
            context.resolveInlineType(functionName);

        if (inlineType != null && inlineType.isIndexedCollection()) {
          // IT'S A COLLECTION CONSTRUCTOR!
          return transformCollectionConstructor(partCtx, b, inlineType);
        }
      }

      // PHASE 1C.5 + 1D: Check if this is collection element access (array or map)
      // Oracle: v_nums(1) → PostgreSQL: (v_nums->0)::numeric (array, 1-based → 0-based)
      // Oracle: v_map('key') → PostgreSQL: (v_map->>'key')::text (map)
      // NOTE: This must be checked BEFORE treating it as a regular function call
      if (context != null && !b.isInAssignmentTarget()) {
        String elementAccess = tryTransformCollectionElementAccess(partCtx, b, context);
        if (elementAccess != null) {
          return elementAccess;
        }
      }

      // PHASE 4: Check if this is UNQUALIFIED package variable collection access (Complex Types)
      // Oracle: g_array(1) (inside package function) → (pkg__get_g_array()->>0)::type
      // NOTE: This handles the unqualified case; qualified cases (pkg.g_array(1)) go through handleDotNavigation
      if (context != null && !b.isInAssignmentTarget() && context.isInPackageMember()) {
        String pkgCollectionAccess = tryTransformUnqualifiedPackageVariableCollectionAccess(partCtx, b, context);
        if (pkgCollectionAccess != null) {
          return pkgCollectionAccess;
        }
      }

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
        case "REGEXP_REPLACE":
        case "REGEXP_SUBSTR":
        case "REGEXP_INSTR":
        case "REGEXP_COUNT":
        case "REGEXP_LIKE":
          return StringFunctionTransformer.transform(upperFunctionName, partCtx, b);
        default:
          // Not a special function - proceed with normal handling
          break;
      }

      String arguments = getFunctionArguments(partCtx, b);

      // Apply schema qualification if context available
      // Oracle implicit schema resolution: unqualified function → current schema
      // PostgreSQL uses search_path which may not include the current schema
      // Reuse context and upperFunctionName from above
      if (context != null && !functionName.contains(".")) {
        // Check if this is a built-in function that should NOT be schema-qualified
        // Built-in PostgreSQL functions (COALESCE, UPPER, COUNT, etc.) are in pg_catalog
        // which is always in search_path, so they must remain unqualified
        if (!isKnownBuiltinFunction(upperFunctionName)) {
          // PACKAGE-LOCAL FUNCTION RESOLUTION:
          // When inside a package member, check if this unqualified call refers to
          // a function in the same package (public or private).
          // Example: mini2 inside minitest package → user_robert.minitest__mini2
          if (context.isInPackageMember()) {
            String currentPackage = context.getCurrentPackageName();
            String schema = context.getCurrentSchema();

            // Check if this function exists in the current package
            if (context.isPackageFunction(schema, currentPackage, functionName)) {
              // Package-local call: qualify with schema.package__function
              functionName = schema.toLowerCase() + "." + currentPackage.toLowerCase() + "__" + functionName.toLowerCase();
              return functionName + arguments;
            }
          }

          // User-defined function (not in current package) → qualify with current schema only
          functionName = context.getCurrentSchema().toLowerCase() + "." + functionName.toLowerCase();
        }
        // else: Built-in function → leave unqualified (PostgreSQL will find it in pg_catalog)
      }

      return functionName + arguments;
    }

    // Check for Oracle pseudo-columns and special constants that need transformation
    // Delimiters are stripped first: "SYSDATE" is the same pseudo-column as SYSDATE, and a
    // quoted name must not reach a lookup key with its quote characters still attached.
    String identifier = IdentifierHelper.unquote(partCtx.getText());
    String upperIdentifier = identifier.toUpperCase();

    // SYSDATE → CURRENT_TIMESTAMP
    // Oracle: SYSDATE returns current date and time
    // PostgreSQL: CURRENT_TIMESTAMP is the equivalent
    if ("SYSDATE".equals(upperIdentifier)) {
      return "CURRENT_TIMESTAMP";
    }

    // SYSTIMESTAMP → CURRENT_TIMESTAMP
    // Oracle: SYSTIMESTAMP returns current timestamp with time zone
    // PostgreSQL: CURRENT_TIMESTAMP is the equivalent
    if ("SYSTIMESTAMP".equals(upperIdentifier)) {
      return "CURRENT_TIMESTAMP";
    }

    // Check for unqualified package variable reference
    // In Oracle, within a package function, you can reference package variables directly:
    // Oracle: RETURN g_counter;  (not counter_pkg.g_counter)
    // PostgreSQL: RETURN hr.counter_pkg__get_g_counter();
    // Note: Oracle identifiers are case-insensitive, so we normalize to lowercase
    // Reuse context from method start (line 498)
    if (!b.isInAssignmentTarget() && context != null && context.isInPackageMember()) {
      String currentPackageName = context.getCurrentPackageName();
      String identifierLower = identifier.toLowerCase();  // Case-insensitive match
      if (context.isPackageVariable(currentPackageName, identifierLower)) {
        // Transform unqualified variable reference to getter call
        return context.getPackageVariableGetter(currentPackageName, identifierLower);
      }
    }

    // A bare name may be a routine Oracle allows to be called without parentheses. The detector
    // gives columns in scope, local variables and package variables precedence, so this only
    // fires on a positive metadata match and otherwise leaves the identifier untouched.
    String parameterlessCall = ParameterlessFunctionDetector.detect(List.of(partCtx), b);
    if (parameterlessCall != null) {
      return parameterlessCall;
    }

    // Simple identifier - column, local variable or parameter. Normalized so a quoted Oracle
    // name matches what the DDL migration created; unquoted names pass through unchanged.
    return IdentifierHelper.emit(partCtx.getText());
  }

  /**
   * Extracts function name from a general_element_part.
   */
  private static String getFunctionName(PlSqlParser.General_element_partContext partCtx) {
    if (partCtx.id_expression() == null) {
      throw new TransformationException("Function part missing id_expression");
    }
    return IdentifierHelper.unquote(partCtx.id_expression().getText());
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
        objectPath.append(IdentifierHelper.emit(parts.get(i).id_expression().getText()));
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

    String firstPart = IdentifierHelper.unquote(parts.get(startIdx).id_expression().getText());
    String secondPart = IdentifierHelper.unquote(parts.get(startIdx + 1).id_expression().getText());

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
   * Handles RECORD field access on RHS (Phase 1B.5).
   *
   * <p>Transforms RECORD field access to PostgreSQL jsonb operations:
   * <ul>
   *   <li>Simple: {@code v.field} → {@code (v->>'field')::type}</li>
   *   <li>Nested: {@code v.addr.city} → {@code (v->'addr'->>'city')::type}</li>
   * </ul>
   *
   * <p>Uses deterministic variable scope lookup to identify RECORD variables.
   *
   * @param parts All dot-separated parts [variable, field1, field2, ...]
   * @param b PostgreSQL code builder
   * @param varDef Variable definition (KNOWN to be RECORD type)
   * @return Transformed jsonb field access expression with type cast
   */
  private static String handleInlineTypeFieldAccess(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b,
      me.christianrobert.orapgsync.transformer.context.TransformationContext.VariableDefinition varDef) {

    if (parts.size() < 2) {
      throw new me.christianrobert.orapgsync.transformer.context.TransformationException(
          "Inline type field access requires at least 2 parts: variable.field");
    }

    // parts[0] = variable name
    // parts[1..n] = field path

    String variableName = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
    me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition inlineType = varDef.getInlineType();

    StringBuilder result = new StringBuilder();

    // Build JSON path access
    // For nested access: variable->'field1'->'field2'->>'field3'
    // Last field uses ->> (extract as text), intermediate fields use -> (keep as jsonb)

    result.append(variableName);

    // Track current type as we navigate nested fields
    me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition currentType = inlineType;
    me.christianrobert.orapgsync.transformer.inline.FieldDefinition finalField = null;

    for (int i = 1; i < parts.size(); i++) {
      String fieldName = IdentifierHelper.unquote(parts.get(i).id_expression().getText());
      boolean isLastField = (i == parts.size() - 1);

      if (isLastField) {
        // Last field: use ->> to extract as text
        result.append("->>'").append(fieldName).append("'");

        // Look up final field for type casting
        if (currentType != null) {
          finalField = findField(currentType, fieldName);
        }
      } else {
        // Intermediate field: use -> to keep as jsonb
        result.append("->'").append(fieldName).append("'");

        // Try to resolve nested type for next iteration
        // (This enables proper type resolution for deeply nested access)
        if (currentType != null) {
          me.christianrobert.orapgsync.transformer.inline.FieldDefinition field = findField(currentType, fieldName);
          if (field != null && field.getOracleType() != null) {
            // Check if this field is itself a RECORD type
            // For Phase 1B.5, we support nested RECORD field access
            TransformationContext context = b.getContext();
            me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition nestedType =
                context.resolveInlineType(field.getOracleType());
            if (nestedType != null && nestedType.isRecord()) {
              currentType = nestedType;
            } else {
              // Not a nested RECORD - can't navigate further
              currentType = null;
            }
          } else {
            currentType = null;
          }
        }
      }
    }

    // Type casting: Cast to the final field's PostgreSQL type
    if (finalField != null) {
      // We know the field type - add cast
      result.insert(0, "( ");
      result.append(" )::").append(finalField.getPostgresType());
    } else {
      // Field type unknown - wrap in parentheses but no cast
      // PostgreSQL will infer text type from ->> operator
      result.insert(0, "( ");
      result.append(" )");
    }

    return result.toString();
  }

  /**
   * Finds an inline RECORD type that contains a field with the given name.
   * This is a heuristic for Phase 1B to disambiguate field access without variable scope tracking.
   *
   * @param context Transformation context
   * @param fieldName Field name to search for (case-insensitive)
   * @return InlineTypeDefinition with matching field, or null if none found
   */
  private static InlineTypeDefinition findInlineTypeWithField(
      TransformationContext context,
      String fieldName) {

    // This is a simplified heuristic - in a real implementation, we'd track variable declarations
    // and their types in a scope stack. For Phase 1B, we assume that if a field name appears
    // in ANY registered RECORD type, it's likely field access on that type.

    // Note: This may cause false positives if table columns and RECORD fields have the same name
    // TODO Phase 1B.5: Implement variable scope tracking to eliminate ambiguity

    // For Phase 1B, return null to avoid false positives
    // We'll rely on the assignment statement transformation for LHS field access
    // RHS field access will need to be addressed in Phase 1B.5 with proper variable tracking
    return null;
  }

  /**
   * Tries to transform package variable field access to jsonb operations (Phase: Complex Types).
   *
   * <p>Detects and transforms package variable RECORD field access patterns:
   * <ul>
   *   <li>Unqualified (inside package): {@code g_rec.field} → {@code (pkg__get_g_rec()->>'field')::type}</li>
   *   <li>Package-qualified: {@code pkg.g_rec.field} → {@code (pkg__get_g_rec()->>'field')::type}</li>
   *   <li>Schema-qualified: {@code schema.pkg.g_rec.field} → {@code (schema.pkg__get_g_rec()->>'field')::type}</li>
   *   <li>Nested fields: {@code pkg.g_rec.addr.city} → {@code (pkg__get_g_rec()->'addr'->>'city')::type}</li>
   * </ul>
   *
   * @param parts Dot-separated parts of the expression
   * @param b PostgreSQL code builder
   * @return Transformed jsonb field access expression, or null if not a package variable field access
   */
  private static String tryTransformPackageVariableFieldAccess(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    TransformationContext context = b.getContext();
    if (context == null) {
      return null;
    }

    // Determine the pattern based on number of parts and context
    String schemaPrefix;
    String packageName;
    String variableName;
    int fieldStartIndex;

    if (parts.size() >= 2) {
      // Try Pattern 1: Unqualified inside package function (g_rec.field)
      // Only applies when we're inside a package member
      if (context.isInPackageMember()) {
        String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
        String currentPackage = context.getCurrentPackageName();

        if (currentPackage != null && b.isPackageVariable(currentPackage, firstPart)) {
          // Check if it's a complex type
          PackageContext pkgContext = context.getPackageContext(currentPackage);
          if (pkgContext != null) {
            PackageContext.PackageVariable pkgVar = pkgContext.getVariable(firstPart);
            if (pkgVar != null) {
              InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
              if (inlineType != null && inlineType.isRecord()) {
                // IT'S UNQUALIFIED PACKAGE VARIABLE FIELD ACCESS!
                schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
                packageName = currentPackage;
                variableName = firstPart;
                fieldStartIndex = 1;
                return buildPackageVariableFieldAccess(
                    schemaPrefix, packageName, variableName, fieldStartIndex, parts, pkgContext, b);
              }
            }
          }
        }
      }
    }

    if (parts.size() >= 3) {
      // Try Pattern 2: Package-qualified (pkg.g_rec.field)
      String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
      String secondPart = IdentifierHelper.unquote(parts.get(1).id_expression().getText());

      if (b.isPackageVariable(firstPart, secondPart)) {
        PackageContext pkgContext = context.getPackageContext(firstPart);
        if (pkgContext != null) {
          PackageContext.PackageVariable pkgVar = pkgContext.getVariable(secondPart);
          if (pkgVar != null) {
            InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
            if (inlineType != null && inlineType.isRecord()) {
              // IT'S PACKAGE-QUALIFIED VARIABLE FIELD ACCESS!
              schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
              packageName = firstPart;
              variableName = secondPart;
              fieldStartIndex = 2;
              return buildPackageVariableFieldAccess(
                  schemaPrefix, packageName, variableName, fieldStartIndex, parts, pkgContext, b);
            }
          }
        }
      }
    }

    if (parts.size() >= 4) {
      // Try Pattern 3: Schema-qualified (schema.pkg.g_rec.field)
      String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
      String secondPart = IdentifierHelper.unquote(parts.get(1).id_expression().getText());
      String thirdPart = IdentifierHelper.unquote(parts.get(2).id_expression().getText());
      String currentSchema = context.getCurrentSchema();

      // Check if first part is the current schema
      if (firstPart.equalsIgnoreCase(currentSchema) && b.isPackageVariable(secondPart, thirdPart)) {
        PackageContext pkgContext = context.getPackageContext(secondPart);
        if (pkgContext != null) {
          PackageContext.PackageVariable pkgVar = pkgContext.getVariable(thirdPart);
          if (pkgVar != null) {
            InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
            if (inlineType != null && inlineType.isRecord()) {
              // IT'S SCHEMA-QUALIFIED VARIABLE FIELD ACCESS!
              schemaPrefix = currentSchema.toLowerCase() + ".";
              packageName = secondPart;
              variableName = thirdPart;
              fieldStartIndex = 3;
              return buildPackageVariableFieldAccess(
                  schemaPrefix, packageName, variableName, fieldStartIndex, parts, pkgContext, b);
            }
          }
        }
      }
    }

    // Not a package variable field access
    return null;
  }

  /**
   * Builds the PostgreSQL expression for package variable field access.
   *
   * <p>Transforms to: {@code (schema.pkg__get_varname()->>'field')::type}
   * or for nested: {@code (schema.pkg__get_varname()->'field1'->>'field2')::type}
   *
   * @param schemaPrefix Schema prefix with dot (e.g., "hr.")
   * @param packageName Package name
   * @param variableName Variable name
   * @param fieldStartIndex Index where field path starts in parts
   * @param parts All dot-separated parts
   * @param pkgContext Package context for type lookup
   * @param b PostgreSQL code builder
   * @return Transformed PostgreSQL expression
   */
  private static String buildPackageVariableFieldAccess(
      String schemaPrefix,
      String packageName,
      String variableName,
      int fieldStartIndex,
      List<PlSqlParser.General_element_partContext> parts,
      PackageContext pkgContext,
      PostgresCodeBuilder b) {

    // Build getter call
    String getterCall = schemaPrefix + packageName.toLowerCase() + "__get_" + variableName.toLowerCase() + "()";

    // Get variable's inline type for field resolution
    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(variableName);
    InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());

    StringBuilder result = new StringBuilder();
    result.append("( ").append(getterCall);

    // Navigate through fields
    InlineTypeDefinition currentType = inlineType;
    FieldDefinition finalField = null;

    for (int i = fieldStartIndex; i < parts.size(); i++) {
      String fieldName = IdentifierHelper.unquote(parts.get(i).id_expression().getText());
      boolean isLastField = (i == parts.size() - 1);

      if (isLastField) {
        // Last field: use ->> to extract as text
        result.append("->>'").append(fieldName).append("'");

        // Look up final field for type casting
        if (currentType != null) {
          finalField = findField(currentType, fieldName);
        }
      } else {
        // Intermediate field: use -> to keep as jsonb
        result.append("->'").append(fieldName).append("'");

        // Try to resolve nested type for next iteration
        if (currentType != null) {
          FieldDefinition field = findField(currentType, fieldName);
          if (field != null && field.getOracleType() != null) {
            // Check if this field is itself a RECORD type (could be package-level or inline)
            InlineTypeDefinition nestedType = pkgContext.getType(field.getOracleType());
            if (nestedType == null) {
              // Try context-level types
              TransformationContext context = b.getContext();
              if (context != null) {
                nestedType = context.resolveInlineType(field.getOracleType());
              }
            }
            if (nestedType != null && nestedType.isRecord()) {
              currentType = nestedType;
            } else {
              currentType = null;
            }
          } else {
            currentType = null;
          }
        }
      }
    }

    result.append(" )");

    // Add type cast if we know the field type
    if (finalField != null) {
      result.append("::").append(finalField.getPostgresType());
    }

    return result.toString();
  }

  /**
   * Tries to transform package variable collection element access to jsonb operations (Phase 4: Complex Types).
   *
   * <p>Detects and transforms package variable collection access patterns where the access
   * looks like a function call but is actually element access on a collection-typed variable:
   * <ul>
   *   <li>Package-qualified: {@code pkg.g_array(1)} → {@code (pkg__get_g_array()->>0)::type}</li>
   *   <li>Unqualified (inside package): {@code g_array(1)} → {@code (pkg__get_g_array()->>0)::type}</li>
   *   <li>Schema-qualified: {@code schema.pkg.g_array(1)} → {@code (schema.pkg__get_g_array()->>0)::type}</li>
   *   <li>Map access: {@code pkg.g_map('key')} → {@code (pkg__get_g_map()->>'key')}</li>
   * </ul>
   *
   * @param parts Dot-separated parts of the expression (last part has function arguments)
   * @param b PostgreSQL code builder
   * @return Transformed jsonb collection access expression, or null if not a package variable collection access
   */
  private static String tryTransformPackageVariableCollectionAccess(
      List<PlSqlParser.General_element_partContext> parts,
      PostgresCodeBuilder b) {

    TransformationContext context = b.getContext();
    if (context == null) {
      return null;
    }

    // Last part is the one with function arguments (which might be collection element access)
    PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
    String memberName = IdentifierHelper.unquote(lastPart.id_expression().getText());

    // Get the index/key argument
    List<PlSqlParser.Function_argumentContext> funcArgList = lastPart.function_argument();
    if (funcArgList == null || funcArgList.isEmpty()) {
      return null;
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.size() != 1) {
      return null;  // Collection access has exactly one argument
    }

    String argValue = b.visit(arguments.get(0).expression());

    // Determine the pattern based on number of parts and context
    String schemaPrefix;
    String packageName;
    String variableName = memberName;

    if (parts.size() == 1) {
      // Single part with args: g_array(1) - unqualified inside package
      if (!context.isInPackageMember()) {
        return null;  // Must be inside a package
      }
      packageName = context.getCurrentPackageName();
      schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
    } else if (parts.size() == 2) {
      // Two parts: pkg.g_array(1) - package-qualified
      packageName = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
      schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";
    } else if (parts.size() == 3) {
      // Three parts: schema.pkg.g_array(1) - schema-qualified
      String firstPart = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
      String currentSchema = context.getCurrentSchema();

      if (!firstPart.equalsIgnoreCase(currentSchema)) {
        return null;  // Cross-schema not supported
      }
      schemaPrefix = currentSchema.toLowerCase() + ".";
      packageName = IdentifierHelper.unquote(parts.get(1).id_expression().getText());
    } else {
      return null;  // Too many parts for collection access
    }

    // Check if this is actually a package variable (not a function)
    if (!b.isPackageVariable(packageName, variableName)) {
      return null;
    }

    // Get the collection type
    PackageContext pkgContext = context.getPackageContext(packageName);
    if (pkgContext == null) {
      return null;
    }

    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(variableName);
    if (pkgVar == null) {
      return null;
    }

    InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
    if (inlineType == null || !inlineType.isCollection()) {
      return null;  // Not a collection type
    }

    // IT'S PACKAGE VARIABLE COLLECTION ACCESS!
    return buildPackageVariableCollectionAccess(
        schemaPrefix, packageName, variableName, argValue, inlineType, b);
  }

  /**
   * Builds the PostgreSQL expression for package variable collection element access.
   *
   * <p>Transforms to:
   * <ul>
   *   <li>Array (TABLE OF, VARRAY, INDEX BY PLS_INTEGER): {@code (schema.pkg__get_varname()->>0)::type}</li>
   *   <li>Map (INDEX BY VARCHAR2): {@code (schema.pkg__get_varname()->>'key')}</li>
   * </ul>
   *
   * @param schemaPrefix Schema prefix with dot (e.g., "hr.")
   * @param packageName Package name
   * @param variableName Variable name
   * @param argValue Index/key argument (already transformed)
   * @param inlineType Inline type definition for the collection
   * @param b PostgreSQL code builder
   * @return Transformed PostgreSQL expression
   */
  private static String buildPackageVariableCollectionAccess(
      String schemaPrefix,
      String packageName,
      String variableName,
      String argValue,
      InlineTypeDefinition inlineType,
      PostgresCodeBuilder b) {

    // Build getter call
    String getterCall = schemaPrefix + packageName.toLowerCase() + "__get_" + variableName.toLowerCase() + "()";

    if (inlineType.isAssociativeArray()) {
      // INDEX BY (both VARCHAR2 and PLS_INTEGER) → object key access with ->>
      // Stored as object: {"0": val0, "1": val1, ...} or {"key1": val1, ...}
      if (isStringIndexKeyType(inlineType.getIndexKeyType())) {
        // String key: direct access, no index conversion
        return "( " + getterCall + " ->> " + argValue + " )";
      } else {
        // Numeric key: convert 1-based to 0-based and use as string key
        return buildNumericObjectKeyAccessForPackageVar(getterCall, argValue, inlineType);
      }
    } else {
      // TABLE OF, VARRAY → real jsonb array access
      return buildNumericArrayAccessForPackageVar(getterCall, argValue, inlineType);
    }
  }

  /**
   * Builds PostgreSQL jsonb array access expression for package variables with 1-based to 0-based index conversion.
   *
   * @param getterCall The getter function call (e.g., "hr.pkg__get_g_array()")
   * @param argValue Index argument expression (already transformed)
   * @param inlineType Inline type definition for the collection
   * @return PostgreSQL jsonb array access expression with type cast
   */
  private static String buildNumericArrayAccessForPackageVar(
      String getterCall,
      String argValue,
      InlineTypeDefinition inlineType) {

    // Check if argument is a simple numeric literal
    boolean isNumericLiteral = argValue.matches("\\d+");

    String indexExpression;
    if (isNumericLiteral) {
      // Simple numeric literal: pkg.g_array(1) → getter()->0
      int oracleIndex = Integer.parseInt(argValue);
      int postgresIndex = oracleIndex - 1;
      indexExpression = String.valueOf(postgresIndex);
    } else {
      // Variable or expression: pkg.g_array(i) → getter()->(i-1)::int
      indexExpression = "( " + argValue + " - 1 )::int";
    }

    // Get PostgreSQL element type for casting
    String oracleElementType = inlineType.getElementType();
    String pgElementType;
    if (oracleElementType != null) {
      pgElementType = me.christianrobert.orapgsync.core.tools.TypeConverter.toPostgre(oracleElementType);
    } else {
      pgElementType = "text";
    }

    // Build array access with proper type casting:
    // (getter()->>index)::type
    return "( " + getterCall + "->>" + indexExpression + " )::" + pgElementType;
  }

  /**
   * Builds PostgreSQL jsonb object key access for INDEX BY PLS_INTEGER package variables.
   *
   * <p>INDEX BY PLS_INTEGER types are stored as jsonb objects with string keys: {"0": val0, "1": val1}.
   * This method builds the access expression using string key syntax (->>key) with proper
   * 1-based to 0-based index conversion.
   *
   * @param getterCall The getter function call (e.g., "hr.pkg__get_g_array()")
   * @param argValue Index argument expression (already transformed)
   * @param inlineType Inline type definition for the collection
   * @return PostgreSQL jsonb object key access expression with type cast
   */
  private static String buildNumericObjectKeyAccessForPackageVar(
      String getterCall,
      String argValue,
      InlineTypeDefinition inlineType) {

    // Check if argument is a simple numeric literal
    boolean isNumericLiteral = argValue.matches("\\d+");

    String keyExpression;
    if (isNumericLiteral) {
      // Simple numeric literal: pkg.g_array(1) → getter()->>'0'
      int oracleIndex = Integer.parseInt(argValue);
      int postgresIndex = oracleIndex - 1;
      keyExpression = "'" + postgresIndex + "'";
    } else {
      // Variable or expression: pkg.g_array(i) → getter()->>((i-1)::text)
      keyExpression = "( " + argValue + " - 1 )::text";
    }

    // Get PostgreSQL element type for casting
    String oracleElementType = inlineType.getElementType();
    String pgElementType;
    if (oracleElementType != null) {
      pgElementType = me.christianrobert.orapgsync.core.tools.TypeConverter.toPostgre(oracleElementType);
    } else {
      pgElementType = "text";
    }

    // Build object key access with proper type casting:
    // (getter()->>key)::type
    return "( " + getterCall + " ->> " + keyExpression + " )::" + pgElementType;
  }

  /**
   * Tries to transform unqualified package variable collection access inside a package function.
   *
   * <p>Pattern: {@code g_array(1)} inside a package function → {@code (pkg__get_g_array()->>0)::type}
   *
   * <p>This handles the case where a collection-typed package variable is accessed without
   * the package name prefix (i.e., just the variable name with parentheses for element access).
   *
   * @param partCtx General element part context (identifier with function arguments)
   * @param b PostgreSQL code builder
   * @param context Transformation context (must be inside a package member)
   * @return Transformed jsonb collection access expression, or null if not a package variable
   */
  private static String tryTransformUnqualifiedPackageVariableCollectionAccess(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b,
      TransformationContext context) {

    // Get variable name
    String variableName = IdentifierHelper.unquote(partCtx.id_expression().getText());

    // Get the index/key argument
    List<PlSqlParser.Function_argumentContext> funcArgList = partCtx.function_argument();
    if (funcArgList == null || funcArgList.isEmpty()) {
      return null;
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.size() != 1) {
      return null;  // Collection access has exactly one argument
    }

    // Check if this is a package variable in the current package
    String currentPackage = context.getCurrentPackageName();
    if (!b.isPackageVariable(currentPackage, variableName)) {
      return null;
    }

    // Get the collection type
    PackageContext pkgContext = context.getPackageContext(currentPackage);
    if (pkgContext == null) {
      return null;
    }

    PackageContext.PackageVariable pkgVar = pkgContext.getVariable(variableName);
    if (pkgVar == null) {
      return null;
    }

    InlineTypeDefinition inlineType = pkgContext.getType(pkgVar.getDataType());
    if (inlineType == null || !inlineType.isCollection()) {
      return null;  // Not a collection type
    }

    // IT'S UNQUALIFIED PACKAGE VARIABLE COLLECTION ACCESS!
    String argValue = b.visit(arguments.get(0).expression());
    String schemaPrefix = context.getCurrentSchema().toLowerCase() + ".";

    return buildPackageVariableCollectionAccess(
        schemaPrefix, currentPackage, variableName, argValue, inlineType, b);
  }

  /**
   * Finds a field definition by name in an inline type definition.
   *
   * @param inlineType Inline type definition
   * @param fieldName Field name to find (case-insensitive)
   * @return FieldDefinition or null if not found
   */
  private static me.christianrobert.orapgsync.transformer.inline.FieldDefinition findField(
      me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition inlineType,
      String fieldName) {

    if (inlineType.getFields() == null) {
      return null;
    }

    String normalizedFieldName = fieldName.toLowerCase();

    for (me.christianrobert.orapgsync.transformer.inline.FieldDefinition field : inlineType.getFields()) {
      if (field.getFieldName().toLowerCase().equals(normalizedFieldName)) {
        return field;
      }
    }

    return null;
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

  /**
   * Transforms collection constructor calls to JSON array literals (Phase 1C).
   *
   * <p>Oracle collection constructors:
   * <pre>
   * num_list_t(10, 20, 30)           → '[10, 20, 30]'::jsonb
   * codes_t('A001', 'B002', 'C003')  → '["A001", "B002", "C003"]'::jsonb
   * empty_list_t()                   → '[]'::jsonb
   * </pre>
   *
   * <p>Transformation logic:
   * <ol>
   *   <li>Extract all constructor arguments</li>
   *   <li>Transform each argument expression</li>
   *   <li>Build JSON array literal</li>
   *   <li>Add ::jsonb cast</li>
   * </ol>
   *
   * @param partCtx General element part context (contains constructor call)
   * @param b PostgreSQL code builder
   * @param inlineType Inline type definition for the collection
   * @return JSON array literal with jsonb cast
   */
  private static String transformCollectionConstructor(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b,
      me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition inlineType) {

    // Extract constructor arguments
    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      // Empty constructor: num_list_t() → '[]'::jsonb
      return "'[]'::jsonb";
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.isEmpty()) {
      // Empty constructor
      return "'[]'::jsonb";
    }

    // Transform each argument and build JSON array
    StringBuilder jsonArray = new StringBuilder();
    jsonArray.append("'[ ");

    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) {
        jsonArray.append(" , ");
      }

      // Transform the argument expression
      PlSqlParser.ArgumentContext argCtx = arguments.get(i);
      if (argCtx.expression() != null) {
        String argValue = b.visit(argCtx.expression());

        // Check if argument is a string literal (needs quotes in JSON)
        // String literals in SQL are enclosed in single quotes
        if (isStringLiteral(argValue)) {
          // String literal: 'value' → "value" (JSON double quotes)
          // Remove SQL quotes and add JSON quotes
          String unquoted = argValue.substring(1, argValue.length() - 1);
          jsonArray.append("\"").append(unquoted).append("\"");
        } else {
          // Numeric or other: pass through
          jsonArray.append(argValue);
        }
      }
    }

    jsonArray.append(" ]'::jsonb");

    return jsonArray.toString();
  }

  /**
   * Checks if a value is a string literal (enclosed in single quotes).
   *
   * @param value Value to check
   * @return true if value is a string literal
   */
  private static boolean isStringLiteral(String value) {
    if (value == null || value.length() < 2) {
      return false;
    }

    String trimmed = value.trim();
    return trimmed.startsWith("'") && trimmed.endsWith("'");
  }

  /**
   * Checks if the INDEX BY key type is string-based.
   *
   * <p>Oracle associative arrays (INDEX BY) can be keyed by either:</p>
   * <ul>
   *   <li><strong>String types:</strong> VARCHAR2, VARCHAR, STRING, CHAR</li>
   *   <li><strong>Numeric types:</strong> PLS_INTEGER, BINARY_INTEGER, NUMBER</li>
   * </ul>
   *
   * <p>This determines the PostgreSQL jsonb access operator:</p>
   * <ul>
   *   <li>String keys → {@code ->>} (map access, returns text)</li>
   *   <li>Numeric keys → {@code ->} (array access with 1-based to 0-based conversion)</li>
   * </ul>
   *
   * @param indexKeyType Index key type from InlineTypeDefinition (e.g., "VARCHAR2", "PLS_INTEGER")
   * @return true if string-based key type
   */
  private static boolean isStringIndexKeyType(String indexKeyType) {
    if (indexKeyType == null) {
      // Default to numeric if not specified
      return false;
    }

    String upperType = indexKeyType.toUpperCase();

    // String-based INDEX BY key types
    return upperType.startsWith("VARCHAR2") ||
           upperType.startsWith("VARCHAR") ||
           upperType.startsWith("STRING") ||
           upperType.startsWith("CHAR");

    // Numeric types (PLS_INTEGER, BINARY_INTEGER, NUMBER) return false
  }

  /**
   * Builds PostgreSQL jsonb array access expression with 1-based to 0-based index conversion.
   *
   * <p>Oracle uses 1-based indexing, PostgreSQL jsonb uses 0-based indexing.</p>
   *
   * <h3>Transformations:</h3>
   * <ul>
   *   <li>Numeric literal: {@code v_nums(1)} → {@code (v_nums->0)}</li>
   *   <li>Variable: {@code v_nums(i)} → {@code (v_nums->(i-1))}</li>
   *   <li>Expression: {@code v_nums(i+5)} → {@code (v_nums->((i+5)-1))}</li>
   *   <li>String literal: {@code v_nums('10')} → {@code (v_nums->9)} (converted to int)</li>
   * </ul>
   *
   * @param variableName Collection variable name
   * @param argValue Index argument expression (already transformed)
   * @return PostgreSQL jsonb array access expression
   */
  private static String buildNumericArrayAccess(String variableName, String argValue,
                                                 me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition inlineType) {
    // Check if argument is a simple numeric literal
    boolean isNumericLiteral = argValue.matches("\\d+");

    String indexExpression;
    if (isNumericLiteral) {
      // Simple numeric literal: v_nums(1) → v_nums->0
      int oracleIndex = Integer.parseInt(argValue);
      int postgresIndex = oracleIndex - 1;
      indexExpression = String.valueOf(postgresIndex);
    } else {
      // Variable, expression, or string literal: v_nums(i) → v_nums->((i - 1)::int)
      // Cast to int because -> operator requires integer index
      indexExpression = "( " + argValue + " - 1 )::int";
    }

    // Get PostgreSQL element type for casting
    String oracleElementType = inlineType.getElementType();
    String pgElementType;
    if (oracleElementType != null) {
      // Convert Oracle type to PostgreSQL type (NUMBER → numeric, VARCHAR2 → text, etc.)
      pgElementType = me.christianrobert.orapgsync.core.tools.TypeConverter.toPostgre(oracleElementType);
    } else {
      // Fallback to text if element type unknown
      pgElementType = "text";
    }

    // Build array access with proper type casting:
    // Use ->> operator (returns text) and cast to element type
    // This ensures the result can be used in arithmetic operations, comparisons, etc.
    // Examples:
    //   v_nums(1) → (v_nums->>0)::numeric
    //   v_nums(i) → (v_nums->>((i - 1)::int))::numeric
    //   v_items(1) → (v_items->>0)::text
    return "( " + variableName + "->>" + indexExpression + " )::" + pgElementType;
  }

  /**
   * DEPRECATED: No longer used. Replaced with deterministic variable scope lookup.
   *
   * <p>This heuristic-based approach was error-prone and caused bugs like:
   * <ul>
   *   <li>Function calls with underscores (e.g., calculate_bonus) misidentified as variables</li>
   *   <li>Package functions (e.g., emp_pkg__function) misidentified as variables</li>
   * </ul>
   *
   * <p>Replaced by: {@link TransformationContext#isLocalVariable(String)}
   * which uses deterministic scope-based lookup.
   *
   * @deprecated Use context.isLocalVariable() instead (deterministic, scope-based)
   * @param identifier Identifier to check
   * @return true if it looks like a variable name
   */
  @Deprecated
  private static boolean looksLikeVariable(String identifier) {
    if (identifier == null || identifier.isEmpty()) {
      return false;
    }

    // Common Oracle variable naming patterns:
    // 1. Starts with v_ (very common): v_nums, v_map, v_result
    if (identifier.toLowerCase().startsWith("v_")) {
      return true;
    }

    // 2. Contains underscore (common for multi-word variables): emp_list, dept_map, my_var
    if (identifier.contains("_")) {
      return true;
    }

    // 3. Starts with lowercase (common for simple variables): nums, map, result
    // But exclude single character (often parameters like 'i', 'x')
    if (identifier.length() > 1 && Character.isLowerCase(identifier.charAt(0))) {
      return true;
    }

    // If none of the above, likely NOT a variable (probably a function)
    return false;
  }

  /**
   * Checks if a package name is an Oracle compatibility package.
   * These packages are provided in the oracle_compat schema and should not be
   * prefixed with the current schema during transformation.
   *
   * <p>Supported packages:
   * <ul>
   *   <li>HTP - Hypertext Procedures (web output buffer)</li>
   *   <li>HTF - Hypertext Functions (return HTML strings)</li>
   *   <li>OWA - Oracle Web Agent (request initialization)</li>
   *   <li>OWA_UTIL - OWA Utilities (CGI environment, redirects)</li>
   *   <li>OWA_COOKIE - Cookie handling</li>
   *   <li>DBMS_OUTPUT - Debug output</li>
   *   <li>DBMS_UTILITY - Utility functions</li>
   *   <li>DBMS_LOB - Large object handling</li>
   *   <li>UTL_FILE - File I/O</li>
   * </ul>
   *
   * @param packageName Package name to check (case-insensitive)
   * @return true if it's an Oracle compatibility package
   */
  private static boolean isOracleCompatibilityPackage(String packageName) {
    if (packageName == null) {
      return false;
    }
    String upper = packageName.toUpperCase();
    return switch (upper) {
      case "HTP", "HTF", "OWA", "OWA_UTIL", "OWA_COOKIE",
           "DBMS_OUTPUT", "DBMS_UTILITY", "DBMS_LOB", "UTL_FILE" -> true;
      default -> false;
    };
  }

  /**
   * Checks if a function name is a known Oracle/PostgreSQL built-in function.
   * This prevents false positives where built-in functions are mistaken for collection element access.
   *
   * @param upperFunctionName Function name in uppercase
   * @return true if it's a known built-in function
   */
  private static boolean isKnownBuiltinFunction(String upperFunctionName) {
    // Common Oracle/PostgreSQL string functions
    switch (upperFunctionName) {
      // String functions
      case "TRIM":
      case "LTRIM":
      case "RTRIM":
      case "UPPER":
      case "LOWER":
      case "INITCAP":
      case "LENGTH":
      case "SUBSTR":
      case "SUBSTRING":
      case "REPLACE":
      case "TRANSLATE":
      case "INSTR":
      case "LPAD":
      case "RPAD":
      case "CHR":
      case "ASCII":
      case "CONCAT":
      // Numeric functions
      case "ABS":
      case "CEIL":
      case "FLOOR":
      case "ROUND":
      case "TRUNC":
      case "MOD":
      case "POWER":
      case "SQRT":
      case "EXP":
      case "LN":
      case "LOG":
      // Date functions
      case "SYSDATE":
      case "SYSTIMESTAMP":
      case "CURRENT_DATE":
      case "CURRENT_TIMESTAMP":
      case "ADD_MONTHS":
      case "MONTHS_BETWEEN":
      case "LAST_DAY":
      case "NEXT_DAY":
      case "EXTRACT":
      // Conversion functions
      case "TO_CHAR":
      case "TO_NUMBER":
      case "TO_DATE":
      case "TO_TIMESTAMP":
      case "CAST":
      // Null handling
      case "NVL":
      case "NVL2":
      case "COALESCE":
      case "NULLIF":
      // Conditional
      case "DECODE":
      case "GREATEST":
      case "LEAST":
      // Aggregate functions
      case "COUNT":
      case "SUM":
      case "AVG":
      case "MIN":
      case "MAX":
      case "STDDEV":
      case "VARIANCE":
      // Regexp functions
      case "REGEXP_LIKE":
      case "REGEXP_REPLACE":
      case "REGEXP_SUBSTR":
      case "REGEXP_INSTR":
      case "REGEXP_COUNT":
        return true;
      default:
        return false;
    }
  }

  /**
   * Tries to transform collection element access (Phase 1C.5 + 1D).
   *
   * <p>Detects and transforms array/map element access patterns:
   * <ul>
   *   <li>Array (TABLE OF/VARRAY): v_nums(1) → (v_nums->0)::numeric (1-based → 0-based)</li>
   *   <li>Map (INDEX BY): v_map('key') → (v_map->>'key')::text</li>
   * </ul>
   *
   * <p>Detection logic:
   * <ol>
   *   <li>Check if identifier is a variable (not a type constructor)</li>
   *   <li>Check if it has exactly ONE argument (not multiple like constructor)</li>
   *   <li>Look up variable type in context</li>
   *   <li>Check if type is a collection (TABLE OF, VARRAY, INDEX BY)</li>
   * </ol>
   *
   * @param partCtx General element part context with function_argument
   * @param b PostgreSQL code builder
   * @param context Transformation context
   * @return Transformed element access, or null if not a collection element access
   */
  private static String tryTransformCollectionElementAccess(
      PlSqlParser.General_element_partContext partCtx,
      PostgresCodeBuilder b,
      TransformationContext context) {

    // Must have exactly ONE argument (multiple arguments = constructor or function call)
    List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
    if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
      return null;
    }

    PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
    if (arguments == null || arguments.size() != 1) {
      return null; // Must be exactly one argument for element access
    }

    // Get variable name
    String variableName = IdentifierHelper.unquote(partCtx.id_expression().getText());
    String upperName = variableName.toUpperCase();

    // Exclude known Oracle/PostgreSQL built-in functions
    // This prevents false positives where TRIM('text'), UPPER('text'), etc. are treated as collection access
    if (isKnownBuiltinFunction(upperName)) {
      return null;
    }

    // DETERMINISTIC VARIABLE LOOKUP (replaces heuristic detection)
    // Check if this identifier is a registered local variable
    me.christianrobert.orapgsync.transformer.context.TransformationContext.VariableDefinition varDef =
        context.lookupVariable(variableName);

    if (varDef == null) {
      // Not a local variable → Must be a function call or type constructor
      return null;
    }

    // Variable found! Check if it's a collection type
    if (!varDef.isCollection()) {
      // Variable exists but not a collection → Not element access
      // This could be a simple variable used in an expression
      return null;
    }

    // COLLECTION VARIABLE CONFIRMED
    // We now have definitive knowledge:
    // - It's a registered variable (not a function)
    // - It has a collection inline type
    // - We can safely transform as element access
    me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition inlineType = varDef.getInlineType();

    // Check argument pattern
    PlSqlParser.ArgumentContext arg = arguments.get(0);
    if (arg.expression() == null) {
      return null;
    }

    String argValue = b.visit(arg.expression());

    // TYPE-AWARE RESOLUTION (Phase 4 - Variable Scope Tracking)
    // Use actual collection type information instead of argument heuristics
    // This correctly handles:
    // - INDEX BY VARCHAR2 with variable keys: v_map(v_key) → map access
    // - INDEX BY PLS_INTEGER with string literals: v_array('10') → array access

    if (inlineType.isAssociativeArray()) {
      // INDEX BY collection - check key type to determine access pattern
      String indexKeyType = inlineType.getIndexKeyType();

      if (isStringIndexKeyType(indexKeyType)) {
        // INDEX BY VARCHAR2/STRING/CHAR → use ->> (map access, returns text)
        // No index conversion needed for string-keyed maps
        // Examples:
        //   v_map('key') → (v_map->>'key')
        //   v_map(v_key) → (v_map->>v_key)

        // Build map access expression
        return "( " + variableName + " ->> " + argValue + " )";
      } else {
        // INDEX BY PLS_INTEGER/BINARY_INTEGER → use -> (array access, returns jsonb)
        // Apply 1-based → 0-based index conversion
        // Examples:
        //   v_array(1) → (v_array->0)
        //   v_array(i) → (v_array->(i-1))
        //   v_array('10') → (v_array->9)  -- string literal converted to int

        return buildNumericArrayAccess(variableName, argValue, inlineType);
      }
    } else {
      // TABLE OF or VARRAY - always numeric array access
      // Apply 1-based → 0-based index conversion
      // Examples:
      //   v_nums(1) → (v_nums->0)
      //   v_nums(i) → (v_nums->(i-1))

      return buildNumericArrayAccess(variableName, argValue, inlineType);
    }
  }

  /**
   * Checks if a method name is a valid Oracle collection method.
   *
   * <p>Oracle collection methods supported:
   * - COUNT (no args) - Returns number of elements
   * - EXISTS(n) (with args) - Checks if element at index n exists
   * - FIRST (no args) - Returns first index (always 1 for Oracle)
   * - LAST (no args) - Returns last index (same as COUNT)
   * - DELETE(n) (with args) - Deletes element at index n
   *
   * @param methodName The method name (uppercase)
   * @param hasArguments Whether the method call has arguments
   * @return true if this is a recognized collection method with correct argument pattern
   */
  private static boolean isCollectionMethod(String methodName, boolean hasArguments) {
    switch (methodName) {
      case "COUNT":
      case "FIRST":
      case "LAST":
        // These methods never have arguments
        return !hasArguments;

      case "EXISTS":
      case "DELETE":
        // These methods always have arguments
        return hasArguments;

      default:
        return false;
    }
  }

  /**
   * Handles Oracle collection method calls and transforms them to PostgreSQL equivalents.
   *
   * <p>Transformations (for jsonb collections):
   * - v.COUNT → jsonb_array_length(v)
   * - v.EXISTS(i) → jsonb_typeof(v->(i-1)) IS NOT NULL
   * - v.FIRST → 1 (constant - Oracle arrays always start at 1)
   * - v.LAST → jsonb_array_length(v)
   * - v.DELETE(i) → v - (i-1) (remove element by index)
   *
   * @param variableName The collection variable name
   * @param methodName The collection method name (uppercase)
   * @param methodPart The AST part containing method call details
   * @param b PostgreSQL code builder
   * @return Transformed PostgreSQL expression
   */
  private static String handleCollectionMethod(
      String variableName,
      String methodName,
      PlSqlParser.General_element_partContext methodPart,
      PostgresCodeBuilder b) {

    switch (methodName) {
      case "COUNT":
        // v_nums.COUNT → jsonb_array_length(v_nums)
        return "jsonb_array_length( " + variableName + " )";

      case "FIRST":
        // v_nums.FIRST → 1 (Oracle collections always start at index 1)
        return "1";

      case "LAST":
        // v_nums.LAST → jsonb_array_length(v_nums)
        // Last index is same as count (1-based indexing)
        return "jsonb_array_length( " + variableName + " )";

      case "EXISTS":
        // v_nums.EXISTS(i) → jsonb_typeof(v_nums->(i-1)) IS NOT NULL
        // Extract argument and apply 1-based → 0-based conversion
        String existsArg = extractFirstArgument(methodPart, b);
        String zeroBasedIndex = convertToZeroBasedIndex(existsArg);
        return "jsonb_typeof( " + variableName + " -> " + zeroBasedIndex + " ) IS NOT NULL";

      case "DELETE":
        // v_nums.DELETE(i) → v_nums - (i-1)
        // PostgreSQL jsonb - operator removes element by index (0-based)
        String deleteArg = extractFirstArgument(methodPart, b);
        String deleteIndex = convertToZeroBasedIndex(deleteArg);
        return variableName + " - " + deleteIndex;

      default:
        throw new TransformationException("Unsupported collection method: " + methodName);
    }
  }

  /**
   * Extracts the first argument from a function call.
   *
   * @param methodPart The method part with arguments
   * @param b PostgreSQL code builder
   * @return Transformed argument expression
   */
  private static String extractFirstArgument(
      PlSqlParser.General_element_partContext methodPart,
      PostgresCodeBuilder b) {
    List<PlSqlParser.Function_argumentContext> funcArgList = methodPart.function_argument();
    if (funcArgList == null || funcArgList.isEmpty()) {
      throw new TransformationException("Collection method requires an argument");
    }

    // function_argument contains all arguments: '(' (argument (',' argument)*)? ')'
    PlSqlParser.Function_argumentContext funcArgCtx = funcArgList.get(0);
    List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();

    if (arguments == null || arguments.isEmpty()) {
      throw new TransformationException("Collection method requires an argument");
    }

    // Get first argument and transform its expression
    PlSqlParser.ArgumentContext firstArg = arguments.get(0);
    if (firstArg.expression() != null) {
      return b.visit(firstArg.expression());
    }

    throw new TransformationException("Invalid argument in collection method call");
  }

  /**
   * Converts a 1-based Oracle index to 0-based PostgreSQL index.
   *
   * <p>Transformation rules:
   * - Numeric literal: subtract 1 directly (e.g., "1" → "0", "5" → "4")
   * - Variable/expression: wrap in parentheses and subtract 1 (e.g., "i" → "(i-1)")
   *
   * @param indexExpr The 1-based index expression
   * @return The 0-based index expression
   */
  private static String convertToZeroBasedIndex(String indexExpr) {
    // Try to parse as integer literal
    try {
      int oracleIndex = Integer.parseInt(indexExpr.trim());
      int pgIndex = oracleIndex - 1;
      return String.valueOf(pgIndex);
    } catch (NumberFormatException e) {
      // Not a literal - it's a variable or expression
      // Wrap in parentheses for safe arithmetic and cast to integer
      // (-> operator requires integer index, not numeric)
      return "( " + indexExpr + " - 1 )::int";
    }
  }
}
