package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.antlr.PlSqlParserBaseVisitor;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Analyzes FROM and WHERE clauses to identify Oracle (+) outer join syntax.
 *
 * <p>This analyzer performs two passes:
 * <ol>
 *   <li><b>FROM clause analysis:</b> Extract table names and aliases</li>
 *   <li><b>WHERE clause analysis:</b> Identify (+) conditions and determine join types</li>
 * </ol>
 *
 * <p>The analyzer populates an {@link OuterJoinContext} with all discovered information.
 *
 * <p>Example:
 * <pre>
 * FROM a, b, c
 * WHERE a.field1 = b.field1(+)
 *   AND b.field2 = c.field2(+)
 *   AND a.col1 > 10
 *
 * Results in:
 * - Tables: a, b, c
 * - Outer joins: a LEFT JOIN b ON a.field1 = b.field1
 *                b LEFT JOIN c ON b.field2 = c.field2
 * - Regular WHERE: a.col1 > 10
 * </pre>
 */
public class OuterJoinAnalyzer {

    /**
     * Analyzes FROM and WHERE clauses to populate outer join context.
     *
     * @param fromClauseCtx FROM clause context
     * @param whereClauseCtx WHERE clause context (can be null)
     * @return Populated OuterJoinContext
     */
    public static OuterJoinContext analyze(
            PlSqlParser.From_clauseContext fromClauseCtx,
            PlSqlParser.Where_clauseContext whereClauseCtx) {

        OuterJoinContext context = new OuterJoinContext();

        // Step 1: Analyze FROM clause to extract tables and aliases
        analyzeFromClause(fromClauseCtx, context);

        // Step 2: Analyze WHERE clause to extract outer join conditions
        if (whereClauseCtx != null) {
            analyzeWhereClause(whereClauseCtx, context);
        }

        return context;
    }

    /**
     * Analyzes FROM clause to extract table names and aliases.
     */
    private static void analyzeFromClause(PlSqlParser.From_clauseContext ctx, OuterJoinContext context) {
        PlSqlParser.Table_ref_listContext tableRefListCtx = ctx.table_ref_list();
        if (tableRefListCtx == null) {
            throw new TransformationException("FROM clause missing table_ref_list");
        }

        // Visit each table_ref
        for (PlSqlParser.Table_refContext tableRefCtx : tableRefListCtx.table_ref()) {
            extractTableInfo(tableRefCtx, context);
        }
    }

    /**
     * Extracts table name and alias from a table_ref.
     */
    private static void extractTableInfo(PlSqlParser.Table_refContext ctx, OuterJoinContext context) {
        PlSqlParser.Table_ref_auxContext auxCtx = ctx.table_ref_aux();
        if (auxCtx == null) {
            return;
        }

        // Navigate: table_ref -> table_ref_aux -> table_ref_aux_internal
        PlSqlParser.Table_ref_aux_internalContext internal = auxCtx.table_ref_aux_internal();
        if (internal == null) {
            return;
        }

        // Handle different table_ref_aux_internal types (labeled alternatives in ANTLR grammar)
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
            // Unsupported table reference type - skip
            return;
        }

        // Extract table name
        PlSqlParser.Tableview_nameContext tableviewName = dmlTable.tableview_name();
        if (tableviewName == null) {
            return;
        }

        String tableName = tableviewName.getText();

        // Extract alias (if present)
        String alias = null;
        PlSqlParser.Table_aliasContext aliasCtx = auxCtx.table_alias();
        if (aliasCtx != null) {
            alias = aliasCtx.getText();
        }

