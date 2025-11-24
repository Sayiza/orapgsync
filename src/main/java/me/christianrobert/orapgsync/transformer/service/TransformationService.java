package me.christianrobert.orapgsync.transformer.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.builder.VisitFunctionBody;
import me.christianrobert.orapgsync.transformer.builder.VisitProcedureBody;
import me.christianrobert.orapgsync.transformer.util.AstTreeFormatter;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified transformation service for Oracle SQL and PL/SQL to PostgreSQL.
 *
 * <p>This service handles all transformation types using a single ANTLR parser,
 * PostgresCodeBuilder, and transformation context. The only difference between
 * transformation types is the grammar entry point used.
 *
 * <p>Architecture:
 * <pre>
 * Oracle Code → ANTLR Parse (entry point) → PostgresCodeBuilder → PostgreSQL Code
 *                    ↓                            ↓                      ↓
 *               PlSqlParser                 Static Visitors          String
 * </pre>
 *
 * <p>Supported Transformations:
 * <ul>
 *   <li>SQL Views: SELECT statements (entry: select_statement)</li>
 *   <li>Functions: PL/SQL function bodies (entry: function_body)</li>
 *   <li>Procedures: PL/SQL procedure bodies (entry: procedure_body)</li>
 *   <li>Future: Package bodies, triggers, etc.</li>
 * </ul>
 *
 * <p>Note: SQL and PL/SQL are not separated because:
 * <ul>
 *   <li>They share the same grammar (PlSqlParser.g4)</li>
 *   <li>PL/SQL contains SQL (FOR cursor loops, SELECT INTO, etc.)</li>
 *   <li>Same transformations apply (NVL, DECODE, CASE, etc.)</li>
 *   <li>One PostgresCodeBuilder handles both</li>
 * </ul>
 */
@ApplicationScoped
public class TransformationService {

    private static final Logger log = LoggerFactory.getLogger(TransformationService.class);

    @Inject
    AntlrParser parser;

    // ==================== SQL TRANSFORMATIONS ====================

    /**
     * Transforms Oracle SQL to PostgreSQL equivalent.
     *
     * <p>This is the main entry point for SQL transformation (views). It handles:
     * <ul>
     *   <li>Parsing Oracle SQL using ANTLR (entry: select_statement)</li>
     *   <li>Direct AST transformation via PostgresCodeBuilder</li>
     *   <li>Transforming to PostgreSQL SQL using metadata indices</li>
     *   <li>Error handling and reporting</li>
     * </ul>
     *
     * <p>Use cases:
     * <ul>
     *   <li>View creation (PostgresViewImplementationJob)</li>
     *   <li>REST API for development testing</li>
     *   <li>Future: Dynamic SQL conversion in PL/pgSQL</li>
     * </ul>
     *
     * @param oracleSql Oracle SELECT statement (any Oracle SQL syntax)
     * @param schema Schema context for synonym and name resolution
     * @param indices Pre-built metadata indices for lookups
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformSql(String oracleSql, String schema, TransformationIndices indices) {
        return transformSql(oracleSql, schema, indices, null, false);
    }

    /**
     * Transforms Oracle SQL to PostgreSQL equivalent for view transformations with column type metadata.
     *
     * <p>This variant is used for view implementation where we need to cast SELECT list expressions
     * to match stub column types (ensures CREATE OR REPLACE VIEW succeeds).</p>
     *
     * @param oracleSql Oracle SELECT statement
     * @param schema Current schema context (e.g., "hr")
     * @param indices Pre-built metadata indices for lookups
     * @param viewColumnTypes Column name → PostgreSQL type mapping (from view stub metadata)
     * @return TransformationResult with transformed SQL or error details
     */
    public TransformationResult transformSql(String oracleSql, String schema, TransformationIndices indices, Map<String, String> viewColumnTypes) {
        return transformSql(oracleSql, schema, indices, viewColumnTypes, false);
    }

