package me.christianrobert.orapgsync.transformer.builder.functions;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;

/**
 * Transforms Oracle {@code CAST(expr AS type)} to PostgreSQL.
 *
 * <p>The cast target is deliberately <b>not</b> routed through
 * {@link me.christianrobert.orapgsync.core.tools.TypeConverter}. That class maps a type for
 * <i>declaring</i> a column or variable, where discarding detail is intentional: it collapses
 * {@code NUMBER(10,2)} to bare {@code numeric} and maps the LOB types to {@code oid}. In a cast
 * the discarded detail is the whole point of the expression — {@code CAST(x AS NUMBER(10,2))}
 * is written to round to two decimals, and {@code ::oid} is a cast to an integer type. So this
 * class carries its own target-type table.</p>
 *
 * <p><b>The table is an allowlist and everything outside it throws.</b> A target that cannot be
 * reproduced exactly is rejected rather than approximated, per the project's fail-loudly rule —
 * an approximated cast still reports success and corrupts values silently.</p>
 *
 * <h2>Accepted targets</h2>
 * <table>
 *   <tr><th>Oracle</th><th>PostgreSQL</th><th>Note</th></tr>
 *   <tr><td>{@code DATE}</td><td>{@code DATE_TRUNC('second', (x)::timestamp)}</td>
 *       <td>see below — <b>never</b> PostgreSQL {@code date}</td></tr>
 *   <tr><td>{@code TIMESTAMP[(p)]}</td><td>{@code timestamp[(p)]}</td><td>both round to p</td></tr>
 *   <tr><td>{@code TIMESTAMP[(p)] WITH [LOCAL] TIME ZONE}</td><td>{@code timestamptz[(p)]}</td><td></td></tr>
 *   <tr><td>{@code NUMBER[(p[,s])]}, {@code NUMERIC}, {@code DECIMAL}, {@code DEC}</td>
 *       <td>{@code numeric[(p[,s])]}</td><td>precision preserved</td></tr>
 *   <tr><td>{@code INTEGER}, {@code INT}, {@code SMALLINT}</td><td>{@code numeric(38,0)}</td>
 *       <td>see below</td></tr>
 *   <tr><td>{@code PLS_INTEGER}, {@code BINARY_INTEGER}, {@code SIMPLE_INTEGER}</td>
 *       <td>{@code integer}</td><td>genuinely 32-bit in Oracle</td></tr>
 *   <tr><td>{@code BINARY_FLOAT} / {@code BINARY_DOUBLE}</td><td>{@code real} / {@code double precision}</td>
 *       <td>IEEE 754 on both sides</td></tr>
 *   <tr><td>{@code VARCHAR2(n)}, {@code VARCHAR(n)}, {@code NVARCHAR2(n)}, {@code STRING(n)}</td>
 *       <td>{@code varchar(n)}</td><td>length preserved</td></tr>
 *   <tr><td>{@code CHAR[(n)]}, {@code NCHAR[(n)]}, {@code CHARACTER[(n)]}</td><td>{@code char[(n)]}</td>
 *       <td>blank-padded on both sides</td></tr>
 *   <tr><td>{@code BOOLEAN}</td><td>{@code boolean}</td><td>PL/SQL only</td></tr>
 * </table>
 *
 * <h2>Why {@code AS DATE} is the dangerous one</h2>
 *
 * <p>Oracle's {@code DATE} is a day <i>plus a time to the second</i>; PostgreSQL's {@code date} is
 * a day alone. The rest of the migration already resolves that — {@code TypeConverter} maps Oracle
 * {@code DATE} to {@code timestamp}, so every migrated {@code DATE} column is a {@code timestamp}.
 * Emitting the literal-looking {@code (x)::date} would therefore compile, run, and silently move
 * every value to midnight: a whole-column data change reported as success.</p>
 *
 * <p>Oracle {@code CAST(ts AS DATE)} <b>truncates</b> the fractional seconds, which is usually the
 * reason the cast was written at all, so {@code DATE_TRUNC('second', ...)} is emitted rather than a
 * plain {@code ::timestamp}. {@code ::timestamp(0)} is not a substitute — PostgreSQL <i>rounds</i>
 * there, so the two disagree on any value at or above half a second. {@code date_trunc(text,
 * timestamp)} is IMMUTABLE, so the result stays usable in an expression index.</p>
 *
 * <p>The {@code ::timestamp} inside the truncation pins the direction: on a {@code timestamptz}
 * argument (a TSTZ column, or {@code SYSTIMESTAMP}) it renders in the session {@code TimeZone},
 * which is what Oracle's own conversion does too. Same lossiness already documented for
 * {@code FROM_TZ} in {@link DateFunctionTransformer#transformFromTz}.</p>
 *
 * <h2>Why the ANSI integer aliases become {@code numeric(38,0)}</h2>
 *
 * <p>Oracle's {@code INTEGER}, {@code INT} and {@code SMALLINT} are all aliases for
 * {@code NUMBER(38,0)} — none of them is machine-width. {@code CAST(3000000000 AS SMALLINT)}
 * succeeds in Oracle; {@code ::smallint} and {@code ::integer} would raise <i>out of range</i> on
 * values Oracle accepts. Both sides round half away from zero, so {@code numeric(38,0)} reproduces
 * the Oracle result exactly and still fails loudly past 38 digits. The PL/SQL integer types are
 * different — {@code PLS_INTEGER} really is 32-bit — and map to {@code integer}.</p>
 *
 * <h2>Rejected targets, and why</h2>
 * <ul>
 *   <li>{@code CAST(MULTISET(subquery) AS coll_type)} — collections are deferred work.</li>
 *   <li>{@code XMLCAST}, {@code XMLTYPE} — XML conversion semantics are not the same operation.</li>
 *   <li>{@code CLOB} / {@code NCLOB} / {@code BLOB} / {@code BFILE} — the migration maps LOBs to
 *       {@code oid} (a Large Object reference), which is an integer type and meaningless as a cast
 *       target. {@code text} would be the honest expression type but would not match the {@code oid}
 *       column it is written to, so neither answer is safe.</li>
 *   <li>{@code FLOAT}, {@code REAL}, {@code DOUBLE PRECISION} — Oracle spells these as
 *       binary-precision {@code NUMBER}s carrying up to 38 decimal digits, not IEEE 754. Mapping
 *       them onto PostgreSQL's IEEE types loses digits silently; {@code REAL} keeps about six.
 *       The {@code BINARY_*} types are the IEEE ones and are accepted.</li>
 *   <li>{@code NATURAL}, {@code NATURALN}, {@code POSITIVE}, {@code POSITIVEN}, {@code SIGNTYPE} —
 *       constrained subtypes whose range check PostgreSQL cannot express in a cast; dropping the
 *       check is a silent relaxation.</li>
 *   <li>{@code RAW}, {@code LONG}, {@code LONG RAW}, {@code ROWID}, {@code UROWID},
 *       {@code MLSLABEL}, {@code INTERVAL ... TO ...} — no exact equivalent, and rare enough that a
 *       loud failure is the cheaper way to learn one is needed.</li>
 *   <li>User-defined object types, {@code REF}, {@code %TYPE}, {@code %ROWTYPE}.</li>
 *   <li>{@code DEFAULT ... ON CONVERSION ERROR} — rejected for the same reason
 *       {@code VisitOtherFunction.conversionFunction} rejects it on TO_NUMBER: PostgreSQL has no
 *       error-substitution form, and dropping it turns "substitute" into "raise".</li>
 *   <li>The Oracle 21c {@code CAST(x AS t, 'fmt')} format argument — the format decides the
 *       conversion, so dropping it changes the result.</li>
 *   <li>{@code CHARACTER SET} — a character-set conversion, not a type change.</li>
 * </ul>
 *
 * <h2>Known relaxations</h2>
 * <ul>
 *   <li><b>Length semantics.</b> {@code VARCHAR2(30 BYTE)} counts bytes, {@code varchar(30)} counts
 *       characters. They diverge only on multi-byte data, and BYTE is the Oracle default
 *       ({@code NLS_LENGTH_SEMANTICS}), so rejecting it would reject nearly every string cast.</li>
 *   <li><b>Overflow on a character target.</b> PostgreSQL's explicit cast to {@code varchar(n)}
 *       truncates. Whether Oracle truncates or raises here was not verified against a live
 *       database; preserving the length at least keeps the type right, where dropping to
 *       {@code text} would remove the limit entirely.</li>
 * </ul>
 */
