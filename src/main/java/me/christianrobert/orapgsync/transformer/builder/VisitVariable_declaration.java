package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;

/**
 * Static helper for visiting PL/SQL variable declarations.
 *
 * <p>Transforms Oracle variable declarations to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * identifier CONSTANT? type_spec (NOT NULL)? default_value_part? ';'
 *
 * Examples:
 * v_count NUMBER;
 * v_name VARCHAR2(100);
 * v_rate CONSTANT NUMBER := 0.08;
 * v_total NUMBER NOT NULL := 0;
 * v_date DATE DEFAULT SYSDATE;
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * identifier [CONSTANT] pg_type [NOT NULL] [:= default_value];
 *
 * Examples:
 * v_count numeric;
 * v_name text;
 * v_rate CONSTANT numeric := 0.08;
 * v_total numeric NOT NULL := 0;
 * v_date timestamp := CURRENT_TIMESTAMP;
 * </pre>
 *
 * <h3>Transformations Applied:</h3>
 * <ul>
 *   <li>Oracle type → PostgreSQL type via TypeConverter</li>
 *   <li>CONSTANT keyword preserved</li>
 *   <li>NOT NULL constraint preserved</li>
 *   <li>Default value expressions transformed</li>
 *   <li>Semicolon terminator</li>
 * </ul>
 */
public class VisitVariable_declaration {

    /**
     * Transforms variable declaration to PostgreSQL syntax.
     *
     * @param ctx Variable declaration parse tree context
     * @param b PostgresCodeBuilder instance (for visiting default value expressions)
     * @return PostgreSQL variable declaration statement
     */
    public static String v(PlSqlParser.Variable_declarationContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // STEP 1: Extract variable name
        String varName = ctx.identifier().getText().toLowerCase();
        result.append(varName).append(" ");

        // STEP 2: Handle CONSTANT keyword (optional)
        if (ctx.CONSTANT() != null) {
            result.append("CONSTANT ");
        }

        // STEP 3: Convert type from Oracle to PostgreSQL
        String oracleType = ctx.type_spec().getText();
        String postgresType = TypeConverter.toPostgre(oracleType);
        result.append(postgresType);

        // STEP 4: Handle NOT NULL constraint (optional)
        if (ctx.NULL_() != null && ctx.NOT() != null) {
            result.append(" NOT NULL");
        }

        // STEP 5: Handle default value (optional)
        if (ctx.default_value_part() != null) {
            PlSqlParser.Default_value_partContext defaultCtx = ctx.default_value_part();

            // Visit the expression to transform it
            String defaultValue = b.visit(defaultCtx.expression());

            // PostgreSQL uses := for default values (same as Oracle)
            result.append(" := ").append(defaultValue);
        }

        // STEP 6: Semicolon terminator with newline
        result.append(";\n");

        return result.toString();
    }
}
