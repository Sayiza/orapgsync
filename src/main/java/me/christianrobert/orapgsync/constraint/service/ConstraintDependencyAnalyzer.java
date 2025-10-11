package me.christianrobert.orapgsync.constraint.service;

import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes and sorts constraints by dependency order for creation.
 *
 * Constraint Creation Order:
 * 1. Primary Keys (foundational, no dependencies)
 * 2. Unique Constraints (can be referenced by FKs)
 * 3. Foreign Keys (topologically sorted by table dependencies)
 * 4. Check Constraints (independent, can fail without blocking others)
 *
 * This ensures that:
 * - PKs exist before FKs that might reference them
 * - Unique constraints exist before FKs that reference them
 * - FKs are created after their target tables' PKs/Unique constraints
 * - Check constraints are added last (failures won't block other constraints)
 */
public class ConstraintDependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ConstraintDependencyAnalyzer.class);

    /**
     * Sorts constraints by dependency order for creation.
     *
     * @param tables List of tables with their constraints
     * @return List of table-constraint pairs sorted by creation order
     */
    public static List<TableConstraintPair> sortConstraintsByDependency(List<TableMetadata> tables) {
        List<TableConstraintPair> sortedConstraints = new ArrayList<>();

        // Step 1: Add all Primary Keys first
        for (TableMetadata table : tables) {
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (constraint.isPrimaryKey()) {
                    sortedConstraints.add(new TableConstraintPair(table, constraint));
                    log.debug("Added PK constraint: {}.{}", table.getTableName(), constraint.getConstraintName());
                }
            }
        }

        // Step 2: Add all Unique Constraints
        for (TableMetadata table : tables) {
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (constraint.isUniqueConstraint()) {
                    sortedConstraints.add(new TableConstraintPair(table, constraint));
                    log.debug("Added UNIQUE constraint: {}.{}", table.getTableName(), constraint.getConstraintName());
                }
            }
        }

        // Step 3: Add Foreign Keys (with simple topological sort)
        List<TableConstraintPair> foreignKeys = new ArrayList<>();
        for (TableMetadata table : tables) {
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (constraint.isForeignKey()) {
                    foreignKeys.add(new TableConstraintPair(table, constraint));
                }
            }
        }

        // Sort FKs by dependency (simple approach: self-referencing last)
        List<TableConstraintPair> sortedFKs = sortForeignKeysByDependency(foreignKeys);
        sortedConstraints.addAll(sortedFKs);

        // Step 4: Add all Check Constraints last
        for (TableMetadata table : tables) {
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (constraint.isCheckConstraint()) {
                    sortedConstraints.add(new TableConstraintPair(table, constraint));
                    log.debug("Added CHECK constraint: {}.{}", table.getTableName(), constraint.getConstraintName());
                }
            }
        }

        log.info("Sorted {} constraints by dependency order", sortedConstraints.size());
        return sortedConstraints;
    }

    /**
     * Sorts foreign keys by dependency (simple approach).
     * Self-referencing FKs go last, others sorted by referenced table.
     */
    private static List<TableConstraintPair> sortForeignKeysByDependency(List<TableConstraintPair> foreignKeys) {
        List<TableConstraintPair> nonSelfReferencing = new ArrayList<>();
        List<TableConstraintPair> selfReferencing = new ArrayList<>();

        for (TableConstraintPair pair : foreignKeys) {
            String tableName = pair.table.getSchema() + "." + pair.table.getTableName();
            String referencedTable = pair.constraint.getReferencedSchema() + "." + pair.constraint.getReferencedTable();

            if (tableName.equalsIgnoreCase(referencedTable)) {
                // Self-referencing FK - add last
                selfReferencing.add(pair);
                log.debug("FK {} is self-referencing ({}), will be added last",
                        pair.constraint.getConstraintName(), tableName);
            } else {
                // Regular FK
                nonSelfReferencing.add(pair);
            }
        }

        // Combine: non-self-referencing first, then self-referencing
        List<TableConstraintPair> result = new ArrayList<>(nonSelfReferencing);
        result.addAll(selfReferencing);

        log.info("Sorted {} foreign keys: {} regular, {} self-referencing",
                foreignKeys.size(), nonSelfReferencing.size(), selfReferencing.size());

        return result;
    }

    /**
     * Pair of table and constraint for sorted list.
     */
    public static class TableConstraintPair {
        public final TableMetadata table;
        public final ConstraintMetadata constraint;

        public TableConstraintPair(TableMetadata table, ConstraintMetadata constraint) {
            this.table = table;
            this.constraint = constraint;
        }

        public String getQualifiedTableName() {
            return table.getSchema() + "." + table.getTableName();
        }

        @Override
        public String toString() {
            return String.format("TableConstraintPair{table=%s.%s, constraint=%s, type=%s}",
                    table.getSchema(), table.getTableName(),
                    constraint.getConstraintName(), constraint.getConstraintType());
        }
    }
}
