package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.MethodType;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.TypeMethodSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight state machine scanner for identifying type method boundaries in Oracle type bodies.
 *
 * This scanner provides a fast alternative to full ANTLR parsing when only method boundaries
 * are needed (e.g., for extraction jobs). It operates on comment-free source code to simplify
 * state management.
 *
 * **Performance:**
 * - Scans 1000-line type body in ~0.5 seconds vs. minutes for full ANTLR parse
 * - Memory: ~5KB vs. 200MB for full AST
 *
 * **Usage:**
 * ```java
 * String cleanedBody = CodeCleaner.removeComments(typeBodySql);
 * TypeMethodBoundaryScanner scanner = new TypeMethodBoundaryScanner();
 * TypeBodySegments segments = scanner.scanTypeBody(cleanedBody);
 * ```
 *
 * **IMPORTANT:** Input MUST be comment-free (use CodeCleaner.removeComments first).
 * Comments are not handled by this scanner to keep the state machine simple.
 */
public class TypeMethodBoundaryScanner {

    private static final Logger log = LoggerFactory.getLogger(TypeMethodBoundaryScanner.class);

    /**
     * Scanner states.
     */
    private enum State {
        TYPE_LEVEL,           // Initial state, looking for methods
        IN_MODIFIER,          // Inside MEMBER/STATIC/MAP/ORDER keyword
        IN_KEYWORD,           // Inside FUNCTION or PROCEDURE keyword
        IN_SIGNATURE,         // Tracking signature (parameters, RETURN clause)
        IN_SIGNATURE_PAREN,   // Inside parameter list parentheses
        IN_METHOD_BODY,       // Inside method implementation
        IN_STRING             // Inside string literal (ignore all keywords)
    }

    /**
     * Method modifier type.
     */
    private enum Modifier {
        NONE,
        MEMBER,
        STATIC,
        MAP,
        ORDER,
        CONSTRUCTOR
    }

    // Current state
    private State currentState;
    private State previousState; // For returning from IN_STRING

    // Position tracking
    private int position;
    private String source;

    // Current method being parsed
    private String currentMethodName;
    private int currentMethodStart;
    private int currentBodyStart;
    private Modifier currentModifier;
    private boolean currentIsFunction; // true = FUNCTION, false = PROCEDURE

    // Depth tracking
    private int parenDepth;
    private int bodyDepth; // BEGIN/END depth

    // Results
    private TypeBodySegments segments;

    /**
     * Scans type body and identifies method boundaries.
     *
     * IMPORTANT: Input must be comment-free (use CodeCleaner.removeComments first)
     *
     * @param typeBodySql Clean type body SQL (comments removed)
     * @return Scanned segments with method boundaries
     */
    public TypeBodySegments scanTypeBody(String typeBodySql) {
        log.debug("Scanning type body ({} chars)", typeBodySql.length());

        // Initialize
        this.source = typeBodySql;
        this.position = 0;
        this.currentState = State.TYPE_LEVEL;
        this.previousState = null;
        this.segments = new TypeBodySegments();
        this.parenDepth = 0;
        this.bodyDepth = 0;
        this.currentModifier = Modifier.NONE;

        // Scan character by character
        while (position < source.length()) {
            char currentChar = source.charAt(position);

            switch (currentState) {
                case TYPE_LEVEL:
                    handleTypeLevel(currentChar);
                    break;
                case IN_MODIFIER:
                    handleInModifier(currentChar);
                    break;
                case IN_KEYWORD:
                    handleInKeyword(currentChar);
                    break;
                case IN_SIGNATURE_PAREN:
                    handleInSignatureParen(currentChar);
                    break;
                case IN_SIGNATURE:
                    handleInSignature(currentChar);
                    break;
                case IN_METHOD_BODY:
                    handleInMethodBody(currentChar);
                    break;
                case IN_STRING:
                    handleInString(currentChar);
                    break;
            }

            position++;
        }

        log.debug("Scan complete: found {} methods", segments.getMethods().size());
        return segments;
    }

