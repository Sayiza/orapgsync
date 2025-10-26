package me.christianrobert.orapgsync.core.job.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import me.christianrobert.orapgsync.constraint.rest.ConstraintResource;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.JobStatus;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import me.christianrobert.orapgsync.core.job.model.schema.SchemaCreationResult;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceCreationResult;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.FKIndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationVerificationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewStubCreationResult;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionStubCreationResult;
import me.christianrobert.orapgsync.core.job.model.function.StandaloneFunctionImplementationResult;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodStubCreationResult;
import me.christianrobert.orapgsync.core.job.service.JobService;
import me.christianrobert.orapgsync.function.rest.FunctionResource;
import me.christianrobert.orapgsync.objectdatatype.rest.ObjectTypeResource;
import me.christianrobert.orapgsync.oraclecompat.model.OracleCompatInstallationResult;
import me.christianrobert.orapgsync.oraclecompat.model.OracleCompatVerificationResult;
import me.christianrobert.orapgsync.schema.rest.SchemaResource;
import me.christianrobert.orapgsync.sequence.rest.SequenceResource;
import me.christianrobert.orapgsync.table.rest.TableResource;
import me.christianrobert.orapgsync.transfer.rest.DataTransferResource;
import me.christianrobert.orapgsync.typemethod.rest.TypeMethodResource;
import me.christianrobert.orapgsync.view.rest.ViewResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic REST resource for job status and result retrieval.
 * Domain-specific job endpoints are in their respective resource classes.
 */
