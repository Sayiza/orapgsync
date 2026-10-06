package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

/**
 * Oracle pseudo-columns that are written like a bare identifier but are not columns.
 *
 * <h2>Why this is a separate class</h2>
 * Oracle's grammar hands a bare name to the transformer through <b>two</b> different rules, and
 * which one wins depends on where the name sits:
 *
 * <ul>
 *   <li>{@code general_element} — expressions generally. {@link VisitGeneralElement} resolves it.</li>
 *   <li>{@code table_element} — the argument of {@code TO_CHAR}, {@code TO_DATE} and
 *       {@code COALESCE}, where the grammar names that alternative first and it therefore wins for
 *       any dotted-or-undotted identifier. {@code VisitTableElement} resolves it.</li>
 * </ul>
 *
 * <p>Only the first branch recognised {@code SYSDATE}, so {@code TO_CHAR(SYSDATE)} and
 * {@code TO_CHAR(SYSDATE, 'YYYY')} emitted the pseudo-column as a plain column reference and failed
 * with {@code column "sysdate" does not exist}. {@code TO_CHAR(SYSDATE + 1)} worked, because
 * arithmetic pushes the name back into the {@code expression} branch — which is exactly the kind of
 * split that keeps a bug alive.
 *
 * <p>Holding the rule once means the two branches cannot disagree about it. This is the same reason
 * {@code PostgresIdentifierNormalizer} is the single authority for identifier naming.
 *
 * <h2>Scope</h2>
 * Deliberately only the two names {@link VisitGeneralElement} already handled — this class was
 * extracted to remove a divergence, not to add coverage. {@code ROWNUM} has its own transformation
 * package, and {@code USER}/{@code LEVEL} are left to pass through as PostgreSQL keywords (see
 * {@link IdentifierHelper}).
 */
public final class OraclePseudoColumns {

    private OraclePseudoColumns() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Resolves a bare identifier to its PostgreSQL equivalent, if it names a pseudo-column.
     *
     * <p>Delimiters are stripped first: {@code "SYSDATE"} is the same pseudo-column as
     * {@code SYSDATE}, and a quoted name must not reach the comparison with its quote characters
     * still attached.
     *
     * <p>Both {@code SYSDATE} (date and time) and {@code SYSTIMESTAMP} (timestamp with time zone)
     * map to {@code CURRENT_TIMESTAMP}. Note that makes them {@code timestamptz} in PostgreSQL,
     * which is why {@code oracle_compat.to_char} needs a {@code timestamptz} overload.
     *
     * @param rawIdentifierText identifier text exactly as the parser produced it, quoted or not
     * @return the PostgreSQL replacement, or {@code null} if this is not a pseudo-column and should
     *         be resolved as an ordinary identifier
     */
    public static String resolve(String rawIdentifierText) {
        if (rawIdentifierText == null) {
            return null;
        }

        String upper = IdentifierHelper.unquote(rawIdentifierText).toUpperCase();

        // SYSDATE → CURRENT_TIMESTAMP (Oracle: current date and time)
        // SYSTIMESTAMP → CURRENT_TIMESTAMP (Oracle: current timestamp with time zone)
        if ("SYSDATE".equals(upper) || "SYSTIMESTAMP".equals(upper)) {
            return "CURRENT_TIMESTAMP";
        }

        return null;
    }
}
