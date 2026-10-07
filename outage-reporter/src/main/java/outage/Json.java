package outage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for this service: a strict parser into Map/List/String/
 * Double/Boolean/null, and a writer for the same types plus other Numbers.
 */
final class Json {
    private Json() { }

    static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message, int pos) {
            super(message + " at offset " + pos);
        }
    }

    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw new ParseException("trailing characters", p.pos);
        return v;
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Boolean b -> sb.append(b);
            case Double d when d.isNaN() || d.isInfinite() -> sb.append("null");
            case Float f when f.isNaN() || f.isInfinite() -> sb.append("null");
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            }
            default -> quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            if (pos >= s.length()) throw new ParseException("unexpected end of input", pos);
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw new ParseException("unexpected '" + c + "'", pos);
                }
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw new ParseException("expected a string key", pos);
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, value());
                skipWs();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new ParseException("control character in string", pos - 1);
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) break;
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw new ParseException("bad \\u escape", pos);
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad \\u escape", pos);
                        }
                        pos += 4;
                    }
                    default -> throw new ParseException("bad escape \\" + e, pos - 1);
                }
            }
            throw new ParseException("unterminated string", pos);
        }

        Double number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new ParseException("bad number", start);
            }
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) throw new ParseException("unexpected token", pos);
            pos += word.length();
            return v;
        }

        void expect(char c) {
            if (peek() != c) throw new ParseException("expected '" + c + "'", pos);
            pos++;
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
