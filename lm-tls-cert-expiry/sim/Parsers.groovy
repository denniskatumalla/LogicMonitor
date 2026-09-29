/** One instance line from Active Discovery. */
class DiscoveredInstance {
    String wildvalue
    String wildalias
    String description
    Map<String, String> props = [:]
    /** Why LogicMonitor would give this instance NoData, or null if it's valid. */
    String invalidReason
}

/** Everything Active Discovery output parsed into. */
class DiscoveryParse {
    List<DiscoveredInstance> instances = []
    List<String> errors = []
    List<String> warnings = []
}

/** Parses script output the way the collector does. */
class Parsers {
    /** Characters LogicMonitor documents as invalid in a wildvalue. */
    static final String FORBIDDEN_WILDVALUE_CHARS = '=:\\# '
    static final int MAX_WILDALIAS = 255

    /**
     * Parses id##name##description####key=value&key=value lines. Malformed
     * lines are reported as errors and dropped; a wildvalue with forbidden
     * characters is kept but marked invalid, because LogicMonitor creates the
     * instance and then collects NoData for it.
     */
    static DiscoveryParse parseDiscovery(String stdout) {
        DiscoveryParse result = new DiscoveryParse()
        Set<String> seen = [] as Set
        stdout.readLines().eachWithIndex { String line, int i ->
            if (!line.trim()) return
            String where = "line ${i + 1}"

            int split = line.indexOf("####")
            String head = split >= 0 ? line.substring(0, split) : line
            String propPart = split >= 0 ? line.substring(split + 4) : ""
            List<String> fields = head.split("##", 3) as List<String>
            if (fields.size() < 2) {
                result.errors << "${where}: expected id##name[##description][####props], got '${line}'".toString()
                return
            }

            DiscoveredInstance inst = new DiscoveredInstance(
                    wildvalue: fields[0], wildalias: fields[1], description: fields.size() > 2 ? fields[2] : "")
            if (!inst.wildvalue) {
                result.errors << "${where}: empty wildvalue".toString()
                return
            }
            if (!seen.add(inst.wildvalue)) {
                result.errors << "${where}: duplicate wildvalue '${inst.wildvalue}'".toString()
                return
            }
            String bad = inst.wildvalue.findAll { FORBIDDEN_WILDVALUE_CHARS.contains(it) }.unique().collect { it == " " ? "space" : "'${it}'" }.join(", ")
            if (bad) {
                inst.invalidReason = "wildvalue '${inst.wildvalue}' contains ${bad}; LogicMonitor returns NoData for it".toString()
                result.errors << "${where}: ${inst.invalidReason}".toString()
            }
            if (inst.wildalias.length() > MAX_WILDALIAS) {
                result.errors << "${where}: name is ${inst.wildalias.length()} characters; the limit is ${MAX_WILDALIAS}".toString()
            }

            propPart.split("&").findAll { it }.each { String pair ->
                int eq = pair.indexOf("=")
                if (eq <= 0) {
                    result.errors << "${where}: property '${pair}' is not key=value".toString()
                    return
                }
                String key = pair.substring(0, eq)
                if (!key.startsWith("auto.")) {
                    result.warnings << "${where}: property '${key}' has no auto. prefix".toString()
                }
                inst.props[key] = pair.substring(eq + 1)
            }
            result.instances << inst
        }
        return result
    }

    /** Parses key=value lines; later keys win. Lines without '=' are ignored. */
    static Map<String, String> parseKeyValue(String stdout) {
        Map<String, String> kv = [:]
        stdout.readLines().each { String line ->
            int eq = line.indexOf("=")
            if (eq > 0) kv[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return kv
    }

    /** A datapoint value: the number for the key, or NaN if missing or not numeric. */
    static double valueFor(Map<String, String> kv, String key) {
        String raw = kv[key]
        if (raw == null) return Double.NaN
        try {
            return Double.parseDouble(raw)
        } catch (NumberFormatException ignored) {
            return Double.NaN
        }
    }
}
