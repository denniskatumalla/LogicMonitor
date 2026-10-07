package shortlink;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static shortlink.Assert.eq;
import static shortlink.Assert.ok;

/** Starts real servers on random ports and drives them over HTTP(S) and JMX. */
class EndToEndTest {

    static final String TOKEN = "test-token";
    static Path tmp;
    static Path keystore;
    static HttpClient client;

    static void beforeAll() throws Exception {
        tmp = LinkStoreTest.tempDir();
        keystore = tmp.resolve("tls.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "shortlink", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("keytool failed: " + out);

        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).sslContext(trustAll).build();
    }

    static void afterAll() throws IOException {
        LinkStoreTest.delete(tmp);
    }

    /** Fast chaos and thresholds so the suite stays quick: 80 ms injected latency vs a 40 ms limit. */
    static ShortlinkServer start(String dataDir, String... overrides) throws Exception {
        Map<String, String> env = new HashMap<>(Map.of(
                "SHORTLINK_BIND", "127.0.0.1", "SHORTLINK_PORT", "0",
                "SHORTLINK_ADMIN_PORT", "0", "SHORTLINK_ADMIN_TOKEN", TOKEN,
                "SHORTLINK_TLS_PORT", "0", "SHORTLINK_TLS_KEYSTORE", keystore.toString(), "SHORTLINK_TLS_PASSWORD", "changeit",
                "SHORTLINK_DATA_DIR", tmp.resolve(dataDir).toString(),
                "SHORTLINK_CHAOS_LATENCY_MS", "80", "SHORTLINK_DEGRADED_P95_MS", "40"));
        env.put("SHORTLINK_CHAOS_ERROR_PERCENT", "100");
        for (int i = 0; i + 1 < overrides.length; i += 2) env.put(overrides[i], overrides[i + 1]);
        return ShortlinkServer.start(Config.fromEnv(env));
    }

    static HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<?, ?> json(HttpResponse<String> r) {
        return (Map<?, ?>) Json.parse(r.body());
    }

    static String base(ShortlinkServer s) {
        return "http://127.0.0.1:" + s.port();
    }

    static Map<?, ?> health(ShortlinkServer s) throws Exception {
        return json(get(base(s) + "/health"));
    }

    static void chaos(ShortlinkServer s, String mode) throws Exception {
        HttpResponse<String> r = post("http://127.0.0.1:" + s.adminPort() + "/admin/chaos?mode=" + mode, "",
                "X-Admin-Token", TOKEN);
        eq(r.body(), 200, r.statusCode());
    }

    static String createLink(ShortlinkServer s, String url) throws Exception {
        HttpResponse<String> r = post(base(s) + "/api/links", "{\"url\":\"" + url + "\"}");
        eq(r.body(), 201, r.statusCode());
        return (String) json(r).get("code");
    }

    @TestRunner.Test
    void createRedirectAndStats() throws Exception {
        try (ShortlinkServer s = start("crud")) {
            HttpResponse<String> created = post(base(s) + "/api/links", "{\"url\": \"https://www.logicmonitor.com/\"}");
            eq(201, created.statusCode());
            Map<?, ?> link = json(created);
            String code = (String) link.get("code");
            eq(base(s) + "/" + code, link.get("shortUrl"));
            eq(link.get("shortUrl"), created.headers().firstValue("Location").orElse(null));

            HttpResponse<String> redirect = get(base(s) + "/" + code);
            eq(302, redirect.statusCode());
            eq("https://www.logicmonitor.com/", redirect.headers().firstValue("Location").orElse(null));
            get(base(s) + "/" + code);

            Map<?, ?> stats = json(get(base(s) + "/api/links/" + code));
            eq(2L, ((Double) stats.get("hits")).longValue());
            eq("https://www.logicmonitor.com/", stats.get("url"));
        }
    }

