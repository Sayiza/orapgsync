package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of {@link OuterJoinAnalyzer} for one query block.
 *
 * <p>Holds the FROM items, one {@link OuterJoinCondition} per outer-joined item, the WHERE
 * conjuncts that stay in WHERE, and the (+) markers the analysis accounted for. Stores AST nodes,
 * not strings, so conditions are transformed by the normal visitor when they are emitted.
 *
 * <p><b>Claimed markers.</b> {@code PostgresCodeBuilder.visitOuter_join_sign} drops a (+) only if
 * a context on the stack claimed it, and throws otherwise. Every (+) the analysis does not turn
 * into a join — in the SELECT list, under CONNECT BY, in an ANSI {@code ON} — therefore fails
 * loudly instead of silently becoming an inner join.
 *
 * <p>Created fresh for each query block (subqueries get their own) and pushed onto the builder's
 * stack by {@code VisitQueryBlock}.
 */
public class OuterJoinContext {

    private final PlSqlParser.From_clauseContext fromClause;
    private final PlSqlParser.Where_clauseContext whereClause;
    private final List<TableInfo> tables = new ArrayList<>();
    private final Map<Integer, OuterJoinCondition> outerJoins = new LinkedHashMap<>();
    private final List<ParserRuleContext> regularWhereConditionNodes = new ArrayList<>();
    private final Set<PlSqlParser.Outer_join_signContext> claimedSigns =
        Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * @param fromClause the FROM clause this context was built from (null for none)
     * @param whereClause the WHERE clause this context was built from (null for none)
     */
    public OuterJoinContext(PlSqlParser.From_clauseContext fromClause, PlSqlParser.Where_clauseContext whereClause) {
        this.fromClause = fromClause;
        this.whereClause = whereClause;
    }

    /** A context for a query block that is not analyzed: no outer joins, no claimed markers. */
    public static OuterJoinContext none() {
        return new OuterJoinContext(null, null);
    }

    // ========== Analysis-phase population ==========

    void addTable(TableInfo table) {
        tables.add(table);
    }

    void addOuterJoinCondition(int optionalItem, ParserRuleContext conditionNode, Set<Integer> dependencies) {
        outerJoins.computeIfAbsent(optionalItem, OuterJoinCondition::new).addCondition(conditionNode, dependencies);
    }

    void addRegularWhereConditionNode(ParserRuleContext conditionNode) {
        regularWhereConditionNodes.add(conditionNode);
    }

    void claim(PlSqlParser.Outer_join_signContext sign) {
        claimedSigns.add(sign);
    }

    // ========== Transformation-phase queries ==========

    /** True if at least one FROM item is outer-joined, i.e. FROM and WHERE must be rebuilt. */
    public boolean hasOuterJoins() {
        return !outerJoins.isEmpty();
    }

    /**
     * True if this context was built from exactly this FROM clause. A query block transformed
     * without going through {@code VisitQueryBlock}'s analysis — an inline view under CONNECT BY —
     * would otherwise find the <em>enclosing</em> query's context on the stack and build its join
     * tree from another query's conditions.
     */
    public boolean isFor(PlSqlParser.From_clauseContext ctx) {
        return fromClause != null && fromClause == ctx;
    }

    /** True if this context was built from exactly this WHERE clause; see {@link #isFor(PlSqlParser.From_clauseContext)}. */
    public boolean isFor(PlSqlParser.Where_clauseContext ctx) {
        return whereClause != null && whereClause == ctx;
    }

    /** True if the analysis accounted for this (+) marker, so it may be dropped from the output. */
    public boolean isClaimed(PlSqlParser.Outer_join_signContext sign) {
        return claimedSigns.contains(sign);
    }

    public List<TableInfo> getAllTables() {
        return new ArrayList<>(tables);
    }

    /** The outer join of the item at this FROM position, or null if it is not outer-joined. */
    public OuterJoinCondition getOuterJoin(int item) {
        return outerJoins.get(item);
    }

    public List<OuterJoinCondition> getOuterJoins() {
        return new ArrayList<>(outerJoins.values());
    }

    public List<ParserRuleContext> getRegularWhereConditionNodes() {
        return new ArrayList<>(regularWhereConditionNodes);
    }

    /**
     * Transforms the conjuncts that stay in WHERE and combines them with AND.
     *
     * <p>Each node is a {@code logical_expression}, so ROWNUM conjuncts are filtered by
     * {@code VisitLogicalExpression} exactly as on the path without outer joins.
     *
     * @return the combined condition, or null if nothing remains
     */
    public String buildWhereClause(PostgresCodeBuilder builder) {
        List<String> transformed = new ArrayList<>();
        for (ParserRuleContext node : regularWhereConditionNodes) {
            String condition = builder.visit(node);
            if (condition != null && !condition.isBlank()) {
                transformed.add(condition);
            }
        }
        return transformed.isEmpty() ? null : String.join(" AND ", transformed);
    }

    @Override
    public String toString() {
        return "OuterJoinContext{tables=" + tables + ", outerJoins=" + outerJoins.values()
            + ", regularConditions=" + regularWhereConditionNodes.size() + "}";
    }
}
