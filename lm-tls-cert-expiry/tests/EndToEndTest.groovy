import groovy.json.JsonSlurper
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

import java.nio.file.Files

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

/**
 * The real module and scripts through the simulated collector, against local
 * TLS servers: one valid for 90 days, one that expired about a year ago, and
 * a port with nothing listening.
 */
class EndToEndTest {
    static final File REPO = new File(".").canonicalFile
    static final File MODULE_FILE = new File(REPO, "module/TLS_Certificate_Expiry.json")

    static File tmp
    static TlsServer fresh
    static TlsServer expired
    static int closed

    @BeforeClass
    static void startServers() {
        tmp = Files.createTempDirectory("lmsim-test").toFile()
        fresh = new TlsServer(TlsServer.keystore(tmp, "fresh", 90))
        expired = new TlsServer(TlsServer.keystore(tmp, "expired", 30, "-400d"))
        closed = TlsServer.closedPort()
    }

    @AfterClass
    static void stopServers() {
        fresh?.close()
        expired?.close()
        tmp?.deleteDir()
    }

    static Map module() { new JsonSlurper().parse(MODULE_FILE) as Map }

    static Map run(Map module, Map<String, String> props, File moduleDir = MODULE_FILE.parentFile) {
        new Collector(module, moduleDir).runResource([displayName: "collector01", hostname: "collector01.lab", properties: props])
    }

    static Map<String, Double> values(Map instance) {
        instance.datapoints.collectEntries { [it.name, it.value] } as Map<String, Double>
    }

    @Test
    void monitorsEachEndpointAndAlertsOnlyWhereItShould() {
        String endpoints = "localhost:${fresh.port}, localhost:${expired.port}, localhost:${closed}, localhost:${fresh.port}"
        Map r = run(module(), ["tls.endpoints": endpoints])

        assertTrue(r.appliesTo.matched)
        assertEquals(0, r.discovery.exitCode)
        assertEquals("no discovery errors", [], r.discovery.errors)
        assertEquals("duplicate endpoint collapsed",
                ["localhost_${fresh.port}", "localhost_${expired.port}", "localhost_${closed}"]*.toString(),
                r.instances*.wildvalue)

        Map ok = r.instances[0]
        Map<String, Double> v = values(ok)
        assertEquals(1d, v.handshakeOk, 0d)
        assertTrue("90-day cert: ${v.daysUntilExpiry}".toString(), v.daysUntilExpiry >= 88d && v.daysUntilExpiry <= 90d)
        assertEquals(1d, v.chainLength, 0d)
        assertEquals("self-signed, so untrusted", 0d, v.chainTrusted, 0d)
        assertEquals(["chainTrusted:error"], ok.alerts.collect { "${it.datapoint}:${it.level}".toString() })

        Map old = r.instances[1]
        v = values(old)
        assertEquals("the trust-all handshake still reads an expired cert", 1d, v.handshakeOk, 0d)
        assertTrue("expired about 370 days ago: ${v.daysUntilExpiry}".toString(), v.daysUntilExpiry >= -371d && v.daysUntilExpiry <= -369d)
        assertEquals(["daysUntilExpiry:critical", "chainTrusted:error"], old.alerts.collect { "${it.datapoint}:${it.level}".toString() })
        assertTrue(old.alerts[0].subject.contains("expires in ${Alerts.formatValue(v.daysUntilExpiry)} days"))

        Map dead = r.instances[2]
        v = values(dead)
        assertEquals(0, dead.exitCode)
        assertEquals(0d, v.handshakeOk, 0d)
        ["daysUntilExpiry", "daysSinceIssued", "chainLength", "chainTrusted"].each {
            assertTrue("${it} should be NaN".toString(), Double.isNaN(v[it]))
        }
        assertEquals("one outage, one alert", ["handshakeOk:critical"], dead.alerts.collect { "${it.datapoint}:${it.level}".toString() })
        assertTrue(dead.alerts[0].subject.startsWith("TLS handshake failed on localhost:${closed}"))

        assertEquals("every alert token is recognised", [], r.instances*.alerts.flatten()*.unknownTokens.flatten())
    }

    @Test
    void doesNotApplyWithoutTheProperty() {
        [[:], ["tls.endpoints": ""], ["other": "x"]].each { Map props ->
            Map r = run(module(), props)
            assertFalse(props.toString(), r.appliesTo.matched)
            assertFalse(r.containsKey("discovery"))
        }
    }

    @Test
    void skipsEndpointsThatCannotBeInstances() {
        Map r = run(module(), ["tls.endpoints": "good.example, ::1, bad host, x:99999, y:abc"])
        assertEquals(["good.example_443"], r.discovery.instances*.wildvalue)
        assertEquals(4, r.discovery.stderr.readLines().count { it.startsWith("Skipping invalid endpoint") })
    }

    @Test
    void theOriginalHostPortWildvaluesWouldHaveCollectedNothing() {
        File dir = Files.createTempDirectory(tmp.toPath(), "old").toFile()
        new File(dir, "ad.groovy").text = 'println "localhost:' + fresh.port + '##localhost:' + fresh.port + '##d####auto.tls.host=localhost"'
        Map m = module() + [activeDiscovery: [script: "ad.groovy"],
                            collection     : [script: new File(REPO, "scripts/collection.groovy").path, timeoutSeconds: 60]]
        Map r = run(m, ["tls.endpoints": "x"], dir)
        assertTrue(r.discovery.errors[0].contains("contains ':'"))
        assertTrue(r.instances[0].noData.contains("NoData"))
        assertTrue(values(r.instances[0]).values().every { Double.isNaN(it) })
    }

    @Test
    void failedCollectionMeansNoDataAndNoAlerts() {
        File dir = Files.createTempDirectory(tmp.toPath(), "fail").toFile()
        new File(dir, "ad.groovy").text = 'println "i1##i1"'
        new File(dir, "exit1.groovy").text = 'println "handshakeOk=0"; return 1'
        new File(dir, "slow.groovy").text = 'Thread.sleep(5000); println "handshakeOk=0"'
        [["exit1.groovy", 60, "script exited 1"], ["slow.groovy", 1, "script timed out"]].each { String script, int timeout, String reason ->
            Map m = module() + [activeDiscovery: [script: "ad.groovy"], collection: [script: script, timeoutSeconds: timeout]]
            Map inst = run(m, ["tls.endpoints": "x"], dir).instances[0]
            assertEquals(script, reason, inst.noData)
            assertEquals("NaN never alerts, so ${script} is silent".toString(), [], inst.alerts)
        }
    }

    @Test
    void discoveryFailureKeepsInstancesAndEmptySuccessRemovesThem() {
        File dir = Files.createTempDirectory(tmp.toPath(), "ad").toFile()
        new File(dir, "fail.groovy").text = 'return 2'
        new File(dir, "empty.groovy").text = 'return 0'
        Map fail = run(module() + [activeDiscovery: [script: "fail.groovy"]], ["tls.endpoints": "x"], dir)
        assertTrue(fail.discovery.outcome.contains("kept unchanged"))
        Map empty = run(module() + [activeDiscovery: [script: "empty.groovy"]], ["tls.endpoints": "x"], dir)
        assertTrue(empty.discovery.outcome.contains("removed"))
    }
}
