package me.christianrobert.orapgsync.transformer.type.helpers;

import me.christianrobert.orapgsync.antlr.PlSqlParser.*;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import org.antlr.v4.runtime.ParserRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static helper for resolving function return types.
 *
 * <p>Handles all built-in Oracle functions from Phase 3:</p>
 * <ul>
 *   <li>Polymorphic functions (ROUND, TRUNC with DATE vs NUMBER)</li>
 *   <li>Date functions (ADD_MONTHS, MONTHS_BETWEEN, LAST_DAY)</li>
 *   <li>String functions (UPPER, LOWER, SUBSTR, LENGTH, INSTR, TRIM)</li>
 *   <li>Conversion functions (TO_CHAR, TO_DATE, TO_NUMBER, TO_TIMESTAMP)</li>
 *   <li>NULL-handling functions (NVL, COALESCE, DECODE, NULLIF)</li>
 *   <li>Aggregate functions (COUNT, SUM, AVG, MIN, MAX)</li>
 *   <li>Numeric functions (ABS, SQRT, CEIL, FLOOR, etc.)</li>
 * </ul>
 *
 * <p>Pattern: Static helper following PostgresCodeBuilder architecture.</p>
 */
public final class ResolveFunction {

    private static final Logger log = LoggerFactory.getLogger(ResolveFunction.class);

    private ResolveFunction() {
        // Static utility class - prevent instantiation
    }

    // ========== General Element Functions (e.g., UPPER(name)) ==========

    /**
     * Resolves function return type from general_element context.
     *
     * <p>Handles functions called with parentheses: func(arg1, arg2, ...)</p>
     *
     * @param firstPart First part containing function name
     * @param funcArg Function argument context
     * @param typeCache Type cache for looking up argument types
     * @param visitor Visitor for generating node keys
     * @return Function return type
     */
    public static TypeInfo resolveFromGeneralElement(General_element_partContext firstPart,
                                                      Function_argumentContext funcArg,
                                                      Map<String, TypeInfo> typeCache,
                                                      TypeAnalysisVisitor visitor) {
        // Extract function name
        String functionName = extractIdentifier(firstPart);
        if (functionName == null) {
            log.trace("Could not extract function name");
            return TypeInfo.UNKNOWN;
        }

        // Extract argument types (already visited and cached)
        List<TypeInfo> argumentTypes = new ArrayList<>();
        if (funcArg.argument() != null) {
            for (ArgumentContext arg : funcArg.argument()) {
                if (arg.expression() != null) {
                    // Look up cached type for this argument expression
                    String key = visitor.nodeKey(arg.expression());
                    TypeInfo argType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
                    argumentTypes.add(argType);
                }
            }
        }

        // Resolve function return type
        TypeInfo returnType = getFunctionReturnType(functionName, argumentTypes);
        log.trace("Function {}(...) returns type {}", functionName, returnType.getCategory());
        return returnType;
    }

    /**
     * Extracts identifier from general_element_part.
     */
    private static String extractIdentifier(General_element_partContext partCtx) {
        if (partCtx == null || partCtx.id_expression() == null) {
            return null;
        }
        String text = partCtx.id_expression().getText();
        return text != null ? text.toLowerCase() : null;
    }

    // ========== Other Function Context (COUNT, TO_NUMBER, etc.) ==========

