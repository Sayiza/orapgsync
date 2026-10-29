package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Analyzes one query block's FROM and WHERE clauses for Oracle (+) outer joins.
 *
 * <p><b>Oracle's rule</b>, which this reproduces: a (+) marks the <em>optional</em> side. Every
 * top-level WHERE conjunct containing (+) belongs to the join condition of the one FROM item its
 * (+) columns qualify, whatever the conjunct's shape — a comparison, {@code BETWEEN}, a function
 * call, a comparison with a constant. All other conjuncts stay in WHERE and are applied after the
 * joins. An item whose (+) conjuncts reference no other item is not outer-joined at all; Oracle
 * applies them as plain filters, and so does this.
 *
 * <p><b>Conjuncts</b> are found by splitting the WHERE condition on AND and unwrapping redundant
 * parentheses: {@code ( a.k = b.k(+) )} is the same conjunct as {@code a.k = b.k(+)}.
 *
 * <p><b>What is refused</b>, by name, rather than approximated — each is also an Oracle error, so
 * valid source does not contain it, but a silent translation would hide a misreading of the tree:
 * (+) in an OR (ORA-01719), (+) together with a subquery (ORA-01799), (+) columns of two items in
 * one conjunct (ORA-01468), two items outer-joined to each other (ORA-01416), (+) with ANSI joins
 * in the same FROM (ORA-25156). Also refused: an unqualified (+) column, which would need column
 * metadata to place.
 *
 * <p>The (+) markers of every conjunct handled here are claimed in the context; any other (+)
 * makes {@code visitOuter_join_sign} throw.
 */
public class OuterJoinAnalyzer {

    private static final int AMBIGUOUS = -1;

    /**
     * @param fromClauseCtx FROM clause (null for none)
     * @param whereClauseCtx WHERE clause (null for none)
     */
    public static OuterJoinContext analyze(
            PlSqlParser.From_clauseContext fromClauseCtx,
            PlSqlParser.Where_clauseContext whereClauseCtx) {

        OuterJoinContext context = new OuterJoinContext(fromClauseCtx, whereClauseCtx);
        if (fromClauseCtx != null) {
            claimRedundantInLeftJoins(fromClauseCtx, context);
        }
        if (fromClauseCtx == null || whereClauseCtx == null || whereClauseCtx.condition() == null) {
            return context;
        }
        PlSqlParser.ConditionContext condition = whereClauseCtx.condition();
        if (collect(condition, PlSqlParser.Outer_join_signContext.class).isEmpty()) {
            return context;
        }

        PlSqlParser.Logical_expressionContext top =
            condition.expression() == null ? null : condition.expression().logical_expression();
        if (top == null) {
            throw new TransformationException("Oracle outer join (+): unsupported WHERE condition form: "
                + condition.getText());
        }

        Map<String, Integer> keyToItem = analyzeFromClause(fromClauseCtx, context);

        // Pass 1: classify each conjunct
        List<PlSqlParser.Logical_expressionContext> conjuncts = new ArrayList<>();
        flatten(top, conjuncts);
        List<Integer> optionalItemOf = new ArrayList<>();
        List<Set<Integer>> dependenciesOf = new ArrayList<>();
        Map<Integer, Set<Integer>> itemDependencies = new HashMap<>();
        for (PlSqlParser.Logical_expressionContext conjunct : conjuncts) {
            List<PlSqlParser.Outer_join_signContext> signs = collect(conjunct, PlSqlParser.Outer_join_signContext.class);
            if (signs.isEmpty()) {
                optionalItemOf.add(null);
                dependenciesOf.add(Set.of());
                continue;
            }
            int item = optionalItem(conjunct, signs, keyToItem);
            Set<Integer> dependencies = referencedItems(conjunct, keyToItem);
            dependencies.remove(item);
            optionalItemOf.add(item);
            dependenciesOf.add(dependencies);
            itemDependencies.computeIfAbsent(item, k -> new HashSet<>()).addAll(dependencies);
            signs.forEach(context::claim);
        }

        // Pass 2: distribute, keeping the source order within WHERE and within each ON
        for (int i = 0; i < conjuncts.size(); i++) {
            Integer item = optionalItemOf.get(i);
            if (item == null || itemDependencies.get(item).isEmpty()) {
                context.addRegularWhereConditionNode(conjuncts.get(i));
            } else {
                context.addOuterJoinCondition(item, conjuncts.get(i), dependenciesOf.get(i));
            }
        }
        return context;
    }

