package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.service.StateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signatures of package-private members, which come from the parse tree rather than
 * {@code ALL_ARGUMENTS} (ROADMAP P1.4).
 *
 * <p>{@code %TYPE} and {@code %ROWTYPE} are sibling tokens of {@code type_name} in
 * {@code type_spec}, and the extractor read only {@code type_name}. So {@code st.nr%TYPE} reached
 * the stub job as {@code ST.NR}, which resolves as a package type that does not exist, and the stub
 * was refused — about a quarter of all stub errors on the 2026-10-24 run.
 */
class PrivateMemberSignatureExtractionTest {

    private static List<FunctionMetadata> extract(String body) {
        return OracleFunctionExtractor.parseAndExtractPackageFunctions(
                "lending", body, "main_app", List.of(), new StateService());
    }

    private static FunctionMetadata member(List<FunctionMetadata> functions, String name) {
        return functions.stream().filter(f -> f.getObjectName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no member " + name + " in " + functions));
    }

    private static final String BODY = """
            PACKAGE BODY lending IS
              FUNCTION due_term(p_term IN term_def.nr%TYPE, p_item main_app.items%ROWTYPE,
                                p_note VARCHAR2(200), p_code labels.code) RETURN term_def.nr%TYPE IS
              BEGIN
                RETURN p_term;
              END due_term;

              PROCEDURE renew(p_term term_def.nr%type) IS
              BEGIN
                NULL;
              END renew;
            END lending;
            """;

    @Test
    @DisplayName("A %TYPE parameter keeps its anchor")
    void typeAnchorIsKept() {
        FunctionParameter p = member(extract(BODY), "due_term").getParameters().get(0);

        assertEquals("TERM_DEF.NR%TYPE", p.getDataType());
        assertFalse(p.isCustomDataType(), "an anchor must not be split into owner and type name");
    }

    @Test
    @DisplayName("A %ROWTYPE parameter keeps its anchor, so the stub refuses it by name")
    void rowtypeAnchorIsKept() {
        FunctionParameter p = member(extract(BODY), "due_term").getParameters().get(1);

        assertEquals("MAIN_APP.ITEMS%ROWTYPE", p.getDataType());
        assertFalse(p.isCustomDataType());
    }

    @Test
    @DisplayName("A %TYPE return type keeps its anchor")
    void returnTypeAnchorIsKept() {
        assertEquals("TERM_DEF.NR%TYPE", member(extract(BODY), "due_term").getReturnDataType());
    }

    @Test
    @DisplayName("Lower-case %type is the same anchor")
    void lowerCaseAnchor() {
        assertEquals("TERM_DEF.NR%TYPE",
                member(extract(BODY), "renew").getParameters().get(0).getDataType());
    }

    @Test
    @DisplayName("Built-ins and package types are unchanged")
    void unanchoredTypesUnchanged() {
        List<FunctionParameter> params = member(extract(BODY), "due_term").getParameters();

        assertEquals("VARCHAR2", params.get(2).getDataType());
        assertEquals("LABELS.CODE", params.get(3).getDataType());
        assertTrue(params.get(3).isCustomDataType());
    }
}
