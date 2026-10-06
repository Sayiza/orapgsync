package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the neutral fixtures in {@code src/test/resources/typemethod/} actually parse
 * through the {@code type_body} rule, so the coverage claim in their headers is checked rather
 * than asserted.
 *
 * <p>Parsing the whole body, rather than individual elements, is deliberate: it exercises the
 * {@code (type_body_elements)+} iteration, which is where a construct that leaves a token
 * unconsumed corrupts the element that follows it.
 */
public class DemoTypeFixtureParseTest {

    /** Matches "CREATE OR REPLACE TYPE BODY x IS ... END;" up to the SQL*Plus terminator. */
    private static final Pattern TYPE_BODY = Pattern.compile(
            "CREATE\\s+OR\\s+REPLACE\\s+TYPE\\s+(BODY\\s+.*?END\\s*;)\\s*^/\\s*$",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private String readResource(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "fixture not found on the test classpath: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> typeBodiesIn(String sql) {
        List<String> bodies = new ArrayList<>();
        Matcher m = TYPE_BODY.matcher(sql);
        while (m.find()) {
            bodies.add(m.group(1));
        }
        return bodies;
    }

    private List<String> parseErrors(String typeBody) {
        CharStream input = CharStreams.fromString(typeBody);
        PlSqlLexer lexer = new PlSqlLexer(input);
        lexer.removeErrorListeners();
        PlSqlParser parser = new PlSqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();

        List<String> errors = new ArrayList<>();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int pos,
                                    String msg, RecognitionException e) {
                errors.add("line " + line + ":" + pos + " " + msg);
            }
        });

        parser.type_body();
        return errors;
    }

    /**
     * Canary: without this, a harness that silently reported zero errors for everything would make
     * both tests below pass vacuously.
     */
    @Test
    @DisplayName("the parse harness actually detects a broken body")
    void harnessDetectsASyntaxError() {
        List<String> errors = parseErrors(
                "BODY demo_broken_t IS MEMBER FUNCTION f RETURN varchar2 IS BEGIN RETURN END;");
        assertFalse(errors.isEmpty(), "a missing expression after RETURN should be reported");
    }

    @Test
    @DisplayName("demo_types.sql: every TYPE BODY parses clean")
    void transformableFixtureParsesClean() throws Exception {
        List<String> bodies = typeBodiesIn(readResource("typemethod/demo_types.sql"));
        assertEquals(2, bodies.size(), "expected DEMO_ITEM_T and DEMO_TAG_T bodies");

        for (String body : bodies) {
            List<String> errors = parseErrors(body);
            assertTrue(errors.isEmpty(),
                    () -> "fixture does not parse:\n" + errors + "\n--- body ---\n"
                            + body.substring(0, Math.min(200, body.length())));
        }
    }

    /**
     * The join between the two halves of extraction: what {@link TypeMethodBoundaryScanner} cuts out
     * of a type body must be exactly what {@code AntlrParser.parseTypeMethod} can parse. Each half
     * had its own green tests while the pair was broken for months — the scanner closed a method at
     * its first {@code END IF} and the scanner tests only counted segments, so nothing compared the
     * two. Both fixture bodies exercise the constructs that truncated: {@code compare_to} ends in
     * {@code END IF; END;} and {@code describe} returns a {@code CASE} expression closed by a bare
     * {@code END}.
     */
    @Test
    @DisplayName("demo_types.sql: every scanned segment parses as a complete type method")
    void scannedSegmentsParseAsCompleteTypeMethods() throws Exception {
        List<String> bodies = typeBodiesIn(readResource("typemethod/demo_types.sql"));
        assertEquals(2, bodies.size(), "expected DEMO_ITEM_T and DEMO_TAG_T bodies");

        AntlrParser parser = new AntlrParser();
        int scanned = 0;

        for (String body : bodies) {
            List<TypeBodySegments.TypeMethodSegment> methods =
                    new TypeMethodBoundaryScanner().scanTypeBody(body).getMethods();
            assertFalse(methods.isEmpty(), "scanner found no methods in a fixture body");

            for (TypeBodySegments.TypeMethodSegment method : methods) {
                String source = body.substring(method.getStartPos(), method.getEndPos());
                ParseResult result = parser.parseTypeMethod(source);
                assertTrue(result.isSuccess(),
                        () -> "scanned segment for " + method.getName() + " does not parse: "
                                + result.getErrors() + "\n--- segment ---\n" + source);
                scanned++;
            }
        }

        // 10 in DEMO_ITEM_T (3 constructors, 2 member functions, 2 member procedures, 1 static
        // function, 1 static procedure, 1 map) + 3 in DEMO_TAG_T (constructor, member, order).
        assertEquals(13, scanned, "unexpected number of scanned type methods");
    }

    /**
     * The rejected fixture must still be syntactically reachable -- a construct that cannot even
     * be parsed can never reach the rejection that names it, and would surface as a generic parse
     * error instead. The inheritance body is the one that matters most: it parses clean, which is
     * precisely why OVERRIDING needs an explicit guard.
     */
    @Test
    @DisplayName("demo_types_rejected.sql: OVERRIDING body parses clean, so it needs a guard")
    void overridingRejectionCaseParsesClean() throws Exception {
        List<String> bodies = typeBodiesIn(readResource("typemethod/demo_types_rejected.sql"));

        String overridingBody = bodies.stream()
                .filter(b -> b.toUpperCase().contains("OVERRIDING"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no OVERRIDING type body in the fixture"));

        List<String> errors = parseErrors(overridingBody);
        assertTrue(errors.isEmpty(),
                () -> "expected OVERRIDING to parse clean (that is the hazard), got: " + errors);
    }
}
