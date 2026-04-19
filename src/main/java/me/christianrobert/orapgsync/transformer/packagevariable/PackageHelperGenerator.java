package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates PostgreSQL helper functions for Oracle package variables.
 *
 * <p>Generates three types of functions:
 * <ul>
 *   <li><strong>Initialization function:</strong> {@code pkg__initialize()} - Initializes all variables to defaults</li>
 *   <li><strong>Getter functions:</strong> {@code pkg__get_varname()} - Retrieves variable value</li>
 *   <li><strong>Setter functions:</strong> {@code pkg__set_varname(value)} - Sets variable value</li>
 * </ul>
 *
 * <p>Uses PostgreSQL {@code set_config}/{@code current_setting} for transaction-local state management.
 * Variables automatically reset when the transaction commits, ensuring clean state between web requests.
 *
 * <p><strong>Usage Pattern:</strong>
 * <pre>
 * PackageHelperGenerator generator = new PackageHelperGenerator();
 * List&lt;String&gt; sqlStatements = generator.generateHelperSql(packageContext);
 * // Execute each SQL statement in PostgreSQL
 * </pre>
 */
public class PackageHelperGenerator {

    private static final Logger log = LoggerFactory.getLogger(PackageHelperGenerator.class);

    /**
     * Generates all helper function SQL statements for a package.
     *
     * @param context Package context with variable declarations
     * @return List of SQL statements (CREATE OR REPLACE FUNCTION) to execute
     */
    public List<String> generateHelperSql(PackageContext context) {
        log.debug("Generating helper SQL for package {}.{}", context.getSchema(), context.getPackageName());

        List<String> sqlStatements = new ArrayList<>();

        // 1. Generate initialization function
        sqlStatements.add(generateInitializeFunction(context));

        // 2. Generate getters and setters for each variable
        for (PackageContext.PackageVariable var : context.getVariables().values()) {
            sqlStatements.add(generateGetterFunction(context, var));

            // Constants don't need setters
            if (!var.isConstant()) {
                sqlStatements.add(generateSetterFunction(context, var));
            }
        }

        log.debug("Generated {} helper functions for package {}.{}",
                  sqlStatements.size(), context.getSchema(), context.getPackageName());
        return sqlStatements;
    }

    /**
     * Generates the package initialization function.
     * Pattern: pkg__initialize() RETURNS void
     */
    private String generateInitializeFunction(PackageContext context) {
        StringBuilder sql = new StringBuilder();
        String schema = context.getSchema().toLowerCase();
        String pkgName = context.getPackageName().toLowerCase();

        // Function signature
        sql.append("CREATE OR REPLACE FUNCTION ")
           .append(schema).append(".").append(pkgName).append("__initialize()\n");
        sql.append("RETURNS void\n");
        sql.append("LANGUAGE plpgsql\n");
        sql.append("AS $$\n");
        sql.append("BEGIN\n");

        // Fast path: Check if already initialized
        String initFlagKey = schema + "." + pkgName + ".__initialized";
        sql.append("  -- Fast path: Already initialized?\n");
        sql.append("  IF current_setting('").append(initFlagKey).append("', true) = 'true' THEN\n");
        sql.append("    RETURN;\n");
        sql.append("  END IF;\n\n");

        // Initialize all variables with defaults
        sql.append("  -- Initialize all package variables with defaults\n");
        for (PackageContext.PackageVariable var : context.getVariables().values()) {
            String configKey = schema + "." + pkgName + "." + var.getVariableName().toLowerCase();

            // Check if variable type is a known inline type (RECORD, TABLE OF, etc.)
            InlineTypeDefinition inlineType = context.getType(var.getDataType());
            String pgDefaultValue;

            if (inlineType != null) {
                // Complex type → use raw JSON value (e.g., "{}" or "[]")
                // set_config stores as TEXT, so we don't need ::jsonb cast
                pgDefaultValue = getJsonbDefaultValue(inlineType);
            } else {
                // Scalar type → use default value transformation
                pgDefaultValue = transformDefaultValue(var.getDefaultValue(), var.getDataType());
            }

            sql.append("  PERFORM set_config('").append(configKey).append("', '")
               .append(escapeQuotes(pgDefaultValue)).append("', true);\n");
        }

        // Mark as initialized
        sql.append("\n  -- Mark as initialized (idempotent)\n");
        sql.append("  PERFORM set_config('").append(initFlagKey).append("', 'true', true);\n");
        sql.append("END;\n");
        sql.append("$$;");

        return sql.toString();
    }

