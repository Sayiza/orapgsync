package me.christianrobert.orapgsync.trigger.transformer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ColonReferenceTransformer.
 * Tests transformation of Oracle trigger correlation names to PostgreSQL equivalents.
 */
class ColonReferenceTransformerTest {

    // ==================== STANDARD NEW/OLD TESTS ====================

    @Test
    void testStandardNewReference() {
        String input = "INSERT INTO audit VALUES (:NEW.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input));
    }

    @Test
    void testStandardOldReference() {
        String input = "INSERT INTO audit VALUES (:OLD.id);";
        String expected = "INSERT INTO audit VALUES (OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input));
    }

    @Test
    void testStandardBothReferences() {
        String input = "IF :NEW.salary > :OLD.salary THEN";
        String expected = "IF NEW.salary > OLD.salary THEN";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input));
    }

    @Test
    void testLowercaseReferences() {
        String input = "INSERT INTO audit VALUES (:new.id, :old.id);";
        String expected = "INSERT INTO audit VALUES (new.id, old.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input));
    }

    @Test
    void testMixedCaseReferences() {
        String input = "INSERT INTO audit VALUES (:New.id, :Old.id);";
        String expected = "INSERT INTO audit VALUES (New.id, Old.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input));
    }

    @Test
    void testNullInput() {
        assertNull(ColonReferenceTransformer.removeColonReferences(null));
    }

    @Test
    void testEmptyInput() {
        assertEquals("", ColonReferenceTransformer.removeColonReferences(""));
    }

    @Test
    void testNoReferences() {
        String input = "SELECT * FROM employees WHERE id = 1;";
        assertEquals(input, ColonReferenceTransformer.removeColonReferences(input));
    }

    // ==================== CUSTOM ALIAS TESTS ====================

    @Test
    void testCustomNewAlias() {
        String input = "INSERT INTO audit VALUES (:pNew.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "OLD"));
    }

    @Test
    void testCustomOldAlias() {
        String input = "INSERT INTO audit VALUES (:pOld.id);";
        String expected = "INSERT INTO audit VALUES (OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "NEW", "POLD"));
    }

    @Test
    void testCustomBothAliases() {
        String input = "IF :pNew.salary > :pOld.salary THEN";
        String expected = "IF NEW.salary > OLD.salary THEN";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "POLD"));
    }

    @Test
    void testCustomAliases_MultipleOccurrences() {
        String input = """
            SELECT :pNew.id, :pNew.name, :pOld.status
            WHERE :pOld.id = :pNew.id
            """;
        String expected = """
            SELECT NEW.id, NEW.name, OLD.status
            WHERE OLD.id = NEW.id
            """;
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "POLD"));
    }

    @Test
    void testCustomAliases_CaseInsensitive() {
        // Input uses lowercase aliases, but alias definition is uppercase
        String input = "INSERT INTO audit VALUES (:pnew.id, :pold.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id, OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "POLD"));
    }

    @Test
    void testCustomAliases_SingleLetterAliases() {
        String input = "INSERT INTO audit VALUES (:n.id, :o.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id, OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "N", "O"));
    }

    @Test
    void testCustomAliases_LongAliases() {
        String input = "INSERT INTO audit VALUES (:newRecord.id, :oldRecord.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id, OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "NEWRECORD", "OLDRECORD"));
    }

    @Test
    void testCustomAliases_MixedWithStandard() {
        // Custom NEW alias, but standard OLD
        String input = "INSERT INTO audit VALUES (:pNew.id, :OLD.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id, OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "OLD"));
    }

    @Test
    void testCustomAliases_StandardWhenNull() {
        // Should fall back to standard when alias is null
        String input = "INSERT INTO audit VALUES (:NEW.id, :OLD.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id, OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, null, null));
    }

    @Test
    void testCustomAliases_StandardWhenEmpty() {
        // Should fall back to standard when alias is empty
        String input = "INSERT INTO audit VALUES (:NEW.id, :OLD.id);";
        String expected = "INSERT INTO audit VALUES (NEW.id, OLD.id);";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "", ""));
    }

    // ==================== REAL TRIGGER EXAMPLE ====================

    @Test
    void testRealTriggerExample() {
        // From the user's original trigger
        String input = """
            SELECT i.PERSON_UID INTO vPersonUid
            FROM CO_SHAME_IDENT_API.STUDENT_ID_PERSON_UID_V i
            INNER JOIN STUD.ST_STUDIEN s ON i.STUDENT_ID = s.ST_PERSON_NR
            WHERE s.NR = :pOld.STUDY_ID;

            IF coalesce( :pOld.CREDITS, -1 ) <> coalesce( :pNew.CREDITS, -1 )
               OR coalesce( :pOld.ACHIEVEMENT_DATE, TO_TIMESTAMP( '01.01.1900', 'DD.MM.YYYY' ) )
                  <> coalesce( :pNew.ACHIEVEMENT_DATE, TO_TIMESTAMP( '01.01.1900', 'DD.MM.YYYY' ) )
            THEN
               UPDATE co_xm_core.ac_study_revision
               SET revision = vStudyRevNr
               WHERE study_uid = :pOld.STUDY_ID;
            END IF;
            """;

        String expected = """
            SELECT i.PERSON_UID INTO vPersonUid
            FROM CO_SHAME_IDENT_API.STUDENT_ID_PERSON_UID_V i
            INNER JOIN STUD.ST_STUDIEN s ON i.STUDENT_ID = s.ST_PERSON_NR
            WHERE s.NR = OLD.STUDY_ID;

            IF coalesce( OLD.CREDITS, -1 ) <> coalesce( NEW.CREDITS, -1 )
               OR coalesce( OLD.ACHIEVEMENT_DATE, TO_TIMESTAMP( '01.01.1900', 'DD.MM.YYYY' ) )
                  <> coalesce( NEW.ACHIEVEMENT_DATE, TO_TIMESTAMP( '01.01.1900', 'DD.MM.YYYY' ) )
            THEN
               UPDATE co_xm_core.ac_study_revision
               SET revision = vStudyRevNr
               WHERE study_uid = OLD.STUDY_ID;
            END IF;
            """;

        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "POLD"));
    }

    // ==================== WORD BOUNDARY TESTS ====================

    @Test
    void testDoesNotReplaceSubstrings() {
        // :pNew should not match :pNewRecord
        String input = "INSERT INTO audit VALUES (:pNewRecord.id);";
        // Should NOT replace because pNewRecord != pNew
        String result = ColonReferenceTransformer.removeColonReferences(input, "PNEW", "POLD");
        // :pNewRecord should remain unchanged (except the colon)
        assertTrue(result.contains(":pNewRecord") || result.contains("pNewRecord"));
    }

    @Test
    void testWordBoundary_ColumnNameSimilarToAlias() {
        // Column named "pnew_date" should not be affected
        String input = "SELECT pnew_date, :pNew.id FROM table";
        String expected = "SELECT pnew_date, NEW.id FROM table";
        assertEquals(expected, ColonReferenceTransformer.removeColonReferences(input, "PNEW", "POLD"));
    }
}
