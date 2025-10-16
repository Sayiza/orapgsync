package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;
import me.christianrobert.orapgsync.transformation.semantic.query.FromClause;
import me.christianrobert.orapgsync.transformation.semantic.query.QueryBlock;
import me.christianrobert.orapgsync.transformation.semantic.query.SelectListElement;
import me.christianrobert.orapgsync.transformation.semantic.query.SelectedList;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor that converts ANTLR parse tree to semantic syntax tree.
 * This is the only class that directly touches ANTLR classes.
 *
 * <p>Architecture: Uses visitor pattern to delegate to child nodes.
 * Each visit method creates a semantic node and calls visit() on children.
 * No manual extraction logic - all traversal is via visitor pattern.
 *
 * <p>Current implementation supports:
 * - Simple SELECT column1, column2 FROM table
 *
 * <p>Future phases will add:
 * - WHERE, JOIN, GROUP BY, ORDER BY, HAVING
 * - Complex expressions, function calls
 * - Subqueries, set operations (UNION, INTERSECT, MINUS)
 */
public class SemanticTreeBuilder extends PlSqlParserBaseVisitor<SemanticNode> {

    private static final Logger log = LoggerFactory.getLogger(SemanticTreeBuilder.class);

    // ========== SELECT STATEMENT ==========

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

        // Use visitor pattern - delegate to visitQuery_block
        QueryBlock visitedQueryBlock = (QueryBlock) visit(queryBlock);
        return new SelectStatement(visitedQueryBlock);
    }

    // ========== QUERY BLOCK ==========

    @Override
    public SemanticNode visitQuery_block(PlSqlParser.Query_blockContext ctx) {
        log.debug("Visiting query_block");

        // Extract SELECT list - use visitor pattern
        PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
        if (selectedListCtx == null) {
            throw new TransformationException("Query block missing selected_list");
        }
        SelectedList selectedList = (SelectedList) visit(selectedListCtx);

        // Extract FROM clause - use visitor pattern
        PlSqlParser.From_clauseContext fromClauseCtx = ctx.from_clause();
        if (fromClauseCtx == null) {
            throw new TransformationException("Query block missing from_clause (FROM DUAL not yet supported in minimal implementation)");
        }
        FromClause fromClause = (FromClause) visit(fromClauseCtx);

        return new QueryBlock(selectedList, fromClause);
    }

    // ========== SELECTED LIST (SELECT columns) ==========

    @Override
    public SemanticNode visitSelected_list(PlSqlParser.Selected_listContext ctx) {
        log.debug("Visiting selected_list");

        if (ctx.ASTERISK() != null) {
            // SELECT * - not supported in minimal implementation
            throw new TransformationException("SELECT * not supported in minimal implementation");
        }

        // Visit each select_list_elements child
        List<SelectListElement> elements = new ArrayList<>();
        for (PlSqlParser.Select_list_elementsContext elementCtx : ctx.select_list_elements()) {
            SelectListElement element = (SelectListElement) visit(elementCtx);
            elements.add(element);
        }

        return new SelectedList(elements);
    }

    @Override
    public SemanticNode visitSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        log.debug("Visiting select_list_elements");

        if (ctx.ASTERISK() != null) {
            // table.* syntax not supported yet
            throw new TransformationException("SELECT table.* not supported in minimal implementation");
        }

        // Visit expression child
        PlSqlParser.ExpressionContext exprCtx = ctx.expression();
        if (exprCtx == null) {
            throw new TransformationException("Select list element missing expression");
        }
        SemanticNode expression = visit(exprCtx);

        // Future: handle column_alias from ctx.column_alias()

        return new SelectListElement(expression);
    }

    // ========== EXPRESSION (simplified for minimal implementation) ==========

    @Override
    public SemanticNode visitExpression(PlSqlParser.ExpressionContext ctx) {
        log.debug("Visiting expression (simplified)");

        // In minimal implementation, assume expression is just a simple identifier
        // Future phases will handle complex expressions, function calls, operators, etc.
        String text = ctx.getText();
        return new Identifier(text);
    }

    // ========== FROM CLAUSE ==========

    @Override
    public SemanticNode visitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        log.debug("Visiting from_clause");

        PlSqlParser.Table_ref_listContext tableRefListCtx = ctx.table_ref_list();
        if (tableRefListCtx == null) {
            throw new TransformationException("FROM clause missing table_ref_list");
        }

        // Visit each table_ref child
        List<TableReference> tableRefs = new ArrayList<>();
        for (PlSqlParser.Table_refContext tableRefCtx : tableRefListCtx.table_ref()) {
            TableReference tableRef = (TableReference) visit(tableRefCtx);
            tableRefs.add(tableRef);
        }

        if (tableRefs.isEmpty()) {
            throw new TransformationException("FROM clause has no table references");
        }

        // Minimal implementation: only single table supported
        if (tableRefs.size() > 1) {
            throw new TransformationException("Multiple tables in FROM clause not supported in minimal implementation");
        }

        return new FromClause(tableRefs);
    }

    // ========== TABLE REFERENCE ==========

    @Override
    public SemanticNode visitTable_ref(PlSqlParser.Table_refContext ctx) {
        log.debug("Visiting table_ref");

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

        String tableName = tableviewName.getText();

        // Check for alias
        PlSqlParser.Table_aliasContext aliasCtx = tableRefAux.table_alias();
        String alias = null;
        if (aliasCtx != null) {
            alias = aliasCtx.getText();
        }

        return new TableReference(tableName, alias);
    }
}
