package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;

import java.util.List;

/**
 * Interface for transformers that handle specific dot navigation patterns in Oracle PL/SQL.
 *
 * <p>Each transformer is responsible for detecting and transforming a specific type of
 * dot-separated identifier pattern:
 * <ul>
 *   <li>SequenceCallTransformer: seq.NEXTVAL, schema.seq.CURRVAL</li>
 *   <li>CollectionMethodTransformer: v.COUNT, v.EXISTS(i)</li>
 *   <li>PackageVariableTransformer: pkg.g_var</li>
 *   <li>PackageVariableFieldTransformer: pkg.g_rec.field</li>
 *   <li>InlineTypeFieldTransformer: local_rec.field</li>
 *   <li>ObjectFieldAccessTransformer: table.col.field</li>
 *   <li>FunctionCallTransformer: pkg.function(), type.method()</li>
 * </ul>
 *
 * <p>Transformers return {@link GeneralElementResult#notHandled()} if they cannot handle
 * the input, allowing the dispatcher to try the next transformer in priority order.
 */
public interface GeneralElementTransformer {

    /**
     * Attempts to transform a dot-separated identifier chain.
     *
     * @param parts All parts of the dot navigation (e.g., [pkg, g_var] or [seq, NEXTVAL])
     * @param builder PostgreSQL code builder for context and recursive transformations
     * @return GeneralElementResult.handled(result) if transformed, GeneralElementResult.notHandled() otherwise
     */
    GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder);
}
