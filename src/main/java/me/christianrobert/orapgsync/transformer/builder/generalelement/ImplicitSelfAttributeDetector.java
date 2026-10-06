package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.Map;

/**
 * Resolves a bare attribute reference inside an object type method to {@code self.<attribute>}.
 *
 * <h2>The construct</h2>
 * Oracle gives a member method implicit access to its type's attributes, so a body may write
 * {@code mBindVars := …} or {@code RETURN qty * unit_price;} with no {@code self.} prefix — exactly
 * as a package member may write a package variable bare. PostgreSQL has no implicit receiver: the
 * instance is an ordinary parameter, so the attribute has to be qualified with it.
 *
 * <p>Left alone, PL/pgSQL rejects the function at CREATE time with
 * {@code "<name>" is not a known variable} — about 50 failures on the 2026-08-27 run, concentrated
 * in the types that name their attributes without a prefix.
 *
 * <h2>Why this cannot capture a column</h2>
 * The rewrite needs <b>positive</b> evidence at every step, and Oracle's own resolution order is
 * encoded here in the order the checks run:
 *
 * <ol>
 *   <li><b>Only inside a member method.</b> {@code self} must actually be in scope — in a STATIC
 *       method there is no instance, so a bare attribute name is genuinely unresolvable and is left
 *       to fail loudly rather than be invented.</li>
 *   <li><b>A column of a relation in scope wins.</b> Inside a SQL statement Oracle resolves a name
 *       that is both a column and a PL/SQL name to the column, and so must this.</li>
 *   <li><b>A local variable, parameter or CTE wins.</b> Oracle shadows attributes with locals.</li>
 *   <li><b>The name must be a known attribute of the owning type.</b> Anything the metadata does
 *       not recognise passes through untouched, so a miss leaves the previous behaviour.</li>
 * </ol>
 *
 * <p>The same rule as the parenthesis-less routine detector: only positive metadata rewrites, and
 * columns win. The inverse — "not a local, therefore an attribute" — must never be added.
 *
 * @see ParameterlessFunctionDetector
 */
public final class ImplicitSelfAttributeDetector {

    /** The name SELF is registered and emitted under. */
    private static final String SELF = "self";

    private ImplicitSelfAttributeDetector() {
    }

    /**
     * The attribute a bare identifier resolves to, when it resolves to one.
     *
     * @param attributeName the attribute's name as the metadata holds it
     * @param oracleType    the attribute's declared Oracle type, e.g. {@code VARCHAR2} or
     *                      {@code TEXTLIST3K}. Callers that must decide whether the
     *                      attribute is representable in PostgreSQL need this, and re-deriving it
     *                      would mean re-running the guard chain below.
     */
    public record SelfAttribute(String attributeName, String oracleType) {
    }

    /**
     * @param rawIdentifier the identifier as written in the Oracle source, quotes included
     * @param b             code builder, for scope and metadata
     * @return {@code "self.<attribute>"}, or null when this is not an implicit attribute reference
     */
    public static String detect(String rawIdentifier, PostgresCodeBuilder b) {
        SelfAttribute attribute = resolve(rawIdentifier, b);
        return attribute == null ? null : SELF + "." + IdentifierHelper.emit(rawIdentifier);
    }

    /**
     * As {@link #detect}, but hands back <i>what</i> the name resolved to rather than the emitted
     * text — for callers deciding how to treat the attribute rather than how to print it.
     *
     * <p>Write positions need this. {@code tags(1) := …} cannot simply be qualified: element
     * assignment is transformed to {@code jsonb_set}, which is only correct when the target really
     * is jsonb. An attribute never is — {@code RECORD} and {@code TABLE OF} are declared inside
     * packages and routines, while an attribute always names a schema-level Oracle type — so the
     * caller needs the type to say so in the rejection. See
     * {@code VisitAssignment_statement.tryTransformCollectionElementAssignment}.
     */
    public static SelfAttribute resolve(String rawIdentifier, PostgresCodeBuilder b) {
        TransformationContext context = b == null ? null : b.getContext();
        if (context == null || rawIdentifier == null) {
            return null;
        }

        String typeName = context.getCurrentTypeName();
        if (typeName == null) {
            return null;  // not transforming a type method
        }

        // SELF is registered by TypeMethodEmitter for member methods and constructors, and
        // deliberately not for static methods -- which is exactly the distinction needed here.
        if (!context.isLocalVariable(SELF)) {
            return null;
        }

        String name = IdentifierHelper.canonical(rawIdentifier).toLowerCase();
        if (name.equals(SELF)) {
            return null;
        }

        // A column of a relation in scope always wins, as it does in Oracle.
        if (context.isColumnInScope(name) || context.isCTE(name)) {
            return null;
        }

        // A local or parameter of the same name shadows the attribute.
        if (context.isLocalVariable(name)) {
            return null;
        }

        String qualifiedType = context.getCurrentSchema().toLowerCase() + "."
                + IdentifierHelper.emit(typeName).toLowerCase();

        Map<String, String> attributes = context.getTypeFields(qualifiedType);
        if (attributes == null || !attributes.containsKey(name)) {
            return null;
        }

        return new SelfAttribute(name, attributes.get(name));
    }
}
