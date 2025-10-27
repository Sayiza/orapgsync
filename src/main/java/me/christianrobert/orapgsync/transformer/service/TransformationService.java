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
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return transformSql(oracleSql, schema, indices, false);
    }

    /**
     * Transforms Oracle SQL to PostgreSQL equivalent with optional AST tree output.
     *
     * <p>Same as {@link #transformSql(String, String, TransformationIndices)} but optionally
     * includes AST tree representation for debugging.</p>
     *
     * @param oracleSql Oracle SELECT statement (any Oracle SQL syntax)
     * @param schema Schema context for synonym and name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformSql(String oracleSql, String schema, TransformationIndices indices, boolean includeAst) {
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

        try {
            // STEP 1: Parse Oracle SQL using ANTLR
            log.debug("Step 1: Parsing Oracle SQL");
            ParseResult parseResult = parser.parseSelectStatement(oracleSql);

            // Generate AST tree representation if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation");
                astTree = AstTreeFormatter.format(parseResult.getTree());
            }

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed: {}", errorMsg);
                if (includeAst && astTree != null) {
                    return TransformationResult.failureWithAst(oracleSql, errorMsg, astTree);
                }
                return TransformationResult.failure(oracleSql, errorMsg);
            }

            // STEP 2: Create TransformationContext with schema, indices, and type evaluator
            log.debug("Step 2: Creating transformation context with schema: {}", schema);

            // Create type evaluator (simple implementation for SQL views)
            TypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

            // Create context with type evaluator
            TransformationContext context = new TransformationContext(schema, indices, typeEvaluator);

            // STEP 3: Transform ANTLR parse tree to PostgreSQL SQL with context
            log.debug("Step 3: Transforming to PostgreSQL");
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
     *   <li>Generating CREATE OR REPLACE FUNCTION statement</li>
     *   <li>Error handling and reporting</li>
     * </ul>
     *
     * @param oraclePlSql Oracle PL/SQL function source (from ALL_SOURCE)
     * @param function Function metadata (for signature information)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformFunction(String oraclePlSql, FunctionMetadata function,
                                                   String schema, TransformationIndices indices) {
        return transformFunction(oraclePlSql, function, schema, indices, false);
    }

    /**
     * Transforms Oracle PL/SQL function to PostgreSQL equivalent with optional AST tree output.
     *
     * @param oraclePlSql Oracle PL/SQL function source (from ALL_SOURCE)
     * @param function Function metadata (for signature information)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformFunction(String oraclePlSql, FunctionMetadata function,
                                                   String schema, TransformationIndices indices, boolean includeAst) {
        if (oraclePlSql == null || oraclePlSql.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Oracle PL/SQL cannot be null or empty");
        }

        if (function == null) {
            return TransformationResult.failure(oraclePlSql, "Function metadata cannot be null");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oraclePlSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming function: {}", function.getDisplayName());
        log.trace("Oracle PL/SQL: {}", oraclePlSql);

        try {
            // STEP 1: Parse Oracle PL/SQL function body using ANTLR
            log.debug("Step 1: Parsing Oracle PL/SQL function body");
            ParseResult parseResult = parser.parseFunctionBody(oraclePlSql);

            // Generate AST tree representation if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation");
                astTree = AstTreeFormatter.format(parseResult.getTree());
            }

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed for {}: {}", function.getDisplayName(), errorMsg);
                if (includeAst && astTree != null) {
                    return TransformationResult.failureWithAst(oraclePlSql, errorMsg, astTree);
                }
                return TransformationResult.failure(oraclePlSql, errorMsg);
            }

            // STEP 2: Create TransformationContext with schema, indices, type evaluator, and function metadata
            log.debug("Step 2: Creating transformation context with schema: {}", schema);

            // Create type evaluator
            TypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

            // Create context with type evaluator and function metadata (visitor pattern!)
            TransformationContext context = new TransformationContext(schema, indices, typeEvaluator, function);

            // STEP 3: Transform ANTLR parse tree to PostgreSQL PL/pgSQL with context
            log.debug("Step 3: Transforming to PostgreSQL PL/pgSQL");
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String createFunction = builder.visit(parseResult.getTree());

            log.info("Successfully transformed function: {}", function.getDisplayName());
            log.debug("PostgreSQL PL/pgSQL: {}", createFunction);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oraclePlSql, createFunction, astTree);
            }
            return TransformationResult.success(oraclePlSql, createFunction);

        } catch (TransformationException e) {
            log.error("Transformation failed for {}: {}", function.getDisplayName(), e.getDetailedMessage(), e);
            return TransformationResult.failure(oraclePlSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation of {}", function.getDisplayName(), e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oraclePlSql, errorMsg);
        }
    }

    /**
     * Transforms Oracle PL/SQL procedure to PostgreSQL equivalent.
     *
     * <p>Same as {@link #transformFunction} but for procedures (no return type).</p>
     *
     * @param oraclePlSql Oracle PL/SQL procedure source (from ALL_SOURCE)
     * @param function Function metadata (for signature information)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformProcedure(String oraclePlSql, FunctionMetadata function,
                                                    String schema, TransformationIndices indices) {
        return transformProcedure(oraclePlSql, function, schema, indices, false);
    }

    /**
     * Transforms Oracle PL/SQL procedure to PostgreSQL equivalent with optional AST tree output.
     *
     * @param oraclePlSql Oracle PL/SQL procedure source (from ALL_SOURCE)
     * @param function Function metadata (for signature information)
     * @param schema Schema context for name resolution
     * @param indices Pre-built metadata indices for lookups
     * @param includeAst Whether to include AST tree in result (for debugging)
     * @return TransformationResult containing transformed SQL and optionally AST tree
     */
    public TransformationResult transformProcedure(String oraclePlSql, FunctionMetadata function,
                                                    String schema, TransformationIndices indices, boolean includeAst) {
        if (oraclePlSql == null || oraclePlSql.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Oracle PL/SQL cannot be null or empty");
        }

        if (function == null) {
            return TransformationResult.failure(oraclePlSql, "Function metadata cannot be null");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oraclePlSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oraclePlSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming procedure: {}", function.getDisplayName());
        log.trace("Oracle PL/SQL: {}", oraclePlSql);

        try {
            // STEP 1: Parse Oracle PL/SQL procedure body using ANTLR
            log.debug("Step 1: Parsing Oracle PL/SQL procedure body");
            ParseResult parseResult = parser.parseProcedureBody(oraclePlSql);

            // Generate AST tree representation if requested
            String astTree = null;
            if (includeAst && parseResult.getTree() != null) {
                log.debug("Generating AST tree representation");
                astTree = AstTreeFormatter.format(parseResult.getTree());
            }

            // Check for parse errors
            if (parseResult.hasErrors()) {
                String errorMsg = "Parse errors: " + parseResult.getErrorMessage();
                log.warn("Parse failed for {}: {}", function.getDisplayName(), errorMsg);
                if (includeAst && astTree != null) {
                    return TransformationResult.failureWithAst(oraclePlSql, errorMsg, astTree);
                }
                return TransformationResult.failure(oraclePlSql, errorMsg);
            }

            // STEP 2: Create TransformationContext with schema, indices, type evaluator, and function metadata
            log.debug("Step 2: Creating transformation context with schema: {}", schema);

            // Create type evaluator
            TypeEvaluator typeEvaluator = new SimpleTypeEvaluator(schema, indices);

            // Create context with type evaluator and function metadata (visitor pattern!)
            TransformationContext context = new TransformationContext(schema, indices, typeEvaluator, function);

            // STEP 3: Transform ANTLR parse tree to PostgreSQL PL/pgSQL with context
            log.debug("Step 3: Transforming to PostgreSQL PL/pgSQL");
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String createFunction = builder.visit(parseResult.getTree());

            log.info("Successfully transformed procedure: {}", function.getDisplayName());
            log.debug("PostgreSQL PL/pgSQL: {}", createFunction);

            if (includeAst && astTree != null) {
                return TransformationResult.successWithAst(oraclePlSql, createFunction, astTree);
            }
            return TransformationResult.success(oraclePlSql, createFunction);

        } catch (TransformationException e) {
            log.error("Transformation failed for {}: {}", function.getDisplayName(), e.getDetailedMessage(), e);
            return TransformationResult.failure(oraclePlSql, e);

        } catch (Exception e) {
            log.error("Unexpected error during transformation of {}", function.getDisplayName(), e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            return TransformationResult.failure(oraclePlSql, errorMsg);
        }
    }
}
