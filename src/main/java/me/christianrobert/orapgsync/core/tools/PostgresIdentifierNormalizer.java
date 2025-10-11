package me.christianrobert.orapgsync.core.tools;

import java.util.Set;

/**
 * Utility class for normalizing identifiers (table names, column names, etc.) for PostgreSQL.
 *
 * <h2>Normalization Strategy</h2>
 * Oracle identifiers are converted to PostgreSQL-safe identifiers using "smart quoting":
 * <ul>
 *   <li>Convert to lowercase (PostgreSQL convention)</li>
 *   <li>Quote identifiers that are PostgreSQL reserved words</li>
 *   <li>Quote identifiers that contain special characters (#, $, etc.)</li>
 *   <li>Leave normal identifiers unquoted for better readability</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre>
 * Oracle Column Name  →  PostgreSQL Column Name
 * -----------------      -----------------------
 * END                 →  "end"        (reserved word)
 * OFFSET              →  "offset"     (reserved word)
 * CUSTOMER_ID         →  customer_id  (normal identifier)
 * ORDER#              →  "order#"     (special character)
 * COLUMN$NAME         →  "column$name" (special character)
 * </pre>
 *
 * <h2>Benefits</h2>
 * <ul>
 *   <li>Preserves original Oracle column names (semantic consistency)</li>
 *   <li>Minimizes quoting (only when necessary)</li>
 *   <li>Future PL/SQL transformation can apply same rules consistently</li>
 *   <li>More readable SQL for normal columns</li>
 * </ul>
 *
 * @see <a href="https://www.postgresql.org/docs/current/sql-keywords-appendix.html">PostgreSQL Reserved Keywords</a>
 */
public class PostgresIdentifierNormalizer {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private PostgresIdentifierNormalizer() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * PostgreSQL reserved keywords that require quoting when used as identifiers.
     * This is a curated list of the most commonly problematic keywords.
     *
     * Source: https://www.postgresql.org/docs/current/sql-keywords-appendix.html
     *
     * Note: This list focuses on reserved keywords and common SQL keywords that
     * are likely to appear as column names in Oracle databases.
     */
    private static final Set<String> POSTGRES_RESERVED_WORDS = Set.of(
            // Common reserved words that appear as column names
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc",
            "asymmetric", "authorization", "between", "binary", "both", "case",
            "cast", "check", "collate", "collation", "column", "concurrently",
            "constraint", "create", "cross", "current_catalog", "current_date",
            "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "default", "deferrable", "desc", "distinct", "do",
            "else", "end", "except", "false", "fetch", "for", "foreign", "freeze",
            "from", "full", "grant", "group", "having", "ilike", "in", "initially",
            "inner", "intersect", "into", "is", "isnull", "join", "lateral",
            "leading", "left", "like", "limit", "localtime", "localtimestamp",
            "natural", "new", "not", "notnull", "null", "offset", "old", "on",
            "only", "or", "order", "outer", "overlaps", "placing", "primary",
            "references", "returning", "right", "select", "session_user", "similar",
            "some", "symmetric", "table", "tablesample", "then", "to", "trailing",
            "true", "union", "unique", "user", "using", "variadic", "verbose",
            "when", "where", "window", "with",

            // Common data type names that might be used as columns
            "bigint", "bit", "boolean", "char", "character", "coalesce", "dec",
            "decimal", "exists", "extract", "float", "greatest", "grouping",
            "inout", "int", "integer", "interval", "least", "national", "nchar",
            "none", "nullif", "numeric", "out", "overlay", "position", "precision",
            "real", "row", "setof", "smallint", "substring", "time", "timestamp",
            "treat", "trim", "values", "varchar", "xmlattributes", "xmlconcat",
            "xmlelement", "xmlexists", "xmlforest", "xmlnamespaces", "xmlparse",
            "xmlpi", "xmlroot", "xmlserialize", "xmltable",

            // Temporal keywords often used as columns
            "year", "month", "day", "hour", "minute", "second", "zone",

            // Common business domain keywords
            "type", "value", "key", "level", "comment", "database", "index",
            "schema", "view", "role", "sequence", "trigger", "function", "procedure",
            "domain", "range", "partition", "tablespace"
    );

