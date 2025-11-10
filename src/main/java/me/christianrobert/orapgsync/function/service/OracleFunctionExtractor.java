package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import me.christianrobert.orapgsync.core.tools.DetailedMemoryMonitor;
import me.christianrobert.orapgsync.core.tools.MemoryMonitor;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import me.christianrobert.orapgsync.transformer.parser.FunctionBoundaryScanner;
import me.christianrobert.orapgsync.transformer.parser.FunctionStubGenerator;
import me.christianrobert.orapgsync.transformer.parser.PackageBodyReducer;
import me.christianrobert.orapgsync.transformer.parser.PackageSegments;
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
     * @param stateService State service for storing segmented package functions
     * @return List of FunctionMetadata objects
     * @throws SQLException if database operations fail
     */
    public static List<FunctionMetadata> extractAllFunctions(Connection oracleConn, List<String> schemas, StateService stateService) throws SQLException {
        List<FunctionMetadata> functionMetadataList = new ArrayList<>();

        MemoryMonitor.logMemoryUsage("Starting function extraction");

        for (String schema : schemas) {
            if (UserExcluder.is2BeExclueded(schema)) {
                continue;
            }

            long beforeSchema = MemoryMonitor.logAndGetUsedMemory("Before schema: " + schema);

            // Step 1: Extract public functions (from ALL_PROCEDURES + ALL_ARGUMENTS)
            List<FunctionMetadata> publicFunctions = fetchFunctionsForSchema(oracleConn, schema);
            functionMetadataList.addAll(publicFunctions);
            log.info("Extracted {} public functions/procedures from Oracle schema {}", publicFunctions.size(), schema);

            // Step 2: Extract private functions (from package bodies via lightweight scanner)
            List<FunctionMetadata> privateFunctions = extractPrivateFunctionsForSchema(oracleConn, schema, publicFunctions, stateService);
            functionMetadataList.addAll(privateFunctions);
            log.info("Extracted {} private functions/procedures from Oracle schema {}", privateFunctions.size(), schema);

            long afterSchema = MemoryMonitor.logAndGetUsedMemory("After schema: " + schema);
            MemoryMonitor.logMemoryDelta(beforeSchema, afterSchema, "Schema " + schema);
            MemoryMonitor.warnIfMemoryHigh(75, "After schema: " + schema);
        }

        MemoryMonitor.logMemoryUsage("Completed function extraction");
        return functionMetadataList;
    }

    /**
     * Fetches all public functions/procedures for a single schema (from ALL_PROCEDURES).
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @return List of FunctionMetadata for public functions
     * @throws SQLException if database operations fail
     */
    private static List<FunctionMetadata> fetchFunctionsForSchema(Connection oracleConn, String schema) throws SQLException {
        Map<String, FunctionMetadata> functionsMap = new HashMap<>();

        // Query ALL_PROCEDURES for standalone functions/procedures and package members
        // Note: For standalone functions/procedures, procedure_name can be NULL
        // For package members, object_type='PACKAGE' and procedure_name contains the subprogram name
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

                    // Skip if function name is null (can happen for package specs without implementations)
                    if (functionName == null || functionName.trim().isEmpty()) {
                        continue;
                    }

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
        log.debug("Extracted {} public functions/procedures from schema {}", result.size(), schema);
        return result;
    }

    /**
     * Extracts package-private functions/procedures by scanning package bodies with lightweight scanner.
     * Private functions are those declared only in the package body, not in the package spec.
     *
     * Uses streaming approach to process one package at a time to minimize memory usage.
     * Stores segmented package functions (full/stub/reduced) in StateService for later use.
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @param publicFunctions List of public functions already extracted (for filtering)
     * @param stateService State service for storing segmented package functions
     * @return List of FunctionMetadata for package-private functions
     * @throws SQLException if database operations fail
     */
    private static List<FunctionMetadata> extractPrivateFunctionsForSchema(
            Connection oracleConn, String schema, List<FunctionMetadata> publicFunctions, StateService stateService) throws SQLException {

        List<FunctionMetadata> privateFunctions = new ArrayList<>();

        // Build set of public function names for fast lookup (schema.package.function)
        Set<String> publicFunctionKeys = new HashSet<>();
        for (FunctionMetadata func : publicFunctions) {
            if (func.isPackageMember()) {
                String key = schema.toLowerCase() + "." +
                            func.getPackageName().toLowerCase() + "." +
                            func.getObjectName().toLowerCase();
                publicFunctionKeys.add(key);
            }
        }

        // Query ALL_SOURCE for all package bodies in this schema
        String sql = """
            SELECT name, text
            FROM all_source
            WHERE owner = ?
              AND type = 'PACKAGE BODY'
            ORDER BY name, line
            """;

        // Stream packages one at a time to minimize memory usage
        String currentPackageName = null;
        StringBuilder currentPackageBody = null;
        int packageCount = 0;

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String packageName = rs.getString("name");
                    String line = rs.getString("text");

                    // New package detected?
                    if (!packageName.equals(currentPackageName)) {
                        // Process the previous package if exists
                        if (currentPackageName != null && currentPackageBody != null) {
                            List<FunctionMetadata> packageFunctions = parseAndExtractPackageFunctions(
                                currentPackageName, currentPackageBody.toString(), schema, publicFunctionKeys, stateService
                            );
                            privateFunctions.addAll(packageFunctions);
                            packageCount++;

                            log.debug("Processed package {}.{} ({} private functions, total packages: {})",
                                     schema, currentPackageName, packageFunctions.size(), packageCount);

                            // Clear for GC
                            currentPackageBody = null;

                            // Detailed memory monitoring every 10 packages
                            DetailedMemoryMonitor.logPeriodicStats(packageCount);
                        }

                        // Start new package
                        currentPackageName = packageName;
                        currentPackageBody = new StringBuilder();
                    }

                    // Append current line
                    currentPackageBody.append(line);
                }

                // Process last package
                if (currentPackageName != null && currentPackageBody != null) {
                    List<FunctionMetadata> packageFunctions = parseAndExtractPackageFunctions(
                        currentPackageName, currentPackageBody.toString(), schema, publicFunctionKeys, stateService
                    );
                    privateFunctions.addAll(packageFunctions);
                    packageCount++;

                    log.debug("Processed package {}.{} ({} private functions, total packages: {})",
                             schema, currentPackageName, packageFunctions.size(), packageCount);
                }
            }
        }

        log.info("Completed processing {} package bodies in schema {}", packageCount, schema);

        // Final detailed memory report
        DetailedMemoryMonitor.logDetailedMemory("After processing all packages in schema " + schema);
        DetailedMemoryMonitor.forceGCAndLog("End of schema " + schema);

        return privateFunctions;
    }

    /**
     * Scans a single package body and extracts private functions using lightweight scanner.
     * Stores segmented package data (full/stub/reduced) in StateService for later use.
     * Separated into its own method to enable streaming and improve GC.
     *
     * NEW APPROACH (Package Segmentation):
     * 1. Clean source (remove comments)
     * 2. Scan function boundaries (FunctionBoundaryScanner)
     * 3. Extract full functions + generate stubs + generate reduced body
     * 4. Store in StateService (for transformation job)
     * 5. Parse stubs (tiny, fast) to extract metadata
     * 6. Return only private functions
     *
     * @param packageName Name of the package
     * @param bodySource Package body source code (without CREATE OR REPLACE)
     * @param schema Schema name
     * @param publicFunctionKeys Set of public function keys for filtering
     * @param stateService State service for storing segmented package data
     * @return List of FunctionMetadata for private functions in this package
     */
    private static List<FunctionMetadata> parseAndExtractPackageFunctions(
            String packageName, String bodySource, String schema, Set<String> publicFunctionKeys, StateService stateService) {

        List<FunctionMetadata> functions = new ArrayList<>();

        if (bodySource == null || bodySource.trim().isEmpty()) {
            return functions;
        }

        long beforeScan = MemoryMonitor.logAndGetUsedMemory("Before scanning package: " + schema + "." + packageName);

        // Prepend CREATE OR REPLACE for parsing (Oracle doesn't store it in ALL_SOURCE)
        String fullSource = "CREATE OR REPLACE " + bodySource.trim();

        try {
            // STEP 1: Clean source (remove comments)
            String cleanedSource = CodeCleaner.removeComments(fullSource);
            log.trace("Cleaned package {}.{} ({} chars -> {} chars)",
                     schema, packageName, fullSource.length(), cleanedSource.length());

            // STEP 2: Scan function boundaries (fast, lightweight)
            FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();
            PackageSegments segments = scanner.scanPackageBody(cleanedSource);

            log.debug("Scanned package {}.{}: found {} functions/procedures",
                     schema, packageName, segments.getFunctionCount());

            if (segments.getFunctionCount() == 0) {
                // No functions in this package
                return functions;
            }

            // STEP 3: Extract full functions and generate stubs
            Map<String, String> fullSources = new HashMap<>();
            Map<String, String> stubSources = new HashMap<>();
            FunctionStubGenerator stubGenerator = new FunctionStubGenerator();

            for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
                // Extract full function source
                String fullFunctionSource = cleanedSource.substring(segment.getStartPos(), segment.getEndPos());
                fullSources.put(segment.getName().toLowerCase(), fullFunctionSource);

                // Generate stub
                String stubSource = stubGenerator.generateStub(fullFunctionSource, segment);
                stubSources.put(segment.getName().toLowerCase(), stubSource);

                log.trace("Extracted function {}.{}.{} (full: {} chars, stub: {} chars)",
                         schema, packageName, segment.getName(),
                         fullFunctionSource.length(), stubSource.length());
            }

            // STEP 4: Generate reduced package body (all functions removed)
            PackageBodyReducer reducer = new PackageBodyReducer();
            String reducedBody = reducer.removeAllFunctions(cleanedSource, segments);

            log.debug("Generated reduced package body for {}.{} ({} chars -> {} chars, {} functions removed)",
                     schema, packageName, cleanedSource.length(), reducedBody.length(), segments.getFunctionCount());

            // STEP 5: Store in StateService (for transformation job later)
            stateService.storePackageFunctions(schema, packageName, fullSources, stubSources, reducedBody);

            log.debug("Stored {} functions in StateService for package {}.{}",
                     fullSources.size(), schema, packageName);

            // STEP 6: Parse stubs to extract metadata (fast, tiny memory footprint)
            me.christianrobert.orapgsync.transformer.parser.AntlrParser parser =
                new me.christianrobert.orapgsync.transformer.parser.AntlrParser();

            for (Map.Entry<String, String> stubEntry : stubSources.entrySet()) {
                String functionName = stubEntry.getKey();
                String stubSource = stubEntry.getValue();

                try {
                    // Parse stub (tiny, <1ms per stub)
                    // Note: Stub already starts with FUNCTION/PROCEDURE keyword (no CREATE OR REPLACE needed)
                    me.christianrobert.orapgsync.transformer.parser.ParseResult stubParseResult =
                        parser.parseFunctionBody(stubSource);

                    if (stubParseResult.hasErrors()) {
                        log.warn("Failed to parse stub for {}.{}.{}: {}",
                                schema, packageName, functionName, stubParseResult.getErrorMessage());
                        continue;
                    }

                    // Check if this is a private function (not in public list)
                    String key = schema.toLowerCase() + "." + packageName.toLowerCase() + "." + functionName;
                    if (!publicFunctionKeys.contains(key)) {
                        // Extract metadata from stub
                        FunctionMetadata metadata = extractMetadataFromFunctionStub(
                            stubParseResult.getTree(), schema, packageName);
                        if (metadata != null) {
                            metadata.setPackagePrivate(true);
                            functions.add(metadata);
                            log.trace("Extracted private function metadata: {}.{}.{}", schema, packageName, functionName);
                        }
                    }

                } catch (Exception e) {
                    log.warn("Error parsing stub for {}.{}.{}: {}", schema, packageName, functionName, e.getMessage());
                }
            }

            long afterScan = MemoryMonitor.logAndGetUsedMemory("After scanning package: " + schema + "." + packageName);
            MemoryMonitor.logMemoryDelta(beforeScan, afterScan, "Package scan: " + schema + "." + packageName);

        } catch (Exception e) {
            log.error("Error scanning package body {}.{}: {}", schema, packageName, e.getMessage(), e);
        }

        return functions;
    }

    /**
     * Extracts function/procedure metadata from a parsed stub AST.
     * Used for package-private functions after scanning.
     *
     * Handles both CREATE FUNCTION and CREATE PROCEDURE stubs.
     *
     * @param parseTree Parsed stub AST (can be function or procedure)
     * @param schema Schema name
     * @param packageName Package name
     * @return FunctionMetadata or null if extraction fails
     */
    private static FunctionMetadata extractMetadataFromFunctionStub(
            org.antlr.v4.runtime.tree.ParseTree parseTree,
            String schema, String packageName) {

        if (parseTree == null) {
            return null;
        }

        // Try as function first
        if (parseTree instanceof me.christianrobert.orapgsync.antlr.PlSqlParser.Create_function_bodyContext) {
            me.christianrobert.orapgsync.antlr.PlSqlParser.Create_function_bodyContext funcCtx =
                (me.christianrobert.orapgsync.antlr.PlSqlParser.Create_function_bodyContext) parseTree;

            // Extract function name from function_name rule
            String functionName = funcCtx.function_name().getText().toLowerCase();

            // Create metadata
            FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), functionName, "FUNCTION");
            metadata.setPackageName(packageName.toLowerCase());

            // Extract return type
            if (funcCtx.type_spec() != null) {
                String returnType = extractTypeFromTypeSpec(funcCtx.type_spec());
                metadata.setReturnDataType(returnType);
                log.trace("Extracted return type '{}' for private function: {}.{}.{}",
                         returnType, schema, packageName, functionName);
            }

            return metadata;
        }

        // Try as procedure
        if (parseTree instanceof me.christianrobert.orapgsync.antlr.PlSqlParser.Create_procedure_bodyContext) {
            me.christianrobert.orapgsync.antlr.PlSqlParser.Create_procedure_bodyContext procCtx =
                (me.christianrobert.orapgsync.antlr.PlSqlParser.Create_procedure_bodyContext) parseTree;

            // Extract procedure name from procedure_name rule
            String procedureName = procCtx.procedure_name().getText().toLowerCase();

            // Create metadata
            FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), procedureName, "PROCEDURE");
            metadata.setPackageName(packageName.toLowerCase());

            return metadata;
        }

        log.warn("Unknown stub parse tree type: {}", parseTree.getClass().getName());
        return null;
    }

    /**
     * Extracts the Oracle data type string from a type_spec AST node.
     *
     * @param typeSpecCtx The type_spec AST node
     * @return The Oracle type string (e.g., "NUMBER", "VARCHAR2", etc.)
     */
    private static String extractTypeFromTypeSpec(me.christianrobert.orapgsync.antlr.PlSqlParser.Type_specContext typeSpecCtx) {
        if (typeSpecCtx == null) {
            return null;
        }

        // type_spec can be: datatype | type_name
        if (typeSpecCtx.datatype() != null) {
            // Built-in type (NUMBER, VARCHAR2, DATE, etc.)
            me.christianrobert.orapgsync.antlr.PlSqlParser.DatatypeContext datatypeCtx = typeSpecCtx.datatype();

            if (datatypeCtx.native_datatype_element() != null) {
                // Extract the base type name
                me.christianrobert.orapgsync.antlr.PlSqlParser.Native_datatype_elementContext nativeType =
                    datatypeCtx.native_datatype_element();

                // Get the type name (NUMBER, VARCHAR2, etc.)
                return nativeType.getText().toUpperCase();
            } else if (datatypeCtx.INTERVAL() != null) {
                // INTERVAL type
                return "INTERVAL";
            }
        } else if (typeSpecCtx.type_name() != null) {
            // User-defined type (custom type or ROWTYPE)
            // For now, return the text representation
            return typeSpecCtx.type_name().getText().toUpperCase();
        }

        // Fallback: return the entire text
        return typeSpecCtx.getText().toUpperCase();
    }
}
