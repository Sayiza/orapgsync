package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import org.antlr.v4.runtime.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Finds where a PL/SQL subprogram body ends, on a lexer token stream. The one block-structure walker
 * shared by {@link FunctionBoundaryScanner} (package bodies) and {@link TypeMethodBoundaryScanner}
 * (type bodies).
 *
 * <h2>Why one shared walker</h2>
 *
 * <p>Both scanners have to answer the same question — which {@code END} closes this subprogram — and
 * they used to answer it separately. Phase 2b of the type-method plan taught the type-method copy
 * that a bare {@code END} also closes a CASE <em>expression</em> ({@code x := CASE … END;}); the
 * function copy never learned it, so every package function containing one ended at that
 * {@code END}. The rest of the function then landed in the reduced package body, which no longer
 * parsed, and the whole package was refused: on the 2026-10-24 run the largest single cause of
 * blocked functions (ROADMAP §6.A1). Two copies drifting apart is how that survived, so there is
 * one copy.
 *
 * <h2>What it tracks</h2>
 *
 * <ul>
 *   <li><b>A stack of what is open</b> in a statement section, not a pair of counters. Both
 *       {@code BEGIN} and {@code CASE} can be closed by a bare {@code END}; the only question a bare
 *       {@code END} asks is "what is innermost?", and a block inside a CASE branch is where
 *       independent counters charge an {@code END} to the wrong opener.</li>
 *   <li>{@code END IF} / {@code END LOOP} close no {@code BEGIN}; {@code END CASE} closes a CASE
 *       <em>statement</em>.</li>
 *   <li><b>Open loops are on the stack too</b>, because {@code END LOOP} is not always one: in
 *       {@code FOR i IN 1 .. CASE … END LOOP} the {@code END} closes the bound's CASE expression and
 *       the {@code LOOP} opens the body. Token adjacency cannot tell the two apart; what is
 *       innermost can.</li>
 *   <li><b>A name after a period is a name</b>, whatever the lexer calls it: {@code b.begin} is a
 *       column, not a block.</li>
 *   <li><b>A block's {@code END} is {@code END [label];}</b> and nothing else. Oracle accepts
 *       {@code END} as a column alias ({@code SELECT NULL END FROM …}), so a bare {@code END} that
 *       is not followed by a semicolon — directly or after a label — closes no {@code BEGIN}.</li>
 *   <li><b>Declare sections by recursion.</b> A local subprogram is only ever declared in a declare
 *       section — the routine's own, or a nested {@code DECLARE … BEGIN … END} block's — so it is
 *       scanned there, to its own {@code END}, and skipped. A depth counter for "nested subprograms
 *       seen" (the previous design) has two failure modes this avoids: a subprogram declared in a
 *       nested {@code DECLARE} block was charged to the routine's final {@code END}, so the routine
 *       ran on into the next one; and a {@code FUNCTION} token in a statement section (a column
 *       named {@code function}, aliased with {@code AS}) was counted as a declaration.</li>
 *   <li><b>Call specs</b> ({@code IS LANGUAGE JAVA NAME '…';}, {@code IS EXTERNAL …;}) have no
 *       {@code BEGIN}/{@code END}; they end at their semicolon.</li>
 * </ul>
 *
 * <p>Tokens are expected on the default channel only, without the {@code EOF} token — comments,
 * whitespace and string contents are the lexer's business, which is the reason both scanners are
 * lexer-based in the first place.
 */
final class PlSqlBlockScanner {

    /** What is open in a statement section, innermost on top. */
    private enum Open {
        BLOCK, CASE, LOOP
    }

    private PlSqlBlockScanner() {
    }

    /**
     * Whether a subprogram body starting at {@code index} (the first token after its {@code IS} /
     * {@code AS}) is a call spec rather than a PL/SQL block.
     */
    static boolean isCallSpec(List<Token> tokens, int index) {
        int type = typeAt(tokens, index);
        return type == PlSqlLexer.LANGUAGE || type == PlSqlLexer.EXTERNAL;
    }

