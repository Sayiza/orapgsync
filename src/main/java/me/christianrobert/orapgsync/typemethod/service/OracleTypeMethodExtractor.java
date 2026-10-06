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

        // PHASE 1: Query ALL_TYPE_METHODS for method signatures.
        //
        // INHERITED = 'NO' is load-bearing. Oracle puts a row on the SUBTYPE for every method it
        // inherits, and that row's SELF parameter is typed as the SUPERTYPE while the subtype's
        // body holds no source for it. Without this filter, stubs get the wrong self type and the
        // segment-vs-dictionary count check below fires on a perfectly correct type body.
        //
        // self_param_mode and result_row_count are the classification inputs. Neither METHOD_TYPE
        // nor INSTANTIABLE carries member/static or function/procedure information -- see
        // TypeMethodClassifier. MAX(param_mode) is safe: a method has at most one SELF row.
        String methodSql = """
            SELECT
                m.owner,
                m.type_name,
                m.method_name,
                m.method_type,
                m.method_no,
                m.instantiable,
                (SELECT MAX(p.param_mode) FROM all_method_params p
                  WHERE p.owner = m.owner AND p.type_name = m.type_name
                    AND p.method_name = m.method_name AND p.method_no = m.method_no
                    AND UPPER(p.param_name) = 'SELF') AS self_param_mode,
                (SELECT COUNT(*) FROM all_method_results r
                  WHERE r.owner = m.owner AND r.type_name = m.type_name
                    AND r.method_name = m.method_name AND r.method_no = m.method_no) AS result_row_count
            FROM all_type_methods m
            WHERE m.owner = ?
              AND m.inherited = 'NO'
            ORDER BY m.type_name, m.method_name, m.method_no
            """;

        try (PreparedStatement ps = connection.prepareStatement(methodSql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String owner = rs.getString("owner").toLowerCase();
                    String typeName = rs.getString("type_name").toLowerCase();
                    String methodName = rs.getString("method_name").toLowerCase();
                    String methodType = rs.getString("method_type"); // PUBLIC, MAP or ORDER
                    String methodNo = rs.getString("method_no");
                    String instantiable = rs.getString("instantiable"); // concrete vs abstract
                    String selfParamMode = rs.getString("self_param_mode"); // null when static
                    boolean hasResultRow = rs.getInt("result_row_count") > 0;

                    TypeMethodMetadata method = new TypeMethodMetadata(owner, typeName, methodName, methodType);
                    method.setMethodNo(methodNo);
                    method.setInstantiable(instantiable);
                    method.setSelfParamMode(selfParamMode);
                    method.setKind(TypeMethodClassifier.classify(
                            typeName, methodName, methodType, selfParamMode, hasResultRow));

                    // Return type comes from the result row, so ask only when there is one.
                    if (method.isFunction()) {
                        extractReturnTypeForMethod(connection, owner, typeName, methodName, methodNo, method);
                    }

                    // Extract parameters for this method (SELF excluded -- see the method)
                    extractParametersForMethod(connection, owner, typeName, methodName, methodNo, method);

                    typeMethods.add(method);

                    log.debug("Extracted type method: {} ({})", method.getDisplayName(), method.getMemberTypeDescription());
                }
            }
        }

        log.info("Extracted {} type methods from schema {} (metadata complete)", typeMethods.size(), schema);

        // Rank overloads within each name, so a method's source can be found despite Oracle
        // allowing many methods on one type to share a name.
        TypeMethodSourcePairing.assignOverloadOrdinals(typeMethods);

        Map<String, List<TypeMethodMetadata>> methodsByType = new HashMap<>();
        for (TypeMethodMetadata method : typeMethods) {
            methodsByType.computeIfAbsent(method.getTypeName().toLowerCase(), k -> new ArrayList<>())
                    .add(method);
        }

        // PHASE 2: Scan type bodies and store method sources for transformation optimization
        // This avoids re-querying and re-parsing during transformation (42x speedup for large types)
        Map<String, String> typeBodies = queryTypeBodies(connection, schema);

        log.info("Scanning {} type bodies to extract method sources for transformation", typeBodies.size());

        for (Map.Entry<String, String> entry : typeBodies.entrySet()) {
            String typeName = entry.getKey();
            String bodySource = entry.getValue();

            storeTypeMethodSources(typeName, bodySource, schema,
                    methodsByType.getOrDefault(typeName.toLowerCase(), List.of()));
        }

        log.info("Stored method sources for {} types in StateService", typeBodies.size());

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
        // Query ALL_METHOD_PARAMS to get method parameters.
        //
        // SELF is excluded. Oracle stores it as an ordinary row at param_no = 1 and counts it in
        // ALL_TYPE_METHODS.PARAMETERS, but the stub job prepends its own self parameter -- reading
        // it here too produced a duplicate parameter name in every member-method stub. Its
        // param_mode is already captured on the metadata as the mutability signal.
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
                AND UPPER(param_name) <> 'SELF'
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
     * Scans type body and stores method sources in StateService for transformation optimization.
     *
     * This is a performance optimization that avoids re-querying ALL_SOURCE and re-parsing
     * during transformation. For large type bodies (1000+ lines), this provides 42x speedup
     * and 800x memory reduction vs full ANTLR parse per method.
     *
     * Note: This does NOT extract metadata - all metadata is already complete from ALL_TYPE_METHODS
     * (Oracle requires all methods to be declared in type spec).
     *
     * @param typeName Type name
     * @param bodySource Type body source code from ALL_SOURCE
     * @param schema Schema name
     */
    private void storeTypeMethodSources(String typeName, String bodySource, String schema,
                                        List<TypeMethodMetadata> dictionaryMethods) {
        if (bodySource == null || bodySource.trim().isEmpty()) {
            log.debug("Skipping empty type body for {}.{}", schema, typeName);
            return;
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
                log.debug("No methods found in type body {}.{}", schema, typeName);
                return;
            }

            // STEP 3: Pair segments to dictionary rows before extracting anything.
            //
            // Pairing by name alone would attach one overload's body to another overload's
            // signature. Both would look plausible, so nothing downstream would catch it -- the
            // method would simply do the wrong thing at runtime. If the two sides disagree about
            // any name, store nothing for this type and say so loudly.
            TypeMethodSourcePairing.PairingResult pairing = TypeMethodSourcePairing.pair(
                    schema, typeName, dictionaryMethods, segments.getMethods());

            if (!pairing.isConsistent()) {
                log.error("Type body scan disagrees with the data dictionary for {}.{} -- storing "
                                + "no sources for this type rather than risk pairing a method's "
                                + "body to the wrong signature. Mismatches: {}",
                        schema, typeName, pairing.mismatches());
                return;
            }

            // STEP 4: Extract full methods and generate stubs, keyed by name#overloadOrdinal
            Map<String, String> fullSources = new HashMap<>();
            Map<String, String> stubSources = new HashMap<>();
            TypeMethodStubGenerator stubGenerator = new TypeMethodStubGenerator();

            for (Map.Entry<String, TypeBodySegments.TypeMethodSegment> paired
                    : pairing.segmentsByKey().entrySet()) {
                String sourceKey = paired.getKey();
                TypeBodySegments.TypeMethodSegment segment = paired.getValue();

                // Extract full method source
                String fullMethodSource = cleanedSource.substring(segment.getStartPos(), segment.getEndPos());
                fullSources.put(sourceKey, fullMethodSource);

                // Generate stub
                String stubSource = stubGenerator.generateStub(fullMethodSource, segment);
                stubSources.put(sourceKey, stubSource);

                log.trace("Stored method {}.{}.{} (full: {} chars, stub: {} chars)",
                         schema, typeName, sourceKey,
                         fullMethodSource.length(), stubSource.length());
            }

            // STEP 4: Store in StateService (for transformation job retrieval)
            stateService.storeTypeMethodSources(schema, typeName, fullSources, stubSources);

            log.debug("Stored {} method sources in StateService for type {}.{}",
                     fullSources.size(), schema, typeName);

        } catch (Exception e) {
            log.error("Error scanning type body {}.{}: {}", schema, typeName, e.getMessage(), e);
        }
    }
}
