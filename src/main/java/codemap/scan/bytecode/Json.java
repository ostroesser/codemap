package codemap.scan.bytecode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer for annotation attributes (strings, numbers, booleans, lists, maps). */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Double d && !d.isNaN() && !d.isInfinite()) {
            sb.append(d);
        } else if (value instanceof Float f && !f.isNaN() && !f.isInfinite()) {
            sb.append(f);
        } else {
            string(sb, String.valueOf(value));
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parses JSON text; numbers become Long or Double. Null input gives an empty map. */
    public static Object parse(String text) {
        if (text == null) {
            return new LinkedHashMap<String, Object>();
        }
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("trailing JSON at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    ws();
                    if (s.charAt(i) == '}') {
                        i++;
                        return map;
                    }
                    while (true) {
                        ws();
                        String key = string();
                        ws();
                        expect(':');
                        map.put(key, value());
                        ws();
                        if (s.charAt(i) == ',') {
                            i++;
                        } else {
                            expect('}');
                            return map;
                        }
                    }
                }
                case '[' -> {
                    i++;
                    List<Object> list = new ArrayList<>();
                    ws();
                    if (s.charAt(i) == ']') {
                        i++;
                        return list;
                    }
                    while (true) {
                        list.add(value());
                        ws();
                        if (s.charAt(i) == ',') {
                            i++;
                        } else {
                            expect(']');
                            return list;
                        }
                    }
                }
                case '"' -> {
                    return string();
                }
                default -> {
                    if (s.startsWith("true", i)) {
                        i += 4;
                        return Boolean.TRUE;
                    }
                    if (s.startsWith("false", i)) {
                        i += 5;
                        return Boolean.FALSE;
                    }
                    if (s.startsWith("null", i)) {
                        i += 4;
                        return null;
                    }
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                        i++;
                    }
                    String num = s.substring(start, i);
                    if (num.isEmpty()) {
                        throw new IllegalArgumentException("bad JSON at " + start);
                    }
                    return num.contains(".") || num.contains("e") || num.contains("E")
                            ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }
    }
}
