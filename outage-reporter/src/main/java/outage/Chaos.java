package outage;

import java.util.Locale;

/** Fault injection for break/fix demos. One mode at a time. */
final class Chaos {

    enum Mode {
        OFF, LATENCY, ERRORS, STORE, STORM;

        static final String OPTIONS = "off|latency|errors|store|storm";

        /** Numeric form for JMX and the LogicMonitor DataSource. */
        int code() {
            return ordinal();
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Mode parse(String s) {
            if (s == null) throw new IllegalArgumentException("mode is required: " + OPTIONS);
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown mode '" + s + "': " + OPTIONS);
            }
        }
    }

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
