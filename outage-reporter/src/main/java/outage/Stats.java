package outage;

import java.util.function.Supplier;

/** JMX view of the same snapshot /health serves. Each read takes a fresh one. */
public final class Stats implements StatsMBean {
    static final String OBJECT_NAME = "outagereporter:type=Stats";

    private final Supplier<Health> health;

    Stats(Supplier<Health> health) {
        this.health = health;
    }

    @Override public String getStatus() { return health.get().status().name(); }
    @Override public int getStatusCode() { return health.get().status().code(); }
    @Override public long getUptimeSeconds() { return health.get().uptimeSeconds(); }
    @Override public int getTickets() { return health.get().tickets(); }
    @Override public long getRequestsTotal() { return health.get().requestsTotal(); }
    @Override public long getErrorsTotal() { return health.get().errorsTotal(); }
    @Override public long getClientErrorsTotal() { return health.get().clientErrorsTotal(); }
    @Override public double getP95LatencyMs() { return health.get().p95LatencyMs(); }
    @Override public double getErrorRatePct() { return health.get().errorRatePct(); }
    @Override public int getStoreOk() { return health.get().storeOk() ? 1 : 0; }
    @Override public String getChaosMode() { return health.get().chaosMode().label(); }
    @Override public int getChaosModeCode() { return health.get().chaosMode().code(); }
    @Override public long getReportsTotal() { return health.get().kpis().reportsTotal(); }
    @Override public long getReportsLastMinute() { return health.get().kpis().reportsLastMinute(); }
    @Override public long getFailedReportsLastMinute() { return health.get().kpis().failedReportsLastMinute(); }
    @Override public int getOpenOutages() { return health.get().kpis().openOutages(); }
    @Override public long getCustomersAffected() { return health.get().kpis().customersAffected(); }
    @Override public int getAwaitingTriage() { return health.get().kpis().awaitingTriage(); }
    @Override public double getOldestOpenMinutes() { return health.get().kpis().oldestOpenMinutes(); }
    @Override public int getOverdueOutages() { return health.get().kpis().overdueOutages(); }
    @Override public int getStormMode() { return health.get().kpis().stormMode() ? 1 : 0; }
}
