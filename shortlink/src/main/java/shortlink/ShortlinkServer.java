package shortlink;

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
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Wires the HTTP listeners, store, metrics, chaos switch and JMX bean.
 *
 * <p>Public listener (and optional HTTPS twin): the API, redirects and
 * /health. Admin listener: /admin/chaos only, bound to loopback by default
 * and disabled unless SHORTLINK_ADMIN_TOKEN is set.
 */
public final class ShortlinkServer implements AutoCloseable {

    static final int MAX_BODY_BYTES = 8 * 1024;
    static final int MAX_URL_LENGTH = 2048;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final String API_LINKS = "/api/links";

    private final Config config;
    private final Chaos chaos = new Chaos();
    private final Metrics metrics;
    private final LinkStore store;
    private final long startedMs = System.currentTimeMillis();
    private final AtomicReference<Health.Status> lastStatus = new AtomicReference<>(Health.Status.UP);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer http;
    private HttpsServer https;
    private HttpServer admin;
    private ObjectName mbeanName;

    private ShortlinkServer(Config config) throws IOException {
        this.config = config;
        this.metrics = new Metrics(config.windowSeconds(), System::currentTimeMillis);
        this.store = LinkStore.open(config.dataDir(), () -> chaos.is(Chaos.Mode.STORE));
    }

    public static ShortlinkServer start(Config config) throws IOException, GeneralSecurityException, JMException {
        ShortlinkServer s = new ShortlinkServer(config);
        try {
            s.startListeners();
            s.registerMBean();
        } catch (IOException | GeneralSecurityException | JMException | RuntimeException e) {
            s.close();
            throw e;
        }
        Log.info("started", "version", Main.VERSION, "port", s.port(), "tlsPort", s.tlsPort(),
                "adminPort", s.adminPort(), "links", s.store.size(), "skippedRecords", s.store.skippedOnLoad(),
                "dataDir", config.dataDir().toAbsolutePath(), "java", System.getProperty("java.version"));
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
            Log.warn("admin_disabled", "reason", "SHORTLINK_ADMIN_TOKEN not set");
        }
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
                config.windowSeconds(), w.requests(), w.p95LatencyMs(), w.errorRatePct(), storeOk, chaos.mode());
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
            status = sendJson(ex, 500, Map.of("error", "internal error"));
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        metrics.record(status, ms);
        Log.info("access", "method", ex.getRequestMethod(), "path", path, "status", status,
                "ms", String.format(Locale.ROOT, "%.1f", ms), "client", ex.getRemoteAddress().getAddress().getHostAddress(),
                "tls", ex instanceof HttpsExchange);
    }

    private int route(HttpExchange ex, String path) throws IOException, InterruptedException {
        switch (chaos.mode()) {
            case LATENCY -> Thread.sleep(config.chaosLatencyMs());
            case ERRORS -> {
                if (ThreadLocalRandom.current().nextInt(100) < config.chaosErrorPercent()) {
                    return sendJson(ex, 500, Map.of("error", "injected failure (chaos mode errors)"));
                }
            }
            default -> { }
        }
        String method = ex.getRequestMethod();
        if (path.equals("/")) {
            if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
            return sendJson(ex, 200, Map.of("service", "shortlink", "version", Main.VERSION,
                    "endpoints", List.of("POST /api/links", "GET /{code}", "GET /api/links/{code}", "GET /health")));
        }
        if (path.equals(API_LINKS)) {
            if (!method.equals("POST")) return methodNotAllowed(ex, "POST");
            return create(ex);
        }
        if (path.startsWith(API_LINKS + "/")) {
            if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
            String code = path.substring(API_LINKS.length() + 1);
            Optional<LinkStore.Link> link = CODE.matcher(code).matches() ? store.get(code) : Optional.empty();
            return link.map(l -> sendJson(ex, 200, linkJson(ex, l))).orElseGet(() -> notFound(ex));
        }
        String code = path.substring(1);
        if (!CODE.matcher(code).matches()) return notFound(ex);
        if (!isGet(method)) return methodNotAllowed(ex, "GET, HEAD");
        Optional<LinkStore.Link> link = store.hit(code);
        if (link.isEmpty()) return notFound(ex);
        ex.getResponseHeaders().set("Location", link.get().url());
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        return send(ex, 302, null, null);
    }

    private int create(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) return badRequest(ex, 413, "body larger than " + MAX_BODY_BYTES + " bytes");

        Object parsed;
        try {
            parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (Json.ParseException e) {
            return badRequest(ex, 400, "invalid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> m) || !(m.get("url") instanceof String url)) {
            return badRequest(ex, 400, "expected {\"url\": \"https://...\"}");
        }
        String problem = validateUrl(url);
        if (problem != null) return badRequest(ex, 400, problem);

        LinkStore.Link link;
        try {
            link = store.create(url);
        } catch (IOException e) {
            Log.error("store_write_failed", "error", e.getMessage());
            return sendJson(ex, 503, Map.of("error", "store unavailable, link not created"));
        }
        Log.info("link_created", "code", link.code(), "url", url);
        Map<String, Object> json = linkJson(ex, link);
        ex.getResponseHeaders().set("Location", (String) json.get("shortUrl"));
        return sendJson(ex, 201, json);
    }

    /** Returns null if the URL is acceptable, else a reason for the client. */
    static String validateUrl(String url) {
        if (url.isBlank()) return "url is empty";
        if (url.length() > MAX_URL_LENGTH) return "url longer than " + MAX_URL_LENGTH + " characters";
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) <= ' ' || url.charAt(i) == 0x7f) return "url contains whitespace or control characters";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "url is not a valid URI";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "url must start with http:// or https://";
        if (uri.getHost() == null || uri.getHost().isEmpty()) return "url has no host";
        return null;
    }

    private Map<String, Object> linkJson(HttpExchange ex, LinkStore.Link l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", l.code());
        m.put("shortUrl", baseUrl(ex) + "/" + l.code());
        m.put("url", l.url());
        m.put("createdAt", java.time.Instant.ofEpochMilli(l.createdAt()).toString());
        m.put("hits", l.hits());
        return m;
    }

    private String baseUrl(HttpExchange ex) {
        if (!config.baseUrl().isBlank()) return config.baseUrl().replaceAll("/+$", "");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) host = "localhost:" + ex.getLocalAddress().getPort();
        return (ex instanceof HttpsExchange ? "https://" : "http://") + host;
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
                notFound(ex);
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
                badRequest(ex, 400, e.getMessage());
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

    private static int notFound(HttpExchange ex) {
        return sendJson(ex, 404, Map.of("error", "not found"));
    }

    private static int badRequest(HttpExchange ex, int status, String reason) {
        return sendJson(ex, status, Map.of("error", reason));
    }

    private static int methodNotAllowed(HttpExchange ex, String allow) {
        ex.getResponseHeaders().set("Allow", allow);
        return sendJson(ex, 405, Map.of("error", "method not allowed"));
    }

    private static int sendJson(HttpExchange ex, int status, Map<String, ?> body) {
        return send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends and closes the exchange; returns the status for logging and metrics. */
    private static int send(HttpExchange ex, int status, String contentType, byte[] body) {
        try (ex) {
            if (contentType != null) ex.getResponseHeaders().set("Content-Type", contentType);
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
