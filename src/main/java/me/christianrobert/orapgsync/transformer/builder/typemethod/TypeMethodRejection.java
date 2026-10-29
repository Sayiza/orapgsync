package me.christianrobert.orapgsync.transformer.builder.typemethod;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * The rejection catalogue from section 3.2 of the type-method plan, in one place.
 *
 * <p>These are <strong>architectural</strong> limits, not unfinished work. Each names a construct
 * that has no sound PostgreSQL translation, so approximating it would be the silent semantic
 * corruption CLAUDE.md forbids. Every one throws a {@link TransformationException} naming the
 * construct and why it cannot be transformed, so the failure lands in the job's error list with
 * the Oracle source attached rather than quietly producing a function that means something else.
 *
 * <p>Two of them cannot be reached by letting the parser fail, which is why they are explicit
 * checks here (plan section 3.1.1, pinned by {@code TypeBodyElementsGrammarTest}):
 * <ul>
 *   <li>A Java {@code call_spec} body <b>parses clean</b>. Nothing about the parse tree signals
 *       that the implementation is not PL/SQL.</li>
 *   <li>{@code OVERRIDING} is accepted on <b>two</b> grammar alternatives —
 *       {@code modifier_clause} inside {@code subprog_decl_in_type}, and
 *       {@code overriding_subprogram_spec} — and which one wins depends on the return form and
 *       whether there is a body. Guarding only the latter would let the ordinary form through the
 *       non-overriding path and emit a function that ignores the inheritance it exists to
 *       override.</li>
 * </ul>
 */
public final class TypeMethodRejection {

    private TypeMethodRejection() {
    }

    /** Java or C implementation: there is no PL/SQL to translate. */
    public static TransformationException callSpecBody(String methodName) {
        return new TransformationException(
                "Type method '" + methodName + "' is implemented as a call spec (LANGUAGE JAVA / C). "
                        + "There is no PL/SQL body to transform, so no PostgreSQL function can be "
                        + "generated from it. Reimplement the method in PostgreSQL by hand.",
                null, "type method: call_spec body");
    }

    /** PostgreSQL composite types do not inherit; any emulation would be a heuristic. */
    public static TransformationException overriding(String methodName) {
        return new TransformationException(
                "Type method '" + methodName + "' is OVERRIDING. PostgreSQL composite types do not "
                        + "support inheritance, so an overriding method has no target to override "
                        + "and emitting it as an ordinary function would silently discard the "
                        + "dispatch semantics it exists for.",
                null, "type method: inheritance (OVERRIDING)");
    }

    /** Oracle object references (pointers) have no PostgreSQL equivalent. */
    public static TransformationException refTypeSpec(String methodName, String where) {
        return new TransformationException(
                "Type method '" + methodName + "' uses REF (" + where + "). Oracle object "
                        + "references have no PostgreSQL equivalent -- a composite type is a value, "
                        + "not a referenceable object -- so there is nothing to map REF onto.",
                null, "type method: REF type_spec");
    }

    /**
     * ODCI extensibility interfaces target {@code CREATE AGGREGATE}, a different kind of object.
     */
    public static TransformationException odciInterface(String methodName) {
        return new TransformationException(
                "Type method '" + methodName + "' implements an Oracle ODCI extensibility interface. "
                        + "Its PostgreSQL equivalent is a CREATE AGGREGATE with sfunc/finalfunc, "
                        + "which is a different database object than a function and cannot be "
                        + "produced by transforming the method body.",
                null, "type method: ODCI interface");
    }

    /**
     * {@code SELF IN OUT} plus a return value cannot be expressed: in PostgreSQL the return type of
     * a function with INOUT parameters is dictated by those parameters.
     */
    public static TransformationException mutatingMemberFunction(String methodName) {
        return new TransformationException(
                "Type method '" + methodName + "' is a mutating member function (SELF IN OUT and a "
                        + "return value). PostgreSQL derives a function's return type from its INOUT "
                        + "parameters, so it cannot express 'modifies SELF and independently returns "
                        + "something else'. See type-method plan section 8, Phase 6.",
                null, "type method: mutating member function");
    }

    /**
     * Element assignment into a collection-typed attribute or variable.
     *
     * <p>Oracle collection types (VARRAY, nested table) have no PostgreSQL representation — the
     * object type migration creates composite types, and a collection is not one — so
     * {@code mSlots(i) := x} has nothing to assign into.
     *
     * <p><b>Why this is a rejection rather than a jsonb_set.</b> The collection-element branch was
     * written for <i>inline</i> types declared inside packages and routines, which genuinely are
     * jsonb. An attribute is never one: it always names a schema-level Oracle type. Emitting
     * {@code jsonb_set} against it produces a function PL/pgSQL accepts at CREATE time and fails on
     * at <i>call</i> time — the silent corruption Phase 3 removed from the sibling
     * field-assignment branch. A named error is the honest outcome until collection types have a
     * target representation.
     *
     * <p>The Oracle type is in the message on purpose: it is what the next full run's log needs in
     * order to say <i>which</i> collection types would have to be modelled, and how many methods
     * each one blocks.
     */
    public static TransformationException collectionElementAssignment(String name,
                                                                      String oracleType,
                                                                      String what) {
        return new TransformationException(
                "Cannot transform element assignment '" + name + "(...) := ...': " + what + " '"
                        + name + "' is declared as Oracle type '" + oracleType + "', which is a "
                        + "schema-level type, not an inline collection. Element assignment is "
                        + "transformed to jsonb_set, which is only valid for inline collection types "
                        + "(TYPE ... IS TABLE OF, declared inside a package or routine); Oracle "
                        + "collection types have no PostgreSQL representation to assign into.",
                null, "type method: collection element assignment");
    }

    /**
     * A grammar alternative with no branch. Reaching this is a bug in the dispatcher, not a gap in
     * Oracle coverage — {@code type_body_elements} is a closed set of four.
     */
    public static TransformationException unhandledAlternative(PlSqlParser.Type_body_elementsContext ctx) {
        return new TransformationException(
                "Unhandled type_body_elements alternative: " + ctx.getClass().getSimpleName()
                        + ". The grammar rule has exactly four alternatives and the dispatcher must "
                        + "be total -- this is a missing branch, not an unsupported Oracle construct.",
                ctx.getText(), "type method: dispatch");
    }
}
