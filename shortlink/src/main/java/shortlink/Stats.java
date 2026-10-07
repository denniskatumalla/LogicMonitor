package shortlink;

import java.util.function.Supplier;

/** JMX view of the same snapshot /health serves. Each read takes a fresh one. */
public final class Stats implements StatsMBean {
    static final String OBJECT_NAME = "shortlink:type=Stats";

    private final Supplier<Health> health;

    Stats(Supplier<Health> health) {
        this.health = health;
    }

    @Override public String getStatus() { return health.get().status().name(); }
    @Override public int getStatusCode() { return health.get().status().code(); }
    @Override public long getUptimeSeconds() { return health.get().uptimeSeconds(); }
    @Override public int getLinks() { return health.get().links(); }
    @Override public long getRequestsTotal() { return health.get().requestsTotal(); }
    @Override public long getErrorsTotal() { return health.get().errorsTotal(); }
    @Override public long getClientErrorsTotal() { return health.get().clientErrorsTotal(); }
    @Override public double getP95LatencyMs() { return health.get().p95LatencyMs(); }
    @Override public double getErrorRatePct() { return health.get().errorRatePct(); }
    @Override public int getStoreOk() { return health.get().storeOk() ? 1 : 0; }
    @Override public String getChaosMode() { return health.get().chaosMode().label(); }
    @Override public int getChaosModeCode() { return health.get().chaosMode().code(); }
}
