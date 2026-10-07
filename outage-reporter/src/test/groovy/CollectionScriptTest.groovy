import groovy.json.JsonSlurper
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import outage.Config
import outage.OutageServer

import java.nio.file.Files

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue

/**
 * Runs scripts/collection.groovy the way the collector does (hostProps
 * bound, no args) against a live Outage Reporter, then applies the thresholds
 * from module/Outage_Reporter_Health.json. The point: each fault raises exactly one
 * alert, on the datapoint that names the cause.
 */
class CollectionScriptTest {
    static final File REPO = new File(".").canonicalFile
    static final String TOKEN = "t"
    static final Map MODULE = new JsonSlurper().parse(new File(REPO, "module/Outage_Reporter_Health.json")) as Map
    static File tmp

    @BeforeClass
    static void setUp() { tmp = Files.createTempDirectory("outage-groovy").toFile() }

    @AfterClass
    static void tearDown() { tmp?.deleteDir() }

    static OutageServer start(String name, Map<String, String> extra = [:]) {
        Map<String, String> env = [OUTAGE_BIND: "127.0.0.1", OUTAGE_PORT: "0", OUTAGE_ADMIN_PORT: "0",
                                   OUTAGE_ADMIN_TOKEN: TOKEN, OUTAGE_DATA_DIR: new File(tmp, name).path,
                                   OUTAGE_CHAOS_ERROR_PERCENT: "100", OUTAGE_DISPATCH_INTERVAL_MS: "60000"] + extra
        OutageServer.start(Config.fromEnv(env))
    }

    /** Collector-style run: hostProps bound, stdout captured, return value is the exit code. */
    static Map collect(Map<String, String> hostProps) {
        def out = new StringWriter()
        def err = new ByteArrayOutputStream()
        def binding = new Binding(hostProps: hostProps, out: new PrintWriter(out))
        def oldErr = System.err
        System.err = new PrintStream(err, true, "UTF-8")
        def exit
        try {
            exit = new GroovyShell(binding).evaluate(new File(REPO, "scripts/collection.groovy"))
        } finally {
            System.err = oldErr
        }
        Map<String, Double> kv = [:]
        out.toString().eachLine { line ->
            int eq = line.indexOf("=")
            if (eq > 0) kv[line.substring(0, eq)] = line.substring(eq + 1) as Double
        }
        // Datapoints read their key; a key missing from stdout is NaN.
        Map<String, Double> dps = MODULE.datapoints.collectEntries { dp ->
            [dp.name, kv.containsKey(dp.key) ? kv[dp.key] : Double.NaN]
        }
        [exit: exit, stdout: out.toString(), stderr: err.toString("UTF-8"), datapoints: dps, alerts: alerts(dps)]
    }

    /** "datapoint:level" for every threshold crossed, highest severity per datapoint. NaN never alerts. */
    static List<String> alerts(Map<String, Double> dps) {
        List<String> found = []
        MODULE.datapoints.each { dp ->
            Map t = dp.threshold as Map
            double v = dps[dp.name]
            if (!t || Double.isNaN(v)) return
            String level = ["critical", "error", "warning"].find { lvl ->
                if (!t.containsKey(lvl)) return false
                double limit = t[lvl] as double
                switch (t.op) {
                    case ">": return v > limit
                    case "<": return v < limit
                    case "!=": return v != limit
                    default: throw new IllegalArgumentException("op ${t.op}")
                }
            }
            if (level) found << "${dp.name}:${level}".toString()
        }
        found
    }

    static Map props(OutageServer s) {
        ["system.hostname": "127.0.0.1", "outage.port": String.valueOf(s.port())]
    }

    static void chaos(OutageServer s, String mode) {
        def c = (HttpURLConnection) new URL("http://127.0.0.1:${s.adminPort()}/admin/chaos?mode=${mode}").openConnection()
        c.requestMethod = "POST"
        c.setRequestProperty("X-Admin-Token", TOKEN)
        assertEquals(200, c.responseCode)
    }

    static int hit(OutageServer s, String path) {
        def c = (HttpURLConnection) new URL("http://127.0.0.1:${s.port()}${path}").openConnection()
        c.instanceFollowRedirects = false
        c.responseCode
    }

    static int report(OutageServer s, String zip) {
        def c = (HttpURLConnection) new URL("http://127.0.0.1:${s.port()}/api/reports").openConnection()
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.withWriter("UTF-8") { it << "{\"zip\":\"${zip}\"}" }
        c.responseCode
    }

    static Map health(OutageServer s) {
        new JsonSlurper().parse(new URL("http://127.0.0.1:${s.port()}/health")) as Map
    }

    @Test
    void appliesToNeedsANumericPort() {
        def re = java.util.regex.Pattern.compile("^[0-9]+\$")
        assertTrue(MODULE.appliesTo.contains('"^[0-9]+$"'))
        assertTrue(re.matcher("8080").find())
        assertTrue(!re.matcher("").find() && !re.matcher("80a").find())
    }

