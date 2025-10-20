package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Visitor helper for over_clause grammar rule (window functions).
 *
 * <p>Oracle and PostgreSQL have nearly identical window function syntax (pass-through strategy).
 *
 * <p>Grammar rule:
 * <pre>
 * over_clause
 *     : OVER '(' (
 *         query_partition_clause? (order_by_clause windowing_clause?)?
 *         | HIERARCHY th = id_expression OFFSET numeric (ACROSS ANCESTOR AT LEVEL id_expression)?
 *     ) ')'
 *     ;
 *
 * query_partition_clause
 *     : PARTITION BY (('(' (subquery | expressions_)? ')') | expressions_)
 *     ;
 *
 * windowing_clause
 *     : windowing_type (BETWEEN windowing_elements AND windowing_elements | windowing_elements)
 *     ;
 *
 * windowing_type
 *     : ROWS | RANGE
 *     ;
 *
 * windowing_elements
 *     : UNBOUNDED PRECEDING
 *     | CURRENT ROW
 *     | concatenation (PRECEDING | FOLLOWING)
 *     ;
 * </pre>
 *
 * <h3>Oracle vs PostgreSQL:</h3>
 * <pre>
 * Oracle:     OVER (PARTITION BY dept_id ORDER BY salary DESC)
 * PostgreSQL: OVER (PARTITION BY dept_id ORDER BY salary DESC)
 *
 * Oracle:     OVER (ORDER BY salary)
 * PostgreSQL: OVER (ORDER BY salary)
 *
 * Oracle:     OVER ()
 * PostgreSQL: OVER ()
 *
 * Oracle:     OVER (PARTITION BY dept_id ORDER BY salary ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
 * PostgreSQL: OVER (PARTITION BY dept_id ORDER BY salary ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
 * </pre>
 */
public class VisitOverClause {

    public static String v(PlSqlParser.Over_clauseContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Over_clauseContext cannot be null");
        }

        StringBuilder result = new StringBuilder("OVER ( ");

        // Check for HIERARCHY clause (Oracle-specific hierarchical queries in window functions)
        // This is very rare and complex - not supported initially
        if (ctx.HIERARCHY() != null) {
            throw new TransformationException(
                "HIERARCHY clause in window functions not yet supported");
        }

        // Handle PARTITION BY clause (optional)
        PlSqlParser.Query_partition_clauseContext partitionClause = ctx.query_partition_clause();
        if (partitionClause != null) {
            result.append("PARTITION BY ");

            // Grammar: PARTITION BY (('(' (subquery | expressions_)? ')') | expressions_)
            PlSqlParser.Expressions_Context expressions = partitionClause.expressions_();
            if (expressions != null) {
                // Visit the partition expressions
                result.append(VisitExpressions.v(expressions, b));
            } else if (partitionClause.subquery() != null) {
                // Subquery in PARTITION BY (rare but supported)
                result.append(b.visit(partitionClause.subquery()));
            }

            result.append(" ");
        }

        // Handle ORDER BY clause (optional)
        PlSqlParser.Order_by_clauseContext orderByClause = ctx.order_by_clause();
        if (orderByClause != null) {
            result.append(VisitOrderByClause.v(orderByClause, b));
            result.append(" ");
        }

        // Handle windowing clause (optional) - ROWS/RANGE BETWEEN ... AND ...
        PlSqlParser.Windowing_clauseContext windowingClause = ctx.windowing_clause();
        if (windowingClause != null) {
            result.append(visitWindowingClause(windowingClause, b));
            result.append(" ");
        }

        result.append(")");
        return result.toString();
    }

    /**
     * Visits windowing_clause: ROWS/RANGE BETWEEN ... AND ...
     *
     * <p>Oracle and PostgreSQL have identical syntax for windowing clauses.
     */
    private static String visitWindowingClause(PlSqlParser.Windowing_clauseContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // Windowing type: ROWS or RANGE
        PlSqlParser.Windowing_typeContext windowingType = ctx.windowing_type();
        if (windowingType != null) {
            if (windowingType.ROWS() != null) {
                result.append("ROWS ");
            } else if (windowingType.RANGE() != null) {
                result.append("RANGE ");
            }
        }

        // Windowing elements
        java.util.List<PlSqlParser.Windowing_elementsContext> elements = ctx.windowing_elements();
        if (elements == null || elements.isEmpty()) {
            throw new TransformationException("Windowing clause missing windowing elements");
        }

        if (elements.size() == 1) {
            // Simple form: ROWS N PRECEDING, ROWS CURRENT ROW, etc.
            result.append(visitWindowingElements(elements.get(0), b));
        } else if (elements.size() == 2) {
            // BETWEEN form: ROWS BETWEEN ... AND ...
            result.append("BETWEEN ");
            result.append(visitWindowingElements(elements.get(0), b));
            result.append(" AND ");
            result.append(visitWindowingElements(elements.get(1), b));
        } else {
            throw new TransformationException(
                "Windowing clause has unexpected number of elements: " + elements.size());
        }

        return result.toString();
    }

    /**
     * Visits windowing_elements: UNBOUNDED PRECEDING, CURRENT ROW, N PRECEDING, N FOLLOWING
     *
     * <p>Oracle and PostgreSQL have identical syntax for windowing elements.
     */
    private static String visitWindowingElements(PlSqlParser.Windowing_elementsContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        if (ctx.UNBOUNDED() != null && ctx.PRECEDING() != null) {
            result.append("UNBOUNDED PRECEDING");
        } else if (ctx.CURRENT() != null && ctx.ROW() != null) {
            result.append("CURRENT ROW");
        } else if (ctx.concatenation() != null) {
            // N PRECEDING or N FOLLOWING
            result.append(b.visit(ctx.concatenation()));
            result.append(" ");
            if (ctx.PRECEDING() != null) {
                result.append("PRECEDING");
            } else if (ctx.FOLLOWING() != null) {
                result.append("FOLLOWING");
            }
        }

        return result.toString();
    }
}
