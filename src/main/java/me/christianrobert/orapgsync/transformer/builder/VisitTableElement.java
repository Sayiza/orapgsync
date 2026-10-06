package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.generalelement.ImplicitSelfAttributeDetector;
import me.christianrobert.orapgsync.transformer.builder.generalelement.OraclePseudoColumns;
import me.christianrobert.orapgsync.transformer.builder.generalelement.ParameterlessFunctionDetector;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Visitor helper for table_element grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * table_element
 *     : (INTRODUCER char_set_name)? id_expression ('.' id_expression)*
 * </pre>
 *
 * <h2>Why this rule matters more than its name suggests</h2>
 * Despite the name, {@code table_element} is not about tables. It is the alternative the grammar
 * names <em>first</em> for the argument of three functions:
 *
 * <pre>
 * TO_CHAR  '(' (table_element | standard_function | expression) ... ')'
 * TO_DATE  '(' (table_element | standard_function | expression) ... ')'
 * COALESCE '(' table_element (',' (numeric | quoted_string))? ')'
 * </pre>
 *
 * <p>So any dotted-or-undotted identifier written as an argument to those three lands here rather
 * than in {@link VisitGeneralElement}, which is where every other bare identifier in an expression
 * is resolved. This class used to join the parts with {@code getText()} and emit the result as a
 * plain column reference, which produced two distinct failures:
 *
 * <ul>
 *   <li>{@code TO_CHAR(SYSDATE)} emitted the pseudo-column as a column →
 *       {@code column "sysdate" does not exist}. Independent of the format model, so
 *       {@code TO_CHAR(SYSDATE, 'YYYY')} and {@code TO_DATE(SYSDATE, 'YYYY')} failed identically,
 *       while {@code TO_CHAR(SYSDATE + 1)} worked — arithmetic pushes the name back into the
 *       {@code expression} branch.</li>
 *   <li>{@code getText()} on an identifier is the one thing the project forbids outright: a
 *       delimited {@code "HIRE_DATE"} reached PostgreSQL quoted and case-sensitive, missing the
 *       migrated {@code hire_date}. See {@link IdentifierHelper}.</li>
 * </ul>
 *
 * <p>The shared decisions live in {@link OraclePseudoColumns} and
 * {@link ParameterlessFunctionDetector} rather than being restated here, so the two grammar routes
 * cannot answer the same question differently — which is how the SYSDATE bug survived.
 *
 * <h2>What this path deliberately does not do</h2>
 * {@link VisitGeneralElement}'s dot-navigation machinery — object field access, type methods,
 * collection element access, inline type fields, sequence {@code NEXTVAL}/{@code CURRVAL} — is not
 * reachable from here, because {@code table_element} has no syntax for the arguments those
 * constructs carry. A qualified name that is none of the cases below is emitted as a qualified
 * column reference, which is what {@code a.b} means in both dialects.
 */
public class VisitTableElement {

    public static String v(PlSqlParser.Table_elementContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Table_elementContext cannot be null");
        }

        // Check for character set introducer (e.g., _utf8'text')
        if (ctx.INTRODUCER() != null) {
            throw new TransformationException(
                "Character set introducers (INTRODUCER) in table_element not yet supported");
        }

        // Get all id_expression parts (e.g., schema.table.column)
        List<PlSqlParser.Id_expressionContext> idExpressions = ctx.id_expression();
        if (idExpressions == null || idExpressions.isEmpty()) {
            throw new TransformationException(
                "table_element missing id_expression");
        }

        // A single bare name may be an Oracle pseudo-column rather than a column. Checked before
        // anything else, since SYSDATE is not a name that can be looked up or qualified.
        if (idExpressions.size() == 1) {
            String pseudoColumn = OraclePseudoColumns.resolve(idExpressions.get(0).getText());
            if (pseudoColumn != null) {
                return pseudoColumn;
            }

            String packageVariable = resolvePackageVariable(idExpressions.get(0), b);
            if (packageVariable != null) {
                return packageVariable;
            }

            // Inside an object type method a bare name may be an attribute of SELF. Resolved here
            // as well as in VisitGeneralElement, per the table_element blind-spot rule: this rule
            // is the one the grammar names first for the argument of TO_CHAR, TO_DATE and COALESCE.
            String selfAttribute =
                    ImplicitSelfAttributeDetector.detect(idExpressions.get(0).getText(), b);
            if (selfAttribute != null) {
                return selfAttribute;
            }
        }

        // The name may be a routine Oracle allows to be called without parentheses. The detector
        // gives columns in scope, local variables and package variables precedence, so this only
        // fires on a positive metadata match and otherwise leaves the identifier untouched.
        String parameterlessCall = ParameterlessFunctionDetector.detectFromIdExpressions(idExpressions, b);
        if (parameterlessCall != null) {
            return parameterlessCall;
        }

        // Column reference, qualified or not. Each part is normalized independently so a quoted
        // Oracle name matches what the DDL migration created; unquoted names pass through byte-
        // identical, which is what keeps bare USER and LEVEL working as PostgreSQL keywords.
        return idExpressions.stream()
            .map(IdentifierHelper::emit)
            .collect(Collectors.joining("."));
    }

    /**
     * Resolves an unqualified package variable reference to its generated getter call.
     *
     * <p>Inside a package member, Oracle lets a package variable be written bare —
     * {@code TO_CHAR(g_counter)} rather than {@code TO_CHAR(counter_pkg.g_counter)}. Mirrors the
     * single-part branch of {@link VisitGeneralElement}; a {@code table_element} is never an
     * assignment target, so that guard is not repeated.
     *
     * @return the getter call, or {@code null} if this is not a package variable
     */
    private static String resolvePackageVariable(PlSqlParser.Id_expressionContext idCtx, PostgresCodeBuilder b) {
        TransformationContext context = b.getContext();
        if (context == null || !context.isInPackageMember()) {
            return null;
        }

        // Oracle identifiers are case-insensitive, so normalize to the indices' lower-case keys.
        String name = IdentifierHelper.canonical(idCtx.getText()).toLowerCase();
        String packageName = context.getCurrentPackageName();

        if (context.isPackageVariable(packageName, name)) {
            return context.getPackageVariableGetter(packageName, name);
        }
        return null;
    }
}