    @Test
    void healthyServiceReportsEverythingAndRaisesNothing() {
        def s = start("healthy")
        try {
            assertEquals(201, report(s, "00012"))
            hit(s, "/api/reports/EPL-ZZZZZZ")
            Map r = collect(props(s))
            assertEquals(0, r.exit)
            Map d = r.datapoints
            assertEquals(1d, d.reachable, 0d)
            assertEquals(200d, d.httpStatus, 0d)
            assertEquals(0d, d.status, 0d)
            assertEquals(2d, d.requestRate, 0d)        // raw counter; the portal turns it into a rate
            assertEquals(1d, d.clientErrorRate, 0d)
            assertEquals(1d, d.storeOk, 0d)
            assertEquals(0d, d.chaosMode, 0d)
            assertEquals(1d, d.reportsLastMinute, 0d)
            assertEquals(1d, d.openOutages, 0d)
            assertEquals(1d, d.awaitingTriage, 0d)
            assertEquals(0d, d.stormMode, 0d)
            assertTrue("every datapoint has a value: ${d}", d.values().every { !it.isNaN() })
            assertEquals([], r.alerts)
        } finally {
            s.close()
        }
    }

    @Test
    void latencyChaosRaisesOneLatencyAlert() {
        def s = start("latency", [OUTAGE_CHAOS_LATENCY_MS: "1100"])
        try {
            chaos(s, "latency")
            def threads = (1..5).collect { Thread.start { hit(s, "/api/areas") } }
            threads*.join()
            Map r = collect(props(s))
            assertEquals(["p95LatencyMs:error"], r.alerts)
            assertEquals("DEGRADED", 1d, r.datapoints.status, 0d)
            assertEquals(1d, r.datapoints.chaosMode, 0d)
        } finally {
            s.close()
        }
    }

    @Test
    void errorChaosRaisesOneErrorRateAlert() {
        def s = start("errors")
        try {
            chaos(s, "errors")
            10.times { assertEquals(500, report(s, "00012")) }
            Map r = collect(props(s))
            assertEquals(["errorRatePct:error"], r.alerts)
            assertEquals(100d, r.datapoints.errorRatePct, 0d)
            assertEquals("the customers turned away, without a second alert", 10d, r.datapoints.failedReportsLastMinute, 0d)
        } finally {
            s.close()
        }
    }

    @Test
    void storeChaosIsA503ThatStillCollects() {
        def s = start("store")
        try {
            chaos(s, "store")
            Map r = collect(props(s))
            assertEquals(0, r.exit)
            assertEquals("a 503 with a health body is an answer, not an outage", 1d, r.datapoints.reachable, 0d)
            assertEquals(503d, r.datapoints.httpStatus, 0d)
            assertEquals(2d, r.datapoints.status, 0d)
            assertEquals(["storeOk:critical"], r.alerts)
            assertEquals(3d, r.datapoints.chaosMode, 0d)
        } finally {
            s.close()
        }
    }

    @Test
    void stormChaosRaisesOneCapacityAlertAndNoTechnicalOne() {
        def s = start("storm", [OUTAGE_STORM_REPORTS_PER_SECOND: "400", OUTAGE_STORM_RAMP_SECONDS: "0",
                                OUTAGE_TRIAGE_PER_MINUTE: "60"])
        try {
            chaos(s, "storm")
            for (int i = 0; i < 100 && (health(s).awaitingTriage as int) <= 150; i++) Thread.sleep(50)
            Map r = collect(props(s))
            assertEquals(["awaitingTriage:error"], r.alerts)
            assertEquals("busy is not broken: still UP", 0d, r.datapoints.status, 0d)
            assertEquals(0d, r.datapoints.errorRatePct, 0d)
            assertEquals(1d, r.datapoints.stormMode, 0d)
            assertEquals(4d, r.datapoints.chaosMode, 0d)
            assertTrue(r.datapoints.reportsLastMinute > 60d)
        } finally {
            s.close()
        }
    }

    @Test
    void stoppedServiceRaisesOnlyReachable() {
        def s = start("stopped")
        Map p = props(s)
        s.close()
        Map r = collect(p)
        assertEquals("exit 0 so the failure is data, not silence", 0, r.exit)
        assertEquals("reachable=0\n", r.stdout.replace("\r", ""))
        assertTrue(r.stderr, r.stderr.contains("failed"))
        assertEquals(["reachable:critical"], r.alerts)
        assertTrue(r.datapoints.findAll { k, v -> k != "reachable" }.values().every { it.isNaN() })
    }

    @Test
    void somethingElseOnThePortIsNotReachable() {
        def other = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/") { ex ->
            byte[] b = "<html>not outage reporter</html>".bytes
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.write(b)
            ex.close()
        }
        other.start()
        try {
            Map r = collect(["system.hostname": "127.0.0.1", "outage.port": String.valueOf(other.address.port)])
            assertEquals(["reachable:critical"], r.alerts)
        } finally {
            other.stop(0)
        }
    }

    @Test
    void outageHostOverridesSystemHostname() {
        def s = start("override")
        try {
            Map r = collect(["system.hostname": "does-not-resolve.invalid", "outage.host": "127.0.0.1",
                             "outage.port": String.valueOf(s.port())])
            assertEquals(1d, r.datapoints.reachable, 0d)
        } finally {
            s.close()
        }
    }
}
