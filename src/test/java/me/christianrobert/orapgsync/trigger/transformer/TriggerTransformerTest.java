package me.christianrobert.orapgsync.trigger.transformer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TriggerTransformer.
 * Tests the trigger body wrapping logic for ANTLR parsing.
 */
class TriggerTransformerTest {

    /**
     * Uses reflection to test the private wrapTriggerBodyForParsing method.
     */
    private String wrapTriggerBody(String triggerBody) throws Exception {
        TriggerTransformer transformer = new TriggerTransformer();
        Method method = TriggerTransformer.class.getDeclaredMethod("wrapTriggerBodyForParsing", String.class);
        method.setAccessible(true);
        return (String) method.invoke(transformer, triggerBody);
    }

    @Test
    void testWrapSimpleBeginEnd() throws Exception {
        String input = "BEGIN\n  NULL;\nEND;";
        String result = wrapTriggerBody(input);

        assertTrue(result.startsWith("PROCEDURE trigger_temp_wrapper IS"));
        assertTrue(result.contains("BEGIN"));
        assertTrue(result.contains("NULL"));
        assertTrue(result.contains("END"));
        assertFalse(result.contains("DECLARE"));
    }

    @Test
    void testWrapWithDeclareBlock() throws Exception {
        String input = """
            DECLARE
              v_count NUMBER;
              v_name VARCHAR2(100);
            BEGIN
              SELECT COUNT(*) INTO v_count FROM employees;
            END;
            """;

        String result = wrapTriggerBody(input);

        // Should start with PROCEDURE ... IS
        assertTrue(result.startsWith("PROCEDURE trigger_temp_wrapper IS"));

        // Should NOT contain DECLARE keyword (it's redundant after IS)
        assertFalse(result.toUpperCase().contains("\nDECLARE\n"));
        assertFalse(result.toUpperCase().startsWith("PROCEDURE trigger_temp_wrapper IS\nDECLARE"));

        // Should contain the variable declarations
        assertTrue(result.contains("v_count NUMBER"));
        assertTrue(result.contains("v_name VARCHAR2"));

        // Should contain BEGIN and END
        assertTrue(result.contains("BEGIN"));
        assertTrue(result.contains("END"));
    }

    @Test
    void testWrapWithDeclareBlockLowercase() throws Exception {
        String input = """
            declare
              vStudyRevNr number  := 1;
              vPersRevNr  number  := 1;
            begin
              null;
            end;
            """;

        String result = wrapTriggerBody(input);

        // Should start with PROCEDURE ... IS
        assertTrue(result.startsWith("PROCEDURE trigger_temp_wrapper IS"));

        // Should NOT contain standalone declare keyword
        assertFalse(result.toLowerCase().contains("is\ndeclare"));

        // Should contain the variable declarations
        assertTrue(result.contains("vStudyRevNr number"));
        assertTrue(result.contains("vPersRevNr  number"));
    }

    @Test
    void testWrapComplexTriggerBody() throws Exception {
        // Simulating the user's trigger body structure
        String input = """
            declare
              vStudyRevNr number  := 1;
              vPersRevNr  number  := 1;
              updateStudy boolean := false;
              updatePers  boolean := false;
              vPersonUid  varchar2(64);
            begin
              select i.PERSON_UID
              into vPersonUid
              from STUDENT_ID_PERSON_UID_V i
              where i.STUDENT_ID = 1;

              for r in (
                select r.revision
                from ac_study_revision r
                where r.study_uid = 123
              )
              loop
                vStudyRevNr := r.revision;
                vStudyRevNr := vStudyRevNr + 1;
                updateStudy := true;
              end loop;
            end;
            """;

        String result = wrapTriggerBody(input);

        // Validate structure
        assertTrue(result.startsWith("PROCEDURE trigger_temp_wrapper IS"));

        // Should NOT have DECLARE after IS
        String afterIs = result.substring(result.indexOf("IS") + 2).trim();
        assertFalse(afterIs.toUpperCase().startsWith("DECLARE"));

        // Variable declarations should come right after IS
        assertTrue(afterIs.startsWith("vStudyRevNr") || afterIs.startsWith("\n") && afterIs.contains("vStudyRevNr"));
    }

    @Test
    void testWrapPreservesWhitespaceInBody() throws Exception {
        String input = "BEGIN\n  -- Comment\n  INSERT INTO t VALUES (1);\nEND;";
        String result = wrapTriggerBody(input);

        assertTrue(result.contains("-- Comment"));
        assertTrue(result.contains("INSERT INTO t VALUES (1)"));
    }
}
