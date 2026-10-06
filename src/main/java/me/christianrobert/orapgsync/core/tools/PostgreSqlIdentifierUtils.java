package me.christianrobert.orapgsync.core.tools;

import java.util.Set;

/**
 * Unified utility class for PostgreSQL identifier handling.
 *
 * This class provides consistent functionality for:
 * - Checking if identifiers are PostgreSQL reserved words
 * - Properly quoting identifiers when necessary
 * - Following PostgreSQL naming conventions
 *
 * Replaces multiple scattered implementations throughout the codebase
 * to ensure consistent behavior and comprehensive reserved word coverage.
 */
public class PostgreSqlIdentifierUtils {

  /**
   * Comprehensive set of PostgreSQL reserved words.
   * Based on PostgreSQL 15+ documentation including keywords that:
   * - Cannot be used as identifiers without quoting
   * - Are commonly problematic in Oracle-to-PostgreSQL migrations
   * - Include words that were missing from previous implementations (like "end")
   */
  private static final Set<String> POSTGRESQL_RESERVED_WORDS = Set.of(
          // SQL Standard Reserved Words
          "abort", "absolute", "access", "action", "add", "admin", "after", "aggregate", "all", "also",
          "alter", "always", "analyse", "analyze", "and", "any", "array", "as", "asc", "assertion",
          "assignment", "asymmetric", "com", "atomic", "attribute", "authorization", "backward", "before",
          "begin", "between", "bigint", "binary", "bit", "boolean", "both", "by", "cache", "call",
          "called", "cascade", "cascaded", "case", "cast", "catalog", "chain", "char", "character",
          "characteristics", "check", "checkpoint", "class", "close", "cluster", "coalesce", "collate",
          "collation", "column", "columns", "comment", "comments", "commit", "committed", "concurrently",
          "configuration", "conflict", "connection", "constraint", "constraints", "content", "continue",
          "conversion", "copy", "cost", "create", "cross", "csv", "cube", "current", "current_catalog",
          "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
          "current_user", "cursor", "cycle", "data", "database", "day", "deallocate", "dec", "decimal",
          "declare", "default", "defaults", "deferrable", "deferred", "definer", "delete", "delimiter",
          "delimiters", "depends", "desc", "detach", "dictionary", "disable", "discard", "distinct",
          "do", "document", "domain", "double", "drop", "each", "else", "enable", "encoding", "encrypted",
          "end", "enum", "escape", "event", "except", "exclude", "excluding", "exclusive", "execute",
          "exists", "explain", "expression", "extension", "external", "extract", "false", "family",
          "fetch", "filter", "first", "float", "following", "for", "force", "foreign", "forward",
          "freeze", "from", "full", "function", "functions", "generated", "global", "grant", "granted",
          "greatest", "group", "grouping", "groups", "handler", "having", "header", "hold", "hour",
          "identity", "if", "ilike", "immediate", "immutable", "implicit", "import", "in", "include",
          "including", "increment", "index", "indexes", "inherit", "inherits", "initially", "inline",
          "inner", "inout", "input", "insensitive", "insert", "instead", "int", "integer", "intersect",
          "interval", "into", "invoker", "is", "isnull", "isolation", "join", "key", "label", "language",
          "large", "last", "lateral", "leading", "leakproof", "least", "left", "level", "like", "limit",
          "listen", "load", "local", "localtime", "localtimestamp", "location", "lock", "locked",
          "logged", "mapping", "match", "materialized", "maxvalue", "method", "minute", "minvalue",
          "mode", "month", "move", "name", "names", "national", "natural", "nchar", "new", "next",
          "nfc", "nfd", "nfkc", "nfkd", "no", "none", "normalize", "normalized", "not", "nothing",
          "notify", "notnull", "nowait", "null", "nullif", "nulls", "numeric", "object", "of", "off",
          "offset", "oids", "old", "on", "only", "operator", "option", "options", "or", "order",
          "ordinality", "others", "out", "outer", "over", "overlaps", "overlay", "overriding", "owned",
          "owner", "parallel", "parser", "partial", "partition", "passing", "password", "placing",
          "plans", "policy", "position", "preceding", "precision", "prepare", "prepared", "preserve",
          "primary", "prior", "privileges", "procedural", "procedure", "procedures", "program",
          "publication", "quote", "range", "read", "real", "reassign", "recheck", "recursive", "ref",
          "references", "referencing", "refresh", "reindex", "relative", "release", "rename", "repeatable",
          "replace", "replica", "reset", "respect", "restart", "restrict", "returning", "returns",
          "revoke", "right", "role", "rollback", "rollup", "routine", "routines", "row", "rows", "rule",
          "savepoint", "schema", "schemas", "scroll", "search", "second", "security", "select", "sequence",
          "sequences", "serializable", "server", "session", "session_user", "set", "sets", "setof",
          "share", "show", "similar", "simple", "skip", "smallint", "snapshot", "some", "sql", "stable",
          "standalone", "start", "statement", "statistics", "stdin", "stdout", "storage", "stored",
          "strict", "strip", "subscription", "substring", "support", "symmetric", "sysid", "system",
          "table", "tables", "tablesample", "tablespace", "temp", "template", "temporary", "text",
          "then", "ties", "time", "timestamp", "to", "trailing", "transaction", "transform", "treat",
          "trigger", "trim", "true", "truncate", "trusted", "type", "types", "uescape", "unbounded",
          "uncommitted", "unencrypted", "union", "unique", "unknown", "unlisten", "unlogged", "until",
          "update", "user", "using", "vacuum", "valid", "validate", "validator", "value", "values",
          "varchar", "variadic", "varying", "verbose", "version", "view", "views", "volatile", "when",
          "where", "whitespace", "window", "with", "within", "without", "work", "wrapper", "write",
          "xml", "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest", "xmlnamespaces",
          "xmlparse", "xmlpi", "xmlroot", "xmlserialize", "xmltable", "year", "yes", "zone"
  );

