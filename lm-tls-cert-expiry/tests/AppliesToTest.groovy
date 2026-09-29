import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertThrows

class AppliesToTest {
    static final Map<String, String> PROPS = [
            "tls.endpoints"    : "a.example.com:443",
            "system.categories": "Linux, Collector",
            "empty.prop"       : "",
            "env"              : "Prod",
    ]

    @Test
    void evaluatesExpressions() {
        List<List> cases = [
                // expression                                       expected  why
                ['tls.endpoints =~ ".+"',                           true,  "the module's own AppliesTo"],
                ['missing.prop =~ ".+"',                            false, "a missing property reads as empty"],
                ['empty.prop =~ ".+"',                              false, "an empty property does not match .+"],
                ['TLS.ENDPOINTS =~ "EXAMPLE"',                      true,  "names and regex are case-insensitive"],
                ['tls.endpoints =~ "example"',                      true,  "=~ matches anywhere in the value"],
                ['tls.endpoints !~ "example"',                      false, "!~ negates"],
                ['env == "prod"',                                   true,  "== is case-insensitive"],
                ['env != "prod"',                                   false, "!= negates"],
                ['tls.endpoints',                                   true,  "bare property: set and non-empty"],
                ['empty.prop',                                      false, "bare property: empty is false"],
                ['exists("empty.prop")',                            true,  "exists() is true even when empty"],
                ['exists("missing.prop")',                          false, "exists() on a missing property"],
                ['hasCategory("collector")',                        true,  "hasCategory() reads system.categories"],
                ['hasCategory("Windows")',                          false, "category not present"],
                ['env == "prod" && hasCategory("Linux")',           true,  "&&"],
                ['env == "dev" || hasCategory("Linux")',            true,  "||"],
                ['!(env == "dev") && !exists("missing.prop")',      true,  "! and parentheses"],
                ['env == "dev" || env == "prod" && false',          false, "&& binds tighter than ||"],
                ['true',                                            true,  "literal"],
        ]
        cases.each { String expr, boolean expected, String why ->
            assertEquals("${expr}  (${why})".toString(), expected, AppliesTo.matches(expr, PROPS))
        }
    }

    @Test
    void rejectsWhatItDoesNotUnderstand() {
        ['', 'env ==', 'env == prod', '(env == "prod"', 'env == "prod")', 'isLinux()', 'env > "a"'].each { String expr ->
            assertThrows("should reject: ${expr}".toString(), IllegalArgumentException) { AppliesTo.matches(expr, PROPS) }
        }
    }
}
