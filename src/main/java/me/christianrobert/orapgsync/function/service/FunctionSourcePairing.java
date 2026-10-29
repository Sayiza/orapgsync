package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Matches the bodies a package body declares under one name to the dictionary's overloads of that
 * name.
 *
 * <p>Oracle lets a package declare many subprograms under one name, so the name cannot identify an
 * overload's source. Storing sources keyed by name made every overload take the <em>last</em> body's
 * source: same-kind overloads all reported "implemented" from one body, and a function and a
 * procedure of one name failed to parse each other's source.
 *
 * <p><strong>Position cannot identify it either.</strong> {@code TypeMethodSourcePairing} pairs by
 * rank within a name, which assumes the body declares overloads in the dictionary's order. For
 * package members that is false for one overloaded name in five on the local source database, so a
 * rank-based pairing would silently attach one overload's body to another's signature.
 *
 * <p>Pairing is therefore by <strong>signature</strong>, which Oracle requires a body to repeat from
 * its spec:
 * <ol>
 *   <li>kind (function or procedure), and parameter names and modes in order, exactly;</li>
 *   <li>then each parameter's type, compared coarsely. The dictionary has already resolved subtypes
 *       and anchors to their base type, while the body still spells them as written, so a named type
 *       or an anchor in the source matches anything that is not a different built-in. Only a
 *       built-in against a different built-in excludes a candidate.</li>
 * </ol>
 * A body is paired when it is the only remaining candidate, or the only candidate matching every
 * parameter type exactly. Paired bodies leave the pool, which can settle the rest.
 *
 * <p><strong>Nothing is guessed.</strong> An overload with no unique body is refused by name, and
 * so is every overload whose dictionary signature cannot be told apart from another's, because
 * there the body text is the only evidence and exactness on it would be a guess.
 *
 * <p>Bodies left over once every overload is paired are package-private overloads of a public
 * name. Bodies left over while an overload was refused are not: they may be that overload's.
 */
public final class FunctionSourcePairing {

    private FunctionSourcePairing() {
    }

    /**
     * Outcome for one name.
     *
     * @param ordinals        per dictionary overload, the index of its body, or {@code -1}
     * @param errors          per dictionary overload, why it has no body, or {@code null}
     * @param privateOrdinals bodies that belong to no dictionary overload: private overloads
     * @param unaccounted     bodies neither paired nor private, because an overload was refused
     */
    public record Result(int[] ordinals, String[] errors, List<Integer> privateOrdinals, int unaccounted) {
    }

