package codemap.scan.spring;

/** URL path helpers shared by endpoints and HTTP clients. */
public final class Paths {

    private Paths() {
    }

    /** Joins path segments with single slashes; the result starts with '/' unless empty. */
    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            String p = part.strip();
            if (p.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") || p.startsWith("${")) {
                // absolute URL or a placeholder that may hold one: keep as the prefix
                sb.append(stripTrailingSlash(p));
                continue;
            }
            if (!p.startsWith("/")) {
                sb.append('/');
            }
            sb.append(stripTrailingSlash(p));
        }
        String result = sb.toString().replaceAll("(?<!:)/{2,}", "/");
        return result.isEmpty() ? "/" : result;
    }

    private static String stripTrailingSlash(String p) {
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * Matching key for a path: scheme/host and query removed, every {@code {var}} as {@code {}},
     * unresolved placeholders dropped from the front.
     */
    public static String key(String path) {
        if (path == null) {
            return null;
        }
        String p = path.strip();
        p = p.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*", "");
        p = p.replaceFirst("^\\$?\\{[^}]*}", ""); // base URL from a placeholder or an unknown expression
        p = p.replaceAll("\\{[^}/]*}", "{}").replaceAll("(?<=/)\\*(?=/|$)", "{}");
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
