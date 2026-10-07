package outage;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static outage.Assert.eq;
import static outage.Assert.ok;

/** Starts real servers on random ports and drives them over HTTP(S) and JMX. */
class EndToEndTest {

    static final String TOKEN = "test-token";
    static Path tmp;
    static Path keystore;
    static HttpClient client;

    static void beforeAll() throws Exception {
        tmp = TicketStoreTest.tempDir();
        keystore = tmp.resolve("tls.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "outage", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
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
        TicketStoreTest.delete(tmp);
    }

    /**
     * Fast chaos and thresholds so the suite stays quick: 80 ms injected latency vs a 40 ms limit.
     * The dispatcher ticks every minute here unless a test asks, so ticket states hold still.
     */
    static OutageServer start(String dataDir, String... overrides) throws Exception {
        Map<String, String> env = new HashMap<>(Map.of(
                "OUTAGE_BIND", "127.0.0.1", "OUTAGE_PORT", "0",
                "OUTAGE_ADMIN_PORT", "0", "OUTAGE_ADMIN_TOKEN", TOKEN,
                "OUTAGE_TLS_PORT", "0", "OUTAGE_TLS_KEYSTORE", keystore.toString(), "OUTAGE_TLS_PASSWORD", "changeit",
                "OUTAGE_DATA_DIR", tmp.resolve(dataDir).toString(),
                "OUTAGE_CHAOS_LATENCY_MS", "80", "OUTAGE_DEGRADED_P95_MS", "40"));
        env.put("OUTAGE_CHAOS_ERROR_PERCENT", "100");
        env.put("OUTAGE_DISPATCH_INTERVAL_MS", "60000");
        for (int i = 0; i + 1 < overrides.length; i += 2) env.put(overrides[i], overrides[i + 1]);
        return OutageServer.start(Config.fromEnv(env));
    }

    static HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> postForm(OutageServer s, String form) throws Exception {
        return post(base(s) + "/report", form, "Content-Type", "application/x-www-form-urlencoded");
    }

    static Map<?, ?> json(HttpResponse<String> r) {
        return (Map<?, ?>) Json.parse(r.body());
    }

    static String base(OutageServer s) {
        return "http://127.0.0.1:" + s.port();
    }

    static Map<?, ?> health(OutageServer s) throws Exception {
        return json(get(base(s) + "/health"));
    }

    static double num(Map<?, ?> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    static void chaos(OutageServer s, String mode) throws Exception {
        HttpResponse<String> r = post("http://127.0.0.1:" + s.adminPort() + "/admin/chaos?mode=" + mode, "",
                "X-Admin-Token", TOKEN);
        eq(r.body(), 200, r.statusCode());
    }

    static String report(OutageServer s, String zip) throws Exception {
        HttpResponse<String> r = post(base(s) + "/api/reports", "{\"zip\":\"" + zip + "\"}");
        eq(r.body(), 201, r.statusCode());
        return (String) json(r).get("id");
    }

    static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @TestRunner.Test
    void reportTrackAndSummarise() throws Exception {
        try (OutageServer s = start("crud")) {
            HttpResponse<String> created = post(base(s) + "/api/reports",
                    "{\"zip\":\"00012\",\"address\":\"12 Bay St\",\"phone\":\"(555) 010-0123\",\"notes\":\"bang from the pole\"}");
            eq(201, created.statusCode());
            Map<?, ?> t = json(created);
            String id = (String) t.get("id");
            eq("/api/reports/" + id, created.headers().firstValue("Location").orElse(null));
            eq("reported", t.get("status"));
            eq("•••-•••-0123", t.get("phone"));

            Map<?, ?> looked = json(get(base(s) + "/api/reports/" + id.toLowerCase()));
            eq(id, looked.get("id"));
            eq("Harbor District", ((Map<?, ?>) looked.get("area")).get("name"));

            Map<?, ?> zip = json(get(base(s) + "/api/zip/00012"));
            eq(1.0, zip.get("openOutages"));
            eq(1.0, zip.get("awaitingConfirmation"));

            Map<?, ?> areas = json(get(base(s) + "/api/areas"));
            eq(1.0, ((Map<?, ?>) areas.get("totals")).get("openOutages"));
            eq(6, ((List<?>) areas.get("areas")).size());
            eq(false, areas.get("stormMode"));

            Map<?, ?> h = health(s);
            eq(1.0, h.get("reportsTotal"));
            eq(1.0, h.get("reportsLastMinute"));
            eq(1.0, h.get("openOutages"));
            eq(1.0, h.get("awaitingTriage"));
        }
    }

    @TestRunner.Test
    void rejectsBadInputWithClientErrors() throws Exception {
        try (OutageServer s = start("bad")) {
            String api = base(s) + "/api/reports";
            eq("invalid json", 400, post(api, "{zip:").statusCode());
            eq("not an object", 400, post(api, "[\"00012\"]").statusCode());
            HttpResponse<String> noZip = post(api, "{\"address\":\"12 Bay St\"}");
            eq(400, noZip.statusCode());
            eq("zip", json(noZip).get("field"));
            eq("zip as a number loses its leading zeros", 400, post(api, "{\"zip\":12}").statusCode());
            HttpResponse<String> outside = post(api, "{\"zip\":\"70112\"}");
            eq("ZIP 70112 isn't in our service area. We serve ZIP codes 00010 to 00069.", json(outside).get("error"));
            eq("body too large", 413, post(api, "{\"notes\":\"" + "x".repeat(9000) + "\"}").statusCode());
            eq("GET on collection", 405, get(api).statusCode());
            eq("unknown ticket", 404, get(api + "/EPL-ZZZZZZ").statusCode());
            eq("malformed ZIP", 400, get(base(s) + "/api/zip/12").statusCode());
            eq("ZIP outside the territory", 404, get(base(s) + "/api/zip/70112").statusCode());
            eq("unknown API path", 404, get(base(s) + "/api/nope").statusCode());

            Map<?, ?> h = health(s);
            eq("4xx are not service errors", 0.0, h.get("errorsTotal"));
            eq(11.0, h.get("clientErrorsTotal"));
            eq("nobody failed to report: these were client mistakes", 0.0, h.get("failedReportsLastMinute"));
            eq("UP", h.get("status"));
        }
    }

    @TestRunner.Test
    void healthHasTheContractFieldsAndIgnoresItsOwnPolls() throws Exception {
        try (OutageServer s = start("health")) {
            get(base(s) + "/api");
            HttpResponse<String> r = get(base(s) + "/health");
            eq(200, r.statusCode());
            eq("no-store", r.headers().firstValue("Cache-Control").orElse(null));
            Map<?, ?> h = json(r);
            for (String key : List.of("status", "uptimeSeconds", "requestsTotal", "errorsTotal", "clientErrorsTotal",
                    "p95LatencyMs", "errorRatePct", "storeOk", "chaosMode", "windowSeconds", "tickets",
                    "reportsTotal", "reportsLastMinute", "failedReportsLastMinute", "openOutages", "customersAffected",
                    "awaitingTriage", "oldestOpenMinutes", "overdueOutages", "stormMode")) {
                ok("health has " + key, h.containsKey(key));
            }
            for (int i = 0; i < 3; i++) get(base(s) + "/health");
            eq("only the GET /api counts", 1.0, health(s).get("requestsTotal"));
            eq("off", h.get("chaosMode"));
            eq(true, h.get("storeOk"));
        }
    }

    @TestRunner.Test
    void theDispatcherMovesTicketsOnItsOwn() throws Exception {
        try (OutageServer s = start("dispatch", "OUTAGE_DISPATCH_INTERVAL_MS", "20",
                "OUTAGE_TRIAGE_DELAY_SECONDS", "0", "OUTAGE_RESTORE_MINUTES", "0")) {
            String id = report(s, "00045");
            waitFor("restored", () -> {
                try {
                    return "restored".equals(json(get(base(s) + "/api/reports/" + id)).get("status"));
                } catch (Exception e) {
                    return false;
                }
            });
            Map<?, ?> t = json(get(base(s) + "/api/reports/" + id));
            for (Object step : (List<?>) t.get("timeline")) {
                ok("every step has a time: " + step, ((Map<?, ?>) step).get("at") != null);
            }
            eq(0.0, health(s).get("openOutages"));
        }
    }

    @TestRunner.Test
    void adminNeedsTheTokenAndIsNotOnThePublicPort() throws Exception {
        try (OutageServer s = start("admin")) {
            String admin = "http://127.0.0.1:" + s.adminPort() + "/admin/chaos?mode=errors";
            eq("no token", 401, post(admin, "").statusCode());
            eq("wrong token", 401, post(admin, "", "X-Admin-Token", "guess").statusCode());
            eq("bad mode", 400, post("http://127.0.0.1:" + s.adminPort() + "/admin/chaos?mode=slow", "",
                    "X-Admin-Token", TOKEN).statusCode());
            eq("not on the public listener", 404, post(base(s) + "/admin/chaos?mode=errors", "",
                    "X-Admin-Token", TOKEN).statusCode());
            eq("off", health(s).get("chaosMode"));
        }
        try (OutageServer s = start("noadmin", "OUTAGE_ADMIN_TOKEN", "")) {
            eq("no token configured: no admin listener", -1, s.adminPort());
        }
    }

    @TestRunner.Test
    void latencyChaosDegradesAndRecoversWhenTheWindowRollsOff() throws Exception {
        try (OutageServer s = start("latency", "OUTAGE_WINDOW_SECONDS", "1")) {
            String id = report(s, "00012");
            chaos(s, "latency");
            for (int i = 0; i < 5; i++) eq(200, get(base(s) + "/api/reports/" + id).statusCode());
            Map<?, ?> h = health(s);
            eq("DEGRADED", h.get("status"));
            ok("p95 includes the injected 80 ms: " + h.get("p95LatencyMs"), num(h, "p95LatencyMs") >= 80);
            eq("latency", h.get("chaosMode"));
            eq("DEGRADED is still 200", 200, get(base(s) + "/health").statusCode());

            chaos(s, "off");
            Thread.sleep(1100);
            eq(200, get(base(s) + "/api/reports/" + id).statusCode());
            eq("UP", health(s).get("status"));
        }
    }

    @TestRunner.Test
    void latencyHitsTheApiButNotThePages() throws Exception {
        try (OutageServer s = start("latency-pages", "OUTAGE_CHAOS_LATENCY_MS", "600")) {
            chaos(s, "latency");
            long t0 = System.nanoTime();
            eq(200, get(base(s) + "/").statusCode());
            eq(200, get(base(s) + "/static/app.js").statusCode());
            long pageMs = (System.nanoTime() - t0) / 1_000_000;
            ok("page and script are not delayed: " + pageMs + " ms", pageMs < 500);
            t0 = System.nanoTime();
            eq(200, get(base(s) + "/api/areas").statusCode());
            ok("the API is", (System.nanoTime() - t0) / 1_000_000 >= 600);
        }
    }

    @TestRunner.Test
    void errorChaosFailsReportsAndCountsCustomersTurnedAway() throws Exception {
        try (OutageServer s = start("errors")) {
            chaos(s, "errors");
            for (int i = 0; i < Health.MIN_REQUESTS_FOR_ERROR_RATE; i++) {
                eq(500, post(base(s) + "/api/reports", "{\"zip\":\"00012\"}").statusCode());
            }
            HttpResponse<String> form = postForm(s, "zip=00012");
            eq(500, form.statusCode());
            ok("the form explains and gives the phone number", form.body().contains("call " + OutageServer.SUPPORT_PHONE));
            eq("the page itself still loads", 200, get(base(s) + "/").statusCode());
            Map<?, ?> h = health(s);
            eq("DEGRADED", h.get("status"));
            eq(11.0, h.get("errorsTotal"));
            eq(11.0, h.get("failedReportsLastMinute"));
            eq("nothing was taken", 0.0, h.get("reportsTotal"));
        }
    }

    @TestRunner.Test
    void storeChaosIsDown503ButLookupsStillWork() throws Exception {
        try (OutageServer s = start("store")) {
            String id = report(s, "00012");
            chaos(s, "store");
            HttpResponse<String> h = get(base(s) + "/health");
            eq(503, h.statusCode());
            eq("DOWN", json(h).get("status"));
            eq(false, json(h).get("storeOk"));
            HttpResponse<String> refused = post(base(s) + "/api/reports", "{\"zip\":\"00031\"}");
            eq("cannot take reports", 503, refused.statusCode());
            eq(OutageServer.UNAVAILABLE, json(refused).get("error"));
            eq("can still look one up", 200, get(base(s) + "/api/reports/" + id).statusCode());
            eq(1.0, health(s).get("failedReportsLastMinute"));

            chaos(s, "off");
            eq(200, get(base(s) + "/health").statusCode());
            report(s, "00031");
            eq(2.0, health(s).get("tickets"));
        }
    }

    @TestRunner.Test
    void stormChaosMovesTheBusinessFiguresAndNotTheTechnicalOnes() throws Exception {
        try (OutageServer s = start("storm", "OUTAGE_STORM_REPORTS_PER_SECOND", "400", "OUTAGE_STORM_RAMP_SECONDS", "0",
                "OUTAGE_TRIAGE_PER_MINUTE", "60", "OUTAGE_DISPATCH_INTERVAL_MS", "100")) {
            chaos(s, "storm");
            waitFor("a backlog of 250", () -> {
                try {
                    return num(health(s), "awaitingTriage") > 250;
                } catch (Exception e) {
                    return false;
                }
            });
            Map<?, ?> h = health(s);
            eq("busy is not broken", "UP", h.get("status"));
            eq(true, h.get("stormMode"));
            eq("no HTTP traffic was generated", 0.0, h.get("requestsTotal"));
            eq(0.0, h.get("errorsTotal"));
            ok("reports in the last minute: " + h.get("reportsLastMinute"), num(h, "reportsLastMinute") >= 60);
            ok("the storm banner shows", !get(base(s) + "/").body().contains("id=\"storm-banner\" role=\"status\" hidden"));

            chaos(s, "off");
            Thread.sleep(400);
            double after = num(health(s), "reportsTotal");
            Thread.sleep(400);
            eq("the surge stops with the mode", after, num(health(s), "reportsTotal"));
        }
    }

    @TestRunner.Test
    void jmxBeanReportsTheSameSnapshot() throws Exception {
        try (OutageServer s = start("jmx")) {
            report(s, "00012");
            get(base(s) + "/api/reports/EPL-ZZZZZZ");
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("outagereporter:type=Stats");
            eq(2L, mbs.getAttribute(name, "RequestsTotal"));
            eq(1L, mbs.getAttribute(name, "ClientErrorsTotal"));
            eq(1, mbs.getAttribute(name, "Tickets"));
            eq(1L, mbs.getAttribute(name, "ReportsTotal"));
            eq(1, mbs.getAttribute(name, "OpenOutages"));
            eq(1, mbs.getAttribute(name, "AwaitingTriage"));
            eq(0, mbs.getAttribute(name, "StormMode"));
            eq(0, mbs.getAttribute(name, "StatusCode"));
            eq(1, mbs.getAttribute(name, "StoreOk"));
            chaos(s, "store");
            eq(2, mbs.getAttribute(name, "StatusCode"));
            eq(0, mbs.getAttribute(name, "StoreOk"));
            eq(3, mbs.getAttribute(name, "ChaosModeCode"));
            eq("store", mbs.getAttribute(name, "ChaosMode"));
            chaos(s, "storm");
            eq(4, mbs.getAttribute(name, "ChaosModeCode"));
        }
        ok("unregistered on close",
                !ManagementFactory.getPlatformMBeanServer().isRegistered(new ObjectName("outagereporter:type=Stats")));
    }

    @TestRunner.Test
    void httpsListenerServesTheSameApi() throws Exception {
        try (OutageServer s = start("tls")) {
            String id = report(s, "00012");
            HttpResponse<String> r = get("https://localhost:" + s.tlsPort() + "/api/reports/" + id);
            eq(200, r.statusCode());
            eq(id, json(r).get("id"));
            X509Certificate leaf = (X509Certificate) r.sslSession().orElseThrow().getPeerCertificates()[0];
            eq("CN=localhost", leaf.getSubjectX500Principal().getName());
        }
    }

    @TestRunner.Test
    void ticketsSurviveARestart() throws Exception {
        String id;
        try (OutageServer s = start("restart")) {
            id = report(s, "00012");
        }
        try (OutageServer s = start("restart")) {
            eq(1.0, health(s).get("tickets"));
            eq(1.0, health(s).get("openOutages"));
            eq("reported", json(get(base(s) + "/api/reports/" + id)).get("status"));
            eq("counters restart from zero", 1.0, health(s).get("requestsTotal"));
            eq(0.0, health(s).get("reportsTotal"));
        }
    }

    @TestRunner.Test
    void concurrentReportsAreAllTaken() throws Exception {
        try (OutageServer s = start("concurrent"); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String zip = String.format("%05d", 10 + i % 60);
                results.add(pool.submit(() -> post(base(s) + "/api/reports", "{\"zip\":\"" + zip + "\"}").statusCode()));
            }
            for (var f : results) eq(201, f.get());
            Map<?, ?> h = health(s);
            eq(100.0, h.get("tickets"));
            eq(100.0, h.get("openOutages"));
        }
    }
}
