package me.christianrobert.orapgsync.objectdatatype.service;

import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes dependencies between Oracle object types and provides topologically sorted order
 * for PostgreSQL type creation.
 *
 * This ensures that types are created in the correct order - dependencies before dependents.
 */
public class TypeDependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TypeDependencyAnalyzer.class);

    /**
     * Result of dependency analysis.
     */
    public static class DependencyAnalysisResult {
        private final List<ObjectDataTypeMetaData> sortedTypes;
        private final List<CircularDependency> circularDependencies;
        private final Map<String, Set<String>> dependencyGraph;

        public DependencyAnalysisResult(List<ObjectDataTypeMetaData> sortedTypes,
                                       List<CircularDependency> circularDependencies,
                                       Map<String, Set<String>> dependencyGraph) {
            this.sortedTypes = sortedTypes;
            this.circularDependencies = circularDependencies;
            this.dependencyGraph = dependencyGraph;
        }

        public List<ObjectDataTypeMetaData> getSortedTypes() {
            return sortedTypes;
        }

        public List<CircularDependency> getCircularDependencies() {
            return circularDependencies;
        }

        public Map<String, Set<String>> getDependencyGraph() {
            return dependencyGraph;
        }

        public boolean hasCircularDependencies() {
            return !circularDependencies.isEmpty();
        }
    }

    /**
     * Represents a circular dependency between types.
     */
    public static class CircularDependency {
        private final List<String> cycle;

        public CircularDependency(List<String> cycle) {
            this.cycle = new ArrayList<>(cycle);
        }

        public List<String> getCycle() {
            return cycle;
        }

        @Override
        public String toString() {
            return String.join(" -> ", cycle);
        }
    }

    /**
     * Analyzes dependencies and returns types in topologically sorted order.
     * Types with no dependencies come first, then types that depend on them, etc.
     *
     * @param types List of object types to analyze
     * @return DependencyAnalysisResult containing sorted types and any circular dependencies
     */
    public static DependencyAnalysisResult analyzeDependencies(List<ObjectDataTypeMetaData> types) {
        log.info("Starting dependency analysis for {} object types", types.size());

        // Build a map of qualified type name -> ObjectDataTypeMetaData for quick lookup
        Map<String, ObjectDataTypeMetaData> typeMap = new HashMap<>();
        for (ObjectDataTypeMetaData type : types) {
            String qualifiedName = getQualifiedTypeName(type);
            typeMap.put(qualifiedName, type);
        }

        // Build dependency graph: type -> set of types it depends on
        Map<String, Set<String>> dependencyGraph = buildDependencyGraph(types, typeMap);

        // Detect circular dependencies
        List<CircularDependency> circularDependencies = detectCircularDependencies(dependencyGraph);

        if (!circularDependencies.isEmpty()) {
            log.warn("Found {} circular dependencies:", circularDependencies.size());
            for (CircularDependency cycle : circularDependencies) {
                log.warn("  Circular dependency: {}", cycle);
            }
        }

        // Perform topological sort
        List<ObjectDataTypeMetaData> sortedTypes = topologicalSort(typeMap, dependencyGraph);

        log.info("Dependency analysis complete: {} types sorted, {} circular dependencies",
                sortedTypes.size(), circularDependencies.size());

        return new DependencyAnalysisResult(sortedTypes, circularDependencies, dependencyGraph);
    }

    /**
     * Builds a dependency graph showing which types depend on which other types.
     */
    private static Map<String, Set<String>> buildDependencyGraph(List<ObjectDataTypeMetaData> types,
                                                                 Map<String, ObjectDataTypeMetaData> typeMap) {
        Map<String, Set<String>> graph = new HashMap<>();

        for (ObjectDataTypeMetaData type : types) {
            String qualifiedName = getQualifiedTypeName(type);
            Set<String> dependencies = new HashSet<>();

            // Check each variable in the type
            for (ObjectDataTypeVariable variable : type.getVariables()) {
                // Only consider custom (user-defined) types as dependencies
                if (variable.isCustomDataType()) {
                    String dependencyName = variable.getQualifiedTypeName();

                    // Only add as dependency if the type exists in our type map
                    // (i.e., it's one of the types we're creating)
                    if (typeMap.containsKey(dependencyName)) {
                        dependencies.add(dependencyName);
                        log.debug("Type '{}' depends on '{}'", qualifiedName, dependencyName);
                    }
                }
            }

            graph.put(qualifiedName, dependencies);
        }

        return graph;
    }

    /**
     * Detects circular dependencies in the dependency graph using DFS.
     */
    private static List<CircularDependency> detectCircularDependencies(Map<String, Set<String>> graph) {
        List<CircularDependency> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        Map<String, String> parent = new HashMap<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                detectCyclesDFS(node, graph, visited, recursionStack, parent, cycles);
            }
        }

        return cycles;
    }

    /**
     * DFS helper for cycle detection.
     */
    private static boolean detectCyclesDFS(String node,
                                          Map<String, Set<String>> graph,
                                          Set<String> visited,
                                          Set<String> recursionStack,
                                          Map<String, String> parent,
                                          List<CircularDependency> cycles) {
        visited.add(node);
        recursionStack.add(node);

        Set<String> neighbors = graph.get(node);
        if (neighbors != null) {
            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    parent.put(neighbor, node);
                    if (detectCyclesDFS(neighbor, graph, visited, recursionStack, parent, cycles)) {
                        return true;
                    }
                } else if (recursionStack.contains(neighbor)) {
                    // Found a cycle - reconstruct it
                    List<String> cycle = new ArrayList<>();
                    String current = node;
                    cycle.add(neighbor);
                    while (current != null && !current.equals(neighbor)) {
                        cycle.add(current);
                        current = parent.get(current);
                    }
                    cycle.add(neighbor); // Complete the cycle
                    Collections.reverse(cycle);
                    cycles.add(new CircularDependency(cycle));
                }
            }
        }

        recursionStack.remove(node);
        return false;
    }

    /**
     * Performs topological sort using Kahn's algorithm.
     * Returns types in an order where dependencies come before dependents.
     */
    private static List<ObjectDataTypeMetaData> topologicalSort(Map<String, ObjectDataTypeMetaData> typeMap,
                                                                Map<String, Set<String>> dependencyGraph) {
        List<ObjectDataTypeMetaData> sorted = new ArrayList<>();

        // Calculate in-degree (number of dependencies each type HAS)
        Map<String, Integer> inDegree = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            String type = entry.getKey();
            Set<String> dependencies = entry.getValue();
            inDegree.put(type, dependencies.size());
        }

        // Build reverse graph: dependency -> types that depend on it
        Map<String, Set<String>> reverseGraph = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            String type = entry.getKey();
            Set<String> dependencies = entry.getValue();

            for (String dependency : dependencies) {
                reverseGraph.computeIfAbsent(dependency, k -> new HashSet<>()).add(type);
            }
        }

        // Queue of types with no dependencies (in-degree = 0)
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // Process types in topological order
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(typeMap.get(current));

            // For each type that DEPENDS ON the current type (using reverse graph)
            Set<String> dependents = reverseGraph.get(current);
            if (dependents != null) {
                for (String dependent : dependents) {
                    int newInDegree = inDegree.get(dependent) - 1;
                    inDegree.put(dependent, newInDegree);

                    // If all dependencies of this type are now satisfied, add to queue
                    if (newInDegree == 0) {
                        queue.offer(dependent);
                    }
                }
            }
        }

        // If there are types not in sorted list, they're part of circular dependencies
        // Add them at the end (they may fail to create, but we try anyway)
        if (sorted.size() < typeMap.size()) {
            log.warn("Topological sort incomplete: {} types sorted, {} total types. " +
                    "Remaining types are likely in circular dependencies.",
                    sorted.size(), typeMap.size());

            for (String typeName : typeMap.keySet()) {
                ObjectDataTypeMetaData type = typeMap.get(typeName);
                if (!sorted.contains(type)) {
                    sorted.add(type);
                    log.warn("Adding type '{}' to end of sort order (may be in circular dependency)", typeName);
                }
            }
        }

        return sorted;
    }

    /**
     * Gets the qualified type name (schema.typename) for a type.
     */
    private static String getQualifiedTypeName(ObjectDataTypeMetaData type) {
        return type.getSchema().toLowerCase() + "." + type.getName().toLowerCase();
    }
}
