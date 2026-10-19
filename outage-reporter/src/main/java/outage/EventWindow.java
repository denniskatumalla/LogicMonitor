package outage;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Counts events over the last N seconds, in one-second buckets. Cheap at any
 * rate, which matters during a storm surge.
 * <p>OutageService uses two 60-second instances, which become the
 * reportsLastMinute and failedReportsLastMinute datapoints.
 */
final class EventWindow {

    // second[i] is the epoch second slot i holds and count[i] its events.
    // Long.MIN_VALUE marks a slot never used.
    private final long[] second;
    private final long[] count;
    private final LongSupplier clock;

    EventWindow(int seconds, LongSupplier clockMs) {
        this.second = new long[seconds];
        this.count = new long[seconds];
        this.clock = clockMs;
        Arrays.fill(second, Long.MIN_VALUE);
    }

    // Adds n events to the current second, first resetting the slot if it still
    // holds a second from an earlier pass round the ring.
    synchronized void add(int n) {
        long s = Math.floorDiv(clock.getAsLong(), 1000L);
        int i = Math.floorMod(s, second.length);
        if (second[i] != s) {
            second[i] = s;
            count[i] = 0;
        }
        count[i] += n;
    }

    /** Events in the current second and the N-1 before it. */
    synchronized long sum() {
        long now = Math.floorDiv(clock.getAsLong(), 1000L);
        long total = 0;
        for (int i = 0; i < second.length; i++) {
            if (second[i] > now - second.length && second[i] <= now) total += count[i];
        }
        return total;
    }
}
