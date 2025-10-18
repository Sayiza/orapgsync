package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import java.util.*;

/**
 * Context for Oracle (+) outer join transformation.
 *
 * <p>Stores metadata discovered during the analysis phase:
 * <ul>
 *   <li>Table information (name, alias, key)</li>
 *   <li>Outer join conditions discovered in WHERE clause</li>
 *   <li>Regular WHERE conditions (to be preserved)</li>
 * </ul>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Created in VisitQueryBlock before visiting FROM/WHERE</li>
 *   <li>Populated during analysis phase (scan FROM and WHERE clauses)</li>
 *   <li>Used during transformation phase (generate ANSI JOIN syntax)</li>
 * </ol>
 *
 * <p>This context is query-local and created fresh for each query/subquery.
 */
public class OuterJoinContext {

    // Table metadata: key → TableInfo
    private final Map<String, TableInfo> tables;

    // Outer join conditions discovered in WHERE clause
    private final List<OuterJoinCondition> outerJoins;

    // WHERE conditions that contain (+) and should be removed
    private final Set<String> conditionsToRemove;

    // Regular WHERE conditions to preserve (non-outer-join)
    private final List<String> regularWhereConditions;

    public OuterJoinContext() {
        this.tables = new LinkedHashMap<>();  // Preserve insertion order
        this.outerJoins = new ArrayList<>();
        this.conditionsToRemove = new HashSet<>();
        this.regularWhereConditions = new ArrayList<>();
    }

    // ========== Table Management ==========

    /**
     * Registers a table from the FROM clause.
     *
     * @param name Table name
     * @param alias Table alias (null if none)
     */
    public void registerTable(String name, String alias) {
        TableInfo tableInfo = new TableInfo(name, alias);
        tables.put(tableInfo.getKey(), tableInfo);
    }

    /**
     * Gets table information by key (alias or name).
     *
     * @param key Table key
     * @return TableInfo or null if not found
     */
    public TableInfo getTable(String key) {
        return tables.get(key);
    }

    /**
     * Returns all tables in FROM clause order.
     *
     * @return List of TableInfo
     */
    public List<TableInfo> getAllTables() {
        return new ArrayList<>(tables.values());
    }

    /**
     * Returns the first table (root table for joins).
     *
     * @return First TableInfo or null if no tables
     */
    public TableInfo getFirstTable() {
        return tables.values().stream().findFirst().orElse(null);
    }

    // ========== Outer Join Management ==========

    /**
     * Registers an outer join condition.
     *
     * <p>If a join between the same two tables already exists, the condition
     * is added to the existing join. Otherwise, a new join is created.
     *
     * @param tableKey1 First table key
     * @param tableKey2 Second table key
     * @param joinType Join type (LEFT or RIGHT)
     * @param condition Join condition (without (+))
     */
    public void registerOuterJoin(String tableKey1, String tableKey2,
                                   OuterJoinCondition.JoinType joinType,
                                   String condition) {
        // Find existing join between these tables
        OuterJoinCondition existingJoin = findOuterJoin(tableKey1, tableKey2);

        if (existingJoin != null) {
            // Add condition to existing join
            existingJoin.addCondition(condition);
        } else {
            // Create new join
            OuterJoinCondition newJoin = new OuterJoinCondition(tableKey1, tableKey2, joinType);
            newJoin.addCondition(condition);
            outerJoins.add(newJoin);
        }
    }

    /**
     * Finds an existing outer join between two tables.
     *
     * @param tableKey1 First table key
     * @param tableKey2 Second table key
     * @return OuterJoinCondition or null if not found
     */
    private OuterJoinCondition findOuterJoin(String tableKey1, String tableKey2) {
        for (OuterJoinCondition join : outerJoins) {
            if ((join.getTableKey1().equals(tableKey1) && join.getTableKey2().equals(tableKey2)) ||
                (join.getTableKey1().equals(tableKey2) && join.getTableKey2().equals(tableKey1))) {
                return join;
            }
        }
        return null;
    }

    /**
     * Returns all outer join conditions.
     *
     * @return List of OuterJoinCondition
     */
    public List<OuterJoinCondition> getOuterJoins() {
        return new ArrayList<>(outerJoins);
    }

    /**
     * Checks if there are any outer joins.
     *
     * @return true if outer joins exist
     */
    public boolean hasOuterJoins() {
        return !outerJoins.isEmpty();
    }

    // ========== WHERE Condition Management ==========

    /**
     * Marks a WHERE condition for removal (because it contains (+) and was converted to JOIN).
     *
     * @param condition Condition text to remove
     */
    public void markConditionForRemoval(String condition) {
        if (condition != null && !condition.trim().isEmpty()) {
            conditionsToRemove.add(condition.trim());
        }
    }

    /**
     * Checks if a condition should be removed from WHERE.
     *
     * @param condition Condition to check
     * @return true if should be removed
     */
    public boolean shouldRemoveCondition(String condition) {
        return condition != null && conditionsToRemove.contains(condition.trim());
    }

    /**
     * Adds a regular WHERE condition (non-outer-join).
     *
     * @param condition Regular condition to preserve
     */
    public void addRegularWhereCondition(String condition) {
        if (condition != null && !condition.trim().isEmpty()) {
            regularWhereConditions.add(condition);
        }
    }

    /**
     * Returns all regular WHERE conditions.
     *
     * @return List of regular WHERE conditions
     */
    public List<String> getRegularWhereConditions() {
        return new ArrayList<>(regularWhereConditions);
    }

    /**
     * Returns the combined WHERE clause (all regular conditions with AND).
     *
     * @return WHERE clause string or null if no conditions
     */
    public String buildWhereClause() {
        if (regularWhereConditions.isEmpty()) {
            return null;
        }
        return String.join(" AND ", regularWhereConditions);
    }

    // ========== Debug and Utility ==========

    @Override
    public String toString() {
        return String.format("OuterJoinContext{tables=%s, outerJoins=%s, regularConditions=%d}",
            tables.keySet(), outerJoins.size(), regularWhereConditions.size());
    }

    /**
     * Returns a detailed debug representation.
     *
     * @return Detailed string representation
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder("OuterJoinContext{\n");
        sb.append("  Tables:\n");
        for (TableInfo table : tables.values()) {
            sb.append("    ").append(table).append("\n");
        }
        sb.append("  Outer Joins:\n");
        for (OuterJoinCondition join : outerJoins) {
            sb.append("    ").append(join).append("\n");
        }
        sb.append("  Regular WHERE Conditions:\n");
        for (String cond : regularWhereConditions) {
            sb.append("    ").append(cond).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}
