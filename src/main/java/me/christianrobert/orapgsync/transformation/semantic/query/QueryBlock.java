package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a SQL query block (the main part of a SELECT statement).
 *
 * <p>Grammar rule: query_block
 * <pre>
 * query_block:
 *     SELECT hint? (DISTINCT | UNIQUE | ALL)?
 *     selected_list
 *     from_clause?
 *     where_clause?
 *     hierarchical_query_clause?
 *     group_by_clause?
 *     model_clause?
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ selected_list (SELECT columns)
 * - ✅ from_clause (FROM table - single table only)
 * - ⏳ DISTINCT/UNIQUE/ALL modifiers (not yet implemented)
 * - ⏳ where_clause (not yet implemented)
 * - ⏳ hierarchical_query_clause (CONNECT BY - not yet implemented)
 * - ⏳ group_by_clause (GROUP BY, HAVING - not yet implemented)
 * - ⏳ model_clause (MODEL - not yet implemented)
 */
public class QueryBlock implements SemanticNode {

    private final SelectedList selectedList;
    private final FromClause fromClause;
    // Future: whereClause, groupByClause, orderByClause, etc.

    public QueryBlock(SelectedList selectedList, FromClause fromClause) {
        if (selectedList == null) {
            throw new IllegalArgumentException("SelectedList cannot be null");
        }
        if (fromClause == null) {
            throw new IllegalArgumentException("FromClause cannot be null");
        }
        this.selectedList = selectedList;
        this.fromClause = fromClause;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        StringBuilder sql = new StringBuilder();

        // SELECT clause
        sql.append("SELECT ");
        sql.append(selectedList.toPostgres(context));

        // FROM clause
        sql.append(" FROM ");
        sql.append(fromClause.toPostgres(context));

        // Future: WHERE, GROUP BY, HAVING, ORDER BY, etc.

        return sql.toString();
    }

    // Getters for testing and introspection
    public SelectedList getSelectedList() {
        return selectedList;
    }

    public FromClause getFromClause() {
        return fromClause;
    }

    @Override
    public String toString() {
        return "QueryBlock{selectedList=" + selectedList + ", fromClause=" + fromClause + "}";
    }
}
