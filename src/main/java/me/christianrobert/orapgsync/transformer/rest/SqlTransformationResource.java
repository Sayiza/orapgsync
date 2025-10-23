package me.christianrobert.orapgsync.transformer.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.SqlTransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * REST endpoint for SQL transformation (Oracle → PostgreSQL).
 *
 * <p>This endpoint provides direct access to the SQL transformation service for:
 * <ul>
 *   <li>Development testing - quickly test SQL transformations without modifying test files</li>
 *   <li>Interactive debugging - see transformed SQL immediately</li>
 *   <li>Future use case - dynamic SQL conversion in PL/pgSQL migration</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Transform SQL with default schema (first in state)
 * curl -X POST "http://localhost:8080/api/transformation/sql" \
 *   -H "Content-Type: text/plain" \
 *   --data "SELECT empno, ename FROM emp WHERE dept_id = 10"
 *
 * # Transform SQL with specific schema
 * curl -X POST "http://localhost:8080/api/transformation/sql?schema=HR" \
 *   -H "Content-Type: text/plain" \
 *   --data "SELECT * FROM employees"
 *
 * # Transform SQL from file
 * curl -X POST "http://localhost:8080/api/transformation/sql" \
 *   -H "Content-Type: text/plain" \
 *   --data @query.sql
 * </pre>
 *
 * <p>Response format (JSON):
 * <pre>
 * {
 *   "success": true,
 *   "oracleSql": "SELECT empno FROM emp",
 *   "postgresSql": "SELECT empno FROM hr.emp",
 *   "errorMessage": null
 * }
 * </pre>
 *
 * <p>Note: Always returns HTTP 200. Check "success" field in response.
 * Transformation failure is a valid business outcome, not an HTTP error.
 */
@Path("/api/transformation")
@Produces(MediaType.APPLICATION_JSON)
public class SqlTransformationResource {

    private static final Logger log = LoggerFactory.getLogger(SqlTransformationResource.class);

    @Inject
    SqlTransformationService sqlTransformationService;

    @Inject
    StateService stateService;

    /**
     * Transforms Oracle SQL to PostgreSQL SQL.
     *
     * @param schema Optional schema for metadata context (defaults to first schema in state)
     * @param showAst Optional flag to include AST tree in response (for debugging)
     * @param oracleSql Oracle SQL statement (text/plain body)
     * @return TransformationResult as JSON (always HTTP 200, check "success" field)
     */
    @POST
    @Path("/sql")
    @Consumes(MediaType.TEXT_PLAIN)
    public TransformationResult transformSql(
            @QueryParam("schema") String schema,
            @QueryParam("showAst") @DefaultValue("false") boolean showAst,
            String oracleSql
    ) {
        log.info("SQL transformation request received via REST API");
        log.debug("Schema parameter: {}", schema);
        log.trace("Oracle SQL: {}", oracleSql);

        // Validate SQL input
        if (oracleSql == null || oracleSql.trim().isEmpty()) {
            log.warn("Empty SQL received");
            return TransformationResult.failure("", "SQL cannot be empty");
        }

        // Determine schema (default to first available)
        String targetSchema = schema;
        if (targetSchema == null || targetSchema.trim().isEmpty()) {
            List<String> schemas = stateService.getOracleSchemaNames();
            if (schemas == null || schemas.isEmpty()) {
                log.error("No Oracle schemas available in state");
                return TransformationResult.failure(
                        oracleSql,
                        "No Oracle schemas available in state. Extract Oracle metadata first."
                );
            }
            targetSchema = schemas.get(0);
            log.debug("Using default schema: {}", targetSchema);
        }

        // Build transformation indices from current state
        log.debug("Building transformation indices from current state");
        TransformationIndices indices;
        try {
            List<String> allSchemas = stateService.getOracleSchemaNames();
            indices = MetadataIndexBuilder.build(stateService, allSchemas);
        } catch (Exception e) {
            log.error("Failed to build transformation indices", e);
            return TransformationResult.failure(
                    oracleSql,
                    "Failed to build metadata indices: " + e.getMessage()
            );
        }

        // Transform SQL
        log.debug("Transforming SQL for schema: {} (showAst={})", targetSchema, showAst);
        TransformationResult result = sqlTransformationService.transformSql(
                oracleSql, targetSchema, indices, showAst
        );

        if (result.isSuccess()) {
            log.info("SQL transformation succeeded");
            log.debug("PostgreSQL SQL: {}", result.getPostgresSql());
            if (result.hasAstTree()) {
                log.debug("AST tree included in response");
            }
        } else {
            log.warn("SQL transformation failed: {}", result.getErrorMessage());
        }

        return result;
    }
}