    /**
     * @param dictionary overloads of one name from {@code ALL_ARGUMENTS}, in any order
     * @param bodies     the signatures of the bodies declared under that name, in package body
     *                   order; an entry is {@code null} when the body's header did not parse
     */
    public static Result pair(List<FunctionMetadata> dictionary, List<FunctionMetadata> bodies) {
        int n = dictionary.size();
        int[] ordinals = new int[n];
        Arrays.fill(ordinals, -1);
        String[] errors = new String[n];
        boolean[] taken = new boolean[bodies.size()];

        if (n == 0) {
            List<Integer> all = new ArrayList<>();
            for (int j = 0; j < bodies.size(); j++) {
                if (bodies.get(j) != null) {
                    all.add(j);
                }
            }
            return new Result(ordinals, errors, all, 0);
        }

        // One overload, one body: the body must be its, since Oracle requires every spec member to
        // have one. Only the kind is checked, so a header our parser reads differently from the
        // dictionary does not cost a member that has exactly one possible source. A header that
        // does not parse is paired too: step 28 then reports the parse error, the actual problem.
        if (n == 1 && bodies.size() == 1) {
            FunctionMetadata body = bodies.get(0);
            if (body != null && !kind(body).equals(kind(dictionary.get(0)))) {
                errors[0] = String.format("the only body named %s in the package body is a %s, the specification declares a %s",
                        dictionary.get(0).getObjectName(), kind(body), kind(dictionary.get(0)));
            } else {
                ordinals[0] = 0;
            }
            return new Result(ordinals, errors, List.of(), 0);
        }

        // Overloads the dictionary cannot tell apart are refused, not guessed.
        Map<String, Integer> sameSignature = new HashMap<>();
        for (FunctionMetadata overload : dictionary) {
            sameSignature.merge(dictionarySignature(overload), 1, Integer::sum);
        }
        for (int i = 0; i < n; i++) {
            if (sameSignature.get(dictionarySignature(dictionary.get(i))) > 1) {
                errors[i] = String.format("%d overloads of %s have the same parameter names, modes and base types in the "
                                + "data dictionary, so its body cannot be told apart from theirs",
                        sameSignature.get(dictionarySignature(dictionary.get(i))), dictionary.get(i).getObjectName());
            }
        }

        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < n; i++) {
                if (ordinals[i] >= 0 || errors[i] != null) {
                    continue;
                }
                List<Integer> candidates = new ArrayList<>();
                List<Integer> exact = new ArrayList<>();
                for (int j = 0; j < bodies.size(); j++) {
                    if (taken[j] || bodies.get(j) == null) {
                        continue;
                    }
                    Match match = match(dictionary.get(i), bodies.get(j));
                    if (match == Match.EXACT) {
                        exact.add(j);
                    }
                    if (match != Match.NONE) {
                        candidates.add(j);
                    }
                }
                int chosen = candidates.size() == 1 ? candidates.get(0)
                        : exact.size() == 1 ? exact.get(0)
                        : -1;
                if (chosen >= 0) {
                    ordinals[i] = chosen;
                    taken[chosen] = true;
                    progress = true;
                }
            }
        }

        boolean refused = false;
        for (int i = 0; i < n; i++) {
            if (ordinals[i] < 0) {
                refused = true;
                if (errors[i] == null) {
                    errors[i] = String.format("none of the %d bodies named %s in the package body can be identified as this "
                                    + "overload by parameter names, modes and types", bodies.size(),
                            dictionary.get(i).getObjectName());
                }
            }
        }

        List<Integer> leftover = new ArrayList<>();
        for (int j = 0; j < bodies.size(); j++) {
            if (!taken[j] && bodies.get(j) != null) {
                leftover.add(j);
            }
        }
        return refused
                ? new Result(ordinals, errors, List.of(), leftover.size())
                : new Result(ordinals, errors, leftover, 0);
    }

    private enum Match { NONE, COMPATIBLE, EXACT }

    private static Match match(FunctionMetadata overload, FunctionMetadata body) {
        if (!kind(overload).equals(kind(body))) {
            return Match.NONE;
        }
        List<FunctionParameter> expected = overload.getParameters();
        List<FunctionParameter> actual = body.getParameters();
        if (expected.size() != actual.size()) {
            return Match.NONE;
        }
        boolean exact = true;
        for (int k = 0; k < expected.size(); k++) {
            FunctionParameter e = expected.get(k);
            FunctionParameter a = actual.get(k);
            if (!name(e.getParameterName()).equals(name(a.getParameterName()))
                    || !mode(e.getInOut()).equals(mode(a.getInOut()))) {
                return Match.NONE;
            }
            String dictionaryType = dictionaryFamily(e);
            String sourceType = sourceFamily(a.getDataType());
            if (sourceType.equals(dictionaryType)) {
                continue;
            }
            if (isBuiltin(sourceType)) {
                return Match.NONE;
            }
            exact = false;
        }
        return exact ? Match.EXACT : Match.COMPATIBLE;
    }

    private static String dictionarySignature(FunctionMetadata overload) {
        StringBuilder sb = new StringBuilder(kind(overload)).append('(');
        for (FunctionParameter p : overload.getParameters()) {
            sb.append(name(p.getParameterName())).append(' ').append(mode(p.getInOut()))
                    .append(' ').append(dictionaryFamily(p)).append(',');
        }
        return sb.append(')').toString();
    }

    private static String kind(FunctionMetadata f) {
        return f.isFunction() ? "FUNCTION" : "PROCEDURE";
    }

    /** Parameter names compare as Oracle stores them: a quoted name without its quotes. */
    private static String name(String parameterName) {
        String s = parameterName == null ? "" : parameterName.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** {@code ALL_ARGUMENTS.IN_OUT} spells it {@code IN/OUT}, the parse tree {@code IN OUT}. */
    private static String mode(String inOut) {
        if (inOut == null) {
            return "IN";
        }
        String m = inOut.toUpperCase(Locale.ROOT).replace('/', ' ').replaceAll("\\s+", " ").trim();
        return m.equals("INOUT") ? "IN OUT" : m;
    }

    private static final String UNKNOWN = "?";

    private static boolean isBuiltin(String family) {
        return !family.equals(UNKNOWN) && !family.startsWith("named:") && !family.startsWith("dictionary:");
    }

    /** The base-type family of a parameter as {@code ALL_ARGUMENTS} reports it. */
    static String dictionaryFamily(FunctionParameter p) {
        String family = builtinFamily(p.getDataType());
        if (family != null) {
            return family;
        }
        if (p.isCustomDataType() && p.getDataTypeName() != null) {
            return "named:" + p.getDataTypeName().toLowerCase(Locale.ROOT);
        }
        return p.getDataType() == null ? UNKNOWN : "dictionary:" + p.getDataType().toUpperCase(Locale.ROOT);
    }

    /**
     * The base-type family of a parameter as the body spells it. An anchor is unknown; a name
     * that is not a built-in is compared by its last component, since the dictionary reports the
     * type's own name without the qualifier the source may use.
     */
    static String sourceFamily(String dataType) {
        if (dataType == null || dataType.contains("%")) {
            return UNKNOWN;
        }
        String family = builtinFamily(dataType);
        if (family != null) {
            return family;
        }
        String t = dataType.trim();
        String last = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
        if (last.length() >= 2 && last.startsWith("\"") && last.endsWith("\"")) {
            last = last.substring(1, last.length() - 1);
        }
        return "named:" + last.toLowerCase(Locale.ROOT);
    }

    private static String builtinFamily(String type) {
        if (type == null) {
            return null;
        }
        String t = type.toUpperCase(Locale.ROOT).replaceAll("\\(.*", "").replaceAll("\\s+", " ").trim();
        switch (t) {
            case "VARCHAR2": case "VARCHAR": case "CHAR": case "NCHAR": case "NVARCHAR2": case "STRING":
            case "LONG": case "CHARACTER":
                return "string";
            case "NUMBER": case "INTEGER": case "INT": case "SMALLINT": case "DECIMAL": case "DEC":
            case "NUMERIC": case "FLOAT": case "REAL": case "DOUBLE PRECISION":
                return "number";
            case "BINARY_INTEGER": case "PLS_INTEGER": case "PL/SQL PLS_INTEGER": case "NATURAL": case "NATURALN":
            case "POSITIVE": case "POSITIVEN": case "SIGNTYPE": case "SIMPLE_INTEGER":
                return "pls_integer";
            case "DATE":
                return "date";
            case "BOOLEAN": case "PL/SQL BOOLEAN":
                return "boolean";
            case "CLOB": case "NCLOB":
                return "clob";
            case "BLOB":
                return "blob";
            case "RAW": case "LONG RAW":
                return "raw";
            case "BINARY_DOUBLE":
                return "binary_double";
            case "BINARY_FLOAT":
                return "binary_float";
            default:
                if (t.startsWith("TIMESTAMP")) {
                    return "timestamp";
                }
                if (t.startsWith("INTERVAL")) {
                    return "interval";
                }
                return null;
        }
    }
}
