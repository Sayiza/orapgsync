package me.christianrobert.orapgsync.typemethod.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Service for extracting type method metadata from Oracle database.
 * Queries ALL_TYPE_METHODS and ALL_METHOD_PARAMS.
 */
@ApplicationScoped
public class OracleTypeMethodExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleTypeMethodExtractor.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    public List<TypeMethodMetadata> extractTypeMethods(List<String> schemas,
                                                        Consumer<JobProgress> progressCallback) throws SQLException {
        List<TypeMethodMetadata> allTypeMethods = new ArrayList<>();

        try (Connection connection = oracleConnectionService.getConnection()) {
            int totalSchemas = schemas.size();
            int processedSchemas = 0;

            for (String schema : schemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<TypeMethodMetadata> schemaTypeMethods = extractTypeMethodsForSchema(connection, schema);
                    allTypeMethods.addAll(schemaTypeMethods);

                    log.info("Extracted {} type methods from schema {}", schemaTypeMethods.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract type methods for schema: " + schema, e);
                    updateProgress(progressCallback,
                            10 + (processedSchemas * 80 / totalSchemas),
                            "Error in schema: " + schema,
                            "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            updateProgress(progressCallback, 90, "Extraction complete",
                    String.format("Extracted %d type methods from %d schemas", allTypeMethods.size(), schemas.size()));
        }

        return allTypeMethods;
    }

    private List<TypeMethodMetadata> extractTypeMethodsForSchema(Connection connection, String schema) throws SQLException {
        List<TypeMethodMetadata> typeMethods = new ArrayList<>();

        // Query ALL_TYPE_METHODS to get method signatures
        String methodSql = """
            SELECT
                owner,
                type_name,
                method_name,
                method_type,
                method_no,
                instantiable
            FROM all_type_methods
            WHERE owner = ?
            ORDER BY type_name, method_name, method_no
            """;

        try (PreparedStatement ps = connection.prepareStatement(methodSql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String owner = rs.getString("owner").toLowerCase();
                    String typeName = rs.getString("type_name").toLowerCase();
                    String methodName = rs.getString("method_name").toLowerCase();
                    String methodType = rs.getString("method_type"); // FUNCTION or PROCEDURE
                    String methodNo = rs.getString("method_no");
                    String instantiable = rs.getString("instantiable"); // YES (member) or NO (static)

                    TypeMethodMetadata method = new TypeMethodMetadata(owner, typeName, methodName, methodType);
                    method.setMethodNo(methodNo);
                    method.setInstantiable(instantiable);

                    // Extract return type for functions (from ALL_METHOD_RESULTS)
                    if ("FUNCTION".equalsIgnoreCase(methodType)) {
                        extractReturnTypeForMethod(connection, owner, typeName, methodName, methodNo, method);
                    }

                    // Extract parameters for this method
                    extractParametersForMethod(connection, owner, typeName, methodName, methodNo, method);

                    typeMethods.add(method);

                    log.debug("Extracted type method: {} ({})", method.getDisplayName(), method.getMemberTypeDescription());
                }
            }
        }

        return typeMethods;
    }

    private void extractReturnTypeForMethod(Connection connection, String owner, String typeName,
                                             String methodName, String methodNo, TypeMethodMetadata method) throws SQLException {
        // Query ALL_METHOD_RESULTS to get return type information
        String resultSql = """
            SELECT
                result_type_owner,
                result_type_name
            FROM all_method_results
            WHERE owner = ?
                AND type_name = ?
                AND method_name = ?
                AND method_no = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(resultSql)) {
            ps.setString(1, owner.toUpperCase());
            ps.setString(2, typeName.toUpperCase());
            ps.setString(3, methodName.toUpperCase());
            ps.setString(4, methodNo);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String resultTypeOwner = rs.getString("result_type_owner");
                    String resultTypeName = rs.getString("result_type_name");

                    if (resultTypeName != null) {
                        if (resultTypeOwner != null && !resultTypeOwner.isEmpty()) {
                            // Custom return type
                            method.setCustomReturnType(true);
                            method.setReturnTypeOwner(resultTypeOwner.toLowerCase());
                            method.setReturnTypeName(resultTypeName.toLowerCase());
                            method.setReturnDataType(resultTypeOwner.toLowerCase() + "." + resultTypeName.toLowerCase());
                        } else {
                            // Built-in return type
                            method.setCustomReturnType(false);
                            method.setReturnDataType(resultTypeName);
                        }
                    }
                }
            }
        }
    }

    private void extractParametersForMethod(Connection connection, String owner, String typeName,
                                             String methodName, String methodNo, TypeMethodMetadata method) throws SQLException {
        // Query ALL_METHOD_PARAMS to get method parameters
        String paramSql = """
            SELECT
                param_name,
                param_no,
                param_mode,
                param_type_owner,
                param_type_name
            FROM all_method_params
            WHERE owner = ?
                AND type_name = ?
                AND method_name = ?
                AND method_no = ?
                AND param_name IS NOT NULL
            ORDER BY param_no
            """;

        try (PreparedStatement ps = connection.prepareStatement(paramSql)) {
            ps.setString(1, owner.toUpperCase());
            ps.setString(2, typeName.toUpperCase());
            ps.setString(3, methodName.toUpperCase());
            ps.setString(4, methodNo);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String paramName = rs.getString("param_name");
                    if (paramName == null || paramName.isEmpty()) {
                        continue; // Skip parameters without names
                    }

                    paramName = paramName.toLowerCase();
                    int paramNo = rs.getInt("param_no");
                    String paramMode = rs.getString("param_mode"); // IN, OUT, IN/OUT
                    String paramTypeOwner = rs.getString("param_type_owner");
                    String paramTypeName = rs.getString("param_type_name");

                    // Determine data type
                    String dataType;
                    boolean isCustomType = false;

                    if (paramTypeOwner != null && !paramTypeOwner.isEmpty()) {
                        // Custom parameter type
                        dataType = paramTypeOwner.toLowerCase() + "." + paramTypeName.toLowerCase();
                        isCustomType = true;
                    } else {
                        // Built-in parameter type
                        dataType = paramTypeName;
                    }

                    TypeMethodParameter param = new TypeMethodParameter(paramName, paramNo, dataType);
                    param.setInOut(paramMode);
                    param.setCustomDataType(isCustomType);

                    if (isCustomType) {
                        param.setDataTypeOwner(paramTypeOwner.toLowerCase());
                        param.setDataTypeName(paramTypeName.toLowerCase());
                    }

                    method.addParameter(param);
                }
            }
        }
    }

    private void updateProgress(Consumer<JobProgress> progressCallback, int percentage, String status, String message) {
        if (progressCallback != null) {
            progressCallback.accept(new JobProgress(percentage, status, message));
        }
    }
}
