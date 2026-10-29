package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.service.StateService;

import java.util.List;

/**
 * Opens {@link OracleFunctionExtractor}'s package-private seams to tests in other packages, which
 * need step 22's real output to drive later jobs from.
 */
public final class OracleFunctionExtractorTestSupport {

    private OracleFunctionExtractorTestSupport() {
    }

    /**
     * Scans a package body as step 22 does; stores its sources and reduced body in {@code state}.
     * {@code publicMembers} gain the source ordinal or pairing error step 22 gives them.
     */
    public static List<FunctionMetadata> extractPrivateMembers(String packageName, String bodySource,
                                                               String schema, List<FunctionMetadata> publicMembers,
                                                               StateService state) {
        return OracleFunctionExtractor.parseAndExtractPackageFunctions(
                packageName, bodySource, schema, publicMembers, state);
    }

    /** Indexes a package's spec types, and its body types if its reduced body is in {@code state}. */
    public static boolean indexPackageTypes(String schema, String packageName, String specSource,
                                            StateService state) {
        return OracleFunctionExtractor.indexPackageSpecTypes(schema, packageName, specSource, state);
    }
}
