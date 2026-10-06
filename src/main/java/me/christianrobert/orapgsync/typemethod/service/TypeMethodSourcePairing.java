package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodSourceKey;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Matches scanned type body segments to data dictionary rows.
 *
 * <p>Oracle lets many methods on one type share a name, so a name alone cannot identify a method's
 * source. The key is <strong>ordinal within name</strong>: dictionary rows sharing a name are ranked
 * by {@code method_no}, body segments sharing a name are ranked by position in the source, and rank
 * <em>n</em> on one side pairs with rank <em>n</em> on the other.
 *
 * <p>Ordinal-within-name was chosen over using {@code method_no} directly as a per-type source
 * position. {@code method_no} appeared to follow source order across a whole type, but that was
 * observed by eye on a single type and never verified. Ordinal-within-name only assumes that
 * <em>overloads of one name</em> appear in {@code method_no} order, which is a much smaller claim.
 *
 * <p><strong>Nothing is paired when the counts disagree.</strong> A name whose segment count does
 * not match its dictionary count means the scanner and the dictionary disagree about the type, and
 * a mis-pairing would silently attach one overload's body to another overload's signature — the
 * signature and the body would both be individually plausible, so nothing downstream would catch
 * it. The whole type is rejected rather than the one name, because a scanner that mis-detected one
 * boundary may have shifted its neighbours too.
 *
 * <p>Abstract methods ({@code INSTANTIABLE = 'NO'}) are excluded from the expected count: they are
 * declared in the type spec and genuinely have no body. This is the one place the
 * {@code INSTANTIABLE} column carries the meaning it actually has — concrete vs abstract — as
 * opposed to the member-vs-static reading it was previously misused for.
 */
public final class TypeMethodSourcePairing {

    private TypeMethodSourcePairing() {
    }

    /**
     * Outcome of pairing one type. {@code mismatches} empty means every name agreed and
     * {@code segmentsByKey} is safe to use; otherwise it is empty and nothing should be stored.
     */
    public record PairingResult(Map<String, TypeBodySegments.TypeMethodSegment> segmentsByKey,
                                List<String> mismatches) {

        public boolean isConsistent() {
            return mismatches.isEmpty();
        }
    }

    /**
     * Assigns each method its rank among the dictionary rows sharing its (schema, type, name),
     * ordered by {@code method_no}. Mutates the supplied metadata.
     */
    public static void assignOverloadOrdinals(List<TypeMethodMetadata> methods) {
        Map<String, List<TypeMethodMetadata>> byName = new LinkedHashMap<>();
        for (TypeMethodMetadata method : methods) {
            String key = (method.getSchema() + "." + method.getTypeName() + "."
                    + method.getMethodName()).toLowerCase();
            byName.computeIfAbsent(key, k -> new ArrayList<>()).add(method);
        }

        for (List<TypeMethodMetadata> overloads : byName.values()) {
            overloads.sort(Comparator.comparing(m -> methodNoSortKey(m.getMethodNo())));
            for (int ordinal = 0; ordinal < overloads.size(); ordinal++) {
                overloads.get(ordinal).setOverloadOrdinal(ordinal);
            }
        }
    }

    /**
     * Pairs the segments scanned out of one type body against that type's dictionary rows.
     *
     * @param dictionaryMethods rows for this type only, with ordinals already assigned
     * @param segments          segments in source order
     */
    public static PairingResult pair(String schema,
                                     String typeName,
                                     List<TypeMethodMetadata> dictionaryMethods,
                                     List<TypeBodySegments.TypeMethodSegment> segments) {

        // Expected: concrete methods only. Abstract ones have no body by definition.
        Map<String, Integer> expectedByName = new TreeMap<>();
        for (TypeMethodMetadata method : dictionaryMethods) {
            if (isAbstract(method)) {
                continue;
            }
            expectedByName.merge(method.getMethodName().toLowerCase(), 1, Integer::sum);
        }

        Map<String, Integer> foundByName = new TreeMap<>();
        for (TypeBodySegments.TypeMethodSegment segment : segments) {
            foundByName.merge(segment.getName().toLowerCase(), 1, Integer::sum);
        }

        List<String> mismatches = new ArrayList<>();
        for (String name : union(expectedByName, foundByName)) {
            int expected = expectedByName.getOrDefault(name, 0);
            int found = foundByName.getOrDefault(name, 0);
            if (expected != found) {
                mismatches.add(String.format(
                        "%s.%s.%s: dictionary has %d concrete method(s), type body has %d",
                        schema, typeName, name, expected, found));
            }
        }

        if (!mismatches.isEmpty()) {
            return new PairingResult(Map.of(), mismatches);
        }

        Map<String, TypeBodySegments.TypeMethodSegment> byKey = new LinkedHashMap<>();
        Map<String, Integer> nextOrdinal = new LinkedHashMap<>();
        for (TypeBodySegments.TypeMethodSegment segment : segments) {
            String name = segment.getName().toLowerCase();
            int ordinal = nextOrdinal.merge(name, 0, (existing, ignored) -> existing + 1);
            byKey.put(TypeMethodSourceKey.of(name, ordinal), segment);
        }

        return new PairingResult(byKey, List.of());
    }

    private static boolean isAbstract(TypeMethodMetadata method) {
        return "NO".equalsIgnoreCase(method.getInstantiable());
    }

    private static List<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> names = new ArrayList<>(a.keySet());
        for (String name : b.keySet()) {
            if (!a.containsKey(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * {@code method_no} is numeric in Oracle but arrives as text. Sort numerically so that 10 does
     * not order before 2; fall back to text if it is ever non-numeric.
     */
    private static String methodNoSortKey(String methodNo) {
        if (methodNo == null) {
            return "";
        }
        String trimmed = methodNo.trim();
        try {
            return String.format("%020d", Long.parseLong(trimmed));
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }
}