    // ========== FROM clause ==========

    /**
     * Claims a (+) inside the ON of an ANSI {@code LEFT JOIN} when it marks a column of the
     * joined table: the table is optional already, so Oracle gives the marker no effect (verified
     * against Oracle 19c). Any other (+) in an ON stays unclaimed and is refused — in an inner
     * {@code JOIN} it would turn the join into an outer join.
     */
    private static void claimRedundantInLeftJoins(PlSqlParser.From_clauseContext ctx, OuterJoinContext context) {
        if (ctx.table_ref_list() == null) {
            return;
        }
        for (PlSqlParser.Table_refContext ref : ctx.table_ref_list().table_ref()) {
            for (PlSqlParser.Join_clauseContext join : ref.join_clause()) {
                if (join.outer_join_type() == null || join.outer_join_type().LEFT() == null) {
                    continue;
                }
                Map<String, Integer> joinedTable = new HashMap<>();
                keysOf(join.table_ref_aux()).forEach(key -> joinedTable.put(key, 0));
                for (PlSqlParser.Join_on_partContext on : join.join_on_part()) {
                    for (PlSqlParser.Outer_join_signContext sign : collect(on, PlSqlParser.Outer_join_signContext.class)) {
                        if (sign.getParent() instanceof PlSqlParser.AtomContext atom && atom.general_element() != null) {
                            List<PlSqlParser.General_element_partContext> parts = parts(atom.general_element());
                            if (parts.size() >= 2 && match(parts, parts.size() - 1, joinedTable) != null) {
                                context.claim(sign);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Registers each comma-separated FROM item and returns canonical key → FROM position. */
    private static Map<String, Integer> analyzeFromClause(PlSqlParser.From_clauseContext ctx, OuterJoinContext context) {
        PlSqlParser.Table_ref_listContext list = ctx.table_ref_list();
        if (list == null) {
            throw new TransformationException("FROM clause missing table_ref_list");
        }
        Map<String, Integer> keyToItem = new HashMap<>();
        List<PlSqlParser.Table_refContext> refs = list.table_ref();
        for (int i = 0; i < refs.size(); i++) {
            PlSqlParser.Table_refContext ref = refs.get(i);
            if (!ref.join_clause().isEmpty()) {
                throw new TransformationException("Oracle outer join (+) cannot be combined with ANSI joins in the same "
                    + "FROM clause (Oracle rejects it too, ORA-25156): " + ref.getText());
            }
            List<String> keys = keysOf(ref.table_ref_aux());
            context.addTable(new TableInfo(i, keys));
            for (String key : keys) {
                keyToItem.merge(key, i, (a, b) -> AMBIGUOUS);
            }
        }
        return keyToItem;
    }

    private static List<String> keysOf(PlSqlParser.Table_ref_auxContext aux) {
        List<String> keys = new ArrayList<>();
        if (aux == null) {
            return keys;
        }
        if (aux.table_alias() != null) {
            keys.add(IdentifierHelper.canonical(aux.table_alias()));
            return keys;
        }
        PlSqlParser.Dml_table_expression_clauseContext dml = null;
        if (aux.table_ref_aux_internal() instanceof PlSqlParser.Table_ref_aux_internal_oneContext one) {
            dml = one.dml_table_expression_clause();
        } else if (aux.table_ref_aux_internal() instanceof PlSqlParser.Table_ref_aux_internal_threContext three) {
            dml = three.dml_table_expression_clause();
        }
        PlSqlParser.Tableview_nameContext name = dml == null ? null : dml.tableview_name();
        if (name == null || name.identifier() == null) {
            return keys;
        }
        String first = IdentifierHelper.canonical(name.identifier());
        if (name.id_expression() != null) {
            String table = IdentifierHelper.canonical(name.id_expression());
            keys.add(table);
            keys.add(first + "." + table);
        } else {
            keys.add(first);
        }
        return keys;
    }

    // ========== WHERE clause ==========

    /** Splits on AND, unwrapping parentheses that enclose a conjunct or an AND group. */
    private static void flatten(PlSqlParser.Logical_expressionContext expr,
                                List<PlSqlParser.Logical_expressionContext> out) {
        if (expr.AND() != null) {
            expr.logical_expression().forEach(e -> flatten(e, out));
            return;
        }
        PlSqlParser.Logical_expressionContext inner = parenthesized(expr);
        // An OR group keeps its parentheses: unwrapped, it would bind looser than the AND it is joined into
        if (inner != null && inner.OR() == null) {
            flatten(inner, out);
        } else {
            out.add(expr);
        }
    }

    /**
     * If {@code expr} is nothing but {@code ( condition )}, returns the inner condition.
     * Descends only through single-child wrappers of the condition chain, so any operator, NOT,
     * IN, BETWEEN or function call on the way means "not just parentheses".
     */
    private static PlSqlParser.Logical_expressionContext parenthesized(PlSqlParser.Logical_expressionContext expr) {
        ParseTree node = expr;
        while (!(node instanceof PlSqlParser.AtomContext)) {
            boolean wrapper = node instanceof PlSqlParser.Logical_expressionContext
                || node instanceof PlSqlParser.Unary_logical_expressionContext
                || node instanceof PlSqlParser.Multiset_expressionContext
                || node instanceof PlSqlParser.Relational_expressionContext
                || node instanceof PlSqlParser.Compound_expressionContext
                || node instanceof PlSqlParser.ConcatenationContext
                || node instanceof PlSqlParser.Model_expressionContext
                || node instanceof PlSqlParser.Unary_expressionContext;
            if (!wrapper || node.getChildCount() != 1) {
                return null;
            }
            node = node.getChild(0);
        }
        PlSqlParser.AtomContext atom = (PlSqlParser.AtomContext) node;
        if (atom.expressions_() == null || atom.expressions_().expression().size() != 1) {
            return null;
        }
        return atom.expressions_().expression(0).logical_expression();
    }

    /** The FROM position of the item whose columns carry the conjunct's (+) markers. */
    private static int optionalItem(PlSqlParser.Logical_expressionContext conjunct,
                                    List<PlSqlParser.Outer_join_signContext> signs,
                                    Map<String, Integer> keyToItem) {
        if (!collect(conjunct, PlSqlParser.SubqueryContext.class).isEmpty()) {
            throw new TransformationException("Oracle outer join (+) in a condition with a subquery is not supported "
                + "(Oracle rejects a column outer-joined to a subquery, ORA-01799): " + conjunct.getText());
        }
        Set<Integer> items = new LinkedHashSet<>();
        for (PlSqlParser.Outer_join_signContext sign : signs) {
            for (ParseTree p = sign.getParent(); p != conjunct.getParent(); p = p.getParent()) {
                if (p instanceof PlSqlParser.Logical_expressionContext logical && logical.OR() != null) {
                    throw new TransformationException("Oracle outer join (+) in an operand of OR is not supported "
                        + "(Oracle rejects it too, ORA-01719): " + conjunct.getText());
                }
            }
            if (!(sign.getParent() instanceof PlSqlParser.AtomContext atom) || atom.general_element() == null) {
                throw new TransformationException("Oracle outer join (+) on something other than a column: "
                    + sign.getParent().getText());
            }
            List<PlSqlParser.General_element_partContext> parts = parts(atom.general_element());
            if (parts.stream().anyMatch(part -> !part.function_argument().isEmpty())) {
                throw new TransformationException("Oracle outer join (+) on a function call is not supported: "
                    + atom.getText());
            }
            if (parts.size() < 2) {
                throw new TransformationException("Oracle outer join (+) on an unqualified column is not supported, "
                    + "the outer-joined table cannot be determined without column metadata: " + atom.getText());
            }
            Integer item = match(parts, parts.size() - 1, keyToItem);
            if (item == null || item == AMBIGUOUS) {
                throw new TransformationException("Oracle outer join (+): the qualifier of " + atom.getText()
                    + (item == null ? " names no item of this FROM clause" : " names more than one item of this FROM clause"));
            }
            items.add(item);
        }
        if (items.size() > 1) {
            throw new TransformationException("Oracle outer join (+) on columns of two different tables in one condition "
                + "(Oracle rejects it too, ORA-01468): " + conjunct.getText());
        }
        return items.iterator().next();
    }

    /**
     * FROM positions of every item the conjunct references through a qualified name.
     *
     * <p>Read off the tokens — {@code name . name} — not off parser rules: a column reference is
     * not always a {@code general_element} (the first argument of {@code TO_CHAR} is a
     * {@code table_element}), and a reference missed here would make an outer join look like a
     * filter, i.e. an inner join. A false positive merely orders the join later.
     */
    private static Set<Integer> referencedItems(ParserRuleContext conjunct, Map<String, Integer> keyToItem) {
        List<TerminalNode> tokens = collect(conjunct, TerminalNode.class);
        Set<Integer> items = new HashSet<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!".".equals(tokens.get(i + 1).getText()) || (i > 0 && ".".equals(tokens.get(i - 1).getText()))) {
                continue;
            }
            String first = IdentifierHelper.canonical(tokens.get(i).getText());
            Integer item = null;
            if (i + 4 < tokens.size() && ".".equals(tokens.get(i + 3).getText())) {
                item = keyToItem.get(first + "." + IdentifierHelper.canonical(tokens.get(i + 2).getText()));
            }
            if (item == null) {
                item = keyToItem.get(first);
            }
            if (item != null && item != AMBIGUOUS) {
                items.add(item);
            }
        }
        return items;
    }

    /** Longest prefix of at most {@code maxLength} parts that names a FROM item. */
    private static Integer match(List<PlSqlParser.General_element_partContext> parts, int maxLength,
                                 Map<String, Integer> keyToItem) {
        for (int length = maxLength; length >= 1; length--) {
            String key = parts.subList(0, length).stream()
                .map(part -> IdentifierHelper.canonical(part.id_expression()))
                .collect(Collectors.joining("."));
            Integer item = keyToItem.get(key);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private static List<PlSqlParser.General_element_partContext> parts(PlSqlParser.General_elementContext element) {
        List<PlSqlParser.General_element_partContext> parts = new ArrayList<>();
        if (element.general_element() != null) {
            parts.addAll(parts(element.general_element()));
        }
        parts.addAll(element.general_element_part());
        return parts;
    }

    // ========== Tree search ==========

    /**
     * Collects nodes of {@code type}, never descending into a nested query: its (+) markers and
     * names belong to its own query block, which gets its own analysis. A nested query's root
     * node itself is still collected, so this also detects one.
     */
    private static <T extends ParseTree> List<T> collect(ParseTree tree, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectInto(tree, type, out);
        return out;
    }

    private static <T extends ParseTree> void collectInto(ParseTree tree, Class<T> type, List<T> out) {
        if (type.isInstance(tree)) {
            out.add(type.cast(tree));
        }
        if (tree instanceof PlSqlParser.SubqueryContext || tree instanceof PlSqlParser.Query_blockContext) {
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectInto(tree.getChild(i), type, out);
        }
    }
}
