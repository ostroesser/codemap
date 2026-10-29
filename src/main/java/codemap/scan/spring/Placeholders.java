package codemap.scan.spring;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves {@code ${key:default}} against static config, like Spring's environment with no
 * profile active. Records keys that are missing, and keys whose value differs in some profile.
 */
public final class Placeholders {

    public record Result(String value, Set<String> unresolved, Set<String> profileDependent) {
        public boolean complete() {
            return unresolved.isEmpty();
        }
    }

    private final Map<String, String> defaults;
    private final Map<String, Map<String, String>> profiles;

    /**
     * @param defaults values of the '(default)' profile, highest precedence first already merged
     * @param profiles profile → key → value, for detecting profile-dependent results
     */
    public Placeholders(Map<String, String> defaults, Map<String, Map<String, String>> profiles) {
        this.defaults = defaults;
        this.profiles = profiles;
    }

    public static Placeholders empty() {
        return new Placeholders(Map.of(), Map.of());
    }

    /** Builds from (profile, key, value) rows ordered by precedence: first row for a key wins. */
    public static Placeholders fromRows(List<String[]> rows) {
        Map<String, String> defaults = new LinkedHashMap<>();
        Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
        for (String[] r : rows) {
            if ("(default)".equals(r[0])) {
                defaults.putIfAbsent(r[1], r[2]);
            } else {
                profiles.computeIfAbsent(r[0], p -> new LinkedHashMap<>()).putIfAbsent(r[1], r[2]);
            }
        }
        return new Placeholders(defaults, profiles);
    }

    public String lookup(String key) {
        return defaults.get(key);
    }

    public Result resolve(String expr) {
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> dependent = new LinkedHashSet<>();
        String value = expr == null ? null : resolve(expr, unresolved, dependent, 0);
        return new Result(value, unresolved, dependent);
    }

    private String resolve(String text, Set<String> unresolved, Set<String> dependent, int depth) {
        if (depth > 10 || !text.contains("${")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("${", i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            int end = matchingBrace(text, start + 2);
            if (end < 0) {
                out.append(text, start, text.length());
                break;
            }
            String inner = text.substring(start + 2, end);
            int colon = defaultSeparator(inner);
            String key = colon < 0 ? inner : inner.substring(0, colon);
            String fallback = colon < 0 ? null : inner.substring(colon + 1);
            key = resolve(key, unresolved, dependent, depth + 1);
            String value = defaults.get(key);
            if (value == null) {
                value = relaxedLookup(key);
            }
            for (var p : profiles.entrySet()) {
                String pv = p.getValue().get(key);
                if (pv != null && !pv.equals(value)) {
                    dependent.add(key);
                }
            }
            if (value != null) {
                out.append(resolve(value, unresolved, dependent, depth + 1));
            } else if (fallback != null) {
                out.append(resolve(fallback, unresolved, dependent, depth + 1));
            } else {
                unresolved.add(key);
                out.append("${").append(key).append('}');
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** Spring's relaxed binding for the common case: env-style or kebab vs camel case. */
    private String relaxedLookup(String key) {
        String canonical = canonical(key);
        for (var e : defaults.entrySet()) {
            if (canonical(e.getKey()).equals(canonical)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String canonical(String key) {
        return key.replace("-", "").replace("_", ".").toLowerCase();
    }

    private static int matchingBrace(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            if (s.startsWith("${", i)) {
                depth++;
                i++;
            } else if (s.charAt(i) == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Index of the ':' separating key and default, ignoring colons inside nested ${...}. */
    private static int defaultSeparator(String inner) {
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.startsWith("${", i)) {
                depth++;
                i++;
            } else if (inner.charAt(i) == '}') {
                depth--;
            } else if (inner.charAt(i) == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
