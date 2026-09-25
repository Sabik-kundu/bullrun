package bullrun;

import java.util.*;

final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String s) {
        Json j = new Json(s);
        j.ws();
        return j.value();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String s) {
        try {
            Object o = parse(s);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (RuntimeException ignored) {
        }
        return new LinkedHashMap<>();
    }

    static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    static String str(Object o, String d) {
        return o instanceof String x ? x : d;
    }

    static double num(Object o, double d) {
        return o instanceof Number n ? n.doubleValue() : d;
    }

    static long lng(Object o, long d) {
        return o instanceof Number n ? n.longValue() : d;
    }

    static boolean bool(Object o, boolean d) {
        return o instanceof Boolean b ? b : d;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
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
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            i++;
            ws();
            m.put(k, value());
            ws();
            if (s.charAt(i++) == '}') return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (s.charAt(i++) == ']') return l;
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        return Double.valueOf(s.substring(st, i));
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
    }

    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        put(b, o);
        return b.toString();
    }

    private static void put(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String x) {
            quote(b, x);
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            b.append(Double.isNaN(d) || Double.isInfinite(d) ? "0" : Double.toString(d));
        } else if (o instanceof Number || o instanceof Boolean) {
            b.append(o);
        } else if (o instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                put(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) b.append(',');
                first = false;
                put(b, x);
            }
            b.append(']');
        } else if (o instanceof long[] a) {
            b.append('[');
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(',');
                b.append(a[k]);
            }
            b.append(']');
        } else if (o instanceof double[] a) {
            b.append('[');
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(',');
                put(b, a[k]);
            }
            b.append(']');
        } else {
            quote(b, o.toString());
        }
    }

    private static void quote(StringBuilder b, String x) {
        b.append('"');
        for (int k = 0; k < x.length(); k++) {
            char c = x.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
