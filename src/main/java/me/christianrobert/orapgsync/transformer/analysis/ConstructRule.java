package me.christianrobert.orapgsync.transformer.analysis;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.function.Predicate;

/**
 * One entry of the {@link ConstructCatalog}: an Oracle construct that is worth reporting
 * when it appears in a view or PL/SQL unit.
 *
 * <p>A rule is keyed by the simple name of the ANTLR context class it applies to
 * (for example {@code Pivot_clauseContext}). Some Oracle constructs are not a grammar rule of
 * their own but an optional token inside one (ORDER SIBLINGS BY, BULK COLLECT INTO); those use
 * {@link #applies} to narrow the match.</p>
 *
 * @param id               stable identifier used for grouping and filtering (e.g. {@code PIVOT})
 * @param displayName      human readable name shown in the report
 * @param contextClassName simple name of the ANTLR context class this rule matches
 * @param applies          additional condition on the matched context; matches everything by default
 * @param supportOverride  explicit support status, or null to derive it from the presence of a
 *                         visit method in PostgresCodeBuilder
 * @param note             short explanation shown next to the construct in the report
 */
public record ConstructRule(
        String id,
        String displayName,
        String contextClassName,
        Predicate<ParserRuleContext> applies,
        ConstructSupport supportOverride,
        String note) {

    public static ConstructRule of(String id, String displayName, String contextClassName, String note) {
        return new ConstructRule(id, displayName, contextClassName, ctx -> true, null, note);
    }

    public static ConstructRule of(String id, String displayName, String contextClassName,
                                   Predicate<ParserRuleContext> applies, String note) {
        return new ConstructRule(id, displayName, contextClassName, applies, null, note);
    }

    public static ConstructRule ignored(String id, String displayName, String contextClassName,
                                        Predicate<ParserRuleContext> applies, String note) {
        return new ConstructRule(id, displayName, contextClassName, applies, ConstructSupport.IGNORED, note);
    }

    public boolean matches(ParserRuleContext ctx) {
        return contextClassName.equals(ctx.getClass().getSimpleName()) && applies.test(ctx);
    }

    /**
     * Grammar rule name this construct belongs to, derived from the context class name
     * ({@code Pivot_clauseContext} → {@code Pivot_clause}).
     */
    public String ruleName() {
        return contextClassName.endsWith("Context")
                ? contextClassName.substring(0, contextClassName.length() - "Context".length())
                : contextClassName;
    }
}
