package me.christianrobert.orapgsync.transformer.inline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Definition of an inline type (package-level or block-level).
 *
 * <p>Oracle allows types to be defined at three levels:</p>
 * <ul>
 *   <li><strong>Schema-level:</strong> CREATE TYPE ... (already handled via composite types)</li>
 *   <li><strong>Package-level:</strong> TYPE declarations in package specifications</li>
 *   <li><strong>Block-level:</strong> TYPE declarations inside functions, procedures, or anonymous blocks</li>
 * </ul>
 *
 * <p>This class represents package-level and block-level type definitions for transformation to PostgreSQL.</p>
 *
 * <h3>Phase 1: JSON-First Strategy</h3>
 * <p>All inline types transform to jsonb for consistency and comprehensive Oracle feature coverage.
 * The {@code strategy} field is always {@link ConversionStrategy#JSONB} in Phase 1.</p>
 *
 * <h3>Type Categories</h3>
 * <ul>
 *   <li><strong>RECORD:</strong> Composite structure → jsonb object</li>
 *   <li><strong>TABLE OF:</strong> Dynamic array → jsonb array</li>
 *   <li><strong>VARRAY:</strong> Fixed-size array → jsonb array</li>
 *   <li><strong>INDEX BY:</strong> Associative array → jsonb object (key-value map)</li>
 *   <li><strong>ROWTYPE:</strong> Table row reference → jsonb object with columns</li>
 *   <li><strong>TYPE_REFERENCE:</strong> %TYPE reference → resolve to underlying type</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * // Oracle: TYPE salary_range_t IS RECORD (min_sal NUMBER, max_sal NUMBER);
 * InlineTypeDefinition recordType = new InlineTypeDefinition(
 *     "salary_range_t",
 *     TypeCategory.RECORD,
 *     null,  // No element type for RECORD
 *     List.of(
 *         new FieldDefinition("min_sal", "NUMBER", "numeric"),
 *         new FieldDefinition("max_sal", "NUMBER", "numeric")
 *     ),
 *     ConversionStrategy.JSONB,
 *     null   // No size limit
 * );
 *
 * // Oracle: TYPE num_list_t IS TABLE OF NUMBER;
 * InlineTypeDefinition tableOfType = new InlineTypeDefinition(
 *     "num_list_t",
 *     TypeCategory.TABLE_OF,
 *     "NUMBER",  // Element type
 *     null,      // No fields for collections
 *     ConversionStrategy.JSONB,
 *     null       // No size limit
 * );
 *
 * // Oracle: TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
 * InlineTypeDefinition varrayType = new InlineTypeDefinition(
 *     "codes_t",
 *     TypeCategory.VARRAY,
 *     "VARCHAR2",  // Element type
 *     null,        // No fields
 *     ConversionStrategy.JSONB,
 *     10           // Size limit (not enforced in PostgreSQL)
 * );
 *
 * // Oracle: TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
 * InlineTypeDefinition indexByType = new InlineTypeDefinition(
 *     "dept_map_t",
 *     TypeCategory.INDEX_BY,
 *     "VARCHAR2",  // Value type
 *     null,        // No fields
 *     ConversionStrategy.JSONB,
 *     null,        // No size limit
 *     "VARCHAR2"   // Index key type
 * );
 * }</pre>
 *
 * @see TypeCategory
 * @see ConversionStrategy
 * @see FieldDefinition
 */
public class InlineTypeDefinition {

    /**
     * Type name (e.g., "salary_range_t", "num_list_t").
     */
    private final String typeName;

    /**
     * Category of this type (RECORD, TABLE_OF, VARRAY, INDEX_BY, ROWTYPE, TYPE_REFERENCE).
     */
    private final TypeCategory category;

    /**
     * Element type for collections (TABLE_OF, VARRAY, INDEX BY).
     * Null for RECORD and ROWTYPE.
     * Examples: "NUMBER", "VARCHAR2", "employees%ROWTYPE"
     */
    private final String elementType;

    /**
     * Field definitions for RECORD and ROWTYPE types.
     * Null for collection types (TABLE_OF, VARRAY, INDEX BY).
     */
    private final List<FieldDefinition> fields;

    /**
     * Conversion strategy to PostgreSQL.
     * Phase 1: Always JSONB.
     * Phase 2: May be ARRAY or COMPOSITE for simple cases.
     */
    private final ConversionStrategy strategy;

    /**
     * Size limit for VARRAY types.
     * Null for other types.
     * Note: Size limit is not enforced in PostgreSQL jsonb arrays.
     */
    private final Integer sizeLimit;

    /**
     * Index key type for INDEX BY types (e.g., "VARCHAR2", "PLS_INTEGER").
     * Null for other types.
     */
    private final String indexKeyType;

    /**
     * Creates an inline type definition for RECORD or ROWTYPE.
     *
     * @param typeName Type name
     * @param category Type category (should be RECORD or ROWTYPE)
     * @param elementType Element type (null for RECORD/ROWTYPE)
     * @param fields Field definitions
     * @param strategy Conversion strategy
     * @param sizeLimit Size limit (null for RECORD/ROWTYPE)
     */
    public InlineTypeDefinition(String typeName,
                                TypeCategory category,
                                String elementType,
                                List<FieldDefinition> fields,
                                ConversionStrategy strategy,
                                Integer sizeLimit) {
        this(typeName, category, elementType, fields, strategy, sizeLimit, null);
    }

