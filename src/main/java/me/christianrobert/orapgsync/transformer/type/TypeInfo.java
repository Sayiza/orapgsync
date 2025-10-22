package me.christianrobert.orapgsync.transformer.type;

import java.util.Objects;

/**
 * Represents the data type of an expression in the SQL transformation pipeline.
 * <p>
 * This class is used for type-aware transformations (e.g., determining if ROUND needs a cast).
 * It supports both simple types (numeric, text) and complex types (user-defined composite types).
 * </p>
 * <p>
 * <strong>Design Note:</strong> This is part of a pluggable type evaluation architecture.
 * Currently used with {@link SimpleTypeEvaluator} for basic type checking.
 * In the future, can be used with a full two-pass type inference system for PL/SQL.
 * </p>
 */
public class TypeInfo {

    // Pre-defined common types for convenience
    public static final TypeInfo UNKNOWN = new TypeInfo(TypeCategory.UNKNOWN, null, null, null);
    public static final TypeInfo INTEGER = new TypeInfo(TypeCategory.NUMERIC, "integer", null, null);
    public static final TypeInfo NUMERIC = new TypeInfo(TypeCategory.NUMERIC, "numeric", null, null);
    public static final TypeInfo TEXT = new TypeInfo(TypeCategory.TEXT, "text", null, null);
    public static final TypeInfo DATE = new TypeInfo(TypeCategory.DATE, "date", null, null);
    public static final TypeInfo TIMESTAMP = new TypeInfo(TypeCategory.DATE, "timestamp", null, null);
    public static final TypeInfo BOOLEAN = new TypeInfo(TypeCategory.BOOLEAN, "boolean", null, null);

    private final TypeCategory category;
    private final String postgresType;
    private final Integer precision;
    private final Integer scale;

    /**
     * Creates a TypeInfo instance.
     *
     * @param category      The broad category of the type (numeric, text, etc.)
     * @param postgresType  The PostgreSQL type name (e.g., "numeric", "text", "hr.address_type")
     * @param precision     Optional precision (e.g., 10 in numeric(10,2))
     * @param scale         Optional scale (e.g., 2 in numeric(10,2))
     */
    public TypeInfo(TypeCategory category, String postgresType, Integer precision, Integer scale) {
        this.category = category;
        this.postgresType = postgresType;
        this.precision = precision;
        this.scale = scale;
    }

    /**
     * Creates a simple TypeInfo without precision/scale.
     */
    public static TypeInfo of(TypeCategory category, String postgresType) {
        return new TypeInfo(category, postgresType, null, null);
    }

    /**
     * Creates a numeric TypeInfo with precision and scale.
     */
    public static TypeInfo numeric(int precision, int scale) {
        return new TypeInfo(TypeCategory.NUMERIC, "numeric", precision, scale);
    }

    /**
     * Creates a composite type (user-defined object type).
     */
    public static TypeInfo composite(String schemaQualifiedTypeName) {
        return new TypeInfo(TypeCategory.COMPOSITE, schemaQualifiedTypeName, null, null);
    }

    // Category checks
    public boolean isUnknown() {
        return category == TypeCategory.UNKNOWN;
    }

    public boolean isNumeric() {
        return category == TypeCategory.NUMERIC;
    }

    public boolean isText() {
        return category == TypeCategory.TEXT;
    }

    public boolean isDate() {
        return category == TypeCategory.DATE;
    }

    public boolean isBoolean() {
        return category == TypeCategory.BOOLEAN;
    }

    public boolean isComposite() {
        return category == TypeCategory.COMPOSITE;
    }

    // Getters
    public TypeCategory getCategory() {
        return category;
    }

    public String getPostgresType() {
        return postgresType;
    }

    public Integer getPrecision() {
        return precision;
    }

    public Integer getScale() {
        return scale;
    }

    /**
     * Returns the full PostgreSQL type declaration (e.g., "numeric(10,2)").
     */
    public String getFullTypeDeclaration() {
        if (postgresType == null) {
            return "unknown";
        }

        if (precision != null && scale != null) {
            return postgresType + "(" + precision + "," + scale + ")";
        } else if (precision != null) {
            return postgresType + "(" + precision + ")";
        } else {
            return postgresType;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypeInfo typeInfo = (TypeInfo) o;
        return category == typeInfo.category &&
                Objects.equals(postgresType, typeInfo.postgresType) &&
                Objects.equals(precision, typeInfo.precision) &&
                Objects.equals(scale, typeInfo.scale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, postgresType, precision, scale);
    }

    @Override
    public String toString() {
        return "TypeInfo{" + getFullTypeDeclaration() + ", category=" + category + "}";
    }

    /**
     * Broad category of data types for quick checks.
     */
    public enum TypeCategory {
        UNKNOWN,    // Type could not be determined
        NUMERIC,    // Integer, numeric, real, etc.
        TEXT,       // Text, varchar, char, etc.
        DATE,       // Date, timestamp, time, interval
        BOOLEAN,    // Boolean
        COMPOSITE   // User-defined composite types
    }
}
