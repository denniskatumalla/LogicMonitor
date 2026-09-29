import org.junit.Test

import static org.junit.Assert.assertEquals

class AlertsTest {
    static final Threshold EXPIRY = new Threshold(op: "<", warning: 30d, error: 14d, critical: 7d)
    static final Threshold MUST_BE_ONE = new Threshold(op: "!=", critical: 1d)

    @Test
    void picksTheHighestSeverityCrossed() {
        List<List> cases = [
                // threshold    value       expected
                [EXPIRY,        45d,        null],
                [EXPIRY,        30d,        null],
                [EXPIRY,        29d,        "warning"],
                [EXPIRY,        13d,        "error"],
                [EXPIRY,        6d,         "critical"],
                [EXPIRY,        -4000d,     "critical"],
                [EXPIRY,        Double.NaN, null],
                [MUST_BE_ONE,   1d,         null],
                [MUST_BE_ONE,   0d,         "critical"],
                [MUST_BE_ONE,   Double.NaN, null],
                [null,          0d,         null],
        ]
        cases.each { Threshold t, double v, String expected ->
            assertEquals("${t} with ${v}".toString(), expected, Alerts.level(t, v))
        }
    }

    @Test
    void rendersTokensAndReportsUnknownOnes() {
        List r = Alerts.render("##INSTANCE## is ##VALUE## (##LEVEL##) ##TYPO##",
                [INSTANCE: "a.com:443", VALUE: "-3", LEVEL: "critical"])
        assertEquals("a.com:443 is -3 (critical) ##TYPO##", r[0])
        assertEquals(["TYPO"], r[1])
    }

    @Test
    void formatsValuesLikeTheUi() {
        assertEquals("46", Alerts.formatValue(46d))
        assertEquals("-4187", Alerts.formatValue(-4187d))
        assertEquals("0.5", Alerts.formatValue(0.5d))
        assertEquals("NaN", Alerts.formatValue(Double.NaN))
        assertEquals("< 30 14 7", EXPIRY.toString())
        assertEquals("!= - - 1", MUST_BE_ONE.toString())
    }
}
