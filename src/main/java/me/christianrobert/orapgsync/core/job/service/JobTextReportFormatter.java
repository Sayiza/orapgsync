package me.christianrobert.orapgsync.core.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * Renders any job result as an indented plain-text report.
 *
 * <p>Why plain text: after a full migration run the browser holds thousands of result rows,
 * and the DOM is the bottleneck. The frontend therefore renders only a capped preview and
 * links here for the complete list. A text document of the same size renders instantly,
 * supports the browser's own search, saves to a file, and — unlike the DOM view — can be
 * diffed between two migration runs.
 *
 * <p>The formatter walks the Jackson tree of the result rather than switching on the result
 * type. That is deliberate: {@code JobResource} already carries a 300-line type switch, and a
 * second one would silently produce empty reports for any job type someone forgot to add.
 * A generic walk covers every present and future result type by construction.
 *
 * <h2>Why this streams</h2>
 * {@link #write} exists alongside {@link #format} because a report is not always small. Step 28
 * on the 2026-08-28 run held 54,093 errors, each carrying the full {@code CREATE FUNCTION} text
 * that failed — hundreds of megabytes of report. {@link JobReportArchive} writes that straight to
 * a file, so the whole document never has to exist as one {@code String} in the heap at the moment
 * the largest job finishes. Building it in memory would fail exactly when the evidence matters
 * most.
 */
public final class JobTextReportFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String INDENT = "  ";

    /** Strings longer than this, or containing newlines, are rendered as an indented block. */
    private static final int INLINE_STRING_LIMIT = 80;

    private JobTextReportFormatter() {
    }

    /**
     * Renders the whole report into a string. Convenient, but holds the entire document in the
     * heap — prefer {@link #write} for anything that goes to a file. See the class note.
     *
     * @param headerFields ordered metadata lines (job id, type, status, timings)
     * @param result       the job result object, may be null
     */
    public static String format(Map<String, String> headerFields, Object result) {
        StringBuilder sb = new StringBuilder();
        try {
            write(sb, headerFields, result);
        } catch (IOException e) {
            // StringBuilder.append does not throw; reaching here means Appendable changed.
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    /**
     * Streams the report to {@code out} without materialising it as a single string.
     *
     * @param out          destination, typically a buffered file writer
     * @param headerFields ordered metadata lines (job id, type, status, timings)
     * @param result       the job result object, may be null
     */
    public static void write(Appendable out, Map<String, String> headerFields, Object result)
            throws IOException {

        out.append("========================================================================\n");
        out.append("  Oracle to PostgreSQL Migration - Job Report\n");
        out.append("========================================================================\n");
        for (Map.Entry<String, String> field : headerFields.entrySet()) {
            out.append(String.format("%-14s %s%n", field.getKey() + ":", field.getValue()));
        }
        out.append(String.format("%-14s %s%n", "Generated:", LocalDateTime.now().format(TIMESTAMP)));
        out.append("========================================================================\n\n");

        if (result == null) {
            out.append("(no result)\n");
            return;
        }

        JsonNode root;
        try {
            root = MAPPER.valueToTree(result);
        } catch (Exception e) {
            // Reporting must never fail louder than the job it reports on.
            out.append("Could not render result of type ")
                    .append(result.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(e.getMessage()))
                    .append('\n');
            return;
        }

        appendNode(out, null, root, 0);
    }

    private static void appendNode(Appendable out, String name, JsonNode node, int depth) throws IOException {
        if (node == null || node.isNull()) {
            appendLine(out, depth, label(name) + "(none)");
        } else if (node.isArray()) {
            appendArray(out, name, node, depth);
        } else if (node.isObject()) {
            appendObject(out, name, node, depth);
        } else {
            appendScalar(out, name, node, depth);
        }
    }

    private static void appendArray(Appendable out, String name, JsonNode node, int depth) throws IOException {
        int size = node.size();
        if (size == 0) {
            appendLine(out, depth, label(name) + "(none)");
            return;
        }

        if (allScalars(node)) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode element : node) {
                if (joined.length() > 0) joined.append(", ");
                joined.append(element.asText());
            }
            appendLine(out, depth, label(name) + joined);
            return;
        }

        appendLine(out, depth, header(name) + " (" + size + ")");
        int index = 0;
        for (JsonNode element : node) {
            appendLine(out, depth + 1, "[" + (++index) + "]");
            appendChildren(out, element, depth + 2);
        }
    }

    private static void appendObject(Appendable out, String name, JsonNode node, int depth) throws IOException {
        if (node.size() == 0) {
            appendLine(out, depth, label(name) + "(none)");
            return;
        }

        if (name != null) {
            appendLine(out, depth, header(name) + " (" + node.size() + ")");
        }
        appendChildren(out, node, name == null ? depth : depth + 1);
    }

    private static void appendChildren(Appendable out, JsonNode node, int depth) throws IOException {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                appendNode(out, field.getKey(), field.getValue(), depth);
            }
        } else {
            appendNode(out, null, node, depth);
        }
    }

    private static void appendScalar(Appendable out, String name, JsonNode node, int depth) throws IOException {
        String text = node.asText();

        boolean isBlock = node.isTextual() && (text.indexOf('\n') >= 0 || text.length() > INLINE_STRING_LIMIT);
        if (!isBlock) {
            appendLine(out, depth, label(name) + text);
            return;
        }

        // Multi-line or long values (SQL definitions, error messages) get their own block so
        // the surrounding key/value columns stay readable.
        appendLine(out, depth, header(name));
        for (String line : text.split("\n", -1)) {
            appendLine(out, depth + 1, line.stripTrailing());
        }
    }

    private static boolean allScalars(JsonNode array) {
        for (JsonNode element : array) {
            if (element.isArray() || element.isObject()) {
                return false;
            }
        }
        return true;
    }

    private static String label(String name) {
        return name == null ? "" : name + ": ";
    }

    private static String header(String name) {
        return name == null ? "" : name + ":";
    }

    private static void appendLine(Appendable out, int depth, String text) throws IOException {
        out.append(INDENT.repeat(depth)).append(text).append('\n');
    }
}
