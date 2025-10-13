package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.function.service.OracleFunctionExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Oracle Function Extraction Job.
 * Extracts function and procedure metadata from Oracle database including:
 * - Standalone functions
 * - Standalone procedures
 * - Package functions
 * - Package procedures
 *
 * Uses ALL_PROCEDURES and ALL_ARGUMENTS views to extract complete function signatures.
 */
@Dependent
public class OracleFunctionExtractionJob extends AbstractDatabaseExtractionJob<FunctionMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleFunctionExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "FUNCTION";
    }

    @Override
    public Class<FunctionMetadata> getResultType() {
        return FunctionMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<FunctionMetadata> results) {
        stateService.setOracleFunctionMetadata(results);
    }

    @Override
    protected List<FunctionMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for function extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting function/procedure extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting Oracle function/procedure extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<FunctionMetadata> allFunctionMetadata = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<FunctionMetadata> schemaFunctionMetadata =
                            OracleFunctionExtractor.extractAllFunctions(oracleConnection, List.of(schema));

                    allFunctionMetadata.addAll(schemaFunctionMetadata);

                    log.info("Extracted {} functions/procedures from schema {}", schemaFunctionMetadata.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract functions for schema: " + schema, e);
                    updateProgress(progressCallback,
                            10 + (processedSchemas * 80 / totalSchemas),
                            "Error in schema: " + schema,
                            "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            return allFunctionMetadata;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Function extraction failed: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(List<FunctionMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        long functionCount = results.stream().filter(FunctionMetadata::isFunction).count();
        long procedureCount = results.stream().filter(FunctionMetadata::isProcedure).count();
        long standaloneCount = results.stream().filter(FunctionMetadata::isStandalone).count();
        long packageMemberCount = results.stream().filter(FunctionMetadata::isPackageMember).count();

        for (FunctionMetadata function : results) {
            String schema = function.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);
        }

        return String.format("Extraction completed: %d functions/procedures from %d schemas " +
                        "(%d functions, %d procedures; %d standalone, %d package members)",
                results.size(), schemaSummary.size(),
                functionCount, procedureCount, standaloneCount, packageMemberCount);
    }
}
