package me.christianrobert.orapgsync.typemethod.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments;
import me.christianrobert.orapgsync.transformer.parser.TypeMethodBoundaryScanner;
import me.christianrobert.orapgsync.transformer.parser.TypeMethodStubGenerator;
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

    @Inject
    private StateService stateService;

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
        Set<String> publicMethodKeys = new HashSet<>();

        // PHASE 1: Query ALL_TYPE_METHODS to get PUBLIC method signatures (from type spec)
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

                    // Build public method key (for filtering private methods later)
                    String publicKey = schema.toLowerCase() + "." + typeName + "." + methodName;
                    publicMethodKeys.add(publicKey);

                    log.debug("Extracted public type method: {} ({})", method.getDisplayName(), method.getMemberTypeDescription());
                }
            }
        }

        log.info("Extracted {} public type methods from schema {}", typeMethods.size(), schema);

        // PHASE 2: Query type bodies and scan for PRIVATE methods
        Map<String, String> typeBodies = queryTypeBodies(connection, schema);

        log.info("Found {} type bodies to scan in schema {}", typeBodies.size(), schema);

        for (Map.Entry<String, String> entry : typeBodies.entrySet()) {
            String typeName = entry.getKey();
            String bodySource = entry.getValue();

            List<TypeMethodMetadata> privateMethods = scanAndExtractTypeMethods(
                typeName, bodySource, schema, publicMethodKeys
            );

            typeMethods.addAll(privateMethods);
        }

        log.info("Extracted total {} type methods (public + private) from schema {}", typeMethods.size(), schema);

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

    /**
     * Queries ALL_SOURCE to fetch type body source code for a given schema.
     * Returns map of typename → type body source.
     *
     * @param connection Oracle connection
     * @param schema Schema name
     * @return Map of type name (lowercase) → type body source
     * @throws SQLException if query fails
     */
    private Map<String, String> queryTypeBodies(Connection connection, String schema) throws SQLException {
        Map<String, String> typeBodies = new HashMap<>();

        String sql = """
            SELECT name, text
            FROM all_source
            WHERE owner = ?
              AND type = 'TYPE BODY'
            ORDER BY name, line
            """;

        String currentTypeName = null;
        StringBuilder currentTypeBody = null;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String typeName = rs.getString("name");
                    String line = rs.getString("text");

                    // New type detected?
                    if (!typeName.equals(currentTypeName)) {
                        // Save previous type if exists
                        if (currentTypeName != null && currentTypeBody != null) {
                            typeBodies.put(currentTypeName.toLowerCase(), currentTypeBody.toString());
                        }

                        // Start new type
                        currentTypeName = typeName;
                        currentTypeBody = new StringBuilder();
                    }

                    // Append current line
                    currentTypeBody.append(line);
                }

                // Save last type
                if (currentTypeName != null && currentTypeBody != null) {
                    typeBodies.put(currentTypeName.toLowerCase(), currentTypeBody.toString());
                }
            }
        }

        log.debug("Queried {} type bodies from schema {}", typeBodies.size(), schema);
        return typeBodies;
    }

    /**
     * Scans a single type body and extracts private methods using lightweight scanner.
     * Stores segmented type data (full/stub) in StateService for later use.
     *
     * Approach (Type Segmentation):
     * 1. Clean source (remove comments)
     * 2. Scan method boundaries (TypeMethodBoundaryScanner)
     * 3. Extract full methods + generate stubs
     * 4. Store in StateService (for transformation job)
     * 5. Parse stubs (tiny, fast) to extract metadata
     * 6. Return all methods (public and private)
     *
     * @param typeName Name of the type
     * @param bodySource Type body source code (without CREATE OR REPLACE)
     * @param schema Schema name
     * @param publicMethodKeys Set of public method keys for filtering
     * @return List of TypeMethodMetadata for private methods in this type
     */
    private List<TypeMethodMetadata> scanAndExtractTypeMethods(
            String typeName, String bodySource, String schema, Set<String> publicMethodKeys) {

        List<TypeMethodMetadata> methods = new ArrayList<>();

        if (bodySource == null || bodySource.trim().isEmpty()) {
            return methods;
        }

        // Prepend CREATE OR REPLACE for parsing (Oracle doesn't store it in ALL_SOURCE)
        String fullSource = "CREATE OR REPLACE " + bodySource.trim();

        try {
            // STEP 1: Clean source (remove comments)
            String cleanedSource = CodeCleaner.removeComments(fullSource);
            log.trace("Cleaned type {}.{} ({} chars -> {} chars)",
                     schema, typeName, fullSource.length(), cleanedSource.length());

            // STEP 2: Scan method boundaries (fast, lightweight)
            TypeMethodBoundaryScanner scanner = new TypeMethodBoundaryScanner();
            TypeBodySegments segments = scanner.scanTypeBody(cleanedSource);

            log.debug("Scanned type {}.{}: found {} methods",
                     schema, typeName, segments.getMethods().size());

            if (segments.getMethods().isEmpty()) {
                // No methods in this type body
                return methods;
            }

            // STEP 3: Extract full methods and generate stubs
            Map<String, String> fullSources = new HashMap<>();
            Map<String, String> stubSources = new HashMap<>();
            TypeMethodStubGenerator stubGenerator = new TypeMethodStubGenerator();

            for (TypeBodySegments.TypeMethodSegment segment : segments.getMethods()) {
                // Extract full method source
                String fullMethodSource = cleanedSource.substring(segment.getStartPos(), segment.getEndPos());
                fullSources.put(segment.getName().toLowerCase(), fullMethodSource);

                // Generate stub
                String stubSource = stubGenerator.generateStub(fullMethodSource, segment);
                stubSources.put(segment.getName().toLowerCase(), stubSource);

                log.trace("Extracted method {}.{}.{} (full: {} chars, stub: {} chars)",
                         schema, typeName, segment.getName(),
                         fullMethodSource.length(), stubSource.length());
            }

            // STEP 4: Store in StateService (for transformation job later)
            stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

            log.debug("Stored {} methods in StateService for type {}.{}",
                     fullSources.size(), schema, typeName);

            // STEP 5: Parse stubs to extract metadata (fast, tiny memory footprint)
            // TODO: Implement stub parsing for metadata extraction in next iteration

            log.info("Extracted {} methods from type {}.{}", methods.size(), schema, typeName);

        } catch (Exception e) {
            log.error("Error scanning type body {}.{}: {}", schema, typeName, e.getMessage(), e);
        }

        return methods;
    }
}