    /**
     * Generates a getter function for a package variable.
     * Pattern: pkg__get_varname() RETURNS type
     *
     * <p>For complex types (RECORD, TABLE OF, VARRAY, INDEX BY), returns jsonb.
     * For scalar types, returns the appropriate PostgreSQL type.
     */
    private String generateGetterFunction(PackageContext context, PackageContext.PackageVariable var) {
        StringBuilder sql = new StringBuilder();
        String schema = context.getSchema().toLowerCase();
        String pkgName = context.getPackageName().toLowerCase();
        String varName = var.getVariableName().toLowerCase();

        // Check if variable type is a known inline type (RECORD, TABLE OF, etc.)
        InlineTypeDefinition inlineType = context.getType(var.getDataType());

        String pgType;
        String defaultValue;

        // For complex types (RECORD, TABLE OF, etc.), use jsonb with proper JSON defaults
        // For scalar types, use TypeConverter
        String defaultValueLiteral;  // The raw JSON/value without SQL escaping (e.g., "{}" or "[]")

        if (inlineType != null) {
            // Complex type → jsonb
            pgType = "jsonb";
            // Get raw JSON value without quotes or cast (e.g., "{}" not "'{}'::jsonb")
            defaultValueLiteral = getJsonbDefaultValue(inlineType);
        } else {
            // Scalar type → use TypeConverter
            pgType = TypeConverter.toPostgre(var.getDataType());
            defaultValueLiteral = transformDefaultValue(var.getDefaultValue(), var.getDataType());
        }

        // Function signature
        sql.append("CREATE OR REPLACE FUNCTION ")
           .append(schema).append(".").append(pkgName).append("__get_").append(varName).append("()\n");
        sql.append("RETURNS ").append(pgType).append("\n");
        sql.append("LANGUAGE plpgsql\n");
        sql.append("AS $$\n");
        sql.append("BEGIN\n");

        // Return current value with default fallback
        // IMPORTANT: current_setting returns '' (empty string) when not found, not NULL.
        // We must use NULLIF to convert '' to NULL before casting, especially for jsonb
        // where '' is invalid JSON and would cause "invalid input syntax for type json" errors.
        String configKey = schema + "." + pkgName + "." + varName;

        sql.append("  RETURN COALESCE(\n");
        sql.append("    NULLIF(current_setting('").append(configKey).append("', true), '')::").append(pgType).append(",\n");
        sql.append("    '").append(escapeQuotes(defaultValueLiteral)).append("'::").append(pgType).append("\n");
        sql.append("  );\n");
        sql.append("EXCEPTION WHEN OTHERS THEN\n");
        sql.append("  RETURN '").append(escapeQuotes(defaultValueLiteral)).append("'::").append(pgType).append(";\n");
        sql.append("END;\n");
        sql.append("$$;");

        return sql.toString();
    }

