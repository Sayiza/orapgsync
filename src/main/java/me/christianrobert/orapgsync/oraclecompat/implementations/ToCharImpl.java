package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for Oracle's single-argument {@code TO_CHAR(expr)}.
 *
 * <p><strong>Why these exist:</strong> PostgreSQL has no one-argument {@code to_char} at all —
 * every built-in overload takes a format string ({@code to_char(numeric, text)},
 * {@code to_char(timestamp, text)}, ...). Emitting Oracle's bare {@code TO_CHAR(x)} verbatim
 * therefore fails at {@code CREATE VIEW} with <em>"function to_char(numeric) does not exist"</em>.
 * The two-argument form needs none of this and still maps to the PostgreSQL built-in directly.
 *
 * <p><strong>Why not {@code ::text}:</strong> Oracle's default conversion is not PostgreSQL's.
 * A cast compiles for every argument type and is wrong in the small — silently. See the
 * per-overload notes below.
 *
 * <p><strong>Why overloads rather than deciding the type in the transformer:</strong> the
 * argument's type is frequently unknown at transformation time (a bare column arrives through the
 * grammar's {@code table_element} branch, where the type evaluator is not consulted at all), and
 * the NLS format is install-time configuration the visitor layer deliberately cannot see.
 * PostgreSQL resolves the overload at execution planning time, when the type is a fact.
 *
 * <p><strong>Deliberately not provided:</strong> {@code double precision}/{@code real} (Oracle
 * renders BINARY_DOUBLE/BINARY_FLOAT in a scientific form this class does not reproduce) and
 * {@code interval}. Neither is implicitly coercible to {@code numeric} in PostgreSQL, so such a
 * call fails loudly with "function oracle_compat.to_char(...) does not exist" rather than
 * returning a wrong string.
 *
 * @see NlsFormatConverter
 */
public class ToCharImpl {

    /**
     * {@code TO_CHAR(number)} — Oracle's default number format.
     *
     * <p>Oracle converts to "a VARCHAR2 value exactly long enough to hold its significant digits",
     * which differs from PostgreSQL's {@code numeric::text} in two ways, both reproduced here:
     *
     * <ul>
     *   <li><strong>No trailing zeroes.</strong> Oracle NUMBER does not store scale, so
     *       {@code TO_CHAR(1.50)} is {@code '1.5'}. PostgreSQL {@code numeric} does store scale and
     *       renders {@code '1.50'}. {@code trim_scale()} (PostgreSQL 13+) removes it.</li>
     *   <li><strong>No leading zero below 1.</strong> {@code TO_CHAR(0.5)} is {@code '.5'} in
     *       Oracle, {@code '0.5'} in PostgreSQL. The {@code regexp_replace} strips it, preserving a
     *       leading minus sign ({@code -0.5} → {@code '-.5'}).</li>
     * </ul>
     *
     * <p><strong>Known deviation:</strong> Oracle switches to scientific notation for values whose
     * plain rendering would be excessively long; PostgreSQL {@code numeric::text} never does.
     * Values at that magnitude render differently. Not reproduced because the exact Oracle
     * threshold was not verified against a live database.
     *
     * <p>IMMUTABLE: {@code trim_scale}, the {@code numeric}→{@code text} output function and
     * {@code regexp_replace} are all immutable, so this stays usable in index expressions.
     */
    public static String getToCharNumeric() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.to_char(p_value numeric)
            RETURNS text
            LANGUAGE sql
            IMMUTABLE
            AS $$
                SELECT regexp_replace(trim_scale(p_value)::text, '^(-?)0\\.', '\\1.')
            $$;

