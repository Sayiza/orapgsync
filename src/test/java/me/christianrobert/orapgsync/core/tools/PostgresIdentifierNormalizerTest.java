package me.christianrobert.orapgsync.core.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PostgresIdentifierNormalizer}.
 *
 * <p>Focuses on the two behaviours added alongside the delimited-identifier fix: escaping of
 * embedded double quotes, and detection of Oracle names that collapse onto one PostgreSQL name.
 */
class PostgresIdentifierNormalizerTest {

    @Nested
    @DisplayName("normalizeIdentifier")
    class NormalizeIdentifier {

        @Test
        void lowerCasesPlainIdentifiers() {
            assertEquals("run_id", PostgresIdentifierNormalizer.normalizeIdentifier("RUN_ID"));
            assertEquals("customer_id", PostgresIdentifierNormalizer.normalizeIdentifier("Customer_Id"));
        }

        @Test
        void quotesReservedWordsAndSpecialCharacters() {
            assertEquals("\"end\"", PostgresIdentifierNormalizer.normalizeIdentifier("END"));
            assertEquals("\"timestamp\"", PostgresIdentifierNormalizer.normalizeIdentifier("TIMESTAMP"));
            assertEquals("\"order#\"", PostgresIdentifierNormalizer.normalizeIdentifier("ORDER#"));
        }

        @Test
        void escapesEmbeddedDoubleQuotes() {
            // Without doubling, the quoted identifier terminates early and the SQL is invalid
            assertEquals("\"a\"\"b\"", PostgresIdentifierNormalizer.normalizeIdentifier("a\"b"));
        }
    }

    @Nested
    @DisplayName("findCollisions")
    class FindCollisions {

        @Test
        void reportsNothingForDistinctNames() {
            assertTrue(PostgresIdentifierNormalizer
                    .findCollisions(List.of("RUN_ID", "STATUS", "MSG")).isEmpty());
        }

        @Test
        void reportsNothingForEmptyOrNullInput() {
            assertTrue(PostgresIdentifierNormalizer.findCollisions(List.of()).isEmpty());
            assertTrue(PostgresIdentifierNormalizer.findCollisions(null).isEmpty());
        }

        @Test
        void detectsNamesDifferingOnlyByCase() {
            Map<String, List<String>> collisions =
                    PostgresIdentifierNormalizer.findCollisions(List.of("Foo", "FOO", "bar"));

            assertEquals(1, collisions.size(), "Only Foo/FOO collide");
            assertEquals(List.of("Foo", "FOO"), collisions.get("foo"));
        }

        @Test
        void detectsCollisionAcrossReservedWordQuoting() {
            // Both normalize to "end" - the quoting does not save them from each other
            Map<String, List<String>> collisions =
                    PostgresIdentifierNormalizer.findCollisions(List.of("End", "END"));

            assertEquals(List.of("End", "END"), collisions.get("\"end\""));
        }

        @Test
        void treatsARepeatedNameAsADuplicateNotACollision() {
            // The same Oracle name listed twice is not two columns differing by case
            assertTrue(PostgresIdentifierNormalizer
                    .findCollisions(List.of("RUN_ID", "RUN_ID")).isEmpty());
        }

        @Test
        void describeCollisionsNamesBothOracleColumns() {
            Map<String, List<String>> collisions =
                    PostgresIdentifierNormalizer.findCollisions(List.of("Foo", "FOO"));

            assertEquals("foo (from Foo, FOO)",
                    PostgresIdentifierNormalizer.describeCollisions(collisions));
        }
    }
}
