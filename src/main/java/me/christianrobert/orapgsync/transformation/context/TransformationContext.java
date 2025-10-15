package me.christianrobert.orapgsync.transformation.context;

/**
 * Provides global context for transformation process.
 *
 * In this minimal first implementation, this is just a placeholder.
 * Future phases will add:
 * - Metadata indices (table columns, type methods, package functions)
 * - Synonym resolution
 * - Type conversion
 * - Query-local state (table aliases, etc.)
 */
public class TransformationContext {

    private final String currentSchema;

    public TransformationContext(String currentSchema) {
        this.currentSchema = currentSchema;
    }

    public String getCurrentSchema() {
        return currentSchema;
    }

    /**
     * Placeholder for future synonym resolution.
     * Currently just returns null (no resolution).
     */
    public String resolveSynonym(String name) {
        // TODO: Implement synonym resolution in future phase
        return null;
    }

    /**
     * Placeholder for future type conversion.
     * Currently just returns the Oracle type as-is.
     */
    public String convertType(String oracleType) {
        // TODO: Implement type conversion in future phase
        return oracleType;
    }
}
