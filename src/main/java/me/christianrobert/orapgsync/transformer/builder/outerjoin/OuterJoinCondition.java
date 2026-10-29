package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ON clause of one outer-joined FROM item.
 *
 * <p>Oracle marks the <em>optional</em> side of an outer join with (+). Every WHERE conjunct whose
 * (+) columns belong to the same item is part of that item's join condition — including conjuncts
 * that compare it with a constant ({@code b.status(+) = 'A'}), which Oracle applies while joining,
 * not afterwards. The item becomes {@code LEFT JOIN item ON c1 AND c2 ...}, emitted once all the
 * items its conditions reference (its {@code dependencies}) are already in the join tree.
 *
 * <p>There is deliberately no RIGHT JOIN: the optional side is always the one being joined.
 *
 * <p>Example:
 * <pre>
 * WHERE a.k = b.k(+) AND c.j = b.j(+) AND b.status(+) = 'A'
 *
 *   optional item: b, dependencies: {a, c}
 *   → ... LEFT JOIN b ON a.k = b.k AND c.j = b.j AND b.status = 'A'
 * </pre>
 */
public class OuterJoinCondition {

    private final int optionalItem;
    private final Set<Integer> dependencies = new TreeSet<>();
    private final List<ParserRuleContext> conditionNodes = new ArrayList<>();

    public OuterJoinCondition(int optionalItem) {
        this.optionalItem = optionalItem;
    }

    void addCondition(ParserRuleContext conditionNode, Set<Integer> conditionDependencies) {
        conditionNodes.add(conditionNode);
        dependencies.addAll(conditionDependencies);
    }

    /** FROM position of the item marked with (+). */
    public int getOptionalItem() {
        return optionalItem;
    }

    /** FROM positions of the items the join condition references, the optional item excluded. */
    public Set<Integer> getDependencies() {
        return dependencies;
    }

    public List<ParserRuleContext> getConditionNodes() {
        return new ArrayList<>(conditionNodes);
    }

    /**
     * Transforms the conditions and combines them with AND. The (+) markers were claimed during
     * analysis, so the visitor drops them.
     */
    public String getCombinedConditions(PostgresCodeBuilder builder) {
        List<String> transformed = new ArrayList<>();
        for (ParserRuleContext node : conditionNodes) {
            String condition = builder.visit(node);
            if (condition != null && !condition.isBlank()) {
                transformed.add(condition);
            }
        }
        return String.join(" AND ", transformed);
    }

    @Override
    public String toString() {
        return "OuterJoinCondition{item=" + optionalItem + ", dependencies=" + dependencies
            + ", conditions=" + conditionNodes.size() + "}";
    }
}
