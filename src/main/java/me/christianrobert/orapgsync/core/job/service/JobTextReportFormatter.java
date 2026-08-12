package me.christianrobert.orapgsync.core.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
     * @param headerFields ordered metadata lines (job id, type, status, timings)
     * @param result       the job result object, may be null
     */
    public static String format(Map<String, String> headerFields, Object result) {
        StringBuilder sb = new StringBuilder();

        sb.append("========================================================================\n");
        sb.append("  Oracle to PostgreSQL Migration - Job Report\n");
        sb.append("========================================================================\n");
        for (Map.Entry<String, String> field : headerFields.entrySet()) {
            sb.append(String.format("%-14s %s%n", field.getKey() + ":", field.getValue()));
        }
        sb.append(String.format("%-14s %s%n", "Generated:", LocalDateTime.now().format(TIMESTAMP)));
        sb.append("========================================================================\n\n");

        if (result == null) {
            sb.append("(no result)\n");
            return sb.toString();
        }

        JsonNode root;
        try {
            root = MAPPER.valueToTree(result);
        } catch (Exception e) {
            // Reporting must never fail louder than the job it reports on.
            sb.append("Could not render result of type ")
                    .append(result.getClass().getName())
                    .append(": ")
                    .append(e.getMessage())
                    .append('\n');
            return sb.toString();
        }

        appendNode(sb, null, root, 0);
        return sb.toString();
    }

    private static void appendNode(StringBuilder sb, String name, JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            appendLine(sb, depth, label(name) + "(none)");
        } else if (node.isArray()) {
            appendArray(sb, name, node, depth);
        } else if (node.isObject()) {
            appendObject(sb, name, node, depth);
        } else {
            appendScalar(sb, name, node, depth);
        }
    }

    private static void appendArray(StringBuilder sb, String name, JsonNode node, int depth) {
        int size = node.size();
        if (size == 0) {
            appendLine(sb, depth, label(name) + "(none)");
            return;
        }

        if (allScalars(node)) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode element : node) {
                if (joined.length() > 0) joined.append(", ");
                joined.append(element.asText());
            }
            appendLine(sb, depth, label(name) + joined);
            return;
        }

        appendLine(sb, depth, header(name) + " (" + size + ")");
        int index = 0;
        for (JsonNode element : node) {
            appendLine(sb, depth + 1, "[" + (++index) + "]");
            appendChildren(sb, element, depth + 2);
        }
    }

    private static void appendObject(StringBuilder sb, String name, JsonNode node, int depth) {
        if (node.size() == 0) {
            appendLine(sb, depth, label(name) + "(none)");
            return;
        }

        if (name != null) {
            appendLine(sb, depth, header(name) + " (" + node.size() + ")");
        }
        appendChildren(sb, node, name == null ? depth : depth + 1);
    }

    private static void appendChildren(StringBuilder sb, JsonNode node, int depth) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                appendNode(sb, field.getKey(), field.getValue(), depth);
            }
        } else {
            appendNode(sb, null, node, depth);
        }
    }

    private static void appendScalar(StringBuilder sb, String name, JsonNode node, int depth) {
        String text = node.asText();

        boolean isBlock = node.isTextual() && (text.indexOf('\n') >= 0 || text.length() > INLINE_STRING_LIMIT);
        if (!isBlock) {
            appendLine(sb, depth, label(name) + text);
            return;
        }

        // Multi-line or long values (SQL definitions, error messages) get their own block so
        // the surrounding key/value columns stay readable.
        appendLine(sb, depth, header(name));
        for (String line : text.split("\n", -1)) {
            appendLine(sb, depth + 1, line.stripTrailing());
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

    private static void appendLine(StringBuilder sb, int depth, String text) {
        sb.append(INDENT.repeat(depth)).append(text).append('\n');
    }
}
