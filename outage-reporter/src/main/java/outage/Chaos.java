package outage;

import java.util.Locale;

/** Fault injection for break/fix demos. One mode at a time. */
// Switched at runtime by POST /admin/chaos?mode=... on the loopback admin listener,
// which the chaos command on the VM calls. Request handling, the store and the storm generator read it.
final class Chaos {

    enum Mode {
        // OFF: normal service.
        // LATENCY: dynamic requests (/api/*, POST /report) sleep chaosLatencyMs (1500 ms) first.
        // ERRORS: chaosErrorPercent (50 %) of dynamic requests return 500.
        // STORE: ticket writes fail, so reports get 503, the dispatcher stalls and /health is DOWN.
        // STORM: StormSurge files a surge of reports; the service stays UP and awaitingTriage climbs.
        // Pages and static assets are exempt from LATENCY and ERRORS. The numeric code is the
        // position in this list, so new modes go at the end to keep the DataSource's mapping.
        OFF, LATENCY, ERRORS, STORE, STORM;

        static final String OPTIONS = "off|latency|errors|store|storm";

        /** Numeric form for JMX and the LogicMonitor DataSource. */
        int code() {
            return ordinal();
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Case-insensitive, surrounding whitespace ignored; the error lists the valid modes. */
        static Mode parse(String s) {
            if (s == null) throw new IllegalArgumentException("mode is required: " + OPTIONS);
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown mode '" + s + "': " + OPTIONS);
            }
        }
    }

    // volatile: set by the admin handler thread, read by request threads and background tasks.
    private volatile Mode mode = Mode.OFF;

    Mode mode() {
        return mode;
    }

    void set(Mode m) {
        mode = m;
    }

    boolean is(Mode m) {
        return mode == m;
    }
}
