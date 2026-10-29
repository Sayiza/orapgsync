package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A function or procedure implemented as a call spec ({@code AS LANGUAGE JAVA NAME '…'}) parses
 * clean but has no PL/SQL body. It must be refused by name rather than become a PostgreSQL function
 * with nothing in it. Package members of this form reach the transformer since the boundary scanner
 * records them (ROADMAP §6.A1) instead of derailing into the next member.
 */
class CallSpecRoutineRejectionTest {

    private final AntlrParser parser = new AntlrParser();

    private String transform(String source, boolean isFunction) {
        ParseResult parseResult = isFunction
                ? parser.parseFunctionBody(source)
                : parser.parseProcedureBody(source);
        assertFalse(parseResult.hasErrors(), () -> "Parse failed: " + parseResult.getErrors());

        TransformationIndices indices = new TransformationIndices(
                new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashMap<>(),
                Collections.emptyMap(), Collections.emptySet());
        TransformationContext context = new TransformationContext(
                "crypto", indices, new SimpleTypeEvaluator("crypto", indices));
        return new PostgresCodeBuilder(context).visit(parseResult.getTree());
    }

    @Test
    void javaFunctionIsRejectedByName() {
        String source = "FUNCTION digest(p_text VARCHAR2) RETURN VARCHAR2\n"
                + "AS LANGUAGE JAVA NAME 'demo.Digest.of(java.lang.String) return java.lang.String';";

        TransformationException e = assertThrows(TransformationException.class,
                () -> transform(source, true));
        assertTrue(e.getMessage().contains("'digest'"), e.getMessage());
        assertTrue(e.getMessage().contains("call spec"), e.getMessage());
    }

    @Test
    void javaProcedureIsRejectedByName() {
        String source = "PROCEDURE send_mail(p_to VARCHAR2)\n"
                + "IS LANGUAGE JAVA NAME 'demo.Mail.send(java.lang.String)';";

        TransformationException e = assertThrows(TransformationException.class,
                () -> transform(source, false));
        assertTrue(e.getMessage().contains("'send_mail'"), e.getMessage());
        assertTrue(e.getMessage().contains("call spec"), e.getMessage());
    }
}
