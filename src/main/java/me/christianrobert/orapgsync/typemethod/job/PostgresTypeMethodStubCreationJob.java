package me.christianrobert.orapgsync.typemethod.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodStubCreationResult;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.typemethod.service.TypeMethodStubSqlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Creates type method stubs in PostgreSQL — plan Phase 5.
 *
 * <p>Stubs are standalone PostgreSQL functions with the real signature and an empty body, so that
 * circular references resolve before step 29 replaces each one with
 * {@code CREATE OR REPLACE FUNCTION}. {@link TypeMethodStubSqlBuilder} owns the DDL, because the
 * signature has to match what the transformer emits later, and the write-up of why lives with it.
 *
 * <p><b>There is no "already exists" pre-check.</b> There was one, keyed on {@code pg_proc.proname}
 * alone, and it meant that from the second run onwards every overload after the first was skipped as
 * already created. Testing existence in Java duplicates a decision {@code CREATE OR REPLACE} already
 * makes correctly against the full signature — and the duplicate was wrong. Overloads differ by
 * signature, so each replaces only itself; the case where two Oracle overloads share a PostgreSQL
 * signature is caught up front by the collision guard instead of being discovered as silent
 * overwriting.
 */
@Dependent
public class PostgresTypeMethodStubCreationJob extends AbstractDatabaseWriteJob<TypeMethodStubCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTypeMethodStubCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "TYPE_METHOD_STUB_CREATION";
    }

    @Override
    public Class<TypeMethodStubCreationResult> getResultType() {
        return TypeMethodStubCreationResult.class;
    }

    @Override
    protected void saveResultsToState(TypeMethodStubCreationResult result) {
        stateService.setTypeMethodStubCreationResult(result);
    }

    @Override
    protected TypeMethodStubCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL type method stub creation process");

        // Get Oracle type methods from state
        List<TypeMethodMetadata> oracleTypeMethods = getOracleTypeMethods();
        if (oracleTypeMethods.isEmpty()) {
            updateProgress(progressCallback, 100, "No type methods to process",
                    "No Oracle type methods found in state. Please extract Oracle type methods first.");
            log.warn("No Oracle type methods found in state for stub creation");
            return new TypeMethodStubCreationResult();
        }

        // Filter valid type methods (exclude system schemas)
        List<TypeMethodMetadata> validOracleTypeMethods = filterValidTypeMethods(oracleTypeMethods);

        updateProgress(progressCallback, 10, "Analyzing type methods",
                String.format("Found %d Oracle type methods, %d are valid for creation",
                        oracleTypeMethods.size(), validOracleTypeMethods.size()));

        TypeMethodStubCreationResult result = new TypeMethodStubCreationResult();

        if (validOracleTypeMethods.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid type methods",
                    "No valid Oracle type methods to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Refuse overload sets that collapse onto one PostgreSQL signature, before creating
            // anything. Left alone, the second CREATE OR REPLACE silently overwrites the first.
            List<TypeMethodMetadata> methodsToCreate =
                    rejectSignatureCollisions(validOracleTypeMethods, result);

            updateProgress(progressCallback, 40, "Planning creation",
                    String.format("%d type methods to create, %d refused for signature collisions",
                            methodsToCreate.size(),
                            validOracleTypeMethods.size() - methodsToCreate.size()));

            if (methodsToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "Nothing to create",
                        "No type method stubs could be created");
                return result;
            }

            // Create type method stubs
            createTypeMethodStubs(postgresConnection, methodsToCreate, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d type method stubs, skipped %d, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Type method stub creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<TypeMethodMetadata> getOracleTypeMethods() {
        return stateService.getOracleTypeMethodMetadata();
    }

    private List<TypeMethodMetadata> filterValidTypeMethods(List<TypeMethodMetadata> methods) {
        List<TypeMethodMetadata> validMethods = new ArrayList<>();
        for (TypeMethodMetadata method : methods) {
            if (!filterValidSchemas(List.of(method.getSchema())).isEmpty()) {
                validMethods.add(method);
            }
        }
        return validMethods;
    }

    /**
     * Records an error for every method in a colliding overload set and returns the rest.
     *
     * <p>The whole set is refused, not all-but-one: which of them would survive is an artefact of
     * extraction order, so keeping one would be an arbitrary choice presented as a success.
     */
    private List<TypeMethodMetadata> rejectSignatureCollisions(List<TypeMethodMetadata> methods,
                                                               TypeMethodStubCreationResult result) {
        List<TypeMethodStubSqlBuilder.SignatureCollision> collisions =
                TypeMethodStubSqlBuilder.findSignatureCollisions(methods);
        if (collisions.isEmpty()) {
            return methods;
        }

        Set<String> refused = new HashSet<>();
        for (TypeMethodStubSqlBuilder.SignatureCollision collision : collisions) {
            log.error("PostgreSQL signature collision, refusing all {} methods: {}",
                    collision.methods().size(), collision.describe());

            for (TypeMethodMetadata method : collision.methods()) {
                refused.add(method.getIdentityKey());
                result.addError(method.getQualifiedName(), String.format(
                        "Oracle overloads collapse onto one PostgreSQL signature %s. Oracle "
                                + "distinguishes these methods by parameter types that the type "
                                + "mapping merges (VARCHAR2 and CHAR both become text, NUMBER(10) "
                                + "and NUMBER both become numeric), so creating them would leave one "
                                + "function silently holding the wrong body. Colliding methods: %s",
                        collision.signature(), collision.describe()), null);
            }
        }

        List<TypeMethodMetadata> remaining = new ArrayList<>();
        for (TypeMethodMetadata method : methods) {
            if (!refused.contains(method.getIdentityKey())) {
                remaining.add(method);
            }
        }
        return remaining;
    }

    private void createTypeMethodStubs(Connection connection, List<TypeMethodMetadata> methods,
                                        TypeMethodStubCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalMethods = methods.size();
        int processedMethods = 0;

        for (TypeMethodMetadata method : methods) {
            int progressPercentage = 40 + (processedMethods * 50 / totalMethods);
            String qualifiedMethodName = method.getQualifiedName();
            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating %s %s stub: %s",
                            method.getMemberTypeDescription(),
                            method.isFunction() ? "function" : "procedure",
                            qualifiedMethodName),
                    String.format("Method %d of %d", processedMethods + 1, totalMethods));

            try {
                createTypeMethodStub(connection, method);
                result.addCreatedMethod(qualifiedMethodName);
                log.debug("Successfully created PostgreSQL type method stub: {} ({})",
                        qualifiedMethodName, method.getMemberTypeDescription());
            } catch (SQLException e) {
                String sqlStatement = TypeMethodStubSqlBuilder.createStubSql(method);
                String errorMessage = String.format("Failed to create type method stub '%s': %s",
                        qualifiedMethodName, e.getMessage());
                result.addError(qualifiedMethodName, errorMessage, sqlStatement);
                log.error("Failed to create type method stub '{}': {}", qualifiedMethodName, e.getMessage());
                log.error("Failed SQL statement: {}", sqlStatement);
            }

            processedMethods++;
        }
    }

    private void createTypeMethodStub(Connection connection, TypeMethodMetadata method) throws SQLException {
        String sql = TypeMethodStubSqlBuilder.createStubSql(method);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    @Override
    protected String generateSummaryMessage(TypeMethodStubCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Type method stub creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s",
                    String.join(", ", result.getCreatedMethods().stream().limit(5).toList())));
            if (result.getCreatedCount() > 5) {
                summary.append(String.format(" and %d more", result.getCreatedCount() - 5));
            }
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
