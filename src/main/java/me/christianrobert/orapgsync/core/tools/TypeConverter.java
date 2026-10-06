package me.christianrobert.orapgsync.core.tools;

public class TypeConverter {

  public static String toJava(String returnType) {
    if (returnType.toLowerCase().startsWith("varchar2")) { return "String"; }
    if (returnType.toLowerCase().equals("clob")) { return "String"; }
    if (returnType.toLowerCase().equals("number")) { return "BigDecimal"; }
    return "Object"; //TODO
  }

  public static String toJavaMediaType(String returnType) {
    if (returnType.startsWith("varchar2")) { return "MediaType.TEXT_PLAIN"; }
    if (returnType.equals("clob")) { return "MediaType.TEXT_PLAIN"; }
    if (returnType.equals("number")) { return "MediaType.TEXT_PLAIN"; }
    return "MediaType.APPLICATION_JSON";
  }

  /**
   * Maps an Oracle built-in type to its PostgreSQL equivalent, returning the input unchanged when
   * it recognises nothing. Callers that need to tell "mapped" from "passed through" — because
   * emitting an unrecognised name produces SQL PostgreSQL will reject — must use
   * {@link #toPostgreOrNull} instead of comparing the result against the input: several built-ins
   * legitimately map to themselves ({@code numeric}, {@code boolean}, {@code varchar}), so
   * equality does not distinguish the two cases.
   */
  public static String toPostgre(String returnType) {
    if (returnType == null) {
      return null;
    }
    String mapped = toPostgreOrNull(returnType);
    return mapped != null ? mapped : returnType.toLowerCase().trim();
  }

  /**
   * As {@link #toPostgre}, but returns {@code null} for a type this class does not recognise
   * rather than echoing it back.
   *
   * <p>This is the same switch, not a second copy of it — the two methods must never be able to
   * disagree about what counts as a built-in.
   *
   * @param returnType Oracle type name
   * @return the PostgreSQL type, or null if this is not an Oracle built-in
   */
  public static String toPostgreOrNull(String returnType) {
    if (returnType == null) {
      return null;
    }

    returnType = returnType.toLowerCase().trim();

    // Handle parameterized types first
    if (returnType.startsWith("number")) {
      return "numeric"; 
    }
    if (returnType.startsWith("varchar2")) {
      return "text";
    }
    if (returnType.startsWith("nvarchar2")) {
      return "text";
    }
    if (returnType.startsWith("char")) {
      return "text";
    }
    if (returnType.startsWith("nchar")) {
      return "text";
    }
    if (returnType.startsWith("raw")) {
      return "bytea";
    }
    if (returnType.startsWith("timestamp")) {
      return "timestamp";
    }
    if (returnType.startsWith("interval")) {
      return "interval";
    }

    // Handle exact type matches
    switch (returnType) {
      // Integer types
      case "binary_integer":
      case "pls_integer":
      case "natural":
      case "naturaln":
      case "positive":
      case "positiven":
      case "simple_integer":
      case "integer":
      case "int":
        return "integer";

      // Small integer
      case "signtype":
      case "smallint":
        return "smallint";

      // Floating point types
      case "binary_float":
      case "float":
      case "real":
        return "real";

      case "binary_double":
      case "double":
        return "double precision";

      // Decimal types
      case "dec":
      case "decimal":
        return "decimal";

      case "numeric":
        return "numeric";

      // Character types
      case "long":
      case "string":
      case "varchar":
        return "varchar";

      case "character":
        return "char";

      // Boolean.
      // ALL_ARGUMENTS spells a package member's BOOLEAN as the literal "PL/SQL BOOLEAN" and only
      // a standalone routine's as "BOOLEAN". Both name the same Oracle type; the two spellings are
      // an artefact of the data dictionary, so both belong here. Without the first, every affected
      // signature reached PostgreSQL as "RETURNS pl/sql boolean" and was rejected at the slash.
      case "boolean":
      case "pl/sql boolean":
        return "boolean";

      // Date and time types
      case "date":
        return "timestamp";

      case "timestamp_unconstrained":
        return "timestamp";

      case "timestamp_tz_unconstrained":
      case "timestamp_ltz_unconstrained":
        return "timestamp with time zone";

      case "yminterval_unconstrained":
      case "dsinterval_unconstrained":
        return "interval";

      // Time component types
      case "year":
      case "month":
      case "day":
      case "hour":
      case "minute":
      case "second":
      case "timezone_hour":
      case "timezone_minute":
        return "integer";

      // Text types for timezone and identifiers
      case "timezone_region":
      case "timezone_abbr":
      case "rowid":
      case "urowid":
      case "mlslabel":
        return "text";

      // Large object types
      case "bfile":
        return "text";

      case "blob":
        return "oid";

      case "clob":
      case "nclob":
        return "oid";

      // XML type - Oracle XMLTYPE maps directly to PostgreSQL xml type
      // Note: When XMLTYPE appears with owner SYS/PUBLIC, it's detected via OracleTypeClassifier.isXmlType()
      case "xmltype":
        return "xml";

      // Additional types not in original list
      case "long raw":
        return "bytea";

      case "interval year to month":
      case "interval day to second":
        return "interval";

      case "httpuritype":
      case "dburitype":
      case "xdburitype":
        return "text";
        
      case "anydata":
      case "anytype":
        return "jsonb";

      case "aq$_jms_text_message":
      case "sys.aq$_jms_text_message":
        return "jsonb";

      case "aq$_sig_prop":
      case "sys.aq$_sig_prop":
        return "jsonb";

      case "aq$_recipients":
      case "sys.aq$_recipients":
        return "jsonb";

      case "sdo_geometry":
        return "geometry";

      // A cursor declared as SYS_REFCURSOR reaches us under that name; one declared as an
      // anonymous REF CURSOR reaches us as the dictionary's two-word "REF CURSOR". Same type.
      case "sys_refcursor":
      case "ref cursor":
        return "refcursor";

      case "json":
        return "jsonb";

      case "varray":
      case "nested table":
        return "array";

      // Not an Oracle built-in. It is most likely a user-defined type, which only metadata can
      // place — see TypeResolver. toPostgre() echoes the name back for callers that have no
      // metadata to consult; this method says so instead.
      default:
        return null;
    }
  }
}
