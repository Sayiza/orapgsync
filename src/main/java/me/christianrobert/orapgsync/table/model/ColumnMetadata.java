package me.christianrobert.orapgsync.table.model;

import me.christianrobert.orapgsync.core.State;
import me.christianrobert.orapgsync.core.PostgreSqlIdentifierUtils;
import me.christianrobert.orapgsync.core.TypeConverter;

// Represents a column's metadata
public class ColumnMetadata {
  private String columnName;
  private String dataType; // Oracle data type (to be mapped to PostgreSQL)
  private Integer characterLength; // For VARCHAR2, CHAR
  private Integer numericPrecision; // For NUMBER
  private Integer numericScale; // For NUMBER
  private boolean nullable;
  private String defaultValue;

  public ColumnMetadata(String columnName, String dataType, Integer characterLength,
                        Integer numericPrecision, Integer numericScale, boolean nullable, String defaultValue) {
    this.columnName = columnName;
    this.dataType = dataType;
    this.characterLength = characterLength;
    this.numericPrecision = numericPrecision;
    this.numericScale = numericScale;
    this.nullable = nullable;
    this.defaultValue = defaultValue;
  }

  // Getters
  public String getColumnName() { return columnName; }
  public String getDataType() { return dataType; }
  public Integer getCharacterLength() { return characterLength; }
  public Integer getNumericPrecision() { return numericPrecision; }
  public Integer getNumericScale() { return numericScale; }
  public boolean isNullable() { return nullable; }
  public String getDefaultValue() { return defaultValue; }

  @Override
  public String toString() {
    return "ColumnMetadata{name='" + columnName + "', type='" + dataType + "', nullable=" + nullable + "}";
  }

  public String toPostgre(State data, String schemaWhereWeAreNow, String myTableName) {
    StringBuilder colDef = new StringBuilder();
    colDef.append("    ")
            .append(PostgreSqlIdentifierUtils.quoteIdentifier(this.getColumnName()))
            .append(" ");
    String b4convert = this.getDataType();
    String afterConvert = TypeConverter.toPostgre(b4convert);
    /*
    if (afterConvert.equalsIgnoreCase(b4convert)) {
      String schema = SchemaResolutionUtils.lookupSchema4ObjectType(data, afterConvert, schemaWhereWeAreNow);
      if (schema != null) {
        colDef.append(schema).append(".");
      }
    }
    colDef.append(afterConvert);
    // todo lookup schema of datatype!
    if (!this.isNullable()) {
      colDef.append(" NOT NULL");
    }
    if (this.getDefaultValue() != null && !this.getDefaultValue().isEmpty()) {
      // Handle simple default values; complex expressions may need manual review
      String defaultValue = CodeCleaner.noComments(this.getDefaultValue()).trim();

      // Remove Oracle-specific quotes or functions if necessary
      if (defaultValue.startsWith("'") && defaultValue.endsWith("'")) {
        colDef.append(" DEFAULT ").append(defaultValue);
      } else if (defaultValue.matches("-?\\d+(\\.\\d+)?")) {
        colDef.append(" DEFAULT ").append(defaultValue);
      }  else if (defaultValue.equals("J")
              || defaultValue.equals("N")) {
        colDef.append(" DEFAULT ").append(defaultValue);
      } else if (defaultValue.equalsIgnoreCase("SYSTIMESTAMP")) {
        colDef.append(" DEFAULT CURRENT_TIMESTAMP");
      } else if (defaultValue.equalsIgnoreCase("SYSDATE")) {
        colDef.append(" DEFAULT CURRENT_DATE");
      } else if (defaultValue.equalsIgnoreCase("USER")) {
        colDef.append(" DEFAULT CURRENT_USER");
      } else if (defaultValue.equalsIgnoreCase("NULL")) {
        colDef.append(" DEFAULT NULL");
      } else {
        // TODO find real default value
        data.findDefaultExpression(schemaWhereWeAreNow, myTableName, columnName);
        // For complex defaults (e.g., SYSDATE), skip or log for manual review
        //colDef.append(" -- DEFAULT ").append(defaultValue).append(" (review for PostgreSQL)");
      }
    }

     */
    return colDef.toString();
  }

}
