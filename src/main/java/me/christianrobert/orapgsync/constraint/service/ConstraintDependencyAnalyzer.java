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
     * Sorts foreign keys by dependency using topological sort.
     * This ensures that tables are created in the correct order:
     * - Tables with no dependencies first
     * - Tables that depend on other tables after their dependencies
     * - Self-referencing FKs last (can't be ordered)
     *
     * Uses Kahn's algorithm for topological sorting.
     */
    private static List<TableConstraintPair> sortForeignKeysByDependency(List<TableConstraintPair> foreignKeys) {
        // Separate self-referencing FKs (can't be topologically sorted)
        List<TableConstraintPair> selfReferencing = new ArrayList<>();
        List<TableConstraintPair> nonSelfReferencing = new ArrayList<>();

        for (TableConstraintPair pair : foreignKeys) {
            String tableName = (pair.table.getSchema() + "." + pair.table.getTableName()).toLowerCase();
            String referencedTable = (pair.constraint.getReferencedSchema() + "." + pair.constraint.getReferencedTable()).toLowerCase();

            if (tableName.equals(referencedTable)) {
                selfReferencing.add(pair);
                log.debug("FK {} is self-referencing ({}), will be added last",
                        pair.constraint.getConstraintName(), tableName);
            } else {
                nonSelfReferencing.add(pair);
            }
        }

        // Build dependency graph for non-self-referencing FKs
        // Graph: table -> list of tables it depends on (via FK references)
        Map<String, List<String>> dependencies = new HashMap<>();
        Map<String, List<TableConstraintPair>> fksBySourceTable = new HashMap<>();
        Set<String> allTables = new HashSet<>();

        for (TableConstraintPair pair : nonSelfReferencing) {
            String sourceTable = (pair.table.getSchema() + "." + pair.table.getTableName()).toLowerCase();
            String targetTable = (pair.constraint.getReferencedSchema() + "." + pair.constraint.getReferencedTable()).toLowerCase();

            allTables.add(sourceTable);
            allTables.add(targetTable);

            // sourceTable depends on targetTable (must create target's PK/UK first)
            dependencies.computeIfAbsent(sourceTable, k -> new ArrayList<>()).add(targetTable);
            fksBySourceTable.computeIfAbsent(sourceTable, k -> new ArrayList<>()).add(pair);

            log.debug("FK dependency: {} -> {} (FK: {})",
                    sourceTable, targetTable, pair.constraint.getConstraintName());
        }

        // Calculate in-degree for each table (how many tables depend on it)
        Map<String, Integer> inDegree = new HashMap<>();
        for (String table : allTables) {
            inDegree.put(table, 0);
        }
        for (List<String> deps : dependencies.values()) {
            for (String dep : deps) {
                inDegree.put(dep, inDegree.getOrDefault(dep, 0) + 1);
            }
        }

        // Topological sort using Kahn's algorithm
        // Start with tables that have no dependencies (in-degree = 0)
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
                log.debug("Table {} has no FK dependencies (in-degree=0), processing first", entry.getKey());
            }
        }

        List<String> sortedTables = new ArrayList<>();
        while (!queue.isEmpty()) {
            String table = queue.poll();
            sortedTables.add(table);

            // Reduce in-degree for all tables that this table depends on
            List<String> deps = dependencies.get(table);
            if (deps != null) {
                for (String dep : deps) {
                    int newInDegree = inDegree.get(dep) - 1;
                    inDegree.put(dep, newInDegree);
                    if (newInDegree == 0) {
                        queue.offer(dep);
                    }
                }
            }
        }

        // Check for circular dependencies
        if (sortedTables.size() < allTables.size()) {
            log.warn("Circular FK dependencies detected! {} tables out of {} could not be sorted topologically",
                    allTables.size() - sortedTables.size(), allTables.size());
            log.warn("Tables with circular dependencies: {}",
                    allTables.stream().filter(t -> !sortedTables.contains(t)).toList());
            // Add remaining tables (with circular deps) at the end
            sortedTables.addAll(allTables.stream().filter(t -> !sortedTables.contains(t)).toList());
        }

        // Build result list in topological order
        List<TableConstraintPair> result = new ArrayList<>();
        for (String table : sortedTables) {
            List<TableConstraintPair> tableFKs = fksBySourceTable.get(table);
            if (tableFKs != null) {
                result.addAll(tableFKs);
            }
        }

        // Add self-referencing FKs last
        result.addAll(selfReferencing);

        log.info("Sorted {} foreign keys: {} topologically sorted, {} self-referencing",
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
