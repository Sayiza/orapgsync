package me.christianrobert.orapgsync.core.job.model.preflight;

import me.christianrobert.orapgsync.transformer.analysis.ConstructSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * How often one Oracle construct occurs in the analysed codebase, split by whether the
 * objects containing it transform or not.
 *
 * <p>The split is what makes the report demand-driven: a construct that appears in 30 failing
 * views is worth implementing, one that appears only in views that already transform is not.
 * A construct with {@link ConstructSupport#NO_VISITOR} or {@link ConstructSupport#IGNORED}
 * that appears in <em>passing</em> objects is the dangerous case — the object transformed but
 * the construct was lost.</p>
 */
public class ConstructStat {

    private static final int MAX_EXAMPLES = 10;

    private final String constructId;
    private final String displayName;
    private final ConstructSupport support;
    private final String note;

    private int occurrences;
    private final List<String> failingObjects = new ArrayList<>();
    private final List<String> passingObjects = new ArrayList<>();

    public ConstructStat(String constructId, String displayName, ConstructSupport support, String note) {
        this.constructId = constructId;
        this.displayName = displayName;
        this.support = support;
        this.note = note;
    }

    /**
     * Records one occurrence of the construct.
     *
     * @param qualifiedObjectName object the construct was found in
     * @param objectFailed        whether that object failed to transform
     */
    public void record(String qualifiedObjectName, boolean objectFailed) {
        occurrences++;
        List<String> objects = objectFailed ? failingObjects : passingObjects;
        if (!objects.contains(qualifiedObjectName)) {
            objects.add(qualifiedObjectName);
        }
    }

    public String getConstructId() {
        return constructId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ConstructSupport getSupport() {
        return support;
    }

    public String getNote() {
        return note;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public int getFailingObjectCount() {
        return failingObjects.size();
    }

    public int getPassingObjectCount() {
        return passingObjects.size();
    }

    public int getAffectedObjectCount() {
        return failingObjects.size() + passingObjects.size();
    }

    /** Example failing objects, capped so the report stays small. */
    public List<String> getFailingExamples() {
        return failingObjects.stream().limit(MAX_EXAMPLES).toList();
    }

    /** Example objects that transformed although they contain this construct. */
    public List<String> getPassingExamples() {
        return passingObjects.stream().limit(MAX_EXAMPLES).toList();
    }

    /**
     * True when the construct is dropped or unhandled but the containing objects transformed
     * without an error — the generated code is silently missing something.
     */
    public boolean isSilentLoss() {
        return support != ConstructSupport.HANDLED && !passingObjects.isEmpty();
    }

    @Override
    public String toString() {
        return "ConstructStat{" + constructId + ", occurrences=" + occurrences +
               ", failing=" + failingObjects.size() + ", passing=" + passingObjects.size() + "}";
    }
}
