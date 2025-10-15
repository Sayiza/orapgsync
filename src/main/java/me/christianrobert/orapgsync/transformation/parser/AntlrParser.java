package me.christianrobert.orapgsync.transformation.parser;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import org.antlr.v4.runtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around ANTLR PlSqlParser.
 * Handles parser instantiation, error collection, and provides type-safe parsing methods.
 *
 * This is the only class that directly instantiates ANTLR parsers.
 */
@ApplicationScoped
public class AntlrParser {

    private static final Logger log = LoggerFactory.getLogger(AntlrParser.class);

    /**
     * Parses a SELECT statement (for view definitions).
     *
     * @param sql Oracle SELECT statement
     * @return ParseResult containing the parse tree and any errors
     */
    public ParseResult parseSelectStatement(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new TransformationException("SQL cannot be null or empty");
        }

        log.debug("Parsing SELECT statement: {}", sql.substring(0, Math.min(100, sql.length())));

        try {
            // Create lexer and parser
            CharStream input = CharStreams.fromString(sql);
            PlSqlLexer lexer = new PlSqlLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PlSqlParser parser = new PlSqlParser(tokens);

            // Collect errors
            List<String> errors = new ArrayList<>();
            parser.removeErrorListeners(); // Remove default console error listener
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                        int line, int charPositionInLine, String msg,
                                        RecognitionException e) {
                    String error = String.format("Line %d:%d - %s", line, charPositionInLine, msg);
                    errors.add(error);
                    log.warn("Parse error: {}", error);
                }
            });

            // Parse the SELECT statement
            PlSqlParser.Select_statementContext tree = parser.select_statement();

            return new ParseResult(tree, errors, sql);

        } catch (Exception e) {
            log.error("Failed to parse SQL", e);
            throw new TransformationException("Failed to parse SQL: " + e.getMessage(), sql, "ANTLR parsing", e);
        }
    }

    /**
     * Parses a PL/SQL function body (future implementation).
     */
    public ParseResult parseFunctionBody(String plsql) {
        throw new UnsupportedOperationException("Function body parsing not yet implemented");
    }

    /**
     * Parses a PL/SQL procedure body (future implementation).
     */
    public ParseResult parseProcedureBody(String plsql) {
        throw new UnsupportedOperationException("Procedure body parsing not yet implemented");
    }
}
