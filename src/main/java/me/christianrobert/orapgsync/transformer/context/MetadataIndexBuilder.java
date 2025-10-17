package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds metadata indices from StateService for fast lookups during transformation.
 *
 * <p>This is the bridge between StateService and the transformation module.
 * It's the ONLY class that directly depends on StateService.
 *
 * <p>Usage:
 * <pre>
 * // In a job, build indices once at start
 * TransformationIndices indices = MetadataIndexBuilder.build(
 *     stateService,
 *     stateService.getOracleSchemaNames()
 * );
 *
 * // Pass indices to transformation service for all views
 * for (ViewMetadata view : views) {
 *     service.transformViewSql(view.getSql(), view.getSchema(), indices);
 * }
 * </pre>
 */
public class MetadataIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(MetadataIndexBuilder.class);

    /**
     * Builds transformation indices from StateService metadata.
     * Called once at start of transformation session.
     *
     * @param stateService StateService containing Oracle metadata
     * @param schemas List of schemas to include in indices
     * @return Immutable TransformationIndices ready for fast lookups
     */
    public static TransformationIndices build(StateService stateService, List<String> schemas) {
        if (stateService == null) {
            throw new IllegalArgumentException("StateService cannot be null");
        }
        if (schemas == null || schemas.isEmpty()) {
            throw new IllegalArgumentException("Schemas list cannot be null or empty");
        }

        log.info("Building transformation indices for {} schemas", schemas.size());

        // Normalize schemas to lowercase for case-insensitive lookups
        Set<String> normalizedSchemas = new HashSet<>();
        for (String schema : schemas) {
            normalizedSchemas.add(schema.toLowerCase());
        }

        // Build each index
        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns =
                indexTableColumns(stateService, normalizedSchemas);

        Map<String, Set<String>> typeMethods =
                indexTypeMethods(stateService, normalizedSchemas);

        Set<String> packageFunctions =
                indexPackageFunctions(stateService, normalizedSchemas);

        Map<String, Map<String, String>> synonyms =
                indexSynonyms(stateService);

        log.info("Indices built: {} tables, {} types with methods, {} package functions, {} synonym schemas",
                tableColumns.size(), typeMethods.size(), packageFunctions.size(), synonyms.size());

        return new TransformationIndices(tableColumns, typeMethods, packageFunctions, synonyms);
    }

    /**
     * Builds table column index: "schema.table" → column → type info.
     */
    private static Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> indexTableColumns(
            StateService stateService, Set<String> schemas) {

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> index = new HashMap<>();
        int columnCount = 0;

        for (TableMetadata table : stateService.getOracleTableMetadata()) {
            String normalizedSchema = table.getSchema().toLowerCase();

            if (!schemas.contains(normalizedSchema)) {
                continue; // Skip tables not in target schemas
            }

            String qualifiedTable = normalizedSchema + "." + table.getTableName().toLowerCase();
            Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();

            for (ColumnMetadata column : table.getColumns()) {
                String columnName = column.getColumnName().toLowerCase();
                String typeName = column.getDataType();
                String typeOwner = column.getDataTypeOwner();

                // Normalize type owner (may be null for built-in types)
                if (typeOwner != null) {
                    typeOwner = typeOwner.toLowerCase();
                }

                columns.put(columnName, new TransformationIndices.ColumnTypeInfo(typeName, typeOwner));
                columnCount++;
            }

            index.put(qualifiedTable, columns);
        }

        log.debug("Indexed {} columns across {} tables", columnCount, index.size());
        return index;
    }

    /**
     * Builds type method index: "schema.typename" → Set of method names.
     */
    private static Map<String, Set<String>> indexTypeMethods(
            StateService stateService, Set<String> schemas) {

        Map<String, Set<String>> index = new HashMap<>();
        int methodCount = 0;

        for (TypeMethodMetadata method : stateService.getOracleTypeMethodMetadata()) {
            String normalizedSchema = method.getSchema().toLowerCase();

            if (!schemas.contains(normalizedSchema)) {
                continue; // Skip methods not in target schemas
            }

            String qualifiedType = normalizedSchema + "." + method.getTypeName().toLowerCase();
            String methodName = method.getMethodName().toLowerCase();

            index.computeIfAbsent(qualifiedType, k -> new HashSet<>()).add(methodName);
            methodCount++;
        }

        log.debug("Indexed {} methods across {} types", methodCount, index.size());
        return index;
    }

    /**
     * Builds package function index: Set of "schema.package.function".
     */
    private static Set<String> indexPackageFunctions(
            StateService stateService, Set<String> schemas) {

        Set<String> index = new HashSet<>();

        for (FunctionMetadata function : stateService.getOracleFunctionMetadata()) {
            String normalizedSchema = function.getSchema().toLowerCase();

            if (!schemas.contains(normalizedSchema)) {
                continue; // Skip functions not in target schemas
            }

            // Only index package functions (not standalone functions)
            if (function.getPackageName() != null && !function.getPackageName().isEmpty()) {
                String qualifiedFunction = normalizedSchema + "." +
                        function.getPackageName().toLowerCase() + "." +
                        function.getObjectName().toLowerCase();

                index.add(qualifiedFunction);
            }
        }

        log.debug("Indexed {} package functions", index.size());
        return index;
    }

    /**
     * Builds synonym index from StateService synonym map.
     * Preserves StateService's dual-map structure: schema → synonym → target.
     */
    private static Map<String, Map<String, String>> indexSynonyms(StateService stateService) {
        Map<String, Map<String, String>> index = new HashMap<>();
        int synonymCount = 0;

        for (Map.Entry<String, Map<String, SynonymMetadata>> schemaEntry :
                stateService.getOracleSynonymsByOwnerAndName().entrySet()) {

            String schema = schemaEntry.getKey().toLowerCase();
            Map<String, String> schemaSynonyms = new HashMap<>();

            for (Map.Entry<String, SynonymMetadata> synonymEntry : schemaEntry.getValue().entrySet()) {
                String synonymName = synonymEntry.getKey().toLowerCase();
                SynonymMetadata synonym = synonymEntry.getValue();

                String target = synonym.getTableOwner().toLowerCase() + "." +
                        synonym.getTableName().toLowerCase();

                schemaSynonyms.put(synonymName, target);
                synonymCount++;
            }

            if (!schemaSynonyms.isEmpty()) {
                index.put(schema, schemaSynonyms);
            }
        }

        log.debug("Indexed {} synonyms across {} schemas", synonymCount, index.size());
        return index;
    }

    /**
     * Creates empty indices (for testing with no metadata).
     */
    public static TransformationIndices buildEmpty() {
        return new TransformationIndices(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }
}