    /**
     * Transforms Oracle SQL to PostgreSQL equivalent with optional AST tree output.
     *
     * <p>This is the master transformation method that all other overloads delegate to.</p>
     *
     * @param oracleSql Oracle SELECT statement (any Oracle SQL syntax)
     * @param schema Schema context for synonym and name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param viewColumnTypes Column name → PostgreSQL type mapping (null for non-view transformations)
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformSql(String oracleSql, String schema, TransformationIndices indices, Map<String, String> viewColumnTypes, boolean includeAst) {
        if (oracleSql == null || oracleSql.trim().isEmpty()) {
            return TransformationResult.failure(oracleSql, "Oracle SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oracleSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oracleSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming SQL for schema: {}", schema);
        log.trace("Oracle SQL: {}", oracleSql);

        // Create type evaluator upfront for proper cleanup
        SimpleTypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

        try {
            // STEP 1: Parse Oracle SQL using ANTLR
            log.debug("Step 1: Parsing Oracle SQL");
            ParseResult parseResult = parser.parseSelectStatement(oracleSql);

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed: {}", errorMsg);
                return TransformationResult.failure(oracleSql, errorMsg);
            }

            // STEP 2: Run type analysis pass (populate type cache)
            log.debug("Step 2: Running type analysis pass");
            Map<String, TypeInfo> typeCache = new HashMap<>();
            TypeAnalysisVisitor typeAnalysisVisitor =
                new TypeAnalysisVisitor(schema, indices, typeCache);
            typeAnalysisVisitor.visit(parseResult.getTree());
            log.debug("Type analysis complete: {} types cached", typeCache.size());

            // Generate AST tree representation with type information if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation with type information");
                astTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);
            }

            // STEP 3: Create TransformationContext with schema, indices, and type evaluator
            log.debug("Step 3: Creating transformation context with schema: {}", schema);

            // Create context with type evaluator and optional view column types
            TransformationContext context;
            if (viewColumnTypes != null) {
                log.debug("Creating context for view transformation with {} column types", viewColumnTypes.size());
                context = new TransformationContext(schema, indices, typeEvaluator, viewColumnTypes);
            } else {
                context = new TransformationContext(schema, indices, typeEvaluator);
            }

            // STEP 4: Transform ANTLR parse tree to PostgreSQL SQL with context
            log.debug("Step 4: Transforming to PostgreSQL");
            PostgresCodeBuilder builder =
                new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());

            log.info("Successfully transformed SQL for schema: {}", schema);
            log.debug("PostgreSQL SQL: {}", postgresSql);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oracleSql, postgresSql, astTree);
            }
            return TransformationResult.success(oracleSql, postgresSql);

        } catch (TransformationException e) {
            log.error("Transformation failed: {}", e.getDetailedMessage(), e);
            return TransformationResult.failure(oracleSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation", e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oracleSql, errorMsg);

        } finally {
            // HIGH PRIORITY FIX: Clear type evaluator cache to prevent memory leaks
            // Without this, caches accumulate 100KB-1MB per transformation
            typeEvaluator.clearCache();
            log.trace("Cleared type evaluator cache");
        }
    }

    // ==================== PL/SQL TRANSFORMATIONS ====================

    /**
     * Transforms Oracle PL/SQL function to PostgreSQL equivalent.
     *
     * <p>This method handles:
     * <ul>
     *   <li>Parsing Oracle PL/SQL function body using ANTLR (entry: function_body)</li>
     *   <li>Direct AST transformation via PostgresCodeBuilder</li>
     *   <li>Extracting function name and parameters from AST</li>
     *   <li>Generating CREATE OR REPLACE FUNCTION statement</li>
     *   <li>Error handling and reporting</li>
     * </ul>
     *
     * <p>Note: Function name and parameters are extracted from the AST, not from metadata.
     * Only schema is required from metadata, consistent with SQL transformation.</p>
     *
     * @param oraclePlSql Oracle PL/SQL function source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformFunction(String oraclePlSql, String schema, TransformationIndices indices) {
        return transformFunction(oraclePlSql, schema, indices, false);
    }

    /**
     * Transforms Oracle PL/SQL function to PostgreSQL equivalent with optional AST tree output.
     *
     * @param oraclePlSql Oracle PL/SQL function source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformFunction(String oraclePlSql, String schema, TransformationIndices indices, boolean includeAst) {
        if (oraclePlSql == null || oraclePlSql.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Oracle PL/SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oraclePlSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming function for schema: {}", schema);
        log.trace("Oracle PL/SQL: {}", oraclePlSql);

        // Create type evaluator upfront for proper cleanup
        SimpleTypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

        try {
            // STEP 1: Parse Oracle PL/SQL function body using ANTLR
            log.debug("Step 1: Parsing Oracle PL/SQL function body");
            ParseResult parseResult = parser.parseFunctionBody(oraclePlSql);

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed for schema {}: {}", schema, errorMsg);
                return TransformationResult.failure(oraclePlSql, errorMsg);
            }

            // STEP 2: Run type analysis pass (populate type cache)
            log.debug("Step 2: Running type analysis pass");
            Map<String, TypeInfo> typeCache = new HashMap<>();
            TypeAnalysisVisitor typeAnalysisVisitor =
                new TypeAnalysisVisitor(schema, indices, typeCache);
            typeAnalysisVisitor.visit(parseResult.getTree());
            log.debug("Type analysis complete: {} types cached", typeCache.size());

            // Generate AST tree representation with type information if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation with type information");
                astTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);
            }

            // STEP 3: Create TransformationContext with schema, indices, and type evaluator
            log.debug("Step 3: Creating transformation context with schema: {}", schema);

            // Create context (only schema needed - function name/params extracted from AST)
            TransformationContext context = new TransformationContext(schema, indices, typeEvaluator);

            // STEP 4: Transform ANTLR parse tree to PostgreSQL PL/pgSQL
            log.debug("Step 4: Transforming to PostgreSQL PL/pgSQL");
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String createFunction = builder.visit(parseResult.getTree());

            log.info("Successfully transformed function for schema: {}", schema);
            log.debug("PostgreSQL PL/pgSQL: {}", createFunction);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oraclePlSql, createFunction, astTree);
            }
            return TransformationResult.success(oraclePlSql, createFunction);

        } catch (TransformationException e) {
            log.error("Transformation failed for schema {}: {}", schema, e.getDetailedMessage(), e);
            return TransformationResult.failure(oraclePlSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation for schema {}", schema, e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oraclePlSql, errorMsg);

        } finally {
            // HIGH PRIORITY FIX: Clear type evaluator cache to prevent memory leaks
            typeEvaluator.clearCache();
            log.trace("Cleared type evaluator cache");
        }
    }

    /**
     * Transforms Oracle PL/SQL function to PostgreSQL equivalent with full transformation context.
     *
     * <p>This overload is used for package function transformations that may involve package variables.</p>
     *
     * @param oraclePlSql Oracle PL/SQL function source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param packageContextCache Package variable contexts (null for standalone functions)
     * @param functionName Current function name (for context)
     * @param packageName Current package name (null for standalone functions)
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformFunction(String oraclePlSql,
                                                   String schema,
                                                   TransformationIndices indices,
                                                   Map<String, PackageContext> packageContextCache,
                                                   String functionName,
                                                   String packageName) {
        return transformFunction(oraclePlSql, schema, indices, packageContextCache, functionName, packageName, false);
    }

    /**
     * Transforms Oracle PL/SQL function to PostgreSQL equivalent with full transformation context and optional AST output.
     *
     * <p>This is the most complete overload, used internally by all other transformFunction variants.</p>
     *
     * @param oraclePlSql Oracle PL/SQL function source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param packageContextCache Package variable contexts (null for standalone functions)
     * @param functionName Current function name (for context)
     * @param packageName Current package name (null for standalone functions)
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformFunction(String oraclePlSql,
                                                   String schema,
                                                   TransformationIndices indices,
                                                   Map<String, PackageContext> packageContextCache,
                                                   String functionName,
                                                   String packageName,
                                                   boolean includeAst) {
        if (oraclePlSql == null || oraclePlSql.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Oracle PL/SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oraclePlSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming function for schema: {}, package: {}, function: {}",
                 schema, packageName != null ? packageName : "standalone", functionName);
        log.trace("Oracle PL/SQL: {}", oraclePlSql);

        // Create type evaluator upfront for proper cleanup
        SimpleTypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

        try {
            // STEP 1: Parse Oracle PL/SQL function body using ANTLR
            log.debug("Step 1: Parsing Oracle PL/SQL function body");
            ParseResult parseResult = parser.parseFunctionBody(oraclePlSql);

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed for schema {}: {}", schema, errorMsg);
                return TransformationResult.failure(oraclePlSql, errorMsg);
            }

            // STEP 2: Run type analysis pass (populate type cache)
            log.debug("Step 2: Running type analysis pass");
            Map<String, TypeInfo> typeCache = new HashMap<>();
            TypeAnalysisVisitor typeAnalysisVisitor =
                new TypeAnalysisVisitor(schema, indices, typeCache);
            typeAnalysisVisitor.visit(parseResult.getTree());
            log.debug("Type analysis complete: {} types cached", typeCache.size());

            // Generate AST tree representation with type information if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation with type information");
                astTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);
            }

            // STEP 3: Create FULL TransformationContext with package context
            log.debug("Step 3: Creating full transformation context with schema: {}, package: {}",
                     schema, packageName);

            // Create context with ALL transformation-level information
            TransformationContext context = new TransformationContext(
                schema,
                indices,
                typeEvaluator,
                packageContextCache,  // Package variable context
                functionName,         // Current function name
                packageName,          // Current package name (null for standalone)
                null);                // No view column types for PL/SQL transformations

            // STEP 4: Transform ANTLR parse tree to PostgreSQL PL/pgSQL
            log.debug("Step 4: Transforming to PostgreSQL PL/pgSQL");
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String createFunction = builder.visit(parseResult.getTree());

            log.info("Successfully transformed function for schema: {}, package: {}",
                    schema, packageName != null ? packageName : "standalone");
            log.debug("PostgreSQL PL/pgSQL: {}", createFunction);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oraclePlSql, createFunction, astTree);
            }
            return TransformationResult.success(oraclePlSql, createFunction);

        } catch (TransformationException e) {
            log.error("Transformation failed for schema {}: {}", schema, e.getDetailedMessage(), e);
            return TransformationResult.failure(oraclePlSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation for schema {}", schema, e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oraclePlSql, errorMsg);

        } finally {
            // HIGH PRIORITY FIX: Clear type evaluator cache to prevent memory leaks
            typeEvaluator.clearCache();
            log.trace("Cleared type evaluator cache");
        }
    }

    /**
     * Transforms Oracle PL/SQL procedure to PostgreSQL equivalent.
     *
     * <p>Same as {@link #transformFunction} but for procedures (no return type).</p>
     *
     * <p>Note: Procedure name and parameters are extracted from the AST, not from metadata.
     * Only schema is required from metadata, consistent with SQL transformation.</p>
     *
     * @param oraclePlSql Oracle PL/SQL procedure source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformProcedure(String oraclePlSql, String schema, TransformationIndices indices) {
        return transformProcedure(oraclePlSql, schema, indices, false);
    }

    /**
     * Transforms Oracle PL/SQL procedure to PostgreSQL equivalent with optional AST tree output.
     *
     * @param oraclePlSql Oracle PL/SQL procedure source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformProcedure(String oraclePlSql, String schema, TransformationIndices indices, boolean includeAst) {
        if (oraclePlSql == null || oraclePlSql.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Oracle PL/SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oraclePlSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming procedure for schema: {}", schema);
        log.trace("Oracle PL/SQL: {}", oraclePlSql);

        // Create type evaluator upfront for proper cleanup
        SimpleTypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

        try {
            // STEP 1: Parse Oracle PL/SQL procedure body using ANTLR
            log.debug("Step 1: Parsing Oracle PL/SQL procedure body");
            ParseResult parseResult = parser.parseProcedureBody(oraclePlSql);

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed for schema {}: {}", schema, errorMsg);
                return TransformationResult.failure(oraclePlSql, errorMsg);
            }

            // STEP 2: Run type analysis pass (populate type cache)
            log.debug("Step 2: Running type analysis pass");
            Map<String, TypeInfo> typeCache = new HashMap<>();
            TypeAnalysisVisitor typeAnalysisVisitor =
                new TypeAnalysisVisitor(schema, indices, typeCache);
            typeAnalysisVisitor.visit(parseResult.getTree());
            log.debug("Type analysis complete: {} types cached", typeCache.size());

            // Generate AST tree representation with type information if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation with type information");
                astTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);
            }

            // STEP 3: Create TransformationContext with schema, indices, and type evaluator
            log.debug("Step 3: Creating transformation context with schema: {}", schema);

            // Create context (only schema needed - procedure name/params extracted from AST)
            TransformationContext context = new TransformationContext(schema, indices, typeEvaluator);

            // STEP 4: Transform ANTLR parse tree to PostgreSQL PL/pgSQL
            log.debug("Step 4: Transforming to PostgreSQL PL/pgSQL");
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String createFunction = builder.visit(parseResult.getTree());

            log.info("Successfully transformed procedure for schema: {}", schema);
            log.debug("PostgreSQL PL/pgSQL: {}", createFunction);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oraclePlSql, createFunction, astTree);
            }
            return TransformationResult.success(oraclePlSql, createFunction);

        } catch (TransformationException e) {
            log.error("Transformation failed for schema {}: {}", schema, e.getDetailedMessage(), e);
            return TransformationResult.failure(oraclePlSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation for schema {}", schema, e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oraclePlSql, errorMsg);

        } finally {
            // HIGH PRIORITY FIX: Clear type evaluator cache to prevent memory leaks
            typeEvaluator.clearCache();
            log.trace("Cleared type evaluator cache");
        }
    }

    /**
     * Transforms Oracle PL/SQL procedure to PostgreSQL equivalent with full transformation context.
     *
     * <p>This overload is used for package procedure transformations that may involve package variables.</p>
     *
     * @param oraclePlSql Oracle PL/SQL procedure source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param packageContextCache Package variable contexts (null for standalone procedures)
     * @param procedureName Current procedure name (for context)
     * @param packageName Current package name (null for standalone procedures)
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformProcedure(String oraclePlSql,
                                                    String schema,
                                                    TransformationIndices indices,
                                                    Map<String, PackageContext> packageContextCache,
                                                    String procedureName,
                                                    String packageName) {
        return transformProcedure(oraclePlSql, schema, indices, packageContextCache, procedureName, packageName, false);
    }

    /**
     * Transforms Oracle PL/SQL procedure to PostgreSQL equivalent with full transformation context and optional AST output.
     *
     * <p>This is the most complete overload, used internally by all other transformProcedure variants.</p>
     *
     * @param oraclePlSql Oracle PL/SQL procedure source (from ALL_SOURCE)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param packageContextCache Package variable contexts (null for standalone procedures)
     * @param procedureName Current procedure name (for context)
     * @param packageName Current package name (null for standalone procedures)
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformProcedure(String oraclePlSql,
                                                    String schema,
                                                    TransformationIndices indices,
                                                    Map<String, PackageContext> packageContextCache,
                                                    String procedureName,
                                                    String packageName,
                                                    boolean includeAst) {
        if (oraclePlSql == null || oraclePlSql.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Oracle PL/SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oraclePlSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming procedure for schema: {}, package: {}, procedure: {}",
                 schema, packageName != null ? packageName : "standalone", procedureName);
        log.trace("Oracle PL/SQL: {}", oraclePlSql);

        // Create type evaluator upfront for proper cleanup
        SimpleTypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

        try {
            // STEP 1: Parse Oracle PL/SQL procedure body using ANTLR
            log.debug("Step 1: Parsing Oracle PL/SQL procedure body");
            ParseResult parseResult = parser.parseProcedureBody(oraclePlSql);

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed for schema {}: {}", schema, errorMsg);
                return TransformationResult.failure(oraclePlSql, errorMsg);
            }

            // STEP 2: Run type analysis pass (populate type cache)
            log.debug("Step 2: Running type analysis pass");
            Map<String, TypeInfo> typeCache = new HashMap<>();
            TypeAnalysisVisitor typeAnalysisVisitor =
                new TypeAnalysisVisitor(schema, indices, typeCache);
            typeAnalysisVisitor.visit(parseResult.getTree());
            log.debug("Type analysis complete: {} types cached", typeCache.size());

            // Generate AST tree representation with type information if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation with type information");
                astTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);
            }

            // STEP 3: Create FULL TransformationContext with package context
            log.debug("Step 3: Creating full transformation context with schema: {}, package: {}",
                     schema, packageName);

            // Create context with ALL transformation-level information
            TransformationContext context = new TransformationContext(
                schema,
                indices,
                typeEvaluator,
                packageContextCache,  // Package variable context
                procedureName,        // Current procedure name
                packageName,          // Current package name (null for standalone)
                null);                // No view column types for PL/SQL transformations

            // STEP 4: Transform ANTLR parse tree to PostgreSQL PL/pgSQL
            log.debug("Step 4: Transforming to PostgreSQL PL/pgSQL");
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String createFunction = builder.visit(parseResult.getTree());

            log.info("Successfully transformed procedure for schema: {}, package: {}",
                    schema, packageName != null ? packageName : "standalone");
            log.debug("PostgreSQL PL/pgSQL: {}", createFunction);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oraclePlSql, createFunction, astTree);
            }
            return TransformationResult.success(oraclePlSql, createFunction);

        } catch (TransformationException e) {
            log.error("Transformation failed for schema {}: {}", schema, e.getDetailedMessage(), e);
            return TransformationResult.failure(oraclePlSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation for schema {}", schema, e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oraclePlSql, errorMsg);

        } finally {
            // HIGH PRIORITY FIX: Clear type evaluator cache to prevent memory leaks
            typeEvaluator.clearCache();
            log.trace("Cleared type evaluator cache");
        }
    }
}
