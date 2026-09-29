import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNull
import static org.junit.Assert.assertTrue

class ParsersTest {
    @Test
    void parsesAWellFormedDiscoveryLine() {
        DiscoveryParse p = Parsers.parseDiscovery(
                "a.com_443##a.com:443##TLS endpoint a.com on port 443####auto.tls.host=a.com&auto.tls.port=443\n")
        assertEquals([], p.errors)
        assertEquals(1, p.instances.size())
        DiscoveredInstance i = p.instances[0]
        assertEquals("a.com_443", i.wildvalue)
        assertEquals("a.com:443", i.wildalias)
        assertEquals("TLS endpoint a.com on port 443", i.description)
        assertEquals([("auto.tls.host"): "a.com", ("auto.tls.port"): "443"], i.props)
        assertNull(i.invalidReason)
    }

    @Test
    void flagsForbiddenWildvalueCharacters() {
        // The module's original output: host:port as the wildvalue.
        List<List> cases = [
                ["a.com:443##a.com:443##d####auto.x=1", "':'"],
                ["a=b##n",                              "'='"],
                ["a\\b##n",                             "'\\'"],
                ["a b##n",                              "space"],
        ]
        cases.each { String line, String named ->
            DiscoveryParse p = Parsers.parseDiscovery(line)
            assertEquals(line, 1, p.instances.size())
            assertTrue("${line} -> ${p.instances[0].invalidReason}".toString(), p.instances[0].invalidReason?.contains(named))
            assertEquals(line, 1, p.errors.size())
        }
    }

    @Test
    void reportsMalformedLines() {
        List<List> cases = [
                // output                      error fragment
                ["just-an-id",                 "expected id##name"],
                ["##name",                     "empty wildvalue"],
                ["a##n\na##n2",                "duplicate wildvalue"],
                ["a##n####auto.x",             "not key=value"],
                ["a##" + "x" * 256,            "limit is 255"],
        ]
        cases.each { String out, String fragment ->
            DiscoveryParse p = Parsers.parseDiscovery(out)
            assertTrue("${out.take(40)} -> ${p.errors}".toString(), p.errors.any { it.contains(fragment) })
        }
    }

    @Test
    void warnsOnPropertiesWithoutAutoPrefix() {
        DiscoveryParse p = Parsers.parseDiscovery("a##n####tls.host=x")
        assertEquals([], p.errors)
        assertTrue(p.warnings[0].contains("no auto. prefix"))
    }

    @Test
    void readsKeyValueDatapoints() {
        Map<String, String> kv = Parsers.parseKeyValue("handshakeOk=1\n daysUntilExpiry = -3 \nnoise line\nchainLength=abc\n")
        assertEquals(1d, Parsers.valueFor(kv, "handshakeOk"), 0d)
        assertEquals(-3d, Parsers.valueFor(kv, "daysUntilExpiry"), 0d)
        assertTrue("non-numeric is NaN", Double.isNaN(Parsers.valueFor(kv, "chainLength")))
        assertTrue("missing is NaN", Double.isNaN(Parsers.valueFor(kv, "chainTrusted")))
    }
}
