package outage;

import java.util.LinkedHashMap;
import java.util.Map;

import static outage.Assert.eq;
import static outage.Assert.fails;
import static outage.Assert.ok;

class ValidationTest {

    @TestRunner.Test
    void acceptsAZipAloneOrAnAddressEndingInOne() {
        eq(new Report("00012", "", "", ""), Report.from(Map.of("zip", "00012")));
        eq("ZIP+4 is cut to five digits", "00012", Report.from(Map.of("zip", "00012-3456")).zip());
        Report r = Report.from(Map.of("address", "  12   Bay St,\tEdwin AI District 00014 "));
        eq("00014", r.zip());
        eq("whitespace and control characters collapse", "12 Bay St, Edwin AI District 00014", r.address());
        eq("the ZIP field wins over the address", "00031",
                Report.from(Map.of("zip", "00031", "address", "1 Main St 00014")).zip());
        eq(Territory.TEST_ZIP, Report.from(Map.of("zip", Territory.TEST_ZIP)).zip());
    }

    @TestRunner.Test
    void normalisesPhoneNumbersAndNotes() {
        for (String phone : new String[]{"555-010-0123", "(555) 010-0123", "+1 555 010 0123", "15550100123", "555.010.0123"}) {
            eq(phone, "5550100123", Report.from(Map.of("zip", "00012", "phone", phone)).phone());
        }
        eq("line one line two", Report.from(Map.of("zip", "00012", "notes", "line one\r\nline\ttwo")).notes());
        eq("•••-•••-0123", Report.maskPhone("5550100123"));
        eq(null, Report.maskPhone(""));
    }

    @TestRunner.Test
    void rejectsWithAFieldAndACustomerMessage() {
        Map<Map<String, ?>, String[]> cases = new LinkedHashMap<>();
        cases.put(Map.of(), new String[]{"zip", "Enter the ZIP code where the power is out."});
        cases.put(Map.of("address", "12 Bay St"), new String[]{"zip", "Enter the ZIP code where the power is out."});
        cases.put(Map.of("zip", "12"), new String[]{"zip", "Enter a 5-digit ZIP code, for example 00012."});
        cases.put(Map.of("zip", "70112"), new String[]{"zip", "ZIP 70112 isn't in our service area. We serve ZIP codes 00010 to 00069."});
        cases.put(Map.of("zip", 12.0), new String[]{"zip", "ZIP code must be text."});
        cases.put(Map.of("zip", "00012", "phone", "555-0100"), new String[]{"phone", "Enter a 10-digit phone number, or leave it blank."});
        cases.put(Map.of("zip", "00012", "phone", "call me"), new String[]{"phone", "Enter a 10-digit phone number, or leave it blank."});
        cases.put(Map.of("zip", "00012", "notes", "x".repeat(501)), new String[]{"notes", "Details must be 500 characters or fewer."});
        cases.put(Map.of("zip", "00012", "address", "x".repeat(121)), new String[]{"address", "Street address must be 120 characters or fewer."});
        cases.forEach((in, want) -> {
            Report.Invalid e = fails(Report.Invalid.class, () -> Report.from(in));
            eq(in + " field", want[0], e.field());
            eq(in + " message", want[1], e.getMessage());
        });
    }

    @TestRunner.Test
    void parsesQueryStrings() {
        eq(Map.of("mode", "latency", "x", "a b", "flag", ""), OutageServer.query("mode=latency&x=a+b&flag"));
        eq("form posts", Map.of("zip", "00012", "notes", "pole & wire"), OutageServer.query("zip=00012&&notes=pole+%26+wire"));
        eq(Map.of(), OutageServer.query(null));
    }

    @TestRunner.Test
    void chaosModesParseCaseInsensitively() {
        eq(Chaos.Mode.LATENCY, Chaos.Mode.parse("Latency"));
        eq(Chaos.Mode.STORM, Chaos.Mode.parse("STORM"));
        eq(Chaos.Mode.OFF, Chaos.Mode.parse(" off "));
        ok("names the options", fails(IllegalArgumentException.class, () -> Chaos.Mode.parse("slow"))
                .getMessage().contains("off|latency|errors|store|storm"));
        fails(IllegalArgumentException.class, () -> Chaos.Mode.parse(null));
        eq(0, Chaos.Mode.OFF.code());
        eq(3, Chaos.Mode.STORE.code());
        eq(4, Chaos.Mode.STORM.code());
    }

    @TestRunner.Test
    void configReadsEnvironmentWithDefaults() {
        Config c = Config.fromEnv(Map.of("OUTAGE_PORT", " 9000 ", "OUTAGE_ADMIN_TOKEN", "s3cret"));
        eq(9000, c.port());
        eq("127.0.0.1", c.adminBind());
        ok("admin enabled by token", c.adminEnabled());
        ok("tls off without keystore", !c.tlsEnabled());
        ok("admin off without token", !Config.fromEnv(Map.of()).adminEnabled());
        fails(IllegalArgumentException.class, () -> Config.fromEnv(Map.of("OUTAGE_PORT", "eighty")));
        Config d = Config.fromEnv(Map.of());
        eq("restore minutes", 8, d.restoreMinutes());
        eq("triage per minute", 180, d.triagePerMinute());
        eq("storm peak per second", 6, d.stormReportsPerSecond());
        eq("storm threshold per minute", 60, d.stormThresholdPerMinute());
    }
}
