package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

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
        return VisitSelect_statement.v(ctx, this);
    }

    @Override
    public SemanticNode visitSelect_only_statement(PlSqlParser.Select_only_statementContext ctx) {
        return VisitSelect_only_statement.v(ctx, this);
    }

    @Override
    public SemanticNode visitSubquery(PlSqlParser.SubqueryContext ctx) {
        return VisitSubquery.v(ctx, this);
    }

    @Override
    public SemanticNode visitSubquery_basic_elements(PlSqlParser.Subquery_basic_elementsContext ctx) {
        return VisitSubquery_basic_elements.v(ctx, this);
    }

    // ========== QUERY BLOCK ==========

    @Override
    public SemanticNode visitQuery_block(PlSqlParser.Query_blockContext ctx) {
        return VisitQuery_block.v(ctx, this);
    }

    // ========== SELECTED LIST (SELECT columns) ==========

    @Override
    public SemanticNode visitSelected_list(PlSqlParser.Selected_listContext ctx) {
        return VisitSelected_list.v(ctx, this);
    }

    @Override
    public SemanticNode visitSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        return VisitSelect_list_elements.v(ctx, this);
    }

    // ========== EXPRESSION (simplified for minimal implementation) ==========

    @Override
    public SemanticNode visitExpression(PlSqlParser.ExpressionContext ctx) {
        return VisitExpression.v(ctx, this);
    }

    // ========== FROM CLAUSE ==========

    @Override
    public SemanticNode visitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        return VisitFrom_clause.v(ctx, this);
    }

    // ========== TABLE REFERENCE ==========

    @Override
    public SemanticNode visitTable_ref(PlSqlParser.Table_refContext ctx) {
        return VisitTable_ref.v(ctx, this);
    }
}
