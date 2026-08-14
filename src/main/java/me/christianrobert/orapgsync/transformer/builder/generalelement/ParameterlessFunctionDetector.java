package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognises routine references written without parentheses and rewrites them as PostgreSQL calls.
 *
 * <h2>The problem</h2>
 * Oracle lets a routine that needs no arguments be referenced bare — {@code pkg.get_status} rather
 * than {@code pkg.get_status()}. In an expression that is the same parse shape as
 * {@code table.column}, so the transformer's parenthesis test classified it as a column reference
 * and emitted {@code pkg . get_status}. PostgreSQL reads {@code a.b} as <i>column b of relation
 * a</i> and fails with {@code missing FROM-clause entry for table "pkg"} — the routine appears to
 * have been mistaken for a table.
 *
 * <p>The statement-level case ({@code call_statement}) never had this problem: there the grammar
 * rule itself only matches calls, so no disambiguation is needed. In expression position — which
 * is where view SQL lives — syntax alone cannot decide, and metadata must.
 *
 * <h2>The four shapes</h2>
 * <pre>
 * pkg.func            → hr.pkg__func()          package routine, current schema
 * hr.pkg.func         → hr.pkg__func()          package routine, schema-qualified
 * hr.standalone_func  → hr.standalone_func()    standalone routine, schema-qualified
 * standalone_func     → hr.standalone_func()    standalone routine, unqualified
 * </pre>
 *
 * <h2>Why this cannot corrupt a column reference</h2>
 * Every rewrite requires <b>positive</b> metadata evidence, and column-shaped readings are checked
 * first. The tempting inverse rule — "the qualifier is not a known alias, so it must be a package"
 * — would be wrong, because a table written without an alias registers no alias at all; it is
 * never used here. An identifier the metadata does not recognise is left exactly as it was, so the
 * worst outcome of a miss is the behaviour that existed before this class.
 *
 * <p>Detection order encodes Oracle's own name resolution: columns in scope, then local and
 * package variables, then routines in the current schema, then synonyms.
 *
 * @see me.christianrobert.orapgsync.transformer.context.TransformationIndices#isNoArgCallable(String)
 */
public final class ParameterlessFunctionDetector {

    private ParameterlessFunctionDetector() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Attempts to read a dot-separated identifier chain as a routine reference without parentheses.
     *
     * @param parts Dot-separated parts, none of which carries function arguments
     * @param b PostgreSQL code builder, for context
     * @return the finished PostgreSQL call (e.g. {@code "hr.pkg__func()"}), or {@code null} if this
     *         is not a recognised parameterless routine and should be left alone
     */
    public static String detect(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder b) {

        TransformationContext context = b.getContext();
        if (context == null || parts == null || parts.isEmpty()) {
            return null;
        }

        // An assignment target is a place to store into, never a call.
        if (b.isInAssignmentTarget()) {
            return null;
        }

        // Any parentheses anywhere mean the caller already has a syntactic answer.
        for (PlSqlParser.General_element_partContext part : parts) {
            if (part.function_argument() != null && !part.function_argument().isEmpty()) {
                return null;
            }
            if (part.id_expression() == null) {
                return null;
            }
        }

        List<String> names = new ArrayList<>(parts.size());
        for (PlSqlParser.General_element_partContext part : parts) {
            names.add(IdentifierHelper.canonical(part.id_expression().getText()).toLowerCase());
        }

        switch (names.size()) {
            case 1:
                return detectUnqualified(names.get(0), context);
            case 2:
                return detectTwoPart(names.get(0), names.get(1), context);
            case 3:
                return detectThreePart(names.get(0), names.get(1), names.get(2), context);
            default:
                return null;
        }
    }

