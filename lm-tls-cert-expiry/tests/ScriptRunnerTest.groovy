import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class ScriptRunnerTest {
    @Test
    void substitutesKnownTokensOnly() {
        String out = ScriptRunner.substitute('a="##WILDVALUE##"; b="##tls.endpoints##"; c="##UNSET##"; d="x##y####z"',
                [WILDVALUE: "h_443", "TLS.ENDPOINTS": "h:443"])
        assertEquals('a="h_443"; b="h:443"; c="##UNSET##"; d="x##y####z"', out)
    }

    @Test
    void capturesOutputAndExitCode() {
        List<List> cases = [
                // script                                             exit  stdout      stderr fragment
                ['println "k=1"; return 0',                            0,    "k=1\n",    ""],
                ['println "k=1"',                                      0,    "k=1\n",    ""],
                ['System.out.println("k=2"); return 3',                3,    "k=2\n",    ""],
                ['System.err.println("oops"); return 1',               1,    "",         "oops"],
                ['throw new IllegalStateException("boom")',            1,    "",         "boom"],
                ['println args',                                       1,    "",         "args"],
        ]
        cases.each { String src, int exit, String stdout, String err ->
            ScriptResult r = ScriptRunner.run(src, [:], 10)
            assertEquals(src, exit, r.exitCode)
            assertEquals(src, stdout, r.stdout)
            assertTrue("${src} stderr: ${r.stderr}".toString(), r.stderr.contains(err))
            assertFalse(src, r.timedOut)
        }
    }

    @Test
    void bindsPropsLikeTheCollector() {
        Map<String, String> host = Props.of(["tls.endpoints": "a:1"])
        ScriptResult r = ScriptRunner.run('println hostProps.get("TLS.Endpoints"); println binding.hasVariable("args")',
                [hostProps: host] as Map<String, Object>, 10)
        assertEquals("a:1\nfalse\n", r.stdout)
    }

    @Test
    void abandonsScriptsThatOverrunTheTimeout() {
        ScriptResult r = ScriptRunner.run('Thread.sleep(5000); println "late"', [:], 1)
        assertTrue(r.timedOut)
        assertEquals(1, r.exitCode)
        assertTrue(r.millis < 3000)
    }
}