            COMMENT ON FUNCTION oracle_compat.to_char(numeric) IS
            'Oracle TO_CHAR(number) with no format model: no trailing zeroes, no leading zero below 1 (0.5 -> .5). Does not reproduce Oracle''s switch to scientific notation for very long values.';
            """;
    }

    /**
     * {@code TO_CHAR(char)} — identity.
     *
     * <p>Oracle's character overload converts NCHAR/NVARCHAR2 to the database character set, which
     * has no PostgreSQL counterpart (one server encoding, no national character set).
     *
     * <p>This overload also carries the <strong>untyped-literal</strong> case. PostgreSQL resolves
     * an {@code unknown} argument by preferring the string category's preferred type, which is
     * {@code text} — so {@code oracle_compat.to_char('x')} and {@code oracle_compat.to_char(NULL)}
     * resolve here uniquely instead of failing as ambiguous across the overload set.
     */
    public static String getToCharText() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.to_char(p_value text)
            RETURNS text
            LANGUAGE sql
            IMMUTABLE
            AS $$
                SELECT p_value
            $$;

            COMMENT ON FUNCTION oracle_compat.to_char(text) IS
            'Oracle TO_CHAR(char) with no format model - identity. Also resolves untyped literals and NULL, since text is PostgreSQL''s preferred type in the string category.';
            """;
    }

    /**
     * {@code TO_CHAR(date)} for PostgreSQL {@code date} — formats with the configured
     * NLS_DATE_FORMAT.
     *
     * @param pgDateFormat PostgreSQL format string, already converted from Oracle's
     *                     NLS_DATE_FORMAT by {@link NlsFormatConverter#convertDateFormat}
     */
    public static String getToCharDate(String pgDateFormat) {
        return temporalOverload("date", "p_value", pgDateFormat,
                "Oracle TO_CHAR(date) with no format model - uses the configured NLS_DATE_FORMAT.");
    }

    /**
     * {@code TO_CHAR(date)} for PostgreSQL {@code timestamp} — formats with the configured
     * NLS_DATE_FORMAT.
     *
     * <p><strong>Deviation, by necessity:</strong> Oracle picks NLS_DATE_FORMAT for a DATE and
     * NLS_TIMESTAMP_FORMAT for a TIMESTAMP. Both Oracle types migrate to PostgreSQL
     * {@code timestamp} ({@code TypeConverter}), so the distinction is erased before this function
     * is reached and one format has to be chosen. NLS_DATE_FORMAT wins because Oracle DATE columns
     * dominate real schemas. A genuine Oracle TIMESTAMP column formatted bare will therefore render
     * without its fractional seconds — write an explicit two-argument {@code TO_CHAR} in the Oracle
     * source if that matters.
     *
     * @param pgDateFormat PostgreSQL format string, already converted from Oracle's
     *                     NLS_DATE_FORMAT by {@link NlsFormatConverter#convertDateFormat}
     */
    public static String getToCharTimestamp(String pgDateFormat) {
        return temporalOverload("timestamp", "p_value", pgDateFormat,
                "Oracle TO_CHAR(date) with no format model - uses the configured NLS_DATE_FORMAT. "
                + "Oracle DATE and TIMESTAMP both migrate to timestamp, so NLS_TIMESTAMP_FORMAT is not applied here.");
    }

    /**
     * {@code TO_CHAR(date)} for PostgreSQL {@code timestamptz} — formats with the configured
     * NLS_DATE_FORMAT.
     *
     * <p>Needed because {@code SYSDATE} transforms to {@code CURRENT_TIMESTAMP}, which is
     * {@code timestamptz} — {@code TO_CHAR(SYSDATE)} is one of the most common bare forms there is,
     * and {@code timestamptz} is only assignment-coercible to {@code timestamp}, so it would not
     * reach the overload above.
     *
     * <p><strong>Lossy in the same way {@code FROM_TZ} is:</strong> the rendering depends on the
     * session {@code TimeZone}, which is why this overload is STABLE rather than IMMUTABLE. Oracle
     * would use NLS_TIMESTAMP_TZ_FORMAT for a real TIMESTAMP WITH TIME ZONE column and include the
     * zone; this does not.
     *
     * @param pgDateFormat PostgreSQL format string, already converted from Oracle's
     *                     NLS_DATE_FORMAT by {@link NlsFormatConverter#convertDateFormat}
     */
    public static String getToCharTimestampTz(String pgDateFormat) {
        return temporalOverload("timestamptz", "p_value", pgDateFormat,
                "Oracle TO_CHAR(date) with no format model - uses the configured NLS_DATE_FORMAT. "
                + "Renders in the session TimeZone and omits the zone itself.");
    }

    /**
     * Builds one temporal overload.
     *
     * <p>STABLE, not IMMUTABLE: PostgreSQL's own {@code to_char(timestamp, text)} is STABLE because
     * month and day names depend on {@code lc_time}, and the {@code timestamptz} variant
     * additionally depends on the session {@code TimeZone}. A wrapper cannot be more immutable than
     * what it calls.
     *
     * <p>{@code UPPER()} is applied exactly when the format contains name elements — PostgreSQL
     * renders {@code Mon}/{@code Month}/{@code Day}/{@code Dy} in mixed case where Oracle produces
     * uppercase. Same rule as {@link HtpImpl}, via {@link NlsFormatConverter#needsUpperCase}.
     */
    private static String temporalOverload(String pgType, String paramName, String pgDateFormat, String comment) {
        String toCharExpr = NlsFormatConverter.needsUpperCase(pgDateFormat)
                ? "UPPER(to_char(" + paramName + ", '" + pgDateFormat + "'))"
                : "to_char(" + paramName + ", '" + pgDateFormat + "')";

        return """
            CREATE OR REPLACE FUNCTION oracle_compat.to_char(%s %s)
            RETURNS text
            LANGUAGE sql
            STABLE
            AS $$
                SELECT %s
            $$;

            COMMENT ON FUNCTION oracle_compat.to_char(%s) IS
            '%s';
            """.formatted(paramName, pgType, toCharExpr, pgType, comment.replace("'", "''"));
    }
}