    /**
     * Finds the semicolon that ends a call spec starting at {@code index}.
     *
     * @return index of the semicolon, or -1 if there is none
     */
    static int findCallSpecEnd(List<Token> tokens, int index) {
        int parenDepth = 0;
        for (int i = index; i < tokens.size(); i++) {
            int type = tokens.get(i).getType();
            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (type == PlSqlLexer.SEMICOLON && parenDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the {@code END} that closes a PL/SQL subprogram body.
     *
     * @param index the first token after the subprogram's {@code IS} / {@code AS}: the start of its
     *              declare section, or its {@code BEGIN}
     * @return index of the closing {@code END} token, or -1 if the structure does not close
     */
    static int findBodyEnd(List<Token> tokens, int index) {
        int begin = skipDeclareSection(tokens, index);
        if (begin < 0) {
            return -1;
        }
        return findBlockEnd(tokens, begin);
    }

    /**
     * Walks a declare section up to its {@code BEGIN}, skipping every local subprogram whole.
     *
     * <p>Nothing else in a declare section matters: a CASE expression in a default value or a cursor
     * query closes with an {@code END} that cannot be mistaken for anything, because no
     * {@code BEGIN} is open here — the walk is only looking for the section's {@code BEGIN}.
     *
     * @return index of the {@code BEGIN} that ends the section, or -1
     */
    private static int skipDeclareSection(List<Token> tokens, int index) {
        boolean atDeclarationStart = true;
        int i = index;
        while (i < tokens.size()) {
            int type = structuralTypeAt(tokens, i);
            if (type == PlSqlLexer.BEGIN) {
                return i;
            }
            if (atDeclarationStart && (type == PlSqlLexer.FUNCTION || type == PlSqlLexer.PROCEDURE)) {
                int next = skipSubprogram(tokens, i);
                if (next < 0) {
                    return -1;
                }
                i = next;
                atDeclarationStart = true;
                continue;
            }
            atDeclarationStart = type == PlSqlLexer.SEMICOLON;
            i++;
        }
        return -1;
    }

    /**
     * Skips a local subprogram — a forward declaration, a call spec, or a full definition.
     *
     * @param index the {@code FUNCTION} / {@code PROCEDURE} token
     * @return index of the first token after the subprogram's terminating semicolon, or -1
     */
    private static int skipSubprogram(List<Token> tokens, int index) {
        int bodyStart = findBodyStart(tokens, index + 1);
        if (bodyStart == FORWARD_DECLARATION_END) {
            return nextAfterSemicolon(tokens, index + 1);
        }
        if (bodyStart < 0) {
            return -1;
        }
        if (isCallSpec(tokens, bodyStart)) {
            int semicolon = findCallSpecEnd(tokens, bodyStart);
            return semicolon < 0 ? -1 : semicolon + 1;
        }
        int end = findBodyEnd(tokens, bodyStart);
        return end < 0 ? -1 : nextAfterSemicolon(tokens, end + 1);
    }

    private static final int FORWARD_DECLARATION_END = -2;

    /**
     * Scans a signature to its {@code IS} / {@code AS} at parenthesis depth 0.
     *
     * @return index of the first body token, {@link #FORWARD_DECLARATION_END} if a semicolon came
     *         first, or -1 if neither did
     */
    private static int findBodyStart(List<Token> tokens, int index) {
        int parenDepth = 0;
        for (int i = index; i < tokens.size(); i++) {
            int type = tokens.get(i).getType();
            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (parenDepth == 0) {
                if (type == PlSqlLexer.IS || type == PlSqlLexer.AS) {
                    return i + 1;
                }
                if (type == PlSqlLexer.SEMICOLON) {
                    return FORWARD_DECLARATION_END;
                }
            }
        }
        return -1;
    }

    /**
     * Walks a statement section from its {@code BEGIN} to the {@code END} that closes it.
     *
     * @param index the {@code BEGIN} token
     * @return index of the closing {@code END}, or -1
     */
    private static int findBlockEnd(List<Token> tokens, int index) {
        Deque<Open> open = new ArrayDeque<>();
        int i = index;
        while (i < tokens.size()) {
            int type = structuralTypeAt(tokens, i);

            if (type == PlSqlLexer.BEGIN) {
                open.push(Open.BLOCK);
            } else if (type == PlSqlLexer.CASE) {
                open.push(Open.CASE);
            } else if (type == PlSqlLexer.LOOP) {
                open.push(Open.LOOP);
            } else if (type == PlSqlLexer.DECLARE) {
                // A nested block's declare section may declare subprograms of its own; walk it to
                // its BEGIN, which the next iteration pushes.
                int begin = skipDeclareSection(tokens, i + 1);
                if (begin < 0) {
                    return -1;
                }
                i = begin;
                continue;
            } else if (type == PlSqlLexer.END) {
                int next = typeAt(tokens, i + 1);
                if (next == PlSqlLexer.LOOP) {
                    if (open.peek() == Open.CASE) {
                        // `FOR i IN 1 .. CASE … END LOOP`: the END closes the bound's CASE
                        // expression, and the LOOP that follows opens the loop body.
                        open.pop();
                        i++;
                        continue;
                    }
                    if (open.peek() == Open.LOOP) {
                        open.pop();
                    }
                    i += 2;
                    continue;
                }
                if (next == PlSqlLexer.IF) {
                    i += 2;
                    continue;
                }
                if (next == PlSqlLexer.CASE) {
                    if (open.peek() == Open.CASE) {
                        open.pop();
                    }
                    i += 2;
                    continue;
                }
                if (open.isEmpty()) {
                    return -1;
                }
                if (open.peek() != Open.CASE && !terminatesBlock(tokens, i)) {
                    // `SELECT …, NULL END FROM …`: Oracle accepts END as a column alias. A block's
                    // END is always `END [label];`, so an END that is not is no block's END.
                    i++;
                    continue;
                }
                open.pop();
                if (open.isEmpty()) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /**
     * Whether the {@code END} at {@code index} has the shape of a block terminator,
     * {@code END [label];} — or stands at the very end of the source, which the callers accept.
     */
    private static boolean terminatesBlock(List<Token> tokens, int index) {
        int next = typeAt(tokens, index + 1);
        if (next == PlSqlLexer.SEMICOLON || next == -1) {
            return true;
        }
        int afterLabel = typeAt(tokens, index + 2);
        return afterLabel == PlSqlLexer.SEMICOLON || afterLabel == -1;
    }

    /**
     * The token's type, or {@code REGULAR_ID} when it follows a period: whatever the lexer calls it,
     * the name after a {@code .} is a name ({@code b.begin}, {@code t.end}, {@code r.case}).
     */
    private static int structuralTypeAt(List<Token> tokens, int index) {
        if (index > 0 && tokens.get(index - 1).getType() == PlSqlLexer.PERIOD) {
            return PlSqlLexer.REGULAR_ID;
        }
        return tokens.get(index).getType();
    }

    private static int nextAfterSemicolon(List<Token> tokens, int index) {
        for (int i = index; i < tokens.size(); i++) {
            if (tokens.get(i).getType() == PlSqlLexer.SEMICOLON) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int typeAt(List<Token> tokens, int index) {
        return index < tokens.size() ? tokens.get(index).getType() : -1;
    }
}
