package me.christianrobert.orapgsync.transformer.util;

import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

/**
 * Oracle type name → PostgreSQL type name, for everything the transformer emits.
 *
 * <h2>Why this exists</h2>
 * A type written in PL/SQL can be three different things, and only metadata can tell them apart:
 *
 * <ol>
 *   <li>a <b>package type</b> — {@code pkg.rec_t}, which becomes {@code jsonb} or a flattened name;</li>
 *   <li>a <b>migrated object type</b> — {@code address_type}, which
 *       {@link me.christianrobert.orapgsync.objectdatatype.job.PostgresObjectTypeCreationJob} created
 *       as the composite type {@code hr.address_type}, and which must therefore be emitted
 *       <b>schema-qualified</b>;</li>
 *   <li>a <b>built-in</b> — {@code NUMBER}, {@code VARCHAR2}, handled by {@link TypeConverter}.</li>
 * </ol>
 *
 * <p>{@link TypeConverter} can only answer (3). It maps built-ins, has no metadata, and returns
 * anything it does not recognise <i>unchanged</i> — so an object type reaches PostgreSQL as a bare
 * {@code address_type} and resolves only if {@code search_path} happens to cover the schema. During
 * migration it does not.
 *
 * <h2>The failure this was written for</h2>
 * Until 2026-08-27 exactly one of the transformer's ten type-emission sites consulted metadata:
 * {@code VisitTypeElementsParameter}, fixed for type-method parameters in Phase 2 of the type-method
 * plan. The other nine — local variables, ordinary parameters, ordinary return types, cursor
 * parameters, RECORD fields, {@code %TYPE} and {@code %ROWTYPE} resolution — went straight to
 * {@code TypeConverter}. On the 2026-08-27 full run that produced <b>184 type-method failures</b>
 * with {@code type "X" does not exist}, dominated by a method naming its <i>own</i> type, plus the
 * same error across the standalone and package function job, which shares these visitors.
 *
 * <p>This is the "two routes to one construct" shape recorded throughout CLAUDE.md — the
 * {@code table_element} blind spot, {@code OVERRIDING}'s two grammar routes, the duplicated routine
 * body block. The fix is the same one that settled identifier handling: <b>one authority, called by
 * every route</b>, so the routes cannot answer differently. This class is to types what
 * {@link IdentifierHelper} is to identifiers.
 *
 * <h2>Bare names inside a package</h2>
 * A bare name written inside a package is looked up in that package first — spec and body
 * declarations alike — because that is the scope Oracle searches first. A {@code SUBTYPE} found
 * there is followed to its base, and a base this class would resolve on its own (another package's
 * type, a {@code %TYPE} anchor) is resolved by running the chain again on it.
 *
 * <h2>What it deliberately does not do</h2>
 * Otherwise, resolution of an <i>unqualified</i> name goes through
 * {@link TransformationContext#qualifyTypeName}, which searches synonyms, then the current schema,
 * then {@code PUBLIC}, then {@code SYS}. It does <b>not</b> search the other migrated schemas, so a
 * type owned by a different schema and referenced bare still falls through to the built-in path and
 * still emits unqualified. That is a separate defect with its own decision to make — widening the
 * search risks a name matching in an unintended schema — and it accounts for a small remainder of
 * the failures above.
 *
 * <p>Inline types ({@code RECORD}, {@code TABLE OF}, {@code %ROWTYPE} resolved to a structure) are
 * <b>not</b> handled here. They become {@code jsonb} and carry an initializer, which is a decision
 * with side effects at the declaration site; callers resolve those first and only reach this class
 * once they have ruled one out.
 */
public final class TypeResolver {

    /** Bounds alias chains crossing packages; Oracle forbids cycles, the database may hold one. */
    private static final int MAX_ALIAS_DEPTH = 8;

    private TypeResolver() {
    }