    @TestRunner.Test
    void rejectsBadInputWithClientErrors() throws Exception {
        try (ShortlinkServer s = start("bad")) {
            String api = base(s) + "/api/links";
            eq("invalid json", 400, post(api, "{url:").statusCode());
            eq("no url field", 400, post(api, "{\"link\":\"https://a.com\"}").statusCode());
            eq("url not a string", 400, post(api, "{\"url\":42}").statusCode());
            HttpResponse<String> ftp = post(api, "{\"url\":\"ftp://a.com\"}");
            eq(400, ftp.statusCode());
            eq("url must start with http:// or https://", json(ftp).get("error"));
            eq("body too large", 413, post(api, "{\"url\":\"https://a.com/" + "x".repeat(9000) + "\"}").statusCode());
            eq("GET on collection", 405, get(api).statusCode());
            eq("unknown code", 404, get(base(s) + "/zzzzzzz").statusCode());
            eq("not a code", 404, get(base(s) + "/favicon.ico").statusCode());
            eq("unknown stats", 404, get(api + "/zzzzzzz").statusCode());

            Map<?, ?> h = health(s);
            eq("4xx are not service errors", 0.0, h.get("errorsTotal"));
            eq(9.0, h.get("clientErrorsTotal"));
            eq("UP", h.get("status"));
        }
    }

    @TestRunner.Test
    void healthHasTheContractFieldsAndIgnoresItsOwnPolls() throws Exception {
        try (ShortlinkServer s = start("health")) {
            get(base(s) + "/");
            HttpResponse<String> r = get(base(s) + "/health");
            eq(200, r.statusCode());
            eq("no-store", r.headers().firstValue("Cache-Control").orElse(null));
            Map<?, ?> h = json(r);
            for (String key : List.of("status", "uptimeSeconds", "links", "requestsTotal", "errorsTotal",
                    "clientErrorsTotal", "p95LatencyMs", "errorRatePct", "storeOk", "chaosMode", "windowSeconds")) {
                ok("health has " + key, h.containsKey(key));
            }
            for (int i = 0; i < 3; i++) get(base(s) + "/health");
            eq("only the GET / counts", 1.0, health(s).get("requestsTotal"));
            eq("off", h.get("chaosMode"));
            eq(true, h.get("storeOk"));
        }
    }

    @TestRunner.Test
    void adminNeedsTheTokenAndIsNotOnThePublicPort() throws Exception {
        try (ShortlinkServer s = start("admin")) {
            String admin = "http://127.0.0.1:" + s.adminPort() + "/admin/chaos?mode=errors";
            eq("no token", 401, post(admin, "").statusCode());
            eq("wrong token", 401, post(admin, "", "X-Admin-Token", "guess").statusCode());
            eq("bad mode", 400, post("http://127.0.0.1:" + s.adminPort() + "/admin/chaos?mode=slow", "",
                    "X-Admin-Token", TOKEN).statusCode());
            eq("not on the public listener", 404, post(base(s) + "/admin/chaos?mode=errors", "",
                    "X-Admin-Token", TOKEN).statusCode());
            eq("off", health(s).get("chaosMode"));
        }
        try (ShortlinkServer s = start("noadmin", "SHORTLINK_ADMIN_TOKEN", "")) {
            eq("no token configured: no admin listener", -1, s.adminPort());
        }
    }

    @TestRunner.Test
    void latencyChaosDegradesAndRecoversWhenTheWindowRollsOff() throws Exception {
        try (ShortlinkServer s = start("latency", "SHORTLINK_WINDOW_SECONDS", "1")) {
            String code = createLink(s, "https://example.com/");
            chaos(s, "latency");
            for (int i = 0; i < 5; i++) eq(302, get(base(s) + "/" + code).statusCode());
            Map<?, ?> h = health(s);
            eq("DEGRADED", h.get("status"));
            ok("p95 includes the injected 80 ms: " + h.get("p95LatencyMs"), (Double) h.get("p95LatencyMs") >= 80);
            eq("latency", h.get("chaosMode"));
            eq("DEGRADED is still 200", 200, get(base(s) + "/health").statusCode());

            chaos(s, "off");
            Thread.sleep(1100);
            eq(302, get(base(s) + "/" + code).statusCode());
            eq("UP", health(s).get("status"));
        }
    }

