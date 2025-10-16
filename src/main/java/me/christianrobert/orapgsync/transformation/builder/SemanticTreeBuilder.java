package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.element.TableReference;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;
import me.christianrobert.orapgsync.transformation.semantic.query.*;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectOnlyStatement;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectStatement;

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
 * <p>To be continously expanded...
 */
public class SemanticTreeBuilder extends PlSqlParserBaseVisitor<SemanticNode> {

    // no loggin is desired, this is would create an overkill of logs

    // ========== SELECT STATEMENT ==========

    @Override
    public SemanticNode visitSelect_statement(PlSqlParser.Select_statementContext ctx) {
        // with_clause? select_only_statement

        // Note: WITH clause detection would go here when implementing CTEs
        // Current grammar doesn't expose with_clause() method in this context

        // Visit select_only_statement
        PlSqlParser.Select_only_statementContext selectOnlyCtx = ctx.select_only_statement();
        if (selectOnlyCtx == null) {
            throw new TransformationException("SELECT statement missing select_only_statement");
        }

        SelectOnlyStatement selectOnlyStatement = (SelectOnlyStatement) visit(selectOnlyCtx);
        return new SelectStatement(selectOnlyStatement);
    }

    @Override
    public SemanticNode visitSelect_only_statement(PlSqlParser.Select_only_statementContext ctx) {
        // subquery for_update_clause?

        // Note: FOR UPDATE detection would go here when implementing
        // Current grammar doesn't expose for_update_clause() method in this context

        // Visit subquery
        PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();
        if (subqueryCtx == null) {
            throw new TransformationException("SELECT_ONLY statement missing subquery");
        }

        Subquery subquery = (Subquery) visit(subqueryCtx);
        return new SelectOnlyStatement(subquery);
    }

    @Override
    public SemanticNode visitSubquery(PlSqlParser.SubqueryContext ctx) {
        // subquery_basic_elements subquery_operation_part*

        // Visit basic elements (required)
        PlSqlParser.Subquery_basic_elementsContext basicElementsCtx = ctx.subquery_basic_elements();
        if (basicElementsCtx == null) {
            throw new TransformationException("Subquery missing subquery_basic_elements");
        }

        SubqueryBasicElements basicElements = (SubqueryBasicElements) visit(basicElementsCtx);

        // Visit operation parts (UNION/INTERSECT/MINUS) if present
        List<PlSqlParser.Subquery_operation_partContext> operationParts = ctx.subquery_operation_part();
        if (operationParts != null && !operationParts.isEmpty()) {
            throw new TransformationException("Set operations (UNION/INTERSECT/MINUS) not yet supported");
        }

        return new Subquery(basicElements);
    }

    @Override
    public SemanticNode visitSubquery_basic_elements(PlSqlParser.Subquery_basic_elementsContext ctx) {
        // query_block | LEFT_PAREN subquery RIGHT_PAREN

        // Check for query_block (normal case)
        PlSqlParser.Query_blockContext queryBlockCtx = ctx.query_block();
        if (queryBlockCtx != null) {
            QueryBlock queryBlock = (QueryBlock) visit(queryBlockCtx);
            return new SubqueryBasicElements(queryBlock);
        }

        // Check for parenthesized subquery
        PlSqlParser.SubqueryContext nestedSubqueryCtx = ctx.subquery();
        if (nestedSubqueryCtx != null) {
            throw new TransformationException("Parenthesized subqueries not yet supported");
        }

        throw new TransformationException("Subquery basic elements missing query_block");
    }

    // ========== QUERY BLOCK ==========

    @Override
    public SemanticNode visitQuery_block(PlSqlParser.Query_blockContext ctx) {

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

        // In minimal implementation, assume expression is just a simple identifier
        // Future phases will handle complex expressions, function calls, operators, etc.
        String text = ctx.getText();
        return new Identifier(text);
    }

    // ========== FROM CLAUSE ==========

    @Override
    public SemanticNode visitFrom_clause(PlSqlParser.From_clauseContext ctx) {

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
