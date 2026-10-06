package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

import java.util.List;
import java.util.Set;

/**
 * Analyzed components of an Oracle CONNECT BY hierarchical query.
 *
 * <p>Oracle CONNECT BY structure:</p>
 * <pre>
 * SELECT columns
 * FROM table
 * WHERE filter_condition                 -- Optional
 * START WITH root_condition              -- Optional
 * CONNECT BY PRIOR parent_col = child_col
 * ORDER BY ...                           -- Optional (ORDER SIBLINGS BY not yet supported)
 * </pre>
 *
 * <p>This class stores all extracted components needed to transform
 * to PostgreSQL recursive CTE.</p>
 */
public class ConnectByComponents {

  // Original query block context (for reference)
  private final PlSqlParser.Query_blockContext queryBlockContext;

  // Core CONNECT BY components
  private final PlSqlParser.Start_partContext startWith;  // May be null
  private final PlSqlParser.ConditionContext connectByCondition;
  private final boolean hasNoCycle;

  // Table information (extracted from FROM clause)
  private final String baseTableName;      // e.g., "employees"
  private final String baseTableAlias;     // e.g., "e" (may be null)

  // PRIOR expression analysis
  private final PriorExpression priorExpression;

  // Pseudo-column usage tracking
  private final boolean usesLevelInSelect;
  private final boolean usesLevelInWhere;
  private final Set<String> levelReferencePaths;  // AST paths for LEVEL references

  // SYS_CONNECT_BY_PATH columns
  private final List<PathColumnInfo> pathColumns;  // 0 to N path columns

  // Advanced features (Phase 5)
  private final boolean usesConnectByRoot;
  private final boolean usesConnectByPath;
  private final boolean usesConnectByIsLeaf;

  private ConnectByComponents(Builder builder) {
    this.queryBlockContext = builder.queryBlockContext;
    this.startWith = builder.startWith;
    this.connectByCondition = builder.connectByCondition;
    this.hasNoCycle = builder.hasNoCycle;
    this.baseTableName = builder.baseTableName;
    this.baseTableAlias = builder.baseTableAlias;
    this.priorExpression = builder.priorExpression;
    this.usesLevelInSelect = builder.usesLevelInSelect;
    this.usesLevelInWhere = builder.usesLevelInWhere;
    this.levelReferencePaths = builder.levelReferencePaths;
    this.pathColumns = builder.pathColumns != null ? builder.pathColumns : List.of();
    this.usesConnectByRoot = builder.usesConnectByRoot;
    this.usesConnectByPath = builder.usesConnectByPath;
    this.usesConnectByIsLeaf = builder.usesConnectByIsLeaf;
  }

  // Getters

  public PlSqlParser.Query_blockContext getQueryBlockContext() {
    return queryBlockContext;
  }

  public PlSqlParser.Start_partContext getStartWith() {
    return startWith;
  }

  public PlSqlParser.ConditionContext getConnectByCondition() {
    return connectByCondition;
  }

  public boolean hasNoCycle() {
    return hasNoCycle;
  }

  public boolean hasStartWith() {
    return startWith != null;
  }

  public String getBaseTableName() {
    return baseTableName;
  }

  public String getBaseTableAlias() {
    return baseTableAlias;
  }

  public boolean hasBaseTableAlias() {
    return baseTableAlias != null;
  }

  public PriorExpression getPriorExpression() {
    return priorExpression;
  }

  public boolean usesLevelInSelect() {
    return usesLevelInSelect;
  }

  public boolean usesLevelInWhere() {
    return usesLevelInWhere;
  }

  public boolean usesLevel() {
    return usesLevelInSelect || usesLevelInWhere;
  }

  public Set<String> getLevelReferencePaths() {
    return levelReferencePaths;
  }

  public List<PathColumnInfo> getPathColumns() {
    return pathColumns;
  }

  public boolean hasPathColumns() {
    return pathColumns != null && !pathColumns.isEmpty();
  }

  public boolean usesConnectByRoot() {
    return usesConnectByRoot;
  }

  public boolean usesConnectByPath() {
    return usesConnectByPath;
  }

  public boolean usesConnectByIsLeaf() {
    return usesConnectByIsLeaf;
  }

  public boolean usesAdvancedPseudoColumns() {
    return usesConnectByRoot || usesConnectByPath || usesConnectByIsLeaf;
  }

  // Builder pattern

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private PlSqlParser.Query_blockContext queryBlockContext;
    private PlSqlParser.Start_partContext startWith;
    private PlSqlParser.ConditionContext connectByCondition;
    private boolean hasNoCycle;
    private String baseTableName;
    private String baseTableAlias;
    private PriorExpression priorExpression;
    private boolean usesLevelInSelect;
    private boolean usesLevelInWhere;
    private Set<String> levelReferencePaths;
    private List<PathColumnInfo> pathColumns;
    private boolean usesConnectByRoot;
    private boolean usesConnectByPath;
    private boolean usesConnectByIsLeaf;

    public Builder queryBlockContext(PlSqlParser.Query_blockContext ctx) {
      this.queryBlockContext = ctx;
      return this;
    }

    public Builder startWith(PlSqlParser.Start_partContext startWith) {
      this.startWith = startWith;
      return this;
    }

    public Builder connectByCondition(PlSqlParser.ConditionContext condition) {
      this.connectByCondition = condition;
      return this;
    }

    public Builder hasNoCycle(boolean hasNoCycle) {
      this.hasNoCycle = hasNoCycle;
      return this;
    }

    public Builder baseTableName(String name) {
      this.baseTableName = name;
      return this;
    }

    public Builder baseTableAlias(String alias) {
      this.baseTableAlias = alias;
      return this;
    }

    public Builder priorExpression(PriorExpression expr) {
      this.priorExpression = expr;
      return this;
    }

    public Builder usesLevelInSelect(boolean uses) {
      this.usesLevelInSelect = uses;
      return this;
    }

    public Builder usesLevelInWhere(boolean uses) {
      this.usesLevelInWhere = uses;
      return this;
    }

    public Builder levelReferencePaths(Set<String> paths) {
      this.levelReferencePaths = paths;
      return this;
    }

    public Builder pathColumns(List<PathColumnInfo> pathColumns) {
      this.pathColumns = pathColumns;
      return this;
    }

    public Builder usesConnectByRoot(boolean uses) {
      this.usesConnectByRoot = uses;
      return this;
    }

    public Builder usesConnectByPath(boolean uses) {
      this.usesConnectByPath = uses;
      return this;
    }

    public Builder usesConnectByIsLeaf(boolean uses) {
      this.usesConnectByIsLeaf = uses;
      return this;
    }

    public ConnectByComponents build() {
      // Validation
      if (queryBlockContext == null) {
        throw new IllegalStateException("queryBlockContext is required");
      }
      if (connectByCondition == null) {
        throw new IllegalStateException("connectByCondition is required");
      }
      if (baseTableName == null) {
        throw new IllegalStateException("baseTableName is required");
      }
      if (priorExpression == null) {
        throw new IllegalStateException("priorExpression is required");
      }

      return new ConnectByComponents(this);
    }
  }

  @Override
  public String toString() {
    return "ConnectByComponents{" +
        "baseTable=" + baseTableName +
        (baseTableAlias != null ? " AS " + baseTableAlias : "") +
        ", hasStartWith=" + hasStartWith() +
        ", hasNoCycle=" + hasNoCycle +
        ", priorExpression=" + priorExpression +
        ", usesLevel=" + usesLevel() +
        ", usesAdvanced=" + usesAdvancedPseudoColumns() +
        '}';
  }
}