    @TestRunner.Test
    void errorChaosFailsRequestsAndRaisesTheErrorRate() throws Exception {
        try (ShortlinkServer s = start("errors")) {
            chaos(s, "errors");
            for (int i = 0; i < Health.MIN_REQUESTS_FOR_ERROR_RATE; i++) eq(500, get(base(s) + "/").statusCode());
            Map<?, ?> h = health(s);
            eq("DEGRADED", h.get("status"));
            eq((double) Health.MIN_REQUESTS_FOR_ERROR_RATE, h.get("errorsTotal"));
            eq(100.0, h.get("errorRatePct"));
        }
    }

    @TestRunner.Test
    void storeChaosIsDown503ButExistingLinksStillRedirect() throws Exception {
        try (ShortlinkServer s = start("store")) {
            String code = createLink(s, "https://example.com/");
            chaos(s, "store");
            HttpResponse<String> h = get(base(s) + "/health");
            eq(503, h.statusCode());
            eq("DOWN", json(h).get("status"));
            eq(false, json(h).get("storeOk"));
            eq("cannot create", 503, post(base(s) + "/api/links", "{\"url\":\"https://b.com\"}").statusCode());
            eq("can still redirect", 302, get(base(s) + "/" + code).statusCode());

            chaos(s, "off");
            eq(200, get(base(s) + "/health").statusCode());
            createLink(s, "https://b.com/");
            eq(2.0, health(s).get("links"));
        }
    }

    @TestRunner.Test
    void jmxBeanReportsTheSameSnapshot() throws Exception {
        try (ShortlinkServer s = start("jmx")) {
            createLink(s, "https://example.com/");
            get(base(s) + "/nope123");
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("shortlink:type=Stats");
            eq(2L, mbs.getAttribute(name, "RequestsTotal"));
            eq(1L, mbs.getAttribute(name, "ClientErrorsTotal"));
            eq(1, mbs.getAttribute(name, "Links"));
            eq(0, mbs.getAttribute(name, "StatusCode"));
            eq(1, mbs.getAttribute(name, "StoreOk"));
            chaos(s, "store");
            eq(2, mbs.getAttribute(name, "StatusCode"));
            eq(0, mbs.getAttribute(name, "StoreOk"));
            eq(3, mbs.getAttribute(name, "ChaosModeCode"));
            eq("store", mbs.getAttribute(name, "ChaosMode"));
        }
        ok("unregistered on close",
                !ManagementFactory.getPlatformMBeanServer().isRegistered(new ObjectName("shortlink:type=Stats")));
    }

    @TestRunner.Test
    void httpsListenerServesTheSameApi() throws Exception {
        try (ShortlinkServer s = start("tls")) {
            String code = createLink(s, "https://example.com/");
            HttpResponse<String> r = get("https://localhost:" + s.tlsPort() + "/api/links/" + code);
            eq(200, r.statusCode());
            eq("https://localhost:" + s.tlsPort() + "/" + code, json(r).get("shortUrl"));
            X509Certificate leaf = (X509Certificate) r.sslSession().orElseThrow().getPeerCertificates()[0];
            ok(leaf.getSubjectX500Principal().getName(), leaf.getSubjectX500Principal().getName().equals("CN=localhost"));
        }
    }

    @TestRunner.Test
    void linksSurviveARestart() throws Exception {
        String code;
        try (ShortlinkServer s = start("restart")) {
            code = createLink(s, "https://example.com/kept");
            get(base(s) + "/" + code);
        }
        try (ShortlinkServer s = start("restart")) {
            eq(1.0, health(s).get("links"));
            Map<?, ?> stats = json(get(base(s) + "/api/links/" + code));
            eq("https://example.com/kept", stats.get("url"));
            eq(1.0, stats.get("hits"));
            eq("counters restart from zero", 1.0, health(s).get("requestsTotal"));
        }
    }
}