public final class CastTransformer {

    private CastTransformer() {
        // Static utility class
    }

    /**
     * Resolved cast target: the PostgreSQL type text, plus the one target that needs more than a
     * cast to be faithful.
     *
     * @param postgresType     PostgreSQL type as written in the output, e.g. {@code numeric(10,2)}
     * @param truncateToSecond {@code true} only for Oracle {@code DATE}, which additionally has to
     *                         drop the fractional seconds
     */
    public record CastTarget(String postgresType, boolean truncateToSecond) {

        /** The type this cast produces, for the type-inference pass. */
        public TypeInfo toTypeInfo() {
            if (postgresType.startsWith("timestamp")) {
                return TypeInfo.of(TypeInfo.TypeCategory.DATE, postgresType);
            }
            if (postgresType.startsWith("varchar") || postgresType.startsWith("char")) {
                return TypeInfo.of(TypeInfo.TypeCategory.TEXT, postgresType);
            }
            if (postgresType.equals("boolean")) {
                return TypeInfo.BOOLEAN;
            }
            return TypeInfo.of(TypeInfo.TypeCategory.NUMERIC, postgresType);
        }
    }

    /**
     * Transforms the {@code (CAST | XMLCAST)} alternative of {@code other_function}.
     *
     * @throws TransformationException for any target outside the allowlist above
     */
    public static String transformCast(PlSqlParser.Other_functionContext ctx, PostgresCodeBuilder b) {
        if (ctx.XMLCAST() != null) {
            throw new TransformationException(
                "XMLCAST is not supported - XML conversion has no equivalent PostgreSQL cast");
        }
        if (ctx.MULTISET() != null) {
            throw new TransformationException(
                "CAST(MULTISET(...) AS ...) is not supported - Oracle collection types have no "
                + "PostgreSQL equivalent in this migration");
        }
        if (ctx.DEFAULT() != null) {
            throw new TransformationException(
                "CAST with DEFAULT ... ON CONVERSION ERROR is not supported - PostgreSQL has no "
                + "equivalent for conversion error substitution");
        }
        if (ctx.quoted_string() != null && !ctx.quoted_string().isEmpty()) {
            throw new TransformationException(
                "CAST with a format model is not supported - the format determines the conversion, "
                + "so it cannot be dropped");
        }

        java.util.List<PlSqlParser.ConcatenationContext> concatenations = ctx.concatenation();
        if (concatenations == null || concatenations.isEmpty()) {
            throw new TransformationException("CAST is missing its value expression");
        }

        CastTarget target = resolveTarget(ctx.type_spec());
        String value = "( " + b.visit(concatenations.get(0)) + " )";

        if (target.truncateToSecond()) {
            // Oracle DATE keeps the time but drops the fractional seconds; PostgreSQL 'date'
            // would drop the time as well.
            return "DATE_TRUNC( 'second' , " + value + "::" + target.postgresType() + " )";
        }
        return value + "::" + target.postgresType();
    }