    /**
     * Resolves an Oracle type name to the PostgreSQL type to emit.
     *
     * @param oracleType the type exactly as written in the PL/SQL source
     * @param context    transformation context, for the metadata indices
     * @return the PostgreSQL type name; never null unless {@code oracleType} is
     */
    public static String toPostgres(String oracleType, TransformationContext context) {
        if (oracleType == null) {
            return null;
        }
        String resolved = resolve(oracleType, context, true);
        return resolved != null ? resolved : TypeConverter.toPostgre(oracleType);
    }

    /**
     * As {@link #toPostgres}, but returns {@code null} when neither metadata nor
     * {@link TypeConverter} can place the type, instead of emitting the bare Oracle name.
     *
     * <p>{@code toPostgres} has to answer something: the visitor layer is mid-expression and has
     * nowhere to put a failure. A caller that <i>can</i> refuse — a DDL job building one signature
     * at a time — should, because the bare name it would otherwise emit is indistinguishable from
     * the bug this class exists to fix. It reaches PostgreSQL as {@code schema "uicomponent" does
     * not exist}, which names the symptom and not the type that could not be placed.
     *
     * @param oracleType the type exactly as written
     * @param context    transformation context, for the metadata indices
     * @return the PostgreSQL type name, or null if nothing could place it
     */
    public static String toPostgresOrNull(String oracleType, TransformationContext context) {
        if (oracleType == null) {
            return null;
        }
        return resolve(oracleType, context, false);
    }

    /**
     * The one resolution chain both entry points run.
     *
     * @param acceptGuessedPackageType whether to accept {@code resolvePackageQualifiedType}'s
     *        "unknown package, assume jsonb" fallback. True for the visitor layer, which is
     *        mid-expression and must emit something; false for a caller that can refuse.
     */
    private static String resolve(String oracleType, TransformationContext context,
                                  boolean acceptGuessedPackageType) {
        return resolve(oracleType, context, acceptGuessedPackageType, 0);
    }

    /**
     * @param aliasDepth 0 for the type as written; the depth of the subtype chain otherwise. The
     *        enclosing package is searched only at depth 0: a subtype's base is resolved in the
     *        declaring package's scope, whose own declarations the alias chase has already covered,
     *        and searching the <i>current</i> package for it could only find a wrong match.
     */
    private static String resolve(String oracleType, TransformationContext context,
                                  boolean acceptGuessedPackageType, int aliasDepth) {
        if (context == null) {
            return TypeConverter.toPostgreOrNull(oracleType);
        }

        // An anchored type keeps its anchor: PostgreSQL understands `tab.col%TYPE`, so the useful
        // thing to do is qualify the table rather than flatten the reference to whatever the
        // column happens to hold today. Unqualified, it reaches PostgreSQL as
        // `relation "semester_def" does not exist` — the table is real, it is simply not on the
        // search_path during migration.
        String anchored = resolveAnchoredType(oracleType, context);
        if (anchored != null) {
            return anchored;
        }

        // A bare name declared by the enclosing package — its spec or its body. Searched before
        // object types and built-ins, as Oracle does. Nothing here is a guess: a miss returns null
        // and the chain carries on.
        if (aliasDepth == 0) {
            TransformationContext.PackageTypeResolution enclosing =
                    context.resolveEnclosingPackageType(oracleType);
            if (enclosing != null) {
                if (enclosing.aliasOf() == null) {
                    return enclosing.postgresType();
                }
                return resolveAlias(enclosing.aliasOf(), context, aliasDepth);
            }
        }

        // A package-qualified type is resolved by the package machinery, which knows whether it
        // flattens to a name or collapses to jsonb. Its "unknown package, assume jsonb" fallback
        // is accepted only where there is no alternative: a guessed jsonb in a *signature* is
        // worse than no signature, because the other side of the CREATE OR REPLACE may guess
        // differently and the disagreement then surfaces as "cannot change return type" — a
        // silent overwrite — rather than as a missing type.
        TransformationContext.PackageTypeResolution packageType =
                context.resolvePackageQualifiedType(oracleType);
        if (packageType != null) {
            if (packageType.aliasOf() != null) {
                String base = resolveAlias(packageType.aliasOf(), context, aliasDepth);
                // Before aliases were followed this answered the jsonb guess; the visitor layer
                // keeps it rather than start emitting the bare subtype name.
                return base != null || !acceptGuessedPackageType ? base : "jsonb";
            }
            if (packageType.fromMetadata() || acceptGuessedPackageType) {
                return packageType.postgresType();
            }
            return null;
        }

        // A migrated object type must name the composite type the DDL migration created, which is
        // schema-qualified. Only a positive metadata match rewrites: qualifyTypeName hands back the
        // bare name when it cannot place the type, and emitting that would be indistinguishable
        // from the bug this class exists to fix.
        String qualified = context.qualifyTypeName(oracleType);
        if (context.isObjectType(qualified)) {
            return qualified;
        }

        return TypeConverter.toPostgreOrNull(oracleType);
    }

