package outage;

import java.util.LinkedHashMap;
import java.util.Map;

/*
 * Built by OutageServer.health() for every /health request and every JMX
 * attribute read. The LogicMonitor DataSource Outage_Reporter_Health turns
 * the JSON fields into datapoints; Outage_Reporter_JMX reads the same values
 * through the Stats MBean.
 */
/** A point-in-time health snapshot, as served on /health and over JMX. */
record Health(
        Status status,
        long uptimeSeconds,
        int tickets,
        long requestsTotal,
        long errorsTotal,
        long clientErrorsTotal,
        int windowSeconds,
        int windowRequests,
        double p95LatencyMs,
        double errorRatePct,
        boolean storeOk,
        Chaos.Mode chaosMode,
        OutageService.Kpis kpis) {

    // code() is the JMX StatusCode attribute: 0 = UP, 1 = DEGRADED, 2 = DOWN.
    // The collection script maps the name to the same numbers, so keep this order.
    enum Status {
        UP, DEGRADED, DOWN;

        int code() {
            return ordinal();
        }
    }

    /** Below this many requests in the window, the error rate is too noisy to act on. */
    static final int MIN_REQUESTS_FOR_ERROR_RATE = 10;

    /**
     * DOWN: the store cannot take writes, so reports would be lost. DEGRADED:
     * serving, but slow or failing too often. UP otherwise. Business load
     * (a storm surge) never changes this: a busy service that is answering
     * quickly is healthy, and the business datapoints say the rest.
     * Thresholds come from Config: OUTAGE_DEGRADED_P95_MS (default 500 ms) and
     * OUTAGE_DEGRADED_ERROR_PERCENT (default 5 %), measured over the rolling
     * window (OUTAGE_WINDOW_SECONDS, default 60 s). They match the warning
     * thresholds on the p95LatencyMs and errorRatePct datapoints.
     */
    static Status classify(boolean storeOk, Metrics.Window w, Config c) {
        if (!storeOk) return Status.DOWN;
        if (w.p95LatencyMs() > c.degradedP95Ms()) return Status.DEGRADED;
        if (w.requests() >= MIN_REQUESTS_FOR_ERROR_RATE && w.errorRatePct() > c.degradedErrorPercent()) {
            return Status.DEGRADED;
        }
        return Status.UP;
    }

    // 503 only for DOWN, so even a plain HTTP check sees the outage. The body is
    // still a full health document, which the collection script records as reachable=1.
    int httpStatus() {
        return status == Status.DOWN ? 503 : 200;
    }

    // The /health JSON body. Each numeric field is printed by the collection script
    // under the same name (p95LatencyMs, errorRatePct, awaitingTriage, customersAffected...);
    // booleans become 1/0, status and chaosMode become numeric codes. tickets, reportsTotal,
    // windowSeconds, windowRequests and version are for people reading the endpoint.
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.name());
        m.put("uptimeSeconds", uptimeSeconds);
        m.put("requestsTotal", requestsTotal);
        m.put("errorsTotal", errorsTotal);
        m.put("clientErrorsTotal", clientErrorsTotal);
        m.put("windowSeconds", windowSeconds);
        m.put("windowRequests", windowRequests);
        m.put("p95LatencyMs", p95LatencyMs);
        m.put("errorRatePct", errorRatePct);
        m.put("storeOk", storeOk);
        m.put("chaosMode", chaosMode.label());
        m.put("tickets", tickets);
        m.put("reportsTotal", kpis.reportsTotal());
        m.put("reportsLastMinute", kpis.reportsLastMinute());
        m.put("failedReportsLastMinute", kpis.failedReportsLastMinute());
        m.put("openOutages", kpis.openOutages());
        m.put("customersAffected", kpis.customersAffected());
        m.put("awaitingTriage", kpis.awaitingTriage());
        m.put("oldestOpenMinutes", kpis.oldestOpenMinutes());
        m.put("overdueOutages", kpis.overdueOutages());
        m.put("stormMode", kpis.stormMode());
        m.put("version", Main.VERSION);
        return m;
    }
}
