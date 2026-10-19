package outage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The customer web pages, embedded in the jar under /outage/web/: HTML
 * templates with {{placeholders}}, plus the stylesheet, script and icon.
 * Nothing is fetched from anywhere else.
 */
final class Pages {

    /** A static file and the Content-Type it is served with. */
    record Asset(String contentType, byte[] body) { }

    // Every resource is listed here: load() reads them all at startup, and /static/
    // serves only names on this list, never an arbitrary path.
    static final List<String> TEMPLATES = List.of("index.html", "status.html", "areas.html", "monitoring.html", "message.html");
    static final List<String> ASSETS = List.of("app.css", "app.js", "icon.svg");
    // Content-Type by file extension.
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml");

    // Filled once by load() and only read afterwards, so concurrent requests share them safely.
    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, Asset> assets = new HashMap<>();
    private final String demoCredit;

    private Pages(String demoCredit) {
        this.demoCredit = demoCredit;
    }

    /**
     * Reads every page up front, so a missing resource fails startup rather than a customer's request.
     * {@code demoCredit} is an optional footer line (who built the demo, and for whom), set per deployment.
     */
    static Pages load(String demoCredit) throws IOException {
        Pages p = new Pages(demoCredit.isBlank() ? "" : "<p class=\"fine demo-credit\">" + esc(demoCredit) + "</p>");
        for (String name : TEMPLATES) p.templates.put(name, new String(read(name), StandardCharsets.UTF_8));
        for (String name : ASSETS) p.assets.put(name, new Asset(TYPES.get(name.substring(name.lastIndexOf('.') + 1)), read(name)));
        return p;
    }

    /** One resource from the jar's classpath; fails with the resource name if it is missing. */
    private static byte[] read(String name) throws IOException {
        try (InputStream in = Pages.class.getResourceAsStream("/outage/web/" + name)) {
            if (in == null) throw new IOException("missing resource /outage/web/" + name);
            return in.readAllBytes();
        }
    }

    /** A static asset by file name; empty for anything not in ASSETS. */
    Optional<Asset> asset(String name) {
        return Optional.ofNullable(assets.get(name));
    }

    /** Fills {{key}} placeholders. Values are inserted as HTML, so callers escape anything that came from a request. */
    String render(String template, Map<String, String> html) {
        String out = templates.get(template);
        for (Map.Entry<String, String> e : html.entrySet()) out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        return out.replace("{{demoCredit}}", demoCredit);
    }

    /** Escapes the five HTML-significant characters, so a value is safe in text and in quoted attributes. */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
