package me.christianrobert.orapgsync.core.job.model.trigger;

import java.util.Objects;

/**
 * Metadata for database triggers.
 * Represents trigger information extracted from Oracle or PostgreSQL databases.
 */
public class TriggerMetadata {
    private String schema;        // Trigger's owning schema
    private String triggerName;
    private String tableSchema;   // Table's schema (may differ from trigger schema)
    private String tableName;
    private String triggerType;   // BEFORE/AFTER/INSTEAD OF
    private String triggerEvent;  // INSERT/UPDATE/DELETE
    private String triggerLevel;  // ROW/STATEMENT
    private String whenClause;    // Optional WHEN condition
    private String triggerBody;   // PL/SQL or PL/pgSQL code
    private String status;        // ENABLED/DISABLED

    // PostgreSQL-specific DDL (for verification and manual review)
    private String postgresFunctionDdl;  // CREATE OR REPLACE FUNCTION ... (full function DDL)
    private String postgresTriggerDdl;   // CREATE TRIGGER ... (trigger definition DDL)

    public TriggerMetadata() {
    }

    public TriggerMetadata(String schema, String triggerName, String tableName) {
        this.schema = schema;
        this.triggerName = triggerName;
        this.tableSchema = schema; // Default to trigger schema for backwards compatibility
        this.tableName = tableName;
    }

    public TriggerMetadata(String schema, String triggerName, String tableSchema, String tableName) {
        this.schema = schema;
        this.triggerName = triggerName;
        this.tableSchema = tableSchema;
        this.tableName = tableName;
    }

    // Getters and setters
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTriggerName() {
        return triggerName;
    }

    public void setTriggerName(String triggerName) {
        this.triggerName = triggerName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableSchema() {
        return tableSchema;
    }

    public void setTableSchema(String tableSchema) {
        this.tableSchema = tableSchema;
    }

    /**
     * Returns the qualified table name (table_schema.table_name).
     */
    public String getQualifiedTableName() {
        return tableSchema + "." + tableName;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getTriggerEvent() {
        return triggerEvent;
    }

    public void setTriggerEvent(String triggerEvent) {
        this.triggerEvent = triggerEvent;
    }

    public String getTriggerLevel() {
        return triggerLevel;
    }

    public void setTriggerLevel(String triggerLevel) {
        this.triggerLevel = triggerLevel;
    }

    public String getWhenClause() {
        return whenClause;
    }

    public void setWhenClause(String whenClause) {
        this.whenClause = whenClause;
    }

    public String getTriggerBody() {
        return triggerBody;
    }

    public void setTriggerBody(String triggerBody) {
        this.triggerBody = triggerBody;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPostgresFunctionDdl() {
        return postgresFunctionDdl;
    }

    public void setPostgresFunctionDdl(String postgresFunctionDdl) {
        this.postgresFunctionDdl = postgresFunctionDdl;
    }

    public String getPostgresTriggerDdl() {
        return postgresTriggerDdl;
    }

    public void setPostgresTriggerDdl(String postgresTriggerDdl) {
        this.postgresTriggerDdl = postgresTriggerDdl;
    }

    /**
     * Returns the qualified name of the trigger (schema.trigger_name).
     */
    public String getQualifiedName() {
        return schema + "." + triggerName;
    }

    /**
     * Returns a display name for the trigger.
     */
    public String getDisplayName() {
        return triggerName + " ON " + getQualifiedTableName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TriggerMetadata that = (TriggerMetadata) o;
        return Objects.equals(schema, that.schema) &&
                Objects.equals(triggerName, that.triggerName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, triggerName);
    }

    @Override
    public String toString() {
        return String.format("TriggerMetadata{schema='%s', trigger='%s', tableSchema='%s', table='%s', type='%s', event='%s', level='%s'}",
                schema, triggerName, tableSchema, tableName, triggerType, triggerEvent, triggerLevel);
    }
}
