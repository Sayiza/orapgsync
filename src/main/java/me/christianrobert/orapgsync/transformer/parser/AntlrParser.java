package me.christianrobert.orapgsync.transformer.parser;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
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
     * Parses a PL/SQL function body (extracted from ALL_SOURCE).
     * Expects source starting with "FUNCTION function_name(...) RETURN type IS/AS ..."
     *
     * @param plsql Oracle PL/SQL function source code
     * @return ParseResult containing the parse tree and any errors
     */
    public ParseResult parseFunctionBody(String plsql) {
        if (plsql == null || plsql.trim().isEmpty()) {
            throw new TransformationException("PL/SQL cannot be null or empty");
        }

        log.debug("Parsing function body: {}", plsql.substring(0, Math.min(100, plsql.length())));

        try {
            // Create lexer and parser
            CharStream input = CharStreams.fromString(plsql);
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

            // Parse the function body
            PlSqlParser.Function_bodyContext tree = parser.function_body();

            return new ParseResult(tree, errors, plsql);

        } catch (Exception e) {
            log.error("Failed to parse function body", e);
            throw new TransformationException("Failed to parse function body: " + e.getMessage(), plsql, "ANTLR parsing", e);
        }
    }

    /**
     * Parses a PL/SQL procedure body (extracted from ALL_SOURCE).
     * Expects source starting with "PROCEDURE procedure_name(...) IS/AS ..."
     *
     * @param plsql Oracle PL/SQL procedure source code
     * @return ParseResult containing the parse tree and any errors
     */
    public ParseResult parseProcedureBody(String plsql) {
        if (plsql == null || plsql.trim().isEmpty()) {
            throw new TransformationException("PL/SQL cannot be null or empty");
        }

        log.debug("Parsing procedure body: {}", plsql.substring(0, Math.min(100, plsql.length())));

        try {
            // Create lexer and parser
            CharStream input = CharStreams.fromString(plsql);
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

            // Parse the procedure body
            PlSqlParser.Procedure_bodyContext tree = parser.procedure_body();

            return new ParseResult(tree, errors, plsql);

        } catch (Exception e) {
            log.error("Failed to parse procedure body", e);
            throw new TransformationException("Failed to parse procedure body: " + e.getMessage(), plsql, "ANTLR parsing", e);
        }
    }

    /**
     * Parses an Oracle package specification (extracted from ALL_SOURCE).
     * Expects source starting with "CREATE [OR REPLACE] PACKAGE package_name AS/IS ..."
     *
     * @param plsql Oracle package spec source code
     * @return ParseResult containing the parse tree and any errors
     */
    public ParseResult parsePackageSpec(String plsql) {
        if (plsql == null || plsql.trim().isEmpty()) {
            throw new TransformationException("PL/SQL cannot be null or empty");
        }

        log.debug("Parsing package spec: {}", plsql.substring(0, Math.min(100, plsql.length())));

        try {
            // Create lexer and parser
            CharStream input = CharStreams.fromString(plsql);
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

            // Parse the package spec
            PlSqlParser.Create_packageContext tree = parser.create_package();

            return new ParseResult(tree, errors, plsql);

        } catch (Exception e) {
            log.error("Failed to parse package spec", e);
            throw new TransformationException("Failed to parse package spec: " + e.getMessage(), plsql, "ANTLR parsing", e);
        }
    }

    /**
     * Parses an Oracle package body (extracted from ALL_SOURCE).
     * Expects source starting with "CREATE [OR REPLACE] PACKAGE BODY package_name AS/IS ..."
     *
     * @param plsql Oracle package body source code
     * @return ParseResult containing the parse tree and any errors
     */
    public ParseResult parsePackageBody(String plsql) {
        if (plsql == null || plsql.trim().isEmpty()) {
            throw new TransformationException("PL/SQL cannot be null or empty");
        }

        log.debug("Parsing package body: {}", plsql.substring(0, Math.min(100, plsql.length())));

        try {
            // Create lexer and parser
            CharStream input = CharStreams.fromString(plsql);
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

            // Parse the package body
            PlSqlParser.Create_package_bodyContext tree = parser.create_package_body();

            return new ParseResult(tree, errors, plsql);

        } catch (Exception e) {
            log.error("Failed to parse package body", e);
            throw new TransformationException("Failed to parse package body: " + e.getMessage(), plsql, "ANTLR parsing", e);
        }
    }
}
