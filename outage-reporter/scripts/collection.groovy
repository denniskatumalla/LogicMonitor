/*
 * Outage_Reporter_Health  |  collection script
 *
 * Polls the Outage Reporter service's /health endpoint and prints its fields
 * as key=value datapoints: the technical ones (latency, errors, store) and
 * the business ones (reports, open outages, customers affected, triage
 * backlog). A 503 is a normal answer here (status DOWN, with a JSON body),
 * not a failure to collect. Only "no usable answer at all" is a failure, and
 * it is reported as reachable=0 rather than a non-zero exit.
 *
 * Runs on the LogicMonitor Collector every 60 s as embedded Groovy. Each
 * key=value line becomes a datapoint through the keyValue post-processor;
 * thresholds live in module/Outage_Reporter_Health.json, not here.
 */
import groovy.json.JsonSlurper

// On the collector, read the target from resource properties. Locally there
// are no hostProps, so take host:port from an argument. binding.hasVariable,
// because a bare reference to an unbound variable throws.
String host
Integer port
if (binding.hasVariable("hostProps") && hostProps.get("outage.port")) {
    host = hostProps.get("outage.host") ?: hostProps.get("system.hostname")
    port = hostProps.get("outage.port") as Integer
} else {
    def cli = binding.hasVariable("args") ? args : null
    def bits = ((cli && cli.length > 0) ? cli[0] : "127.0.0.1:8080").tokenize(":")
    host = bits[0]
    port = (bits.size() > 1 ? bits[1] : "8080") as Integer
}

// 5 s connect + 5 s read: worst case 10 s, well inside the 60 s script limit.
int timeoutMs = 5000

// Text fields as numbers so LogicMonitor can graph and threshold them. Same
// codes as Health.Status.code() and Chaos.Mode.code() in the service.
def statusCodes = [UP: 0, DEGRADED: 1, DOWN: 2]
def chaosCodes = [off: 0, latency: 1, errors: 2, store: 3, storm: 4]
// Printed as-is when present; a missing field is left out and goes NaN.
def numeric = ["uptimeSeconds", "requestsTotal", "errorsTotal", "clientErrorsTotal", "p95LatencyMs", "errorRatePct",
               "reportsLastMinute", "failedReportsLastMinute", "openOutages", "customersAffected",
               "awaitingTriage", "oldestOpenMinutes", "overdueOutages"]

// Any failure below (refused, timeout, empty or non-JSON body, wrong document)
// ends up in the catch block.
HttpURLConnection conn = null
try {
    long t0 = System.nanoTime()
    conn = (HttpURLConnection) new URL("http://${host}:${port}/health").openConnection()
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("User-Agent", "LogicMonitor-Outage_Reporter_Health")

// responseCode does not throw on 4xx/5xx. An error response's body comes from
// errorStream, which is how the JSON body of a 503 (DOWN) is still read.
    int code = conn.responseCode
    InputStream body = code >= 400 ? conn.errorStream : conn.inputStream
    String text = body ? body.getText("UTF-8") : ""
    long elapsedMs = ((System.nanoTime() - t0) / 1000000L) as long

// It must be a health document with a known status. Anything else (a proxy
// error page, some other service on the port) counts as unreachable, not data.
    def h = new JsonSlurper().parseText(text)
    if (!(h instanceof Map) || !statusCodes.containsKey(h.status)) {
        throw new IllegalStateException("HTTP ${code} from ${host}:${port}/health is not an Outage Reporter health document: ${text.take(200)}")
    }

// The collector's own view first (reachable, HTTP status, round trip), then
// the service's fields. Booleans are printed as 1/0.
    println "reachable=1"
    println "httpStatus=${code}"
    println "responseTimeMs=${elapsedMs}"
    println "status=${statusCodes[h.status]}"
    numeric.each { key ->
        if (h[key] instanceof Number) println "${key}=${h[key]}"
    }
    println "storeOk=${h.storeOk ? 1 : 0}"
    if (h.containsKey("stormMode")) println "stormMode=${h.stormMode ? 1 : 0}"
    if (chaosCodes.containsKey(h.chaosMode)) println "chaosMode=${chaosCodes[h.chaosMode]}"
    return 0

} catch (Exception e) {
    // Return 0, not 1: a non-zero exit is "no data", NaN never alerts, and
    // a stopped service would show as silence. reachable=0 alerts. The other
    // keys are left out so they go NaN for this poll and raise nothing else.
    println "reachable=0"
    System.err.println("Outage Reporter health check against ${host}:${port} failed: ${e}")
    return 0

} finally {
    conn?.disconnect()
}
