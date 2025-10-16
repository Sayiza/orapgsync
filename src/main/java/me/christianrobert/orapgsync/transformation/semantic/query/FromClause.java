package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents the FROM clause of a query.
 *
 * <p>Grammar rule: from_clause
 * <pre>
 * from_clause:
 *     FROM table_ref_list
 * </pre>
 *
 * <p>Grammar rule: table_ref_list
 * <pre>
 * table_ref_list:
 *     table_ref (COMMA table_ref)*
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ Single table reference
 * - ⏳ Multiple tables (comma-separated - not yet implemented)
 * - ⏳ JOIN syntax (INNER/OUTER/CROSS JOIN - not yet implemented)
 * - ⏳ Subqueries in FROM (not yet implemented)
 * - ⏳ PIVOT/UNPIVOT (not yet implemented)
 */
public class FromClause implements SemanticNode {

    private final List<TableReference> tableReferences;

    public FromClause(List<TableReference> tableReferences) {
        if (tableReferences == null || tableReferences.isEmpty()) {
            throw new IllegalArgumentException("FromClause must have at least one table reference");
        }
        this.tableReferences = new ArrayList<>(tableReferences);
    }

    public FromClause(TableReference singleTable) {
        if (singleTable == null) {
            throw new IllegalArgumentException("Table reference cannot be null");
        }
        this.tableReferences = new ArrayList<>();
        this.tableReferences.add(singleTable);
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // In current minimal implementation, only single table supported
        if (tableReferences.size() > 1) {
            throw new UnsupportedOperationException(
                    "Multiple tables in FROM clause not yet supported in current implementation");
        }

        return tableReferences.stream()
                .map(table -> table.toPostgres(context))
                .collect(Collectors.joining(", "));
    }

    public List<TableReference> getTableReferences() {
        return Collections.unmodifiableList(tableReferences);
    }

    @Override
    public String toString() {
        return "FromClause{tables=" + tableReferences.size() + "}";
    }
}
