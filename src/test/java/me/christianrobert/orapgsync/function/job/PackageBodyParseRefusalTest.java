package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A package body that did not parse is refused, not used.
 *
 * <p>{@code buildPackageContext} used to log the parse error and carry on "best effort". The tree
 * ANTLR returns after error recovery is not a partial reading of the package — it contains
 * invented nodes, and {@code extractBodyVariables} cannot tell them from real declarations. On the
 * 2026-08-30 full run that produced 554 packages whose helper SQL read {@code RETURNS .} and 101
 * reading {@code RETURNS when}, blocking 14,781 functions between them with an error describing
 * PostgreSQL's opinion of DDL that should never have been generated.
 */
class PackageBodyParseRefusalTest {

    private static final String UNPARSEABLE = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              gitemtab order.tTable;
            END test_pkg;
            """;

    private static ParseResult parse(String body) {
        return new AntlrParser().parsePackageBody(body);
    }

    @Test
    @DisplayName("A package body that did not parse refuses the package")
    void unparseableBodyRefusesThePackage() {
        ParseResult result = parse(UNPARSEABLE);
        assertTrue(result.hasErrors(), "precondition: this body does not parse");

        TransformationException e = assertThrows(TransformationException.class,
                () -> PostgresFunctionImplementationJob.requireParsedBody(result));

        assertTrue(e.getMessage().contains("Package body failed to parse"), e.getMessage());
    }

    @Test
    @DisplayName("The refusal carries the parser's message, so the cause is nameable")
    void refusalNamesTheParseError() {
        TransformationException e = assertThrows(TransformationException.class,
                () -> PostgresFunctionImplementationJob.requireParsedBody(parse(UNPARSEABLE)));

        assertTrue(e.getMessage().contains("no viable alternative")
                        || e.getMessage().contains("mismatched input")
                        || e.getMessage().contains("extraneous input"),
                "the ANTLR message is the thing that ranks the remaining grammar gaps, was: "
                        + e.getMessage());
    }

    @Test
    @DisplayName("Error recovery invents variables, which is why the tree cannot be trusted")
    void recoveryTreeYieldsVariablesOracleDoesNotHave() {
        // Pins the mechanism the refusal exists for, driving the same two classes the job does.
        // If ANTLR's recovery ever stops producing this the refusal is still correct, but this
        // test should be re-read rather than deleted.
        ParseResult result = parse(UNPARSEABLE);

        PackageContext ctx = new PackageContext("hr", "test_pkg");
        new PackageContextExtractor(new AntlrParser()).extractBodyVariables(
                (PlSqlParser.Create_package_bodyContext) result.getTree(), ctx);

        assertTrue(ctx.hasVariable("."),
                "the recovered tree yields a variable named '.', which is what produced "
                        + "CREATE FUNCTION ... RETURNS . ; variables were: "
                        + ctx.getVariables().keySet());
    }

    @Test
    @DisplayName("A clean body is left alone")
    void parsedBodyIsAccepted() {
        ParseResult result = parse("""
                CREATE OR REPLACE PACKAGE BODY test_pkg AS
                  gcounter NUMBER := 0;
                END test_pkg;
                """);

        assertFalse(result.hasErrors());
        assertDoesNotThrow(() -> PostgresFunctionImplementationJob.requireParsedBody(result));
    }
}
