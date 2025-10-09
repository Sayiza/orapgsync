package me.christianrobert.orapgsync.core.job.model.synonym;

import java.util.Objects;

/**
 * Metadata for an Oracle synonym.
 *
 * <p>Synonyms are Oracle database objects that provide alternative names for tables, views,
 * sequences, procedures, functions, packages, materialized views, Java class schema objects,
 * user-defined object types, or other synonyms.</p>
 *
 * <p>PostgreSQL does not have a direct equivalent to Oracle synonyms. Instead, we store synonym
 * metadata in the application state and resolve them during migration (e.g., when creating object
 * types that reference other types via synonyms).</p>
 *
 * <h3>Oracle Synonym Resolution Rules</h3>
 * <ol>
 *   <li>Schema-qualified reference (e.g., MYSCHEMA.MYTYPE) - Direct resolution</li>
 *   <li>Unqualified reference in current schema - Check for synonym in current schema</li>
 *   <li>If not found - Check for PUBLIC synonym</li>
 *   <li>If not found - Error</li>
 * </ol>
 */
public class SynonymMetadata {

    /**
     * The schema/owner of the synonym (e.g., "myschema" or "public").
     */
    private String owner;

    /**
     * The name of the synonym (e.g., "my_synonym").
     */
    private String synonymName;

    /**
     * The schema/owner of the target object (e.g., "otherschema").
     */
    private String tableOwner;

    /**
     * The name of the target object (e.g., "my_type", "my_table").
     */
    private String tableName;

    /**
     * The database link name if the synonym points to a remote object, otherwise null.
     */
    private String dbLink;

    public SynonymMetadata() {
    }

    public SynonymMetadata(String owner, String synonymName, String tableOwner, String tableName, String dbLink) {
        this.owner = owner;
        this.synonymName = synonymName;
        this.tableOwner = tableOwner;
        this.tableName = tableName;
        this.dbLink = dbLink;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getSynonymName() {
        return synonymName;
    }

    public void setSynonymName(String synonymName) {
        this.synonymName = synonymName;
    }

    public String getTableOwner() {
        return tableOwner;
    }

    public void setTableOwner(String tableOwner) {
        this.tableOwner = tableOwner;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDbLink() {
        return dbLink;
    }

    public void setDbLink(String dbLink) {
        this.dbLink = dbLink;
    }

    /**
     * Returns the fully qualified target reference (table_owner.table_name).
     */
    public String getQualifiedTarget() {
        return String.format("%s.%s", tableOwner, tableName);
    }

    /**
     * Returns the fully qualified synonym reference (owner.synonym_name).
     */
    public String getQualifiedSynonym() {
        return String.format("%s.%s", owner, synonymName);
    }

    /**
     * Checks if this synonym points to a remote database (has a database link).
     */
    public boolean isRemote() {
        return dbLink != null && !dbLink.trim().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SynonymMetadata that = (SynonymMetadata) o;
        return Objects.equals(owner, that.owner) &&
               Objects.equals(synonymName, that.synonymName) &&
               Objects.equals(tableOwner, that.tableOwner) &&
               Objects.equals(tableName, that.tableName) &&
               Objects.equals(dbLink, that.dbLink);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, synonymName, tableOwner, tableName, dbLink);
    }

    @Override
    public String toString() {
        if (isRemote()) {
            return String.format("SynonymMetadata{%s → %s@%s}",
                getQualifiedSynonym(), getQualifiedTarget(), dbLink);
        }
        return String.format("SynonymMetadata{%s → %s}",
            getQualifiedSynonym(), getQualifiedTarget());
    }
}
