package me.christianrobert.orapgsync.typemethod.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.typemethod.service.OracleTypeMethodExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Extracts type method (member function/procedure) metadata from Oracle database.
 * This job extracts method signatures from ALL_TYPE_METHODS and ALL_METHOD_PARAMS.
 */
@Dependent
public class OracleTypeMethodExtractionJob extends AbstractDatabaseExtractionJob<TypeMethodMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleTypeMethodExtractionJob.class);

    @Inject
    private OracleTypeMethodExtractor oracleTypeMethodExtractor;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "TYPE_METHOD";
    }

    @Override
    public Class<TypeMethodMetadata> getResultType() {
        return TypeMethodMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TypeMethodMetadata> results) {
        stateService.setOracleTypeMethodMetadata(results);
    }

    @Override
    protected List<TypeMethodMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for type method extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting type method extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting type method extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Extracting type methods", "Processing schemas");

        List<TypeMethodMetadata> allTypeMethods = oracleTypeMethodExtractor.extractTypeMethods(
                validSchemas, progressCallback);

        return allTypeMethods;
    }

    @Override
    protected String generateSummaryMessage(List<TypeMethodMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        int memberMethods = 0;
        int staticMethods = 0;
        int functions = 0;
        int procedures = 0;

        for (TypeMethodMetadata method : results) {
            String schema = method.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);

            if (method.isMemberMethod()) {
                memberMethods++;
            } else if (method.isStaticMethod()) {
                staticMethods++;
            }

            if (method.isFunction()) {
                functions++;
            } else if (method.isProcedure()) {
                procedures++;
            }
        }

        return String.format("Extraction completed: %d type methods (%d member, %d static, %d functions, %d procedures) from %d schemas",
                results.size(), memberMethods, staticMethods, functions, procedures, schemaSummary.size());
    }
}