    private void handleTypeLevel(char currentChar) {
        // Check for string literal
        if (currentChar == '\'') {
            previousState = State.TYPE_LEVEL;
            currentState = State.IN_STRING;
            return;
        }

        // Check for method modifiers
        if (isKeyword(position, "MEMBER")) {
            currentModifier = Modifier.MEMBER;
            currentMethodStart = position;
            position += "MEMBER".length() - 1; // Advance past keyword
            currentState = State.IN_MODIFIER;
            log.trace("Found MEMBER at position {}", currentMethodStart);
            return;
        }

        if (isKeyword(position, "STATIC")) {
            currentModifier = Modifier.STATIC;
            currentMethodStart = position;
            position += "STATIC".length() - 1; // Advance past keyword
            currentState = State.IN_MODIFIER;
            log.trace("Found STATIC at position {}", currentMethodStart);
            return;
        }

        if (isKeyword(position, "MAP")) {
            currentModifier = Modifier.MAP;
            currentMethodStart = position;
            position += "MAP".length() - 1; // Advance past keyword
            currentState = State.IN_MODIFIER;
            log.trace("Found MAP at position {}", currentMethodStart);
            return;
        }

        if (isKeyword(position, "ORDER")) {
            currentModifier = Modifier.ORDER;
            currentMethodStart = position;
            position += "ORDER".length() - 1; // Advance past keyword
            currentState = State.IN_MODIFIER;
            log.trace("Found ORDER at position {}", currentMethodStart);
            return;
        }

        if (isKeyword(position, "CONSTRUCTOR")) {
            currentModifier = Modifier.CONSTRUCTOR;
            currentMethodStart = position;
            currentIsFunction = true; // Constructors are always functions
            // Skip CONSTRUCTOR keyword
            position += "CONSTRUCTOR".length();
            // Skip whitespace
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
            // Skip FUNCTION keyword
            if (isKeyword(position, "FUNCTION")) {
                position += "FUNCTION".length() - 1; // -1 for main loop increment
            }
            currentState = State.IN_KEYWORD;
            log.trace("Found CONSTRUCTOR at position {}", currentMethodStart);
            return;
        }
    }

    private void handleInModifier(char currentChar) {
        // Check for string literal
        if (currentChar == '\'') {
            previousState = State.IN_MODIFIER;
            currentState = State.IN_STRING;
            return;
        }

        // Check for FUNCTION/PROCEDURE keywords
        if (isKeyword(position, "FUNCTION")) {
            currentIsFunction = true;
            position += "FUNCTION".length() - 1; // Advance past keyword
            currentState = State.IN_KEYWORD;
            log.trace("Found FUNCTION after modifier at position {}", position);
            return;
        }

        if (isKeyword(position, "PROCEDURE")) {
            currentIsFunction = false;
            position += "PROCEDURE".length() - 1; // Advance past keyword
            currentState = State.IN_KEYWORD;
            log.trace("Found PROCEDURE after modifier at position {}", position);
            return;
        }

        // Check for nested MEMBER (MAP MEMBER / ORDER MEMBER pattern)
        if (isKeyword(position, "MEMBER")) {
            position += "MEMBER".length() - 1; // Advance past keyword
            // Continue in IN_MODIFIER state
            log.trace("Found nested MEMBER at position {}", position);
            return;
        }
    }

    private void handleInKeyword(char currentChar) {
        // Skip whitespace after keyword
        if (Character.isWhitespace(currentChar)) {
            return;
        }

        // Check for string literal
        if (currentChar == '\'') {
            previousState = State.IN_KEYWORD;
            currentState = State.IN_STRING;
            return;
        }

        // Check for function/procedure name (identifier after FUNCTION/PROCEDURE keyword)
        if (Character.isJavaIdentifierStart(currentChar)) {
            // Extract identifier
            int nameStart = position;
            int nameEnd = position;
            while (nameEnd < source.length() &&
                   (Character.isJavaIdentifierPart(source.charAt(nameEnd)) || source.charAt(nameEnd) == '_')) {
                nameEnd++;
            }
            currentMethodName = source.substring(nameStart, nameEnd);
            position = nameEnd - 1; // -1 because main loop increments
            log.trace("Extracted method name: {}", currentMethodName);

            // Move to signature state
            currentState = State.IN_SIGNATURE;
            return;
        }
    }

    private void handleInSignatureParen(char currentChar) {
        // Check for string literal
        if (currentChar == '\'') {
            previousState = State.IN_SIGNATURE_PAREN;
            currentState = State.IN_STRING;
            return;
        }

        // Track parenthesis depth
        if (currentChar == '(') {
            parenDepth++;
        } else if (currentChar == ')') {
            parenDepth--;
            if (parenDepth == 0) {
                currentState = State.IN_SIGNATURE;
                log.trace("Exiting parameter list for {}", currentMethodName);
            }
        }
    }

