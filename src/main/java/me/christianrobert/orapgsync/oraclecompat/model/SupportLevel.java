package me.christianrobert.orapgsync.oraclecompat.model;

/**
 * Support level for Oracle built-in function replacements in PostgreSQL.
 */
public enum SupportLevel {
    /**
     * Full PostgreSQL equivalent - behavior matches Oracle closely.
     */
    FULL,

    /**
     * Partial implementation - covers common use cases but with limitations.
     */
    PARTIAL,

    /**
     * Stub only - no-op or minimal functionality, logs warning when called.
     */
    STUB,

    /**
     * Not implemented - documented as unsupported, manual migration required.
     */
    NONE
}
