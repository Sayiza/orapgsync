package me.christianrobert.orapgsync.trigger.transformer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main orchestrator for Oracle-to-PostgreSQL trigger transformation.
 *
 * <p>This service coordinates the entire trigger transformation pipeline:</p>
 * <ol>
 *   <li>Extract and clean Oracle trigger body</li>
 *   <li>Wrap trigger body in procedure wrapper (for ANTLR parsing)</li>
 *   <li>Transform PL/SQL → PL/pgSQL (using existing {@link TransformationService})</li>
 *   <li>Unwrap transformed body (extract BEGIN...END block)</li>
 *   <li>Remove colons from :NEW/:OLD (using {@link ColonReferenceTransformer})</li>
 *   <li>Inject RETURN statement (using {@link TriggerReturnInjector})</li>
 *   <li>Generate trigger function DDL (using {@link TriggerFunctionGenerator})</li>
 *   <li>Generate trigger definition DDL (using {@link TriggerDefinitionGenerator})</li>
 * </ol>
 *
 * <h3>Architecture:</h3>
 * <pre>
 * Oracle Trigger Body (anonymous block: BEGIN...END)
 *   ↓
 * CodeCleaner.removeComments()
 *   ↓
 * Wrap in PROCEDURE wrapper (ANTLR expects full procedure)
 *   ↓
 * TransformationService.transformProcedure() [PL/SQL → PL/pgSQL]
 *   ↓
 * Unwrap body (extract BEGIN...END from transformed procedure)
 *   ↓
 * ColonReferenceTransformer.removeColonReferences() [:NEW → NEW]
 *   ↓
 * TriggerReturnInjector.injectReturn() [Add RETURN NEW/NULL]
 *   ↓
 * TriggerFunctionGenerator.generateFunctionDdl() [CREATE FUNCTION]
 *   ↓
 * TriggerDefinitionGenerator.generateTriggerDdl() [CREATE TRIGGER]
 *   ↓
 * TriggerDdlPair (functionDdl + triggerDdl)
 * </pre>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * &#64;Inject
 * TriggerTransformer triggerTransformer;
 *
 * TriggerMetadata oracleTrigger = ...; // From Oracle extraction
 * TransformationIndices indices = ...; // From MetadataIndexBuilder
 *
 * TriggerDdlPair ddl = triggerTransformer.transformTrigger(oracleTrigger, indices);
 *
 * // Execute in PostgreSQL:
 * execute(ddl.getFunctionDdl());
 * execute(ddl.getTriggerDdl());
 * </pre>
 */
@ApplicationScoped
public class TriggerTransformer {

    private static final Logger log = LoggerFactory.getLogger(TriggerTransformer.class);

    @Inject
    TransformationService transformationService;