    private void handleInSignature(char currentChar) {
        // Check for string literal
        if (currentChar == '\'') {
            previousState = State.IN_SIGNATURE;
            currentState = State.IN_STRING;
            return;
        }

        // Check for IS (body start)
        if (isKeyword(position, "IS")) {
            currentBodyStart = position;
            position += 2 - 1; // Advance past IS (-1 for main loop increment)
            bodyDepth = 0;
            currentState = State.IN_METHOD_BODY;
            log.trace("Entering body for {} at position {}", currentMethodName, currentBodyStart);
            return;
        }

        // Check for AS (but skip "AS RESULT" pattern for constructors)
        if (isKeyword(position, "AS")) {
            // Look ahead to see if this is "AS RESULT" pattern
            int tempPos = position + 2; // Skip "AS"
            // Skip whitespace
            while (tempPos < source.length() && Character.isWhitespace(source.charAt(tempPos))) {
                tempPos++;
            }
            // Check if followed by "RESULT"
            if (isKeyword(tempPos, "RESULT")) {
                // This is "AS RESULT" - skip both keywords
                position = tempPos + "RESULT".length() - 1;
                log.trace("Found AS RESULT in signature for {}", currentMethodName);
                return;
            } else {
                // Just "AS" - this is the body start
                currentBodyStart = position;
                position += 2 - 1; // Advance past AS (-1 for main loop increment)
                bodyDepth = 0;
                currentState = State.IN_METHOD_BODY;
                log.trace("Entering body for {} at position {}", currentMethodName, currentBodyStart);
                return;
            }
        }

        // RETURN keyword in function signature - continue
        if (isKeyword(position, "RETURN")) {
            position += "RETURN".length() - 1; // Advance past RETURN
            log.trace("Found RETURN in signature for {}", currentMethodName);
            return;
        }
    }

    private void handleInMethodBody(char currentChar) {
        // Check for string literal
        if (currentChar == '\'') {
            previousState = State.IN_METHOD_BODY;
            currentState = State.IN_STRING;
            return;
        }

        // Track BEGIN/END depth
        if (isKeyword(position, "BEGIN")) {
            bodyDepth++;
            position += "BEGIN".length() - 1; // Advance past BEGIN
            log.trace("BEGIN found, bodyDepth={}", bodyDepth);
            return;
        }

        if (isKeyword(position, "END")) {
            bodyDepth--;
            position += "END".length() - 1; // Advance past END
            log.trace("END found, bodyDepth={}", bodyDepth);

            // Check if this is the final END
            if (bodyDepth == 0) {
                // Look for semicolon after END
                int semicolonPos = findNextSemicolon(position);
                if (semicolonPos > 0) {
                    // Method complete
                    recordMethod(semicolonPos + 1);
                    currentState = State.TYPE_LEVEL;
                    currentModifier = Modifier.NONE;
                }
            }
            return;
        }
    }

    private void handleInString(char currentChar) {
        // Check for string end (single quote, not escaped)
        if (currentChar == '\'') {
            // Check if next char is also quote (escaped quote)
            if (position + 1 < source.length() && source.charAt(position + 1) == '\'') {
                position++; // Skip escaped quote
                return;
            }

            // String end - return to previous state
            currentState = previousState;
            previousState = null;
        }
    }

    private void recordMethod(int endPos) {
        // Determine method type
        MethodType methodType = determineMethodType();

        // Create segment
        TypeMethodSegment segment = new TypeMethodSegment(
                currentMethodName,
                methodType,
                currentMethodStart,
                endPos,
                currentBodyStart,
                endPos - 1  // bodyEndPos (before semicolon)
        );

        segments.addMethod(segment);
        log.debug("Recorded method: {}", segment);

        // Reset current method tracking
        currentMethodName = null;
        currentMethodStart = -1;
        currentBodyStart = -1;
    }

    private MethodType determineMethodType() {
        switch (currentModifier) {
            case MEMBER:
                return currentIsFunction ? MethodType.MEMBER_FUNCTION : MethodType.MEMBER_PROCEDURE;
            case STATIC:
                return currentIsFunction ? MethodType.STATIC_FUNCTION : MethodType.STATIC_PROCEDURE;
            case MAP:
                return MethodType.MAP_FUNCTION;
            case ORDER:
                return MethodType.ORDER_FUNCTION;
            case CONSTRUCTOR:
                return MethodType.CONSTRUCTOR;
            default:
                // Default to MEMBER if no modifier specified
                return currentIsFunction ? MethodType.MEMBER_FUNCTION : MethodType.MEMBER_PROCEDURE;
        }
    }

    private int findNextSemicolon(int startPos) {
        for (int i = startPos; i < source.length(); i++) {
            if (source.charAt(i) == ';') {
                return i;
            }
        }
        return -1;
    }

    private boolean isKeyword(int pos, String keyword) {
        // Check if enough characters remain
        if (pos + keyword.length() > source.length()) {
            return false;
        }

        // Extract candidate
        String candidate = source.substring(pos, pos + keyword.length());

        // Case-insensitive match
        if (!candidate.equalsIgnoreCase(keyword)) {
            return false;
        }

        // Check word boundary before (if not at start)
        if (pos > 0) {
            char before = source.charAt(pos - 1);
            if (Character.isLetterOrDigit(before) || before == '_') {
                return false; // Part of identifier
            }
        }

        // Check word boundary after (if not at end)
        if (pos + keyword.length() < source.length()) {
            char after = source.charAt(pos + keyword.length());
            if (Character.isLetterOrDigit(after) || after == '_') {
                return false; // Part of identifier
            }
        }

        return true;
    }
}
