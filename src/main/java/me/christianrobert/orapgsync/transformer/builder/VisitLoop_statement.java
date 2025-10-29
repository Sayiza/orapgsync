package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting PL/SQL loop statements.
 *
 * <p>Transforms Oracle loop statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Loop Types:</h3>
 * <pre>
 * 1. Basic LOOP:
 *    LOOP
 *      statements
 *    END LOOP;
 *
 * 2. WHILE LOOP:
 *    WHILE condition LOOP
 *      statements
 *    END LOOP;
 *
 * 3. FOR LOOP (numeric range):
 *    FOR i IN 1..10 LOOP
 *      statements
 *    END LOOP;
 *
 * 4. FOR LOOP (cursor with inline SELECT):
 *    FOR rec IN (SELECT col1, col2 FROM table) LOOP
 *      statements
 *    END LOOP;
 *
 * 5. FOR LOOP (named cursor):
 *    FOR rec IN cursor_name LOOP
 *      statements
 *    END LOOP;
 * </pre>
 *
 * <h3>Current Implementation:</h3>
 * <ul>
 *   <li>✅ FOR LOOP with numeric range (syntax identical in PostgreSQL)</li>
 *   <li>✅ FOR LOOP with inline SELECT (most common pattern)</li>
 *   <li>❌ Basic LOOP (not yet implemented)</li>
 *   <li>❌ WHILE LOOP (not yet implemented)</li>
 *   <li>❌ FOR LOOP with named cursor (not yet implemented)</li>
 * </ul>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * -- Syntax is identical for numeric range FOR loops
 * FOR i IN 1..10 LOOP
 *   statements
 * END LOOP;
 *
 * -- Syntax is identical for cursor FOR loops
 * FOR rec IN (SELECT col1, col2 FROM table) LOOP
 *   statements
 * END LOOP;
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL syntax is identical to Oracle for inline SELECT cursor loops</li>
 *   <li>Loop labels are supported in both Oracle and PostgreSQL</li>
 *   <li>Parentheses around SELECT are optional in PostgreSQL but required in Oracle</li>
 *   <li>We keep Oracle's parentheses for compatibility</li>
 * </ul>
 */
public class VisitLoop_statement {

