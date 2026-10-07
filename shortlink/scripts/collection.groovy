/*
 * Shortlink_Health  |  collection script
 *
 * Polls the Shortlink service's /health endpoint and prints its fields as
 * key=value datapoints. A 503 is a normal answer here (status DOWN, with a
 * JSON body), not a failure to collect. Only "no usable answer at all" is a
 * failure, and it is reported as reachable=0 rather than a non-zero exit.
 */
import groovy.json.JsonSlurper

// On the collector, read the target from resource properties. Locally there
// are no hostProps, so take host:port from an argument. binding.hasVariable,
// because a bare reference to an unbound variable throws.
String host
Integer port
if (binding.hasVariable("hostProps") && hostProps.get("shortlink.port")) {
    host = hostProps.get("shortlink.host") ?: hostProps.get("system.hostname")
    port = hostProps.get("shortlink.port") as Integer
} else {
    def cli = binding.hasVariable("args") ? args : null
    def bits = ((cli && cli.length > 0) ? cli[0] : "127.0.0.1:8080").tokenize(":")
    host = bits[0]
    port = (bits.size() > 1 ? bits[1] : "8080") as Integer
}

// 5 s connect + 5 s read: worst case 10 s, well inside the 60 s script limit.
int timeoutMs = 5000

def statusCodes = [UP: 0, DEGRADED: 1, DOWN: 2]
def chaosCodes = [off: 0, latency: 1, errors: 2, store: 3]

HttpURLConnection conn = null
try {
    long t0 = System.nanoTime()
    conn = (HttpURLConnection) new URL("http://${host}:${port}/health").openConnection()
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("User-Agent", "LogicMonitor-Shortlink_Health")

    int code = conn.responseCode
    InputStream body = code >= 400 ? conn.errorStream : conn.inputStream
    String text = body ? body.getText("UTF-8") : ""
    long elapsedMs = ((System.nanoTime() - t0) / 1000000L) as long

    def h = new JsonSlurper().parseText(text)
    if (!(h instanceof Map) || !statusCodes.containsKey(h.status)) {
        throw new IllegalStateException("HTTP ${code} from ${host}:${port}/health is not a Shortlink health document: ${text.take(200)}")
    }

    println "reachable=1"
    println "httpStatus=${code}"
    println "responseTimeMs=${elapsedMs}"
    println "status=${statusCodes[h.status]}"
    println "uptimeSeconds=${h.uptimeSeconds}"
    println "links=${h.links}"
    println "requestsTotal=${h.requestsTotal}"
    println "errorsTotal=${h.errorsTotal}"
    println "clientErrorsTotal=${h.clientErrorsTotal}"
    println "p95LatencyMs=${h.p95LatencyMs}"
    println "errorRatePct=${h.errorRatePct}"
    println "storeOk=${h.storeOk ? 1 : 0}"
    if (chaosCodes.containsKey(h.chaosMode)) println "chaosMode=${chaosCodes[h.chaosMode]}"
    return 0

} catch (Exception e) {
    // Return 0, not 1: a non-zero exit is "no data", NaN never alerts, and
    // a stopped service would show as silence. reachable=0 alerts. The other
    // keys are left out so they go NaN for this poll and raise nothing else.
    println "reachable=0"
    System.err.println("Shortlink health check against ${host}:${port} failed: ${e}")
    return 0

} finally {
    conn?.disconnect()
}
