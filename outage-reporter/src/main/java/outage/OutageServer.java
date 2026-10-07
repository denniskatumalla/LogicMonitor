package outage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires the HTTP listeners, the outage domain, its background dispatcher and
 * storm generator, metrics, the chaos switch and the JMX bean.
 *
 * <p>Public listener (and optional HTTPS twin): the customer pages, the JSON
 * API, the no-JavaScript form POST and /health. Admin listener: /admin/chaos
 * only, bound to loopback by default and disabled unless OUTAGE_ADMIN_TOKEN
 * is set.
 *
 * <p>Faults hit the dynamic endpoints (/api/* and POST /report). Pages and
 * static assets are exempt, as if served from a CDN, so the page still
 * renders and its own loading and error states are what a customer sees.
 */
public final class OutageServer implements AutoCloseable {

    static final int MAX_BODY_BYTES = 8 * 1024;
    static final String SUPPORT_PHONE = "1-800-555-0142";
    static final String UNAVAILABLE = "We can't take your report right now — please try again in a few minutes or call "
            + SUPPORT_PHONE + ".";
    private static final long STORM_TICK_MS = 200;
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    private final Config config;
    private final Chaos chaos = new Chaos();
    private final Metrics metrics;
    private final TicketStore store;
    private final OutageService service;
    private final Dispatcher dispatcher;
    private final StormSurge storm;
    private final Pages pages;
    private final long startedMs = System.currentTimeMillis();
    private final AtomicReference<Health.Status> lastStatus = new AtomicReference<>(Health.Status.UP);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dispatcher");
        t.setDaemon(true);
        return t;
    });
    private HttpServer http;
    private HttpsServer https;
    private HttpServer admin;
    private ObjectName mbeanName;

    private OutageServer(Config config) throws IOException {
        this.config = config;
        this.metrics = new Metrics(config.windowSeconds(), System::currentTimeMillis);
        this.store = TicketStore.open(config.dataDir(), () -> chaos.is(Chaos.Mode.STORE));
        this.service = new OutageService(store, config, System::currentTimeMillis);
        this.dispatcher = new Dispatcher(service, config, new SplittableRandom(), startedMs);
        this.storm = new StormSurge(service, chaos, config, new SplittableRandom());
        this.pages = Pages.load(config.demoCredit());
    }

    public static OutageServer start(Config config) throws IOException, GeneralSecurityException, JMException {
        OutageServer s = new OutageServer(config);
        try {
            s.startListeners();
            s.registerMBean();
            s.startBackground();
        } catch (IOException | GeneralSecurityException | JMException | RuntimeException e) {
            s.close();
            throw e;
        }
        Log.info("started", "version", Main.VERSION, "port", s.port(), "tlsPort", s.tlsPort(),
                "adminPort", s.adminPort(), "tickets", s.store.size(), "openOutages", s.store.open().size(),
                "skippedRecords", s.store.skippedOnLoad(), "dataDir", config.dataDir().toAbsolutePath(),
                "java", System.getProperty("java.version"));
        if (s.store.skippedOnLoad() > 0) {
            Log.warn("store_records_skipped", "count", s.store.skippedOnLoad());
        }
        return s;
    }

    private void startListeners() throws IOException, GeneralSecurityException {
        http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        http.createContext("/", this::handlePublic);
        http.setExecutor(executor);
        http.start();

        if (config.tlsEnabled()) {
            https = HttpsServer.create(new InetSocketAddress(config.bind(), config.tlsPort()), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext()));
            https.createContext("/", this::handlePublic);
            https.setExecutor(executor);
            https.start();
        }

        if (config.adminEnabled()) {
            admin = HttpServer.create(new InetSocketAddress(config.adminBind(), config.adminPort()), 0);
            admin.createContext("/admin/", this::handleAdmin);
            admin.setExecutor(executor);
            admin.start();
        } else {
            Log.warn("admin_disabled", "reason", "OUTAGE_ADMIN_TOKEN not set");
        }
    }

    private void startBackground() {
        long every = Math.max(10, config.dispatchIntervalMs());
        background.scheduleAtFixedRate(guarded("dispatcher", () -> dispatcher.tick(System.currentTimeMillis())),
                every, every, TimeUnit.MILLISECONDS);
        background.scheduleAtFixedRate(guarded("storm", () -> storm.tick(System.currentTimeMillis())),
                STORM_TICK_MS, STORM_TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** An exception escaping a scheduled task would cancel it for good; log it and keep the schedule. */
    private static Runnable guarded(String task, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                Log.error("background_task_failed", "task", task, "error", e.toString());
            }
        };
    }

    private SSLContext sslContext() throws IOException, GeneralSecurityException {
        char[] password = config.tlsPassword().toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(config.tlsKeystore())) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private void registerMBean() throws JMException {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        mbeanName = new ObjectName(Stats.OBJECT_NAME);
        // Only one instance per JVM in production; tests start several in turn.
        if (mbs.isRegistered(mbeanName)) mbs.unregisterMBean(mbeanName);
        mbs.registerMBean(new Stats(this::health), mbeanName);
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public int tlsPort() {
        return https == null ? -1 : https.getAddress().getPort();
    }

    public int adminPort() {
        return admin == null ? -1 : admin.getAddress().getPort();
    }

    Health health() {
        boolean storeOk = store.healthy();
        Metrics.Window w = metrics.window();
        Health.Status status = Health.classify(storeOk, w, config);
        Health.Status previous = lastStatus.getAndSet(status);
        if (previous != status) {
            Log.warn("health_changed", "from", previous, "to", status, "p95LatencyMs", w.p95LatencyMs(),
                    "errorRatePct", w.errorRatePct(), "storeOk", storeOk, "chaosMode", chaos.mode().label());
        }
        return new Health(status, (System.currentTimeMillis() - startedMs) / 1000, store.size(),
                metrics.requestsTotal(), metrics.errorsTotal(), metrics.clientErrorsTotal(),
                config.windowSeconds(), w.requests(), w.p95LatencyMs(), w.errorRatePct(), storeOk, chaos.mode(),
                service.kpis());
    }

    // ---- public listener -------------------------------------------------

    private void handlePublic(HttpExchange ex) {
        String path = ex.getRequestURI().getRawPath();
        if (path.equals("/health")) {
            serveHealth(ex);
            return;
        }
        long t0 = System.nanoTime();
        int status;
        try {
            status = route(ex, path);
        } catch (Exception e) {
            Log.error("request_failed", "method", ex.getRequestMethod(), "path", path, "error", e.toString());
            status = path.startsWith("/api") ? sendJson(ex, 500, Map.of("error", "internal error"))
                    : unavailablePage(ex, 500);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        metrics.record(status, ms);
        if (status >= 500 && isReportSubmission(ex.getRequestMethod(), path)) service.reportFailed();
        Log.info("access", "method", ex.getRequestMethod(), "path", path, "status", status,
                "ms", String.format(Locale.ROOT, "%.1f", ms), "client", ex.getRemoteAddress().getAddress().getHostAddress(),
                "tls", ex instanceof HttpsExchange);
    }

    private static boolean isReportSubmission(String method, String path) {
        return method.equals("POST") && (path.equals("/report") || path.equals("/api/reports"));
    }

    private static boolean dynamic(String path) {
        return path.equals("/report") || path.equals("/api") || path.startsWith("/api/");
    }

    private int route(HttpExchange ex, String path) throws IOException, InterruptedException {
        boolean api = path.equals("/api") || path.startsWith("/api/");
        if (dynamic(path)) {
            switch (chaos.mode()) {
                case LATENCY -> Thread.sleep(config.chaosLatencyMs());
                case ERRORS -> {
                    if (ThreadLocalRandom.current().nextInt(100) < config.chaosErrorPercent()) {
                        return api ? sendJson(ex, 500, Map.of("error", "injected failure (chaos mode errors)"))
                                : unavailablePage(ex, 500);
                    }
                }
                default -> { }
            }
        }
        String method = ex.getRequestMethod();
        switch (path) {
            case "/", "/index.html" -> {
                return isGet(method) ? page(ex, "index.html") : methodNotAllowed(ex, "GET, HEAD");
            }
            case "/status" -> {
                return isGet(method) ? page(ex, "status.html") : methodNotAllowed(ex, "GET, HEAD");
            }
            case "/areas" -> {
                return isGet(method) ? page(ex, "areas.html") : methodNotAllowed(ex, "GET, HEAD");
            }
            case "/monitoring" -> {
                return isGet(method) ? page(ex, "monitoring.html") : methodNotAllowed(ex, "GET, HEAD");
            }
            case "/report" -> {
                return method.equals("POST") ? submitForm(ex) : methodNotAllowed(ex, "POST");
            }
            case "/api" -> {
                if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
                return sendJson(ex, 200, Map.of("service", "outage-reporter", "version", Main.VERSION,
                        "endpoints", List.of("POST /api/reports", "GET /api/reports/{id}", "GET /api/zip/{zip}",
                                "GET /api/areas", "POST /report (form)", "GET /health")));
            }
            case "/api/reports" -> {
                return method.equals("POST") ? submitJson(ex) : methodNotAllowed(ex, "POST");
            }
            case "/api/areas" -> {
                return isGet(method) ? sendJson(ex, 200, service.areasJson()) : methodNotAllowed(ex, "GET, HEAD");
            }
            default -> { }
        }
        if (path.startsWith("/static/")) {
            Optional<Pages.Asset> asset = pages.asset(path.substring("/static/".length()));
            if (asset.isEmpty()) return notFoundPage(ex);
            if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
            ex.getResponseHeaders().set("Cache-Control", "max-age=300");
            return send(ex, 200, asset.get().contentType(), asset.get().body());
        }
        if (path.startsWith("/api/reports/")) {
            if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
            String id = path.substring("/api/reports/".length());
            return service.ticket(id).map(t -> sendJson(ex, 200, service.ticketJson(t)))
                    .orElseGet(() -> sendJson(ex, 404, Map.of("error",
                            "We couldn't find that ticket number. Check it and try again.")));
        }
        if (path.startsWith("/api/zip/")) {
            if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
            String zip = path.substring("/api/zip/".length());
            if (!zip.matches("\\d{5}")) return sendJson(ex, 400, Map.of("error", "Enter a 5-digit ZIP code."));
            if (Territory.forZip(zip).isEmpty()) {
                return sendJson(ex, 404, Map.of("error", "ZIP " + zip + " isn't in our service area."));
            }
            return sendJson(ex, 200, service.zipJson(zip));
        }
        return api ? sendJson(ex, 404, Map.of("error", "not found")) : notFoundPage(ex);
    }

    /** POST /api/reports with a JSON body. */
    private int submitJson(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body == null) return sendJson(ex, 413, Map.of("error", "body larger than " + MAX_BODY_BYTES + " bytes"));
        Object parsed;
        try {
            parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (Json.ParseException e) {
            return sendJson(ex, 400, Map.of("error", "invalid JSON: " + e.getMessage()));
        }
        if (!(parsed instanceof Map<?, ?> m)) return sendJson(ex, 400, Map.of("error", "expected a JSON object"));
        Map<String, Object> fields = new LinkedHashMap<>();
        m.forEach((k, v) -> fields.put(String.valueOf(k), v));
        Report report;
        try {
            report = Report.from(fields);
        } catch (Report.Invalid e) {
            return sendJson(ex, 400, Map.of("error", e.getMessage(), "field", e.field()));
        }
        Optional<Ticket> t = accept(report, "api");
        if (t.isEmpty()) return sendJson(ex, 503, Map.of("error", UNAVAILABLE));
        ex.getResponseHeaders().set("Location", "/api/reports/" + t.get().id());
        return sendJson(ex, 201, service.ticketJson(t.get()));
    }

    /** POST /report, form-encoded: the path that works without JavaScript, and for LM's web checks. */
    private int submitForm(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body == null) return message(ex, 413, "error", "Report too long", "<p>Please shorten your report and try again.</p>");
        Report report;
        try {
            report = Report.from(query(new String(body, StandardCharsets.UTF_8)));
        } catch (Report.Invalid e) {
            return message(ex, 400, "error", "Please check your report",
                    "<p>" + Pages.esc(e.getMessage()) + "</p><p><a href=\"/\">Back to the report form</a></p>");
        }
        Optional<Ticket> t = accept(report, "form");
        if (t.isEmpty()) return unavailablePage(ex, 503);
        String id = Pages.esc(t.get().id());
        return message(ex, 200, "ok", "Report received",
                "<p>Your ticket number is <strong class=\"ticket-id\" id=\"ticket-id\">" + id + "</strong>.</p>"
                        + "<p>We'll confirm the outage and post an estimated restoration time shortly.</p>"
                        + "<p><a class=\"button\" href=\"/status?ticket=" + id + "\">Track this outage</a></p>");
    }

    /** Stores the report; empty if the store can't take it. Logs no address or phone number. */
    private Optional<Ticket> accept(Report report, String channel) {
        try {
            Ticket t = service.report(report, Ticket.Source.WEB);
            Log.info("report_received", "id", t.id(), "zip", t.zip(), "area", t.areaId(), "channel", channel,
                    "synthetic", !t.counts());
            return Optional.of(t);
        } catch (IOException e) {
            Log.error("store_write_failed", "error", e.getMessage());
            return Optional.empty();
        }
    }

    /** The body, or null if it is over the limit. */
    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private void serveHealth(HttpExchange ex) {
        try {
            if (!isGet(ex.getRequestMethod())) {
                methodNotAllowed(ex, "GET, HEAD");
                return;
            }
            Health h = health();
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            sendJson(ex, h.httpStatus(), h.toJson());
        } catch (RuntimeException e) {
            Log.error("health_failed", "error", e.toString());
            sendJson(ex, 500, Map.of("error", "internal error"));
        }
    }

    // ---- pages -------------------------------------------------------------

    private int page(HttpExchange ex, String template) {
        return sendHtml(ex, 200, pages.render(template, Map.of("stormHidden", stormHidden())));
    }

    private int message(HttpExchange ex, int status, String tone, String heading, String bodyHtml) {
        return sendHtml(ex, status, pages.render("message.html", Map.of(
                "stormHidden", stormHidden(), "tone", tone, "title", Pages.esc(heading),
                "heading", Pages.esc(heading), "body", bodyHtml)));
    }

    private int unavailablePage(HttpExchange ex, int status) {
        return message(ex, status, "error", "Report not sent",
                "<p>" + Pages.esc(UNAVAILABLE) + "</p><p><a href=\"/\">Try again</a></p>");
    }

    private int notFoundPage(HttpExchange ex) {
        return message(ex, 404, "error", "Page not found",
                "<p>That page doesn't exist. <a href=\"/\">Report an outage</a> or <a href=\"/status\">check status</a>.</p>");
    }

    private String stormHidden() {
        return service.stormMode() ? "" : "hidden";
    }

    // ---- admin listener --------------------------------------------------

    private void handleAdmin(HttpExchange ex) {
        String path = ex.getRequestURI().getRawPath();
        try {
            if (!tokenMatches(ex.getRequestHeaders().getFirst("X-Admin-Token"))) {
                Log.warn("admin_unauthorized", "path", path, "client", ex.getRemoteAddress().getAddress().getHostAddress());
                sendJson(ex, 401, Map.of("error", "missing or wrong X-Admin-Token"));
                return;
            }
            if (!path.equals("/admin/chaos")) {
                sendJson(ex, 404, Map.of("error", "not found"));
                return;
            }
            String method = ex.getRequestMethod();
            if (isGet(method)) {
                sendJson(ex, 200, Map.of("chaosMode", chaos.mode().label()));
                return;
            }
            if (!method.equals("POST")) {
                methodNotAllowed(ex, "GET, POST");
                return;
            }
            Chaos.Mode mode;
            try {
                mode = Chaos.Mode.parse(query(ex.getRequestURI().getRawQuery()).get("mode"));
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, Map.of("error", e.getMessage()));
                return;
            }
            Chaos.Mode previous = chaos.mode();
            chaos.set(mode);
            Log.warn("chaos_mode_changed", "from", previous.label(), "to", mode.label());
            sendJson(ex, 200, Map.of("chaosMode", mode.label(), "previous", previous.label()));
        } catch (RuntimeException e) {
            Log.error("admin_failed", "path", path, "error", e.toString());
            sendJson(ex, 500, Map.of("error", "internal error"));
        }
    }

    private boolean tokenMatches(String presented) {
        if (presented == null) return false;
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                config.adminToken().getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    // ---- responses ---------------------------------------------------------

    private static boolean isGet(String method) {
        return method.equals("GET") || method.equals("HEAD");
    }

    private static int methodNotAllowed(HttpExchange ex, String allow) {
        ex.getResponseHeaders().set("Allow", allow);
        return sendJson(ex, 405, Map.of("error", "method not allowed"));
    }

    private static int sendJson(HttpExchange ex, int status, Map<String, ?> body) {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        return send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static int sendHtml(HttpExchange ex, int status, String html) {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        ex.getResponseHeaders().set("Referrer-Policy", "same-origin");
        return send(ex, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends and closes the exchange; returns the status for logging and metrics. */
    private static int send(HttpExchange ex, int status, String contentType, byte[] body) {
        try (ex) {
            if (contentType != null) ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            boolean noBody = body == null || ex.getRequestMethod().equals("HEAD");
            ex.sendResponseHeaders(status, noBody ? -1 : body.length);
            if (!noBody) ex.getResponseBody().write(body);
        } catch (IOException e) {
            // Client went away; the status we meant to send is still what we record.
        }
        return status;
    }

    @Override
    public void close() {
        if (http != null) http.stop(1);
        if (https != null) https.stop(1);
        if (admin != null) admin.stop(0);
        background.shutdownNow();
        try {
            background.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.close();
        store.close();
        if (mbeanName != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(mbeanName);
            } catch (JMException ignored) {
                // already gone
            }
        }
        Log.info("stopped", "uptimeSeconds", (System.currentTimeMillis() - startedMs) / 1000);
    }
}
