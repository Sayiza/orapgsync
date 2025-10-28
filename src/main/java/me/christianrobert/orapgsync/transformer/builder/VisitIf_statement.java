package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL IF statements.
 *
 * <p>Transforms Oracle IF/ELSIF/ELSE statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * IF condition THEN seq_of_statements
 * elsif_part* (ELSIF condition THEN seq_of_statements)*
 * else_part? (ELSE seq_of_statements)?
 * END IF
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * IF condition THEN
 *   statements
 * ELSIF condition THEN
 *   statements
 * ELSE
 *   statements
 * END IF;
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>Syntax is identical between Oracle and PostgreSQL</li>
 *   <li>Both use ELSIF (not ELSEIF)</li>
 *   <li>Condition expressions need transformation (Oracle functions)</li>
 *   <li>Statement sequences delegated to VisitSeq_of_statements</li>
 *   <li>PostgreSQL requires semicolon after END IF</li>
 * </ul>
 */
public class VisitIf_statement {

    /**
     * Transforms IF statement to PostgreSQL syntax.
     *
     * @param ctx IF statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting conditions and statements)
     * @return PostgreSQL IF statement
     */
    public static String v(PlSqlParser.If_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // STEP 1: IF condition THEN
        result.append("IF ");
        String condition = b.visit(ctx.condition());
        result.append(condition);
        result.append(" THEN\n");

        // STEP 2: IF branch statements
        String ifStatements = b.visit(ctx.seq_of_statements());
        result.append(ifStatements);

        // STEP 3: ELSIF branches (0 or more)
        if (ctx.elsif_part() != null && !ctx.elsif_part().isEmpty()) {
            for (PlSqlParser.Elsif_partContext elsifCtx : ctx.elsif_part()) {
                result.append("ELSIF ");
                String elsifCondition = b.visit(elsifCtx.condition());
                result.append(elsifCondition);
                result.append(" THEN\n");

                String elsifStatements = b.visit(elsifCtx.seq_of_statements());
                result.append(elsifStatements);
            }
        }

        // STEP 4: ELSE branch (optional)
        if (ctx.else_part() != null) {
            result.append("ELSE\n");
            String elseStatements = b.visit(ctx.else_part().seq_of_statements());
            result.append(elseStatements);
        }

        // STEP 5: END IF (PostgreSQL requires semicolon)
        result.append("END IF");

        return result.toString();
    }
}
