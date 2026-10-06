package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

/**
 * Visitor helper for case_statement grammar rule (PL/SQL procedural CASE).
 *
 * <p>Oracle and PostgreSQL have nearly identical CASE statement syntax!
 *
 * <p><strong>IMPORTANT:</strong> This handles CASE statements (procedural), not CASE expressions (SQL).
 * <ul>
 *   <li>CASE statement: WHEN/THEN have <strong>statements</strong> (seq_of_statements)</li>
 *   <li>CASE expression: WHEN/THEN have <strong>expressions</strong> (handled by VisitCaseExpression.java)</li>
 * </ul>
 *
 * <p>Grammar rules:
 * <pre>
 * case_statement
 *     : searched_case_statement
 *     | simple_case_statement
 *
 * simple_case_statement
 *     : label_declaration? CASE expression case_when_part_statement+ case_else_part_statement? END CASE? label_name?
 *
 * searched_case_statement
 *     : label_declaration? CASE case_when_part_statement+ case_else_part_statement? END CASE? label_name?
 *
 * case_when_part_statement
 *     : WHEN expression THEN seq_of_statements
 *
 * case_else_part_statement
 *     : ELSE seq_of_statements
 * </pre>
 *
 * <p>Key differences:
 * <ul>
 *   <li>Oracle: Allows "END CASE" or just "END"</li>
 *   <li>PostgreSQL: Only allows "END CASE" for statements (different from expressions!)</li>
 *   <li>Solution: Always output "END CASE" for PL/SQL CASE statements</li>
 * </ul>
 *
 * <p>Simple CASE statement:
 * <pre>
 * Oracle:
 * CASE v_grade
 *   WHEN 'A' THEN v_result := 'Excellent';
 *   WHEN 'B' THEN v_result := 'Good';
 *   ELSE v_result := 'Other';
 * END CASE;
 *
 * PostgreSQL (identical!):
 * CASE v_grade
 *   WHEN 'A' THEN v_result := 'Excellent';
 *   WHEN 'B' THEN v_result := 'Good';
 *   ELSE v_result := 'Other';
 * END CASE;
 * </pre>
 *
 * <p>Searched CASE statement:
 * <pre>
 * Oracle:
 * CASE
 *   WHEN sal > 5000 THEN v_category := 'High';
 *   WHEN sal > 2000 THEN v_category := 'Medium';
 *   ELSE v_category := 'Low';
 * END CASE;
 *
 * PostgreSQL (identical!):
 * CASE
 *   WHEN sal > 5000 THEN v_category := 'High';
 *   WHEN sal > 2000 THEN v_category := 'Medium';
 *   ELSE v_category := 'Low';
 * END CASE;
 * </pre>
 */
public class VisitCase_statement {

    public static String v(PlSqlParser.Case_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Case_statementContext cannot be null");
        }

        // Check which type of CASE statement we have
        PlSqlParser.Searched_case_statementContext searchedCtx = ctx.searched_case_statement();
        if (searchedCtx != null) {
            return buildSearchedCaseStatement(searchedCtx, b);
        }

        PlSqlParser.Simple_case_statementContext simpleCtx = ctx.simple_case_statement();
        if (simpleCtx != null) {
            return buildSimpleCaseStatement(simpleCtx, b);
        }