  /**
   * Checks if a given identifier is a PostgreSQL reserved word.
   *
   * @param identifier The identifier to check (case-insensitive)
   * @return true if the identifier is a PostgreSQL reserved word, false otherwise
   */
  public static boolean isPostgresReservedWord(String identifier) {
    if (identifier == null || identifier.trim().isEmpty()) {
      return false;
    }
    return POSTGRESQL_RESERVED_WORDS.contains(identifier.toLowerCase().trim());
  }

  /**
   * Quotes a PostgreSQL identifier if necessary.
   *
   * An identifier needs quoting if:
   * - It's a PostgreSQL reserved word
   * - It contains special characters (anything other than letters, digits, underscore)
   * - It starts with a digit
   * - It contains uppercase letters (PostgreSQL is case-sensitive when quoted)
   * - It contains spaces
   *
   * @param identifier The identifier to potentially quote
   * @return The identifier, quoted with double quotes if necessary
   */
  public static String quoteIdentifier(String identifier) {
    if (identifier == null || identifier.trim().isEmpty()) {
      return identifier;
    }

    String trimmed = identifier.trim();

    // Check if quoting is needed
    if (needsQuoting(trimmed)) {
      return "\"" + trimmed + "\"";
    }

    return trimmed;
  }

  /**
   * Determines if an identifier needs quoting according to PostgreSQL rules.
   *
   * @param identifier The identifier to check (should be non-null and trimmed)
   * @return true if the identifier needs quoting, false otherwise
   */
  private static boolean needsQuoting(String identifier) {
    // Empty or null should not happen at this point, but defensive check
    if (identifier.isEmpty()) {
      return false;
    }

    // Check if it's a reserved word
    if (isPostgresReservedWord(identifier)) {
      return true;
    }

    // Check if it starts with a digit
    if (Character.isDigit(identifier.charAt(0))) {
      return true;
    }

    // Check each character
    for (int i = 0; i < identifier.length(); i++) {
      char c = identifier.charAt(i);

      // Allow letters (upper and lower), digits, and underscore
      if (!(Character.isLetter(c) || Character.isDigit(c) || c == '_')) {
        // If it contains uppercase letters, it needs quoting to preserve case
        //if (Character.isUpperCase(c)) {
        return true;
        //}
        //continue;
      }
      // Any other character (spaces, special chars) requires quoting
    }

    return false;
  }

  /**
   * Quotes a schema-qualified identifier (schema.object).
   * Both schema and object parts are quoted independently if necessary.
   *
   * @param schema The schema name
   * @param objectName The object name (table, view, function, etc.)
   * @return The properly quoted schema-qualified identifier
   */
  public static String quoteSchemaQualifiedIdentifier(String schema, String objectName) {
    if (schema == null || schema.trim().isEmpty()) {
      return quoteIdentifier(objectName);
    }

    return quoteIdentifier(schema.trim()) + "." + quoteIdentifier(objectName);
  }

  /**
   * Checks if an identifier is already quoted.
   *
   * @param identifier The identifier to check
   * @return true if the identifier is surrounded by double quotes
   */
  public static boolean isQuoted(String identifier) {
    if (identifier == null || identifier.length() < 2) {
      return false;
    }
    return identifier.startsWith("\"") && identifier.endsWith("\"");
  }

  /**
   * Removes quotes from an identifier if it's quoted.
   *
   * @param identifier The potentially quoted identifier
   * @return The identifier without surrounding quotes
   */
  public static String unquoteIdentifier(String identifier) {
    if (isQuoted(identifier)) {
      return identifier.substring(1, identifier.length() - 1);
    }
    return identifier;
  }

  /**
   * Get the complete set of PostgreSQL reserved words.
   * Useful for documentation, testing, or validation purposes.
   *
   * @return An unmodifiable set of all PostgreSQL reserved words (in lowercase)
   */
  public static Set<String> getReservedWords() {
    return POSTGRESQL_RESERVED_WORDS;
  }

  /**
   * Returns the count of reserved words in the current implementation.
   * Useful for testing and validation.
   *
   * @return The number of PostgreSQL reserved words tracked by this utility
   */
  public static int getReservedWordCount() {
    return POSTGRESQL_RESERVED_WORDS.size();
  }
}