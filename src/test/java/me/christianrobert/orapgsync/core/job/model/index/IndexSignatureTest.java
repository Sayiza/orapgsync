package me.christianrobert.orapgsync.core.job.model.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signature component decides every "does this index already exist?" question in the
 * migration, so its two rules are pinned separately: exact match for reproducing Oracle indexes,
 * prefix match for the FK indexes the migration invents.
 */
class IndexSignatureTest {

    private static IndexSignature index(String table, boolean unique, IndexKeyPart... parts) {
        return IndexSignature.of("hr", table, unique, List.of(parts));
    }

    private static IndexKeyPart col(String name) {
        return IndexKeyPart.ofColumn(name, false);
    }

    private static IndexKeyPart colDesc(String name) {
        return IndexKeyPart.ofColumn(name, true);
    }

    @Nested
    @DisplayName("key normalization")
    class KeyNormalization {

        @Test
        void lowercasesAndUnquotes() {
            assertEquals("dept_id", IndexSignature.normalizeKey("\"DEPT_ID\""));
        }

        @Test
        void collapsesWhitespaceAroundPunctuation() {
            assertEquals("upper(name)", IndexSignature.normalizeKey("UPPER( \"NAME\" )"));
        }

        @Test
        void stripsFullyEnclosingParentheses() {
            assertEquals("upper(name)", IndexSignature.normalizeKey("((UPPER(name)))"));
        }

        @Test
        void keepsParenthesesThatDoNotEncloseTheWholeExpression() {
            // "(a) || (b)" opens and closes twice - stripping the outer pair would corrupt it
            assertEquals("(a)||(b)", IndexSignature.normalizeKey("(a) || (b)"));
        }

        @Test
        void handlesNull() {
            assertEquals("", IndexSignature.normalizeKey(null));
        }
    }

    @Nested
    @DisplayName("makesRedundant - reproducing an Oracle index")
    class MakesRedundant {

        @Test
        void identicalIndexesMatch() {
            IndexSignature existing = index("emp", false, col("dept_id"));
            assertTrue(existing.makesRedundant(index("emp", false, col("dept_id"))));
        }

        @Test
        void matchesRegardlessOfOriginalCaseAndQuoting() {
            IndexSignature existing = IndexSignature.of("HR", "EMP", false,
                    List.of(IndexKeyPart.ofColumn("DEPT_ID", false)));
            assertTrue(existing.makesRedundant(index("emp", false, col("dept_id"))));
        }

        @Test
        void differentTableDoesNotMatch() {
            IndexSignature existing = index("emp", false, col("dept_id"));
            assertFalse(existing.makesRedundant(index("dept", false, col("dept_id"))));
        }

        @Test
        void keyOrderMatters() {
            IndexSignature existing = index("emp", false, col("a"), col("b"));
            assertFalse(existing.makesRedundant(index("emp", false, col("b"), col("a"))));
        }

        @Test
        @DisplayName("a wider index does not stand in for a narrower one")
        void widerIndexIsNotEquivalent() {
            // Prefix coverage is deliberately NOT used here: we reproduce Oracle faithfully, and a
            // wider index leaves the migrated schema quietly different from the source.
            IndexSignature existing = index("emp", false, col("a"), col("b"));
            assertFalse(existing.makesRedundant(index("emp", false, col("a"))));
        }

        @Test
        @DisplayName("a unique index satisfies a non-unique requirement")
        void uniqueSatisfiesNonUnique() {
            IndexSignature existing = index("emp", true, col("email"));
            assertTrue(existing.makesRedundant(index("emp", false, col("email"))));
        }

        @Test
        @DisplayName("a non-unique index never satisfies a unique requirement")
        void nonUniqueDoesNotSatisfyUnique() {
            // Uniqueness is a constraint, not merely an access path.
            IndexSignature existing = index("emp", false, col("email"));
            assertFalse(existing.makesRedundant(index("emp", true, col("email"))));
        }

        @Test
        @DisplayName("a single DESC key is interchangeable with ASC - a B-tree scans both ways")
        void singleColumnDirectionIsInterchangeable() {
            IndexSignature existing = index("emp", false, colDesc("hire_date"));
            assertTrue(existing.makesRedundant(index("emp", false, col("hire_date"))));
        }

        @Test
        @DisplayName("fully inverted directions are interchangeable")
        void fullyInvertedDirectionsMatch() {
            IndexSignature existing = index("emp", false, colDesc("a"), col("b"));
            assertTrue(existing.makesRedundant(index("emp", false, col("a"), colDesc("b"))));
        }

        @Test
        @DisplayName("partially differing directions are not interchangeable")
        void mixedDirectionsDoNotMatch() {
            // (a ASC, b ASC) cannot serve an ORDER BY that (a ASC, b DESC) serves.
            IndexSignature existing = index("emp", false, col("a"), col("b"), col("c"));
            assertFalse(existing.makesRedundant(index("emp", false, col("a"), colDesc("b"), col("c"))));
        }

        @Test
        void nullIsNotRedundant() {
            assertFalse(index("emp", false, col("a")).makesRedundant(null));
        }
    }

    @Nested
    @DisplayName("coversLookup - FK gap-fill")
    class CoversLookup {

        @Test
        void exactColumnsAreCovered() {
            assertTrue(index("emp", false, col("dept_id")).coversLookup(List.of("dept_id")));
        }

        @Test
        @DisplayName("a leading prefix is covered - this is what makes gap-fill skip hand-made indexes")
        void leadingPrefixIsCovered() {
            IndexSignature existing = index("emp", false, col("dept_id"), col("hire_date"));
            assertTrue(existing.coversLookup(List.of("dept_id")));
        }

        @Test
        void nonLeadingColumnsAreNotCovered() {
            IndexSignature existing = index("emp", false, col("hire_date"), col("dept_id"));
            assertFalse(existing.coversLookup(List.of("dept_id")));
        }

        @Test
        void moreColumnsThanKeysAreNotCovered() {
            IndexSignature existing = index("emp", false, col("dept_id"));
            assertFalse(existing.coversLookup(List.of("dept_id", "hire_date")));
        }

        @Test
        @DisplayName("direction is irrelevant for an equality lookup")
        void directionIgnored() {
            assertTrue(index("emp", false, colDesc("dept_id")).coversLookup(List.of("dept_id")));
        }

        @Test
        void caseInsensitive() {
            assertTrue(index("emp", false, col("dept_id")).coversLookup(List.of("DEPT_ID")));
        }

        @Test
        void emptyOrNullIsNotCovered() {
            IndexSignature existing = index("emp", false, col("dept_id"));
            assertFalse(existing.coversLookup(List.of()));
            assertFalse(existing.coversLookup(null));
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        void equalSignaturesShareHashCode() {
            IndexSignature a = index("emp", true, col("x"), colDesc("y"));
            IndexSignature b = index("emp", true, col("x"), colDesc("y"));

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void uniquenessIsPartOfIdentity() {
            assertNotEquals(index("emp", true, col("x")), index("emp", false, col("x")));
        }
    }
}
