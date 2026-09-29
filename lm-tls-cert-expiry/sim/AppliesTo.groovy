import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Evaluates a subset of LogicMonitor's AppliesTo language against a resource's
 * properties.
 *
 * Supported: || && ! ( ), comparisons == != =~ !~ against a quoted string,
 * exists("prop"), hasCategory("cat"), true, false, and a bare property name
 * (true when set and non-empty). Anything else is rejected rather than guessed.
 *
 * Semantics, per LogicMonitor's documentation where it says: property names
 * and values compare case-insensitively, and =~ is a case-insensitive regex.
 * Assumptions: a missing property reads as "", and =~ matches anywhere in the
 * value (find, not a full match).
 */
class AppliesTo {
    private static final Pattern TOKEN = ~/\s*("(?:[^"\\]|\\.)*"|\|\||&&|==|!=|=~|!~|[()!,]|[A-Za-z_][A-Za-z0-9_.\-]*)/

    private final String expr
    private final List<String> toks = []
    private final Map<String, String> props
    private int pos = 0

    private AppliesTo(String expr, Map<String, String> props) {
        this.expr = expr
        this.props = Props.of(props)
        Matcher m = TOKEN.matcher(expr)
        int at = 0
        while (at < expr.length()) {
            if (expr.substring(at).trim().isEmpty()) break
            if (!m.find(at) || m.start() != at) {
                throw new IllegalArgumentException("AppliesTo: cannot parse '${expr.substring(at).trim()}' in: ${expr}")
            }
            toks << m.group(1)
            at = m.end()
        }
    }

    /** Returns whether the expression matches the given properties. */
    static boolean matches(String expr, Map<String, String> props) {
        AppliesTo p = new AppliesTo(expr, props)
        if (p.toks.isEmpty()) throw new IllegalArgumentException("AppliesTo is empty")
        boolean result = p.parseOr()
        if (p.pos != p.toks.size()) p.fail("unexpected '${p.toks[p.pos]}'")
        return result
    }

    private boolean parseOr() {
        boolean v = parseAnd()
        while (peek() == "||") { next(); boolean r = parseAnd(); v = v || r }
        return v
    }

    private boolean parseAnd() {
        boolean v = parseUnary()
        while (peek() == "&&") { next(); boolean r = parseUnary(); v = v && r }
        return v
    }

    private boolean parseUnary() {
        if (peek() == "!") { next(); return !parseUnary() }
        return parsePrimary()
    }

    private boolean parsePrimary() {
        String t = next()
        if (t == "(") {
            boolean v = parseOr()
            expect(")")
            return v
        }
        if (t == "true") return true
        if (t == "false") return false
        if (t == null || !(t ==~ /[A-Za-z_].*/)) fail("expected a property, function or '(' but found '${t}'")

        if (peek() == "(") return callFunction(t)

        String value = props.get(t) ?: ""
        String op = peek()
        if (op in ["==", "!=", "=~", "!~"]) {
            next()
            String literal = stringLiteral()
            switch (op) {
                case "==": return value.equalsIgnoreCase(literal)
                case "!=": return !value.equalsIgnoreCase(literal)
                case "=~": return Pattern.compile(literal, Pattern.CASE_INSENSITIVE).matcher(value).find()
                default:   return !Pattern.compile(literal, Pattern.CASE_INSENSITIVE).matcher(value).find()
            }
        }
        return !value.isEmpty()
    }

    private boolean callFunction(String name) {
        expect("(")
        List<String> fnArgs = []
        if (peek() != ")") {
            fnArgs << stringLiteral()
            while (peek() == ",") { next(); fnArgs << stringLiteral() }
        }
        expect(")")
        switch (name.toLowerCase()) {
            case "exists":
                return fnArgs.size() == 1 && props.containsKey(fnArgs[0])
            case "hascategory":
                List<String> cats = (props.get("system.categories") ?: "").split(",")*.trim()
                return fnArgs.size() == 1 && cats.any { it.equalsIgnoreCase(fnArgs[0]) }
            default:
                fail("function ${name}() is not simulated")
        }
        return false
    }

    private String stringLiteral() {
        String t = next()
        if (t == null || !t.startsWith('"')) fail("expected a quoted string but found '${t}'")
        return t.substring(1, t.length() - 1).replace('\\"', '"').replace('\\\\', '\\')
    }

    private String peek() { pos < toks.size() ? toks[pos] : null }

    private String next() { pos < toks.size() ? toks[pos++] : null }

    private void expect(String t) {
        String got = next()
        if (got != t) fail("expected '${t}' but found '${got}'")
    }

    private void fail(String why) {
        throw new IllegalArgumentException("AppliesTo: ${why} in: ${expr}")
    }
}