    /**
     * Transforms a single Oracle trigger to PostgreSQL DDL (function + trigger).
     *
     * @param metadata Oracle trigger metadata (includes trigger body)
     * @param indices Transformation indices for metadata lookups
     * @return Pair containing function DDL and trigger DDL
     * @throws TriggerTransformationException if transformation fails
     */
    public TriggerDdlPair transformTrigger(TriggerMetadata metadata, TransformationIndices indices) {
        String qualifiedName = metadata.getQualifiedName();
        log.debug("Starting transformation for trigger: {}", qualifiedName);

        try {
            // Step 1: Extract and validate trigger body
            String oracleTriggerBody = metadata.getTriggerBody();
            if (oracleTriggerBody == null || oracleTriggerBody.trim().isEmpty()) {
                throw new TriggerTransformationException(
                    "Trigger body is empty: " + qualifiedName);
            }

            // Step 2: Clean comments (important for colon removal)
            String cleanedBody = CodeCleaner.removeComments(oracleTriggerBody);
            log.trace("Cleaned trigger body for {}: {}", qualifiedName, cleanedBody);

            // Step 3: Wrap trigger body in procedure wrapper for ANTLR parsing
            // Trigger bodies are anonymous blocks (BEGIN...END), but ANTLR expects PROCEDURE...IS BEGIN...END
            String wrappedBody = wrapTriggerBodyForParsing(cleanedBody);
            log.trace("Wrapped body for {}: {}", qualifiedName, wrappedBody);

            // Step 4: Transform PL/SQL → PL/pgSQL using existing infrastructure
            TransformationResult transformationResult = transformationService.transformProcedure(
                wrappedBody,
                metadata.getSchema(),
                indices
            );

            if (transformationResult.isFailure()) {
                throw new TriggerTransformationException(
                    "PL/SQL transformation failed for " + qualifiedName + ": " +
                    transformationResult.getErrorMessage());
            }

            // Step 5: Unwrap the transformed body (extract BEGIN...END block from procedure)
            String transformedBody = unwrapTransformedBody(transformationResult.getPostgresSql());
            log.trace("Transformed body for {}: {}", qualifiedName, transformedBody);

            // Step 6: Remove colons from :NEW/:OLD
            String withoutColons = ColonReferenceTransformer.removeColonReferences(transformedBody);
            log.trace("After colon removal for {}: {}", qualifiedName, withoutColons);

            // Step 7: Inject RETURN statement
            String withReturn = TriggerReturnInjector.injectReturn(
                withoutColons,
                metadata.getTriggerType(),
                metadata.getTriggerLevel()
            );
            log.trace("After RETURN injection for {}: {}", qualifiedName, withReturn);

            // Step 8: Generate trigger function DDL
            String functionDdl = TriggerFunctionGenerator.generateFunctionDdl(
                metadata,
                withReturn
            );
            log.debug("Generated function DDL for {}", qualifiedName);

            // Step 9: Generate trigger definition DDL
            String triggerDdl = TriggerDefinitionGenerator.generateTriggerDdl(metadata);
            log.debug("Generated trigger DDL for {}", qualifiedName);

            log.info("Successfully transformed trigger: {}", qualifiedName);
            return new TriggerDdlPair(functionDdl, triggerDdl);

        } catch (TriggerTransformationException e) {
            // Re-throw our own exceptions
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error transforming trigger: " + qualifiedName, e);
            throw new TriggerTransformationException(
                "Unexpected error transforming trigger " + qualifiedName + ": " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Wraps a trigger body in a procedure wrapper for ANTLR parsing.
     *
     * <p>Trigger bodies are anonymous blocks that start with BEGIN (or DECLARE...BEGIN).
     * ANTLR's PL/SQL parser expects a full procedure definition with PROCEDURE name IS BEGIN...END.
     * This method wraps the trigger body so ANTLR can parse it.</p>
     *
     * @param triggerBody Trigger body (may start with DECLARE or BEGIN)
     * @return Wrapped procedure definition
     */
    private String wrapTriggerBodyForParsing(String triggerBody) {
        String trimmed = triggerBody.trim();

        // If body starts with DECLARE, keep it
        // Otherwise, just prepend the procedure wrapper before BEGIN
        if (trimmed.toUpperCase().startsWith("DECLARE")) {
            // PROCEDURE trigger_temp_wrapper IS
            // DECLARE ... BEGIN ... END
            return "PROCEDURE trigger_temp_wrapper IS\n" + trimmed;
        } else {
            // PROCEDURE trigger_temp_wrapper IS
            // BEGIN ... END
            return "PROCEDURE trigger_temp_wrapper IS\n" + trimmed;
        }
    }

    /**
     * Unwraps the transformed procedure body to extract just the BEGIN...END block.
     *
     * <p>After transformation, the result is a complete PostgreSQL function definition
     * like:</p>
     * <pre>
     * CREATE OR REPLACE FUNCTION schema.trigger_temp_wrapper()
     * RETURNS void
     * LANGUAGE plpgsql
     * AS $$
     * DECLARE ... (if any)
     * BEGIN
     *   ...
     * END;$$;
     * </pre>
     *
     * <p>We need to extract just the body (DECLARE...BEGIN...END or BEGIN...END) for
     * use in the trigger function.</p>
     *
     * @param transformedProcedure Full transformed procedure DDL
     * @return Just the procedure body (DECLARE...BEGIN...END or BEGIN...END)
     */
    private String unwrapTransformedBody(String transformedProcedure) {
        // The transformed result is a complete function DDL
        // We need to extract the body between AS $$ and $$

        String upper = transformedProcedure.toUpperCase();
        int asIndex = upper.indexOf("AS $$");

        if (asIndex == -1) {
            // Fallback: try with different quoting
            asIndex = upper.indexOf("AS $");
            if (asIndex == -1) {
                // If we can't find AS $$, just return the whole thing
                // (this shouldn't happen, but safe fallback)
                log.warn("Could not find AS $$ in transformed body, returning as-is");
                return transformedProcedure;
            }
        }

        // Find the start of the body (after AS $$)
        int bodyStart = transformedProcedure.indexOf('\n', asIndex + 5);
        if (bodyStart == -1) {
            bodyStart = asIndex + 5;
        } else {
            bodyStart++; // Skip the newline
        }

        // Find the closing $$
        int bodyEnd = transformedProcedure.lastIndexOf("$$");

        if (bodyEnd == -1 || bodyEnd <= bodyStart) {
            // Fallback
            log.warn("Could not find closing $$ in transformed body, returning from AS $$ onwards");
            return transformedProcedure.substring(bodyStart).trim();
        }

        // Extract the body
        String body = transformedProcedure.substring(bodyStart, bodyEnd).trim();

        return body;
    }

    /**
     * Result object containing both DDL statements for a trigger.
     *
     * <p>PostgreSQL triggers require two DDL statements:
     * <ol>
     *   <li><strong>Function DDL</strong> - CREATE FUNCTION statement containing trigger logic</li>
     *   <li><strong>Trigger DDL</strong> - CREATE TRIGGER statement binding function to table</li>
     * </ol>
     *
     * <p>These must be executed in order: function first, then trigger.</p>
     */
    public static class TriggerDdlPair {
        private final String functionDdl;
        private final String triggerDdl;

        public TriggerDdlPair(String functionDdl, String triggerDdl) {
            this.functionDdl = functionDdl;
            this.triggerDdl = triggerDdl;
        }

        /**
         * Gets the CREATE FUNCTION DDL (must be executed first).
         *
         * @return Function DDL statement
         */
        public String getFunctionDdl() {
            return functionDdl;
        }

        /**
         * Gets the CREATE TRIGGER DDL (must be executed second).
         *
         * @return Trigger DDL statement
         */
        public String getTriggerDdl() {
            return triggerDdl;
        }
    }

    /**
     * Exception thrown when trigger transformation fails.
     */
    public static class TriggerTransformationException extends RuntimeException {
        public TriggerTransformationException(String message) {
            super(message);
        }

        public TriggerTransformationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
