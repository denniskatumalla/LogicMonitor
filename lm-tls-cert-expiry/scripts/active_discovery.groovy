/*
 * TLS_Certificate_Expiry-  |  active discovery
 *
 * One instance per endpoint listed in the resource property tls.endpoints.
 * Output contract:  id##name##description####auto.key=value&auto.key=value
 */

def raw = "##TLS.ENDPOINTS##"
if (raw.startsWith("##")) {
    def cli = binding.hasVariable("args") ? args : null
    raw = (cli && cli.length > 0) ? cli[0] : "www.logicmonitor.com:443,expired.badssl.com:443"
}
if (!raw?.trim()) return 0          // property present but empty: no instances, not an error

def seen = [] as Set
raw.split(",").each { entry ->
    def e = entry.trim()
    if (!e) return

    def bits = e.tokenize(":")
    def h = bits[0]
    def p = (bits.size() > 1 ? bits[1] : "443")
    def wild = "${h}:${p}"

    if (!seen.add(wild)) return      // tolerate duplicates in the property

    println "${wild}##${wild}##TLS endpoint ${h} on port ${p}####auto.tls.host=${h}&auto.tls.port=${p}"
}
return 0