    /**
     * Maps an Oracle cast target onto its PostgreSQL type.
     *
     * @throws TransformationException if the target is not on the allowlist
     */
    public static CastTarget resolveTarget(PlSqlParser.Type_specContext typeSpec) {
        if (typeSpec == null) {
            throw new TransformationException("CAST is missing its target type");
        }
        if (typeSpec.datatype() == null) {
            // REF? type_name (%ROWTYPE | %TYPE)? - user-defined object type or an attribute
            // reference. Neither is a PostgreSQL cast target we can construct.
            throw new TransformationException(
                "CAST to a user-defined or referenced type is not supported: " + typeSpec.getText());
        }

        PlSqlParser.DatatypeContext datatype = typeSpec.datatype();
        if (datatype.INTERVAL() != null) {
            throw new TransformationException(
                "CAST to an INTERVAL type is not supported: " + datatype.getText());
        }
        if (datatype.CHARACTER() != null && datatype.SET() != null) {
            throw new TransformationException(
                "CAST with CHARACTER SET is not supported - it is a character-set conversion, "
                + "not a type change: " + datatype.getText());
        }

        PlSqlParser.Native_datatype_elementContext element = datatype.native_datatype_element();
        if (element == null) {
            throw new TransformationException("CAST to unrecognised type: " + datatype.getText());
        }

        boolean withTimeZone = datatype.WITH() != null && datatype.TIME() != null;
        Precision precision = Precision.from(datatype.precision_part(), datatype.getText());

        // --- Date and time ---
        if (element.DATE() != null) {
            precision.rejectIfPresent("DATE");
            return new CastTarget("timestamp", true);
        }
        if (element.TIMESTAMP() != null || element.TIMESTAMP_UNCONSTRAINED() != null) {
            return new CastTarget(
                (withTimeZone ? "timestamptz" : "timestamp") + precision.suffix(), false);
        }
        if (element.TIMESTAMP_TZ_UNCONSTRAINED() != null
            || element.TIMESTAMP_LTZ_UNCONSTRAINED() != null) {
            return new CastTarget("timestamptz" + precision.suffix(), false);
        }

        // --- Numeric ---
        if (element.NUMBER() != null || element.NUMERIC() != null
            || element.DECIMAL() != null || element.DEC() != null) {
            return new CastTarget("numeric" + precision.suffix(), false);
        }
        if (element.INTEGER() != null || element.INT() != null || element.SMALLINT() != null) {
            // Oracle spells all three as NUMBER(38,0); PostgreSQL's int4/int2 would overflow
            // on values Oracle accepts.
            precision.rejectIfPresent("INTEGER");
            return new CastTarget("numeric(38,0)", false);
        }
        if (element.PLS_INTEGER() != null || element.BINARY_INTEGER() != null
            || element.SIMPLE_INTEGER() != null) {
            precision.rejectIfPresent("PLS_INTEGER");
            return new CastTarget("integer", false);
        }
        if (element.BINARY_FLOAT() != null) {
            precision.rejectIfPresent("BINARY_FLOAT");
            return new CastTarget("real", false);
        }
        if (element.BINARY_DOUBLE() != null) {
            precision.rejectIfPresent("BINARY_DOUBLE");
            return new CastTarget("double precision", false);
        }

        // --- Character ---
        if (element.VARCHAR2() != null || element.VARCHAR() != null
            || element.NVARCHAR2() != null || element.STRING() != null) {
            // Oracle requires a length here; without one there is nothing to preserve.
            return new CastTarget(
                precision.isPresent() ? "varchar" + precision.lengthSuffix() : "text", false);
        }
        if (element.CHAR() != null || element.NCHAR() != null || element.CHARACTER() != null) {
            return new CastTarget("char" + precision.lengthSuffix(), false);
        }

        // --- Boolean ---
        if (element.BOOLEAN() != null) {
            precision.rejectIfPresent("BOOLEAN");
            return new CastTarget("boolean", false);
        }

        throw new TransformationException(
            "CAST to " + element.getText() + " is not supported - no PostgreSQL type reproduces "
            + "its Oracle semantics exactly");
    }

