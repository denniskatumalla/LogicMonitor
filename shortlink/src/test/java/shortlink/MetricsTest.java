package shortlink;

import java.util.concurrent.atomic.AtomicLong;

import static shortlink.Assert.eq;

class MetricsTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Metrics metrics = new Metrics(60, now::get);

    @TestRunner.Test
    void emptyWindowIsZeroNotNaN() {
        eq(new Metrics.Window(0, 0, 0), metrics.window());
    }

    @TestRunner.Test
    void p95IsNearestRank() {
        for (int i = 1; i <= 100; i++) metrics.record(200, i);
        eq(95.0, metrics.window().p95LatencyMs());
        metrics.record(200, 1000);
        eq("101 samples: rank ceil(95.95) = 96", 96.0, metrics.window().p95LatencyMs());
    }

    @TestRunner.Test
    void countsServerAndClientErrorsSeparately() {
        metrics.record(200, 1);
        metrics.record(302, 1);
        metrics.record(404, 1);
        metrics.record(400, 1);
        metrics.record(500, 1);
        metrics.record(503, 1);
        eq(6L, metrics.requestsTotal());
        eq(2L, metrics.errorsTotal());
        eq(2L, metrics.clientErrorsTotal());
        eq("only 5xx count towards the error rate", 33.3, metrics.window().errorRatePct());
    }

    @TestRunner.Test
    void oldSamplesLeaveTheWindowButCountersKeepThem() {
        metrics.record(500, 2000);
        now.addAndGet(61_000);
        metrics.record(200, 10);
        Metrics.Window w = metrics.window();
        eq(1, w.requests());
        eq(10.0, w.p95LatencyMs());
        eq(0.0, w.errorRatePct());
        eq(2L, metrics.requestsTotal());
        eq(1L, metrics.errorsTotal());
    }

    @TestRunner.Test
    void ringKeepsTheMostRecentSamples() {
        for (int i = 0; i < Metrics.CAPACITY; i++) metrics.record(200, 5000);
        for (int i = 0; i < Metrics.CAPACITY; i++) metrics.record(200, 1);
        eq(Metrics.CAPACITY, metrics.window().requests());
        eq(1.0, metrics.window().p95LatencyMs());
        eq((long) Metrics.CAPACITY * 2, metrics.requestsTotal());
    }
}
