package com.rumitx.app.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small JSON reader and writer. No org.json, so host unit tests compile with javac. */
public final class Json {
    private Json() {}

    public enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL }

    public static final class Value {
        public final Type type;
        public final String string;
        public final double number;
        public final boolean bool;
        public final Map<String, Value> object;
        public final List<Value> array;

        private Value(Type type, String string, double number, boolean bool,
                      Map<String, Value> object, List<Value> array) {
            this.type = type;
            this.string = string;
            this.number = number;
            this.bool = bool;
            this.object = object;
            this.array = array;
        }

        public boolean isObject() { return type == Type.OBJECT; }

        public Value get(String key) {
            if (object == null) return null;
            return object.get(key);
        }

        public boolean has(String key) { return object != null && object.containsKey(key); }

        public Set<String> keys() { return object == null ? java.util.Collections.<String>emptySet() : object.keySet(); }

        public String str(String key, String fallback) {
            Value v = get(key);
            return v != null && v.type == Type.STRING ? v.string : fallback;
        }

        public double num(String key, double fallback) {
            Value v = get(key);
            return v != null && v.type == Type.NUMBER ? v.number : fallback;
        }

        public boolean bool(String key, boolean fallback) {
            Value v = get(key);
            return v != null && v.type == Type.BOOL ? v.bool : fallback;
        }

        public Value obj(String key) {
            Value v = get(key);
            return v != null && v.type == Type.OBJECT ? v : null;
        }
    }

    public static final class Obj {
        private final StringBuilder sb = new StringBuilder();
        private boolean first = true;

        public Obj() { sb.append('{'); }

        public Obj put(String key, String value) {
            comma();
            sb.append(quote(key)).append(':');
            sb.append(value == null ? "null" : quote(value));
            return this;
        }

        public Obj put(String key, long value) {
            comma();
            sb.append(quote(key)).append(':').append(value);
            return this;
        }

        public Obj put(String key, double value) {
            comma();
            sb.append(quote(key)).append(':');
            if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 1e15) {
                sb.append((long) value);
            } else if (Double.isFinite(value)) {
                sb.append(String.format(Locale.US, "%.5f", value));
            } else {
                sb.append("null");
            }
            return this;
        }

        public Obj put(String key, boolean value) {
            comma();
            sb.append(quote(key)).append(':').append(value ? "true" : "false");
            return this;
        }

        public Obj raw(String key, String json) {
            comma();
            sb.append(quote(key)).append(':').append(json == null ? "null" : json);
            return this;
        }

        public Obj put(String key, Obj nested) {
            return raw(key, nested == null ? "null" : nested.toString());
        }

        @Override public String toString() { return sb.toString() + "}"; }

        private void comma() {
            if (!first) sb.append(',');
            first = false;
        }
    }

    public static String quote(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String error(String message) {
        return new Obj().put("ok", false).put("error", message == null ? "error" : message).toString();
    }

    public static Value parse(String text) {
        if (text == null) throw new IllegalArgumentException("empty json");
        Parser p = new Parser(text);
        Value v = p.value();
        p.skip();
        if (p.i != p.s.length()) throw new IllegalArgumentException("trailing json");
        return v;
    }

    public static String emit(Value value) {
        if (value == null || value.type == Type.NULL) return "null";
        if (value.type == Type.STRING) return quote(value.string);
        if (value.type == Type.BOOL) return value.bool ? "true" : "false";
        if (value.type == Type.NUMBER) {
            if (Double.isFinite(value.number) && value.number == Math.rint(value.number) && Math.abs(value.number) < 1e15) {
                return Long.toString((long) value.number);
            }
            return Double.toString(value.number);
        }
        if (value.type == Type.ARRAY) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < value.array.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(emit(value.array.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        if (value.type == Type.OBJECT) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Value> entry : value.object.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(entry.getKey())).append(':').append(emit(entry.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        throw new IllegalStateException("Unhandled json type " + value.type);
    }

    /** True when an object carries an encryption-key field. Nested objects are checked one level. */
    public static boolean hasSecret(Value value) {
        if (value == null || value.object == null) return false;
        for (String key : value.object.keySet()) {
            if (secretName(key)) return true;
            Value child = value.object.get(key);
            if (child != null && child.object != null) {
                for (String nested : child.object.keySet()) {
                    if (secretName(nested)) return true;
                }
            }
        }
        return false;
    }

    private static boolean secretName(String key) {
        String n = key.toLowerCase(Locale.US).replace("_", "");
        return n.equals("encry") || n.equals("secret") || n.equals("secretkey")
                || n.equals("encryptionkey") || n.equals("secretkeyhex") || n.equals("aeskey");
    }

    private static final class Parser {
        final String s;
        int i;
        int depth;

        Parser(String s) { this.s = s; }

        void skip() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

        Value value() {
            skip();
            if (i >= s.length()) throw new IllegalArgumentException("truncated json");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return new Value(Type.STRING, string(), 0, false, null, null);
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') { literal("null"); return new Value(Type.NULL, null, 0, false, null, null); }
            return number();
        }

        Value object() {
            if (++depth > 8) throw new IllegalArgumentException("json too deep");
            expect('{');
            Map<String, Value> map = new LinkedHashMap<String, Value>();
            skip();
            if (peek('}')) { i++; depth--; return new Value(Type.OBJECT, null, 0, false, map, null); }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(':');
                map.put(key, value());
                skip();
                if (peek('}')) { i++; break; }
                expect(',');
            }
            depth--;
            return new Value(Type.OBJECT, null, 0, false, map, null);
        }

        Value array() {
            if (++depth > 8) throw new IllegalArgumentException("json too deep");
            expect('[');
            List<Value> list = new ArrayList<Value>();
            skip();
            if (peek(']')) { i++; depth--; return new Value(Type.ARRAY, null, 0, false, null, list); }
            while (true) {
                list.add(value());
                skip();
                if (peek(']')) { i++; break; }
                expect(',');
            }
            depth--;
            return new Value(Type.ARRAY, null, 0, false, null, list);
        }

        Value bool() {
            if (s.startsWith("true", i)) { i += 4; return new Value(Type.BOOL, null, 0, true, null, null); }
            if (s.startsWith("false", i)) { i += 5; return new Value(Type.BOOL, null, 0, false, null, null); }
            throw new IllegalArgumentException("bad json bool");
        }

        Value number() {
            int start = i;
            if (peek('-')) i++;
            if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') throw new IllegalArgumentException("bad json number");
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            }
            double n = Double.parseDouble(s.substring(start, i));
            if (!Double.isFinite(n)) throw new IllegalArgumentException("bad json number");
            return new Value(Type.NUMBER, null, n, false, null, null);
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("bad json string");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                        case '\\':
                        case '/': sb.append(e); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad json unicode");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("bad json escape");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated json string");
        }

        void literal(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad json");
            i += word.length();
        }

        void expect(char c) {
            skip();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c);
            i++;
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
    }
}
