package me.christianrobert.orapgsync.transformer.type.helpers;

import me.christianrobert.orapgsync.transformer.type.TypeInfo;

/**
 * Static helper for resolving Oracle pseudo-column types.
 *
 * <p>Pseudo-columns are special identifiers that Oracle treats as built-in values.
 * They look like column references but have fixed types and don't exist in table metadata.</p>
 *
 * <p>Common pseudo-columns handled:</p>
 * <ul>
 *   <li>SYSDATE, CURRENT_DATE → DATE</li>
 *   <li>SYSTIMESTAMP, CURRENT_TIMESTAMP → TIMESTAMP</li>
 *   <li>ROWNUM, LEVEL, UID → NUMERIC</li>
 *   <li>USER, ROWID, SESSIONTIMEZONE → TEXT</li>
 * </ul>
 *
 * <p>Pattern: Static helper following PostgresCodeBuilder architecture.</p>
 */
public final class ResolvePseudoColumn {

    private ResolvePseudoColumn() {
        // Static utility class - prevent instantiation
    }

    /**
     * Resolves a pseudo-column identifier to its type.
     *
     * @param identifier Identifier name (case-insensitive)
     * @return TypeInfo for pseudo-column, or UNKNOWN if not a pseudo-column
     */
    public static TypeInfo resolve(String identifier) {
        if (identifier == null) {
            return TypeInfo.UNKNOWN;
        }

        String upperIdentifier = identifier.toUpperCase();

        switch (upperIdentifier) {
            // Date/time pseudo-columns
            case "SYSDATE":
            case "CURRENT_DATE":
                return TypeInfo.DATE;

            case "SYSTIMESTAMP":
            case "CURRENT_TIMESTAMP":
            case "LOCALTIMESTAMP":
                return TypeInfo.TIMESTAMP;

            // Numeric pseudo-columns
            case "ROWNUM":
            case "LEVEL":
            case "UID":
                return TypeInfo.NUMERIC;

            // String pseudo-columns
            case "USER":
            case "ROWID":
            case "SESSIONTIMEZONE":
            case "DBTIMEZONE":
                return TypeInfo.TEXT;

            default:
                // Not a recognized pseudo-column
                return TypeInfo.UNKNOWN;
        }
    }
}
