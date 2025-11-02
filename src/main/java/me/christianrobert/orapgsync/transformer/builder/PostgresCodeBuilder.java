package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.builder.rownum.RownumContext;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
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
     * Cursor attribute tracking context.
     * Tracks which cursors use attributes (%FOUND, %NOTFOUND, %ROWCOUNT, %ISOPEN)
     * and generates tracking variables for those cursors.
     */
    private static class CursorAttributeTracker {
        // Cursors that use at least one cursor attribute
        // Used to determine if tracking variables need to be generated
        private final Set<String> cursorsNeedingTracking = new HashSet<>();

        // Tracking variables already declared (to avoid duplicates)
        private final Set<String> trackingVariablesDeclared = new HashSet<>();

        /**
         * Registers that a cursor uses at least one attribute.
         * Call this when encountering cursor%FOUND, cursor%NOTFOUND, cursor%ROWCOUNT, or cursor%ISOPEN.
         *
         * @param cursorName Cursor name (will be normalized to lowercase)
         */
        public void registerCursorAttributeUsage(String cursorName) {
            cursorsNeedingTracking.add(cursorName.toLowerCase());
        }

        /**
         * Checks if a cursor needs tracking variables.
         *
         * @param cursorName Cursor name (will be normalized to lowercase)
         * @return true if cursor uses attributes and needs tracking
         */
        public boolean needsTracking(String cursorName) {
            return cursorsNeedingTracking.contains(cursorName.toLowerCase());
        }

        /**
         * Generates tracking variable declarations for all cursors that need them.
         * Should be called once during DECLARE section processing.
         *
         * For explicit cursors, generates three variables:
         *   - cursor__found BOOLEAN;
         *   - cursor__rowcount INTEGER := 0;
         *   - cursor__isopen BOOLEAN := FALSE;
         *
         * For SQL% implicit cursor, generates only one variable:
         *   - sql__rowcount INTEGER := 0;
         * (SQL%FOUND and SQL%NOTFOUND are expressions based on sql__rowcount,
         *  and SQL%ISOPEN always returns FALSE)
         *
         * @return List of declaration statements (empty if no cursors need tracking)
         */
        public java.util.List<String> generateTrackingDeclarations() {
            java.util.List<String> declarations = new java.util.ArrayList<>();

            for (String cursorName : cursorsNeedingTracking) {
                if (!trackingVariablesDeclared.contains(cursorName)) {
                    // Special handling for SQL% implicit cursor
                    if (cursorName.equalsIgnoreCase("sql")) {
                        // Only generate rowcount variable for SQL cursor
                        // SQL%FOUND → (sql__rowcount > 0)
                        // SQL%NOTFOUND → (sql__rowcount = 0)
                        // SQL%ISOPEN → FALSE (constant)
                        declarations.add("sql__rowcount INTEGER := 0;");
                    } else {
                        // Generate three tracking variables per explicit cursor
                        declarations.add(cursorName + "__found BOOLEAN;");
                        declarations.add(cursorName + "__rowcount INTEGER := 0;");
                        declarations.add(cursorName + "__isopen BOOLEAN := FALSE;");
                    }

                    trackingVariablesDeclared.add(cursorName);
                }
            }

            return declarations;
        }

        /**
         * Resets the tracker (used when entering a new function/procedure).
         */
        public void reset() {
            cursorsNeedingTracking.clear();
            trackingVariablesDeclared.clear();
        }
    }

    // Block-level cursor attribute tracker
    // Tracks cursors that use attributes in the current block
    // One instance per function/procedure (not a stack - cursor names are local to function scope)
    private final CursorAttributeTracker cursorAttributeTracker;

    // Statement-level flag for SELECT INTO tracking
    // Set by VisitQueryBlock when processing a query with INTO clause
    // Checked by VisitSql_statement to inject GET DIAGNOSTICS for SQL% tracking
    private boolean lastStatementHadIntoClause = false;

    // ========== Package Variable Support ==========

    // Assignment target flag for package variable transformation
    // When true, package variable references should NOT be transformed to getters
    // (assignment statement will handle transformation to setter)
    // Package context is now in TransformationContext (unified architecture)
    private boolean isInAssignmentTarget = false;

    /**
     * Creates a PostgresCodeBuilder with transformation context.
     *
     * <p>All transformation-level context (package variables, function name, etc.)
     * is now unified in TransformationContext. No separate parameters needed.</p>
     *
     * @param context Transformation context for metadata lookups (can be null for simple transformations)
     */
    public PostgresCodeBuilder(TransformationContext context) {
        this.context = context;
        this.outerJoinContextStack = new ArrayDeque<>();
        this.rownumContextStack = new ArrayDeque<>();
        this.loopRecordVariablesStack = new ArrayDeque<>();
        this.exceptionContextStack = new ArrayDeque<>();
        this.cursorAttributeTracker = new CursorAttributeTracker();
    }

    /**
     * Creates a PostgresCodeBuilder without context (for simple transformations without metadata).
     */
    public PostgresCodeBuilder() {
        this(null);
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

    /**
     * Registers that a cursor uses at least one attribute (%FOUND, %NOTFOUND, %ROWCOUNT, %ISOPEN).
     * This triggers generation of tracking variables for the cursor.
     * Used by cursor attribute transformation visitors.
     *
     * @param cursorName Cursor name (will be normalized to lowercase)
     */
    public void registerCursorAttributeUsage(String cursorName) {
        cursorAttributeTracker.registerCursorAttributeUsage(cursorName);
    }

    /**
     * Checks if a cursor needs tracking variables (i.e., uses at least one attribute).
     *
     * @param cursorName Cursor name (will be normalized to lowercase)
     * @return true if cursor uses attributes and needs tracking
     */
    public boolean cursorNeedsTracking(String cursorName) {
        return cursorAttributeTracker.needsTracking(cursorName);
    }

    /**
     * Generates tracking variable declarations for all cursors that use attributes.
     * Should be called once during DECLARE section processing.
     *
     * @return List of declaration statements (empty if no cursors use attributes)
     */
    public java.util.List<String> generateCursorTrackingDeclarations() {
        return cursorAttributeTracker.generateTrackingDeclarations();
    }

    /**
     * Resets cursor attribute tracker (used when entering a new function/procedure).
     */
    public void resetCursorAttributeTracker() {
        cursorAttributeTracker.reset();
    }

    /**
     * Pre-scans a function/procedure body to register all cursor attribute usage BEFORE transformation.
     * This ensures that FETCH/OPEN/CLOSE statements can inject tracking code correctly.
     *
     * <p>Problem: Cursor attributes are registered lazily during traversal when encountered.
     * If a FETCH appears before the first cursor attribute (e.g., c%NOTFOUND in EXIT WHEN),
     * the FETCH visitor sees needsTracking() = false and skips state injection.
     *
     * <p>Solution: Pre-scan the entire body to register all cursor attribute usage,
     * then perform the actual transformation with complete registration information.
     *
     * @param bodyCtx The function/procedure body context to scan
     */
    public void prescanCursorAttributes(PlSqlParser.BodyContext bodyCtx) {
        if (bodyCtx == null) {
            return;
        }

        // Create a visitor that only looks for cursor attributes
        me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor<Void> scanner =
            new me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor<Void>() {

            @Override
            public Void visitOther_function(PlSqlParser.Other_functionContext ctx) {
                // Check if this is a cursor attribute reference
                if (ctx.cursor_name() != null) {
                    // Check for any cursor attribute (FOUND, NOTFOUND, ROWCOUNT, ISOPEN)
                    if (ctx.PERCENT_FOUND() != null ||
                        ctx.PERCENT_NOTFOUND() != null ||
                        ctx.PERCENT_ROWCOUNT() != null ||
                        ctx.PERCENT_ISOPEN() != null) {

                        String cursorName = ctx.cursor_name().getText();
                        registerCursorAttributeUsage(cursorName);
                    }
                }

                // Continue visiting children
                return super.visitOther_function(ctx);
            }
        };

        // Walk the entire body tree to register all cursor attributes
        bodyCtx.accept(scanner);
    }

    // ========== SELECT INTO TRACKING (for SQL% implicit cursor) ==========

    /**
     * Marks that the current statement has an INTO clause (SELECT INTO).
     * Called by VisitQueryBlock when processing a query with INTO clause.
     * Checked by VisitSql_statement to inject GET DIAGNOSTICS for SQL% tracking.
     */
    public void markStatementHasIntoClause() {
        this.lastStatementHadIntoClause = true;
    }

    /**
     * Checks if the last statement had an INTO clause and resets the flag.
     * Called by VisitSql_statement after processing each statement.
     *
     * @return true if the last statement had an INTO clause
     */
    public boolean consumeIntoClauseFlag() {
        boolean result = this.lastStatementHadIntoClause;
        this.lastStatementHadIntoClause = false;  // Reset for next statement
        return result;
    }

    // ========== PACKAGE VARIABLE SUPPORT ==========

    /**
     * Gets the current package name from context.
     *
     * @return Current package name, or null if not in a package function
     */
    public String getCurrentPackageName() {
        return context != null ? context.getCurrentPackageName() : null;
    }

    /**
     * Checks if current function is a package member that needs initialization.
     * Returns true if:
     * 1. Currently processing a package function (currentPackageName != null)
     * 2. Package has variables declared
     *
     * @return true if initialization call should be injected
     */
    public boolean needsPackageInitialization() {
        if (context == null || !context.isInPackageMember()) {
            return false;
        }

        PackageContext pkgContext = context.getPackageContext(context.getCurrentPackageName());
        return pkgContext != null && !pkgContext.getVariables().isEmpty();
    }

    /**
     * Generates package initialization call for injection into function body.
     * Format: PERFORM schema.pkg__initialize();
     *
     * @return Initialization call string
     */
    public String generatePackageInitializationCall() {
        if (context == null || !context.isInPackageMember()) {
            return null;
        }

        return "PERFORM " + context.getCurrentSchema().toLowerCase() + "." +
                context.getCurrentPackageName().toLowerCase() + "__initialize()";
    }

    /**
     * Sets the assignment target flag for package variable transformation.
     * When true, package variable references will not be transformed to getters.
     *
     * @param value true if currently in assignment target context
     */
    public void setInAssignmentTarget(boolean value) {
        this.isInAssignmentTarget = value;
    }

    /**
     * Checks if currently in assignment target context.
     *
     * @return true if in assignment target context
     */
    public boolean isInAssignmentTarget() {
        return this.isInAssignmentTarget;
    }

    /**
     * Checks if a dot-qualified reference is a package variable.
     *
     * @param packageName Package name (first part of "pkg.varname")
     * @param variableName Variable name (second part of "pkg.varname")
     * @return true if this is a package variable reference
     */
    public boolean isPackageVariable(String packageName, String variableName) {
        if (context == null) {
            return false;
        }

        return context.isPackageVariable(packageName, variableName);
    }

    /**
     * Transforms a package variable reference to a getter function call.
     * Pattern: packagename.varname → schema.packagename__get_varname()
     *
     * @param packageName Package name
     * @param variableName Variable name
     * @return PostgreSQL getter function call
     */
    public String transformToPackageVariableGetter(String packageName, String variableName) {
        if (context == null) {
            return packageName + "." + variableName;  // Fallback
        }

        return context.getPackageVariableGetter(packageName, variableName);
    }

    /**
     * Parses a package variable reference from assignment left-hand side.
     * Pattern: "pkg.varname" → PackageVariableReference
     *
     * @param leftSide Left-hand side of assignment (e.g., "pkg.varname")
     * @return PackageVariableReference or null if not a package variable
     */
    public PackageVariableReference parsePackageVariableReference(String leftSide) {
        if (leftSide == null || context == null) {
            return null;
        }

        // Check if it's a dot-qualified reference
        String[] parts = leftSide.split("\\.");
        if (parts.length != 2) {
            return null;  // Not a simple dot reference
        }

        String packageName = parts[0].trim();
        String variableName = parts[1].trim();

        // Check if it's actually a package variable
        if (!isPackageVariable(packageName, variableName)) {
            return null;  // Not a package variable
        }

        return new PackageVariableReference(context.getCurrentSchema(), packageName, variableName);
    }

    /**
     * Represents a parsed package variable reference.
     * Used for transforming assignments to setter calls.
     */
    public static class PackageVariableReference {
        private final String schema;
        private final String packageName;
        private final String variableName;

        public PackageVariableReference(String schema, String packageName, String variableName) {
            this.schema = schema;
            this.packageName = packageName;
            this.variableName = variableName;
        }

        /**
         * Generates a setter function call for this package variable.
         * Pattern: schema.packagename__set_varname(rhsExpression)
         *
         * @param rhsExpression Right-hand side expression (value to set)
         * @return PostgreSQL setter function call
         */
        public String getSetterCall(String rhsExpression) {
            return schema.toLowerCase() + "." +
                   packageName.toLowerCase() + "__set_" +
                   variableName.toLowerCase() + "(" + rhsExpression + ")";
        }

        public String getSchema() {
            return schema;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getVariableName() {
            return variableName;
        }
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
    public String visitOpen_statement(PlSqlParser.Open_statementContext ctx) {
        return VisitOpen_statement.v(ctx, this);
    }

    @Override
    public String visitFetch_statement(PlSqlParser.Fetch_statementContext ctx) {
        return VisitFetch_statement.v(ctx, this);
    }

    @Override
    public String visitClose_statement(PlSqlParser.Close_statementContext ctx) {
        return VisitClose_statement.v(ctx, this);
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
    public String visitSql_statement(PlSqlParser.Sql_statementContext ctx) {
        return VisitSql_statement.v(ctx, this);
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
    public String visitVariable_or_collection(PlSqlParser.Variable_or_collectionContext ctx) {
        return VisitVariable_or_collection.v(ctx, this);
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
