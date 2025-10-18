package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single outer join relationship between two tables.
 *
 * <p>Used during Oracle (+) outer join transformation to track:
 * <ul>
 *   <li>The two tables involved (by their keys: alias or table name)</li>
 *   <li>The join type (LEFT or RIGHT)</li>
 *   <li>All join conditions (multiple conditions for the same table pair are combined)</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * WHERE a.field1 = b.field1(+)
 *   AND a.field2 = b.field2(+)
 *
 * Creates one OuterJoinCondition:
 *   tableKey1: "a"
 *   tableKey2: "b"
 *   joinType: LEFT
 *   conditions: ["a.field1 = b.field1", "a.field2 = b.field2"]
 * </pre>
 */
public class OuterJoinCondition {

    public enum JoinType {
        LEFT,   // (+) on right side: a.field = b.field(+)
        RIGHT   // (+) on left side:  a.field(+) = b.field
    }

    private final String tableKey1;  // First table (left side, non-nullable)
    private final String tableKey2;  // Second table (right side, nullable)
    private final JoinType joinType;
    private final List<String> conditions;  // Join conditions without (+)

    /**
     * Creates a new outer join condition.
     *
     * @param tableKey1 First table key (alias or table name)
     * @param tableKey2 Second table key (alias or table name)
     * @param joinType Join type (LEFT or RIGHT)
     */
    public OuterJoinCondition(String tableKey1, String tableKey2, JoinType joinType) {
        this.tableKey1 = Objects.requireNonNull(tableKey1, "tableKey1 cannot be null");
        this.tableKey2 = Objects.requireNonNull(tableKey2, "tableKey2 cannot be null");
        this.joinType = Objects.requireNonNull(joinType, "joinType cannot be null");
        this.conditions = new ArrayList<>();
    }

    /**
     * Adds a join condition (without the (+) operator).
     *
     * @param condition Join condition (e.g., "a.field1 = b.field1")
     */
    public void addCondition(String condition) {
        if (condition != null && !condition.trim().isEmpty()) {
            conditions.add(condition);
        }
    }

    public String getTableKey1() {
        return tableKey1;
    }

    public String getTableKey2() {
        return tableKey2;
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public List<String> getConditions() {
        return new ArrayList<>(conditions);  // Return defensive copy
    }

    /**
     * Returns all conditions combined with AND.
     *
     * @return Combined conditions string (e.g., "a.field1 = b.field1 AND a.field2 = b.field2")
     */
    public String getCombinedConditions() {
        return String.join(" AND ", conditions);
    }

    /**
     * Checks if this join involves the specified table key.
     *
     * @param tableKey Table key to check
     * @return true if this join involves the table
     */
    public boolean involvesTable(String tableKey) {
        return tableKey1.equals(tableKey) || tableKey2.equals(tableKey);
    }

    @Override
    public String toString() {
        return String.format("OuterJoinCondition{%s %s JOIN %s ON %s}",
            tableKey1, joinType, tableKey2, getCombinedConditions());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OuterJoinCondition that = (OuterJoinCondition) o;
        return Objects.equals(tableKey1, that.tableKey1) &&
               Objects.equals(tableKey2, that.tableKey2) &&
               joinType == that.joinType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableKey1, tableKey2, joinType);
    }
}
