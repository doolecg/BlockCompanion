package io.blockcompanion.core.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, enough for BlockDesigner's {@code project.json} and the link messages, so the core
 * needs no JSON library. Objects become {@link Map}s (in file order), arrays {@link List}s, numbers {@link Double}s or
 * {@link Long}s, and {@code null} Java null. {@link #write} turns the same kinds of values back into compact JSON.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws IOException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.error("Unexpected text after the JSON value");
        return v;
    }

    private IOException error(String msg) {
        return new IOException(msg + " at character " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') i++;
            else break;
        }
    }

    private Object value(int depth) throws IOException {
        if (depth > 64) throw error("JSON nested too deep");
        if (i >= s.length()) throw error("Unexpected end of JSON");
        char c = s.charAt(i);
        switch (c) {
            case '{' -> {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) return m;
                while (true) {
                    ws();
                    if (i >= s.length() || s.charAt(i) != '"') throw error("Expected a key");
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    if (peek('}')) return m;
                    expect(',');
                }
            }
            case '[' -> {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) return l;
                while (true) {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                    if (peek(']')) return l;
                    expect(',');
                }
            }
            case '"' -> {
                return string();
            }
            case 't' -> {
                word("true");
                return Boolean.TRUE;
            }
            case 'f' -> {
                word("false");
                return Boolean.FALSE;
            }
            case 'n' -> {
                word("null");
                return null;
            }
            default -> {
                return number();
            }
        }
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) throws IOException {
        if (!peek(c)) throw error("Expected '" + c + "'");
    }

    private void word(String w) throws IOException {
        if (!s.startsWith(w, i)) throw error("Unexpected token");
        i += w.length();
    }

    private String string() throws IOException {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("Unterminated string");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("Bad unicode escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Bad unicode escape");
                    }
                    i += 4;
                }
                default -> throw error("Bad escape");
            }
        }
    }

    private Object number() throws IOException {
        int start = i;
        if (peek('-')) {
            // sign
        }
        boolean fraction = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fraction = true;
                i++;
            } else break;
        }
        String t = s.substring(start, i);
        if (t.isEmpty() || t.equals("-")) throw error("Unexpected character");
        try {
            if (!fraction) return Long.parseLong(t);
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException ex2) {
                throw error("Bad number '" + t + "'");
            }
        }
    }

    // ---- typed access, lenient like Jackson's path().asX(default) ---------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String string(Object o, String def) {
        if (o instanceof String str) return str;
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        return def;
    }

    public static int integer(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        if (o instanceof Boolean b) return b ? 1 : 0;
        return def;
    }

    public static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        if (o instanceof String str) {
            if (str.equalsIgnoreCase("true")) return true;
            if (str.equalsIgnoreCase("false")) return false;
        }
        return def;
    }

    public static long longValue(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static double number(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    // ---- writing ----------------------------------------------------------------------------------------------------

    /**
     * Compact JSON for maps (string keys), iterables, arrays of objects, strings, numbers, booleans, enums and null.
     * Non-finite numbers are written as null.
     */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        switch (v) {
            case null -> b.append("null");
            case String str -> quote(b, str);
            case Boolean bool -> b.append(bool);
            case Double d -> b.append(Double.isFinite(d) ? (d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : d.toString()) : "null");
            case Float f -> write(b, f.doubleValue());
            case Number n -> b.append(n);
            case Enum<?> e -> quote(b, e.name());
            case Map<?, ?> m -> {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(b, String.valueOf(e.getKey()));
                    b.append(':');
                    write(b, e.getValue());
                }
                b.append('}');
            }
            case Iterable<?> it -> {
                b.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) b.append(',');
                    first = false;
                    write(b, o);
                }
                b.append(']');
            }
            case Object[] arr -> write(b, List.of(arr));
            default -> quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
