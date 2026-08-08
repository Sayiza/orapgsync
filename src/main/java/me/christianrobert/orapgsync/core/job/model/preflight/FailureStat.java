package me.christianrobert.orapgsync.core.job.model.preflight;

import java.util.ArrayList;
import java.util.List;

/**
 * Failing objects grouped by status and a normalized failure signature.
 *
 * <p>Complements {@link ConstructStat}: constructs explain failures that happen inside a parsed
 * tree, this explains the rest — objects that do not parse at all, that the parser truncated, or
 * that the transformer rejected for a reason unrelated to a catalogued construct. Identical
 * signatures usually mean the same missing support.</p>
 */
public class FailureStat {

    private static final int MAX_EXAMPLES = 10;

    private final CompatibilityStatus status;
    private final String signature;
    private final List<String> objects = new ArrayList<>();
    private String exampleMessage;

    public FailureStat(CompatibilityStatus status, String signature) {
        this.status = status;
        this.signature = signature;
    }

    public void record(String qualifiedObjectName, String fullMessage) {
        if (!objects.contains(qualifiedObjectName)) {
            objects.add(qualifiedObjectName);
        }
        if (exampleMessage == null) {
            exampleMessage = fullMessage;
        }
    }

    public CompatibilityStatus getStatus() {
        return status;
    }

    public String getSignature() {
        return signature;
    }

    public int getObjectCount() {
        return objects.size();
    }

    public List<String> getExamples() {
        return objects.stream().limit(MAX_EXAMPLES).toList();
    }

    public String getExampleMessage() {
        return exampleMessage;
    }

    @Override
    public String toString() {
        return "FailureStat{" + status + ": " + signature + ", objects=" + objects.size() + "}";
    }
}
