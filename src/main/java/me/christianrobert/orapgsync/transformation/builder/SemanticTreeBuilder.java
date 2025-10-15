package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor that converts ANTLR parse tree to semantic syntax tree.
 * This is the only class that directly touches ANTLR classes.
 *
 * In this minimal implementation, handles only:
 * - Simple SELECT column1, column2 FROM table
 *
 * Future phases will add support for:
 * - WHERE, JOIN, GROUP BY, ORDER BY, etc.
 * - Complex expressions
 * - Subqueries
 * - Set operations (UNION, INTERSECT, MINUS)
 */
public class SemanticTreeBuilder extends PlSqlParserBaseVisitor<SemanticNode> {

    private static final Logger log = LoggerFactory.getLogger(SemanticTreeBuilder.class);

    @Override
    public SemanticNode visitSelect_statement(PlSqlParser.Select_statementContext ctx) {
        log.debug("Visiting select_statement");

        // Navigate to the actual query_block
        // select_statement -> select_only_statement -> subquery -> ... -> query_block
        PlSqlParser.Select_only_statementContext selectOnly = ctx.select_only_statement();
        if (selectOnly == null) {
            throw new TransformationException("SELECT statement missing select_only_statement");
        }

        PlSqlParser.SubqueryContext subquery = selectOnly.subquery();
        if (subquery == null) {
            throw new TransformationException("SELECT statement missing subquery");
        }

        // Get the first query block (minimal implementation: no UNION/INTERSECT support yet)
        PlSqlParser.Subquery_basic_elementsContext basicElements = subquery.subquery_basic_elements();
        if (basicElements == null) {
            throw new TransformationException("SELECT statement missing subquery_basic_elements");
        }

        PlSqlParser.Query_blockContext queryBlock = basicElements.query_block();
        if (queryBlock == null) {
            throw new TransformationException("SELECT statement missing query_block");
        }

        return visitQuery_block(queryBlock);
    }

    @Override
    public SemanticNode visitQuery_block(PlSqlParser.Query_blockContext ctx) {
        log.debug("Visiting query_block");

        // Extract SELECT list
        PlSqlParser.Selected_listContext selectedList = ctx.selected_list();
        if (selectedList == null) {
            throw new TransformationException("Query block missing selected_list");
        }

        List<Identifier> columns = extractSelectColumns(selectedList);

        // Extract FROM clause
        PlSqlParser.From_clauseContext fromClause = ctx.from_clause();
        if (fromClause == null) {
            throw new TransformationException("Query block missing from_clause (FROM DUAL not yet supported in minimal implementation)");
        }

        TableReference table = extractFromTable(fromClause);

        return new SelectStatement(columns, table);
    }

    /**
     * Extracts column identifiers from the SELECT list.
     * In this minimal implementation, only handles simple column names (no expressions, no aliases).
     */
    private List<Identifier> extractSelectColumns(PlSqlParser.Selected_listContext ctx) {
        List<Identifier> columns = new ArrayList<>();

        if (ctx.ASTERISK() != null) {
            // SELECT * - not supported in minimal implementation
            throw new TransformationException("SELECT * not supported in minimal implementation");
        }

        // Extract each select_list_elements
        for (PlSqlParser.Select_list_elementsContext element : ctx.select_list_elements()) {
            if (element.ASTERISK() != null) {
                // table.* syntax not supported yet
                throw new TransformationException("SELECT table.* not supported in minimal implementation");
            }

            // Extract the expression
            PlSqlParser.ExpressionContext expr = element.expression();
            if (expr == null) {
                throw new TransformationException("Select list element missing expression");
            }

            // In minimal implementation, assume expression is just a simple identifier
            String columnName = extractSimpleIdentifier(expr);
            columns.add(new Identifier(columnName));
        }

        return columns;
    }

    /**
     * Extracts the table reference from the FROM clause.
     * In this minimal implementation, only handles a single table (no joins).
     */
    private TableReference extractFromTable(PlSqlParser.From_clauseContext ctx) {
        PlSqlParser.Table_ref_listContext tableRefList = ctx.table_ref_list();
        if (tableRefList == null) {
            throw new TransformationException("FROM clause missing table_ref_list");
        }

        List<PlSqlParser.Table_refContext> tableRefs = tableRefList.table_ref();
        if (tableRefs.isEmpty()) {
            throw new TransformationException("FROM clause has no table references");
        }

        if (tableRefs.size() > 1) {
            throw new TransformationException("Multiple tables in FROM clause not supported in minimal implementation");
        }

        // Extract the single table reference
        PlSqlParser.Table_refContext tableRef = tableRefs.get(0);
        return extractTableReference(tableRef);
    }

    /**
     * Extracts a table reference (table name and optional alias).
     */
    private TableReference extractTableReference(PlSqlParser.Table_refContext ctx) {
        // Navigate: table_ref -> table_ref_aux -> table_ref_aux_internal -> ...
        PlSqlParser.Table_ref_auxContext tableRefAux = ctx.table_ref_aux();
        if (tableRefAux == null) {
            throw new TransformationException("Table reference missing table_ref_aux");
        }

        PlSqlParser.Table_ref_aux_internalContext internal = tableRefAux.table_ref_aux_internal();
        if (internal == null) {
            throw new TransformationException("Table reference missing table_ref_aux_internal");
        }

        // ANTLR generates subclasses for labeled alternatives in the grammar
        // The table_ref_aux_internal rule has labels (#table_ref_aux_internal_one, etc.)
        // We need to handle the specific subclass type
        PlSqlParser.Dml_table_expression_clauseContext dmlTable = null;

        if (internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext) {
            PlSqlParser.Table_ref_aux_internal_oneContext internalOne =
                (PlSqlParser.Table_ref_aux_internal_oneContext) internal;
            dmlTable = internalOne.dml_table_expression_clause();
        } else if (internal instanceof PlSqlParser.Table_ref_aux_internal_threContext) {
            // ONLY (table) syntax
            PlSqlParser.Table_ref_aux_internal_threContext internalThree =
                (PlSqlParser.Table_ref_aux_internal_threContext) internal;
            dmlTable = internalThree.dml_table_expression_clause();
        }

        if (dmlTable == null) {
            throw new TransformationException("Table reference type not supported in minimal implementation");
        }

        PlSqlParser.Tableview_nameContext tableviewName = dmlTable.tableview_name();
        if (tableviewName == null) {
            throw new TransformationException("Table reference missing tableview_name");
        }

        String tableName = extractTableName(tableviewName);

        // Check for alias
        PlSqlParser.Table_aliasContext aliasCtx = tableRefAux.table_alias();
        String alias = null;
        if (aliasCtx != null) {
            alias = aliasCtx.getText();
        }

        return new TableReference(tableName, alias);
    }

    /**
     * Extracts a simple table name from tableview_name context.
     */
    private String extractTableName(PlSqlParser.Tableview_nameContext ctx) {
        // In minimal implementation, just get the full text
        // Future phases will handle schema qualification, synonyms, etc.
        return ctx.getText();
    }

    /**
     * Extracts a simple identifier from an expression context.
     * In this minimal implementation, only handles the simplest case (just an identifier).
     */
    private String extractSimpleIdentifier(PlSqlParser.ExpressionContext ctx) {
        // In minimal implementation, just get the text
        // Future phases will handle complex expressions, function calls, etc.
        return ctx.getText();
    }
}
