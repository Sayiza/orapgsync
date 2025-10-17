package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an Oracle string function call.
 *
 * <p>Grammar rule: string_function
 * <pre>
 * string_function
 *     : SUBSTR '(' expression ',' expression (',' expression)? ')'
 *     | TO_CHAR '(' (table_element | standard_function | expression) (',' quoted_string)? (',' quoted_string)? ')'
 *     | DECODE '(' expressions_ ')'
 *     | CHR '(' concatenation USING NCHAR_CS ')'
 *     | NVL '(' expression ',' expression ')'
 *     | TRIM '(' ((LEADING | TRAILING | BOTH)? expression? FROM)? concatenation ')'
 *     | TO_DATE '(' (table_element | standard_function | expression) (DEFAULT concatenation ON CONVERSION ERROR)? (',' quoted_string (',' quoted_string)?)? ')'
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ NVL → COALESCE (implemented)
 * - ⏳ DECODE → CASE (not yet implemented)
 * - ⏳ SUBSTR → SUBSTRING (not yet implemented)
 * - ⏳ TO_CHAR → TO_CHAR (format conversion needed)
 * - ⏳ TO_DATE → TO_TIMESTAMP (format conversion needed)
 * - ⏳ TRIM → TRIM (compatible)
 * - ⏳ CHR → CHR (not yet implemented)
 */
public class StringFunction implements SemanticNode {

    public enum FunctionType {
        NVL,
        DECODE,
        SUBSTR,
        TO_CHAR,
        TO_DATE,
        TRIM,
        CHR
    }

    private final FunctionType functionType;
    private final List<SemanticNode> arguments;

    /**
     * Constructor for string function.
     *
     * @param functionType The type of string function
     * @param arguments The function arguments (expressions)
     */
    public StringFunction(FunctionType functionType, List<SemanticNode> arguments) {
        if (functionType == null) {
            throw new IllegalArgumentException("StringFunction functionType cannot be null");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("StringFunction arguments cannot be null");
        }
        this.functionType = functionType;
        this.arguments = arguments;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        switch (functionType) {
            case NVL:
                return transformNvl(context);
            case DECODE:
                return transformDecode(context);
            case SUBSTR:
                return transformSubstr(context);
            case TO_CHAR:
                return transformToChar(context);
            case TO_DATE:
                return transformToDate(context);
            case TRIM:
                return transformTrim(context);
            case CHR:
                return transformChr(context);
            default:
                throw new UnsupportedOperationException(
                    "Transformation not implemented for string function: " + functionType);
        }
    }

    /**
     * Transform Oracle NVL to PostgreSQL COALESCE.
     * Oracle: NVL(expr1, expr2)
     * PostgreSQL: COALESCE(expr1, expr2)
     */
    private String transformNvl(TransformationContext context) {
        if (arguments.size() != 2) {
            throw new IllegalStateException(
                "NVL function requires exactly 2 arguments, found: " + arguments.size());
        }

        String arg1 = arguments.get(0).toPostgres(context);
        String arg2 = arguments.get(1).toPostgres(context);

        return "COALESCE(" + arg1 + ", " + arg2 + ")";
    }

    /**
     * Transform Oracle DECODE to PostgreSQL CASE expression.
     * TODO: Implement when needed
     */
    private String transformDecode(TransformationContext context) {
        throw new UnsupportedOperationException(
            "DECODE transformation not yet implemented");
    }

    /**
     * Transform Oracle SUBSTR to PostgreSQL SUBSTRING.
     * TODO: Implement when needed
     */
    private String transformSubstr(TransformationContext context) {
        throw new UnsupportedOperationException(
            "SUBSTR transformation not yet implemented");
    }

    /**
     * Transform Oracle TO_CHAR to PostgreSQL TO_CHAR (with format conversion).
     * TODO: Implement when needed
     */
    private String transformToChar(TransformationContext context) {
        throw new UnsupportedOperationException(
            "TO_CHAR transformation not yet implemented");
    }

    /**
     * Transform Oracle TO_DATE to PostgreSQL TO_TIMESTAMP.
     * TODO: Implement when needed
     */
    private String transformToDate(TransformationContext context) {
        throw new UnsupportedOperationException(
            "TO_DATE transformation not yet implemented");
    }

    /**
     * Transform Oracle TRIM to PostgreSQL TRIM (compatible).
     * TODO: Implement when needed
     */
    private String transformTrim(TransformationContext context) {
        throw new UnsupportedOperationException(
            "TRIM transformation not yet implemented");
    }

    /**
     * Transform Oracle CHR to PostgreSQL CHR.
     * TODO: Implement when needed
     */
    private String transformChr(TransformationContext context) {
        throw new UnsupportedOperationException(
            "CHR transformation not yet implemented");
    }

    public FunctionType getFunctionType() {
        return functionType;
    }

    public List<SemanticNode> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "StringFunction{" +
            "functionType=" + functionType +
            ", arguments=" + arguments +
            '}';
    }
}
