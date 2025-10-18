package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import java.util.Objects;

/**
 * Stores information about a table in the FROM clause.
 *
 * <p>Used during outer join transformation to track:
 * <ul>
 *   <li>The actual table name</li>
 *   <li>The alias (if present)</li>
 *   <li>The "table key" used for lookups (alias if present, else table name)</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>
 * FROM employees e      → TableInfo(name="employees", alias="e", key="e")
 * FROM departments      → TableInfo(name="departments", alias=null, key="departments")
 * </pre>
 */
public class TableInfo {

    private final String name;   // Actual table name
    private final String alias;  // Alias (null if no alias)

    /**
     * Creates table information.
     *
     * @param name Actual table name
     * @param alias Table alias (null if none)
     */
    public TableInfo(String name, String alias) {
        this.name = Objects.requireNonNull(name, "Table name cannot be null");
        this.alias = alias;  // Can be null
    }

    /**
     * Returns the table key for lookups.
     * Uses alias if present, otherwise uses table name.
     *
     * @return Table key (alias or name)
     */
    public String getKey() {
        return alias != null ? alias : name;
    }

    public String getName() {
        return name;
    }

    public String getAlias() {
        return alias;
    }

    public boolean hasAlias() {
        return alias != null;
    }

    /**
     * Returns the table reference for SQL (name with optional alias).
     *
     * @return SQL table reference (e.g., "employees e" or "departments")
     */
    public String toSqlReference() {
        if (alias != null) {
            return name + " " + alias;
        }
        return name;
    }

    @Override
    public String toString() {
        return String.format("TableInfo{name='%s', alias='%s', key='%s'}", name, alias, getKey());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableInfo tableInfo = (TableInfo) o;
        return Objects.equals(name, tableInfo.name) &&
               Objects.equals(alias, tableInfo.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, alias);
    }
}
