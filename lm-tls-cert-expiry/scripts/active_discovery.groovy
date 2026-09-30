/*
 * TLS_Certificate_Expiry-  |  active discovery
 *
 * One instance per endpoint listed in the resource property tls.endpoints.
 * Output contract:  id##name##description####auto.key=value&auto.key=value
 *
 * The id (wildvalue) may not contain = : \ # or spaces -- LogicMonitor returns
 * NoData for such instances -- so it is host_port. The display name keeps the
 * familiar host:port, and collection reads auto.tls.host / auto.tls.port
 * rather than parsing the id.
 */

// The collector binds hostProps; locally it doesn't exist, so fall back to an
// argument. Same file runs in both places.
def raw
if (binding.hasVariable("hostProps")) {
    raw = hostProps.get("tls.endpoints")
} else {
    def cli = binding.hasVariable("args") ? args : null
    raw = (cli && cli.length > 0) ? cli[0] : "www.logicmonitor.com:443,expired.badssl.com:443"
}
// Property empty or cleared: no instances. Exiting 0 with no output removes
// any previously discovered instances, which is what clearing it should do.
if (!raw?.trim()) return 0

def seen = [] as Set
raw.split(",").each { entry ->
    def e = entry.trim()
    if (!e) return

    def bits = e.tokenize(":")
    def h = bits[0]
    def p = (bits.size() > 1 ? bits[1] : "443")

    // Skip what can't be a host[:port] (IPv6 literals, typos) rather than
    // emitting an instance that can never collect. Count colons in the raw
    // entry: tokenize() drops empty pieces, so "::1" would pass as host "1".
    // !(x in r) rather than x !in r, which needs Groovy 3+.
    if (e.count(":") > 1 || !(h ==~ /[A-Za-z0-9._-]+/) || !(p ==~ /\d{1,5}/) || !((p as Integer) in 1..65535)) {
        System.err.println("Skipping invalid endpoint '${e}': expected host[:port]")
        return
    }

    def id = "${h}_${p}"
    if (!seen.add(id)) return      // tolerate duplicates in the property

    println "${id}##${h}:${p}##TLS endpoint ${h} on port ${p}####auto.tls.host=${h}&auto.tls.port=${p}"
}
return 0
