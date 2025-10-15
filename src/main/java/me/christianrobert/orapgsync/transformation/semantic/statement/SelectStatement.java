package me.christianrobert.orapgsync.transformation.semantic.statement;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a complete SELECT statement.
 * In this minimal implementation, handles only:
 * - SELECT column1, column2, ...
 * - FROM table
 *
 * Future phases will add: WHERE, JOIN, GROUP BY, ORDER BY, etc.
 */
public class SelectStatement implements SemanticNode {

    private final List<Identifier> selectColumns;
    private final TableReference fromTable;

    public SelectStatement(List<Identifier> selectColumns, TableReference fromTable) {
        if (selectColumns == null || selectColumns.isEmpty()) {
            throw new IllegalArgumentException("SELECT statement must have at least one column");
        }
        if (fromTable == null) {
            throw new IllegalArgumentException("SELECT statement must have a FROM clause");
        }
        this.selectColumns = new ArrayList<>(selectColumns);
        this.fromTable = fromTable;
    }

    public List<Identifier> getSelectColumns() {
        return Collections.unmodifiableList(selectColumns);
    }

    public TableReference getFromTable() {
        return fromTable;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // Build SELECT column list
        String columnList = selectColumns.stream()
                .map(col -> col.toPostgres(context))
                .collect(Collectors.joining(", "));

        // Build complete SELECT statement
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(columnList);
        sql.append(" FROM ").append(fromTable.toPostgres(context));

        return sql.toString();
    }

    @Override
    public String toString() {
        return "SelectStatement{columns=" + selectColumns.size() + ", table=" + fromTable + "}";
    }
}
