package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Service for extracting function and procedure metadata from Oracle database.
 * Extracts standalone functions/procedures and package subprograms using ALL_PROCEDURES and ALL_ARGUMENTS.
 */
public class OracleFunctionExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleFunctionExtractor.class);

    /**
     * Extracts all functions and procedures for the specified schemas from Oracle.
     * This includes:
     * - Standalone functions (OBJECT_TYPE = 'FUNCTION')
     * - Standalone procedures (OBJECT_TYPE = 'PROCEDURE')
     * - Package functions (OBJECT_TYPE = 'PACKAGE', subprograms with return type)
     * - Package procedures (OBJECT_TYPE = 'PACKAGE', subprograms without return type)
     *
     * @param oracleConn Oracle database connection
     * @param schemas List of schema names to extract functions from
     * @return List of FunctionMetadata objects
     * @throws SQLException if database operations fail
     */
    public static List<FunctionMetadata> extractAllFunctions(Connection oracleConn, List<String> schemas) throws SQLException {
        List<FunctionMetadata> functionMetadataList = new ArrayList<>();

        for (String schema : schemas) {
            if (UserExcluder.is2BeExclueded(schema)) {
                continue;
            }

            List<FunctionMetadata> schemaFunctions = fetchFunctionsForSchema(oracleConn, schema);
            functionMetadataList.addAll(schemaFunctions);

            log.info("Extracted {} functions/procedures from Oracle schema {}", schemaFunctions.size(), schema);
        }

        return functionMetadataList;
    }

    /**
     * Fetches all functions and procedures for a single schema.
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @return List of FunctionMetadata for the schema
     * @throws SQLException if database operations fail
     */
    private static List<FunctionMetadata> fetchFunctionsForSchema(Connection oracleConn, String schema) throws SQLException {
        Map<String, FunctionMetadata> functionsMap = new HashMap<>();

        // Query ALL_PROCEDURES for standalone functions/procedures and package members
        String procSql = """
            SELECT
                owner,
                object_name,
                procedure_name,
                object_type,
                overload
            FROM all_procedures
            WHERE owner = ?
              AND object_type IN ('FUNCTION', 'PROCEDURE', 'PACKAGE')
              AND procedure_name IS NOT NULL
            ORDER BY object_name, procedure_name, overload
            """;

        try (PreparedStatement ps = oracleConn.prepareStatement(procSql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String objectName = rs.getString("object_name");
                    String procedureName = rs.getString("procedure_name");
                    String objectType = rs.getString("object_type");
                    String overloadStr = rs.getString("overload");
                    int overload = (overloadStr != null) ? Integer.parseInt(overloadStr) : 0;

                    // Determine if this is standalone or package member
                    boolean isPackageMember = "PACKAGE".equalsIgnoreCase(objectType);
                    String functionName = isPackageMember ? procedureName : objectName;
                    String packageName = isPackageMember ? objectName : null;

                    // Create unique key for this function (schema.package.function.overload)
                    String key = schema.toLowerCase() + "." +
                                 (packageName != null ? packageName.toLowerCase() + "." : "") +
                                 functionName.toLowerCase() +
                                 (overload > 0 ? "." + overload : "");

                    // We'll determine if it's a function or procedure based on return type from ALL_ARGUMENTS
                    FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), functionName.toLowerCase(), "FUNCTION");
                    if (packageName != null) {
                        metadata.setPackageName(packageName.toLowerCase());
                    }
                    metadata.setOverloadNumber(overload);

                    functionsMap.put(key, metadata);
                }
            }
        }

        // Now query ALL_ARGUMENTS to get parameters and return types
        String argSql = """
            SELECT
                object_name,
                package_name,
                argument_name,
                position,
                data_type,
                in_out,
                overload,
                type_owner,
                type_name,
                data_level
            FROM all_arguments
            WHERE owner = ?
              AND object_name IN (
                  SELECT object_name FROM all_procedures
                  WHERE owner = ? AND object_type IN ('FUNCTION', 'PROCEDURE')
                  UNION
                  SELECT procedure_name FROM all_procedures
                  WHERE owner = ? AND object_type = 'PACKAGE' AND procedure_name IS NOT NULL
              )
            ORDER BY object_name, package_name, overload, position
            """;

        try (PreparedStatement ps = oracleConn.prepareStatement(argSql)) {
            ps.setString(1, schema.toUpperCase());
            ps.setString(2, schema.toUpperCase());
            ps.setString(3, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String objectName = rs.getString("object_name");
                    String packageName = rs.getString("package_name");
                    String argumentName = rs.getString("argument_name");
                    int position = rs.getInt("position");
                    String dataType = rs.getString("data_type");
                    String inOut = rs.getString("in_out");
                    String overloadStr = rs.getString("overload");
                    int overload = (overloadStr != null) ? Integer.parseInt(overloadStr) : 0;
                    String typeOwner = rs.getString("type_owner");
                    String typeName = rs.getString("type_name");
                    int dataLevel = rs.getInt("data_level");

                    // Skip collection element types (data_level > 0)
                    if (dataLevel > 0) {
                        continue;
                    }

                    // Create unique key
                    String functionName = objectName.toLowerCase();
                    String key = schema.toLowerCase() + "." +
                                 (packageName != null ? packageName.toLowerCase() + "." : "") +
                                 functionName +
                                 (overload > 0 ? "." + overload : "");

                    FunctionMetadata metadata = functionsMap.get(key);
                    if (metadata == null) {
                        log.warn("Found arguments for unknown function: {}", key);
                        continue;
                    }

                    // Position 0 (or null argument_name) indicates the return value for functions
                    if (position == 0 || (argumentName == null && inOut != null && inOut.contains("OUT"))) {
                        // This is a function with a return type
                        metadata.setObjectType("FUNCTION");
                        metadata.setReturnDataType(dataType);

                        // Check if return type is custom
                        if (typeOwner != null && typeName != null) {
                            metadata.setCustomReturnType(true);
                            metadata.setReturnTypeOwner(typeOwner.toLowerCase());
                            metadata.setReturnTypeName(typeName.toLowerCase());
                        }
                    } else if (argumentName != null) {
                        // This is a parameter
                        FunctionParameter param = new FunctionParameter(
                            argumentName.toLowerCase(),
                            position,
                            dataType,
                            inOut != null ? inOut : "IN"
                        );

                        // Check if parameter type is custom
                        if (typeOwner != null && typeName != null) {
                            param.setCustomDataType(true);
                            param.setDataTypeOwner(typeOwner.toLowerCase());
                            param.setDataTypeName(typeName.toLowerCase());
                        }

                        metadata.addParameter(param);
                    }
                }
            }
        }

        // Set object type to PROCEDURE if no return type was found
        for (FunctionMetadata metadata : functionsMap.values()) {
            if (metadata.getReturnDataType() == null) {
                metadata.setObjectType("PROCEDURE");
            }
        }

        List<FunctionMetadata> result = new ArrayList<>(functionsMap.values());
        log.debug("Extracted {} functions/procedures from schema {}", result.size(), schema);
        return result;
    }
}
