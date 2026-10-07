package outage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static outage.Assert.eq;
import static outage.Assert.ok;
import static outage.EndToEndTest.base;
import static outage.EndToEndTest.get;
import static outage.EndToEndTest.health;
import static outage.EndToEndTest.json;
import static outage.EndToEndTest.postForm;
import static outage.EndToEndTest.start;

/** The customer pages over HTTP, including the no-JavaScript form POST that LogicMonitor's web checks use. */
class PagesTest {

    private static final Pattern TICKET = Pattern.compile("id=\"ticket-id\">(EPL-[A-Z0-9]{6})<");

    static void beforeAll() throws Exception {
        EndToEndTest.beforeAll();
    }

    static void afterAll() throws IOException {
        EndToEndTest.afterAll();
    }

    @TestRunner.Test
    void everyPageIsServedWithTheFormFieldsAndSafeHeaders() throws Exception {
        try (OutageServer s = start("pages")) {
            for (String path : List.of("/", "/status", "/areas", "/monitoring")) {
                HttpResponse<String> r = get(base(s) + path);
                eq(path, 200, r.statusCode());
                eq(path, "text/html; charset=utf-8", r.headers().firstValue("Content-Type").orElse(null));
                ok(path + " has a CSP", r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
                ok(path + " has no unfilled placeholder", !r.body().contains("{{"));
                ok(path + " hides the storm banner when calm", r.body().contains("id=\"storm-banner\" role=\"status\" hidden"));
                ok(path + " says the utility is fictional", r.body().contains("Example Power &amp; Light is a fictional utility"));
                ok(path + " says it is a LogicMonitor demo", r.body().contains("LogicMonitor demo") && r.body().contains("href=\"/monitoring\""));
                ok(path + " has no credit line unless one is configured", !r.body().contains("demo-credit"));
            }
            String home = get(base(s) + "/").body();
            for (String field : List.of("name=\"zip\"", "name=\"address\"", "name=\"phone\"", "name=\"notes\"",
                    "<form id=\"report-form\" method=\"post\" action=\"/report\">")) {
                ok("report form has " + field, home.contains(field));
            }
            eq("HEAD works on pages", 200, EndToEndTest.client.send(
                    HttpRequest.newBuilder(URI.create(base(s) + "/areas")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @TestRunner.Test
    void theDeploymentCreditIsEscapedIntoEveryFooter() throws Exception {
        try (OutageServer s = start("credit", "OUTAGE_DEMO_CREDIT", "Built for <the> demo & team")) {
            for (String path : List.of("/", "/status", "/areas", "/monitoring")) {
                String body = get(base(s) + path).body();
                ok(path + " shows the escaped credit", body.contains("<p class=\"fine demo-credit\">Built for &lt;the&gt; demo &amp; team</p>"));
            }
        }
    }

    @TestRunner.Test
    void staticAssetsAreServedFromTheJarWithTheirTypes() throws Exception {
        try (OutageServer s = start("assets")) {
            Map<String, String> types = Map.of("app.css", "text/css; charset=utf-8",
                    "app.js", "text/javascript; charset=utf-8", "icon.svg", "image/svg+xml");
            types.forEach((name, type) -> {
                try {
                    HttpResponse<String> r = get(base(s) + "/static/" + name);
                    eq(name, 200, r.statusCode());
                    eq(name, type, r.headers().firstValue("Content-Type").orElse(null));
                    eq(name, "nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null));
                } catch (Exception e) {
                    throw new AssertionError(name, e);
                }
            });
            eq("unknown asset", 404, get(base(s) + "/static/missing.js").statusCode());
            eq("no path tricks", 404, get(base(s) + "/static/../outage/Main.class").statusCode());
            HttpResponse<String> missing = get(base(s) + "/no-such-page");
            eq(404, missing.statusCode());
            ok("a 404 is a page, not JSON", missing.body().contains("Page not found"));
        }
    }

    @TestRunner.Test
    void pagesFetchNothingFromAnywhereElse() throws IOException {
        for (String name : List.of("index.html", "status.html", "areas.html", "monitoring.html", "message.html", "app.css", "app.js", "icon.svg")) {
            String text;
            try (InputStream in = Pages.class.getResourceAsStream("/outage/web/" + name)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String scrubbed = text.replace("xmlns=\"http://www.w3.org/2000/svg\"", "");
            ok(name + " references no external URL", !scrubbed.contains("http://") && !scrubbed.contains("https://")
                    && !scrubbed.contains("src=\"//") && !scrubbed.contains("href=\"//"));
            ok(name + " says the utility is fictional", !name.endsWith(".html") || text.contains("fictional"));
        }
    }

    @TestRunner.Test
    void theFormPostWorksWithoutJavaScript() throws Exception {
        try (OutageServer s = start("form")) {
            HttpResponse<String> r = postForm(s, "zip=00012&address=12+Bay+St&phone=555-010-0123&notes=Lights+out+%26+a+bang");
            eq(200, r.statusCode());
            ok("confirmation page", r.body().contains("<h1>Report received</h1>"));
            Matcher m = TICKET.matcher(r.body());
            ok("shows the ticket number", m.find());
            String id = m.group(1);
            ok("links to its status", r.body().contains("href=\"/status?ticket=" + id + "\""));
            Map<?, ?> t = json(get(base(s) + "/api/reports/" + id));
            eq("12 Bay St", t.get("address"));
            eq(1.0, health(s).get("reportsTotal"));
        }
    }

    @TestRunner.Test
    void aBadFormPostIsAnHtmlPageThatSaysWhatToFix() throws Exception {
        try (OutageServer s = start("form-bad")) {
            HttpResponse<String> r = postForm(s, "zip=70112");
            eq(400, r.statusCode());
            ok(r.body(), r.body().contains("ZIP 70112 isn&#39;t in our service area."));
            ok("links back to the form", r.body().contains("<a href=\"/\">Back to the report form</a>"));
            eq("GET is not a submission", 405, get(base(s) + "/report").statusCode());
        }
    }

    @TestRunner.Test
    void aSyntheticCheckGoesThroughTheWholePathButCountsForNothing() throws Exception {
        try (OutageServer s = start("synthetic", "OUTAGE_DISPATCH_INTERVAL_MS", "20")) {
            ok("step 1: the page", get(base(s) + "/").body().contains("Report a power outage"));
            HttpResponse<String> r = postForm(s, "zip=" + Territory.TEST_ZIP + "&notes=LogicMonitor+web+check");
            eq("step 2: the form", 200, r.statusCode());
            ok(r.body().contains("Report received"));
            Matcher m = TICKET.matcher(r.body());
            ok(m.find());
            EndToEndTest.waitFor("synthetic ticket closed", () -> {
                try {
                    return "restored".equals(json(get(base(s) + "/api/reports/" + m.group(1))).get("status"));
                } catch (Exception e) {
                    return false;
                }
            });
            Map<?, ?> h = health(s);
            eq(0.0, h.get("reportsTotal"));
            eq(0.0, h.get("openOutages"));
            eq("but it was stored", 1.0, h.get("tickets"));
        }
    }

    @TestRunner.Test
    void theStormBannerShowsOnEveryPageDuringAStorm() throws Exception {
        try (OutageServer s = start("banner", "OUTAGE_STORM_THRESHOLD_PER_MINUTE", "2")) {
            EndToEndTest.report(s, "00012");
            EndToEndTest.report(s, "00012");
            for (String path : List.of("/", "/status", "/areas")) {
                String body = get(base(s) + path).body();
                ok(path, body.contains("id=\"storm-banner\" role=\"status\" >") && body.contains("Storm response in effect."));
            }
            eq(true, json(get(base(s) + "/api/areas")).get("stormMode"));
        }
    }

    @TestRunner.Test
    void escapesWhatItEchoes() {
        eq("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;", Pages.esc("<script>alert(\"x\")</script> & '"));
    }
}
