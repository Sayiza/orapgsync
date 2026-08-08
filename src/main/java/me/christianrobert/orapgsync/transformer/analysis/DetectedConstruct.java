package me.christianrobert.orapgsync.transformer.analysis;

/**
 * One occurrence of a catalogued Oracle construct in a parsed source.
 *
 * @param id          construct id from the {@link ConstructCatalog} (e.g. {@code PIVOT})
 * @param displayName human readable construct name
 * @param support     how the transformer treats this construct
 * @param line        1-based line in the Oracle source where the construct starts
 * @param snippet     short excerpt of the Oracle source at that position
 * @param note        short explanation of what the construct means for the migration
 */
public record DetectedConstruct(
        String id,
        String displayName,
        ConstructSupport support,
        int line,
        String snippet,
        String note) {
}
