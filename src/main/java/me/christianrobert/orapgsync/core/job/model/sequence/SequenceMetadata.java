package me.christianrobert.orapgsync.core.job.model.sequence;

/**
 * Represents metadata for a database sequence.
 * This is a pure data model without dependencies on other services.
 */
public class SequenceMetadata {
    private String schema;
    private String sequenceName;
    private Long minValue;
    private Long maxValue;
    private Integer incrementBy;
    private Long currentValue; // last_number from Oracle, last_value from PostgreSQL
    private Integer cacheSize;
    private String cycleFlag; // "Y" or "N"
    private String orderFlag; // "Y" or "N" (Oracle only, PostgreSQL doesn't have ORDER)

    public SequenceMetadata(String schema, String sequenceName) {
        this.schema = schema;
        this.sequenceName = sequenceName;
    }

    // Getters and setters
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getSequenceName() {
        return sequenceName;
    }

    public void setSequenceName(String sequenceName) {
        this.sequenceName = sequenceName;
    }

    public Long getMinValue() {
        return minValue;
    }

    public void setMinValue(Long minValue) {
        this.minValue = minValue;
    }

    public Long getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(Long maxValue) {
        this.maxValue = maxValue;
    }

    public Integer getIncrementBy() {
        return incrementBy;
    }

    public void setIncrementBy(Integer incrementBy) {
        this.incrementBy = incrementBy;
    }

    public Long getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(Long currentValue) {
        this.currentValue = currentValue;
    }

    public Integer getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(Integer cacheSize) {
        this.cacheSize = cacheSize;
    }

    public String getCycleFlag() {
        return cycleFlag;
    }

    public void setCycleFlag(String cycleFlag) {
        this.cycleFlag = cycleFlag;
    }

    public String getOrderFlag() {
        return orderFlag;
    }

    public void setOrderFlag(String orderFlag) {
        this.orderFlag = orderFlag;
    }

    /**
     * Gets a qualified name for the sequence (schema.sequence_name).
     */
    public String getQualifiedName() {
        return schema + "." + sequenceName;
    }

    @Override
    public String toString() {
        return "SequenceMetadata{" +
                "schema='" + schema + '\'' +
                ", sequenceName='" + sequenceName + '\'' +
                ", currentValue=" + currentValue +
                ", incrementBy=" + incrementBy +
                ", minValue=" + minValue +
                ", maxValue=" + maxValue +
                ", cacheSize=" + cacheSize +
                ", cycleFlag='" + cycleFlag + '\'' +
                '}';
    }
}
