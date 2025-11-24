package me.christianrobert.orapgsync.transformer.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * REST endpoint for Oracle to PostgreSQL transformation (SQL and PL/SQL).
 *
 * <p>This endpoint provides direct access to the transformation service for:
 * <ul>
 *   <li>Development testing - quickly test transformations without modifying test files</li>
 *   <li>Interactive debugging - see transformed code immediately</li>
 *   <li>Future use case - dynamic SQL conversion in PL/pgSQL migration</li>
 * </ul>
 *
 * <p>Supported transformation types:
 * <ul>
 *   <li>select_statement - SQL views (default for /sql endpoint)</li>
 *   <li>function_body - PL/SQL function bodies</li>
 *   <li>procedure_body - PL/SQL procedure bodies</li>
 *   <li>package_body - Package bodies (for analysis)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Transform SQL view
 * curl -X POST "http://localhost:8080/api/transformation/code?parseType=select_statement" \
 *   -H "Content-Type: text/plain" \
 *   --data "SELECT empno, ename FROM emp WHERE dept_id = 10"
 *
 * # Transform PL/SQL function
 * curl -X POST "http://localhost:8080/api/transformation/code?parseType=function_body&schema=HR" \
 *   -H "Content-Type: text/plain" \
 *   --data "FUNCTION get_salary(p_empno NUMBER) RETURN NUMBER IS BEGIN..."
 * </pre>
 *
 * <p>Response format (JSON):
 * <pre>
 * {
 *   "success": true,
 *   "oracleSql": "...",
 *   "postgresSql": "...",
 *   "errorMessage": null
 * }
 * </pre>
 *
 * <p>Note: Always returns HTTP 200. Check "success" field in response.
 * Transformation failure is a valid business outcome, not an HTTP error.
 */
@Path("/api/transformation")
@Produces(MediaType.APPLICATION_JSON)
public class TransformationResource {

    private static final Logger log = LoggerFactory.getLogger(TransformationResource.class);

    @Inject
    TransformationService transformationService;

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
        TransformationResult result = transformationService.transformSql(
                oracleSql, targetSchema, indices, null, showAst
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

    /**
     * Generic transformation endpoint supporting multiple parse types.
     *
     * <p>This endpoint allows selecting the ANTLR grammar entry point,
     * enabling transformation of SQL, PL/SQL functions, procedures, and packages.</p>
     *
     * @param parseType Grammar entry point: select_statement, function_body, procedure_body, package_body
     * @param schema Optional schema for metadata context (defaults to first schema in state)
     * @param showAst Optional flag to include AST tree in response (for debugging)
     * @param oracleCode Oracle code to transform (text/plain body)
     * @return TransformationResult as JSON (always HTTP 200, check "success" field)
     */
    @POST
    @Path("/code")
    @Consumes(MediaType.TEXT_PLAIN)
    public TransformationResult transformCode(
            @QueryParam("parseType") @DefaultValue("select_statement") String parseType,
            @QueryParam("schema") String schema,
            @QueryParam("showAst") @DefaultValue("false") boolean showAst,
            String oracleCode
    ) {
        log.info("Generic transformation request: parseType={}, schema={}", parseType, schema);
        log.trace("Oracle code: {}", oracleCode);

        // Validate input
        if (oracleCode == null || oracleCode.trim().isEmpty()) {
            log.warn("Empty code received");
            return TransformationResult.failure("", "Oracle code cannot be empty");
        }

        // Validate parse type
        if (!isValidParseType(parseType)) {
            log.warn("Invalid parse type: {}", parseType);
            return TransformationResult.failure(
                    oracleCode,
                    "Invalid parse type: " + parseType + ". Valid types: select_statement, function_body, procedure_body, package_body"
            );
        }

        // Determine schema (default to first available)
        String targetSchema = schema;
        if (targetSchema == null || targetSchema.trim().isEmpty()) {
            List<String> schemas = stateService.getOracleSchemaNames();
            if (schemas == null || schemas.isEmpty()) {
                log.error("No Oracle schemas available in state");
                return TransformationResult.failure(
                        oracleCode,
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
                    oracleCode,
                    "Failed to build metadata indices: " + e.getMessage()
            );
        }

        // Route to appropriate transformation method based on parse type
        log.debug("Transforming code with parseType: {}, schema: {}, showAst: {}", parseType, targetSchema, showAst);
        TransformationResult result;

        try {
            switch (parseType.toLowerCase()) {
                case "select_statement":
                    result = transformationService.transformSql(oracleCode, targetSchema, indices, null, showAst);
                    break;

                case "function_body":
                    // For testing, create a minimal FunctionMetadata
                    // In production, this would come from proper metadata extraction
                    result = transformFunctionBody(oracleCode, targetSchema, indices, showAst);
                    break;

                case "procedure_body":
                    result = transformProcedureBody(oracleCode, targetSchema, indices, showAst);
                    break;

                case "package_body":
                    // Future: parse package body for analysis
                    return TransformationResult.failure(
                            oracleCode,
                            "Package body parsing not yet implemented. Use for analysis in future."
                    );

                default:
                    return TransformationResult.failure(
                            oracleCode,
                            "Unsupported parse type: " + parseType
                    );
            }
        } catch (Exception e) {
            log.error("Transformation failed", e);
            return TransformationResult.failure(
                    oracleCode,
                    "Transformation error: " + e.getMessage()
            );
        }

        if (result.isSuccess()) {
            log.info("Transformation succeeded for parseType: {}", parseType);
            log.debug("PostgreSQL code: {}", result.getPostgresSql());
        } else {
            log.warn("Transformation failed: {}", result.getErrorMessage());
        }

        return result;
    }

    /**
     * Helper to transform function body for testing.
     * No metadata required - function name and parameters extracted from AST.
     */
    private TransformationResult transformFunctionBody(String oracleCode, String schema, TransformationIndices indices, boolean showAst) {
        return transformationService.transformFunction(oracleCode, schema, indices, showAst);
    }

    /**
     * Helper to transform procedure body for testing.
     * No metadata required - procedure name and parameters extracted from AST.
     */
    private TransformationResult transformProcedureBody(String oracleCode, String schema, TransformationIndices indices, boolean showAst) {
        return transformationService.transformProcedure(oracleCode, schema, indices, showAst);
    }

    /**
     * Validates parse type parameter.
     */
    private boolean isValidParseType(String parseType) {
        if (parseType == null) {
            return false;
        }
        String lowerType = parseType.toLowerCase();
        return lowerType.equals("select_statement") ||
               lowerType.equals("function_body") ||
               lowerType.equals("procedure_body") ||
               lowerType.equals("package_body");
    }
}