    /**
     * Creates an inline type definition with all parameters.
     *
     * @param typeName Type name
     * @param category Type category
     * @param elementType Element type for collections (null for RECORD/ROWTYPE)
     * @param fields Field definitions for RECORD/ROWTYPE (null for collections)
     * @param strategy Conversion strategy
     * @param sizeLimit Size limit for VARRAY (null for other types)
     * @param indexKeyType Index key type for INDEX BY (null for other types)
     */
    public InlineTypeDefinition(String typeName,
                                TypeCategory category,
                                String elementType,
                                List<FieldDefinition> fields,
                                ConversionStrategy strategy,
                                Integer sizeLimit,
                                String indexKeyType) {
        if (typeName == null || typeName.trim().isEmpty()) {
            throw new IllegalArgumentException("Type name cannot be null or empty");
        }
        if (category == null) {
            throw new IllegalArgumentException("Type category cannot be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Conversion strategy cannot be null");
        }

        this.typeName = typeName;
        this.category = category;
        this.elementType = elementType;
        this.fields = fields != null ? Collections.unmodifiableList(new ArrayList<>(fields)) : null;
        this.strategy = strategy;
        this.sizeLimit = sizeLimit;
        this.indexKeyType = indexKeyType;
    }

    /**
     * Gets the PostgreSQL type for this inline type.
     *
     * <p>Phase 1: Always returns "jsonb".</p>
     * <p>Phase 2: May return native types (e.g., "numeric[]", composite type names).</p>
     *
     * @return PostgreSQL type name
     */
    public String getPostgresType() {
        // Phase 1: All inline types → jsonb
        if (strategy == ConversionStrategy.JSONB) {
            return "jsonb";
        }

        // Phase 2 (Future): Native types
        if (strategy == ConversionStrategy.ARRAY) {
            // Example: numeric[], text[], etc.
            return elementType + "[]";
        }

        if (strategy == ConversionStrategy.COMPOSITE) {
            // Example: Custom composite type
            return typeName;
        }

        // Default fallback
        return "jsonb";
    }

    /**
     * Gets the PostgreSQL initializer for this type.
     *
     * <p>Returns appropriate empty value based on type category:</p>
     * <ul>
     *   <li>RECORD, INDEX_BY, ROWTYPE → {@code '{}'::jsonb} (empty object)</li>
     *   <li>TABLE_OF, VARRAY → {@code '[]'::jsonb} (empty array)</li>
     * </ul>
     *
     * @return PostgreSQL initializer expression
     */
    public String getInitializer() {
        if (strategy == ConversionStrategy.JSONB) {
            switch (category) {
                case RECORD:
                case INDEX_BY:
                case ROWTYPE:
                    return "'{}'::jsonb";  // Empty object
                case TABLE_OF:
                case VARRAY:
                    return "'[]'::jsonb";  // Empty array
                case TYPE_REFERENCE:
                    // TYPE_REFERENCE should be resolved to actual type first
                    return "'{}'::jsonb";  // Default to object
                default:
                    return "'{}'::jsonb";
            }
        }

        // Phase 2 (Future): Native type initializers
        if (strategy == ConversionStrategy.ARRAY) {
            return "ARRAY[]";
        }

        // Default fallback
        return "'{}'::jsonb";
    }

    /**
     * Checks if this is a collection type (TABLE_OF, VARRAY, INDEX_BY).
     *
     * @return true if collection type
     */
    public boolean isCollection() {
        return category == TypeCategory.TABLE_OF ||
               category == TypeCategory.VARRAY ||
               category == TypeCategory.INDEX_BY;
    }

    /**
     * Checks if this is a record type (RECORD, ROWTYPE).
     *
     * @return true if record type
     */
    public boolean isRecord() {
        return category == TypeCategory.RECORD ||
               category == TypeCategory.ROWTYPE;
    }

    /**
     * Checks if this is an indexed collection (array-like).
     *
     * @return true if TABLE_OF or VARRAY
     */
    public boolean isIndexedCollection() {
        return category == TypeCategory.TABLE_OF ||
               category == TypeCategory.VARRAY;
    }

    /**
     * Checks if this is an associative array (key-value map).
     *
     * @return true if INDEX_BY
     */
    public boolean isAssociativeArray() {
        return category == TypeCategory.INDEX_BY;
    }

    // Getters

    public String getTypeName() {
        return typeName;
    }

    public TypeCategory getCategory() {
        return category;
    }

    public String getElementType() {
        return elementType;
    }

    public List<FieldDefinition> getFields() {
        return fields;
    }

    public ConversionStrategy getStrategy() {
        return strategy;
    }

    public Integer getSizeLimit() {
        return sizeLimit;
    }

    public String getIndexKeyType() {
        return indexKeyType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InlineTypeDefinition that = (InlineTypeDefinition) o;
        return Objects.equals(typeName, that.typeName) &&
               category == that.category &&
               Objects.equals(elementType, that.elementType) &&
               Objects.equals(fields, that.fields) &&
               strategy == that.strategy &&
               Objects.equals(sizeLimit, that.sizeLimit) &&
               Objects.equals(indexKeyType, that.indexKeyType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, category, elementType, fields, strategy, sizeLimit, indexKeyType);
    }

    @Override
    public String toString() {
        return "InlineTypeDefinition{" +
               "typeName='" + typeName + '\'' +
               ", category=" + category +
               ", elementType='" + elementType + '\'' +
               ", fields=" + fields +
               ", strategy=" + strategy +
               ", sizeLimit=" + sizeLimit +
               ", indexKeyType='" + indexKeyType + '\'' +
               '}';
    }
}
