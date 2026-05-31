package me.christianrobert.orapgsync.trigger.transformer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TriggerReturnInjector.
 * Tests RETURN statement injection for PostgreSQL trigger functions.
 */
class TriggerReturnInjectorTest {

    @Test
    void testSimpleBody_AfterTrigger() {
        String input = """
            BEGIN
              INSERT INTO audit_log VALUES (NEW.id);
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "AFTER", "ROW");

        assertTrue(result.contains("RETURN NULL;"));
        assertTrue(result.contains("INSERT INTO audit_log"));
        // RETURN should be before END
        int returnIndex = result.indexOf("RETURN NULL;");
        int endIndex = result.indexOf("END;");
        assertTrue(returnIndex < endIndex);
    }

    @Test
    void testSimpleBody_BeforeTrigger() {
        String input = """
            BEGIN
              NEW.modified_date := CURRENT_TIMESTAMP;
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "BEFORE", "ROW");

        assertTrue(result.contains("RETURN NEW;"));
    }

    @Test
    void testBodyWithException_ReturnsOnBothPaths() {
        String input = """
            BEGIN
              INSERT INTO audit_log VALUES (NEW.id);
            EXCEPTION
              WHEN OTHERS THEN
                PERFORM log_error();
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "AFTER", "ROW");

        // Count RETURN statements - should be 2 (one for each path)
        int returnCount = countOccurrences(result, "RETURN NULL;");
        assertEquals(2, returnCount, "Should have RETURN on both normal and exception paths");

        // First RETURN should be before EXCEPTION
        int firstReturn = result.indexOf("RETURN NULL;");
        int exceptionIndex = result.indexOf("EXCEPTION");
        assertTrue(firstReturn < exceptionIndex, "First RETURN should be before EXCEPTION");

        // Second RETURN should be before END
        int secondReturn = result.indexOf("RETURN NULL;", firstReturn + 1);
        int endIndex = result.lastIndexOf("END;");
        assertTrue(secondReturn < endIndex, "Second RETURN should be before END");
        assertTrue(secondReturn > exceptionIndex, "Second RETURN should be after EXCEPTION");
    }

    @Test
    void testBodyWithException_BeforeTrigger() {
        String input = """
            BEGIN
              NEW.updated_at := CURRENT_TIMESTAMP;
            EXCEPTION
              WHEN OTHERS THEN
                RAISE;
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "BEFORE", "ROW");

        // Should have RETURN NEW on both paths
        int returnCount = countOccurrences(result, "RETURN NEW;");
        assertEquals(2, returnCount, "Should have RETURN NEW on both paths");
    }

    @Test
    void testStatementLevelTrigger() {
        String input = """
            BEGIN
              UPDATE stats SET last_modified = CURRENT_TIMESTAMP;
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "AFTER", "STATEMENT");

        assertTrue(result.contains("RETURN NULL;"));
    }

    @Test
    void testAlreadyHasReturn() {
        String input = """
            BEGIN
              IF some_condition THEN
                RETURN NULL;
              END IF;
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "AFTER", "ROW");

        // Should not add another RETURN since one already exists
        int returnCount = countOccurrences(result, "RETURN");
        assertEquals(1, returnCount, "Should not add RETURN if already present");
    }

    @Test
    void testComplexExceptionBlock() {
        // Real-world example similar to user's trigger
        String input = """
            BEGIN
              SELECT person_uid INTO v_person_uid FROM users WHERE id = NEW.user_id;
              INSERT INTO revisions VALUES (NEW.study_id, 1);
            EXCEPTION
              WHEN OTHERS THEN
                PERFORM log_error(SQLERRM);
                PERFORM log_error(SQLSTATE);
            END;""";

        String result = TriggerReturnInjector.injectReturn(input, "AFTER", "ROW");

        // Verify structure
        assertTrue(result.contains("SELECT person_uid"));
        assertTrue(result.contains("INSERT INTO revisions"));
        assertTrue(result.contains("EXCEPTION"));
        assertTrue(result.contains("log_error"));

        // Two RETURNs
        int returnCount = countOccurrences(result, "RETURN NULL;");
        assertEquals(2, returnCount);
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
