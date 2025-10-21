package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;

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
 * <p><b>ARCHITECTURE: AST Node Storage</b>
 * <p>This class stores AST nodes ({@link PlSqlParser.Relational_expressionContext}) rather
 * than pre-transformed strings. This allows join conditions to be transformed during the
 * transformation phase, ensuring all Oracle functions (INSTR, TRUNC, etc.) are properly
 * converted to PostgreSQL equivalents.
 *
 * <p>Example:
 * <pre>
 * WHERE a.field1 = b.field1(+)
 *   AND TRUNC(a.field2) = TRUNC(b.field2(+))
 *
 * Creates one OuterJoinCondition:
 *   tableKey1: "a"
 *   tableKey2: "b"
 *   joinType: LEFT
 *   conditionNodes: [AST for "a.field1 = b.field1", AST for "TRUNC(a.field2) = TRUNC(b.field2)"]
 *
 * During transformation:
 *   → LEFT JOIN b ON a.field1 = b.field1 AND DATE_TRUNC('day', a.field2)::DATE = DATE_TRUNC('day', b.field2)::DATE
 * </pre>
 *
 * <p><b>COORDINATION WITH OTHER TRANSFORMATIONS:</b>
 * <ul>
 *   <li><b>ROWNUM:</b> No interference - both filter WHERE clause independently</li>
 *   <li><b>CONNECT BY:</b> No interference - CONNECT BY rewrites entire query first</li>
 * </ul>
 */
public class OuterJoinCondition {

    public enum JoinType {
        LEFT,   // (+) on right side: a.field = b.field(+)
        RIGHT   // (+) on left side:  a.field(+) = b.field
    }

    private final String tableKey1;  // First table (left side, non-nullable)
    private final String tableKey2;  // Second table (right side, nullable)
    private final JoinType joinType;

    // Store AST nodes instead of strings to enable transformation during visit phase
    private final List<PlSqlParser.Relational_expressionContext> conditionNodes;

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
        this.conditionNodes = new ArrayList<>();
    }

    /**
     * Adds a join condition AST node.
     *
     * <p>The node will be transformed during the transformation phase by visiting it
     * with the PostgresCodeBuilder, ensuring all Oracle-specific functions are converted.
     *
     * @param conditionNode AST node for the join condition (without (+) operator)
     */
    public void addConditionNode(PlSqlParser.Relational_expressionContext conditionNode) {
        if (conditionNode != null) {
            conditionNodes.add(conditionNode);
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

    /**
     * Returns all condition AST nodes.
     *
     * @return List of AST nodes representing join conditions
     */
    public List<PlSqlParser.Relational_expressionContext> getConditionNodes() {
        return new ArrayList<>(conditionNodes);  // Return defensive copy
    }

    /**
     * Transforms and combines all join conditions with AND.
     *
     * <p>This method visits each stored AST node using the provided builder,
     * ensuring all Oracle-specific functions are transformed to PostgreSQL equivalents.
     *
     * <p>Example:
     * <pre>
     * Oracle: a.id = b.id(+) AND TRUNC(a.date) = TRUNC(b.date(+))
     * PostgreSQL: a.id = b.id AND DATE_TRUNC('day', a.date)::DATE = DATE_TRUNC('day', b.date)::DATE
     * </pre>
     *
     * @param builder PostgresCodeBuilder for transforming AST nodes
     * @return Combined conditions string with all transformations applied
     */
    public String getCombinedConditions(PostgresCodeBuilder builder) {
        if (conditionNodes.isEmpty()) {
            return "";
        }

        List<String> transformedConditions = new ArrayList<>();
        for (PlSqlParser.Relational_expressionContext node : conditionNodes) {
            // Visit the node to transform it (removes (+) and converts Oracle functions)
            String transformed = builder.visit(node);
            if (transformed != null && !transformed.trim().isEmpty()) {
                transformedConditions.add(transformed);
            }
        }

        return String.join(" AND ", transformedConditions);
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
        return String.format("OuterJoinCondition{%s %s JOIN %s ON [%d conditions]}",
            tableKey1, joinType, tableKey2, conditionNodes.size());
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
