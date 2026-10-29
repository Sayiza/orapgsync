package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionImplementationResult;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageHelperGenerator;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * PostgreSQL Function Implementation Job (Unified).
 *
 * This job implements BOTH standalone functions/procedures AND package member functions/procedures
 * by transforming Oracle PL/SQL to PostgreSQL PL/pgSQL and replacing stub implementations.
 *
 * For PACKAGE FUNCTIONS, the job follows a unified on-demand approach:
 * 1. Detects package membership (via "__" in function name)
 * 2. Parses package spec from Oracle (if not already cached)
 * 3. Generates helper functions (initialize, getters, setters) in PostgreSQL
 * 4. Caches package context for remaining functions in same package
 *
 * The job:
 * 1. Retrieves Oracle function metadata from state (both standalone and package members)
 * 2. For each function:
 *    a. If package function: ensure package context exists (parse spec, generate helpers)
 *    b. Extract Oracle PL/SQL source from ALL_SOURCE
 *    c. Transform PL/SQL → PL/pgSQL using ANTLR
 *    d. Execute CREATE OR REPLACE FUNCTION in PostgreSQL
 * 3. Returns results tracking success/skipped/errors
 *
 * Package context is ephemeral - exists only during job execution, cached in-memory.
 * Maintains ANTLR-only-in-transformation pattern (no ANTLR in extraction jobs).
 */
