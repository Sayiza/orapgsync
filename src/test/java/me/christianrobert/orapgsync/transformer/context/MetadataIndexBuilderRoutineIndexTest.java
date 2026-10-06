package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.service.StateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routine indices that make a parenthesis-less reference decidable.
 *
 * <p>Two questions have to be answerable from metadata: is this name a standalone routine rather
 * than a relation, and could it have been invoked with no arguments at all? Only a routine that
 * answers yes to the second may be rewritten, which is what keeps an ordinary column reference
 * from being turned into a call.
 */
class MetadataIndexBuilderRoutineIndexTest {

    @Test
    void routineWithoutParameters_isNoArgCallable() {
        TransformationIndices indices = build(standalone("get_today"));

        assertTrue(indices.isStandaloneFunction("hr.get_today"));
        assertTrue(indices.isNoArgCallable("hr.get_today"));
    }

    @Test
    void routineWithMandatoryParameter_isNotNoArgCallable() {
        FunctionMetadata function = standalone("get_salary");
        function.addParameter(parameter("emp_id", "IN", false));

        TransformationIndices indices = build(function);

        assertTrue(indices.isStandaloneFunction("hr.get_salary"),
                "Still a known standalone routine");
        assertFalse(indices.isNoArgCallable("hr.get_salary"),
                "A mandatory argument means a bare reference was never a call");
    }

    @Test
    void routineWithOnlyDefaultedParameters_isNoArgCallable() {
        FunctionMetadata function = standalone("get_rate");
        function.addParameter(parameter("as_of", "IN", true));
        function.addParameter(parameter("currency", "IN", true));

        TransformationIndices indices = build(function);

        assertTrue(indices.isNoArgCallable("hr.get_rate"),
                "Oracle allows every-parameter-defaulted routines to be called bare");
    }

    @Test
    void defaultedOutParameter_isNotNoArgCallable() {
        // The caller must supply somewhere for the value to go, so this can never be a bare read.
        FunctionMetadata function = standalone("fetch_next");
        function.addParameter(parameter("result", "OUT", true));

        TransformationIndices indices = build(function);

        assertFalse(indices.isNoArgCallable("hr.fetch_next"));
    }

    @Test
    void packageMember_isIndexedUnderItsPackage_notAsStandalone() {
        FunctionMetadata function = standalone("get_status");
        function.setPackageName("emp_pkg");

        TransformationIndices indices = build(function);

        assertTrue(indices.isPackageFunction("hr.emp_pkg.get_status"));
        assertTrue(indices.isNoArgCallable("hr.emp_pkg.get_status"));
        assertFalse(indices.isStandaloneFunction("hr.get_status"),
                "A package member must not be reachable as an unqualified standalone routine");
    }

    @Test
    void routineOutsideTargetSchemas_isNotIndexed() {
        FunctionMetadata function = standalone("get_today");
        function.setSchema("other");

        TransformationIndices indices = build(function);

        assertFalse(indices.isStandaloneFunction("other.get_today"));
        assertFalse(indices.isNoArgCallable("other.get_today"));
    }

    // ========== Helpers ==========

    private TransformationIndices build(FunctionMetadata... functions) {
        StateService stateService = new StateService();
        stateService.setOracleFunctionMetadata(List.of(functions));
        return MetadataIndexBuilder.build(stateService, List.of("hr"));
    }

    private FunctionMetadata standalone(String name) {
        return new FunctionMetadata("hr", name, "FUNCTION");
    }

    private FunctionParameter parameter(String name, String inOut, boolean defaulted) {
        FunctionParameter parameter = new FunctionParameter(name, 1, "NUMBER", inOut);
        parameter.setDefaulted(defaulted);
        return parameter;
    }
}
