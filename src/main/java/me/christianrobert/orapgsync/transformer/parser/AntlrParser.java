package me.christianrobert.orapgsync.transformer.parser;

import jakarta.enterprise.context.Dependent;
import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Thin wrapper around ANTLR PlSqlParser.
 * Handles parser instantiation, error collection, and provides type-safe parsing methods.
 *
 * Uses two-stage parsing strategy for performance and memory optimization:
 * 1. Try SLL(*) mode first (fast, low memory)
 * 2. Fall back to LL(*) mode if SLL fails (slower, more memory, handles all cases)
 *
 * CRITICAL: Clears ANTLR's static caches after each parse to prevent unbounded memory growth:
 * - DFA cache (_decisionToDFA) - decision state cache
 * - PredictionContextCache (_sharedContextCache) - ATN simulation context cache
 *
 * Both caches are static and shared across all parser instances. Without clearing, they grow
 * unbounded when parsing hundreds of packages, causing OutOfMemoryError.
 *
 * This is the only class that directly instantiates ANTLR parsers.
 *
 * Note: Uses @Dependent scope (not @ApplicationScoped) because:
 * - It's stateless (safe to create multiple instances)
 * - It's both @Injected (TransformationService) and instantiated with new (OracleFunctionExtractor)
 * - Cache clearing ensures no memory leaks across instances
 */
@Dependent
public class AntlrParser {

    private static final Logger log = LoggerFactory.getLogger(AntlrParser.class);
    private static final boolean USE_TWO_STAGE_PARSING = true; // Can be toggled for testing

    /**
     * Clears the PredictionContextCache to prevent memory accumulation.
     * Uses reflection because PredictionContextCache doesn't expose a public clear() method.
     *
     * @param cache The prediction context cache to clear
     */
    private void clearPredictionContextCache(PredictionContextCache cache) {
        if (cache == null) {
            return;
        }

        try {
            // Access the protected 'cache' field using reflection
            Field cacheField = PredictionContextCache.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            Map<?, ?> internalCache = (Map<?, ?>) cacheField.get(cache);

            if (internalCache != null) {
                int sizeBefore = internalCache.size();
                internalCache.clear();
                log.trace("Cleared PredictionContextCache ({} entries removed)", sizeBefore);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Failed to clear PredictionContextCache via reflection (ANTLR API may have changed): {}",
                    e.getMessage());
        }
    }

    /**
     * Two-stage parsing strategy: Try SLL(*) first (fast), fall back to LL(*) if needed.
     *
     * @param source Source code to parse
     * @param parseFunction Function that invokes the specific parser rule (e.g., parser::select_statement)
     * @param description Description for logging (e.g., "SELECT statement")
     * @return ParseResult containing parse tree and errors
     */
    private <T extends ParserRuleContext> ParseResult parseTwoStage(
            String source,
            Function<PlSqlParser, T> parseFunction,
            String description) {

        CharStream input = CharStreams.fromString(source);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        List<String> errors = new ArrayList<>();
        T tree = null;
        PlSqlParser parser = null;  // Declare outside try block for cleanup

        try {
            if (USE_TWO_STAGE_PARSING) {
                // Stage 1: Try SLL(*) mode (fast path)
                parser = new PlSqlParser(tokens);
                parser.getInterpreter().clearDFA();
                parser.removeErrorListeners();
                parser.setErrorHandler(new BailErrorStrategy()); // Fail fast on ambiguity
                parser.getInterpreter().setPredictionMode(PredictionMode.SLL);

                try {
                    log.trace("Attempting SLL(*) parse for {}", description);
                    tree = parseFunction.apply(parser);
                    log.trace("SLL(*) parse succeeded for {}", description);

                } catch (Exception sllException) {
                    // SLL failed - fall back to LL(*)
                    log.trace("SLL(*) parse failed for {}, falling back to LL(*)", description);

                    // Stage 2: Retry with LL(*) mode (slow path)
                    tokens.seek(0); // Rewind token stream
                    parser.reset();
                    parser.removeErrorListeners();
                    parser.setErrorHandler(new DefaultErrorStrategy()); // Full error recovery
                    parser.getInterpreter().setPredictionMode(PredictionMode.LL);

                    // Collect errors in LL mode
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

                    tree = parseFunction.apply(parser);
                    log.debug("LL(*) parse completed for {}", description);
                }

            } else {
                // Single-stage LL(*) parsing (legacy mode)
                parser = new PlSqlParser(tokens);
                parser.getInterpreter().clearDFA();
                parser.removeErrorListeners();
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

                tree = parseFunction.apply(parser);
            }

            return new ParseResult(tree, errors, source);

        } finally {
            // CRITICAL: Clear BOTH static caches to prevent memory accumulation
            // ANTLR's caches are static and shared across all parser instances
            // Without clearing, they grow unbounded as we parse hundreds of packages
            if (parser != null) {
                // Clear DFA cache (decision states)
                parser.getInterpreter().clearDFA();

                // Clear PredictionContextCache (ATN simulation contexts)
                // This was identified via heap dump analysis as the primary memory leak
                PredictionContextCache sharedCache = parser.getInterpreter().getSharedContextCache();
                clearPredictionContextCache(sharedCache);

                log.trace("Cleared DFA and PredictionContext caches after parsing {}", description);
            }
        }
    }

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
            return parseTwoStage(sql, PlSqlParser::select_statement, "SELECT statement");
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
            return parseTwoStage(plsql, PlSqlParser::function_body, "function body");
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
            return parseTwoStage(plsql, PlSqlParser::procedure_body, "procedure body");
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
            return parseTwoStage(plsql, PlSqlParser::create_package, "package spec");
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
            return parseTwoStage(plsql, PlSqlParser::create_package_body, "package body");
        } catch (Exception e) {
            log.error("Failed to parse package body", e);
            throw new TransformationException("Failed to parse package body: " + e.getMessage(), plsql, "ANTLR parsing", e);
        }
    }
}
