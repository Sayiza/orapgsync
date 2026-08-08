package me.christianrobert.orapgsync.transformer.analysis;

/**
 * How the transformer currently treats an Oracle construct.
 *
 * <p>The value is derived where possible rather than hand-maintained: {@link ConstructCatalog}
 * asks {@link me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder} by reflection
 * whether a visit method for the grammar rule exists. Constructs that have a visitor but are
 * known to be dropped inside it carry an explicit override in the catalog entry.</p>
 */
public enum ConstructSupport {

    /**
     * No visit method exists for the grammar rule. The default visitor behaviour
     * (visitChildren) applies, which either drops the construct or produces output
     * that is not valid PostgreSQL.
     */
    NO_VISITOR,

    /**
     * A visitor exists but deliberately ignores this construct, so it disappears
     * from the generated code without an error.
     */
    IGNORED,

    /**
     * A visitor exists and handles the construct. Listed in the catalog because the
     * construct is still worth counting (for example to explain a transformation failure
     * that happens further down the tree).
     */
    HANDLED
}
