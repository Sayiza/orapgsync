package me.christianrobert.orapgsync.transformer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IdentifierHelper}.
 *
 * <p>The central property under test is that {@code emit} is a no-op for unquoted identifiers.
 * That is what makes the delimited-identifier fix incapable of regressing a transformation that
 * already worked, and it is the reason {@code USER} and {@code LEVEL} keep their PostgreSQL
 * keyword meaning.
 */
class IdentifierHelperTest {

    @Nested
    @DisplayName("unquote")
    class Unquote {

        @Test
        void leavesUnquotedTextAlone() {
            assertEquals("run_id", IdentifierHelper.unquote("run_id"));
            assertEquals("RUN_ID", IdentifierHelper.unquote("RUN_ID"));
            assertEquals("MiXeD", IdentifierHelper.unquote("MiXeD"));
        }

        @Test
        void stripsDelimitersWithoutFoldingCase() {
            assertEquals("RUN_ID", IdentifierHelper.unquote("\"RUN_ID\""));
            assertEquals("MyCol", IdentifierHelper.unquote("\"MyCol\""));
        }

        @Test
        void unescapesDoubledQuotes() {
            // Oracle escapes a literal double quote inside a delimited identifier by doubling it
            assertEquals("a\"b", IdentifierHelper.unquote("\"a\"\"b\""));
        }

        @Test
        void toleratesNullAndDegenerateInput() {
            assertNull(IdentifierHelper.unquote(null));
            assertEquals("", IdentifierHelper.unquote(""));
            assertEquals("\"", IdentifierHelper.unquote("\""));
        }
    }

    @Nested
    @DisplayName("canonical")
    class Canonical {

        @Test
        void collapsesEveryWrittenFormOfTheSameOracleName() {
            // Oracle stores unquoted identifiers upper-cased, so all four name the same column
            assertEquals("RUN_ID", IdentifierHelper.canonical("run_id"));
            assertEquals("RUN_ID", IdentifierHelper.canonical("Run_Id"));
            assertEquals("RUN_ID", IdentifierHelper.canonical("RUN_ID"));
            assertEquals("RUN_ID", IdentifierHelper.canonical("\"RUN_ID\""));
        }

        @Test
        void producesKeysUsableByLowerCaseKeyedIndices() {
            // The bug this guards: "RUN_ID".toLowerCase() is the string "run_id" WITH quotes,
            // which misses every entry in the metadata indices.
            assertEquals("run_id", IdentifierHelper.canonical("\"RUN_ID\"").toLowerCase());
        }
    }

    @Nested
    @DisplayName("emit")
    class Emit {

        @Test
        void passesUnquotedIdentifiersThroughUntouched() {
            // The non-regression guarantee: PostgreSQL folds these to the migrated lower-case name
            assertEquals("run_id", IdentifierHelper.emit("run_id"));
            assertEquals("RUN_ID", IdentifierHelper.emit("RUN_ID"));
            assertEquals("MiXeD", IdentifierHelper.emit("MiXeD"));
        }

        @Test
        void leavesBareKeywordsAlone() {
            // Quoting these would destroy their PostgreSQL meaning: "user" is a column named
            // user, USER is the current-user keyword.
            assertEquals("USER", IdentifierHelper.emit("USER"));
            assertEquals("LEVEL", IdentifierHelper.emit("LEVEL"));
            assertEquals("end", IdentifierHelper.emit("end"));
        }

        @Test
        void normalizesQuotedIdentifiersToTheMigratedName() {
            // The reported bug: "RUN_ID" reached PostgreSQL case-sensitive and missed run_id
            assertEquals("run_id", IdentifierHelper.emit("\"RUN_ID\""));
            assertEquals("status", IdentifierHelper.emit("\"STATUS\""));
            assertEquals("mycol", IdentifierHelper.emit("\"MyCol\""));
        }

        @Test
        void quotesQuotedIdentifiersThatPostgresRequiresQuoted() {
            assertEquals("\"timestamp\"", IdentifierHelper.emit("\"TIMESTAMP\""));
            assertEquals("\"order#\"", IdentifierHelper.emit("\"Order#\""));
        }

        @Test
        void normalizesUnquotedNamesPostgresCannotReadBare() {
            // Oracle permits # and $ in unquoted identifiers, PostgreSQL does not - emitting
            // these verbatim is a syntax error, so normalizing can only be an improvement
            assertEquals("\"order#\"", IdentifierHelper.emit("ORDER#"));
        }

        @Test
        void isIdempotent() {
            assertEquals("run_id", IdentifierHelper.emit(IdentifierHelper.emit("\"RUN_ID\"")));
            assertEquals("\"timestamp\"", IdentifierHelper.emit(IdentifierHelper.emit("\"TIMESTAMP\"")));
        }
    }

    @Nested
    @DisplayName("isQuoted")
    class IsQuoted {

        @Test
        void distinguishesDelimitedFromRegularIdentifiers() {
            assertTrue(IdentifierHelper.isQuoted("\"RUN_ID\""));
            assertFalse(IdentifierHelper.isQuoted("RUN_ID"));
            assertFalse(IdentifierHelper.isQuoted(null));
            assertFalse(IdentifierHelper.isQuoted("\""));
        }
    }
}
