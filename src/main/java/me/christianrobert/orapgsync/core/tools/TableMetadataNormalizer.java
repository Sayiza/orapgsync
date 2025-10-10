package me.christianrobert.orapgsync.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.service.StateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for normalizing table metadata by resolving synonym references in column data types.
 *
 * <h2>Purpose</h2>
 * Oracle allows columns to reference types via synonyms (e.g., PUBLIC.LANGDATA → CO_TYPES.LANGDATA).
 * Oracle's metadata stores the synonym name as used in DDL, not the resolved target type.
 * PostgreSQL doesn't have synonyms, so we must resolve them to actual type names during migration.
 *
 * <h2>Usage</h2>
 * This normalizer should be used by any component that needs to work with type names:
 * <ul>
 *   <li>Table Creation: Resolve synonyms to generate correct DDL (CREATE TABLE with proper type references)</li>
 *   <li>Data Transfer: Resolve synonyms to correctly classify types for serialization logic</li>
 * </ul>
 *
 * <h2>Design Rationale</h2>
 * Centralizing normalization logic in core/tools:
 * <ul>
 *   <li>Avoids code duplication between table and transfer modules</li>
 *   <li>Prevents direct dependencies between domain modules</li>
 *   <li>Follows existing pattern (TypeConverter, OracleTypeClassifier)</li>
 *   <li>Single source of truth for synonym resolution</li>
 * </ul>
 *
 * @see StateService#resolveSynonym(String, String)
 * @see me.christianrobert.orapgsync.table.job.PostgresTableCreationJob
 * @see me.christianrobert.orapgsync.transfer.job.DataTransferJob
 */
@ApplicationScoped
public class TableMetadataNormalizer {

    private static final Logger log = LoggerFactory.getLogger(TableMetadataNormalizer.class);

    @Inject
    private StateService stateService;

    /**
     * Normalizes a list of tables by resolving all synonym references in column data types.
     *
     * @param tables The original tables with potential synonym references
     * @return Normalized copies with all synonyms resolved to their targets
     */
    public List<TableMetadata> normalizeTableMetadata(List<TableMetadata> tables) {
        log.info("Normalizing {} tables by resolving synonym references in column types", tables.size());

        List<TableMetadata> normalizedTables = new ArrayList<>();
        int synonymsResolved = 0;

        for (TableMetadata originalTable : tables) {
            try {
                TableMetadata normalizedTable = normalizeTableMetadata(originalTable);
                normalizedTables.add(normalizedTable);

                // Count how many synonyms were resolved in this table
                for (int i = 0; i < originalTable.getColumns().size(); i++) {
                    ColumnMetadata original = originalTable.getColumns().get(i);
                    ColumnMetadata normalized = normalizedTable.getColumns().get(i);
                    if (original.isCustomDataType() &&
                        !original.getQualifiedDataType().equals(normalized.getQualifiedDataType())) {
                        synonymsResolved++;
                    }
                }
            } catch (Exception e) {
                log.error("Failed to normalize table {}.{}, using original",
                    originalTable.getSchema(), originalTable.getTableName(), e);
                normalizedTables.add(originalTable); // Fall back to original on error
            }
        }

        log.info("Normalization complete: {} tables processed, {} synonym references resolved",
            normalizedTables.size(), synonymsResolved);

        return normalizedTables;
    }

    /**
     * Normalizes a single table by resolving all synonym references in column data types.
     * This preprocessing step ensures that:
     * <ol>
     *   <li>Table creation SQL uses the correct target types (not synonyms)</li>
     *   <li>Data transfer correctly classifies types for serialization</li>
     *   <li>Synonym resolution happens only once per type reference</li>
     * </ol>
     *
     * @param originalTable The original table with potential synonym references
     * @return Normalized copy with all synonyms resolved to their targets
     */
    public TableMetadata normalizeTableMetadata(TableMetadata originalTable) {
        // Create a new table with normalized column references
        TableMetadata normalizedTable = new TableMetadata(
            originalTable.getSchema(),
            originalTable.getTableName()
        );

        // Normalize each column's data type
        for (ColumnMetadata originalColumn : originalTable.getColumns()) {
            ColumnMetadata normalizedColumn;

            // Only resolve synonyms for custom (user-defined) types
            if (originalColumn.isCustomDataType()) {
                String owner = originalColumn.getDataTypeOwner();
                String typeName = originalColumn.getDataType();

                // Extract the base type name without size/precision info
                String baseTypeName = extractBaseTypeName(typeName);

                // Try to resolve as a synonym
                String resolvedTarget = stateService.resolveSynonym(owner, baseTypeName);

                if (resolvedTarget != null) {
                    // Parse the resolved target (format: "schema.typename")
                    String[] parts = resolvedTarget.split("\\.");
                    if (parts.length == 2) {
                        String resolvedOwner = parts[0];
                        String resolvedTypeName = parts[1];

                        // Preserve any size/precision info from the original type
                        String typeWithSize = typeName.substring(baseTypeName.length());
                        String fullResolvedType = resolvedTypeName + typeWithSize;

                        normalizedColumn = new ColumnMetadata(
                            originalColumn.getColumnName(),
                            fullResolvedType,
                            resolvedOwner,
                            originalColumn.getCharacterLength(),
                            originalColumn.getNumericPrecision(),
                            originalColumn.getNumericScale(),
                            originalColumn.isNullable(),
                            originalColumn.getDefaultValue()
                        );

                        log.debug("Resolved synonym {}.{} -> {}.{} for column '{}' in table {}.{}",
                            owner, baseTypeName, resolvedOwner, resolvedTypeName,
                            originalColumn.getColumnName(), originalTable.getSchema(), originalTable.getTableName());
                    } else {
                        // Malformed resolution result, use original
                        normalizedColumn = createColumnCopy(originalColumn);
                        log.warn("Malformed synonym resolution result: {}", resolvedTarget);
                    }
                } else {
                    // Not a synonym, use original column
                    normalizedColumn = createColumnCopy(originalColumn);
                }
            } else {
                // Built-in type, use original column
                normalizedColumn = createColumnCopy(originalColumn);
            }

            normalizedTable.addColumn(normalizedColumn);
        }

        // Copy constraints as-is (no type references in constraints at this stage)
        for (ConstraintMetadata constraint : originalTable.getConstraints()) {
            normalizedTable.addConstraint(constraint);
        }

        return normalizedTable;
    }

    /**
     * Extracts the base type name without size/precision information.
     * For example: "VARCHAR2(100)" -> "VARCHAR2", "NUMBER(10,2)" -> "NUMBER"
     */
    private String extractBaseTypeName(String dataType) {
        int parenIndex = dataType.indexOf('(');
        if (parenIndex > 0) {
            return dataType.substring(0, parenIndex);
        }
        return dataType;
    }

    /**
     * Creates a copy of a ColumnMetadata object.
     */
    private ColumnMetadata createColumnCopy(ColumnMetadata original) {
        return new ColumnMetadata(
            original.getColumnName(),
            original.getDataType(),
            original.getDataTypeOwner(),
            original.getCharacterLength(),
            original.getNumericPrecision(),
            original.getNumericScale(),
            original.isNullable(),
            original.getDefaultValue()
        );
    }
}
