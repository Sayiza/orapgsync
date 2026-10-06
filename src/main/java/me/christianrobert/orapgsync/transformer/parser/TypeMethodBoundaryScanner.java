package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.MethodType;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.TypeMethodSegment;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Lexer-based scanner for identifying type method boundaries in Oracle type bodies.
 *
 * <p>Tokenises with ANTLR's {@code PlSqlLexer} and applies state tracking to the token stream — the
 * same approach {@link FunctionBoundaryScanner} uses for package bodies, and for the same reason:
 * boundary detection needs to know what a keyword <em>is</em>, and a character scanner cannot tell.
 *
 * <h2>Why this replaced the character scanner (2026-08-27)</h2>
 *
 * <p>The previous implementation decremented its {@code BEGIN}/{@code END} depth on <b>every</b>
 * {@code END}. {@code END IF}, {@code END LOOP} and {@code END CASE} close no {@code BEGIN}, so the
 * depth reached zero at the first one and the method was recorded as ending there. The stored source
 * was a fragment; transformation then failed with {@code mismatched input '<EOF>'}. On the
 * production run of 2026-08-27 this accounted for ~325 of 351 transformation failures — the single
 * largest cause of type-method failures, and one the function path had already solved.
 *
 * <p>The truncation slipped through two nets. {@link TypeMethodSourcePairing} compares segment
 * <em>counts</em> against the data dictionary, and the orphaned tail of a truncated method contains
 * no modifier keyword, so no extra segment appeared and the counts still tallied. And
 * {@code TypeMethodBoundaryScannerTest.scan_orderMemberFunction} passed against the bug: it asserted
 * the method's name and count but never its extent, so a body cut off at {@code END IF;} looked
 * correct. Every test here now asserts the extracted text, not just the boundary count.
 *
 * <h2>Correctly handles</h2>
 * <ul>
 *   <li>{@code END IF} / {@code END LOOP} / {@code END CASE} — do not close a {@code BEGIN}</li>
 *   <li>A bare {@code END} closing a <b>CASE expression</b> ({@code x := CASE … END;}), which no
 *       lookahead can distinguish from a block {@code END} — hence the stack of what is open</li>
 *   <li>Nested {@code BEGIN} blocks, including a block inside a {@code CASE} branch, and local
 *       subprograms declared in a method's DECLARE section</li>
 *   <li>{@code RETURN SELF AS RESULT} — the {@code AS} is part of the signature, not the body</li>
 *   <li>{@code call_spec} bodies ({@code IS LANGUAGE JAVA NAME '…';}) — no {@code BEGIN}/{@code END}
 *       at all; see below</li>
 *   <li>Comments, string literals containing keywords, quoted identifiers, mixed case — all handled
 *       by the lexer rather than by hand</li>
 * </ul>
 *
 * <h2>Two boundaries that deliberately differ from the old scanner</h2>
 *
 * <p><b>Leading modifiers are part of the segment.</b> {@code OVERRIDING MEMBER FUNCTION f …} used
 * to be recorded from {@code MEMBER}, so the stored source parsed as an ordinary member method and
 * {@link me.christianrobert.orapgsync.transformer.builder.typemethod.TypeMethodRejection#overriding}
 * could never fire — the method was emitted as a plain function that silently discarded the
 * inheritance it exists for. The grammar admits {@code modifier_clause*} before {@code MEMBER |
 * STATIC} and {@code FINAL? INSTANTIABLE?} before {@code CONSTRUCTOR}, so starting the segment at
 * the first modifier is both valid and what makes the rejection reachable. Expect some methods that
 * previously reported success to become named rejections; that is the point.
 *
 * <p><b>A {@code call_spec} body is recorded, not skipped.</b> It has no {@code BEGIN}/{@code END},
 * so the old scanner hunted for an {@code END} that belonged to a later method and derailed the rest
 * of the type. Ending the segment at the call spec's semicolon lets the source reach the transformer,
 * which throws the named "implemented in Java/C" rejection for that one method instead of the whole
 * type being refused for a count mismatch.
 *
 * <p><b>Input need not be comment-free</b> — the lexer puts comments on a hidden channel. Positions
 * are always relative to the string passed in, so a caller that pre-cleans (as
 * {@code OracleTypeMethodExtractor} does) stays correct.
 */
public class TypeMethodBoundaryScanner {

    private static final Logger log = LoggerFactory.getLogger(TypeMethodBoundaryScanner.class);

    /**
     * Tokens that may precede {@code FUNCTION} / {@code PROCEDURE} in a type body element.
     *
     * <pre>
     * subprog_decl_in_type    : modifier_clause* (MEMBER | STATIC) (proc_decl_in_type | func_decl_in_type)
     * map_order_func_declaration : (MAP | ORDER) MEMBER func_decl_in_type
     * constructor_declaration : FINAL? INSTANTIABLE? CONSTRUCTOR FUNCTION …
     * overriding_subprogram_spec : OVERRIDING MEMBER overriding_function_spec
     * modifier_clause         : NOT? (INSTANTIABLE | FINAL | OVERRIDING | PERSISTABLE)
     * </pre>
     */
    private static final Set<Integer> METHOD_PREFIX_TOKENS = Set.of(
            PlSqlLexer.NOT, PlSqlLexer.INSTANTIABLE, PlSqlLexer.FINAL, PlSqlLexer.OVERRIDING,
            PlSqlLexer.PERSISTABLE, PlSqlLexer.MEMBER, PlSqlLexer.STATIC, PlSqlLexer.MAP,
            PlSqlLexer.ORDER, PlSqlLexer.CONSTRUCTOR);

    /** The prefix tokens that actually establish what kind of method this is. */
    private static final Set<Integer> METHOD_KIND_TOKENS = Set.of(
            PlSqlLexer.MEMBER, PlSqlLexer.STATIC, PlSqlLexer.MAP, PlSqlLexer.ORDER,
            PlSqlLexer.CONSTRUCTOR);

    /**
     * Scans a type body and identifies method boundaries.
     *
     * @param typeBodySql type body SQL (comments are fine, the lexer hides them)
     * @return segments in source order
     */
    public TypeBodySegments scanTypeBody(String typeBodySql) {
        log.debug("Scanning type body ({} chars)", typeBodySql.length());

        TypeBodySegments segments = new TypeBodySegments();
        List<Token> tokens = tokenize(typeBodySql);

        int i = 0;
        while (i < tokens.size()) {
            MethodStart start = methodStartAt(tokens, i);
            if (start != null) {
                ScanResult result = scanMethod(tokens, start, typeBodySql);
                if (result != null) {
                    segments.addMethod(result.segment);
                    log.debug("Found {} {} at [{}-{}]", result.segment.getMethodType(),
                            result.segment.getName(), result.segment.getStartPos(),
                            result.segment.getEndPos());
                    i = result.nextTokenIndex;
                    continue;
                }
                // Scan failed; resume after the FUNCTION/PROCEDURE keyword so the same prefix is
                // not re-detected on every subsequent token.
                i = start.subprogramIndex + 1;
                continue;
            }
            i++;
        }

        log.debug("Scan complete: found {} methods", segments.getMethods().size());
        return segments;
    }

    private List<Token> tokenize(String sql) {
        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        tokenStream.fill();

        List<Token> tokens = new ArrayList<>();
        for (Token t : tokenStream.getTokens()) {
            if (t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * Detects a method declaration starting at {@code index}: a run of modifier tokens that reaches
     * {@code FUNCTION} or {@code PROCEDURE} and includes at least one kind token. Requiring the run
     * to terminate in the subprogram keyword is what keeps {@code ORDER} (as in {@code ORDER BY}) and
     * {@code MAP} from being read as method starts.
     */
    private MethodStart methodStartAt(List<Token> tokens, int index) {
        if (!METHOD_PREFIX_TOKENS.contains(tokens.get(index).getType())) {
            return null;
        }

        boolean sawKindToken = false;
        int i = index;
        while (i < tokens.size() && METHOD_PREFIX_TOKENS.contains(tokens.get(i).getType())) {
            if (METHOD_KIND_TOKENS.contains(tokens.get(i).getType())) {
                sawKindToken = true;
            }
            i++;
        }

        if (!sawKindToken || i >= tokens.size()) {
            return null;
        }

        int subprogramType = tokens.get(i).getType();
        if (subprogramType != PlSqlLexer.FUNCTION && subprogramType != PlSqlLexer.PROCEDURE) {
            return null;
        }

        Modifier modifier = modifierOf(tokens, index, i);
        // Constructors are always functions, whatever the keyword run looked like.
        boolean isFunction = subprogramType == PlSqlLexer.FUNCTION;

        return new MethodStart(tokens.get(index).getStartIndex(), i, modifier, isFunction);
    }

    /**
     * CONSTRUCTOR wins over everything; MAP/ORDER over MEMBER, because they are spelled
     * {@code MAP MEMBER} and {@code ORDER MEMBER}; STATIC and MEMBER are mutually exclusive.
     */
    private Modifier modifierOf(List<Token> tokens, int from, int toExclusive) {
        Modifier modifier = Modifier.NONE;
        for (int i = from; i < toExclusive; i++) {
            switch (tokens.get(i).getType()) {
                case PlSqlLexer.CONSTRUCTOR:
                    return Modifier.CONSTRUCTOR;
                case PlSqlLexer.MAP:
                    modifier = Modifier.MAP;
                    break;
                case PlSqlLexer.ORDER:
                    modifier = Modifier.ORDER;
                    break;
                case PlSqlLexer.STATIC:
                    modifier = Modifier.STATIC;
                    break;
                case PlSqlLexer.MEMBER:
                    if (modifier == Modifier.NONE) {
                        modifier = Modifier.MEMBER;
                    }
                    break;
                default:
                    break;
            }
        }
        return modifier;
    }

    /**
     * Scans one method from its {@code FUNCTION}/{@code PROCEDURE} keyword to the semicolon after its
     * final {@code END}.
     *
     * @return the segment, or {@code null} if the method has no body or no terminating END was found
     */
    private ScanResult scanMethod(List<Token> tokens, MethodStart start, String source) {
        int i = start.subprogramIndex + 1;

        String methodName = readName(tokens, i);
        if (methodName == null) {
            log.warn("Could not read a method name after FUNCTION/PROCEDURE at position {}",
                    start.startPos);
            return null;
        }

        // Signature: run to IS/AS at paren depth 0. RETURN SELF AS RESULT must not be mistaken for
        // the body start -- that AS belongs to the return clause.
        int parenDepth = 0;
        int bodyStartPos = -1;
        while (i < tokens.size()) {
            int type = tokens.get(i).getType();

            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (parenDepth == 0) {
                if (type == PlSqlLexer.AS && typeAt(tokens, i + 1) == PlSqlLexer.RESULT) {
                    i += 2;
                    continue;
                }
                if (type == PlSqlLexer.IS || type == PlSqlLexer.AS) {
                    bodyStartPos = tokens.get(i).getStartIndex();
                    i++;
                    break;
                }
                if (type == PlSqlLexer.SEMICOLON) {
                    // Body-less form: a declaration only (the grammar makes the body optional on
                    // constructor_declaration and overriding_function_spec). There is no source to
                    // store, and the stub already covers the signature.
                    log.debug("Type method {} is declared without a body - no source to store",
                            methodName);
                    return null;
                }
            }
            i++;
        }

        if (bodyStartPos < 0) {
            log.warn("Could not find IS/AS for type method {} at position {}",
                    methodName, start.startPos);
            return null;
        }

        // call_spec: java_spec is LANGUAGE JAVA NAME '…', c_spec starts with LANGUAGE or EXTERNAL.
        // No block to balance -- the declaration ends at its semicolon.
        int firstBodyToken = typeAt(tokens, i);
        if (firstBodyToken == PlSqlLexer.LANGUAGE || firstBodyToken == PlSqlLexer.EXTERNAL) {
            return callSpecSegment(tokens, i, start, methodName, bodyStartPos, source);
        }

        return blockBodySegment(tokens, i, start, methodName, bodyStartPos, source);
    }

    /**
     * Walks the block structure to the {@code END} that closes the method.
     *
     * <p>A <b>stack</b> of what is open, not a pair of counters. Both {@code BEGIN} and {@code CASE}
     * can be closed by a bare {@code END}, so the only question a bare {@code END} ever asks is
     * "what is innermost?" — and two independent depth counters cannot answer it. A {@code BEGIN}
     * block inside a {@code CASE} branch is where counters get it wrong: the inner block's
     * {@code END} would be charged to the open CASE, and every {@code END} after it is off by one.
     *
     * <p>{@code nestedSubprogramDepth} stays a counter because a local function or procedure
     * declared before the method's own {@code BEGIN} does not nest inside anything — it empties the
     * stack and hands it back.
     */
    private ScanResult blockBodySegment(List<Token> tokens, int i, MethodStart start,
                                        String methodName, int bodyStartPos, String source) {
        Deque<Open> open = new ArrayDeque<>();
        int nestedSubprogramDepth = 0;

        while (i < tokens.size()) {
            int type = tokens.get(i).getType();

            if (type == PlSqlLexer.BEGIN) {
                open.push(Open.BLOCK);
            } else if (type == PlSqlLexer.CASE) {
                open.push(Open.CASE);
            } else if (type == PlSqlLexer.FUNCTION || type == PlSqlLexer.PROCEDURE) {
                if (isNestedSubprogramDeclaration(tokens, i)) {
                    nestedSubprogramDepth++;
                }
            } else if (type == PlSqlLexer.END) {
                int next = typeAt(tokens, i + 1);

                if (next == PlSqlLexer.IF || next == PlSqlLexer.LOOP) {
                    // END IF / END LOOP close a control structure, not a block.
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
                    // An END with nothing open cannot be this method's: no BEGIN ever opened.
                    break;
                }
                if (open.pop() == Open.CASE) {
                    // `x := CASE … END` -- the expression form, closed by a bare END.
                    i++;
                    continue;
                }

                if (open.isEmpty()) {
                    if (nestedSubprogramDepth > 0) {
                        nestedSubprogramDepth--;
                        i++;
                        continue;
                    }
                    return finish(tokens, i, start, methodName, bodyStartPos,
                            tokens.get(i).getStartIndex(), source);
                }
            }
            i++;
        }

        log.warn("Could not find the closing END for type method {} starting at position {}",
                methodName, start.startPos);
        return null;
    }

    private ScanResult callSpecSegment(List<Token> tokens, int i, MethodStart start,
                                       String methodName, int bodyStartPos, String source) {
        int parenDepth = 0;
        for (int j = i; j < tokens.size(); j++) {
            int type = tokens.get(j).getType();
            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (type == PlSqlLexer.SEMICOLON && parenDepth == 0) {
                log.debug("Type method {} has a call_spec body (LANGUAGE JAVA / C)", methodName);
                return record(start, methodName, bodyStartPos,
                        tokens.get(j).getStartIndex(), tokens.get(j).getStopIndex() + 1, j + 1);
            }
        }
        log.warn("Unterminated call spec for type method {}", methodName);
        return null;
    }

    /** Consumes {@code END [name]} through its semicolon and records the segment. */
    private ScanResult finish(List<Token> tokens, int endIndex, MethodStart start, String methodName,
                              int bodyStartPos, int bodyEndPos, String source) {
        for (int j = endIndex + 1; j < tokens.size(); j++) {
            if (tokens.get(j).getType() == PlSqlLexer.SEMICOLON) {
                return record(start, methodName, bodyStartPos, bodyEndPos,
                        tokens.get(j).getStopIndex() + 1, j + 1);
            }
        }
        // END at the very end of the source without its semicolon.
        return record(start, methodName, bodyStartPos, bodyEndPos, source.length(), tokens.size());
    }

    private ScanResult record(MethodStart start, String methodName,
                              int bodyStartPos, int bodyEndPos, int endPos, int nextTokenIndex) {
        TypeMethodSegment segment = new TypeMethodSegment(
                methodName,
                methodType(start),
                start.startPos,
                endPos,
                bodyStartPos,
                bodyEndPos);
        return new ScanResult(segment, nextTokenIndex);
    }

    /**
     * Reads the method name: the identifier after {@code FUNCTION}/{@code PROCEDURE}, taking the last
     * component of a dotted name so that a constructor written {@code schema.type} yields the type.
     */
    private String readName(List<Token> tokens, int index) {
        if (index >= tokens.size() || !isIdentifierToken(tokens.get(index))) {
            return null;
        }
        String name = tokens.get(index).getText();
        int i = index + 1;
        while (i + 1 < tokens.size()
                && tokens.get(i).getType() == PlSqlLexer.PERIOD
                && isIdentifierToken(tokens.get(i + 1))) {
            name = tokens.get(i + 1).getText();
            i += 2;
        }
        return name;
    }

    /**
     * A nested {@code FUNCTION}/{@code PROCEDURE} with a body (IS/AS before the semicolon) brings its
     * own {@code BEGIN}…{@code END}; a forward declaration does not.
     */
    private boolean isNestedSubprogramDeclaration(List<Token> tokens, int index) {
        int parenDepth = 0;
        for (int i = index + 1; i < tokens.size(); i++) {
            int type = tokens.get(i).getType();
            if (type == PlSqlLexer.LEFT_PAREN) {
                parenDepth++;
            } else if (type == PlSqlLexer.RIGHT_PAREN) {
                parenDepth--;
            } else if (parenDepth == 0) {
                if (type == PlSqlLexer.IS || type == PlSqlLexer.AS) {
                    return true;
                }
                if (type == PlSqlLexer.SEMICOLON) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Oracle lets most keywords serve as identifiers. Mirrors {@link FunctionBoundaryScanner}'s
     * "not a structural keyword" rule rather than enumerating what is allowed.
     */
    private boolean isIdentifierToken(Token token) {
        int type = token.getType();

        if (type == PlSqlLexer.REGULAR_ID || type == PlSqlLexer.DELIMITED_ID) {
            return true;
        }

        return type != PlSqlLexer.SEMICOLON
                && type != PlSqlLexer.LEFT_PAREN
                && type != PlSqlLexer.RIGHT_PAREN
                && type != PlSqlLexer.COMMA
                && type != PlSqlLexer.PERIOD
                && type != PlSqlLexer.ASSIGN_OP
                && type != PlSqlLexer.COLON
                && type != PlSqlLexer.IS
                && type != PlSqlLexer.AS
                && type != PlSqlLexer.BEGIN
                && type != PlSqlLexer.END
                && type != PlSqlLexer.RETURN
                && type != PlSqlLexer.FUNCTION
                && type != PlSqlLexer.PROCEDURE
                && type != PlSqlLexer.CHAR_STRING
                && type != PlSqlLexer.UNSIGNED_INTEGER
                && type != PlSqlLexer.APPROXIMATE_NUM_LIT;
    }

    private int typeAt(List<Token> tokens, int index) {
        return index < tokens.size() ? tokens.get(index).getType() : -1;
    }

    private MethodType methodType(MethodStart start) {
        switch (start.modifier) {
            case MEMBER:
                return start.isFunction ? MethodType.MEMBER_FUNCTION : MethodType.MEMBER_PROCEDURE;
            case STATIC:
                return start.isFunction ? MethodType.STATIC_FUNCTION : MethodType.STATIC_PROCEDURE;
            case MAP:
                return MethodType.MAP_FUNCTION;
            case ORDER:
                return MethodType.ORDER_FUNCTION;
            case CONSTRUCTOR:
                return MethodType.CONSTRUCTOR;
            default:
                return start.isFunction ? MethodType.MEMBER_FUNCTION : MethodType.MEMBER_PROCEDURE;
        }
    }

    /** What a bare {@code END} could be closing. */
    private enum Open {
        BLOCK, CASE
    }

    private enum Modifier {
        NONE, MEMBER, STATIC, MAP, ORDER, CONSTRUCTOR
    }

    /** Where a method declaration begins, and what the modifier run said about it. */
    private record MethodStart(int startPos, int subprogramIndex, Modifier modifier,
                               boolean isFunction) {
    }

    private record ScanResult(TypeMethodSegment segment, int nextTokenIndex) {
    }
}
