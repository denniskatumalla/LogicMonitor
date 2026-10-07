package outage;

import java.util.LinkedHashMap;
import java.util.Map;

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
     */
    static Status classify(boolean storeOk, Metrics.Window w, Config c) {
        if (!storeOk) return Status.DOWN;
        if (w.p95LatencyMs() > c.degradedP95Ms()) return Status.DEGRADED;
        if (w.requests() >= MIN_REQUESTS_FOR_ERROR_RATE && w.errorRatePct() > c.degradedErrorPercent()) {
            return Status.DEGRADED;
        }
        return Status.UP;
    }

    int httpStatus() {
        return status == Status.DOWN ? 503 : 200;
    }

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