    /**
     * Shape 4: bare {@code standalone_func}.
     *
     * <p>The riskiest shape, because every unqualified column in every statement arrives here. The
     * scope, variable and CTE checks below are what make it safe.
     */
    private static String detectUnqualified(String name, TransformationContext context) {
        // A column of a relation in scope always wins.
        if (context.isColumnInScope(name)) {
            return null;
        }

        // PL/SQL locals and package variables are names, not calls.
        if (context.isLocalVariable(name) || context.isCTE(name)) {
            return null;
        }
        if (context.isInPackageMember()
                && context.isPackageVariable(context.getCurrentPackageName(), name)) {
            return null;
        }

        String schema = context.getCurrentSchema().toLowerCase();

        // A sibling in the package currently being transformed, public or private.
        if (context.isInPackageMember()) {
            String packageName = context.getCurrentPackageName();
            if (packageName != null) {
                String qualified = schema + "." + packageName.toLowerCase() + "." + name;
                if (context.isNoArgCallable(qualified)) {
                    return flattenedPackageCall(schema, packageName.toLowerCase(), name);
                }
            }
        }

        // A standalone routine in the current schema.
        String qualified = schema + "." + name;
        if (context.isStandaloneFunction(qualified) && context.isNoArgCallable(qualified)) {
            return qualified + "()";
        }

        // A synonym for a standalone routine elsewhere.
        return standaloneViaSynonym(name, context);
    }

    /**
     * Shapes 1 and 3, which share the two-part {@code a.b} syntax: {@code pkg.func} in the current
     * schema, or {@code schema.standalone_func}.
     */
    private static String detectTwoPart(String first, String second, TransformationContext context) {
        // Column-shaped readings first: a registered alias or CTE qualifier means a column
        // reference, whatever the metadata says about a same-named package.
        if (context.resolveAlias(first) != null || context.isCTE(first)) {
            return null;
        }

        // A record variable's field, not a call.
        if (context.isLocalVariable(first)) {
            return null;
        }

        // Oracle built-in packages live in oracle_compat and are never table aliases. They carry
        // no extracted metadata, so the catalogued name is the evidence.
        if (context.isOracleCompatibilityPackage(first)) {
            return "oracle_compat." + first + "__" + second + "()";
        }

        String schema = context.getCurrentSchema().toLowerCase();

        // Shape 1: package routine in the current schema.
        String asPackageMember = schema + "." + first + "." + second;
        if (context.isNoArgCallable(asPackageMember)) {
            return flattenedPackageCall(schema, first, second);
        }

        // Shape 1 via synonym: the qualifier names a package in another schema.
        String resolved = context.resolveSynonym(first);
        if (resolved != null) {
            String[] target = resolved.toLowerCase().split("\\.");
            if (target.length == 2) {
                String viaSynonym = target[0] + "." + target[1] + "." + second;
                if (context.isNoArgCallable(viaSynonym)) {
                    return flattenedPackageCall(target[0], target[1], second);
                }
            }
        }

        // Shape 3: standalone routine, the qualifier being its schema.
        String asStandalone = first + "." + second;
        if (context.isStandaloneFunction(asStandalone) && context.isNoArgCallable(asStandalone)) {
            return asStandalone + "()";
        }

        return null;
    }

    /**
     * Shape 2: {@code schema.pkg.func}.
     *
     * <p>{@code alias.column.field} shares this syntax but is resolved earlier by
     * {@link ObjectFieldAccessAdapter}; the package-routine lookup here cannot match it.
     */
    private static String detectThreePart(
            String first, String second, String third, TransformationContext context) {

        if (context.resolveAlias(first) != null || context.isLocalVariable(first)) {
            return null;
        }

        String qualified = first + "." + second + "." + third;
        if (context.isNoArgCallable(qualified)) {
            return flattenedPackageCall(first, second, third);
        }

        return null;
    }

    /**
     * Resolves a bare name through a synonym to a standalone routine in another schema.
     */
    private static String standaloneViaSynonym(String name, TransformationContext context) {
        String resolved = context.resolveSynonym(name);
        if (resolved == null) {
            return null;
        }

        String target = resolved.toLowerCase();
        if (context.isStandaloneFunction(target) && context.isNoArgCallable(target)) {
            return target + "()";
        }

        return null;
    }

    /**
     * Builds a call to a migrated package member.
     *
     * <p>The flattened {@code package__function} name and its lower-casing mirror
     * {@code FunctionMetadata.getPostgresName()}, which is what named the object being called.
     */
    private static String flattenedPackageCall(String schema, String packageName, String functionName) {
        return schema + "." + packageName + "__" + functionName + "()";
    }
}
