package shortlink;

/**
 * Registered as {@code shortlink:type=Stats}. Numeric attributes for anything
 * LogicMonitor should graph or threshold; the String ones are for humans
 * browsing with jconsole.
 */
public interface StatsMBean {
    String getStatus();

    /** 0 = UP, 1 = DEGRADED, 2 = DOWN. */
    int getStatusCode();

    long getUptimeSeconds();

    int getLinks();

    long getRequestsTotal();

    long getErrorsTotal();

    long getClientErrorsTotal();

    double getP95LatencyMs();

    double getErrorRatePct();

    /** 1 if the store can take writes, else 0. */
    int getStoreOk();

    String getChaosMode();

    /** 0 = off, 1 = latency, 2 = errors, 3 = store. */
    int getChaosModeCode();
}
