package me.christianrobert.orapgsync.transformer.typemethod;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Type method <b>call site</b> transformation — type-method plan Phase 4.
 *
 * <p>Phase 2 made type method bodies transformable. This covers the other half: what happens when
 * one is <i>called</i>. The two must agree, and until Phase 4 they did not — a member call on a
 * local variable emitted the <i>variable</i> name where the type name belongs and dropped the
 * instance argument entirely, producing a function that does not exist and, inside a PL/pgSQL
 * body, is not caught at {@code CREATE} but at runtime.
 */
class TypeMethodCallSiteTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        Map<String, Set<String>> typeMethods = new HashMap<>();
        typeMethods.put("hr.demo_item_t", Set.of("describe", "line_total", "make_code"));

        Map<String, Map<String, String>> typeFieldTypes = new HashMap<>();
        typeFieldTypes.put("hr.demo_item_t", Map.of("item_code", "varchar2"));

        indices = new TransformationIndices(
                new HashMap<>(),
                typeMethods,
                new HashSet<>(),
                new HashMap<>(),
                typeFieldTypes,
                Set.of("hr.demo_item_t")
        );
    }

    private String transformFunction(String oracleFunctionBody) {
        ParseResult parseResult = parser.parseFunctionBody(oracleFunctionBody);
        if (parseResult.hasErrors()) {
            fail("Parse failed: " + parseResult.getErrors());
        }
        TransformationContext context = new TransformationContext(
                "hr", indices, new SimpleTypeEvaluator("hr", indices));
        return new PostgresCodeBuilder(context).visit(parseResult.getTree()).trim()
                .replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("member method on a local variable passes the instance and uses the TYPE name")
    void memberCallOnLocalVariable() {
        String result = transformFunction("""
                FUNCTION f RETURN varchar2 IS
                  a demo_item_t;
                BEGIN
                  RETURN a.describe('x');
                END;
                """);

        System.out.println("\n=== memberCallOnLocalVariable ===\n" + result + "\n");

        assertTrue(result.contains("hr.demo_item_t__describe(a, 'x')")
                        || result.contains("hr.demo_item_t__describe( a , 'x' )"),
                "expected type-qualified call passing the instance, got: " + result);
        assertFalse(result.contains("a__describe"),
                "the VARIABLE name must not be used as the type name, got: " + result);
    }

    @Test
    @DisplayName("parameterless member method on a local variable still passes the instance")
    void parameterlessMemberCallOnLocalVariable() {
        String result = transformFunction("""
                FUNCTION f RETURN number IS
                  a demo_item_t;
                BEGIN
                  RETURN a.line_total();
                END;
                """);

        System.out.println("\n=== parameterlessMemberCallOnLocalVariable ===\n" + result + "\n");

        assertTrue(result.contains("hr.demo_item_t__line_total(a)")
                        || result.contains("hr.demo_item_t__line_total( a )"),
                "expected the instance as the sole argument, got: " + result);
    }

    @Test
    @DisplayName("static method is called on the TYPE, with no instance argument")
    void staticCallOnTypeName() {
        String result = transformFunction("""
                FUNCTION f RETURN varchar2 IS
                BEGIN
                  RETURN demo_item_t.make_code('raw');
                END;
                """);

        System.out.println("\n=== staticCallOnTypeName ===\n" + result + "\n");

        assertTrue(result.contains("hr.demo_item_t__make_code('raw')")
                        || result.contains("hr.demo_item_t__make_code( 'raw' )"),
                "expected a schema-qualified static call, got: " + result);
    }

    @Test
    @DisplayName("NEW t(...) transforms to the constructor function")
    void constructorWithNewKeyword() {
        String result = transformFunction("""
                FUNCTION f RETURN demo_item_t IS
                BEGIN
                  RETURN NEW demo_item_t('abc');
                END;
                """);

        System.out.println("\n=== constructorWithNewKeyword ===\n" + result + "\n");

        assertTrue(result.contains("hr.demo_item_t('abc')")
                        || result.contains("hr.demo_item_t( 'abc' )"),
                "NEW must be dropped and the constructor called, got: " + result);
        assertFalse(result.toUpperCase().contains("NEW "),
                "the NEW keyword has no PostgreSQL equivalent and must not survive, got: " + result);
    }
}
