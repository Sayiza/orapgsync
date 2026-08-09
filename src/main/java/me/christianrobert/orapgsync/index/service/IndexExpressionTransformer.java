package me.christianrobert.orapgsync.index.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts an Oracle function-based index expression to its PostgreSQL equivalent.
 *
 * <p>The transformer has no entry rule for a bare expression - it transforms statements - so the
 * expression is wrapped in a probe query against its own table, transformed, and the projection
 * read back out. Wrapping it against the real table matters: it gives the transformer the column
 * metadata it needs to resolve identifiers and to distinguish a column from a function call.</p>
 *
 * <h2>Strict rather than best-effort</h2>
 *
 * <p>If the transformation fails, or the result does not match the expected probe shape exactly,
 * this returns empty and the caller reports the index as unsupported. It never falls back to
 * guessing or to passing the Oracle text through: an index built on a silently mis-transformed
 * expression is wrong in a way nobody would notice, which is the failure mode the transformer
 * hardening work exists to remove.</p>
 *
 * <p>Transformability is not the only hurdle - PostgreSQL additionally requires index expressions
 * to be {@code IMMUTABLE}, and Oracle functions that are merely deterministic often map to
 * {@code STABLE} PostgreSQL equivalents. That is not pre-checked here; such an index fails at
 * {@code CREATE INDEX} and is reported with PostgreSQL's own error message.</p>
 */
@ApplicationScoped
public class IndexExpressionTransformer {

    private static final Logger log = LoggerFactory.getLogger(IndexExpressionTransformer.class);

    /** The probe query shape, anchored so a differently shaped result is rejected rather than sliced. */
    private static final Pattern PROBE_PROJECTION =
            Pattern.compile("(?is)^\\s*select\\s+(.+?)\\s+from\\s+[^\\s;]+\\s*;?\\s*$");

    @Inject
    TransformationService transformationService;

    /**
     * @return the PostgreSQL expression, or empty if it could not be transformed with confidence
     */
    public Optional<String> transform(String oracleExpression, String schema, String table,
                                      TransformationIndices indices) {
        if (oracleExpression == null || oracleExpression.isBlank()) {
            return Optional.empty();
        }

        String probe = "SELECT " + oracleExpression + " FROM " + schema + "." + table;

        TransformationResult result;
        try {
            result = transformationService.transformSql(probe, schema, indices);
        } catch (RuntimeException e) {
            log.debug("Index expression transformation threw for '{}': {}", oracleExpression, e.getMessage());
            return Optional.empty();
        }

        if (result == null || !result.isSuccess() || result.getPostgresSql() == null) {
            return Optional.empty();
        }

        Matcher matcher = PROBE_PROJECTION.matcher(result.getPostgresSql());
        if (!matcher.matches()) {
            log.debug("Index expression probe produced an unexpected shape for '{}': {}",
                    oracleExpression, result.getPostgresSql());
            return Optional.empty();
        }

        String expression = matcher.group(1).trim();
        if (expression.isEmpty() || "*".equals(expression)) {
            return Optional.empty();
        }

        return Optional.of(expression);
    }
}
