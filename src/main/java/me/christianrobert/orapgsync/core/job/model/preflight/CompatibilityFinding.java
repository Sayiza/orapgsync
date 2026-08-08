package me.christianrobert.orapgsync.core.job.model.preflight;

import me.christianrobert.orapgsync.transformer.analysis.DetectedConstruct;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of analysing a single Oracle object (view, function, procedure) without touching
 * PostgreSQL: whether it transforms, and which catalogued Oracle constructs it contains.
 *
 * <p>The Oracle source is intentionally not stored here — it is already in state
 * (ViewMetadata / FunctionMetadata) and duplicating it for thousands of objects would blow up
 * the report. The findings carry the source line and a snippet per construct instead.</p>
 */
public class CompatibilityFinding {

    private final String objectType;
    private final String schema;
    private final String objectName;
    private final CompatibilityStatus status;
    private final String errorMessage;
    private final List<DetectedConstruct> constructs;

    public CompatibilityFinding(String objectType, String schema, String objectName,
                                CompatibilityStatus status, String errorMessage,
                                List<DetectedConstruct> constructs) {
        this.objectType = objectType;
        this.schema = schema;
        this.objectName = objectName;
        this.status = status;
        this.errorMessage = errorMessage;
        this.constructs = constructs != null ? new ArrayList<>(constructs) : new ArrayList<>();
    }

    public String getObjectType() {
        return objectType;
    }

    public String getSchema() {
        return schema;
    }

    public String getObjectName() {
        return objectName;
    }

    public String getQualifiedName() {
        return schema + "." + objectName;
    }

    public CompatibilityStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<DetectedConstruct> getConstructs() {
        return new ArrayList<>(constructs);
    }

    /**
     * True when the object will not migrate correctly. A truncated parse counts as a failure even
     * though the transformation reported success — the result is an incomplete object.
     */
    public boolean isFailure() {
        return status == CompatibilityStatus.PARSE_ERROR
                || status == CompatibilityStatus.TRANSFORM_ERROR
                || status == CompatibilityStatus.TRUNCATED_PARSE;
    }

    @Override
    public String toString() {
        return "CompatibilityFinding{" + objectType + " " + getQualifiedName() +
               ", status=" + status + ", constructs=" + constructs.size() + "}";
    }
}
