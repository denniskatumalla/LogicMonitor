package shortlink;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Lifetime counters plus a sliding window of recent requests for p95 latency
 * and error rate. The window is a fixed ring: at very high request rates it
 * covers less than windowSeconds, which is fine for a health signal.
 */
final class Metrics {

    record Window(int requests, double p95LatencyMs, double errorRatePct) { }

    static final int CAPACITY = 4096;

    private final LongAdder requests = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();

    private final long[] stampMs = new long[CAPACITY];
    private final double[] latencyMs = new double[CAPACITY];
    private final boolean[] failed = new boolean[CAPACITY];
    private int next;
    private int size;

    private final long windowMs;
    private final LongSupplier clock;

    Metrics(int windowSeconds, LongSupplier clockMs) {
        this.windowMs = windowSeconds * 1000L;
        this.clock = clockMs;
    }

    void record(int status, double latency) {
        requests.increment();
        boolean serverError = status >= 500;
        if (serverError) serverErrors.increment();
        else if (status >= 400) clientErrors.increment();
        synchronized (this) {
            stampMs[next] = clock.getAsLong();
            latencyMs[next] = latency;
            failed[next] = serverError;
            next = (next + 1) % CAPACITY;
            if (size < CAPACITY) size++;
        }
    }

    long requestsTotal() {
        return requests.sum();
    }

    /** 5xx responses: failures of this service. */
    long errorsTotal() {
        return serverErrors.sum();
    }

    /** 4xx responses: bad input or unknown codes, not failures of this service. */
    long clientErrorsTotal() {
        return clientErrors.sum();
    }

    Window window() {
        long cutoff = clock.getAsLong() - windowMs;
        double[] recent;
        int n = 0;
        int errors = 0;
        synchronized (this) {
            recent = new double[size];
            for (int i = 0; i < size; i++) {
                if (stampMs[i] >= cutoff) {
                    recent[n++] = latencyMs[i];
                    if (failed[i]) errors++;
                }
            }
        }
        if (n == 0) return new Window(0, 0, 0);
        Arrays.sort(recent, 0, n);
        // Nearest-rank percentile.
        double p95 = recent[(int) Math.ceil(0.95 * n) - 1];
        return new Window(n, round1(p95), round1(100.0 * errors / n));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
