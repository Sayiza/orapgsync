package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer-based scanner for identifying function/procedure boundaries in Oracle packages.
 *
 * This scanner uses ANTLR's PlSqlLexer to tokenize the source code, then applies
 * state-based tracking on the token stream. This provides a fast alternative to full
 * ANTLR parsing when only function boundaries are needed (e.g., for extraction jobs).
 *
 * **Key Features:**
 * - Case insensitivity handled by lexer (FUNCTION, function, Function all become same token)
 * - Whitespace invisible in token stream (no manual skipping needed)
 * - Comments filtered to hidden channel (no preprocessing needed)
 * - String literals are single tokens (no escape handling needed)
 * - Quoted identifiers handled correctly ("END" as identifier vs END keyword)
 * - Word boundaries are implicit (APPEND doesn't contain END token)
 *
 * **Correctly handles:**
 * - END LOOP, END IF, END CASE (don't close BEGIN blocks)
 * - The bare END of a CASE *expression* (`x := CASE … END;`) — see {@link PlSqlBlockScanner},
 *   which finds the closing END for this scanner and {@link TypeMethodBoundaryScanner} alike
 * - Nested functions/procedures, including ones declared in a nested DECLARE block
 * - Forward declarations (signature without body)
 * - Call specs (`IS LANGUAGE JAVA NAME '…';`) — recorded, ending at their semicolon
 * - Mixed case keywords
 * - String literals containing keywords
 *
 * **A truncated segment is invisible to a count check**: the orphaned tail carries no FUNCTION
 * keyword, so segment counts still tally. It shows up instead as `mismatched input '<EOF>'` when
 * the segment is parsed, and as statement debris (`else`, `then`, bare assignments) in the reduced
 * package body, which then does not parse. Tests therefore assert the extracted text.
 *
 * **Performance:**
 * - O(n) tokenization + O(n) scan = O(n) total
 * - Scans 5000-line package in ~1 second vs. 3 minutes for full ANTLR parse
 * - Memory: ~250KB vs. 2GB for full AST
 *
 * **Usage:**
 * ```java
 * FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();
 * PackageSegments segments = scanner.scanPackageBody(packageBodySql);
 * ```
 *
 * **Note:** This scanner does NOT require comment-free input.
 * Comments are automatically filtered by the lexer.
 */
public class FunctionBoundaryScanner {

    private static final Logger log = LoggerFactory.getLogger(FunctionBoundaryScanner.class);

    /**
     * Scans package body and identifies function/procedure boundaries.
     *
     * @param packageBodySql Package body SQL (comments are OK, lexer handles them)
     * @return Scanned segments with function boundaries
     */
    public PackageSegments scanPackageBody(String packageBodySql) {
        log.debug("Scanning package body ({} chars)", packageBodySql.length());

        PackageSegments segments = new PackageSegments();

        // Tokenize using ANTLR lexer
        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(packageBodySql));
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        tokenStream.fill();

        // Filter to only default channel tokens (excludes whitespace, comments)
        List<Token> allTokens = tokenStream.getTokens();
        List<Token> tokens = new ArrayList<>();
        for (Token t : allTokens) {
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                tokens.add(t);
            }
        }
        log.trace("Tokenized into {} tokens ({} on default channel)", allTokens.size(), tokens.size());

        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            int tokenType = token.getType();

            // Look for FUNCTION or PROCEDURE keyword at package level
            if (tokenType == PlSqlLexer.FUNCTION || tokenType == PlSqlLexer.PROCEDURE) {
                // Check if this is a package-level definition (not nested)
                // by scanning to find IS/AS or semicolon
                ScanResult result = scanFunctionOrProcedure(tokens, i, packageBodySql);
                if (result != null) {
                    if (!result.isForwardDeclaration) {
                        segments.addFunction(result.segment);
                        log.debug("Found {} {} at [{}-{}]",
                                result.segment.isFunction() ? "FUNCTION" : "PROCEDURE",
                                result.segment.getName(),
                                result.segment.getStartPos(),
                                result.segment.getEndPos());
                    } else {
                        log.trace("Skipping forward declaration: {}", result.functionName);
                    }
                    i = result.nextTokenIndex;
                    continue;
                }
            }

            i++;
        }

        log.debug("Scan complete: found {} functions", segments.getFunctionCount());
        return segments;
    }

    /**
     * Scans a function or procedure starting at the given token index.
     *
     * @param tokens All tokens
     * @param startIndex Index of FUNCTION/PROCEDURE token
     * @param source Original source for position extraction
     * @return ScanResult with segment info, or null if scan fails
     */
    private ScanResult scanFunctionOrProcedure(List<Token> tokens, int startIndex, String source) {
        Token startToken = tokens.get(startIndex);
        boolean isFunction = startToken.getType() == PlSqlLexer.FUNCTION;
        int startPos = startToken.getStartIndex();

        int i = startIndex + 1;

        // Skip to find function/procedure name (next identifier-like token)
        String functionName = null;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (isIdentifierToken(t)) {
                functionName = t.getText();
                i++;
                break;
            } else if (t.getType() == PlSqlLexer.EOF) {
                return null;
            }
            i++;
        }

        if (functionName == null) {
            log.warn("Could not find function name after FUNCTION/PROCEDURE at position {}", startPos);
            return null;
        }

        // Scan signature until IS/AS or semicolon (forward declaration)
        int parenDepth = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            int type = t.getType();

            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (parenDepth == 0) {
                if (type == PlSqlLexer.IS || type == PlSqlLexer.AS) {
                    // Found IS/AS - this is a full definition
                    i++;
                    break;
                } else if (type == PlSqlLexer.SEMICOLON) {
                    // Forward declaration - no body
                    return new ScanResult(functionName, i + 1, true, null);
                }
            }

            if (type == PlSqlLexer.EOF) {
                return null;
            }
            i++;
        }

        // Now we're after IS/AS: the body starts at the next token
        if (i >= tokens.size() || tokens.get(i).getType() == PlSqlLexer.EOF) {
            log.warn("No body after IS/AS for function {} starting at position {}", functionName, startPos);
            return null;
        }
        int bodyStartPos = tokens.get(i).getStartIndex();

        // call_spec (IS LANGUAGE JAVA NAME '...';) has no BEGIN/END and ends at its semicolon.
        // Recorded rather than skipped, so the transformer rejects it by name instead of the scan
        // hunting for an END that belongs to the next function.
        if (PlSqlBlockScanner.isCallSpec(tokens, i)) {
            int semicolon = PlSqlBlockScanner.findCallSpecEnd(tokens, i);
            if (semicolon < 0) {
                log.warn("Unterminated call spec for function {} starting at position {}", functionName, startPos);
                return null;
            }
            Token t = tokens.get(semicolon);
            return segment(functionName, startPos, t.getStopIndex() + 1, bodyStartPos, t.getStartIndex(),
                    isFunction, semicolon + 1);
        }

        int endIndex = PlSqlBlockScanner.findBodyEnd(tokens, i);
        if (endIndex < 0) {
            log.warn("Could not find END for function {} starting at position {}", functionName, startPos);
            return null;
        }
        int bodyEndPos = tokens.get(endIndex).getStartIndex();

        // Find the semicolon after END [name]
        for (int j = endIndex + 1; j < tokens.size(); j++) {
            if (tokens.get(j).getType() == PlSqlLexer.SEMICOLON) {
                return segment(functionName, startPos, tokens.get(j).getStopIndex() + 1, bodyStartPos,
                        bodyEndPos, isFunction, j + 1);
            }
        }
        // END at the very end of the source without its semicolon
        return segment(functionName, startPos, source.length(), bodyStartPos, bodyEndPos, isFunction,
                tokens.size());
    }

    private ScanResult segment(String functionName, int startPos, int endPos, int bodyStartPos,
                               int bodyEndPos, boolean isFunction, int nextTokenIndex) {
        PackageSegments.FunctionSegment segment = new PackageSegments.FunctionSegment(
                functionName, startPos, endPos, bodyStartPos, bodyEndPos, isFunction);
        return new ScanResult(functionName, nextTokenIndex, false, segment);
    }

    /**
     * Checks if a token can be used as an identifier in the context of a function/procedure name.
     * In Oracle, many keywords can be used as identifiers in certain contexts.
     *
     * We use a "not a structural keyword" approach: anything that's not a
     * punctuation mark or structural keyword can be a function name.
     */
    private boolean isIdentifierToken(Token token) {
        int type = token.getType();

        // Definitely identifiers
        if (type == PlSqlLexer.REGULAR_ID || type == PlSqlLexer.DELIMITED_ID) {
            return true;
        }

        // Definitely NOT identifiers (structural tokens)
        if (type == PlSqlLexer.EOF ||
            type == PlSqlLexer.SEMICOLON ||
            type == PlSqlLexer.LEFT_PAREN ||
            type == PlSqlLexer.RIGHT_PAREN ||
            type == PlSqlLexer.COMMA ||
            type == PlSqlLexer.PERIOD ||
            type == PlSqlLexer.ASSIGN_OP ||
            type == PlSqlLexer.COLON ||
            type == PlSqlLexer.IS ||
            type == PlSqlLexer.AS ||
            type == PlSqlLexer.BEGIN ||
            type == PlSqlLexer.END ||
            type == PlSqlLexer.RETURN ||
            type == PlSqlLexer.FUNCTION ||
            type == PlSqlLexer.PROCEDURE ||
            type == PlSqlLexer.CHAR_STRING ||
            type == PlSqlLexer.UNSIGNED_INTEGER ||
            type == PlSqlLexer.APPROXIMATE_NUM_LIT) {
            return false;
        }

        // Most other tokens (including many keywords) can be used as identifiers
        // in Oracle. For example: TYPE, CURSOR, EXCEPTION, etc. can be function names.
        return true;
    }

    /**
     * Scans package spec (currently returns empty segments, reserved for future use).
     *
     * @param packageSpecSql Clean package spec SQL
     * @return Scanned segments (empty for now)
     */
    public PackageSegments scanPackageSpec(String packageSpecSql) {
        // Future: Scan package spec if needed
        // For now, return empty segments (specs are small, not a bottleneck)
        return new PackageSegments();
    }

    /**
     * Internal result class for scan operations.
     */
    private static class ScanResult {
        final String functionName;
        final int nextTokenIndex;
        final boolean isForwardDeclaration;
        final PackageSegments.FunctionSegment segment;

        ScanResult(String functionName, int nextTokenIndex, boolean isForwardDeclaration,
                   PackageSegments.FunctionSegment segment) {
            this.functionName = functionName;
            this.nextTokenIndex = nextTokenIndex;
            this.isForwardDeclaration = isForwardDeclaration;
            this.segment = segment;
        }
    }
}
