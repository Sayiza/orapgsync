package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.tablereference.TableReferenceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Static helper for visiting Oracle MERGE statements.
 *
 * <p>Oracle MERGE (upsert) syntax:</p>
 * <pre>
 * MERGE INTO target_table t
 * USING source ON (join_condition)
 * WHEN MATCHED THEN UPDATE SET col = value
 * WHEN NOT MATCHED THEN INSERT (cols) VALUES (values);
 * </pre>
 *
 * <p>PostgreSQL equivalent using INSERT ... ON CONFLICT:</p>
 * <pre>
 * INSERT INTO target_table (cols)
 * VALUES (values)
 * ON CONFLICT (key_cols) DO UPDATE SET col = value;
 * </pre>
 *
 * <p><b>Supported patterns:</b></p>
 * <ul>
 *   <li>MERGE INTO ... USING dual ON (condition) - Common upsert idiom</li>
 *   <li>Both WHEN MATCHED and WHEN NOT MATCHED clauses</li>
 * </ul>
 *
 * <p><b>Limitations:</b></p>
 * <ul>
 *   <li>Complex source tables (not dual) require manual review</li>
 *   <li>WHERE clauses in WHEN branches not fully supported</li>
 *   <li>DELETE in WHEN MATCHED not supported</li>
 * </ul>
 */
public class VisitMerge_statement {

    private static final Logger log = LoggerFactory.getLogger(VisitMerge_statement.class);

    public static String v(PlSqlParser.Merge_statementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            return null;
        }

        // Get target table
        List<PlSqlParser.Selected_tableviewContext> tableviews = ctx.selected_tableview();
        if (tableviews.size() < 2) {
            log.warn("MERGE statement missing source or target table");
            return generateFallbackComment(ctx);
        }

        PlSqlParser.Selected_tableviewContext targetCtx = tableviews.get(0);
        PlSqlParser.Selected_tableviewContext sourceCtx = tableviews.get(1);

        // Get target table with full qualification (schema, synonym resolution)
        String targetTable = getQualifiedTableName(targetCtx, b);
        String targetAlias = getTableAlias(targetCtx);

        // Get source table as raw name (just need to check if it's "dual")
        String sourceTableRaw = getRawTableName(sourceCtx);

        if (targetTable == null) {
            log.warn("Could not resolve target table in MERGE statement");
            return generateFallbackComment(ctx);
        }

        // Check if source is DUAL (simple upsert pattern)
        boolean isUsingDual = "dual".equalsIgnoreCase(sourceTableRaw.trim());

        if (!isUsingDual) {
            log.warn("MERGE with non-dual source table requires manual review: {}", sourceTableRaw);
            return generateFallbackComment(ctx);
        }

        // Get the ON condition - extract the conflict column(s)
        PlSqlParser.ConditionContext onCondition = ctx.condition();
        String conflictColumn = extractConflictColumn(onCondition, targetAlias, b);

        if (conflictColumn == null) {
            log.warn("Could not extract conflict column from MERGE ON condition");
            return generateFallbackComment(ctx);
        }

        // Get WHEN MATCHED (UPDATE) clause
        PlSqlParser.Merge_update_clauseContext updateClause = ctx.merge_update_clause();

        // Get WHEN NOT MATCHED (INSERT) clause
        PlSqlParser.Merge_insert_clauseContext insertClause = ctx.merge_insert_clause();

        if (insertClause == null) {
            log.warn("MERGE without INSERT clause (WHEN NOT MATCHED) not supported");
            return generateFallbackComment(ctx);
        }

