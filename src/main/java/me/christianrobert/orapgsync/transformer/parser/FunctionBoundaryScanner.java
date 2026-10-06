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
 * - Nested functions/procedures (inner END doesn't close outer function)
 * - Forward declarations (signature without body)
 * - Mixed case keywords
 * - String literals containing keywords
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

        // Now we're after IS/AS - record body start position
        // Find first non-whitespace token position for body start
        int bodyStartPos = -1;
        while (i < tokens.size() && bodyStartPos < 0) {
            Token t = tokens.get(i);
            if (t.getType() != PlSqlLexer.EOF && t.getChannel() == Token.DEFAULT_CHANNEL) {
                bodyStartPos = t.getStartIndex();
            }
            if (t.getType() == PlSqlLexer.BEGIN || t.getType() == PlSqlLexer.FUNCTION ||
                t.getType() == PlSqlLexer.PROCEDURE || t.getType() == PlSqlLexer.CURSOR ||
                t.getType() == PlSqlLexer.TYPE || isIdentifierToken(t)) {
                // Found start of declarations or BEGIN
                break;
            }
            i++;
        }

        if (bodyStartPos < 0 && i < tokens.size()) {
            bodyStartPos = tokens.get(i).getStartIndex();
        }

        // Track block depth to find the matching END
        // BEGIN increments, END (not followed by IF/LOOP/CASE) decrements
        // Also track nested FUNCTION/PROCEDURE definitions
        int blockDepth = 0;
        int nestedFunctionDepth = 0;
        int bodyEndPos = -1;
        int endPos = -1;

        while (i < tokens.size()) {
            Token t = tokens.get(i);
            int type = t.getType();

            if (type == PlSqlLexer.EOF) {
                break;
            }

            // Check for nested FUNCTION/PROCEDURE declaration
            if (type == PlSqlLexer.FUNCTION || type == PlSqlLexer.PROCEDURE) {
                // Look ahead to see if this is a declaration (has IS/AS before semicolon)
                if (isNestedFunctionDeclaration(tokens, i)) {
                    nestedFunctionDepth++;
                    log.trace("Nested function detected at token {}, depth now {}", i, nestedFunctionDepth);
                }
            }

            // Track BEGIN
            if (type == PlSqlLexer.BEGIN) {
                blockDepth++;
                log.trace("BEGIN at token {}, depth now {}", i, blockDepth);
            }

            // Track END
            if (type == PlSqlLexer.END) {
                // Peek at next token to check for IF/LOOP/CASE
                Token nextToken = getNextDefaultChannelToken(tokens, i + 1);

                if (nextToken != null && isEndControlStructure(nextToken.getType())) {
                    // END IF, END LOOP, END CASE - skip, doesn't close BEGIN
                    log.trace("END {} at token {} - skipping (control structure)", nextToken.getText(), i);
                    i++; // Skip past the IF/LOOP/CASE token (will be incremented again at loop end)
                } else {
                    // Plain END or END <name> - closes a BEGIN block
                    blockDepth--;
                    log.trace("END at token {}, depth now {}", i, blockDepth);

                    if (blockDepth <= 0) {
                        if (nestedFunctionDepth > 0) {
                            // This END closes a nested function, not the outer one
                            nestedFunctionDepth--;
                            blockDepth = 0; // Reset to avoid going more negative
                            log.trace("END closes nested function, nested depth now {}", nestedFunctionDepth);
                        } else {
                            // This END closes the main function
                            bodyEndPos = t.getStartIndex();

                            // Find the semicolon after END [name]
                            int j = i + 1;
                            while (j < tokens.size()) {
                                Token semicolonCandidate = tokens.get(j);
                                if (semicolonCandidate.getType() == PlSqlLexer.SEMICOLON) {
                                    endPos = semicolonCandidate.getStopIndex() + 1;
                                    break;
                                } else if (semicolonCandidate.getType() == PlSqlLexer.EOF) {
                                    endPos = source.length();
                                    break;
                                }
                                j++;
                            }

                            PackageSegments.FunctionSegment segment = new PackageSegments.FunctionSegment(
                                    functionName,
                                    startPos,
                                    endPos,
                                    bodyStartPos,
                                    bodyEndPos,
                                    isFunction
                            );

                            return new ScanResult(functionName, j + 1, false, segment);
                        }
                    }
                }
            }

            i++;
        }

        log.warn("Could not find END for function {} starting at position {}", functionName, startPos);
        return null;
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
     * Checks if the token type indicates END LOOP, END IF, or END CASE.
     */
    private boolean isEndControlStructure(int tokenType) {
        return tokenType == PlSqlLexer.LOOP ||
               tokenType == PlSqlLexer.IF ||
               tokenType == PlSqlLexer.CASE;
    }

    /**
     * Gets the next token on the default channel (skipping hidden channel tokens).
     */
    private Token getNextDefaultChannelToken(List<Token> tokens, int startIndex) {
        for (int i = startIndex; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != PlSqlLexer.EOF) {
                return t;
            }
        }
        return null;
    }

    /**
     * Checks if the FUNCTION/PROCEDURE at the given index is a nested function declaration
     * (has IS/AS before semicolon).
     */
    private boolean isNestedFunctionDeclaration(List<Token> tokens, int funcIndex) {
        int parenDepth = 0;
        for (int i = funcIndex + 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            int type = t.getType();

            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (parenDepth == 0) {
                if (type == PlSqlLexer.IS || type == PlSqlLexer.AS) {
                    return true; // Has body
                } else if (type == PlSqlLexer.SEMICOLON) {
                    return false; // Forward declaration
                }
            }

            if (type == PlSqlLexer.EOF) {
                break;
            }
        }
        return false;
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
