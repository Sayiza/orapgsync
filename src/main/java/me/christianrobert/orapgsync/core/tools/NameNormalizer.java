package me.christianrobert.orapgsync.core.tools;

/**
 * Utility class for normalizing Oracle identifier names.
 *
 * This class provides methods to normalize Oracle object names by removing
 * double quotes and converting to uppercase, ensuring consistent naming
 * throughout the migration pipeline.
 *
 * Oracle identifiers can be:
 * - Unquoted (e.g., MYTYPE) - converted to uppercase by Oracle
 * - Quoted (e.g., "MyType") - preserve exact case, but we normalize for consistency
 *
 * Since we work under the assumption that no elements in the source database
 * have names that only differ in upper-vs-lower case, we can safely normalize
 * all names to uppercase for consistent processing.
 */
public class NameNormalizer {

  /**
   * Normalizes an Oracle object type name by removing quotes and converting to uppercase.
   *
   * Examples:
   * - "MyType" -> MYTYPE
   * - MyType -> MYTYPE
   * - "MYTYPE" -> MYTYPE
   * - mytype -> MYTYPE
   *
   * @param name The original object type name (may include quotes)
   * @return The normalized name (uppercase, no quotes)
   */
  public static String normalizeObjectTypeName(String name) {
    if (name == null) {
      return name;
    }

    String trimmed = name.trim();
    if (trimmed.isEmpty()) {
      return trimmed;
    }

    return normalizeIdentifier(name);
  }

  /**
   * Normalizes any Oracle identifier by removing quotes and converting to uppercase.
   *
   * This method handles:
   * - Double-quoted identifiers: "name" -> NAME
   * - Unquoted identifiers: name -> NAME
   * - Mixed case: "CamelCase" -> CAMELCASE
   *
   * @param identifier The original identifier (may include quotes)
   * @return The normalized identifier (uppercase, no quotes)
   */
  public static String normalizeIdentifier(String identifier) {
    if (identifier == null) {
      return identifier;
    }

    String trimmed = identifier.trim();
    if (trimmed.isEmpty()) {
      return trimmed;
    }

    // Remove surrounding double quotes if present
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      trimmed = trimmed.substring(1, trimmed.length() - 1);
    }

    // Convert to uppercase for consistency
    return trimmed.toUpperCase();
  }

  /**
   * Checks if an identifier is quoted (surrounded by double quotes).
   *
   * @param identifier The identifier to check
   * @return true if the identifier is quoted, false otherwise
   */
  public static boolean isQuoted(String identifier) {
    if (identifier == null || identifier.trim().length() < 2) {
      return false;
    }

    String trimmed = identifier.trim();
    return trimmed.startsWith("\"") && trimmed.endsWith("\"");
  }

  /**
   * Normalizes a data type name, handling both simple and qualified names.
   *
   * Examples:
   * - "SCHEMA"."TYPE" -> SCHEMA.TYPE
   * - schema.type -> SCHEMA.TYPE
   * - "MyType" -> MYTYPE
   *
   * @param dataType The data type name (may be qualified with schema)
   * @return The normalized data type name
   */
  public static String normalizeDataType(String dataType) {
    if (dataType == null) {
      return dataType;
    }

    String trimmed = dataType.trim();
    if (trimmed.isEmpty()) {
      return trimmed;
    }

    // Handle qualified names (schema.type)
    if (trimmed.contains(".")) {
      String[] parts = trimmed.split("\\.", 2);
      String schema = normalizeIdentifier(parts[0]);
      String type = normalizeIdentifier(parts[1]);
      return schema + "." + type;
    }

    // Handle simple names
    return normalizeIdentifier(trimmed);
  }
}