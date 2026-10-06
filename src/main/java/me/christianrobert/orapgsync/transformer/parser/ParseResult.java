package me.christianrobert.orapgsync.transformer.parser;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of parsing Oracle SQL/PL-SQL code.
 * Contains the parse tree and any syntax errors encountered.
 */
public class ParseResult {

    private final ParserRuleContext tree;
    private final List<String> errors;
    private final String originalSql;

    public ParseResult(ParserRuleContext tree, List<String> errors, String originalSql) {
        this.tree = tree;
        this.errors = new ArrayList<>(errors);
        this.originalSql = originalSql;
    }

    /**
     * Gets the ANTLR parse tree root node.
     */
    public ParserRuleContext getTree() {
        return tree;
    }

    /**
     * Gets the list of syntax errors encountered during parsing.
     */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /**
     * Gets the original SQL that was parsed.
     */
    public String getOriginalSql() {
        return originalSql;
    }

    /**
     * Checks if parsing was successful (no errors).
     */
    public boolean isSuccess() {
        return errors.isEmpty();
    }

    /**
     * Checks if parsing encountered errors.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Gets a formatted error message combining all errors.
     */
    public String getErrorMessage() {
        if (errors.isEmpty()) {
            return null;
        }
        return String.join("\n", errors);
    }

    @Override
    public String toString() {
        return "ParseResult{success=" + isSuccess() + ", errors=" + errors.size() + "}";
    }
}
