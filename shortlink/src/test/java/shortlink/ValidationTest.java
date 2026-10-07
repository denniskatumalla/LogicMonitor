package shortlink;

import java.util.Map;

import static shortlink.Assert.eq;
import static shortlink.Assert.fails;
import static shortlink.Assert.ok;

class ValidationTest {

    @TestRunner.Test
    void acceptsHttpAndHttpsUrls() {
        for (String url : new String[]{"https://www.logicmonitor.com/", "http://10.0.0.5:8080/a/b?c=d#e",
                "HTTPS://Example.COM", "https://xn--bcher-kva.example/"}) {
            eq(url, null, ShortlinkServer.validateUrl(url));
        }
    }

    @TestRunner.Test
    void rejectsWithAReason() {
        Map<String, String> cases = Map.of(
                "", "url is empty",
                "ftp://example.com/x", "url must start with http:// or https://",
                "javascript:alert(1)", "url must start with http:// or https://",
                "example.com", "url must start with http:// or https://",
                "https://", "url is not a valid URI",
                "http:///path", "url has no host",
                "https://a.com/x y", "url contains whitespace or control characters",
                "https://a.com/\ttab", "url contains whitespace or control characters",
                "https://a.com/%%", "url is not a valid URI");
        cases.forEach((url, reason) -> eq(url, reason, ShortlinkServer.validateUrl(url)));
        String tooLong = "https://a.com/" + "x".repeat(ShortlinkServer.MAX_URL_LENGTH);
        ok("too long", ShortlinkServer.validateUrl(tooLong).startsWith("url longer than"));
    }

    @TestRunner.Test
    void parsesQueryStrings() {
        eq(Map.of("mode", "latency", "x", "a b", "flag", ""), ShortlinkServer.query("mode=latency&x=a+b&flag"));
        eq(Map.of(), ShortlinkServer.query(null));
    }

    @TestRunner.Test
    void chaosModesParseCaseInsensitively() {
        eq(Chaos.Mode.LATENCY, Chaos.Mode.parse("Latency"));
        eq(Chaos.Mode.OFF, Chaos.Mode.parse(" off "));
        ok("names the options", fails(IllegalArgumentException.class, () -> Chaos.Mode.parse("slow"))
                .getMessage().contains("off|latency|errors|store"));
        fails(IllegalArgumentException.class, () -> Chaos.Mode.parse(null));
        eq(0, Chaos.Mode.OFF.code());
        eq(3, Chaos.Mode.STORE.code());
    }

    @TestRunner.Test
    void configReadsEnvironmentWithDefaults() {
        Config c = Config.fromEnv(Map.of("SHORTLINK_PORT", " 9000 ", "SHORTLINK_ADMIN_TOKEN", "s3cret"));
        eq(9000, c.port());
        eq("127.0.0.1", c.adminBind());
        ok("admin enabled by token", c.adminEnabled());
        ok("tls off without keystore", !c.tlsEnabled());
        ok("admin off without token", !Config.fromEnv(Map.of()).adminEnabled());
        fails(IllegalArgumentException.class, () -> Config.fromEnv(Map.of("SHORTLINK_PORT", "eighty")));
    }
}
