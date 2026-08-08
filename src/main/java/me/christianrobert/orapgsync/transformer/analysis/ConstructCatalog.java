package me.christianrobert.orapgsync.transformer.analysis;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.antlr.v4.runtime.ParserRuleContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalog of Oracle constructs the pre-flight compatibility report looks for.
 *
 * <p>The catalog only says <em>what</em> to look for. Whether the transformer handles a construct
 * is derived at class-load time from the visit methods {@link PostgresCodeBuilder} actually
 * declares, so the report cannot go stale when a visitor is added — the only hand-maintained
 * status values are the {@link ConstructSupport#IGNORED} overrides, which describe constructs that
 * have a visitor but are dropped inside it.</p>
 */
public final class ConstructCatalog {

    private static final List<ConstructRule> RULES = List.of(
            // ---- SQL / view constructs ----
            ConstructRule.of("PIVOT", "PIVOT", "Pivot_clauseContext",
                    "PostgreSQL has no PIVOT; needs manual CASE aggregation or crosstab()."),
            ConstructRule.of("UNPIVOT", "UNPIVOT", "Unpivot_clauseContext",
                    "PostgreSQL has no UNPIVOT; needs a LATERAL VALUES rewrite."),
            ConstructRule.of("MODEL", "MODEL clause", "Model_clauseContext",
                    "No PostgreSQL equivalent; spreadsheet-style calculation must be rewritten."),
            ConstructRule.of("CURSOR_EXPRESSION", "CURSOR() expression", "Cursor_expressionContext",
                    "Nested cursor in the select list; PostgreSQL needs a refcursor or array aggregate."),
            ConstructRule.of("FLASHBACK_QUERY", "Flashback query (AS OF)", "Flashback_query_clauseContext",
                    "Oracle flashback has no PostgreSQL equivalent."),
            ConstructRule.of("SAMPLE", "SAMPLE clause", "Sample_clauseContext",
                    "Maps to TABLESAMPLE with different semantics."),
            ConstructRule.of("GROUPING_SETS", "GROUPING SETS", "Grouping_sets_clauseContext",
                    "PostgreSQL supports GROUPING SETS; listed to explain failures in the group by clause."),
            ConstructRule.of("ROLLUP_CUBE", "ROLLUP / CUBE", "Rollup_cube_clauseContext",
                    "PostgreSQL supports ROLLUP/CUBE; listed to explain failures in the group by clause."),
            ConstructRule.of("XMLTABLE", "XMLTABLE", "XmltableContext",
                    "Oracle XML functions need an XPath rewrite for PostgreSQL."),
            ConstructRule.of("JSON_TABLE", "JSON_TABLE", "Json_table_clauseContext",
                    "Needs a json_to_recordset / jsonb_path_query rewrite."),
            ConstructRule.ignored("ORDER_SIBLINGS_BY", "ORDER SIBLINGS BY", "Order_by_clauseContext",
                    ctx -> ((PlSqlParser.Order_by_clauseContext) ctx).SIBLINGS() != null,
                    "Sibling ordering of a hierarchical query is dropped; row order changes silently."),

            // ---- PL/SQL constructs ----
            ConstructRule.of("FORALL", "FORALL statement", "Forall_statementContext",
                    "Bulk DML; needs a plain loop or set-based statement in PL/pgSQL."),
            ConstructRule.of("BULK_COLLECT", "BULK COLLECT INTO", "Into_clauseContext",
                    ctx -> ((PlSqlParser.Into_clauseContext) ctx).BULK() != null,
                    "Bulk fetch into a collection; needs an array or cursor loop in PL/pgSQL."),
            ConstructRule.of("EXECUTE_IMMEDIATE", "EXECUTE IMMEDIATE", "Execute_immediateContext",
                    "Dynamic SQL; the statement text itself is not transformed."),
            ConstructRule.ignored("AUTONOMOUS_TRANSACTION", "PRAGMA AUTONOMOUS_TRANSACTION",
                    "Pragma_declarationContext",
                    ctx -> ((PlSqlParser.Pragma_declarationContext) ctx).AUTONOMOUS_TRANSACTION() != null,
                    "Dropped without a trace; commit semantics of the generated function differ from Oracle.")
    );

    private static final Map<String, List<ConstructRule>> RULES_BY_CONTEXT = indexByContext(RULES);

    /** Visit method names declared by PostgresCodeBuilder, e.g. {@code visitQuery_block}. */
    private static final Set<String> VISITOR_METHODS = collectVisitorMethods();

    private ConstructCatalog() {
    }

    public static List<ConstructRule> rules() {
        return RULES;
    }

    /**
     * Returns the rules that could match the given context class, or an empty list.
     * Callers still have to check {@link ConstructRule#matches(ParserRuleContext)} for the
     * additional condition.
     */
    public static List<ConstructRule> rulesFor(Class<?> contextClass) {
        return RULES_BY_CONTEXT.getOrDefault(contextClass.getSimpleName(), List.of());
    }

    /**
     * Support status of a construct: the explicit override if the catalog defines one,
     * otherwise derived from whether PostgresCodeBuilder declares a visit method for the rule.
     */
    public static ConstructSupport supportOf(ConstructRule rule) {
        if (rule.supportOverride() != null) {
            return rule.supportOverride();
        }
        return VISITOR_METHODS.contains("visit" + rule.ruleName())
                ? ConstructSupport.HANDLED
                : ConstructSupport.NO_VISITOR;
    }

    private static Map<String, List<ConstructRule>> indexByContext(List<ConstructRule> rules) {
        Map<String, List<ConstructRule>> index = new HashMap<>();
        for (ConstructRule rule : rules) {
            index.computeIfAbsent(rule.contextClassName(), k -> new ArrayList<>()).add(rule);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Set<String> collectVisitorMethods() {
        Set<String> methods = new HashSet<>();
        for (Method method : PostgresCodeBuilder.class.getDeclaredMethods()) {
            if (method.getName().startsWith("visit")) {
                methods.add(method.getName());
            }
        }
        return Collections.unmodifiableSet(methods);
    }
}
