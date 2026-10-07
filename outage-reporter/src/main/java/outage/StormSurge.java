package outage;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Chaos mode "storm": a reporting surge generated in-process. The report rate
 * ramps from zero to stormReportsPerSecond over stormRampSeconds and holds
 * until the mode changes. Reports go through {@link OutageService} like a
 * customer's but not through HTTP, so the technical datapoints stay clean and
 * only the business ones move. Most of them land on the two coastal areas.
 */
final class StormSurge {

    private static final long LOG_EVERY_MS = 10_000;
    private static final List<String> LANDFALL = List.of("edwin", "envision");

    private final OutageService service;
    private final Chaos chaos;
    private final Config config;
    private final RandomGenerator random;
    private long startedAt;
    private long lastTick;
    private long lastLog;
    private double owed;
    private int sinceLog;
    private int total;

    StormSurge(OutageService service, Chaos chaos, Config config, RandomGenerator random) {
        this.service = service;
        this.chaos = chaos;
        this.config = config;
        this.random = random;
    }

    void tick(long now) {
        if (!chaos.is(Chaos.Mode.STORM)) {
            if (startedAt != 0) {
                Log.warn("storm_surge_ended", "reports", total);
                startedAt = 0;
                owed = 0;
                total = 0;
            }
            return;
        }
        if (startedAt == 0) {
            startedAt = lastTick = lastLog = now;
            sinceLog = 0;
            Log.warn("storm_surge_started", "peakReportsPerSecond", config.stormReportsPerSecond(),
                    "rampSeconds", config.stormRampSeconds());
        }
        double ramp = config.stormRampSeconds() <= 0 ? 1 : Math.min(1, (now - startedAt) / (config.stormRampSeconds() * 1000.0));
        double rate = config.stormReportsPerSecond() * ramp;
        owed += rate * (now - lastTick) / 1000.0;
        lastTick = now;
        try {
            for (; owed >= 1; owed--) {
                service.report(randomReport(), Ticket.Source.STORM);
                sinceLog++;
                total++;
            }
        } catch (IOException e) {
            owed = 0; // a broken store drops the surge rather than queueing it
        }
        if (now - lastLog >= LOG_EVERY_MS) {
            Log.info("storm_surge", "reportsPerSecond", String.format(Locale.ROOT, "%.1f", rate),
                    "reports", sinceLog, "total", total);
            sinceLog = 0;
            lastLog = now;
        }
    }

    private Report randomReport() {
        double r = random.nextDouble();
        String areaId = r < 0.45 ? LANDFALL.get(0) : r < 0.75 ? LANDFALL.get(1)
                : Territory.AREAS.get(2 + random.nextInt(Territory.AREAS.size() - 2)).id();
        Territory.Area a = Territory.byId(areaId).orElseThrow();
        int zip = a.zipFrom() + random.nextInt(a.zipTo() - a.zipFrom() + 1);
        return new Report(String.format("%05d", zip), "", "", "");
    }
}
