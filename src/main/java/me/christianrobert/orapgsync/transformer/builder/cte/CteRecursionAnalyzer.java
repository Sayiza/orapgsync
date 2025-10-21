package me.christianrobert.orapgsync.transformer.builder.cte;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.HashSet;
import java.util.Set;

/**
 * Analyzes CTE definitions to detect recursion.
 *
 * <p>A CTE is recursive if it references itself in its subquery definition.</p>
 *
 * <p>Example:</p>
 * <pre>
 * WITH emp_tree AS (
 *   SELECT ... FROM employees          -- Base case (no reference to emp_tree)
 *   UNION ALL
 *   SELECT ... FROM emp_tree           -- Recursive case (references emp_tree!)
 * )
 * </pre>
 *
 * <p>Detection strategy:</p>
 * <ul>
 *   <li>Extract CTE name</li>
 *   <li>Walk subquery AST to find all table references</li>
 *   <li>Check if CTE name appears in table references</li>
 *   <li>If yes → recursive, otherwise → non-recursive</li>
 * </ul>
 *
 * <p>Note: PostgreSQL requires RECURSIVE keyword even for mutually recursive CTEs
 * (cte1 references cte2, cte2 references cte1). We handle this by checking if
 * ANY CTE in the WITH clause is recursive.</p>
 */
public class CteRecursionAnalyzer {

  /**
   * Check if a CTE references itself.
   *
   * @param ctx The subquery_factoring_clause context
   * @return true if CTE is recursive, false otherwise
   */
  public static boolean isRecursive(PlSqlParser.Subquery_factoring_clauseContext ctx) {
    // Get CTE name
    String cteName = ctx.query_name().getText().toLowerCase();

    // Get the subquery
    PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();

    // Extract all table references from the subquery
    Set<String> tableReferences = extractTableReferences(subqueryCtx);

    // Check if CTE name appears in table references
    return tableReferences.contains(cteName);
  }

  /**
   * Extract all table names referenced in a subquery.
   * Walks the AST to find all tableview_name nodes.
   */
  private static Set<String> extractTableReferences(PlSqlParser.SubqueryContext ctx) {
    TableReferenceCollector collector = new TableReferenceCollector();
    collector.visit(ctx);
    return collector.getTableNames();
  }

  /**
   * AST visitor that collects all table references.
   */
  private static class TableReferenceCollector {
    private final Set<String> tableNames = new HashSet<>();

    public Set<String> getTableNames() {
      return tableNames;
    }

    public void visit(ParseTree tree) {
      if (tree == null) {
        return;
      }

      // Check if this node is a tableview_name
      if (tree instanceof PlSqlParser.Tableview_nameContext) {
        PlSqlParser.Tableview_nameContext tableCtx = (PlSqlParser.Tableview_nameContext) tree;

        // Extract table name (ignoring schema qualifier)
        // Grammar: tableview_name : identifier ('.' id_expression)?
        String fullName = tableCtx.getText();
        String tableName;

        // If qualified (schema.table), take just the table part
        if (fullName.contains(".")) {
          String[] parts = fullName.split("\\.");
          tableName = parts[parts.length - 1]; // Last part is table name
        } else {
          tableName = fullName;
        }

        tableNames.add(tableName.toLowerCase());
      }

      // Recursively visit children
      for (int i = 0; i < tree.getChildCount(); i++) {
        visit(tree.getChild(i));
      }
    }
  }
}