    /**
     * Transforms loop statement to PostgreSQL syntax.
     *
     * @param ctx Loop statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting conditions and statements)
     * @return PostgreSQL loop statement
     */
    public static String v(PlSqlParser.Loop_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // STEP 1: Handle optional loop label at start
        // Oracle: <<label_name>> LOOP ... END LOOP label_name;
        // PostgreSQL: <<label_name>> LOOP ... END LOOP label_name;
        if (ctx.label_declaration() != null) {
            // Label format: <<label_name>>
            String label = ctx.label_declaration().getText();
            result.append(label).append("\n");
        }

        // STEP 2: Determine loop type and handle accordingly

        // Check for WHILE loop
        if (ctx.WHILE() != null) {
            throw new UnsupportedOperationException(
                    "WHILE loops are not yet supported. " +
                    "Supported: FOR i IN 1..10 LOOP (numeric ranges) and FOR rec IN (SELECT ...) LOOP (cursor loops).");
        }

        // Check for FOR loop
        if (ctx.FOR() != null) {
            PlSqlParser.Cursor_loop_paramContext cursorParam = ctx.cursor_loop_param();
            if (cursorParam == null) {
                throw new TransformationException("FOR loop missing cursor_loop_param");
            }

            // Determine if it's a numeric range loop or cursor loop
            // Grammar: cursor_loop_param
            //   : index_name IN REVERSE? lower_bound '..' upper_bound  (numeric range)
            //   | record_name IN (cursor_name | '(' select_statement ')')  (cursor)

            // Check for numeric range loop (has '..' range separator)
            if (cursorParam.range_separator != null) {
                // NUMERIC RANGE LOOP: FOR i IN [REVERSE] lower_bound..upper_bound LOOP

                // Extract index_name (loop variable)
                PlSqlParser.Index_nameContext indexNameCtx = cursorParam.index_name();
                if (indexNameCtx == null) {
                    throw new TransformationException("Numeric range FOR loop missing index_name");
                }
                String indexName = indexNameCtx.getText().toLowerCase();

                // Extract REVERSE keyword (optional)
                boolean isReverse = cursorParam.REVERSE() != null;

                // Extract and transform lower_bound
                PlSqlParser.Lower_boundContext lowerBoundCtx = cursorParam.lower_bound();
                if (lowerBoundCtx == null) {
                    throw new TransformationException("Numeric range FOR loop missing lower_bound");
                }
                String lowerBound = b.visit(lowerBoundCtx);

                // Extract and transform upper_bound
                PlSqlParser.Upper_boundContext upperBoundCtx = cursorParam.upper_bound();
                if (upperBoundCtx == null) {
                    throw new TransformationException("Numeric range FOR loop missing upper_bound");
                }
                String upperBound = b.visit(upperBoundCtx);

                // Build FOR loop statement
                // IMPORTANT: PostgreSQL REVERSE requires swapped bounds!
                // Oracle: FOR i IN REVERSE 1..10 LOOP → counts 10, 9, 8, ..., 1
                // PostgreSQL: FOR i IN REVERSE 10..1 LOOP → counts 10, 9, 8, ..., 1
                result.append("FOR ").append(indexName).append(" IN ");
                if (isReverse) {
                    result.append("REVERSE ");
                    // Swap bounds for PostgreSQL REVERSE loops
                    result.append(upperBound).append("..").append(lowerBound);
                } else {
                    result.append(lowerBound).append("..").append(upperBound);
                }
                result.append(" LOOP\n");

                // Note: DO NOT register index variable for RECORD declaration
                // Numeric loop variables are implicitly INTEGER in both Oracle and PostgreSQL
                // Only cursor loop variables need explicit RECORD declarations

            } else {
                // Must be a cursor loop - get the record name
                // Grammar: record_name: identifier | bind_variable
                PlSqlParser.Record_nameContext recordNameCtx = cursorParam.record_name();
                if (recordNameCtx == null) {
                    throw new TransformationException("Cursor FOR loop missing record_name");
                }

                // Handle record_name which can be identifier or bind_variable
                String recordName;
                if (recordNameCtx.identifier() != null) {
                    // Normal case: identifier (e.g., emp_rec)
                    recordName = recordNameCtx.identifier().getText().toLowerCase();
                } else if (recordNameCtx.bind_variable() != null) {
                    // Bind variable case: :emp_rec → emp_rec (strip : prefix)
                    recordName = b.visit(recordNameCtx.bind_variable());
                } else {
                    throw new TransformationException("record_name is neither identifier nor bind_variable");
                }

                // Check if it's inline SELECT or named cursor
                PlSqlParser.Select_statementContext selectStmt = cursorParam.select_statement();
                PlSqlParser.Cursor_nameContext cursorNameCtx = cursorParam.cursor_name();

                if (selectStmt != null) {
                    // INLINE SELECT - This is what we support

                    // Register the loop variable for RECORD declaration
                    // PostgreSQL requires explicit RECORD type declaration for cursor FOR loop variables
                    b.registerLoopRecordVariable(recordName);

                    result.append("FOR ").append(recordName).append(" IN (");

                    // Transform the SELECT statement using existing visitors
                    String transformedSelect = b.visit(selectStmt);
                    result.append(transformedSelect);

                    result.append(") LOOP\n");
                } else if (cursorNameCtx != null) {
                    // NAMED CURSOR - Not yet supported
                    String cursorName = cursorNameCtx.getText();
                    throw new UnsupportedOperationException(
                            "FOR loops with named cursors (FOR rec IN " + cursorName + " LOOP) are not yet supported. " +
                            "Only FOR loops with inline SELECT are currently implemented. " +
                            "You can convert the cursor to inline: FOR rec IN (SELECT ...) LOOP");
                } else {
                    throw new TransformationException("Cursor FOR loop missing both select_statement and cursor_name");
                }
            }
        } else {
            // Basic LOOP (no WHILE or FOR)
            throw new UnsupportedOperationException(
                    "Basic LOOP statements (LOOP...END LOOP) are not yet supported. " +
                    "Supported: FOR i IN 1..10 LOOP (numeric ranges) and FOR rec IN (SELECT ...) LOOP (cursor loops).");
        }

        // STEP 3: Transform loop body statements
        PlSqlParser.Seq_of_statementsContext stmtsCtx = ctx.seq_of_statements();
        if (stmtsCtx == null) {
            throw new TransformationException("Loop missing seq_of_statements");
        }
        String loopBody = b.visit(stmtsCtx);
        result.append(loopBody);

        // STEP 4: END LOOP
        result.append("END LOOP");

        // STEP 5: Handle optional loop label at end
        // Oracle allows repeating the label after END LOOP
        // PostgreSQL also supports this
        if (ctx.label_name() != null) {
            String endLabel = ctx.label_name().getText().toLowerCase();
            result.append(" ").append(endLabel);
        }

        return result.toString();
    }
}
