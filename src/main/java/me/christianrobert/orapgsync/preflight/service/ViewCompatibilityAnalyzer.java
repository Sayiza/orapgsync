package me.christianrobert.orapgsync.preflight.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityFinding;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityStatus;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.transformer.analysis.ConstructDetector;
import me.christianrobert.orapgsync.transformer.analysis.ConstructSupport;
import me.christianrobert.orapgsync.transformer.analysis.DetectedConstruct;
import me.christianrobert.orapgsync.transformer.analysis.ParseCompleteness;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Analyses a single Oracle view without touching PostgreSQL: does its SQL transform, and which
 * catalogued Oracle constructs does it contain.
 *
 * <p>Both questions are answered from one parse — the parse tree is used for construct detection
 * and then handed to the transformer, so a report run costs about as much as a transformation
 * run.</p>
 */
@ApplicationScoped
public class ViewCompatibilityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ViewCompatibilityAnalyzer.class);

    public static final String OBJECT_TYPE = "VIEW";

    @Inject
    AntlrParser parser;

    @Inject
    TransformationService transformationService;

    /**
     * Analyses one view.
     *
     * @param view    Oracle view metadata including its SQL definition
     * @param indices pre-built metadata indices, shared across the whole report run
     * @return the finding for this view; never null and never throws
     */
    public CompatibilityFinding analyze(ViewMetadata view, TransformationIndices indices) {
        String schema = view.getSchema();
        String viewName = view.getViewName();
        String oracleSql = view.getSqlDefinition();

        if (oracleSql == null || oracleSql.isBlank()) {
            return finding(schema, viewName, CompatibilityStatus.NO_SOURCE,
                    "No Oracle SQL definition in state for this view", List.of());
        }

        ParseResult parseResult;
        try {
            parseResult = parser.parseSelectStatement(oracleSql);
        } catch (Exception e) {
            log.debug("Parsing failed for view {}.{}", schema, viewName, e);
            return finding(schema, viewName, CompatibilityStatus.PARSE_ERROR,
                    "Parser threw: " + e.getMessage(), List.of());
        }

        // A failed parse can still leave a partial tree; detect what is visible in it.
        List<DetectedConstruct> constructs = ConstructDetector.detect(parseResult.getTree());

        if (parseResult.hasErrors()) {
            return finding(schema, viewName, CompatibilityStatus.PARSE_ERROR,
                    parseResult.getErrorMessage(), constructs);
        }

        // The grammar entry rule is not anchored to EOF: the parser can end the statement early
        // and leave the rest of the view unread without reporting anything. Everything after that
        // point would be missing from the generated view.
        String unconsumedTail = ParseCompleteness.unconsumedTail(parseResult);
        if (unconsumedTail != null) {
            String message = "Parser stopped before the end of the statement at '"
                    + ParseCompleteness.firstUnconsumedToken(unconsumedTail)
                    + "'; the rest of the view would be missing | unread source: "
                    + ParseCompleteness.snippet(unconsumedTail);
            return finding(schema, viewName, CompatibilityStatus.TRUNCATED_PARSE, message, constructs);
        }

        TransformationResult result;
        try {
            result = transformationService.transformParsedSql(parseResult, schema, indices, false);
        } catch (Exception e) {
            // transformParsedSql handles its own exceptions, but a report run must not abort
            // because of an unexpected one.
            log.debug("Transformation threw for view {}.{}", schema, viewName, e);
            return finding(schema, viewName, CompatibilityStatus.TRANSFORM_ERROR,
                    "Transformer threw: " + e.getMessage(), constructs);
        }

        if (result.isFailure()) {
            return finding(schema, viewName, CompatibilityStatus.TRANSFORM_ERROR,
                    result.getErrorMessage(), constructs);
        }

        boolean hasUnhandledConstruct = constructs.stream()
                .anyMatch(c -> c.support() != ConstructSupport.HANDLED);

        CompatibilityStatus status = hasUnhandledConstruct
                ? CompatibilityStatus.OK_WITH_WARNINGS
                : CompatibilityStatus.OK;

        return finding(schema, viewName, status, null, constructs);
    }

    private CompatibilityFinding finding(String schema, String name, CompatibilityStatus status,
                                         String errorMessage, List<DetectedConstruct> constructs) {
        return new CompatibilityFinding(OBJECT_TYPE, schema, name, status, errorMessage, constructs);
    }
}
