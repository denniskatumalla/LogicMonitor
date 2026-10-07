package outage;

import java.util.Map;

import static outage.Assert.eq;

class HealthTest {

    private static final OutageService.Kpis QUIET = new OutageService.Kpis(0, 0, 0, 0, 0, 0, 0, 0, false);
    private final Config config = Config.fromEnv(Map.of());

    private Health.Status classify(boolean storeOk, int requests, double p95, double errorPct) {
        return Health.classify(storeOk, new Metrics.Window(requests, p95, errorPct), config);
    }

    @TestRunner.Test
    void defaults() {
        eq(500, config.degradedP95Ms());
        eq(5.0, config.degradedErrorPercent());
    }

    @TestRunner.Test
    void upWhenFastAndClean() {
        eq(Health.Status.UP, classify(true, 0, 0, 0));
        eq(Health.Status.UP, classify(true, 100, 500, 5));
    }

    @TestRunner.Test
    void degradedWhenSlowOrFailing() {
        eq(Health.Status.DEGRADED, classify(true, 100, 501, 0));
        eq(Health.Status.DEGRADED, classify(true, 100, 10, 5.1));
    }

    @TestRunner.Test
    void errorRateIgnoredOnTooFewRequests() {
        eq(Health.Status.UP, classify(true, Health.MIN_REQUESTS_FOR_ERROR_RATE - 1, 10, 50));
        eq(Health.Status.DEGRADED, classify(true, Health.MIN_REQUESTS_FOR_ERROR_RATE, 10, 50));
    }

    @TestRunner.Test
    void downWhenStoreFailsWhateverElse() {
        eq(Health.Status.DOWN, classify(false, 100, 1, 0));
        eq(Health.Status.DOWN, classify(false, 100, 5000, 90));
    }

    @TestRunner.Test
    void onlyDownIs503() {
        Health h = new Health(Health.Status.DEGRADED, 1, 0, 0, 0, 0, 60, 0, 0, 0, true, Chaos.Mode.OFF, QUIET);
        eq(200, h.httpStatus());
        eq(503, new Health(Health.Status.DOWN, 1, 0, 0, 0, 0, 60, 0, 0, 0, false, Chaos.Mode.STORE, QUIET).httpStatus());
    }

    @TestRunner.Test
    void businessFiguresAreFlatFieldsBesideTheTechnicalOnes() {
        OutageService.Kpis storm = new OutageService.Kpis(900, 360, 2, 750, 41_000, 240, 3.5, 1, true);
        Map<String, Object> j = new Health(Health.Status.UP, 1, 800, 10, 0, 0, 60, 10, 2, 0, true, Chaos.Mode.STORM, storm).toJson();
        eq("a storm is not a technical fault", "UP", j.get("status"));
        eq("storm", j.get("chaosMode"));
        eq(360L, j.get("reportsLastMinute"));
        eq(240, j.get("awaitingTriage"));
        eq(41_000L, j.get("customersAffected"));
        eq(true, j.get("stormMode"));
    }
}