@ApplicationScoped
@Path("/api/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JobResource {

    private static final Logger log = LoggerFactory.getLogger(JobResource.class);

    @Inject
    JobService jobService;

    @GET
    @Path("/{jobId}/status")
    public Response getJobStatus(@PathParam("jobId") String jobId) {
        log.debug("Getting job status for: {}", jobId);

        try {
            JobService.JobExecution<?> execution = jobService.getJobExecution(jobId);

            if (execution == null) {
                Map<String, Object> errorResult = Map.of(
                        "status", "error",
                        "message", "Job not found: " + jobId
                );
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(errorResult)
                        .build();
            }

            JobStatus status = execution.getStatus();
            JobProgress progress = execution.getProgress();

            Map<String, Object> result = new HashMap<>();
            result.put("jobId", jobId);
            result.put("jobType", execution.getJob().getJobType());
            result.put("status", status.name());
            result.put("isComplete", jobService.isJobComplete(jobId));

            if (progress != null) {
                Map<String, Object> progressInfo = Map.of(
                        "percentage", progress.getPercentage(),
                        "currentTask", progress.getCurrentTask(),
                        "details", progress.getDetails(),
                        "lastUpdated", progress.getLastUpdated().toString()
                );
                result.put("progress", progressInfo);
            }

            if (status == JobStatus.FAILED) {
                Exception error = jobService.getJobError(jobId);
                if (error != null) {
                    result.put("error", error.getMessage());
                }
            }

            if (execution.getStartTime() != null) {
                result.put("startTime", execution.getStartTime().toString());
            }

            if (execution.getEndTime() != null) {
                result.put("endTime", execution.getEndTime().toString());
            }

            return Response.ok(result).build();

        } catch (Exception e) {
            log.error("Error getting job status for: " + jobId, e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "message", "Error getting job status: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }

    @GET
    @Path("/{jobId}/result")
    public Response getJobResult(@PathParam("jobId") String jobId) {
        log.debug("Getting job result for: {}", jobId);

        try {
            JobService.JobExecution<?> execution = jobService.getJobExecution(jobId);

            if (execution == null) {
                Map<String, Object> errorResult = Map.of(
                        "status", "error",
                        "message", "Job not found: " + jobId
                );
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(errorResult)
                        .build();
            }

            if (!jobService.isJobComplete(jobId)) {
                Map<String, Object> errorResult = Map.of(
                        "status", "error",
                        "message", "Job is not yet complete: " + jobId
                );
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorResult)
                        .build();
            }

            if (execution.getStatus() == JobStatus.FAILED) {
                Exception error = jobService.getJobError(jobId);
                Map<String, Object> errorResult = Map.of(
                        "status", "failed",
                        "jobId", jobId,
                        "message", error != null ? error.getMessage() : "Job failed with unknown error"
                );
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorResult)
                        .build();
            }

            Object result = jobService.getJobResult(jobId);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("jobId", jobId);
            response.put("jobType", execution.getJob().getJobType());

            // Handle different job result types - delegate to domain resources for summaries
            String jobType = execution.getJob().getJobType();

            // Check result object type first (more specific), then fall back to jobType string matching for Lists
            if (result instanceof FKIndexCreationResult) {
                FKIndexCreationResult fkIndexResult = (FKIndexCreationResult) result;
                Map<String, Object> summary = ConstraintResource.generateFKIndexCreationSummary(fkIndexResult);
                response.put("summary", summary);
                response.put("createdCount", fkIndexResult.getCreatedCount());
                response.put("skippedCount", fkIndexResult.getSkippedCount());
                response.put("errorCount", fkIndexResult.getErrorCount());
                response.put("isSuccessful", fkIndexResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof ConstraintCreationResult) {
                ConstraintCreationResult constraintResult = (ConstraintCreationResult) result;
                Map<String, Object> summary = ConstraintResource.generateConstraintCreationSummary(constraintResult);
                response.put("summary", summary);
                response.put("createdCount", constraintResult.getCreatedCount());
                response.put("skippedCount", constraintResult.getSkippedCount());
                response.put("errorCount", constraintResult.getErrorCount());
                response.put("isSuccessful", constraintResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof SequenceCreationResult) {
                SequenceCreationResult sequenceResult = (SequenceCreationResult) result;
                Map<String, Object> summary = SequenceResource.generateSequenceCreationSummary(sequenceResult);
                response.put("summary", summary);
                response.put("createdCount", sequenceResult.getCreatedCount());
                response.put("skippedCount", sequenceResult.getSkippedCount());
                response.put("errorCount", sequenceResult.getErrorCount());
                response.put("isSuccessful", sequenceResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof SchemaCreationResult) {
                SchemaCreationResult schemaResult = (SchemaCreationResult) result;
                Map<String, Object> summary = SchemaResource.generateSchemaCreationSummary(schemaResult);
                response.put("summary", summary);
                response.put("createdCount", schemaResult.getCreatedCount());
                response.put("skippedCount", schemaResult.getSkippedCount());
                response.put("errorCount", schemaResult.getErrorCount());
                response.put("isSuccessful", schemaResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof ObjectTypeCreationResult) {
                ObjectTypeCreationResult objectTypeResult = (ObjectTypeCreationResult) result;
                Map<String, Object> summary = ObjectTypeResource.generateObjectTypeCreationSummary(objectTypeResult);
                response.put("summary", summary);
                response.put("createdCount", objectTypeResult.getCreatedCount());
                response.put("skippedCount", objectTypeResult.getSkippedCount());
                response.put("errorCount", objectTypeResult.getErrorCount());
                response.put("isSuccessful", objectTypeResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof TableCreationResult) {
                TableCreationResult tableResult = (TableCreationResult) result;
                Map<String, Object> summary = TableResource.generateTableCreationSummary(tableResult);
                response.put("summary", summary);
                response.put("createdCount", tableResult.getCreatedCount());
                response.put("skippedCount", tableResult.getSkippedCount());
                response.put("errorCount", tableResult.getErrorCount());
                response.put("isSuccessful", tableResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof DataTransferResult) {
                DataTransferResult transferResult = (DataTransferResult) result;
                Map<String, Object> summary = DataTransferResource.generateDataTransferSummary(transferResult);
                response.put("summary", summary);
                response.put("transferredCount", transferResult.getTransferredCount());
                response.put("skippedCount", transferResult.getSkippedCount());
                response.put("errorCount", transferResult.getErrorCount());
                response.put("totalRowsTransferred", transferResult.getTotalRowsTransferred());
                response.put("isSuccessful", transferResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof ViewStubCreationResult) {
                ViewStubCreationResult viewStubResult = (ViewStubCreationResult) result;
                Map<String, Object> summary = ViewResource.generateViewStubCreationSummary(viewStubResult);
                response.put("summary", summary);
                response.put("createdCount", viewStubResult.getCreatedCount());
                response.put("skippedCount", viewStubResult.getSkippedCount());
                response.put("errorCount", viewStubResult.getErrorCount());
                response.put("isSuccessful", viewStubResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof FunctionStubCreationResult) {
                FunctionStubCreationResult functionStubResult = (FunctionStubCreationResult) result;
                Map<String, Object> summary = FunctionResource.generateFunctionStubCreationSummary(functionStubResult);
                response.put("summary", summary);
                response.put("createdCount", functionStubResult.getCreatedCount());
                response.put("skippedCount", functionStubResult.getSkippedCount());
                response.put("errorCount", functionStubResult.getErrorCount());
                response.put("isSuccessful", functionStubResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof StandaloneFunctionImplementationResult) {
                StandaloneFunctionImplementationResult standaloneFuncImplResult = (StandaloneFunctionImplementationResult) result;
                Map<String, Object> summary = FunctionResource.generateStandaloneFunctionImplementationSummary(standaloneFuncImplResult);
                response.put("summary", summary);
                response.put("implementedCount", standaloneFuncImplResult.getImplementedCount());
                response.put("skippedCount", standaloneFuncImplResult.getSkippedCount());
                response.put("errorCount", standaloneFuncImplResult.getErrorCount());
                response.put("isSuccessful", standaloneFuncImplResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof TypeMethodStubCreationResult) {
                TypeMethodStubCreationResult typeMethodStubResult = (TypeMethodStubCreationResult) result;
                Map<String, Object> summary = TypeMethodResource.generateTypeMethodStubCreationSummary(typeMethodStubResult);
                response.put("summary", summary);
                response.put("createdCount", typeMethodStubResult.getCreatedCount());
                response.put("skippedCount", typeMethodStubResult.getSkippedCount());
                response.put("errorCount", typeMethodStubResult.getErrorCount());
                response.put("isSuccessful", !typeMethodStubResult.hasErrors());
                response.put("result", result);
            } else if (result instanceof ViewImplementationResult) {
                ViewImplementationResult viewImplResult = (ViewImplementationResult) result;
                Map<String, Object> summary = ViewResource.generateViewImplementationSummary(viewImplResult);
                response.put("summary", summary);
                response.put("implementedCount", viewImplResult.getImplementedCount());
                response.put("skippedCount", viewImplResult.getSkippedCount());
                response.put("errorCount", viewImplResult.getErrorCount());
                response.put("isSuccessful", viewImplResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof ViewImplementationVerificationResult) {
                ViewImplementationVerificationResult viewImplVerifyResult = (ViewImplementationVerificationResult) result;
                Map<String, Object> summary = ViewResource.generateViewImplementationVerificationSummary(viewImplVerifyResult);
                response.put("summary", summary);
                response.put("verifiedCount", viewImplVerifyResult.getVerifiedCount());
                response.put("failedCount", viewImplVerifyResult.getFailedCount());
                response.put("warningCount", viewImplVerifyResult.getWarningCount());
                response.put("isSuccessful", viewImplVerifyResult.isSuccessful());
                response.put("result", result);
            } else if (result instanceof OracleCompatInstallationResult) {
                OracleCompatInstallationResult oracleCompatInstallResult = (OracleCompatInstallationResult) result;
                response.put("totalFunctions", oracleCompatInstallResult.getTotalFunctions());
                response.put("installedFull", oracleCompatInstallResult.getInstalledFull());
                response.put("installedPartial", oracleCompatInstallResult.getInstalledPartial());
                response.put("installedStubs", oracleCompatInstallResult.getInstalledStubs());
                response.put("failed", oracleCompatInstallResult.getFailed());
                response.put("errorMessages", oracleCompatInstallResult.getErrorMessages());
                response.put("executionTimeMs", oracleCompatInstallResult.getExecutionTimeMs());
                response.put("result", result);
            } else if (result instanceof List<?> && jobType.contains("ORACLE_COMPAT_VERIFICATION")) {
                @SuppressWarnings("unchecked")
                List<OracleCompatVerificationResult> oracleCompatVerifyResults = (List<OracleCompatVerificationResult>) result;
                if (!oracleCompatVerifyResults.isEmpty()) {
                    OracleCompatVerificationResult oracleCompatVerifyResult = oracleCompatVerifyResults.get(0);
                    response.put("totalExpected", oracleCompatVerifyResult.getTotalExpected());
                    response.put("verified", oracleCompatVerifyResult.getVerified());
                    response.put("missing", oracleCompatVerifyResult.getMissing());
                    response.put("errors", oracleCompatVerifyResult.getErrors());
                    response.put("executionTimeMs", oracleCompatVerifyResult.getExecutionTimeMs());
                    response.put("result", oracleCompatVerifyResult);
                }
            } else if (result instanceof List<?>) {
                // Handle List results based on jobType
                if (jobType.contains("SCHEMA") && !jobType.contains("SCHEMA_CREATION")) {
                    @SuppressWarnings("unchecked")
                    List<String> schemas = (List<String>) result;
                    Map<String, Object> summary = SchemaResource.generateSchemaExtractionSummary(schemas);
                    response.put("summary", summary);
                    response.put("count", schemas.size());
                    response.put("schemas", schemas);
                } else if (jobType.contains("TABLE_METADATA")) {
                    @SuppressWarnings("unchecked")
                    List<TableMetadata> tableMetadata = (List<TableMetadata>) result;
                    Map<String, Object> summary = TableResource.generateTableMetadataSummary(tableMetadata);
                    response.put("summary", summary);
                    response.put("tableCount", tableMetadata.size());
                } else if (jobType.contains("OBJECT_DATATYPE")) {
                    @SuppressWarnings("unchecked")
                    List<ObjectDataTypeMetaData> objectDataTypes = (List<ObjectDataTypeMetaData>) result;
                    Map<String, Object> summary = ObjectTypeResource.generateObjectDataTypeSummary(objectDataTypes);
                    response.put("summary", summary);
                    response.put("objectDataTypeCount", objectDataTypes.size());
                    response.put("result", result);
                } else if (jobType.contains("ROW_COUNT")) {
                    @SuppressWarnings("unchecked")
                    List<RowCountMetadata> rowCounts = (List<RowCountMetadata>) result;
                    Map<String, Object> summary = DataTransferResource.generateRowCountSummary(rowCounts);
                    response.put("summary", summary);
                    response.put("rowCountDataCount", rowCounts.size());
                    response.put("result", result);
                } else if (jobType.contains("SYNONYM")) {
                    @SuppressWarnings("unchecked")
                    List<?> synonyms = (List<?>) result;
                    Map<String, Object> summary = DataTransferResource.generateSynonymSummary(synonyms);
                    response.put("summary", summary);
                    response.put("synonymCount", synonyms.size());
                    response.put("result", result);
                } else if (jobType.contains("SEQUENCE") && !jobType.contains("SEQUENCE_CREATION")) {
                    @SuppressWarnings("unchecked")
                    List<SequenceMetadata> sequences = (List<SequenceMetadata>) result;
                    Map<String, Object> summary = SequenceResource.generateSequenceSummary(sequences);
                    response.put("summary", summary);
                    response.put("sequenceCount", sequences.size());
                    response.put("result", result);
                } else if (jobType.contains("CONSTRAINT") && !jobType.contains("CONSTRAINT_CREATION")) {
                    @SuppressWarnings("unchecked")
                    List<ConstraintMetadata> constraints = (List<ConstraintMetadata>) result;
                    Map<String, Object> summary = ConstraintResource.generateConstraintSummary(constraints);
                    response.put("summary", summary);
                    response.put("constraintCount", constraints.size());
                    response.put("result", result);
                } else if (jobType.contains("VIEW")
                        && !jobType.contains("VIEW_STUB_CREATION")
                        && !jobType.contains("VIEW_IMPLEMENTATION")
                        && !jobType.contains("VIEW_IMPLEMENTATION_VERIFICATION")) {
                    @SuppressWarnings("unchecked")
                    List<ViewMetadata> viewDefinitions = (List<ViewMetadata>) result;
                    Map<String, Object> summary = ViewResource.generateViewDefinitionSummary(viewDefinitions);
                    response.put("summary", summary);
                    response.put("viewCount", viewDefinitions.size());
                    response.put("result", result);
                } else if (jobType.contains("FUNCTION") && !jobType.contains("FUNCTION_STUB_CREATION")) {
                    @SuppressWarnings("unchecked")
                    List<FunctionMetadata> functions = (List<FunctionMetadata>) result;
                    Map<String, Object> summary = FunctionResource.generateFunctionSummary(functions);
                    response.put("summary", summary);
                    response.put("functionCount", functions.size());
                    response.put("result", result);
                } else if (jobType.contains("TYPE_METHOD") && !jobType.contains("TYPE_METHOD_STUB_CREATION")) {
                    @SuppressWarnings("unchecked")
                    List<TypeMethodMetadata> typeMethods = (List<TypeMethodMetadata>) result;
                    Map<String, Object> summary = TypeMethodResource.generateTypeMethodSummary(typeMethods);
                    response.put("summary", summary);
                    response.put("typeMethodCount", typeMethods.size());
                    response.put("result", result);
                } else {
                    // Generic list result
                    response.put("result", result);
                }
            } else {
                response.put("result", result);
            }

            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Error getting job result for: " + jobId, e);

            Map<String, Object> errorResult = Map.of(
                    "status", "error",
                    "message", "Error getting job result: " + e.getMessage()
            );

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResult)
                    .build();
        }
    }
}
