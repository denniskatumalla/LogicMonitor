/** A static threshold: one operator, a value per severity (any may be null). */
class Threshold {
    String op
    Double warning
    Double error
    Double critical

    static Threshold from(Map m) {
        if (!m) return null
        return new Threshold(op: m.op as String,
                warning: m.warning as Double, error: m.error as Double, critical: m.critical as Double)
    }

    /** LogicMonitor's "op warning error critical" form, e.g. "< 30 14 7". */
    String toString() {
        [op, fmt(warning), fmt(error), fmt(critical)].join(" ")
    }

    private static String fmt(Double v) { v == null ? "-" : Alerts.formatValue(v) }
}

/** Threshold evaluation and alert message rendering. */
class Alerts {
    static final List<String> LEVELS = ["critical", "error", "warning"]
    static final List<String> OPS = [">", ">=", "<", "<=", "=", "==", "!="]

    /**
     * Returns the highest severity whose threshold the value crosses, or null.
     * NaN never alerts -- that is why the collection script always reports
     * handshakeOk rather than failing.
     */
    static String level(Threshold t, double value) {
        if (t == null || Double.isNaN(value)) return null
        if (!(t.op in OPS)) throw new IllegalArgumentException("Unsupported threshold operator '${t.op}'")
        return LEVELS.find { String lvl ->
            Double limit = t."${lvl}" as Double
            limit != null && crosses(t.op, value, limit)
        }
    }

    static boolean crosses(String op, double v, double limit) {
        switch (op) {
            case ">":  return v > limit
            case ">=": return v >= limit
            case "<":  return v < limit
            case "<=": return v <= limit
            case "!=": return v != limit
            default:   return v == limit
        }
    }

    /** Alert message tokens this simulator knows how to fill. */
    static final List<String> TOKENS = ["HOST", "HOSTNAME", "INSTANCE", "DSIDESCRIPTION", "DATASOURCE",
                                        "DATAPOINT", "VALUE", "THRESHOLD", "LEVEL"]

    /** Fills ##TOKEN##s; returns [text, tokens it didn't recognise]. */
    static List render(String template, Map<String, String> values) {
        Set<String> unknown = [] as Set
        String text = template.replaceAll(/##([A-Za-z0-9._-]+)##/) { List<String> m ->
            String name = m[1].toUpperCase()
            if (values.containsKey(name)) return values[name]
            unknown << m[1]
            return m[0]
        }
        return [text, unknown as List]
    }

    /** 46.0 -> "46", 0.5 -> "0.5", NaN -> "NaN". */
    static String formatValue(double v) {
        if (Double.isNaN(v)) return "NaN"
        return (v == Math.rint(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v)
    }
}
