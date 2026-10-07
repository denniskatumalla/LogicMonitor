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

    record Asset(String contentType, byte[] body) { }

    static final List<String> TEMPLATES = List.of("index.html", "status.html", "areas.html", "message.html");
    static final List<String> ASSETS = List.of("app.css", "app.js", "icon.svg");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml");

    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, Asset> assets = new HashMap<>();

    private Pages() { }

    /** Reads every page up front, so a missing resource fails startup rather than a customer's request. */
    static Pages load() throws IOException {
        Pages p = new Pages();
        for (String name : TEMPLATES) p.templates.put(name, new String(read(name), StandardCharsets.UTF_8));
        for (String name : ASSETS) p.assets.put(name, new Asset(TYPES.get(name.substring(name.lastIndexOf('.') + 1)), read(name)));
        return p;
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream in = Pages.class.getResourceAsStream("/outage/web/" + name)) {
            if (in == null) throw new IOException("missing resource /outage/web/" + name);
            return in.readAllBytes();
        }
    }

    Optional<Asset> asset(String name) {
        return Optional.ofNullable(assets.get(name));
    }

    /** Fills {{key}} placeholders. Values are inserted as HTML, so callers escape anything that came from a request. */
    String render(String template, Map<String, String> html) {
        String out = templates.get(template);
        for (Map.Entry<String, String> e : html.entrySet()) out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        return out;
    }

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