        throw new TransformationException("CASE statement has no recognized type (searched or simple)");
    }

    /**
     * Builds searched CASE statement.
     *
     * <p>Format: CASE WHEN condition1 THEN statements1 WHEN condition2 THEN statements2 ... ELSE default_statements END CASE
     */
    private static String buildSearchedCaseStatement(
            PlSqlParser.Searched_case_statementContext ctx, PostgresCodeBuilder b) {

        StringBuilder result = new StringBuilder();

        // Handle optional label at start
        if (ctx.label_declaration() != null) {
            String label = ctx.label_declaration().getText();
            result.append(label).append("\n");
        }

        result.append("CASE\n");

        // Process WHEN/THEN pairs
        List<PlSqlParser.Case_when_part_statementContext> whenParts = ctx.case_when_part_statement();
        if (whenParts == null || whenParts.isEmpty()) {
            throw new TransformationException("CASE statement missing WHEN clauses");
        }

        for (PlSqlParser.Case_when_part_statementContext whenPart : whenParts) {
            PlSqlParser.ExpressionContext conditionExpr = whenPart.expression();
            if (conditionExpr == null) {
                throw new TransformationException("WHEN clause missing condition expression");
            }

            PlSqlParser.Seq_of_statementsContext statementsCtx = whenPart.seq_of_statements();
            if (statementsCtx == null) {
                throw new TransformationException("WHEN clause missing THEN statements");
            }

            String condition = b.visit(conditionExpr);
            String statements = b.visit(statementsCtx);

            result.append("  WHEN ").append(condition).append(" THEN\n");
            result.append(statements);
        }

        // Process ELSE clause if present
        PlSqlParser.Case_else_part_statementContext elseCtx = ctx.case_else_part_statement();
        if (elseCtx != null) {
            PlSqlParser.Seq_of_statementsContext elseStatementsCtx = elseCtx.seq_of_statements();
            if (elseStatementsCtx != null) {
                String elseStatements = b.visit(elseStatementsCtx);
                result.append("  ELSE\n");
                result.append(elseStatements);
            }
        }

        // PostgreSQL requires "END CASE" for PL/pgSQL CASE statements (not just "END")
        result.append("END CASE");

        // Handle optional label at end
        if (ctx.label_name() != null) {
            String endLabel = ctx.label_name().getText().toLowerCase();
            result.append(" ").append(endLabel);
        }

        return result.toString();
    }

    /**
     * Builds simple CASE statement.
     *
     * <p>Format: CASE expr WHEN value1 THEN statements1 WHEN value2 THEN statements2 ... ELSE default_statements END CASE
     */
    private static String buildSimpleCaseStatement(
            PlSqlParser.Simple_case_statementContext ctx, PostgresCodeBuilder b) {

        StringBuilder result = new StringBuilder();

        // Handle optional label at start
        if (ctx.label_declaration() != null) {
            String label = ctx.label_declaration().getText();
            result.append(label).append("\n");
        }

        result.append("CASE ");

        // Get the expression to evaluate (the "selector" expression)
        PlSqlParser.ExpressionContext selectorExpr = ctx.expression();
        if (selectorExpr == null) {
            throw new TransformationException("Simple CASE statement missing selector expression");
        }

        String selector = b.visit(selectorExpr);
        result.append(selector).append("\n");

        // Process WHEN/THEN pairs
        List<PlSqlParser.Case_when_part_statementContext> whenParts = ctx.case_when_part_statement();
        if (whenParts == null || whenParts.isEmpty()) {
            throw new TransformationException("CASE statement missing WHEN clauses");
        }

        for (PlSqlParser.Case_when_part_statementContext whenPart : whenParts) {
            PlSqlParser.ExpressionContext whenExpr = whenPart.expression();
            if (whenExpr == null) {
                throw new TransformationException("WHEN clause missing value expression");
            }

            PlSqlParser.Seq_of_statementsContext statementsCtx = whenPart.seq_of_statements();
            if (statementsCtx == null) {
                throw new TransformationException("WHEN clause missing THEN statements");
            }

            String whenValue = b.visit(whenExpr);
            String statements = b.visit(statementsCtx);

            result.append("  WHEN ").append(whenValue).append(" THEN\n");
            result.append(statements);
        }

        // Process ELSE clause if present
        PlSqlParser.Case_else_part_statementContext elseCtx = ctx.case_else_part_statement();
        if (elseCtx != null) {
            PlSqlParser.Seq_of_statementsContext elseStatementsCtx = elseCtx.seq_of_statements();
            if (elseStatementsCtx != null) {
                String elseStatements = b.visit(elseStatementsCtx);
                result.append("  ELSE\n");
                result.append(elseStatements);
            }
        }

        // PostgreSQL requires "END CASE" for PL/pgSQL CASE statements (not just "END")
        result.append("END CASE");

        // Handle optional label at end
        if (ctx.label_name() != null) {
            String endLabel = ctx.label_name().getText().toLowerCase();
            result.append(" ").append(endLabel);
        }

        return result.toString();
    }
}
