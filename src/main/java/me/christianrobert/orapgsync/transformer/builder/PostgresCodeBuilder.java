package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

import java.util.ArrayDeque;
import java.util.Deque;

public class PostgresCodeBuilder extends PlSqlParserBaseVisitor<String> {

    // no logging is desired, this would create an overkill of logs

    private final TransformationContext context;

    // Query-local state for outer join transformation
    // Stack to handle nested queries (subqueries)
    // Each query_block pushes its context, pops when done
    private final Deque<OuterJoinContext> outerJoinContextStack;

    /**
     * Creates a PostgresCodeBuilder with transformation context.
     * @param context Transformation context for metadata lookups (can be null for simple transformations)
     */
    public PostgresCodeBuilder(TransformationContext context) {
        this.context = context;
        this.outerJoinContextStack = new ArrayDeque<>();
    }

    /**
     * Creates a PostgresCodeBuilder without context (for simple transformations without metadata).
     */
    public PostgresCodeBuilder() {
        this.context = null;
        this.outerJoinContextStack = new ArrayDeque<>();
    }

    /**
     * Gets the transformation context (may be null).
     */
    public TransformationContext getContext() {
        return context;
    }

    /**
     * Pushes an outer join context onto the stack for the current query level.
     * Used by VisitQueryBlock when entering a query (including subqueries).
     *
     * @param outerJoinContext Outer join context for this query level
     */
    public void pushOuterJoinContext(OuterJoinContext outerJoinContext) {
        outerJoinContextStack.push(outerJoinContext);
    }

    /**
     * Pops the outer join context from the stack when exiting a query level.
     * Used by VisitQueryBlock when leaving a query (including subqueries).
     */
    public void popOuterJoinContext() {
        if (!outerJoinContextStack.isEmpty()) {
            outerJoinContextStack.pop();
        }
    }

    /**
     * Gets the outer join context for the current query level.
     *
     * @return Outer join context or null if no context (empty stack)
     */
    public OuterJoinContext getOuterJoinContext() {
        return outerJoinContextStack.peek();
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

    @Override
    public String visitNumeric_function_wrapper(PlSqlParser.Numeric_function_wrapperContext ctx) {
        return VisitNumericFunctionWrapper.v(ctx, this);
    }

    @Override
    public String visitNumeric_function(PlSqlParser.Numeric_functionContext ctx) {
        return VisitNumericFunction.v(ctx, this);
    }

    @Override
    public String visitOther_function(PlSqlParser.Other_functionContext ctx) {
        return VisitOtherFunction.v(ctx, this);
    }

    @Override
    public String visitFunction_argument_analytic(PlSqlParser.Function_argument_analyticContext ctx) {
        return VisitFunctionArgumentAnalytic.v(ctx, this);
    }

    // ========== FROM CLAUSE ==========

    @Override
    public String visitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        return VisitFromClause.v(ctx, this);
    }

    // ========== WHERE CLAUSE ==========

    @Override
    public String visitWhere_clause(PlSqlParser.Where_clauseContext ctx) {
        return VisitWhereClause.v(ctx, this);
    }

    @Override
    public String visitCondition(PlSqlParser.ConditionContext ctx) {
        return VisitCondition.v(ctx, this);
    }

    // ========== ORDER BY CLAUSE ==========

    @Override
    public String visitOrder_by_clause(PlSqlParser.Order_by_clauseContext ctx) {
        return VisitOrderByClause.v(ctx, this);
    }

    // ========== GROUP BY CLAUSE ==========

    @Override
    public String visitGroup_by_clause(PlSqlParser.Group_by_clauseContext ctx) {
        return VisitGroupByClause.v(ctx, this);
    }

    // ========== HAVING CLAUSE ==========

    @Override
    public String visitHaving_clause(PlSqlParser.Having_clauseContext ctx) {
        return VisitHavingClause.v(ctx, this);
    }

    // ========== CONSTANTS ==========

    @Override
    public String visitConstant(PlSqlParser.ConstantContext ctx) {
        return VisitConstant.v(ctx, this);
    }

    // ========== TABLE REFERENCE ==========

    @Override
    public String visitTable_ref(PlSqlParser.Table_refContext ctx) {
        return VisitTableReference.v(ctx, this);
    }
}
