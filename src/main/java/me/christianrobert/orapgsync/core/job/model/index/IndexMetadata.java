package me.christianrobert.orapgsync.core.job.model.index;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An Oracle index that is not backed by a primary key or unique constraint.
 *
 * <p>Constraint-backed indexes are deliberately absent: PostgreSQL creates those itself when the
 * constraint is created, so migrating them as well would produce duplicates. They are filtered
 * during extraction by joining {@code ALL_CONSTRAINTS.INDEX_NAME}, not by matching
 * {@code SYS_C%} name patterns - a hand-named constraint index would slip straight through a
 * name pattern.</p>
 *
 * <p>Indexes are held in their own state list rather than inside {@code TableMetadata}, which is
 * consumed by the data transfer path and its normalizer; index migration has no business
 * mutating a model those steps depend on.</p>
 */
public class IndexMetadata {

    /** Oracle {@code INDEX_TYPE} values. */
    public static final String TYPE_NORMAL = "NORMAL";
    public static final String TYPE_BITMAP = "BITMAP";
    public static final String TYPE_DOMAIN = "DOMAIN";

    private final String schema;
    private final String tableName;
    private final String indexName;
    private final boolean unique;
    private final String indexType;
    private final List<IndexKeyPart> keyParts;

    public IndexMetadata(String schema, String tableName, String indexName,
                         boolean unique, String indexType, List<IndexKeyPart> keyParts) {
        this.schema = schema == null ? null : schema.toLowerCase();
        this.tableName = tableName == null ? null : tableName.toLowerCase();
        this.indexName = indexName == null ? null : indexName.toLowerCase();
        this.unique = unique;
        this.indexType = indexType;
        this.keyParts = keyParts == null ? new ArrayList<>() : new ArrayList<>(keyParts);
    }

    public String getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }

    public String getIndexName() {
        return indexName;
    }

    public boolean isUnique() {
        return unique;
    }

    public String getIndexType() {
        return indexType;
    }

    public List<IndexKeyPart> getKeyParts() {
        return new ArrayList<>(keyParts);
    }

    public String getQualifiedTableName() {
        return IndexSignature.qualifiedName(schema, tableName);
    }

    /** True when any key is an expression rather than a plain column. */
    public boolean isFunctionBased() {
        return keyParts.stream().anyMatch(IndexKeyPart::isExpression);
    }

    /**
     * Oracle bitmap indexes have no PostgreSQL equivalent and are migrated as plain B-trees. That
     * changes the storage characteristics, so the creation job records it as a note.
     */
    public boolean isBitmap() {
        return TYPE_BITMAP.equalsIgnoreCase(indexType);
    }

    /** Domain (and text/spatial) indexes depend on Oracle-specific index types and cannot be migrated. */
    public boolean isDomain() {
        return indexType != null && indexType.toUpperCase().contains(TYPE_DOMAIN);
    }

    public IndexSignature getSignature() {
        return IndexSignature.of(schema, tableName, unique, keyParts);
    }

    /** Key parts rendered for display, e.g. {@code "dept_id, hire_date DESC"}. */
    public String getKeyDisplay() {
        return keyParts.stream().map(IndexKeyPart::toString).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return String.format("IndexMetadata{%s on %s (%s)%s}",
                indexName, getQualifiedTableName(), getKeyDisplay(), unique ? " UNIQUE" : "");
    }
}
