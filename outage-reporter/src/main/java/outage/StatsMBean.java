package outage;

/**
 * Registered as {@code outagereporter:type=Stats}. Numeric attributes for
 * anything LogicMonitor should graph or threshold; the String ones are for
 * humans browsing with jconsole.
 *
 * <p>The name ends in MBean because that is the JMX Standard MBean convention:
 * each getter on Stats is exposed as a read-only attribute (getP95LatencyMs
 * becomes P95LatencyMs). Outage_Reporter_JMX maps every numeric attribute to
 * a datapoint, collecting the *Total counters as derive (per-second rates).
 */
public interface StatsMBean {
    String getStatus();

    /** 0 = UP, 1 = DEGRADED, 2 = DOWN. */
    int getStatusCode();

    long getUptimeSeconds();

    int getTickets();

    long getRequestsTotal();

    long getErrorsTotal();

    long getClientErrorsTotal();

    double getP95LatencyMs();

    double getErrorRatePct();

    /** 1 if the store can take writes, else 0. */
    int getStoreOk();

    String getChaosMode();

    /** 0 = off, 1 = latency, 2 = errors, 3 = store, 4 = storm. */
    int getChaosModeCode();

    long getReportsTotal();

    long getReportsLastMinute();

    long getFailedReportsLastMinute();

    int getOpenOutages();

    long getCustomersAffected();

    int getAwaitingTriage();

    double getOldestOpenMinutes();

    int getOverdueOutages();

    /** 1 while report volume is at storm level, else 0. */
    int getStormMode();
}