    /**
     * Normalizes an identifier (column name, table name, etc.) for use in PostgreSQL.
     *
     * <p>The normalization process:
     * <ol>
     *   <li>Converts identifier to lowercase (PostgreSQL convention)</li>
     *   <li>Checks if quoting is required (reserved word or special characters)</li>
     *   <li>Returns quoted identifier if needed, otherwise returns lowercase identifier</li>
     * </ol>
     *
     * @param identifier The Oracle identifier (e.g., "END", "CUSTOMER_ID", "ORDER#")
     * @return The normalized PostgreSQL identifier (e.g., "\"end\"", "customer_id", "\"order#\"")
     */
    public static String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return identifier;
        }

        // Convert to lowercase (PostgreSQL convention)
        String lowercase = identifier.toLowerCase();

        // Check if quoting is required
        if (needsQuoting(lowercase)) {
            return "\"" + lowercase + "\"";
        }

        // Return unquoted lowercase identifier
        return lowercase;
    }

    /**
     * Determines if an identifier needs to be quoted in PostgreSQL.
     *
     * <p>An identifier needs quoting if:
     * <ul>
     *   <li>It is a PostgreSQL reserved keyword</li>
     *   <li>It contains special characters (anything other than letters, digits, underscore)</li>
     *   <li>It starts with a digit</li>
     * </ul>
     *
     * @param lowercaseIdentifier The identifier in lowercase
     * @return {@code true} if the identifier must be quoted, {@code false} otherwise
     */
    private static boolean needsQuoting(String lowercaseIdentifier) {
        // Check if it's a reserved word
        if (POSTGRES_RESERVED_WORDS.contains(lowercaseIdentifier)) {
            return true;
        }

        // Check if identifier matches PostgreSQL unquoted identifier rules:
        // - Must start with letter (a-z) or underscore
        // - Can contain letters (a-z), digits (0-9), underscores, or dollar signs
        // - Note: Dollar signs are allowed in PostgreSQL but we'll quote them for safety
        //
        // If identifier contains anything else (special chars like #, $, spaces, etc.), it needs quoting
        if (!lowercaseIdentifier.matches("^[a-z_][a-z0-9_]*$")) {
            return true;
        }

        return false;
    }

    /**
     * Normalizes a qualified identifier (schema.table or schema.table.column) for PostgreSQL.
     *
     * <p>Each component is normalized separately and rejoined with dots.
     *
     * <p>Example:
     * <pre>
     * normalizeQualifiedIdentifier("USER_SCHEMA.ORDER_TABLE") → user_schema.order_table
     * normalizeQualifiedIdentifier("MYSCHEMA.USER") → myschema."user"
     * </pre>
     *
     * @param qualifiedIdentifier The qualified identifier (e.g., "SCHEMA.TABLE")
     * @return The normalized qualified identifier
     */
    public static String normalizeQualifiedIdentifier(String qualifiedIdentifier) {
        if (qualifiedIdentifier == null || qualifiedIdentifier.isEmpty()) {
            return qualifiedIdentifier;
        }

        String[] parts = qualifiedIdentifier.split("\\.");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(".");
            }
            result.append(normalizeIdentifier(parts[i]));
        }

        return result.toString();
    }

    /**
     * Checks if an identifier is a PostgreSQL reserved word (for testing/validation purposes).
     *
     * @param identifier The identifier to check (case-insensitive)
     * @return {@code true} if the identifier is a PostgreSQL reserved word, {@code false} otherwise
     */
    public static boolean isReservedWord(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        return POSTGRES_RESERVED_WORDS.contains(identifier.toLowerCase());
    }
}
