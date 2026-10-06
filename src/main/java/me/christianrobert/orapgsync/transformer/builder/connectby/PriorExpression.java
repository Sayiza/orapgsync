package me.christianrobert.orapgsync.transformer.builder.connectby;

/**
 * Represents an analyzed PRIOR expression from CONNECT BY clause.
 *
 * <p>Oracle PRIOR syntax examples:</p>
 * <pre>
 * CONNECT BY PRIOR emp_id = manager_id       -- PRIOR on left
 * CONNECT BY manager_id = PRIOR emp_id       -- PRIOR on right
 * CONNECT BY PRIOR e.emp_id = e.manager_id   -- With table alias
 * </pre>
 *
 * <p>PRIOR indicates the "parent" side of the relationship. The transformation
 * to PostgreSQL recursive CTE requires understanding which column is the parent
 * and which is the child.</p>
 *
 * <p>Example transformation:</p>
 * <pre>
 * Oracle:  CONNECT BY PRIOR emp_id = manager_id
 * Meaning: parent.emp_id = child.manager_id
 *
 * PostgreSQL CTE:
 *   FROM employees e                          -- child table
 *   JOIN emp_hierarchy eh                     -- parent (CTE)
 *     ON e.manager_id = eh.emp_id             -- child.manager_id = parent.emp_id
 * </pre>
 */
public class PriorExpression {

  private final boolean priorOnLeft;
  private final String priorColumnExpression;  // e.g., "emp_id" or "e.emp_id"
  private final String childColumnExpression;  // e.g., "manager_id" or "e.manager_id"

  public PriorExpression(
      boolean priorOnLeft,
      String priorColumnExpression,
      String childColumnExpression) {
    this.priorOnLeft = priorOnLeft;
    this.priorColumnExpression = priorColumnExpression;
    this.childColumnExpression = childColumnExpression;
  }

  public boolean isPriorOnLeft() {
    return priorOnLeft;
  }

  public String getPriorColumnExpression() {
    return priorColumnExpression;
  }

  public String getChildColumnExpression() {
    return childColumnExpression;
  }

  /**
   * Generates the JOIN condition for the recursive CTE member.
   *
   * <p>Logic:</p>
   * <ul>
   *   <li>PRIOR column → parent (CTE alias)</li>
   *   <li>Non-PRIOR column → child (table alias)</li>
   * </ul>
   *
   * <p>Example:</p>
   * <pre>
   * Oracle:  CONNECT BY PRIOR emp_id = manager_id
   * Result:  e.manager_id = eh.emp_id
   *          (child.manager_id = parent.emp_id)
   * </pre>
   *
   * @param childAlias Alias for child table (e.g., "e")
   * @param cteAlias Alias for CTE (e.g., "eh")
   * @return JOIN condition string
   */
  public String generateJoinCondition(String childAlias, String cteAlias) {
    // Extract bare column names (strip existing table qualifiers if present)
    String priorColumn = stripTableQualifier(priorColumnExpression);
    String childColumn = stripTableQualifier(childColumnExpression);

    // Build qualified references
    String parentRef = cteAlias + "." + priorColumn;   // CTE has PRIOR column
    String childRef = childAlias + "." + childColumn;  // Table has non-PRIOR column

    // JOIN condition: child.col = parent.col
    return childRef + " = " + parentRef;
  }

  /**
   * Strips table qualifier from column expression if present.
   *
   * <p>Examples:</p>
   * <ul>
   *   <li>"emp_id" → "emp_id"</li>
   *   <li>"e.emp_id" → "emp_id"</li>
   *   <li>"schema.table.col" → "col" (last segment)</li>
   * </ul>
   */
  private String stripTableQualifier(String columnExpression) {
    if (columnExpression.contains(".")) {
      String[] parts = columnExpression.split("\\.");
      return parts[parts.length - 1];  // Last segment
    }
    return columnExpression;
  }

  @Override
  public String toString() {
    return "PriorExpression{" +
        (priorOnLeft ? "PRIOR " + priorColumnExpression + " = " + childColumnExpression
                     : childColumnExpression + " = PRIOR " + priorColumnExpression) +
        '}';
  }
}
