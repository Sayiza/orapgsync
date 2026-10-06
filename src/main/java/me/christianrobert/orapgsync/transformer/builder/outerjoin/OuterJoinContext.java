package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.antlr.v4.runtime.ParserRuleContext;

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
 * <p><b>ARCHITECTURE: AST Node Storage</b>
 * <p>This context stores AST nodes rather than pre-transformed strings. This allows
 * conditions to be transformed during the transformation phase, ensuring all Oracle
 * functions (INSTR, TRUNC, etc.) are properly converted to PostgreSQL equivalents.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li><b>Created</b> in VisitQueryBlock before visiting FROM/WHERE</li>
 *   <li><b>Populated</b> during analysis phase (OuterJoinAnalyzer scans FROM and WHERE clauses)</li>
 *   <li><b>Used</b> during transformation phase (VisitFromClause generates ANSI JOIN syntax,
 *       VisitWhereClause filters and transforms WHERE conditions)</li>
 * </ol>
 *
 * <p><b>COORDINATION WITH OTHER TRANSFORMATIONS:</b>
 * <ul>
 *   <li><b>AFFECTS:</b> FROM clause (converts to ANSI JOIN), WHERE clause (filters (+) conditions)</li>
 *   <li><b>ROWNUM:</b> No interference - both filter WHERE clause independently via VisitLogicalExpression</li>
 *   <li><b>CONNECT BY (future):</b> No interference - CONNECT BY rewrites entire query first</li>
 *   <li><b>ORDER:</b> Phase 2 (after CONNECT BY rewrite, before ROWNUM LIMIT)</li>
 * </ul>
 *
 * <p>This context is query-local and created fresh for each query/subquery.
 */
public class OuterJoinContext {

    // Table metadata: key → TableInfo
    private final Map<String, TableInfo> tables;

    // Outer join conditions discovered in WHERE clause
    private final List<OuterJoinCondition> outerJoins;

    // WHERE conditions that contain (+) and should be removed (stored as raw text for comparison)
    private final Set<String> conditionsToRemove;

    // Regular WHERE conditions to preserve (non-outer-join)
    // Store AST nodes to enable transformation during visit phase
    private final List<ParserRuleContext> regularWhereConditionNodes;

    public OuterJoinContext() {
        this.tables = new LinkedHashMap<>();  // Preserve insertion order
        this.outerJoins = new ArrayList<>();
        this.conditionsToRemove = new HashSet<>();
        this.regularWhereConditionNodes = new ArrayList<>();
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
     * <p>If a join between the same two tables already exists, the condition node
     * is added to the existing join. Otherwise, a new join is created.
     *
     * <p>The condition node will be transformed during the transformation phase,
     * ensuring all Oracle functions are converted to PostgreSQL equivalents.
     *
     * @param tableKey1 First table key
     * @param tableKey2 Second table key
     * @param joinType Join type (LEFT or RIGHT)
     * @param conditionNode AST node for join condition (without (+))
     */
    public void registerOuterJoin(String tableKey1, String tableKey2,
                                   OuterJoinCondition.JoinType joinType,
                                   PlSqlParser.Relational_expressionContext conditionNode) {
        // Find existing join between these tables
        OuterJoinCondition existingJoin = findOuterJoin(tableKey1, tableKey2);

        if (existingJoin != null) {
            // Add condition to existing join
            existingJoin.addConditionNode(conditionNode);
        } else {
            // Create new join
            OuterJoinCondition newJoin = new OuterJoinCondition(tableKey1, tableKey2, joinType);
            newJoin.addConditionNode(conditionNode);
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
     * Adds a regular WHERE condition AST node (non-outer-join).
     *
     * <p>The node will be transformed during the transformation phase,
     * ensuring all Oracle functions are converted to PostgreSQL equivalents.
     *
     * @param conditionNode AST node for regular WHERE condition
     */
    public void addRegularWhereConditionNode(ParserRuleContext conditionNode) {
        if (conditionNode != null) {
            regularWhereConditionNodes.add(conditionNode);
        }
    }

    /**
     * Returns all regular WHERE condition nodes.
     *
     * @return List of AST nodes representing regular WHERE conditions
     */
    public List<ParserRuleContext> getRegularWhereConditionNodes() {
        return new ArrayList<>(regularWhereConditionNodes);
    }

    /**
     * Transforms and combines all regular WHERE conditions with AND.
     *
     * <p>This method visits each stored AST node using the provided builder,
     * ensuring all Oracle-specific functions are transformed to PostgreSQL equivalents.
     *
     * <p>Example:
     * <pre>
     * Oracle: WHERE INSTR(e.email, '@') > 0 AND TRUNC(e.hire_date) = TRUNC(SYSDATE)
     * PostgreSQL: WHERE POSITION('@' IN e.email) > 0 AND DATE_TRUNC('day', e.hire_date)::DATE = DATE_TRUNC('day', CURRENT_TIMESTAMP)::DATE
     * </pre>
     *
     * @param builder PostgresCodeBuilder for transforming AST nodes
     * @return Combined WHERE conditions string with all transformations applied, or null if no conditions
     */
    public String buildWhereClause(PostgresCodeBuilder builder) {
        if (regularWhereConditionNodes.isEmpty()) {
            return null;
        }

        List<String> transformedConditions = new ArrayList<>();
        for (ParserRuleContext node : regularWhereConditionNodes) {
            // Visit the node to transform it (converts Oracle functions to PostgreSQL)
            String transformed = builder.visit(node);
            if (transformed != null && !transformed.trim().isEmpty()) {
                transformedConditions.add(transformed);
            }
        }

        return transformedConditions.isEmpty() ? null : String.join(" AND ", transformedConditions);
    }

    // ========== Debug and Utility ==========

    @Override
    public String toString() {
        return String.format("OuterJoinContext{tables=%s, outerJoins=%s, regularConditions=%d}",
            tables.keySet(), outerJoins.size(), regularWhereConditionNodes.size());
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
        sb.append("  Regular WHERE Condition Nodes:\n");
        for (ParserRuleContext node : regularWhereConditionNodes) {
            sb.append("    ").append(node.getClass().getSimpleName()).append(": ")
              .append(node.getText()).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}