@Dependent
public class PostgresFunctionImplementationJob extends AbstractDatabaseWriteJob<FunctionImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresFunctionImplementationJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private TransformationService transformationService;

    @Inject
    private AntlrParser antlrParser;

    // Metadata indices for this run, also read by buildPackageContext so the package variable
    // helpers it generates resolve types the same way the function bodies do.
    private TransformationIndices indices;

    // Package context cache (ephemeral, per-job execution)
    // Caches parsed package specifications to avoid re-parsing for each function
    private final Map<String, PackageContext> packageContextCache = new HashMap<>();

    // Packages whose context could not be built, keyed the same way, holding the reason.
    // The negative half of the cache above: without it a failing package is retried, and re-fails,
    // once per member function. See ensurePackageContext.
    //
    // Package-private, like ensurePackageContext itself, so PostgresFunctionImplementationJobTest
    // can seed a known failure and assert the retry never happens -- without a database.
    final Map<String, String> packageContextFailures = new HashMap<>();

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "STANDALONE_FUNCTION_IMPLEMENTATION";
    }

    @Override
    public Class<FunctionImplementationResult> getResultType() {
        return FunctionImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(FunctionImplementationResult result) {
        stateService.setFunctionImplementationResult(result);
    }

    @Override
    protected FunctionImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        FunctionImplementationResult result = new FunctionImplementationResult();

        updateProgress(progressCallback, 0, "Initializing",
                "Starting function implementation (standalone and package)");

        // Get Oracle function metadata from state (both standalone and package members)
        List<FunctionMetadata> oracleFunctions = stateService.getOracleFunctionMetadata();
        if (oracleFunctions == null || oracleFunctions.isEmpty()) {
            updateProgress(progressCallback, 100, "Complete",
                    "No Oracle functions found in state");
            return result;
        }

        // Count standalone vs package functions
        long standaloneCount = oracleFunctions.stream().filter(FunctionMetadata::isStandalone).count();
        long packageCount = oracleFunctions.size() - standaloneCount;

        log.info("Found {} functions to implement: {} standalone, {} package members",
                oracleFunctions.size(), standaloneCount, packageCount);

        updateProgress(progressCallback, 10, "Building metadata indices",
                "Creating transformation indices from state");

        // Build transformation indices from state
        List<String> schemas = oracleFunctions.stream()
                .map(FunctionMetadata::getSchema)
                .distinct()
                .toList();

        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);
        this.indices = indices;

        updateProgress(progressCallback, 15, "Connecting to Oracle",
                "Establishing Oracle database connection for source extraction");

        // Open Oracle connection for source extraction
        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected to Oracle",
                    "Oracle connection established");

            updateProgress(progressCallback, 25, "Connecting to PostgreSQL",
                    "Establishing PostgreSQL database connection");

            try (Connection postgresConnection = postgresConnectionService.getConnection()) {
                updateProgress(progressCallback, 30, "Connected to both databases",
                        String.format("Processing %d functions (%d standalone, %d package)",
                                      oracleFunctions.size(), standaloneCount, packageCount));

                int processed = 0;
                int totalFunctions = oracleFunctions.size();

                for (FunctionMetadata function : oracleFunctions) {
                    try {
                        updateProgress(progressCallback,
                                25 + (processed * 65 / totalFunctions),
                                "Processing function: " + function.getDisplayName(),
                                String.format("Function %d of %d", processed + 1, totalFunctions));

                        // Step 0: If package function, ensure package context exists.
                        // A package whose context cannot be built blocks all of its members. The
                        // failure is recorded once against the package, and each member is
                        // recorded as blocked naming that package -- not as a copy of the
                        // package's own error against the member's name.
                        String packageName = function.isStandalone() ? null : extractPackageName(function);
                        String contextFailure = packageName == null
                                ? null
                                : ensurePackageContext(oracleConnection, postgresConnection,
                                                       function.getSchema(), packageName, result);

                        if (contextFailure != null) {
                            result.addBlockedFunction(function, packageName, contextFailure);
                            processed++;
                            continue;
                        }

                        // Step 1: Extract Oracle source
                        log.info("Extracting Oracle source for: {}", function.getDisplayName());
                        String oracleSource = extractOracleFunctionSource(oracleConnection, function);

                        log.info("Successfully extracted Oracle source for: {} ({} characters)",
                                function.getDisplayName(), oracleSource.length());
                        log.debug("Oracle source for {}:\n{}", function.getDisplayName(), oracleSource);

                        // Step 2: Transform PL/SQL to PL/pgSQL with FULL context
                        log.info("Transforming PL/SQL to PL/pgSQL for: {}", function.getDisplayName());
                        TransformationResult transformResult;

                        if (function.isFunction()) {
                            transformResult = transformationService.transformFunction(
                                    oracleSource,
                                    function.getSchema(),
                                    indices,
                                    packageContextCache,       // Package variable context
                                    function.getObjectName(),  // Function name
                                    function.getPackageName()); // Package name (null for standalone)
                        } else {
                            transformResult = transformationService.transformProcedure(
                                    oracleSource,
                                    function.getSchema(),
                                    indices,
                                    packageContextCache,       // Package variable context
                                    function.getObjectName(),  // Procedure name
                                    function.getPackageName()); // Package name (null for standalone)
                        }

                        if (!transformResult.isSuccess()) {
                            recordTransformationFailure(function, transformResult.getErrorMessage(),
                                    result);
                        } else {
                            // Step 3: Execute CREATE OR REPLACE FUNCTION/PROCEDURE in PostgreSQL
                            String pgSql = transformResult.getPostgresSql();
                            log.info("Executing transformed SQL for: {}", function.getDisplayName());
                            log.debug("PostgreSQL SQL for {}:\n{}", function.getDisplayName(), pgSql);

                            executeInPostgres(postgresConnection, pgSql, function, result);
                        }

                    } catch (Exception e) {
                        log.error("Error processing function: " + function.getDisplayName(), e);
                        result.addError(function, e.getMessage(), null);
                    }

                    processed++;
                }

                updateProgress(progressCallback, 90, "Finalizing",
                        "Function implementation complete (standalone and package)");

                log.info("Function implementation complete: {} implemented, {} errors, "
                                + "{} blocked by {} failed package contexts",
                        result.getImplementedCount(), result.getErrorCount(),
                        result.getBlockedCount(), result.getPackageErrorCount());

                // NOTE: Package function sources remain in StateService for re-runs
                // They will be cleared only when user manually resets state
                // (Memory optimization must not compromise re-run capability)

                return result;
            }
        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Function implementation failed: " + e.getMessage());
            log.error("Function implementation failed", e);
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(FunctionImplementationResult result) {
        // Blocked functions are named as blocked, never folded into "skipped" or omitted: they are
        // failures, and a summary that hides them is the defect this job had once already.
        return String.format(
                "Function implementation completed: %d implemented, %d errors, %d blocked by %d failed package contexts",
                result.getImplementedCount(), result.getErrorCount(),
                result.getBlockedCount(), result.getPackageErrorCount());
    }

    /**
     * Extracts Oracle function/procedure source code.
     *
     * <p>For STANDALONE functions/procedures:
     * <ul>
     *   <li>Queries ALL_SOURCE directly (type='FUNCTION' or 'PROCEDURE')</li>
     *   <li>Assembles source from line-by-line rows</li>
     * </ul>
     *
     * <p>For PACKAGE member functions/procedures:
     * <ul>
     *   <li>Extracts from cached package body AST (efficient for multi-function packages)</li>
     *   <li>Package body already parsed and cached by ensurePackageContext()</li>
     *   <li>Uses character index slicing to preserve Oracle formatting</li>
     * </ul>
     *
     * @param oracleConn Oracle database connection
     * @param function Function metadata (contains schema and name)
     * @return Complete Oracle PL/SQL source code as a single string
     * @throws SQLException if source extraction fails
     */
    private String extractOracleFunctionSource(Connection oracleConn, FunctionMetadata function) throws SQLException {

        // Check if this is a package member function
        if (!function.isStandalone()) {
            return extractPackageMemberSource(function);
        }

        // Standalone function/procedure - query ALL_SOURCE directly
        String schema = function.getSchema().toUpperCase();
        String name = function.getObjectName().toUpperCase();
        String type = function.isFunction() ? "FUNCTION" : "PROCEDURE";

        // Query ALL_SOURCE to get the complete PL/SQL source
        // ALL_SOURCE contains one row per line of code, ordered by LINE column
        String sql = """
            SELECT text
            FROM all_source
            WHERE owner = ?
              AND name = ?
              AND type = ?
            ORDER BY line
            """;

        StringBuilder source = new StringBuilder();

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, type);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString("text");
                    if (line != null) {
                        source.append(line);
                        // Note: Oracle ALL_SOURCE.TEXT does NOT include newlines
                        // We add them here to preserve line structure for debugging
                        if (!line.endsWith("\n")) {
                            source.append("\n");
                        }
                    }
                }
            }
        }

        String result = source.toString().trim();

        if (result.isEmpty()) {
            throw new SQLException(String.format(
                "No source found in ALL_SOURCE/DBA_SOURCE for %s %s.%s",
                type, schema, name
            ));
        }

        return result;
    }

    /**
     * Extracts package member function/procedure source from StateService.
     * Sources were pre-extracted during the extraction job via FunctionBoundaryScanner.
     *
     * NEW APPROACH (Package Segmentation):
     * - No querying of ALL_SOURCE needed
     * - No ANTLR parsing needed
     * - Direct retrieval from StateService (instant, ~O(1) lookup)
     * - Sources stored during OracleFunctionExtractionJob
     *
     * @param function Package member function metadata
     * @return Oracle PL/SQL source code for the function
     * @throws SQLException if function source not found in StateService
     */
    private String extractPackageMemberSource(FunctionMetadata function) throws SQLException {
        return packageMemberSource(stateService, function);
    }

    /**
     * The source step 22 paired with a package member, or an exception naming why there is none.
     *
     * <p>Package-private so a test can drive the real lookup from step 22's real output.
     */
    static String packageMemberSource(StateService stateService, FunctionMetadata function) throws SQLException {
        String packageName = function.getPackageName();
        if (packageName == null) {
            throw new SQLException("Cannot extract package name from: " + function.getObjectName());
        }

        // Step 22 paired this member with one of the bodies of its name, or said why it could not.
        // Never fall back to "the body of that name": with overloads that is another member's body.
        if (function.getSourcePairingError() != null) {
            throw new SQLException(String.format(
                "%s %s.%s.%s has no identifiable source: %s",
                function.isFunction() ? "Function" : "Procedure",
                function.getSchema(),
                packageName,
                function.getObjectName(),
                function.getSourcePairingError()
            ));
        }

        String source = stateService.getPackageFunctionSource(
            function.getSchema(),
            packageName,
            function.getObjectName(),
            function.getSourceOrdinal()
        );

        if (source == null) {
            throw new SQLException(String.format(
                "%s %s.%s.%s not found in StateService - extraction job may not have run",
                function.isFunction() ? "Function" : "Procedure",
                function.getSchema(),
                packageName,
                function.getObjectName()
            ));
        }

        log.debug("Retrieved package function source from StateService: {}.{}.{} ({} chars)",
                 function.getSchema(), packageName, function.getObjectName(), source.length());

        return source;
    }

    /**
     * Records a function whose PL/SQL could not be transformed.
     *
     * <p><b>This is an error, never a skip, and that is the whole point of the method.</b>
     * "Skipped" reads as neutral in every summary line, badge and report, so a failure routed
     * there disappears: the 2026-08-28 full run reported <b>3,965 skipped</b> functions that were
     * in fact 3,965 transformation failures, alongside 54,093 counted ones.
     *
     * <p>It is the same defect that let the type method job report success while implementing
     * nothing — {@code TYPE_METHOD_IMPLEMENTATION_PLAN.md} §1. That was fixed in its Phase 2 and
     * left standing here for months, because only that job was ever measured. Writing the decision
     * down in one plan did not propagate it to the sibling job built from the same template.
     *
     * <p>Skip must mean "there was nothing to do". This job has no such case — every function has
     * a body. If one ever appears, give it its own exception type first, the way
     * {@code AbstractTypeMethodException} keeps the two apart for type methods, rather than
     * reusing this path.
     *
     * <p>Package-private so {@code PostgresFunctionImplementationJobTest} can drive the real
     * routing decision without a database.
     */
    static void recordTransformationFailure(FunctionMetadata function, String errorMessage,
                                            FunctionImplementationResult result) {
        result.addError(function, "Transformation failed: " + errorMessage, null);
        log.error("Failed to transform {}: {}", function.getDisplayName(), errorMessage);
    }

    /**
     * Executes transformed PL/pgSQL in PostgreSQL.
     * Creates or replaces the function/procedure in the target database.
     *
     * @param postgresConn PostgreSQL database connection
     * @param pgSql Transformed CREATE OR REPLACE FUNCTION/PROCEDURE statement
     * @param function Function metadata
     * @param result Result tracker
     */
    private void executeInPostgres(Connection postgresConn, String pgSql,
                                   FunctionMetadata function,
                                   FunctionImplementationResult result) {
        try (PreparedStatement ps = postgresConn.prepareStatement(pgSql)) {
            ps.execute();
            result.addImplementedFunction(function);
            log.info("Successfully implemented: {}", function.getDisplayName());
        } catch (SQLException e) {
            String errorMsg = String.format("Failed to execute in PostgreSQL: %s", e.getMessage());
            result.addError(function, errorMsg, pgSql);
            log.error("Failed to implement {}: {}", function.getDisplayName(), errorMsg, e);
        }
    }

    // ========== Package Context Management ==========

    /**
     * Extracts package name from a package function.
     * FunctionMetadata stores packageName as a separate field, not embedded in objectName.
     *
     * @param function Function metadata
     * @return Package name or null if standalone function
     */
    private String extractPackageName(FunctionMetadata function) {
        return function.getPackageName();
    }

    /**
     * Ensures package context exists for a given package.
     * If not already cached, this method:
     * 1. Queries Oracle for package spec source (ALL_SOURCE)
     * 2. Parses spec with ANTLR to extract variable declarations from spec
     * 3. Queries Oracle for package body source (ALL_SOURCE)
     * 4. Parses body with ANTLR to extract variable declarations from body
     * 5. Generates helper functions (initialize, getters, setters) for ALL variables
     * 6. Executes helper creation in PostgreSQL
     * 7. Caches context for subsequent functions from same package
     *
     * <p><b>This method does not throw, and it attempts each package exactly once.</b> Both
     * properties are load-bearing, and neither held on the 2026-08-28 run:
     *
     * <ul>
     *   <li>It previously threw, and the caller's per-function catch recorded the exception
     *       against the <i>function</i>. Since it is called for every member of a package, one
     *       broken package produced one identical error per member with the package never named —
     *       <b>44,693 of that run's 56,338 recorded failures, 79% of the job</b>, from a few
     *       hundred root causes. 5,913 identical {@code schema "kindlib" does not exist} entries came
     *       from 300 packages.</li>
     *   <li>The cache was populated only on success, so a failing package was retried from
     *       scratch for every one of its members: Oracle spec re-queried, body re-parsed, helpers
     *       re-generated and re-executed, ~16 times over. Failures are now cached too.</li>
     * </ul>
     *
     * @param oracleConn Oracle database connection
     * @param postgresConn PostgreSQL database connection
     * @param schema Schema name
     * @param packageName Package name
     * @param result Result tracker; receives one package error per failing package
     * @return {@code null} when the context is usable, otherwise the reason it is not
     */
    String ensurePackageContext(Connection oracleConn, Connection postgresConn,
                                String schema, String packageName,
                                FunctionImplementationResult result) {
        String cacheKey = (schema + "." + packageName).toLowerCase();

        // Check cache
        if (packageContextCache.containsKey(cacheKey)) {
            log.debug("Package context already cached: {}", cacheKey);
            return null;
        }
        String previousFailure = packageContextFailures.get(cacheKey);
        if (previousFailure != null) {
            log.debug("Package context already known to have failed: {}", cacheKey);
            return previousFailure;
        }

        try {
            return buildPackageContext(oracleConn, postgresConn, schema, packageName, cacheKey, result);
        } catch (Exception e) {
            // Spec query, spec parse, body retrieval or body parse failed. Same contract as a
            // helper failure: named once against the package, cached, never retried.
            String reason = describe(e);
            log.error("Package context failed for {}.{}: {}", schema, packageName, reason, e);
            result.addPackageError(schema, packageName, reason, null);
            packageContextFailures.put(cacheKey, reason);
            return reason;
        }
    }

    /**
     * Builds and installs a package's context. Separated from {@link #ensurePackageContext} only so
     * that the cache bookkeeping and the one failure contract live in a single place.
     */
    private String buildPackageContext(Connection oracleConn, Connection postgresConn,
                                       String schema, String packageName, String cacheKey,
                                       FunctionImplementationResult result) throws SQLException {

        log.info("Creating package context for: {}.{}", schema, packageName);

        // Step 1: Query Oracle for package spec
        String packageSpecSql = queryPackageSpec(oracleConn, schema, packageName);
        log.debug("Retrieved package spec for {}.{} ({} characters)",
                  schema, packageName, packageSpecSql.length());

        // Step 2: Parse spec and extract variable declarations from spec
        PackageContextExtractor extractor = new PackageContextExtractor(antlrParser);
        PackageContext context = extractor.extractContext(schema, packageName, packageSpecSql);
        log.info("Extracted {} variables from package spec {}.{}",
                 context.getVariables().size(), schema, packageName);

        // Step 3: Get reduced package body from StateService (all functions removed)
        // NEW APPROACH (Package Segmentation):
        // - No querying of ALL_SOURCE needed (100s of rows avoided)
        // - Parse reduced body only (20 lines vs 5000 lines)
        // - Memory: 100KB AST vs 2GB AST
        // - Time: <10ms vs 3 minutes
        String reducedPackageBody = stateService.getReducedPackageBody(schema, packageName);

        if (reducedPackageBody == null) {
            log.warn("No reduced package body found in StateService for {}.{} - extraction job may not have run",
                     schema, packageName);
            // Fallback: Query Oracle for package body (old approach)
            reducedPackageBody = queryPackageBody(oracleConn, schema, packageName);
            log.info("Fallback: Retrieved full package body from Oracle for {}.{} ({} characters)",
                     schema, packageName, reducedPackageBody.length());
        } else {
            log.debug("Retrieved reduced package body from StateService for {}.{} ({} characters)",
                     schema, packageName, reducedPackageBody.length());
        }

        ParseResult bodyParseResult = antlrParser.parsePackageBody(reducedPackageBody);
        requireParsedBody(bodyParseResult);

        me.christianrobert.orapgsync.antlr.PlSqlParser.Create_package_bodyContext bodyAst =
            (me.christianrobert.orapgsync.antlr.PlSqlParser.Create_package_bodyContext) bodyParseResult.getTree();
        context.setPackageBody(reducedPackageBody, bodyAst);
        log.info("Parsed reduced package body for {}.{} (segmentation optimization: {} chars)",
                 schema, packageName, reducedPackageBody.length());

        // Step 4: Extract body declarations (private variables, types and subtypes)
        extractor.extractBodyDeclarations(bodyAst, context);
        log.info("Total variables (spec + body) for package {}.{}: {}",
                 schema, packageName, context.getVariables().size());

        // Step 5: Generate helper function SQL (for ALL variables - spec + body).
        // The generator is handed the same metadata the function bodies are transformed against,
        // so a variable of a migrated object type is named the way the DDL migration created it
        // rather than however it was spelled in the package spec.
        PackageHelperGenerator generator = new PackageHelperGenerator();

        // This package is not in the cache yet — it is put there only once its helpers exist — so
        // it is added to a copy for resolution. Without it a variable naming its own package's
        // type (`mypkg.trec`) is unresolvable in the one place that certainly knows it.
        Map<String, PackageContext> resolutionContexts = new HashMap<>(packageContextCache);
        resolutionContexts.put(cacheKey, context);
        String lowerSchema = schema.toLowerCase();
        TransformationContext typeResolutionContext = new TransformationContext(
                lowerSchema, indices, new SimpleTypeEvaluator(lowerSchema, indices),
                resolutionContexts, null, packageName.toLowerCase());

        List<String> helperSqlStatements = generator.generateHelperSql(context, typeResolutionContext);
        log.info("Generated {} helper functions for package {}.{}",
                 helperSqlStatements.size(), schema, packageName);

        // Step 6: Execute helper creation in PostgreSQL.
        // Every statement is attempted, rather than aborting on the first failure, so the recorded
        // error describes the whole package instead of whichever helper happened to be generated
        // first. Safe because the connection is in autocommit: each statement is its own
        // transaction, so a failure does not poison the ones after it.
        int failedHelpers = 0;
        String firstFailure = null;
        String firstFailingSql = null;
        for (String sql : helperSqlStatements) {
            try (Statement stmt = postgresConn.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                failedHelpers++;
                if (firstFailure == null) {
                    firstFailure = e.getMessage();
                    firstFailingSql = sql;
                }
                log.debug("Helper creation failed for {}.{}: {}", schema, packageName, e.getMessage());
            }
        }

        if (failedHelpers > 0) {
            String reason = String.format("%d of %d package variable helpers failed to create: %s",
                    failedHelpers, helperSqlStatements.size(), firstFailure);
            log.error("Package context failed for {}.{}: {}", schema, packageName, reason);
            result.addPackageError(schema, packageName, reason, firstFailingSql);
            packageContextFailures.put(cacheKey, reason);
            return reason;
        }

        log.info("Successfully created helper functions for package {}.{}", schema, packageName);

        // Step 7: Cache context
        context.setHelpersCreated(true);
        packageContextCache.put(cacheKey, context);
        log.debug("Cached package context: {}", cacheKey);
        return null;
    }

    /**
     * Refuses a package whose body did not parse.
     *
     * <p>This used to log a warning and carry on "best effort". It is not best effort: the tree
     * ANTLR hands back after error recovery is not a partial reading of the package, it contains
     * invented nodes, and {@link PackageContextExtractor#extractBodyDeclarations} cannot tell the
     * difference. A recovered {@code gitemtab order.tTable;} arrives as a variable *named* {@code .}
     * of type {@code tTable}; a declaration the recovery slid into an exception handler arrives as
     * a variable named {@code exception} of type {@code when}. Those reached PostgreSQL as
     * {@code CREATE FUNCTION pkg__get_itemtab() RETURNS .} — DDL for variables Oracle does not have,
     * generated from a failure that had already been detected and discarded.
     *
     * <p>The cost of refusing is small and was measured before the change: of the 1,070 packages
     * whose body failed to parse on the 2026-08-30 run, 1,018 were already failing on one of
     * these invented helpers. The other 52 lose a context that was built from the same debris.
     *
     * <p>Throwing hands the package to {@link #ensurePackageContext}'s failure path, which names
     * it once and caches the reason, so its members are blocked with the parse error rather than
     * with PostgreSQL's opinion of the invented SQL.
     *
     * <p>Package-private so a test can drive the decision without a database.
     *
     * @throws TransformationException if the parse reported any error
     */
    static void requireParsedBody(ParseResult bodyParseResult) {
        if (bodyParseResult.hasErrors()) {
            throw new TransformationException(
                    "Package body failed to parse: " + bodyParseResult.getErrorMessage(),
                    null, "Package body parsing");
        }
    }

    /**
     * A message for an exception that may carry none of its own. {@code getMessage()} is null for
     * several of the exceptions thrown out of ANTLR and the JDBC drivers, and a package error
     * reading "null" names nothing to fix.
     */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank())
                ? e.getClass().getSimpleName()
                : message;
    }

    /**
     * Queries Oracle ALL_SOURCE for package specification source code.
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @param packageName Package name
     * @return Complete package spec SQL as a single string
     * @throws SQLException if query fails or no source found
     */
    private String queryPackageSpec(Connection oracleConn, String schema, String packageName) throws SQLException {
        String sql = """
            SELECT text
            FROM all_source
            WHERE owner = ?
              AND name = ?
              AND type = 'PACKAGE'
            ORDER BY line
            """;

        StringBuilder source = new StringBuilder();

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());
            ps.setString(2, packageName.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString("text");
                    if (line != null) {
                        source.append(line);
                        if (!line.endsWith("\n")) {
                            source.append("\n");
                        }
                    }
                }
            }
        }

        String result = source.toString().trim();

        if (result.isEmpty()) {
            throw new SQLException(String.format(
                "No package spec found in ALL_SOURCE/DBA_SOURCE for %s.%s",
                schema.toUpperCase(), packageName.toUpperCase()
            ));
        }

        // Oracle ALL_SOURCE doesn't include CREATE keyword - prepend it for ANTLR parsing
        // ALL_SOURCE stores: "PACKAGE package_name AS ..."
        // ANTLR expects: "CREATE OR REPLACE PACKAGE package_name AS ..."
        return "CREATE OR REPLACE " + result;
    }

    /**
     * Queries Oracle ALL_SOURCE for package body source code.
     *
     * @param oracleConn Oracle database connection
     * @param schema Schema name
     * @param packageName Package name
     * @return Complete package body SQL as a single string
     * @throws SQLException if query fails or no source found
     */
    private String queryPackageBody(Connection oracleConn, String schema, String packageName) throws SQLException {
        String sql = """
            SELECT text
            FROM all_source
            WHERE owner = ?
              AND name = ?
              AND type = 'PACKAGE BODY'
            ORDER BY line
            """;

        StringBuilder source = new StringBuilder();

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());
            ps.setString(2, packageName.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString("text");
                    if (line != null) {
                        source.append(line);
                        if (!line.endsWith("\n")) {
                            source.append("\n");
                        }
                    }
                }
            }
        }

        String result = source.toString().trim();

        if (result.isEmpty()) {
            throw new SQLException(String.format(
                "No package body found in ALL_SOURCE/DBA_SOURCE for %s.%s",
                schema.toUpperCase(), packageName.toUpperCase()
            ));
        }

        // Oracle ALL_SOURCE doesn't include CREATE keyword - prepend it for ANTLR parsing
        // ALL_SOURCE stores: "PACKAGE BODY package_name AS ..."
        // ANTLR expects: "CREATE OR REPLACE PACKAGE BODY package_name AS ..."
        return "CREATE OR REPLACE " + result;
    }
}