        // Register table
        context.registerTable(tableName, alias);
    }

    /**
     * Analyzes WHERE clause to extract outer join conditions.
     */
    private static void analyzeWhereClause(PlSqlParser.Where_clauseContext ctx, OuterJoinContext context) {
        if (ctx.CURRENT() != null) {
            // CURRENT OF cursor - not relevant for outer joins
            return;
        }

        PlSqlParser.ConditionContext conditionCtx = ctx.condition();
        if (conditionCtx == null) {
            return;
        }

        // Use a visitor to traverse the condition tree and find (+) operators
        OuterJoinConditionVisitor visitor = new OuterJoinConditionVisitor(context);
        visitor.visit(conditionCtx);
    }

    /**
     * Visitor that traverses WHERE clause conditions looking for (+) operators.
     */
    private static class OuterJoinConditionVisitor extends PlSqlParserBaseVisitor<Void> {

        private final OuterJoinContext context;

        public OuterJoinConditionVisitor(OuterJoinContext context) {
            this.context = context;
        }

        @Override
        public Void visitLogical_expression(PlSqlParser.Logical_expressionContext ctx) {
            // Grammar: logical_expression : unary_logical_expression
            //                              | logical_expression AND logical_expression
            //                              | logical_expression OR logical_expression

            // Check if this is an AND expression
            if (ctx.AND() != null) {
                // Visit both sides of AND
                return visitChildren(ctx);
            } else if (ctx.OR() != null) {
                // OR expressions are more complex - for now just treat the whole thing as regular
                // (Outer joins with OR are rare and complex)
                context.addRegularWhereCondition(ctx.getText());
                return null;
            }

            // Continue traversing
            return visitChildren(ctx);
        }

        @Override
        public Void visitUnary_logical_expression(PlSqlParser.Unary_logical_expressionContext ctx) {
            // Grammar: unary_logical_expression : NOT? multiset_expression

            // Continue traversing to find relational expressions
            return visitChildren(ctx);
        }

        @Override
        public Void visitMultiset_expression(PlSqlParser.Multiset_expressionContext ctx) {
            // Grammar: multiset_expression : relational_expression ...

            // Continue traversing
            return visitChildren(ctx);
        }

        @Override
        public Void visitRelational_expression(PlSqlParser.Relational_expressionContext ctx) {
            // Grammar: relational_expression : relational_expression relational_operator relational_expression
            //                                 | compound_expression

            // IMPORTANT: Don't drill into subqueries - they will be analyzed when their query_block is visited
            if (containsSubquery(ctx)) {
                // This relational expression contains a subquery - treat whole thing as regular condition
                context.addRegularWhereCondition(ctx.getText());
                return null;
            }

            // Check if this is a comparison with (+)
            if (hasOuterJoinSign(ctx)) {
                processOuterJoinCondition(ctx);
                // Don't traverse children - we've handled this condition
                return null;
            }

            // Not an outer join - check if this is a simple comparison (leaf condition)
            // If it's a leaf condition (not containing other logical operators), add it as regular
            if (isLeafCondition(ctx)) {
                context.addRegularWhereCondition(ctx.getText());
                return null;
            }

            // Continue traversing to find leaf conditions
            return visitChildren(ctx);
        }

        @Override
        public Void visitCompound_expression(PlSqlParser.Compound_expressionContext ctx) {
            // Grammar: compound_expression : concatenation (NOT? (IN ... | BETWEEN ... | LIKE ...))

            // Check if this contains a subquery
            // Subqueries will be analyzed and transformed separately when their query_block is visited
            // We should NOT drill into them during this analysis phase
            if (containsSubquery(ctx)) {
                // This is a compound condition with subquery (e.g., IN (SELECT ...))
                // Check if the outer part has (+) - if so, that's not supported
                if (hasOuterJoinSignInCompound(ctx)) {
                    throw new TransformationException(
                        "Outer join operator (+) in compound expression with subquery not supported: " + ctx.getText()
                    );
                }
                // Add this as a regular WHERE condition
                // NOTE: We use getText() here which captures the RAW text, but that's OK because:
                // 1. This is just for the analysis phase to identify what's NOT an outer join
                // 2. The actual transformation happens in the TRANSFORMATION phase via b.visit()
                // 3. When b.visit() encounters the subquery, it will recursively transform it
                context.addRegularWhereCondition(ctx.getText());
                // Don't traverse children - we've marked this for WHERE
                return null;
            }

            // Check if this contains (+)
            if (hasOuterJoinSignInCompound(ctx)) {
                // Compound expression with (+) - not common, but possible
                throw new TransformationException(
                    "Outer join operator (+) in compound expression not supported: " + ctx.getText()
                );
            }

            // This is a compound condition (IN, BETWEEN, LIKE, etc.) without (+) or subquery
            // Add as regular WHERE condition
            context.addRegularWhereCondition(ctx.getText());
            return null;
        }

        /**
         * Checks if a parse tree contains a subquery.
         * Used to avoid drilling into subqueries during analysis.
         */
        private boolean containsSubquery(ParseTree tree) {
            if (tree instanceof PlSqlParser.SubqueryContext) {
                return true;
            }

            for (int i = 0; i < tree.getChildCount(); i++) {
                if (containsSubquery(tree.getChild(i))) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Checks if a compound expression contains (+).
         */
        private boolean hasOuterJoinSignInCompound(PlSqlParser.Compound_expressionContext ctx) {
            return findOuterJoinSign(ctx) != null;
        }

        /**
         * Checks if a relational expression is a leaf condition (simple comparison).
         */
        private boolean isLeafCondition(PlSqlParser.Relational_expressionContext ctx) {
            // A leaf condition has:
            // - A relational operator (=, <, >, etc.)
            // - Two child relational expressions (left and right)
            // - No nested logical operators (AND, OR)

            if (ctx.relational_operator() != null && ctx.relational_expression().size() == 2) {
                // This is a comparison - it's a leaf if children don't have operators
                for (PlSqlParser.Relational_expressionContext child : ctx.relational_expression()) {
                    if (child.relational_operator() != null) {
                        // Child has operator - not a leaf
                        return false;
                    }
                }
                return true;
            }

            return false;
        }

        /**
         * Checks if a relational expression contains the (+) operator.
         */
        private boolean hasOuterJoinSign(PlSqlParser.Relational_expressionContext ctx) {
            // Look for outer_join_sign in any descendant
            return findOuterJoinSign(ctx) != null;
        }

        /**
         * Recursively searches for outer_join_sign in the tree.
         */
        private PlSqlParser.Outer_join_signContext findOuterJoinSign(ParseTree tree) {
            if (tree instanceof PlSqlParser.Outer_join_signContext) {
                return (PlSqlParser.Outer_join_signContext) tree;
            }

            for (int i = 0; i < tree.getChildCount(); i++) {
                PlSqlParser.Outer_join_signContext result = findOuterJoinSign(tree.getChild(i));
                if (result != null) {
                    return result;
                }
            }

            return null;
        }

        /**
         * Processes an outer join condition (e.g., a.field = b.field(+)).
         */
        private void processOuterJoinCondition(PlSqlParser.Relational_expressionContext ctx) {
            // Grammar: relational_expression relational_operator relational_expression

            if (ctx.relational_operator() == null || ctx.relational_expression().size() != 2) {
                // Not a simple comparison - skip for now
                context.addRegularWhereCondition(ctx.getText());
                return;
            }

            // Check if operator is '='
            String operator = ctx.relational_operator().getText();
            if (!"=".equals(operator)) {
                // Non-equality outer join - not supported, throw exception
                throw new TransformationException(
                    "Non-equality outer join not supported: " + ctx.getText() +
                    ". Only equality (=) is supported with (+) operator."
                );
            }

            PlSqlParser.Relational_expressionContext leftExpr = ctx.relational_expression(0);
            PlSqlParser.Relational_expressionContext rightExpr = ctx.relational_expression(1);

            // Determine which side has (+)
            boolean leftHasPlus = findOuterJoinSign(leftExpr) != null;
            boolean rightHasPlus = findOuterJoinSign(rightExpr) != null;

            if (leftHasPlus && rightHasPlus) {
                throw new TransformationException(
                    "Both sides of condition have (+): " + ctx.getText()
                );
            }

            if (!leftHasPlus && !rightHasPlus) {
                // No (+) found - this shouldn't happen, but treat as regular condition
                context.addRegularWhereCondition(ctx.getText());
                return;
            }

            // Extract table keys from both sides
            String leftTable = extractTableKey(leftExpr);
            String rightTable = extractTableKey(rightExpr);

            if (leftTable == null || rightTable == null) {
                // Can't determine tables - treat as regular condition
                context.addRegularWhereCondition(ctx.getText());
                return;
            }

            // Determine join type
            OuterJoinCondition.JoinType joinType;
            if (rightHasPlus) {
                // a.field = b.field(+) → LEFT JOIN
                joinType = OuterJoinCondition.JoinType.LEFT;
            } else {
                // a.field(+) = b.field → RIGHT JOIN
                joinType = OuterJoinCondition.JoinType.RIGHT;
            }

            // Build condition string without (+)
            String leftText = getTextWithoutOuterJoinSign(leftExpr);
            String rightText = getTextWithoutOuterJoinSign(rightExpr);
            String condition = leftText + " = " + rightText;

            // Register outer join
            context.registerOuterJoin(leftTable, rightTable, joinType, condition);

            // Mark original condition for removal from WHERE
            context.markConditionForRemoval(ctx.getText());
        }

        /**
         * Extracts the table key (alias or table name) from an expression.
         * Looks for patterns like "a.field" or "alias.column".
         */
        private String extractTableKey(PlSqlParser.Relational_expressionContext ctx) {
            // Get the text and look for qualified column reference (table.column)
            String text = ctx.getText();

            // Remove (+) if present
            text = text.replace("(+)", "");

            // Look for first dot
            int dotIndex = text.indexOf('.');
            if (dotIndex > 0) {
                return text.substring(0, dotIndex).trim();
            }

            return null;
        }

        /**
         * Gets the text of an expression without the (+) operator.
         */
        private String getTextWithoutOuterJoinSign(PlSqlParser.Relational_expressionContext ctx) {
            return ctx.getText().replace("(+)", "");
        }
    }
}
