package me.christianrobert.orapgsync.core.job.model.sequence;

import java.math.BigInteger;

/**
 * Represents metadata for a database sequence.
 * This is a pure data model without dependencies on other services.
 * Uses BigInteger for min/max/current values to support Oracle's large sequence values (up to 10^28).
 */
public class SequenceMetadata {
    private String schema;
    private String sequenceName;
    private BigInteger minValue;
    private BigInteger maxValue;
    private Integer incrementBy;
    private BigInteger currentValue; // last_number from Oracle, last_value from PostgreSQL
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

    public BigInteger getMinValue() {
        return minValue;
    }

    public void setMinValue(BigInteger minValue) {
        this.minValue = minValue;
    }

    public BigInteger getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(BigInteger maxValue) {
        this.maxValue = maxValue;
    }

    public Integer getIncrementBy() {
        return incrementBy;
    }

    public void setIncrementBy(Integer incrementBy) {
        this.incrementBy = incrementBy;
    }

    public BigInteger getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(BigInteger currentValue) {
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
