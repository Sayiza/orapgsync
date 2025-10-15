package me.christianrobert.orapgsync.transformation.parser;

/**
 * Enumeration of SQL/PL-SQL statement types that can be parsed and transformed.
 */
public enum SqlType {
    /**
     * SELECT statement used in view definitions.
     */
    VIEW_SELECT,

    /**
     * PL/SQL function body (future).
     */
    FUNCTION_BODY,

    /**
     * PL/SQL procedure body (future).
     */
    PROCEDURE_BODY,

    /**
     * PL/SQL trigger body (future).
     */
    TRIGGER_BODY,

    /**
     * PL/SQL type method body (future).
     */
    TYPE_METHOD_BODY
}