        // Build INSERT ... ON CONFLICT statement
        return buildInsertOnConflict(
                targetTable, targetAlias, conflictColumn,
                insertClause, updateClause, b
        );
    }

    /**
     * Extracts the fully qualified table name from a selected_tableview context.
     * Uses TableReferenceHelper for proper schema qualification and synonym resolution.
     */
    private static String getQualifiedTableName(PlSqlParser.Selected_tableviewContext ctx, PostgresCodeBuilder b) {
        if (ctx.tableview_name() != null) {
            // Use TableReferenceHelper for proper resolution (schema qualification, synonyms, etc.)
            return TableReferenceHelper.resolveTableviewName(ctx.tableview_name(), b);
        }
        // Subquery case - not supported for MERGE transformation
        return null;
    }

    /**
     * Extracts the raw table name from a selected_tableview context (no resolution).
     * Used for checking source table identity (e.g., "dual").
     */
    private static String getRawTableName(PlSqlParser.Selected_tableviewContext ctx) {
        if (ctx.tableview_name() != null) {
            return ctx.tableview_name().getText();
        }
        // Subquery case
        return ctx.getText();
    }

    /**
     * Extracts the table alias from a selected_tableview context.
     */
    private static String getTableAlias(PlSqlParser.Selected_tableviewContext ctx) {
        if (ctx.table_alias() != null) {
            return ctx.table_alias().getText().toLowerCase();
        }
        return null;
    }

    /**
     * Extracts the conflict column from the ON condition.
     *
     * <p>Expects conditions like: r.study_uid = :OLD.STUDY_ID</p>
     * <p>Extracts: study_uid</p>
     */
    private static String extractConflictColumn(PlSqlParser.ConditionContext condition,
                                                 String targetAlias, PostgresCodeBuilder b) {
        if (condition == null) {
            return null;
        }

        String conditionText = condition.getText().toLowerCase();

        // Look for pattern: alias.column = or column =
        // This is a simplified extraction - just get the first identifier after alias.
        if (targetAlias != null && conditionText.contains(targetAlias + ".")) {
            int aliasStart = conditionText.indexOf(targetAlias + ".");
            int colStart = aliasStart + targetAlias.length() + 1;
            int colEnd = colStart;

            while (colEnd < conditionText.length() &&
                   (Character.isLetterOrDigit(conditionText.charAt(colEnd)) ||
                    conditionText.charAt(colEnd) == '_')) {
                colEnd++;
            }

            if (colEnd > colStart) {
                return conditionText.substring(colStart, colEnd);
            }
        }

        // Fallback: try to find first identifier
        StringBuilder col = new StringBuilder();
        boolean started = false;
        for (char c : conditionText.toCharArray()) {
            if (Character.isLetter(c) || c == '_') {
                col.append(c);
                started = true;
            } else if (started && Character.isDigit(c)) {
                col.append(c);
            } else if (started) {
                break;
            }
        }

        return col.length() > 0 ? col.toString() : null;
    }

    /**
     * Builds the INSERT ... ON CONFLICT statement.
     */
    private static String buildInsertOnConflict(
            String targetTable,
            String targetAlias,
            String conflictColumn,
            PlSqlParser.Merge_insert_clauseContext insertClause,
            PlSqlParser.Merge_update_clauseContext updateClause,
            PostgresCodeBuilder b) {

        StringBuilder result = new StringBuilder();

        // INSERT INTO table
        result.append("INSERT INTO ").append(targetTable);

        // Column list from INSERT clause
        // Use getText() directly - paren_column_list already includes parentheses
        if (insertClause.paren_column_list() != null) {
            String columnList = insertClause.paren_column_list().getText().toLowerCase();
            result.append(" ").append(columnList);
        }

        // VALUES clause - reuse the visitor from VisitInsert_statement
        if (insertClause.values_clause() != null) {
            String valuesClause = VisitInsert_statement.visitValuesClause(insertClause.values_clause(), b);
            result.append("\n").append(valuesClause);
        }

        // ON CONFLICT clause
        result.append("\nON CONFLICT (").append(conflictColumn).append(")");

        if (updateClause != null) {
            // DO UPDATE SET ...
            result.append(" DO UPDATE SET ");

            // Get merge elements (column = expression pairs)
            List<String> updates = new ArrayList<>();
            for (PlSqlParser.Merge_elementContext element : updateClause.merge_element()) {
                String colName = element.column_name().getText().toLowerCase();
                String expr = b.visit(element.expression());

                // Replace target alias references with table name for PostgreSQL
                if (targetAlias != null) {
                    // In ON CONFLICT DO UPDATE, use table name not alias
                    // Handle optional whitespace around the dot (expression visitor may add spaces)
                    expr = expr.replaceAll("(?i)\\b" + targetAlias + "\\s*\\.\\s*", targetTable + ".");
                }

                updates.add(colName + " = " + expr);
            }

            result.append(String.join(", ", updates));
        } else {
            // No update clause - just do nothing on conflict
            result.append(" DO NOTHING");
        }

        return result.toString();
    }

    /**
     * Generates a fallback SQL comment for unsupported MERGE patterns.
     */
    private static String generateFallbackComment(PlSqlParser.Merge_statementContext ctx) {
        return "/* MERGE statement requires manual conversion:\n" +
               ctx.getText().substring(0, Math.min(200, ctx.getText().length())) +
               (ctx.getText().length() > 200 ? "..." : "") +
               "\n*/\nRAISE EXCEPTION 'MERGE not converted - manual review required'";
    }
}