    /**
     * The parsed {@code precision_part}, or an absent one.
     *
     * <p>{@code NUMBER(*)} and {@code NUMBER(*,s)} substitute Oracle's default precision of 38 for
     * the asterisk. A symbolic precision ({@code NUMBER(c_scale)}) cannot be resolved at
     * transformation time and is rejected.</p>
     */
    private record Precision(Integer first, Integer second) {

        static final Precision ABSENT = new Precision(null, null);

        static Precision from(PlSqlParser.Precision_partContext ctx, String typeText) {
            if (ctx == null) {
                return ABSENT;
            }
            if (ctx.id_expression() != null && !ctx.id_expression().isEmpty()) {
                throw new TransformationException(
                    "CAST with a symbolic precision is not supported - it cannot be resolved at "
                    + "transformation time: " + typeText);
            }

            java.util.List<PlSqlParser.NumericContext> numerics = ctx.numeric();
            Integer first;
            Integer second;
            if (ctx.ASTERISK() != null) {
                // NUMBER(*) / NUMBER(*,s) - the asterisk took the first slot, so any numeric
                // present is the scale.
                first = 38;
                second = numerics.isEmpty() ? null : parse(numerics.get(0), typeText);
            } else {
                first = numerics.isEmpty() ? null : parse(numerics.get(0), typeText);
                second = numerics.size() > 1 ? parse(numerics.get(1), typeText) : null;
            }
            if (second == null && ctx.numeric_negative() != null) {
                second = -parse(ctx.numeric_negative().numeric(), typeText);
            }
            return new Precision(first, second);
        }

        private static int parse(PlSqlParser.NumericContext ctx, String typeText) {
            // numeric: UNSIGNED_INTEGER '.'? | APPROXIMATE_NUM_LIT - only whole numbers are
            // meaningful as a precision.
            String text = ctx.getText().replace(".", "");
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new TransformationException(
                    "CAST with a non-integer precision is not supported: " + typeText);
            }
        }

        boolean isPresent() {
            return first != null;
        }

        /** {@code (p)}, {@code (p,s)} or the empty string. */
        String suffix() {
            if (first == null) {
                return "";
            }
            return second == null ? "(" + first + ")" : "(" + first + "," + second + ")";
        }

        /** {@code (n)} or the empty string; a scale is meaningless on a character type. */
        String lengthSuffix() {
            if (first == null) {
                return "";
            }
            if (second != null) {
                throw new TransformationException(
                    "CAST to a character type with a scale is not valid Oracle");
            }
            return "(" + first + ")";
        }

        void rejectIfPresent(String typeName) {
            if (first != null) {
                throw new TransformationException(
                    "CAST to " + typeName + " does not take a precision");
            }
        }
    }
}
