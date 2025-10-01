package me.christianrobert.orapgsync.table.model;

/**
 * Represents a column's metadata including data type, constraints, and default values.
 * This is a pure data model without dependencies on other services.
 */
public class ColumnMetadata {
  private String columnName;
  private String dataType; // Database data type (e.g., Oracle or PostgreSQL)
  private String dataTypeOwner; // Schema/owner of custom data type (for user-defined types)
  private Integer characterLength; // For VARCHAR2, CHAR, VARCHAR, etc.
  private Integer numericPrecision; // For NUMBER, NUMERIC, etc.
  private Integer numericScale; // For NUMBER, NUMERIC, etc.
  private boolean nullable;
  private String defaultValue;

  public ColumnMetadata(String columnName, String dataType, Integer characterLength,
                        Integer numericPrecision, Integer numericScale, boolean nullable, String defaultValue) {
    this(columnName, dataType, null, characterLength, numericPrecision, numericScale, nullable, defaultValue);
  }

  public ColumnMetadata(String columnName, String dataType, String dataTypeOwner, Integer characterLength,
                        Integer numericPrecision, Integer numericScale, boolean nullable, String defaultValue) {
    this.columnName = columnName;
    this.dataType = dataType;
    this.dataTypeOwner = dataTypeOwner;
    this.characterLength = characterLength;
    this.numericPrecision = numericPrecision;
    this.numericScale = numericScale;
    this.nullable = nullable;
    this.defaultValue = defaultValue;
  }

  // Getters
  public String getColumnName() { return columnName; }
  public String getDataType() { return dataType; }
  public String getDataTypeOwner() { return dataTypeOwner; }
  public Integer getCharacterLength() { return characterLength; }
  public Integer getNumericPrecision() { return numericPrecision; }
  public Integer getNumericScale() { return numericScale; }
  public boolean isNullable() { return nullable; }
  public String getDefaultValue() { return defaultValue; }

  /**
   * Returns true if this column uses a custom (user-defined) data type.
   */
  public boolean isCustomDataType() {
    return dataTypeOwner != null && !dataTypeOwner.isEmpty();
  }

  /**
   * Returns the fully qualified data type name (schema.type) if it's a custom type,
   * otherwise just returns the data type name.
   */
  public String getQualifiedDataType() {
    if (isCustomDataType()) {
      return dataTypeOwner + "." + dataType;
    }
    return dataType;
  }

  @Override
  public String toString() {
    return "ColumnMetadata{name='" + columnName + "', type='" + dataType + "', nullable=" + nullable + "}";
  }
}
