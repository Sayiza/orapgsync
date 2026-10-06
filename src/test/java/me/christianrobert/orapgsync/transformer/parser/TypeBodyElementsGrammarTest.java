package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of {@code type_body_elements}, the entry rule for type-method transformation.
 *
 * <p>This is a grammar characterization test, not a feature test: it pins down which alternative
 * each Oracle construct actually routes to, so that TYPE_METHOD_IMPLEMENTATION_PLAN section 3's
 * coverage matrix is checked against the grammar rather than assumed. If the grammar is
 * regenerated or edited, these tests say what changed.
 *
 * <p>Two results here drive design decisions in that plan:
 * <ul>
 *   <li>{@code OVERRIDING MEMBER FUNCTION} routes to <em>two different</em> alternatives depending
 *       on its return form and whether it has a body, so a visitor must be attached to both.</li>
 *   <li>A Java {@code call_spec} body parses <em>clean</em>, so rejecting it requires an explicit
 *       check; a parse failure will not catch it.</li>
 * </ul>
 */
public class TypeBodyElementsGrammarTest {

    private record ParseOutcome(String alternative, List<String> errors, int consumedTo, int length) {
        boolean isClean() {
            return errors.isEmpty();
        }

        boolean consumedEverything() {
            return consumedTo >= length;
        }
    }

