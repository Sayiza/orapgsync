package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.job.model.function.PackageTypeMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.core.tools.DetailedMemoryMonitor;
import me.christianrobert.orapgsync.core.tools.MemoryMonitor;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.FunctionBoundaryScanner;
import me.christianrobert.orapgsync.transformer.parser.FunctionStubGenerator;
import me.christianrobert.orapgsync.transformer.parser.PackageBodyReducer;
import me.christianrobert.orapgsync.transformer.parser.PackageSegments;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
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
     * Reused across every package spec. {@link AntlrParser} and {@link PackageContextExtractor}
     * hold no per-parse state — the tree lives in the returned {@link PackageContext}, which this
     * class discards — so one instance serves the whole run rather than one per package.
     */
    private static final AntlrParser BODY_PARSER = new AntlrParser();

    private static final PackageContextExtractor SPEC_TYPE_EXTRACTOR =
            new PackageContextExtractor(BODY_PARSER);

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

            // Step 3: Extract the types each package declares, so every later job places a pkg.type
            // reference the same way. Reads the reduced bodies step 2 stored, for body-declared
            // types; otherwise independent of steps 1 and 2 and of their outcome.
            extractPackageSpecTypesForSchema(oracleConn, schema, stateService);

            long afterSchema = MemoryMonitor.logAndGetUsedMemory("After schema: " + schema);
            MemoryMonitor.logMemoryDelta(beforeSchema, afterSchema, "Schema " + schema);
            MemoryMonitor.warnIfMemoryHigh(75, "After schema: " + schema);
        }

        // Sort all functions for deterministic ordering (schema, package, function name)
        // This ensures consistent ordering between runs, especially for private functions
        // which are extracted from package bodies and can vary in encounter order
        functionMetadataList.sort(Comparator
            .comparing(FunctionMetadata::getSchema)
            .thenComparing(f -> f.getPackageName() != null ? f.getPackageName() : "")
            .thenComparing(FunctionMetadata::getObjectName)
            .thenComparingInt(FunctionMetadata::getOverloadNumber));

        log.info("Sorted {} functions/procedures for deterministic ordering", functionMetadataList.size());

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
                type_subname,
                data_level,
                defaulted
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
                    String typeSubname = rs.getString("type_subname");
                    int dataLevel = rs.getInt("data_level");
                    boolean defaulted = "Y".equalsIgnoreCase(rs.getString("defaulted"));

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
                            metadata.setReturnTypeOwner(ownerOf(typeOwner, typeName, typeSubname));
                            metadata.setReturnTypeName(nameOf(typeName, typeSubname));
                        }
                    } else if (argumentName != null) {
                        // This is a parameter
                        FunctionParameter param = new FunctionParameter(
                            argumentName.toLowerCase(),
                            position,
                            dataType,
                            inOut != null ? inOut : "IN"
                        );
                        param.setDefaulted(defaulted);

                        // Check if parameter type is custom
                        if (typeOwner != null && typeName != null) {
                            param.setCustomDataType(true);
                            param.setDataTypeOwner(ownerOf(typeOwner, typeName, typeSubname));
                            param.setDataTypeName(nameOf(typeName, typeSubname));
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

        // Public members per package, for pairing each body with its dictionary overload
        Map<String, List<FunctionMetadata>> publicMembersByPackage = new HashMap<>();
        for (FunctionMetadata func : publicFunctions) {
            if (func.isPackageMember()) {
                publicMembersByPackage.computeIfAbsent(func.getPackageName().toLowerCase(), k -> new ArrayList<>()).add(func);
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
                                currentPackageName, currentPackageBody.toString(), schema,
                                publicMembersByPackage.getOrDefault(currentPackageName.toLowerCase(), List.of()), stateService
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
                        currentPackageName, currentPackageBody.toString(), schema,
                                publicMembersByPackage.getOrDefault(currentPackageName.toLowerCase(), List.of()), stateService
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
     * Extracts the types and subtypes declared by every package <b>specification</b> in a schema.
     *
     * <p>Streams one spec at a time, like the body pass above, and for the same reason.
     *
     * <p><b>A spec that does not parse is skipped, not fatal.</b> This pass is building an index
     * for other jobs to consult; a package missing from it degrades to exactly the behaviour that
     * existed before the index — the type is unplaceable and the caller refuses — whereas failing
     * extraction here would take the whole function job down with it. The failure is still visible:
     * every consumer that needed one of those types reports it by name.
     */
    private static void extractPackageSpecTypesForSchema(
            Connection oracleConn, String schema, StateService stateService) throws SQLException {

        String sql = """
            SELECT name, text
            FROM all_source
            WHERE owner = ?
              AND type = 'PACKAGE'
            ORDER BY name, line
            """;

        String currentPackageName = null;
        StringBuilder currentSpec = null;
        int indexed = 0;
        int unparseable = 0;

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String packageName = rs.getString("name");
                    String line = rs.getString("text");

                    if (!packageName.equals(currentPackageName)) {
                        if (currentPackageName != null) {
                            if (indexPackageSpecTypes(schema, currentPackageName,
                                    currentSpec.toString(), stateService)) {
                                indexed++;
                            } else {
                                unparseable++;
                            }
                        }
                        currentPackageName = packageName;
                        currentSpec = new StringBuilder();
                    }

                    currentSpec.append(line);
                }

                if (currentPackageName != null) {
                    if (indexPackageSpecTypes(schema, currentPackageName,
                            currentSpec.toString(), stateService)) {
                        indexed++;
                    } else {
                        unparseable++;
                    }
                }
            }
        }

        log.info("Indexed package spec types for schema {}: {} packages declare types, {} specs did not parse",
                 schema, indexed, unparseable);
    }

    /**
     * Parses one package spec and records what it declares, together with what the package body
     * declares at package level.
     *
     * <p>The body is read from the reduced body step 2 stored — the package with its members cut
     * out, which is all that holds package-level declarations and is small to parse. Body types are
     * recorded as such, visible only to lookups from inside the package. They are what a
     * package-private member's signature names, and without them its stub could not be created
     * (ROADMAP P1.5). A body that does not parse costs only its own entries: the spec's are still
     * indexed, and the implementation job refuses that package by name anyway.
     *
     * <p>Package-private so a test can drive spec source → stored metadata without a database.
     *
     * @return false if the spec could not be parsed, true otherwise (including when it parses
     *         cleanly but declares no types at all)
     */
    static boolean indexPackageSpecTypes(String schema, String packageName,
                                         String specSource, StateService stateService) {
        if (specSource == null || specSource.trim().isEmpty()) {
            return true;
        }

        // Oracle does not store CREATE OR REPLACE in ALL_SOURCE; the grammar expects it.
        String fullSource = "CREATE OR REPLACE " + specSource.trim();

        try {
            // The context is discarded immediately; only the small PackageTypeMetadata is kept,
            // so the spec's parse tree is garbage before the next package is read. That is what
            // makes indexing every package in the database affordable.
            PackageContext context = SPEC_TYPE_EXTRACTOR.extractContext(schema, packageName, fullSource);

            PackageTypeMetadata types = new PackageTypeMetadata(schema, packageName);
            context.getSubtypes().forEach(types::addSubtype);
            context.getTypes().keySet().forEach(types::addStructuredType);
            indexPackageBodyTypes(schema, packageName, types, stateService);
            stateService.storePackageTypes(types);
            return true;
        } catch (Exception e) {
            // Includes the wrapped packages, whose "spec" is a blob of encrypted text.
            log.debug("Package spec did not parse, types not indexed for {}.{}: {}",
                      schema, packageName, e.getMessage());
            return false;
        }
    }

    /**
     * Adds the package-level types of a package's reduced body to {@code types}, if the body was
     * stored and parses. Never throws: this fills an index, and a missing entry degrades to the
     * caller refusing a type by name.
     */
    private static void indexPackageBodyTypes(String schema, String packageName,
                                              PackageTypeMetadata types, StateService stateService) {
        String reducedBody = stateService.getReducedPackageBody(schema, packageName);
        if (reducedBody == null || reducedBody.isBlank()) {
            return;
        }
        try {
            ParseResult parsed = BODY_PARSER.parsePackageBody(reducedBody);
            if (parsed.hasErrors()) {
                // Never build on a failed parse: a recovered tree holds invented declarations.
                log.debug("Reduced package body did not parse, body types not indexed for {}.{}: {}",
                          schema, packageName, parsed.getErrorMessage());
                return;
            }
            PackageContext bodyDeclarations = new PackageContext(schema, packageName);
            SPEC_TYPE_EXTRACTOR.extractBodyDeclarations(
                    (PlSqlParser.Create_package_bodyContext) parsed.getTree(), bodyDeclarations);
            bodyDeclarations.getSubtypes().forEach(types::addBodySubtype);
            bodyDeclarations.getTypes().keySet().forEach(types::addBodyStructuredType);
        } catch (Exception e) {
            log.debug("Body types not indexed for {}.{}: {}", schema, packageName, e.getMessage());
        }
    }

    /**
     * Scans a single package body, pairs each body with its dictionary overload, and returns the
     * package-private members. Stores each name's full sources and the reduced body in StateService.
     *
     * <ol>
     *   <li>Scan member boundaries ({@link FunctionBoundaryScanner}).</li>
     *   <li>Parse every member's header (its stub, so the body is never parsed here).</li>
     *   <li>Per name, pair bodies with the public overloads of that name ({@link FunctionSourcePairing})
     *       and record on each public member which body is its source, or why none is.</li>
     *   <li>Bodies no public overload claims are the private members.</li>
     * </ol>
     *
     * <p>Sources are stored per name <b>as a list in body order</b>, never keyed by name alone:
     * a package may declare many members under one name, and a name-keyed map kept only the last.
     *
     * @param packageName   name of the package
     * @param bodySource    package body source code (without CREATE OR REPLACE)
     * @param schema        schema name
     * @param publicMembers this package's members from the data dictionary; gain a source ordinal
     *                      or a pairing error
     * @param stateService  state service for storing segmented package data
     * @return FunctionMetadata for the package-private members
     *
     * <p>Package-private so a test can drive the real extraction of a private member's signature.
     */
    static List<FunctionMetadata> parseAndExtractPackageFunctions(
            String packageName, String bodySource, String schema, List<FunctionMetadata> publicMembers,
            StateService stateService) {

        List<FunctionMetadata> functions = new ArrayList<>();

        if (bodySource == null || bodySource.trim().isEmpty()) {
            return functions;
        }

        long beforeScan = MemoryMonitor.logAndGetUsedMemory("Before scanning package: " + schema + "." + packageName);

        // Prepend CREATE OR REPLACE for parsing (Oracle doesn't store it in ALL_SOURCE)
        String fullSource = "CREATE OR REPLACE " + bodySource.trim();

        try {
            // STEP 1: Scan function boundaries (fast, lightweight)
            // Note: Scanner uses ANTLR lexer which handles comments automatically
            FunctionBoundaryScanner scanner = new FunctionBoundaryScanner();
            PackageSegments segments = scanner.scanPackageBody(fullSource);

            log.debug("Scanned package {}.{}: found {} functions/procedures",
                     schema, packageName, segments.getFunctionCount());

            if (segments.getFunctionCount() == 0) {
                // No functions in this package
                return functions;
            }

            // STEP 2: Per name, every body in body order: its full source and its parsed header
            Map<String, List<String>> fullSources = new LinkedHashMap<>();
            Map<String, List<FunctionMetadata>> headers = new LinkedHashMap<>();
            FunctionStubGenerator stubGenerator = new FunctionStubGenerator();
            AntlrParser parser = new AntlrParser();

            for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
                String name = memberKey(segment.getName());
                String fullFunctionSource = fullSource.substring(segment.getStartPos(), segment.getEndPos());
                fullSources.computeIfAbsent(name, k -> new ArrayList<>()).add(fullFunctionSource);
                String stubSource = stubGenerator.generateStub(fullFunctionSource, segment);
                headers.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(parseHeader(parser, stubSource, segment.isFunction(), schema, packageName, name));
            }

            // STEP 3: Generate reduced package body (all functions removed)
            PackageBodyReducer reducer = new PackageBodyReducer();
            String reducedBody = reducer.removeAllFunctions(fullSource, segments);

            log.debug("Generated reduced package body for {}.{} ({} chars -> {} chars, {} functions removed)",
                     schema, packageName, fullSource.length(), reducedBody.length(), segments.getFunctionCount());

            // STEP 4: Store in StateService (for transformation job later)
            stateService.storePackageFunctions(schema, packageName, fullSources, reducedBody);

            // STEP 5: Pair bodies with public overloads, name by name; what is left is private
            Map<String, List<FunctionMetadata>> publicByName = new HashMap<>();
            for (FunctionMetadata member : publicMembers) {
                publicByName.computeIfAbsent(memberKey(member.getObjectName()), k -> new ArrayList<>()).add(member);
            }

            for (Map.Entry<String, List<FunctionMetadata>> entry : headers.entrySet()) {
                String name = entry.getKey();
                List<FunctionMetadata> bodies = entry.getValue();
                List<FunctionMetadata> overloads = publicByName.getOrDefault(name, List.of());

                FunctionSourcePairing.Result pairing = FunctionSourcePairing.pair(overloads, bodies);
                for (int i = 0; i < overloads.size(); i++) {
                    overloads.get(i).setSourceOrdinal(pairing.ordinals()[i]);
                    overloads.get(i).setSourcePairingError(pairing.errors()[i]);
                }
                if (pairing.unaccounted() > 0) {
                    log.warn("Package {}.{}: {} bodies named {} are neither paired with a specification overload nor "
                            + "known to be private, because an overload could not be paired", schema, packageName,
                            pairing.unaccounted(), name);
                }

                // Private overloads are numbered after the public ones, so every member of a name
                // has its own number wherever a name holds more than one.
                int nextOverload = overloads.stream().mapToInt(FunctionMetadata::getOverloadNumber).max().orElse(0) + 1;
                boolean numbered = overloads.size() + pairing.privateOrdinals().size() > 1;
                for (int ordinal : pairing.privateOrdinals()) {
                    FunctionMetadata metadata = bodies.get(ordinal);
                    metadata.setPackagePrivate(true);
                    metadata.setSourceOrdinal(ordinal);
                    if (numbered) {
                        metadata.setOverloadNumber(nextOverload++);
                    }
                    functions.add(metadata);
                    log.trace("Extracted private function metadata: {}.{}.{}", schema, packageName, name);
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
     * The key a member's bodies and overloads are grouped under: the name as the dictionary stores
     * it, lower-cased, so a quoted name in the source ({@code "Get Value"}) meets its dictionary row.
     */
    private static String memberKey(String name) {
        String s = name.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase();
    }

    /**
     * Parses one member's stub into its signature, or {@code null} if the header does not parse.
     * Never throws: a header that does not parse leaves its overload unpaired, which is reported.
     */
    private static FunctionMetadata parseHeader(AntlrParser parser, String stubSource, boolean isFunction,
                                                String schema, String packageName, String name) {
        try {
            ParseResult result = isFunction
                    ? parser.parseFunctionBody(stubSource)
                    : parser.parseProcedureBody(stubSource);
            if (result.hasErrors()) {
                log.warn("Failed to parse stub for {}.{}.{}: {}", schema, packageName, name, result.getErrorMessage());
                return null;
            }
            return extractMetadataFromFunctionStub(result.getTree(), schema, packageName);
        } catch (Exception e) {
            log.warn("Error parsing stub for {}.{}.{}: {}", schema, packageName, name, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts function/procedure metadata from a parsed stub AST.
     * Used for package-private functions after scanning.
     *
     * Handles both:
     * - function_body / procedure_body (from parseFunctionBody/parseProcedureBody)
     * - create_function_body / create_procedure_body (with CREATE OR REPLACE prefix)
     *
     * The key difference is the function name accessor:
     * - function_body uses identifier() for name
     * - create_function_body uses function_name() for name
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

        // Try as function_body (no CREATE prefix - from parseFunctionBody)
        // Grammar: function_body: FUNCTION identifier ('(' parameter (',' parameter)* ')')? RETURN type_spec ...
        if (parseTree instanceof me.christianrobert.orapgsync.antlr.PlSqlParser.Function_bodyContext) {
            me.christianrobert.orapgsync.antlr.PlSqlParser.Function_bodyContext funcCtx =
                (me.christianrobert.orapgsync.antlr.PlSqlParser.Function_bodyContext) parseTree;

            // Extract function name from identifier rule (not function_name!)
            String functionName = memberKey(funcCtx.identifier().getText());

            // Create metadata
            FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), functionName, "FUNCTION");
            metadata.setPackageName(packageName.toLowerCase());

            // Extract parameters
            extractParametersFromContext(funcCtx.parameter(), metadata);

            // Extract return type
            if (funcCtx.type_spec() != null) {
                String returnType = extractTypeFromTypeSpec(funcCtx.type_spec());
                metadata.setReturnDataType(returnType);
                log.trace("Extracted return type '{}' for private function: {}.{}.{}",
                         returnType, schema, packageName, functionName);
            }

            return metadata;
        }

        // Try as procedure_body (no CREATE prefix - from parseProcedureBody)
        // Grammar: procedure_body: PROCEDURE identifier ('(' parameter (',' parameter)* ')')? ...
        if (parseTree instanceof me.christianrobert.orapgsync.antlr.PlSqlParser.Procedure_bodyContext) {
            me.christianrobert.orapgsync.antlr.PlSqlParser.Procedure_bodyContext procCtx =
                (me.christianrobert.orapgsync.antlr.PlSqlParser.Procedure_bodyContext) parseTree;

            // Extract procedure name from identifier rule (not procedure_name!)
            String procedureName = memberKey(procCtx.identifier().getText());

            // Create metadata
            FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), procedureName, "PROCEDURE");
            metadata.setPackageName(packageName.toLowerCase());

            // Extract parameters
            extractParametersFromContext(procCtx.parameter(), metadata);

            return metadata;
        }

        // Try as create_function_body (with CREATE prefix)
        // Grammar: create_function_body: CREATE (OR REPLACE)? ... FUNCTION function_name ('(' parameter (',' parameter)* ')')? ...
        if (parseTree instanceof me.christianrobert.orapgsync.antlr.PlSqlParser.Create_function_bodyContext) {
            me.christianrobert.orapgsync.antlr.PlSqlParser.Create_function_bodyContext funcCtx =
                (me.christianrobert.orapgsync.antlr.PlSqlParser.Create_function_bodyContext) parseTree;

            // Extract function name from function_name rule
            String functionName = memberKey(funcCtx.function_name().getText());

            // Create metadata
            FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), functionName, "FUNCTION");
            metadata.setPackageName(packageName.toLowerCase());

            // Extract parameters
            extractParametersFromContext(funcCtx.parameter(), metadata);

            // Extract return type
            if (funcCtx.type_spec() != null) {
                String returnType = extractTypeFromTypeSpec(funcCtx.type_spec());
                metadata.setReturnDataType(returnType);
                log.trace("Extracted return type '{}' for private function: {}.{}.{}",
                         returnType, schema, packageName, functionName);
            }

            return metadata;
        }

        // Try as create_procedure_body (with CREATE prefix)
        // Grammar: create_procedure_body: CREATE (OR REPLACE)? PROCEDURE procedure_name ('(' parameter (',' parameter)* ')')? ...
        if (parseTree instanceof me.christianrobert.orapgsync.antlr.PlSqlParser.Create_procedure_bodyContext) {
            me.christianrobert.orapgsync.antlr.PlSqlParser.Create_procedure_bodyContext procCtx =
                (me.christianrobert.orapgsync.antlr.PlSqlParser.Create_procedure_bodyContext) parseTree;

            // Extract procedure name from procedure_name rule
            String procedureName = memberKey(procCtx.procedure_name().getText());

            // Create metadata
            FunctionMetadata metadata = new FunctionMetadata(schema.toLowerCase(), procedureName, "PROCEDURE");
            metadata.setPackageName(packageName.toLowerCase());

            // Extract parameters
            extractParametersFromContext(procCtx.parameter(), metadata);

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
            // User-defined type, or an anchor. The anchor is a sibling token of type_name, not part
            // of it, and dropping it turns `term_def.nr%TYPE` ("the type of this column") into
            // `TERM_DEF.NR` — a package type that does not exist. TypeResolver keeps a %TYPE
            // anchor and qualifies its table; a %ROWTYPE is refused there by name.
            String typeName = typeSpecCtx.type_name().getText().toUpperCase();
            if (typeSpecCtx.PERCENT_TYPE() != null) {
                return typeName + "%TYPE";
            }
            if (typeSpecCtx.PERCENT_ROWTYPE() != null) {
                return typeName + "%ROWTYPE";
            }
            return typeName;
        }

        // Fallback: return the entire text
        return typeSpecCtx.getText().toUpperCase();
    }

    /**
     * The qualifier half of a custom type, read off {@code ALL_ARGUMENTS}.
     *
     * <p>The dictionary uses the same three columns for two different shapes, and only
     * {@code TYPE_SUBNAME} tells them apart:
     *
     * <ul>
     *   <li><b>Schema-level object type</b> — {@code TYPE_OWNER=HR}, {@code TYPE_NAME=ADDRESS_TYPE},
     *       {@code TYPE_SUBNAME} null. The pair composes to {@code hr.address_type}, the composite
     *       type the DDL migration created.</li>
     *   <li><b>Package-declared type</b> — {@code TYPE_OWNER=MAIN_APP}, {@code TYPE_NAME=UICOMPONENT}
     *       (the <i>package</i>), {@code TYPE_SUBNAME=COMPONENT}. Reading only the first two
     *       columns composes {@code main_app.uicomponent}, which names a package as if it were a
     *       type and reaches PostgreSQL as {@code schema "uicomponent" does not exist}.</li>
     * </ul>
     *
     * <p>For the second shape the pair is composed as {@code package.type} — the spelling the
     * source itself uses, and therefore the one {@code TransformationContext} already knows how to
     * resolve. The owning schema is not lost: a package type only ever appears on a member of that
     * same package, whose schema is already the resolution context.
     */
    private static String ownerOf(String typeOwner, String typeName, String typeSubname) {
        return typeSubname != null
                ? typeName.toLowerCase()
                : typeOwner.toLowerCase();
    }

    /** @see #ownerOf */
    private static String nameOf(String typeName, String typeSubname) {
        return typeSubname != null
                ? typeSubname.toLowerCase()
                : typeName.toLowerCase();
    }

    /**
     * Extracts parameters from a list of ANTLR ParameterContext nodes.
     * Used for package-private functions where parameters are parsed from the stub.
     *
     * Grammar: parameter: parameter_name (IN | OUT | INOUT | NOCOPY)* type_spec? default_value_part?
     *
     * @param parameterContexts List of ParameterContext nodes from the parsed function/procedure
     * @param metadata The FunctionMetadata to add parameters to
     */
    private static void extractParametersFromContext(
            List<me.christianrobert.orapgsync.antlr.PlSqlParser.ParameterContext> parameterContexts,
            FunctionMetadata metadata) {

        if (parameterContexts == null || parameterContexts.isEmpty()) {
            return;
        }

        int position = 1; // Oracle uses 1-based positions
        for (me.christianrobert.orapgsync.antlr.PlSqlParser.ParameterContext paramCtx : parameterContexts) {
            // Extract parameter name
            String paramName = paramCtx.parameter_name().getText().toLowerCase();

            // Extract IN/OUT mode
            // Grammar allows: (IN | OUT | INOUT | NOCOPY)*
            String inOut = "IN"; // Default is IN
            if (paramCtx.OUT() != null && !paramCtx.OUT().isEmpty()) {
                if (paramCtx.IN() != null && !paramCtx.IN().isEmpty()) {
                    inOut = "IN OUT";
                } else if (paramCtx.INOUT() != null && !paramCtx.INOUT().isEmpty()) {
                    inOut = "IN OUT";
                } else {
                    inOut = "OUT";
                }
            } else if (paramCtx.INOUT() != null && !paramCtx.INOUT().isEmpty()) {
                inOut = "IN OUT";
            }

            // Extract data type
            String dataType = null;
            if (paramCtx.type_spec() != null) {
                dataType = extractTypeFromTypeSpec(paramCtx.type_spec());
            }

            // Create and add parameter
            FunctionParameter param = new FunctionParameter(paramName, position, dataType, inOut);
            param.setDefaulted(paramCtx.default_value_part() != null);

            // Check for custom (user-defined) types.
            //
            // An anchored type is deliberately left alone. `semester_def.nr%TYPE` splits into a
            // perfectly plausible-looking owner and name, and doing so drops the `%TYPE` — which
            // turns "the type of this column" into "a type named nr owned by semester_def", a type
            // that does not exist. The full text is already in dataType, where TypeResolver can
            // qualify the table and keep the anchor.
            if (paramCtx.type_spec() != null
                    && paramCtx.type_spec().type_name() != null
                    && paramCtx.type_spec().PERCENT_TYPE() == null
                    && paramCtx.type_spec().PERCENT_ROWTYPE() == null) {
                me.christianrobert.orapgsync.antlr.PlSqlParser.Type_nameContext typeName = paramCtx.type_spec().type_name();
                // type_name can be: id_expression ('.' id_expression)*
                // For user-defined types like SCHEMA.TYPE_NAME
                String typeText = typeName.getText();
                if (typeText.contains(".")) {
                    String[] parts = typeText.split("\\.", 2);
                    param.setCustomDataType(true);
                    param.setDataTypeOwner(parts[0].toLowerCase());
                    param.setDataTypeName(parts[1].toLowerCase());
                }
            }

            metadata.addParameter(param);
            log.trace("Extracted parameter: {} {} {} (position {})", paramName, inOut, dataType, position);
            position++;
        }
    }
}
