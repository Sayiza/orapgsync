package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.List;

/**
 * Transforms Oracle collection method calls to PostgreSQL jsonb equivalents.
 *
 * <p><strong>Supported Oracle collection methods:</strong>
 * <ul>
 *   <li>{@code v.COUNT} → {@code jsonb_array_length(v)}</li>
 *   <li>{@code v.EXISTS(i)} → {@code jsonb_typeof(v->(i-1)) IS NOT NULL}</li>
 *   <li>{@code v.FIRST} → {@code 1} (constant - Oracle arrays always start at 1)</li>
 *   <li>{@code v.LAST} → {@code jsonb_array_length(v)}</li>
 *   <li>{@code v.DELETE(i)} → {@code v - (i-1)} (remove element by index)</li>
 * </ul>
 *
 * <p><strong>Detection criteria:</strong>
 * <ul>
 *   <li>Exactly 2 parts: variable.method</li>
 *   <li>First part is a collection variable (TABLE OF, VARRAY, or INDEX BY)</li>
 *   <li>Second part is a recognized collection method name</li>
 *   <li>Argument pattern matches (COUNT/FIRST/LAST: no args; EXISTS/DELETE: with args)</li>
 *   <li>Not in assignment target (collection methods are read-only operations)</li>
 * </ul>
 *
 * <p>Uses deterministic variable scope lookup via TransformationContext.
 */
public class CollectionMethodTransformer implements GeneralElementTransformer {

    @Override
    public GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        // Collection methods require exactly 2 parts: variable.method
        if (parts.size() != 2) {
            return GeneralElementResult.notHandled();
        }

        // Skip if in assignment target (collection methods are read-only)
        if (builder.isInAssignmentTarget()) {
            return GeneralElementResult.notHandled();
        }

        TransformationContext context = builder.getContext();
        if (context == null) {
            return GeneralElementResult.notHandled();
        }

        // Check if first part is a collection variable
        String variableName = IdentifierHelper.unquote(parts.get(0).id_expression().getText());
        TransformationContext.VariableDefinition varDef = context.lookupVariable(variableName);

        if (varDef == null || !varDef.isCollection()) {
            return GeneralElementResult.notHandled();
        }

        // Check if second part is a valid collection method
        PlSqlParser.General_element_partContext methodPart = parts.get(1);
        String methodName = IdentifierHelper.unquote(methodPart.id_expression().getText()).toUpperCase();
        boolean hasArguments = methodPart.function_argument() != null && !methodPart.function_argument().isEmpty();

        if (!isCollectionMethod(methodName, hasArguments)) {
            return GeneralElementResult.notHandled();
        }

        // Transform the collection method call
        String result = handleCollectionMethod(variableName, methodName, methodPart, builder);
        return GeneralElementResult.handled(result);
    }

    /**
     * Checks if a method name is a valid Oracle collection method with correct argument pattern.
     *
     * @param methodName The method name (uppercase)
     * @param hasArguments Whether the method call has arguments
     * @return true if this is a recognized collection method
     */
    private boolean isCollectionMethod(String methodName, boolean hasArguments) {
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
     * @param variableName The collection variable name
     * @param methodName The collection method name (uppercase)
     * @param methodPart The AST part containing method call details
     * @param builder PostgreSQL code builder
     * @return Transformed PostgreSQL expression
     */
    private String handleCollectionMethod(
            String variableName,
            String methodName,
            PlSqlParser.General_element_partContext methodPart,
            PostgresCodeBuilder builder) {

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
                String existsArg = extractFirstArgument(methodPart, builder);
                String zeroBasedIndex = convertToZeroBasedIndex(existsArg);
                return "jsonb_typeof( " + variableName + " -> " + zeroBasedIndex + " ) IS NOT NULL";

            case "DELETE":
                // v_nums.DELETE(i) → v_nums - (i-1)
                // PostgreSQL jsonb - operator removes element by index (0-based)
                String deleteArg = extractFirstArgument(methodPart, builder);
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
     * @param builder PostgreSQL code builder
     * @return Transformed argument expression
     */
    private String extractFirstArgument(
            PlSqlParser.General_element_partContext methodPart,
            PostgresCodeBuilder builder) {
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
            return builder.visit(firstArg.expression());
        }

        throw new TransformationException("Invalid argument in collection method call");
    }

    /**
     * Converts a 1-based Oracle index to 0-based PostgreSQL index.
     *
     * <p>Transformation rules:
     * <ul>
     *   <li>Numeric literal: subtract 1 directly (e.g., "1" → "0", "5" → "4")</li>
     *   <li>Variable/expression: wrap in parentheses and subtract 1 (e.g., "i" → "(i-1)")</li>
     * </ul>
     *
     * @param indexExpr The 1-based index expression
     * @return The 0-based index expression
     */
    private String convertToZeroBasedIndex(String indexExpr) {
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