    private ParseOutcome parse(String src) {
        String trimmed = src.trim();
        CharStream input = CharStreams.fromString(trimmed);
        PlSqlLexer lexer = new PlSqlLexer(input);
        lexer.removeErrorListeners();
        PlSqlParser parser = new PlSqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();

        List<String> errors = new ArrayList<>();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int pos,
                                    String msg, RecognitionException e) {
                errors.add(pos + ": " + msg);
            }
        });

        PlSqlParser.Type_body_elementsContext ctx = parser.type_body_elements();
        ParseTree first = ctx.getChildCount() > 0 ? ctx.getChild(0) : null;
        String alternative = first == null
                ? "<none>"
                : first.getClass().getSimpleName().replace("Context", "");
        int consumedTo = ctx.getStop() == null ? -1 : ctx.getStop().getStopIndex() + 1;
        return new ParseOutcome(alternative, errors, consumedTo, trimmed.length());
    }

    private void assertRoutesTo(String expectedAlternative, String src) {
        ParseOutcome o = parse(src);
        assertTrue(o.isClean(), () -> "expected a clean parse but got " + o.errors() + " for: " + src);
        assertEquals(expectedAlternative, o.alternative(), () -> "wrong alternative for: " + src);
    }

    @Nested
    @DisplayName("OVERRIDING routes to two different alternatives")
    class OverridingRouting {

        /**
         * modifier_clause in subprog_decl_in_type (alternative 2) already accepts OVERRIDING, and
         * ANTLR takes the lowest-numbered matching alternative, so the ordinary form never reaches
         * overriding_subprogram_spec (alternative 4).
         */
        @Test
        void ordinaryReturnTakesSubprogDeclInType() {
            assertRoutesTo("Subprog_decl_in_type",
                    "OVERRIDING MEMBER FUNCTION f RETURN varchar2 IS BEGIN RETURN 'x'; END;");
        }

        /**
         * These three forms are accepted only by overriding_function_spec: func_decl_in_type has no
         * RETURN SELF AS RESULT, no PIPELINED, and requires a body. So alternative 4 is live, and
         * the same logical construct reaches the visitor layer by two distinct routes.
         */
        @Test
        void returnSelfAsResultTakesOverridingSubprogramSpec() {
            assertRoutesTo("Overriding_subprogram_spec",
                    "OVERRIDING MEMBER FUNCTION f RETURN SELF AS RESULT IS BEGIN RETURN; END;");
        }

        @Test
        void bodyLessTakesOverridingSubprogramSpec() {
            assertRoutesTo("Overriding_subprogram_spec",
                    "OVERRIDING MEMBER FUNCTION f RETURN varchar2;");
        }

        @Test
        void pipelinedTakesOverridingSubprogramSpec() {
            assertRoutesTo("Overriding_subprogram_spec",
                    "OVERRIDING MEMBER FUNCTION f RETURN varchar2 PIPELINED IS BEGIN RETURN 'x'; END;");
        }

        /** overriding_function_spec covers FUNCTION only, so a procedure falls to alternative 2. */
        @Test
        void overridingProcedureTakesSubprogDeclInType() {
            assertRoutesTo("Subprog_decl_in_type",
                    "OVERRIDING MEMBER PROCEDURE p IS BEGIN NULL; END;");
        }

        @Test
        void notOverridingTakesSubprogDeclInType() {
            assertRoutesTo("Subprog_decl_in_type",
                    "NOT OVERRIDING MEMBER FUNCTION f RETURN varchar2 IS BEGIN RETURN 'x'; END;");
        }
    }

    @Nested
    @DisplayName("Function option keywords")
    class FunctionOptions {

        /** func_decl_in_type places DETERMINISTIC before IS/AS. */
        @Test
        void deterministicIsAcceptedOnAFunction() {
            assertRoutesTo("Subprog_decl_in_type",
                    "MEMBER FUNCTION f RETURN varchar2 DETERMINISTIC IS BEGIN RETURN 'x'; END;");
        }

        /** proc_decl_in_type has no DETERMINISTIC -- and neither does Oracle. Correctly rejected. */
        @Test
        void deterministicIsRejectedOnAProcedure() {
            ParseOutcome o = parse("MEMBER PROCEDURE p DETERMINISTIC IS BEGIN NULL; END;");
            assertFalse(o.isClean(), "DETERMINISTIC on a procedure should not parse");
        }

        /**
         * KNOWN GRAMMAR GAP. These are legal Oracle on a member function but func_decl_in_type
         * accepts only DETERMINISTIC. The asymmetry is stark for PIPELINED: it parses on an
         * OVERRIDING member function (via overriding_function_spec) and not on a plain one.
         *
         * <p>These assertions pin the gap rather than endorse it. If the grammar is extended, they
         * fail and should be inverted.
         */
        @Test
        void parallelEnableResultCacheAndPipelinedAreNotAccepted() {
            assertFalse(parse("MEMBER FUNCTION f RETURN varchar2 PARALLEL_ENABLE IS BEGIN RETURN 'x'; END;").isClean(),
                    "PARALLEL_ENABLE unexpectedly parses -- grammar gap may be fixed, update the plan");
            assertFalse(parse("MEMBER FUNCTION f RETURN varchar2 RESULT_CACHE IS BEGIN RETURN 'x'; END;").isClean(),
                    "RESULT_CACHE unexpectedly parses -- grammar gap may be fixed, update the plan");
            assertFalse(parse("MEMBER FUNCTION f RETURN varchar2 PIPELINED IS BEGIN RETURN 'x'; END;").isClean(),
                    "PIPELINED unexpectedly parses -- grammar gap may be fixed, update the plan");
        }
    }

    @Nested
    @DisplayName("The four alternatives and their parameter forms")
    class Alternatives {

        @Test
        void memberAndStaticSubprogramsRouteToSubprogDeclInType() {
            assertRoutesTo("Subprog_decl_in_type", "MEMBER PROCEDURE p IS BEGIN NULL; END;");
            assertRoutesTo("Subprog_decl_in_type", "STATIC PROCEDURE p IS BEGIN NULL; END;");
            assertRoutesTo("Subprog_decl_in_type",
                    "MEMBER FUNCTION f RETURN varchar2 IS BEGIN RETURN 'x'; END;");
        }

        @Test
        void mapAndOrderRouteToMapOrderFuncDeclaration() {
            assertRoutesTo("Map_order_func_declaration",
                    "MAP MEMBER FUNCTION f RETURN number IS BEGIN RETURN 1; END;");
            assertRoutesTo("Map_order_func_declaration",
                    "ORDER MEMBER FUNCTION f(o t) RETURN number IS BEGIN RETURN 1; END;");
        }

        /** All three constructor forms parse, including the explicit SELF IN OUT first parameter. */
        @Test
        void allConstructorFormsRouteToConstructorDeclaration() {
            assertRoutesTo("Constructor_declaration",
                    "CONSTRUCTOR FUNCTION t RETURN SELF AS RESULT IS BEGIN RETURN; END;");
            assertRoutesTo("Constructor_declaration",
                    "CONSTRUCTOR FUNCTION t(p varchar2) RETURN SELF AS RESULT IS BEGIN RETURN; END;");
            assertRoutesTo("Constructor_declaration",
                    "CONSTRUCTOR FUNCTION t(SELF IN OUT t, p varchar2) RETURN SELF AS RESULT "
                            + "IS BEGIN RETURN; END;");
        }

        @Test
        void nocopyAndDefaultParameterFormsParse() {
            assertRoutesTo("Subprog_decl_in_type",
                    "MEMBER FUNCTION f(a IN OUT NOCOPY varchar2, b number := 1) RETURN varchar2 "
                            + "IS BEGIN RETURN 'x'; END;");
        }
    }

    /**
     * A Java-implemented method body parses without error, so it cannot be caught by checking for
     * parse failures -- section 3.2 of the plan requires an explicit rejection. Note also that
     * call_spec leaves the trailing semicolon unconsumed, because func_decl_in_type places the ';'
     * inside the body alternative only.
     */
    @Test
    @DisplayName("A Java call_spec body parses clean, so it needs an explicit rejection")
    void javaCallSpecParsesCleanAndLeavesTrailingSemicolon() {
        ParseOutcome o = parse(
                "MEMBER FUNCTION f RETURN varchar2 IS LANGUAGE JAVA NAME 'X.y() return java.lang.String';");
        assertTrue(o.isClean(), () -> "expected a clean parse, got " + o.errors());
        assertEquals("Subprog_decl_in_type", o.alternative());
        assertFalse(o.consumedEverything(),
                "call_spec is expected to leave the trailing ';' unconsumed");
    }
}
