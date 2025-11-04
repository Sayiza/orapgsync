package me.christianrobert.orapgsync.transformer.inline;

import java.util.Objects;

/**
 * Represents a field within a RECORD type or %ROWTYPE.
 *
 * <p>Stores both Oracle and PostgreSQL type information for proper transformation.</p>
 *
 * <p>Example Oracle RECORD:</p>
 * <pre>{@code
 * TYPE salary_range_t IS RECORD (
 *   min_sal NUMBER,      -- FieldDefinition: fieldName="min_sal", oracleType="NUMBER", postgresType="numeric"
 *   max_sal NUMBER,      -- FieldDefinition: fieldName="max_sal", oracleType="NUMBER", postgresType="numeric"
 *   currency VARCHAR2(3) -- FieldDefinition: fieldName="currency", oracleType="VARCHAR2", postgresType="text"
 * );
 * }</pre>
 *
 * @see InlineTypeDefinition
 * @see TypeCategory#RECORD
 */
public class FieldDefinition {

    /**
     * Field name (e.g., "min_sal", "max_sal").
     */
    private final String fieldName;

    /**
     * Oracle type name (e.g., "NUMBER", "VARCHAR2", "DATE").
     */
    private final String oracleType;

    /**
     * PostgreSQL type name after conversion (e.g., "numeric", "text", "timestamp").
     */
    private final String postgresType;

    /**
     * Creates a field definition with Oracle and PostgreSQL types.
     *
     * @param fieldName Field name
     * @param oracleType Oracle type name
     * @param postgresType PostgreSQL type name
     */
    public FieldDefinition(String fieldName, String oracleType, String postgresType) {
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name cannot be null or empty");
        }
        if (oracleType == null || oracleType.trim().isEmpty()) {
            throw new IllegalArgumentException("Oracle type cannot be null or empty");
        }
        if (postgresType == null || postgresType.trim().isEmpty()) {
            throw new IllegalArgumentException("PostgreSQL type cannot be null or empty");
        }

        this.fieldName = fieldName;
        this.oracleType = oracleType;
        this.postgresType = postgresType;
    }

    /**
     * Gets the field name.
     *
     * @return Field name (e.g., "min_sal")
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Gets the Oracle type name.
     *
     * @return Oracle type (e.g., "NUMBER")
     */
    public String getOracleType() {
        return oracleType;
    }

    /**
     * Gets the PostgreSQL type name.
     *
     * @return PostgreSQL type (e.g., "numeric")
     */
    public String getPostgresType() {
        return postgresType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FieldDefinition that = (FieldDefinition) o;
        return Objects.equals(fieldName, that.fieldName) &&
               Objects.equals(oracleType, that.oracleType) &&
               Objects.equals(postgresType, that.postgresType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, oracleType, postgresType);
    }

    @Override
    public String toString() {
        return "FieldDefinition{" +
               "fieldName='" + fieldName + '\'' +
               ", oracleType='" + oracleType + '\'' +
               ", postgresType='" + postgresType + '\'' +
               '}';
    }
}
