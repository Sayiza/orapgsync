package me.christianrobert.orapgsync.transformer.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-level service for transforming Oracle view SQL to PostgreSQL.
 * This is the main entry point for migration jobs.
 *
 * <p>Architecture:
 * <pre>
 * Oracle SQL → ANTLR Parse → Semantic Tree → PostgreSQL SQL
 *                  ↓              ↓               ↓
 *             PlSqlParser    SemanticNode    toPostgres()
 * </pre>
 *
 * <p>Usage:
 * <pre>
 * TransformationResult result = service.transformViewSql(oracleSql, schema);
 * if (result.isSuccess()) {
 *     String postgresSql = result.getPostgresSql();
 *     // Create view with postgresSql
 * } else {
 *     // Handle error: result.getErrorMessage()
 * }
 * </pre>
 *
 * <p>Current implementation status:
 * - Phase 1: Simple SELECT statements (column list, single table)
 * - Future: ALL select statement, with any oracle native features :-)
 */
@ApplicationScoped
public class ViewTransformationService {

    private static final Logger log = LoggerFactory.getLogger(ViewTransformationService.class);

    @Inject
    AntlrParser parser;

    /**
     * Transforms Oracle view SQL to PostgreSQL equivalent.
     *
     * <p>This is the main entry point for transformation. It handles:
     * <ul>
     *   <li>Parsing Oracle SQL using ANTLR</li>
     *   <li>Building semantic syntax tree</li>
     *   <li>Transforming to PostgreSQL SQL using metadata indices</li>
     *   <li>Error handling and reporting</li>
     * </ul>
     *
     * <p>Current limitations (will be addressed in future phases):
     * <ul>
     *   <li>Only simple SELECT statements (no WHERE, ORDER BY, etc.)</li>
     *   <li>No Oracle-specific function transformations yet</li>
     *   <li>No type conversion yet</li>
     * </ul>
     *
     * @param oracleSql Oracle SELECT statement from view definition
     * @param schema Schema context for synonym and name resolution
     * @param indices Pre-built metadata indices for lookups
     * @return TransformationResult containing either transformed SQL or error details
     */
    public TransformationResult transformViewSql(String oracleSql, String schema, TransformationIndices indices) {
        if (oracleSql == null || oracleSql.trim().isEmpty()) {
            return TransformationResult.failure(oracleSql, "Oracle SQL cannot be null or empty");
        }

        if (schema == null || schema.trim().isEmpty()) {
            return TransformationResult.failure(oracleSql, "Schema cannot be null or empty");
        }

        if (indices == null) {
            return TransformationResult.failure(oracleSql, "Transformation indices cannot be null");
        }

        log.debug("Transforming view SQL for schema: {}", schema);
        log.trace("Oracle SQL: {}", oracleSql);

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

            // STEP 2: Create TransformationContext with schema and indices
            log.debug("Step 2: Creating transformation context with schema: {}", schema);
            me.christianrobert.orapgsync.transformer.context.TransformationContext context =
                new me.christianrobert.orapgsync.transformer.context.TransformationContext(schema, indices);

            // STEP 3: Transform ANTLR parse tree to PostgreSQL SQL with context
            log.debug("Step 3: Transforming to PostgreSQL");
            me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder builder =
                new me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());

            log.info("Successfully transformed view SQL for schema: {}", schema);
            log.debug("PostgreSQL SQL: {}", postgresSql);

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
}
