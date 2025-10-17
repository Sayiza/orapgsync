package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

public class PostgresCodeBuilder extends PlSqlParserBaseVisitor<String> {

    // no logging is desired, this would create an overkill of logs

    private final TransformationContext context;

    /**
     * Creates a PostgresCodeBuilder with transformation context.
     * @param context Transformation context for metadata lookups (can be null for simple transformations)
     */
    public PostgresCodeBuilder(TransformationContext context) {
        this.context = context;
    }

    /**
     * Creates a PostgresCodeBuilder without context (for simple transformations without metadata).
     */
    public PostgresCodeBuilder() {
        this.context = null;
    }

    /**
     * Gets the transformation context (may be null).
     */
    public TransformationContext getContext() {
        return context;
    }

    // ========== SELECT STATEMENT ==========

    @Override
    public String visitSelect_statement(PlSqlParser.Select_statementContext ctx) {
        return VisitSelectStatement.v(ctx, this);
    }

    @Override
    public String visitSelect_only_statement(PlSqlParser.Select_only_statementContext ctx) {
        return VisitSelectOnlyStatement.v(ctx, this);
    }

    @Override
    public String visitSubquery(PlSqlParser.SubqueryContext ctx) {
        return VisitSubquery.v(ctx, this);
    }

    @Override
    public String visitSubquery_basic_elements(PlSqlParser.Subquery_basic_elementsContext ctx) {
        return VisitSubqueryBasicElements.v(ctx, this);
    }

    // ========== QUERY BLOCK ==========

    @Override
    public String visitQuery_block(PlSqlParser.Query_blockContext ctx) {
        return VisitQueryBlock.v(ctx, this);
    }

    // ========== SELECTED LIST (SELECT columns) ==========

    @Override
    public String visitSelected_list(PlSqlParser.Selected_listContext ctx) {
        return VisitSelectedList.v(ctx, this);
    }

    @Override
    public String visitSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        return VisitSelectListElement.v(ctx, this);
    }

    // ========== EXPRESSION HIERARCHY ==========

    @Override
    public String visitExpression(PlSqlParser.ExpressionContext ctx) {
        return VisitExpression.v(ctx, this);
    }

    @Override
    public String visitLogical_expression(PlSqlParser.Logical_expressionContext ctx) {
        return VisitLogicalExpression.v(ctx, this);
    }

    @Override
    public String visitUnary_logical_expression(PlSqlParser.Unary_logical_expressionContext ctx) {
        return VisitUnaryLogicalExpression.v(ctx, this);
    }

    @Override
    public String visitMultiset_expression(PlSqlParser.Multiset_expressionContext ctx) {
        return VisitMultisetExpression.v(ctx, this);
    }

    @Override
    public String visitRelational_expression(PlSqlParser.Relational_expressionContext ctx) {
        return VisitRelationalExpression.v(ctx, this);
    }

    @Override
    public String visitCompound_expression(PlSqlParser.Compound_expressionContext ctx) {
        return VisitCompoundExpression.v(ctx, this);
    }

    @Override
    public String visitConcatenation(PlSqlParser.ConcatenationContext ctx) {
        return VisitConcatenation.v(ctx, this);
    }

    @Override
    public String visitModel_expression(PlSqlParser.Model_expressionContext ctx) {
        return VisitModelExpression.v(ctx, this);
    }

    @Override
    public String visitUnary_expression(PlSqlParser.Unary_expressionContext ctx) {
        return VisitUnaryExpression.v(ctx, this);
    }

    @Override
    public String visitAtom(PlSqlParser.AtomContext ctx) {
        return VisitAtom.v(ctx, this);
    }

    @Override
    public String visitGeneral_element(PlSqlParser.General_elementContext ctx) {
        return VisitGeneralElement.v(ctx, this);
    }

    // ========== STANDARD FUNCTIONS ==========

    @Override
    public String visitStandard_function(PlSqlParser.Standard_functionContext ctx) {
        return VisitStandardFunction.v(ctx, this);
    }

    @Override
    public String visitString_function(PlSqlParser.String_functionContext ctx) {
        return VisitStringFunction.v(ctx, this);
    }

    // ========== FROM CLAUSE ==========

    @Override
    public String visitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        return VisitFromClause.v(ctx, this);
    }

    // ========== TABLE REFERENCE ==========

    @Override
    public String visitTable_ref(PlSqlParser.Table_refContext ctx) {
        return VisitTableReference.v(ctx, this);
    }
}