    /**
     * Generates a setter function for a package variable (not for constants).
     * Pattern: pkg__set_varname(p_value type) RETURNS void
     *
     * <p>For complex types (RECORD, TABLE OF, VARRAY, INDEX BY), accepts jsonb.
     * For scalar types, accepts the appropriate PostgreSQL type.
     */
    private String generateSetterFunction(PackageContext context, PackageContext.PackageVariable var) {
        StringBuilder sql = new StringBuilder();
        String schema = context.getSchema().toLowerCase();
        String pkgName = context.getPackageName().toLowerCase();
        String varName = var.getVariableName().toLowerCase();

        // Check if variable type is a known inline type (RECORD, TABLE OF, etc.)
        InlineTypeDefinition inlineType = context.getType(var.getDataType());
        String pgType = (inlineType != null) ? "jsonb" : TypeConverter.toPostgre(var.getDataType());

        // Function signature
        sql.append("CREATE OR REPLACE FUNCTION ")
           .append(schema).append(".").append(pkgName).append("__set_").append(varName)
           .append("(p_value ").append(pgType).append(")\n");
        sql.append("RETURNS void\n");
        sql.append("LANGUAGE plpgsql\n");
        sql.append("AS $$\n");
        sql.append("BEGIN\n");

        // Set configuration value
        String configKey = schema + "." + pkgName + "." + varName;
        sql.append("  PERFORM set_config('").append(configKey).append("', p_value::text, false);\n");
        sql.append("END;\n");
        sql.append("$$;");

        return sql.toString();
    }

    /**
     * Transforms Oracle default value to PostgreSQL equivalent.
     * Examples:
     * - "0" → "0"
     * - "'ACTIVE'" → "ACTIVE" (remove quotes for text storage)
     * - "SYSDATE" → "(CURRENT_TIMESTAMP)::text"
     * - null → "0" or "" depending on type
     */
    private String transformDefaultValue(String oracleDefault, String oracleType) {
        // No default specified - use type-appropriate default
        if (oracleDefault == null || oracleDefault.trim().isEmpty()) {
            return getTypeDefaultValue(oracleType);
        }

        oracleDefault = oracleDefault.trim();

        // Numeric literals - keep as-is
        if (oracleDefault.matches("-?\\d+(\\.\\d+)?")) {
            return oracleDefault;
        }

        // String literals - remove surrounding quotes
        if (oracleDefault.startsWith("'") && oracleDefault.endsWith("'")) {
            return oracleDefault.substring(1, oracleDefault.length() - 1);
        }

        // Oracle functions - transform to PostgreSQL equivalents
        String lower = oracleDefault.toLowerCase();
        if (lower.equals("sysdate") || lower.equals("systimestamp")) {
            return "CURRENT_TIMESTAMP";
        }
        if (lower.equals("true")) {
            return "true";
        }
        if (lower.equals("false")) {
            return "false";
        }
        if (lower.equals("null")) {
            return getTypeDefaultValue(oracleType);
        }

        // Complex expression - store as text representation (best effort)
        return oracleDefault;
    }

    /**
     * Returns appropriate default value for a given Oracle type.
     */
    private String getTypeDefaultValue(String oracleType) {
        if (oracleType == null) {
            return "";
        }

        String lower = oracleType.toLowerCase();

        // Numeric types
        if (lower.startsWith("number") || lower.equals("integer") ||
            lower.equals("int") || lower.equals("pls_integer") ||
            lower.equals("binary_integer")) {
            return "0";
        }

        // Boolean
        if (lower.equals("boolean")) {
            return "false";
        }

        // Date/Time
        if (lower.equals("date") || lower.startsWith("timestamp")) {
            return "CURRENT_TIMESTAMP";
        }

        // String types - empty string
        return "";
    }

    /**
     * Escapes single quotes in SQL strings.
     */
    private String escapeQuotes(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    /**
     * Returns raw JSON default value for complex types (without quotes or ::jsonb cast).
     * Used for set_config storage and COALESCE defaults.
     *
     * <p>This is separate from InlineTypeDefinition.getInitializer() which returns
     * SQL-ready values like '{}'::jsonb. For set_config we need just the raw JSON.
     */
    private String getJsonbDefaultValue(InlineTypeDefinition inlineType) {
        switch (inlineType.getCategory()) {
            case RECORD:
            case INDEX_BY:
            case ROWTYPE:
                return "{}";  // Empty object
            case TABLE_OF:
            case VARRAY:
                return "[]";  // Empty array
            default:
                return "{}";
        }
    }
}