    /**
     * Resolves function return type from other_function context.
     *
     * <p>Handles special grammar rules for COUNT, TO_NUMBER, EXTRACT, COALESCE, etc.</p>
     */
    public static TypeInfo resolveFromOtherFunction(Other_functionContext ctx,
                                                     Map<String, TypeInfo> typeCache,
                                                     TypeAnalysisVisitor visitor) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        // COUNT function
        if (ctx.COUNT() != null) {
            return TypeInfo.NUMERIC;
        }
        // TO_NUMBER, TO_TIMESTAMP, etc.
        else if (ctx.TO_NUMBER() != null) {
            return TypeInfo.NUMERIC;
        }
        else if (ctx.TO_TIMESTAMP() != null || ctx.TO_TIMESTAMP_TZ() != null) {
            return TypeInfo.TIMESTAMP;
        }
        else if (ctx.TO_DSINTERVAL() != null || ctx.TO_YMINTERVAL() != null) {
            return TypeInfo.TEXT;  // Intervals
        }
        // EXTRACT function
        else if (ctx.EXTRACT() != null) {
            return TypeInfo.NUMERIC;
        }
        // CAST function - would need to parse target type (Phase 4)
        else if (ctx.CAST() != null || ctx.XMLCAST() != null) {
            return TypeInfo.UNKNOWN;
        }
        // COALESCE function
        else if (ctx.COALESCE() != null) {
            List<TypeInfo> argTypes = new ArrayList<>();
            if (ctx.table_element() != null) {
                String key = visitor.nodeKey(ctx.table_element());
                argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
            }
            if (ctx.numeric() != null) {
                argTypes.add(TypeInfo.NUMERIC);
            }
            if (ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
                argTypes.add(TypeInfo.TEXT);
            }
            return resolveCoalesceType(argTypes);
        }
        // String functions
        else if (ctx.TRIM() != null || ctx.TRANSLATE() != null) {
            return TypeInfo.TEXT;
        }
        // Window functions that return numeric
        else if (ctx.over_clause_keyword() != null) {
            return TypeInfo.NUMERIC;
        }
        // Ranking functions
        else if (ctx.within_or_over_clause_keyword() != null) {
            return TypeInfo.NUMERIC;
        }
        // XML functions
        else if (ctx.XMLAGG() != null || ctx.XMLCOLATTVAL() != null || ctx.XMLFOREST() != null ||
                 ctx.XMLELEMENT() != null || ctx.XMLPARSE() != null || ctx.XMLPI() != null ||
                 ctx.XMLQUERY() != null || ctx.XMLROOT() != null || ctx.XMLSERIALIZE() != null) {
            return TypeInfo.TEXT;
        }

