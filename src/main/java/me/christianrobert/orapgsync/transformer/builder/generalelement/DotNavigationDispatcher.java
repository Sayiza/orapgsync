package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatches dot navigation transformations to specialized transformers in priority order.
 *
 * <p>The dispatcher maintains a list of transformers ordered by priority. Each transformer
 * is given a chance to handle the input. The first transformer that returns a handled
 * result wins.
 *
 * <p><strong>Priority Order:</strong>
 * <ol>
 *   <li>PackageVariable - pkg.g_var (before function calls - same syntax)</li>
 *   <li>PackageVariableField - pkg.g_rec.field (before object field)</li>
 *   <li>InlineTypeField - local_rec.field (before table column)</li>
 *   <li>CollectionMethod - v.COUNT (before generic method)</li>
 *   <li>SequenceCall - seq.NEXTVAL (special - no parentheses)</li>
 *   <li>ObjectFieldAccess - table.col.field (before function)</li>
 *   <li>PackageCollectionAccess - pkg.g_array(1) (looks like function)</li>
 *   <li>FunctionCall - catches remaining qualified calls</li>
 *   <li>FALLBACK: Column reference</li>
 * </ol>
 *
 * <p>This class is designed for incremental migration. Transformers are added one at a time,
 * with the original VisitGeneralElement code serving as the fallback until all transformers
 * are extracted.
 */
public class DotNavigationDispatcher {

    private final List<GeneralElementTransformer> transformers = new ArrayList<>();

    /**
     * Creates a dispatcher with an empty transformer list.
     * Transformers should be added via {@link #addTransformer(GeneralElementTransformer)}.
     */
    public DotNavigationDispatcher() {
        // Transformers will be added incrementally during refactoring
    }

    /**
     * Adds a transformer to the end of the priority list.
     * Transformers are tried in the order they are added.
     *
     * @param transformer The transformer to add
     */
    public void addTransformer(GeneralElementTransformer transformer) {
        transformers.add(transformer);
    }

    /**
     * Dispatches dot navigation to registered transformers in priority order.
     *
     * @param parts All parts of the dot navigation
     * @param builder PostgreSQL code builder
     * @return GeneralElementResult from the first transformer that handles the input,
     *         or notHandled() if no transformer handles it
     */
    public GeneralElementResult dispatch(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        for (GeneralElementTransformer transformer : transformers) {
            GeneralElementResult result = transformer.tryTransform(parts, builder);
            if (result.isHandled()) {
                return result;
            }
        }

        // No transformer handled the input
        return GeneralElementResult.notHandled();
    }

    /**
     * @return The number of registered transformers
     */
    public int getTransformerCount() {
        return transformers.size();
    }
}
