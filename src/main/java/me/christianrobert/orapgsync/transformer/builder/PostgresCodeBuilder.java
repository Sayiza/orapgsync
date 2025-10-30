package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.builder.rownum.RownumContext;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class PostgresCodeBuilder extends PlSqlParserBaseVisitor<String> {

    // no logging is desired, this would create an overkill of logs

    private final TransformationContext context;

    // Query-local state for outer join transformation
    // Stack to handle nested queries (subqueries)
    // Each query_block pushes its context, pops when done
    private final Deque<OuterJoinContext> outerJoinContextStack;

    // Query-local state for ROWNUM transformation
    // Stack to handle nested queries (subqueries)
    // Each query_block pushes its context, pops when done
    private final Deque<RownumContext> rownumContextStack;

    // Block-level state for loop RECORD variable declarations
    // PostgreSQL requires explicit RECORD declarations for cursor FOR loop variables
    // Stack to handle nested blocks (anonymous DECLARE...BEGIN...END blocks)
    // Each block (function or nested anonymous block) pushes its context, pops when done
    private final Deque<Set<String>> loopRecordVariablesStack;

    // Block-level state for user-defined exception declarations
    // Oracle uses named exceptions with PRAGMA EXCEPTION_INIT to link to error codes
    // PostgreSQL uses SQLSTATE codes in RAISE and exception handlers
    // Stack to handle nested blocks with proper shadowing semantics
    // Each block (function or nested anonymous block) pushes its context, pops when done
    private final Deque<ExceptionContext> exceptionContextStack;

    /**
     * Exception context for a single block scope.
     * Tracks user-defined exception declarations and their PostgreSQL SQLSTATE mappings.
     */
    private static class ExceptionContext {
        // Maps exception name (lowercase) to PostgreSQL SQLSTATE code
        // Null value means exception declared but not yet assigned a code (waiting for PRAGMA or auto-gen)
        private final java.util.Map<String, String> exceptionToErrorCode = new java.util.HashMap<>();

        // Auto-generated error code counter for exceptions without PRAGMA EXCEPTION_INIT
        // Starts at 9001 (P9001), increments for each exception that needs auto-generation
        // Range P9001-P9999 is reserved for auto-generated codes
        private int nextAutoCode = 9001;

        /**
         * Declares a user-defined exception without assigning a code yet.
         * The code will be auto-generated later if no PRAGMA EXCEPTION_INIT provides one.
         * This prevents wasted auto-code slots when PRAGMA comes after declaration.
         *
         * @param exceptionName Exception name (will be lowercased)
         */
        public void declareException(String exceptionName) {
            String normalizedName = exceptionName.toLowerCase();
            if (!exceptionToErrorCode.containsKey(normalizedName)) {
                // Mark as declared but not yet coded (null value)
                // Code will be assigned on-demand in getErrorCode()
                exceptionToErrorCode.put(normalizedName, null);
            }
        }

        /**
         * Links a user-defined exception to an Oracle error code via PRAGMA EXCEPTION_INIT.
         * If the exception wasn't previously declared, it declares it first.
         *
         * @param exceptionName Exception name (will be lowercased)
         * @param oracleCode Oracle error code (e.g., -20001)
         */
        public void linkToOracleCode(String exceptionName, int oracleCode) {
            String normalizedName = exceptionName.toLowerCase();
            // Map Oracle error code to PostgreSQL SQLSTATE
            // Oracle: -20000 to -20999 → PostgreSQL: P0001 to P0999
            String pgCode = String.format("P%04d", Math.abs(oracleCode) - 20000);
            exceptionToErrorCode.put(normalizedName, pgCode);
        }

        /**
         * Gets the PostgreSQL SQLSTATE code for a user-defined exception.
         * Auto-generates a code if the exception was declared but has no code yet.
         *
         * @param exceptionName Exception name (will be lowercased)
         * @return PostgreSQL SQLSTATE code (e.g., "P0001") or null if exception not declared
         */
        public String getErrorCode(String exceptionName) {
            String normalizedName = exceptionName.toLowerCase();

            // Check if exception exists
            if (!exceptionToErrorCode.containsKey(normalizedName)) {
                return null;  // Exception not declared
            }

            // Get current code (may be null)
            String code = exceptionToErrorCode.get(normalizedName);

            // If code is null, exception was declared but never got a PRAGMA
            // Auto-generate a code now
            if (code == null) {
                code = String.format("P%04d", nextAutoCode++);
                exceptionToErrorCode.put(normalizedName, code);
            }

            return code;
        }
    }

    /**
     * Creates a PostgresCodeBuilder with transformation context.
     * @param context Transformation context for metadata lookups (can be null for simple transformations)
     */
    public PostgresCodeBuilder(TransformationContext context) {
        this.context = context;
        this.outerJoinContextStack = new ArrayDeque<>();
        this.rownumContextStack = new ArrayDeque<>();
        this.loopRecordVariablesStack = new ArrayDeque<>();
        this.exceptionContextStack = new ArrayDeque<>();
    }

    /**
     * Creates a PostgresCodeBuilder without context (for simple transformations without metadata).
     */
    public PostgresCodeBuilder() {
        this.context = null;
        this.outerJoinContextStack = new ArrayDeque<>();
        this.rownumContextStack = new ArrayDeque<>();
        this.loopRecordVariablesStack = new ArrayDeque<>();
        this.exceptionContextStack = new ArrayDeque<>();
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

    /**
     * Pushes a ROWNUM context onto the stack for the current query level.
     * Used by VisitQueryBlock when entering a query (including subqueries).
     *
     * @param rownumContext ROWNUM context for this query level
     */
    public void pushRownumContext(RownumContext rownumContext) {
        rownumContextStack.push(rownumContext);
    }

    /**
     * Pops the ROWNUM context from the stack when exiting a query level.
     * Used by VisitQueryBlock when leaving a query (including subqueries).
     */
    public void popRownumContext() {
        if (!rownumContextStack.isEmpty()) {
            rownumContextStack.pop();
        }
    }

    /**
     * Gets the ROWNUM context for the current query level.
     *
     * @return ROWNUM context or null if no context (empty stack)
     */
    public RownumContext getRownumContext() {
        return rownumContextStack.peek();
    }

    /**
     * Pushes a new loop RECORD variables context onto the stack for the current block.
     * Used when entering a block (function body or anonymous DECLARE...BEGIN...END block).
     * Creates a new empty set for tracking loop variables in this block scope.
     */
    public void pushLoopRecordVariablesContext() {
        loopRecordVariablesStack.push(new HashSet<>());
    }

    /**
     * Pops the loop RECORD variables context from the stack when exiting a block.
     * Used when leaving a block (function body or anonymous DECLARE...BEGIN...END block).
     * Returns the set of loop variables for the block being exited.
     *
     * @return Set of loop variable names for the current block (may be empty)
     */
    public Set<String> popLoopRecordVariablesContext() {
        if (!loopRecordVariablesStack.isEmpty()) {
            return loopRecordVariablesStack.pop();
        }
        return new HashSet<>();  // Empty set if stack is empty (shouldn't happen in valid code)
    }

    /**
     * Registers a loop variable that needs RECORD type declaration in the current block.
     * Used by VisitLoop_statement to track cursor FOR loop variables.
     * Adds to the current block's context (top of stack).
     *
     * @param variableName Name of the loop variable (e.g., "emp_rec")
     */
    public void registerLoopRecordVariable(String variableName) {
        if (!loopRecordVariablesStack.isEmpty()) {
            loopRecordVariablesStack.peek().add(variableName);
        }
        // If stack is empty, we can't register (shouldn't happen - block should push context first)
    }

    /**
     * Pushes a new exception context onto the stack for the current block.
     * Used when entering a block (function body or anonymous DECLARE...BEGIN...END block).
     * Creates a new empty context for tracking user-defined exceptions in this block scope.
     */
    public void pushExceptionContext() {
        exceptionContextStack.push(new ExceptionContext());
    }

    /**
     * Pops the exception context from the stack when exiting a block.
     * Used when leaving a block (function body or anonymous DECLARE...BEGIN...END block).
     */
    public void popExceptionContext() {
        if (!exceptionContextStack.isEmpty()) {
            exceptionContextStack.pop();
        }
    }

    /**
     * Declares a user-defined exception in the current block scope.
     * Assigns an auto-generated PostgreSQL SQLSTATE code (P9001, P9002, ...).
     * Used by VisitException_declaration when encountering: exception_name EXCEPTION;
     *
     * @param exceptionName Exception name (will be normalized to lowercase)
     */
    public void declareException(String exceptionName) {
        if (!exceptionContextStack.isEmpty()) {
            exceptionContextStack.peek().declareException(exceptionName);
        }
    }

    /**
     * Links a user-defined exception to an Oracle error code.
     * Maps the Oracle error code to a PostgreSQL SQLSTATE code.
     * Used by VisitPragma_declaration when encountering: PRAGMA EXCEPTION_INIT(name, code);
     *
     * @param exceptionName Exception name (will be normalized to lowercase)
     * @param oracleCode Oracle error code (e.g., -20001 maps to P0001)
     */
    public void linkExceptionToCode(String exceptionName, int oracleCode) {
        if (!exceptionContextStack.isEmpty()) {
            exceptionContextStack.peek().linkToOracleCode(exceptionName, oracleCode);
        }
    }

    /**
     * Looks up the PostgreSQL SQLSTATE code for a user-defined exception.
     * Searches from innermost to outermost scope (supports shadowing).
     * Used by VisitRaise_statement and VisitException_handler.
     *
     * @param exceptionName Exception name (will be normalized to lowercase)
     * @return PostgreSQL SQLSTATE code (e.g., "P0001") or null if not found
     */
    public String lookupExceptionErrorCode(String exceptionName) {
        // Search from innermost to outermost scope (top to bottom of stack)
        for (int i = exceptionContextStack.size() - 1; i >= 0; i--) {
            ExceptionContext context = ((java.util.List<ExceptionContext>)
                new java.util.ArrayList<>(exceptionContextStack)).get(i);
            String errorCode = context.getErrorCode(exceptionName);
            if (errorCode != null) {
                return errorCode;
            }
        }
        return null;  // Exception not found in any scope
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

    // ========== WITH CLAUSE (CTEs) ==========

    @Override
    public String visitWith_clause(PlSqlParser.With_clauseContext ctx) {
        return VisitWithClause.v(ctx, this);
    }

    @Override
    public String visitWith_factoring_clause(PlSqlParser.With_factoring_clauseContext ctx) {
        return VisitWithFactoringClause.v(ctx, this);
    }

    @Override
    public String visitSubquery_factoring_clause(PlSqlParser.Subquery_factoring_clauseContext ctx) {
        return VisitSubqueryFactoringClause.v(ctx, this);
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
    public String visitCase_expression(PlSqlParser.Case_expressionContext ctx) {
        return VisitCaseExpression.v(ctx, this);
    }

    @Override
    public String visitQuantified_expression(PlSqlParser.Quantified_expressionContext ctx) {
        return VisitQuantifiedExpression.v(ctx, this);
    }

    @Override
    public String visitAtom(PlSqlParser.AtomContext ctx) {
        return VisitAtom.v(ctx, this);
    }

    @Override
    public String visitGeneral_element(PlSqlParser.General_elementContext ctx) {
        return VisitGeneralElement.v(ctx, this);
    }

    @Override
    public String visitTable_element(PlSqlParser.Table_elementContext ctx) {
        return VisitTableElement.v(ctx, this);
    }

    @Override
    public String visitOuter_join_sign(PlSqlParser.Outer_join_signContext ctx) {
        // Oracle outer join operator: (+)
        // PostgreSQL uses ANSI JOIN syntax instead
        // Strip this operator - it's handled by OuterJoinAnalyzer and converted to ANSI JOIN
        // Return empty string to remove it from the output
        return "";
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

    // ========== WINDOW FUNCTIONS ==========

    @Override
    public String visitOver_clause(PlSqlParser.Over_clauseContext ctx) {
        return VisitOverClause.v(ctx, this);
    }

    @Override
    public String visitExpressions_(PlSqlParser.Expressions_Context ctx) {
        return VisitExpressions.v(ctx, this);
    }

    // ========== PL/SQL FUNCTION/PROCEDURE BODIES ==========

    @Override
    public String visitFunction_body(PlSqlParser.Function_bodyContext ctx) {
        return VisitFunctionBody.v(ctx, this);
    }

    @Override
    public String visitProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
        return VisitProcedureBody.v(ctx, this);
    }

    @Override
    public String visitBody(PlSqlParser.BodyContext ctx) {
        return VisitBody.v(ctx, this);
    }

    @Override
    public String visitSeq_of_statements(PlSqlParser.Seq_of_statementsContext ctx) {
        return VisitSeq_of_statements.v(ctx, this);
    }

    @Override
    public String visitReturn_statement(PlSqlParser.Return_statementContext ctx) {
        return VisitReturn_statement.v(ctx, this);
    }

    @Override
    public String visitVariable_declaration(PlSqlParser.Variable_declarationContext ctx) {
        return VisitVariable_declaration.v(ctx, this);
    }

    @Override
    public String visitCursor_declaration(PlSqlParser.Cursor_declarationContext ctx) {
        return VisitCursor_declaration.v(ctx, this);
    }

    @Override
    public String visitException_declaration(PlSqlParser.Exception_declarationContext ctx) {
        return VisitException_declaration.v(ctx, this);
    }

    @Override
    public String visitPragma_declaration(PlSqlParser.Pragma_declarationContext ctx) {
        return VisitPragma_declaration.v(ctx, this);
    }

    @Override
    public String visitAssignment_statement(PlSqlParser.Assignment_statementContext ctx) {
        return VisitAssignment_statement.v(ctx, this);
    }

    @Override
    public String visitSeq_of_declare_specs(PlSqlParser.Seq_of_declare_specsContext ctx) {
        return VisitSeq_of_declare_specs.v(ctx, this);
    }

    @Override
    public String visitIf_statement(PlSqlParser.If_statementContext ctx) {
        return VisitIf_statement.v(ctx, this);
    }

    @Override
    public String visitInto_clause(PlSqlParser.Into_clauseContext ctx) {
        return VisitInto_clause.v(ctx, this);
    }

    @Override
    public String visitBind_variable(PlSqlParser.Bind_variableContext ctx) {
        return VisitBind_variable.v(ctx, this);
    }

    @Override
    public String visitLoop_statement(PlSqlParser.Loop_statementContext ctx) {
        return VisitLoop_statement.v(ctx, this);
    }

    @Override
    public String visitCall_statement(PlSqlParser.Call_statementContext ctx) {
        return VisitCall_statement.v(ctx, this);
    }

    @Override
    public String visitExit_statement(PlSqlParser.Exit_statementContext ctx) {
        return VisitExit_statement.v(ctx, this);
    }

    @Override
    public String visitContinue_statement(PlSqlParser.Continue_statementContext ctx) {
        return VisitContinue_statement.v(ctx, this);
    }

    @Override
    public String visitNull_statement(PlSqlParser.Null_statementContext ctx) {
        return VisitNull_statement.v(ctx, this);
    }

    @Override
    public String visitCase_statement(PlSqlParser.Case_statementContext ctx) {
        return VisitCase_statement.v(ctx, this);
    }

    @Override
    public String visitException_handler(PlSqlParser.Exception_handlerContext ctx) {
        return VisitException_handler.v(ctx, this);
    }

    @Override
    public String visitRaise_statement(PlSqlParser.Raise_statementContext ctx) {
        return VisitRaise_statement.v(ctx, this);
    }
}