        return TypeInfo.UNKNOWN;
    }

    // ========== String Function Context (TO_CHAR, TO_DATE, etc.) ==========

    /**
     * Resolves function return type from string_function context.
     */
    public static TypeInfo resolveFromStringFunction(String_functionContext ctx,
                                                      Map<String, TypeInfo> typeCache,
                                                      TypeAnalysisVisitor visitor) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        // TO_CHAR always returns TEXT
        if (ctx.TO_CHAR() != null) {
            return TypeInfo.TEXT;
        }
        // TO_DATE always returns DATE
        else if (ctx.TO_DATE() != null) {
            return TypeInfo.DATE;
        }
        // String manipulation functions return TEXT
        else if (ctx.SUBSTR() != null || ctx.CHR() != null || ctx.TRIM() != null) {
            return TypeInfo.TEXT;
        }
        // DECODE - return type is highest precedence of result expressions
        else if (ctx.DECODE() != null) {
            if (ctx.expressions_() != null && ctx.expressions_().expression() != null) {
                List<TypeInfo> argTypes = new ArrayList<>();
                List<ExpressionContext> exprs = ctx.expressions_().expression();
                // Results are at indices 2, 4, 6, ...
                for (int i = 2; i < exprs.size(); i += 2) {
                    String key = visitor.nodeKey(exprs.get(i));
                    argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
                }
                // If even arg count, last arg is default value
                if (exprs.size() % 2 == 0) {
                    String key = visitor.nodeKey(exprs.get(exprs.size() - 1));
                    argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
                }
                return resolveCoalesceType(argTypes);
            }
            return TypeInfo.UNKNOWN;
        }
        // NVL - return type is highest precedence of arguments
        else if (ctx.NVL() != null) {
            List<TypeInfo> argTypes = new ArrayList<>();
            if (ctx.expression() != null && ctx.expression().size() >= 2) {
                String key1 = visitor.nodeKey(ctx.expression().get(0));
                String key2 = visitor.nodeKey(ctx.expression().get(1));
                argTypes.add(typeCache.getOrDefault(key1, TypeInfo.UNKNOWN));
                argTypes.add(typeCache.getOrDefault(key2, TypeInfo.UNKNOWN));
            }
            return resolveCoalesceType(argTypes);
        }

        return TypeInfo.UNKNOWN;
    }

    // ========== Numeric Function Context (ROUND, COUNT, SUM, etc.) ==========

    /**
     * Resolves function return type from numeric_function context.
     */
    public static TypeInfo resolveFromNumericFunction(Numeric_functionContext ctx,
                                                       Map<String, TypeInfo> typeCache,
                                                       TypeAnalysisVisitor visitor) {
        if (ctx == null) {
            return TypeInfo.UNKNOWN;
        }

        TypeInfo functionType = TypeInfo.NUMERIC;  // Default for all numeric functions

        // ROUND can return DATE if argument is DATE
        if (ctx.ROUND() != null && ctx.expression() != null) {
            String key = visitor.nodeKey(ctx.expression());
            TypeInfo argType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
            if (argType.isDate()) {
                functionType = argType;  // DATE → DATE
            }
        }
        // MAX returns argument type (could be DATE or NUMBER)
        else if (ctx.MAX() != null && ctx.expression() != null) {
            String key = visitor.nodeKey(ctx.expression());
            functionType = typeCache.getOrDefault(key, TypeInfo.UNKNOWN);
            if (functionType.isUnknown()) {
                functionType = TypeInfo.NUMERIC;  // Default if unknown
            }
        }
        // LEAST, GREATEST return highest precedence type
        else if (ctx.LEAST() != null || ctx.GREATEST() != null) {
            List<TypeInfo> argTypes = new ArrayList<>();
            if (ctx.expressions_() != null && ctx.expressions_().expression() != null) {
                for (ExpressionContext expr : ctx.expressions_().expression()) {
                    String key = visitor.nodeKey(expr);
                    argTypes.add(typeCache.getOrDefault(key, TypeInfo.UNKNOWN));
                }
            }
            functionType = resolveCoalesceType(argTypes);
            if (functionType.isUnknown()) {
                functionType = TypeInfo.NUMERIC;  // Default
            }
        }

        return functionType;
    }

    // ========== Function Return Type Mapping ==========

    /**
     * Maps Oracle built-in function names to their return types.
     *
     * <p>Handles polymorphic functions where return type depends on argument types.</p>
     */
    private static TypeInfo getFunctionReturnType(String functionName, List<TypeInfo> argumentTypes) {
        String upperName = functionName.toUpperCase();

        switch (upperName) {
            // ========== Polymorphic Functions ==========
            case "ROUND":
            case "TRUNC":
                if (!argumentTypes.isEmpty() && argumentTypes.get(0) != null) {
                    TypeInfo firstArgType = argumentTypes.get(0);
                    if (firstArgType.isDate()) {
                        return firstArgType;  // Preserve DATE or TIMESTAMP
                    } else if (firstArgType.isNumeric()) {
                        return TypeInfo.NUMERIC;
                    }
                }
                return TypeInfo.NUMERIC;  // Default: assume numeric

            // ========== Date Functions ==========
            case "SYSDATE":
            case "CURRENT_DATE":
            case "LAST_DAY":
            case "NEXT_DAY":
                return TypeInfo.DATE;

            case "SYSTIMESTAMP":
            case "CURRENT_TIMESTAMP":
                return TypeInfo.TIMESTAMP;

            case "ADD_MONTHS":
                return TypeInfo.DATE;

            case "MONTHS_BETWEEN":
                return TypeInfo.NUMERIC;

            case "EXTRACT":
                return TypeInfo.NUMERIC;

            // ========== String Functions ==========
            case "UPPER":
            case "LOWER":
            case "INITCAP":
            case "TRIM":
            case "LTRIM":
            case "RTRIM":
            case "SUBSTR":
            case "SUBSTRING":
            case "REPLACE":
            case "TRANSLATE":
            case "LPAD":
            case "RPAD":
            case "CHR":
            case "CONCAT":
                return TypeInfo.TEXT;

            case "LENGTH":
            case "INSTR":
            case "ASCII":
                return TypeInfo.NUMERIC;

            // ========== Conversion Functions ==========
            case "TO_CHAR":
                return TypeInfo.TEXT;

            case "TO_NUMBER":
                return TypeInfo.NUMERIC;

            case "TO_DATE":
                return TypeInfo.DATE;

            case "TO_TIMESTAMP":
                return TypeInfo.TIMESTAMP;

            case "CAST":
                return TypeInfo.UNKNOWN;

            // ========== NULL-Handling Functions ==========
            case "NVL":
            case "COALESCE":
                return resolveCoalesceType(argumentTypes);

            case "NVL2":
                if (argumentTypes.size() >= 3) {
                    List<TypeInfo> resultTypes = new ArrayList<>();
                    resultTypes.add(argumentTypes.get(1));  // expr2
                    resultTypes.add(argumentTypes.get(2));  // expr3
                    return resolveCoalesceType(resultTypes);
                }
                return TypeInfo.UNKNOWN;

            case "DECODE":
                return resolveDecodeType(argumentTypes);

            case "NULLIF":
                if (!argumentTypes.isEmpty()) {
                    return argumentTypes.get(0);
                }
                return TypeInfo.UNKNOWN;

            // ========== Aggregate Functions ==========
            case "COUNT":
                return TypeInfo.NUMERIC;

            case "SUM":
            case "AVG":
            case "MIN":
            case "MAX":
                if (!argumentTypes.isEmpty()) {
                    return argumentTypes.get(0);
                }
                return TypeInfo.NUMERIC;

            // ========== Numeric Functions ==========
            case "ABS":
            case "CEIL":
            case "FLOOR":
            case "MOD":
            case "POWER":
            case "SQRT":
            case "EXP":
            case "LN":
            case "LOG":
            case "SIGN":
                return TypeInfo.NUMERIC;

            default:
                log.trace("Unknown function: {}, returning UNKNOWN", functionName);
                return TypeInfo.UNKNOWN;
        }
    }

    // ========== Type Precedence Logic ==========

    /**
     * Resolves return type for NVL/COALESCE functions.
     *
     * <p>Returns highest precedence type among all arguments.</p>
     * <p>Type precedence: TIMESTAMP > DATE > NUMBER > TEXT</p>
     */
    private static TypeInfo resolveCoalesceType(List<TypeInfo> argumentTypes) {
        TypeInfo resultType = TypeInfo.UNKNOWN;

        for (TypeInfo argType : argumentTypes) {
            if (argType.isUnknown() || argType.isNull()) {
                continue;
            }

            if (resultType.isUnknown()) {
                resultType = argType;
            } else {
                resultType = higherPrecedence(resultType, argType);
            }
        }

        return resultType;
    }

    /**
     * Resolves return type for DECODE function.
     *
     * <p>DECODE(expr, search1, result1, search2, result2, ..., default)</p>
     */
    private static TypeInfo resolveDecodeType(List<TypeInfo> argumentTypes) {
        if (argumentTypes.size() < 3) {
            return TypeInfo.UNKNOWN;
        }

        TypeInfo resultType = TypeInfo.UNKNOWN;

        // Result expressions are at indices 2, 4, 6, ...
        for (int i = 2; i < argumentTypes.size(); i += 2) {
            TypeInfo argType = argumentTypes.get(i);
            if (argType.isUnknown() || argType.isNull()) {
                continue;
            }

            if (resultType.isUnknown()) {
                resultType = argType;
            } else {
                resultType = higherPrecedence(resultType, argType);
            }
        }

        // If arg count is even, last arg is default value
        if (argumentTypes.size() % 2 == 0) {
            TypeInfo defaultType = argumentTypes.get(argumentTypes.size() - 1);
            if (!defaultType.isUnknown() && !defaultType.isNull()) {
                if (resultType.isUnknown()) {
                    resultType = defaultType;
                } else {
                    resultType = higherPrecedence(resultType, defaultType);
                }
            }
        }

        return resultType;
    }

    /**
     * Determines higher precedence type between two types.
     *
     * <p>Oracle type precedence: TIMESTAMP > DATE > NUMBER > TEXT</p>
     */
    private static TypeInfo higherPrecedence(TypeInfo t1, TypeInfo t2) {
        // TIMESTAMP has highest precedence
        if (t1.getCategory() == TypeInfo.TypeCategory.DATE && t1 == TypeInfo.TIMESTAMP) {
            return t1;
        }
        if (t2.getCategory() == TypeInfo.TypeCategory.DATE && t2 == TypeInfo.TIMESTAMP) {
            return t2;
        }

        // DATE (but not TIMESTAMP)
        if (t1.isDate() && !t2.isDate()) {
            return t1;
        }
        if (t2.isDate() && !t1.isDate()) {
            return t2;
        }

        // Both DATE - return first
        if (t1.isDate() && t2.isDate()) {
            return t1;
        }

        // NUMBER over TEXT
        if (t1.isNumeric() && !t2.isNumeric()) {
            return t1;
        }
        if (t2.isNumeric() && !t1.isNumeric()) {
            return t2;
        }

        // Both same category or both TEXT - return first
        return t1;
    }
}