    /**
     * Resolves a subtype's base type. Never accepts a guess: a base nothing can place leaves the
     * subtype unplaced, which a caller that can refuse then refuses by the subtype's name.
     */
    private static String resolveAlias(String baseType, TransformationContext context, int aliasDepth) {
        return aliasDepth < MAX_ALIAS_DEPTH
                ? resolve(baseType, context, false, aliasDepth + 1)
                : null;
    }

    /**
     * Qualifies the relation in a {@code rel.col%TYPE} reference, leaving the anchor intact.
     *
     * <p>The relation may be a table or a view: Oracle resolves an anchor against either, and a
     * view's columns are typed in PostgreSQL from the moment its stub exists (step 20). Tables
     * alone left every anchor on a view unplaceable — refused in a stub signature, and passed
     * through bare by the visitor layer to fail as {@code relation "x" does not exist}.
     *
     * <p>Only {@code %TYPE} is handled. {@code %ROWTYPE} names a composite whose PostgreSQL
     * spelling depends on where it is being emitted — a declaration site turns it into jsonb plus
     * an initializer, which is a decision this class does not make — so it is deliberately left
     * unresolved rather than approximated.
     *
     * @return the qualified anchored type, or null if this is not a {@code %TYPE} reference or the
     *         relation cannot be placed
     */
    private static String resolveAnchoredType(String oracleType, TransformationContext context) {
        String trimmed = oracleType.trim();
        if (!trimmed.toUpperCase().endsWith("%TYPE")) {
            return null;
        }

        String anchor = trimmed.substring(0, trimmed.length() - "%TYPE".length()).trim().toLowerCase();
        String[] parts = anchor.split("\\.");

        // Three parts are already schema-qualified; two need the schema working out. One part is
        // `somevar%TYPE`, an anchor on another declaration rather than on a column, which only the
        // declaring scope can resolve — not this class.
        if (parts.length == 3) {
            String qualifiedTable = parts[0] + "." + parts[1];
            return context.getRelationColumnType(qualifiedTable, parts[2]) != null
                    ? qualifiedTable + "." + parts[2] + "%TYPE"
                    : null;
        }
        if (parts.length != 2) {
            return null;
        }

        String table = parts[0];
        String column = parts[1];

        // Synonyms first, matching the order every other lookup here uses, then the current schema.
        String synonymTarget = context.resolveSynonym(table);
        if (synonymTarget != null) {
            String qualified = synonymTarget.toLowerCase();
            if (qualified.contains(".") && context.getRelationColumnType(qualified, column) != null) {
                return qualified + "." + column + "%TYPE";
            }
        }

        String currentSchemaTable = context.getCurrentSchema().toLowerCase() + "." + table;
        if (context.getRelationColumnType(currentSchemaTable, column) != null) {
            return currentSchemaTable + "." + column + "%TYPE";
        }

        // Only positive metadata qualifies. Emitting the bare anchor is what produced
        // `relation "X" does not exist`, and guessing a schema would be worse: it would resolve
        // against whichever table happened to share the name.
        return null;
    }
}
