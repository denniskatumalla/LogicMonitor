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
 *
 * <p>Ticked every 200 ms by {@link OutageServer}. Each tick adds rate times
 * elapsed time to a running "owed" count and files that many whole reports,
 * so the average rate holds even though ticks are coarse. At the default
 * peak (6 a second, 360 a minute) reports outrun triage (180 a minute), so
 * awaitingTriage climbs. They also pass stormThresholdPerMinute, which
 * declares storm response and doubles new ETRs.
 */
final class StormSurge {

    // One progress log line every 10 s rather than one per report.
    private static final long LOG_EVERY_MS = 10_000;
    // The "coast": 45 % of storm reports land in edwin and 30 % in envision; the rest spread inland.
    private static final List<String> LANDFALL = List.of("edwin", "envision");

    private final OutageService service;
    private final Chaos chaos;
    private final Config config;
    private final RandomGenerator random;
    private long startedAt;
    private long lastTick;
    private long lastLog;
    // Reports due but not yet filed; the fractional part carries over to the next tick.
    private double owed;
    private int sinceLog;
    private int total;

    StormSurge(OutageService service, Chaos chaos, Config config, RandomGenerator random) {
        this.service = service;
        this.chaos = chaos;
        this.config = config;
        this.random = random;
    }

    /** Files the reports due since the last tick, and logs the surge's start, progress and end. */
    void tick(long now) {
        if (!chaos.is(Chaos.Mode.STORM)) {
            // The mode has moved off storm: log the total once and reset for the next surge.
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
        // Linear ramp from zero to full rate over stormRampSeconds (full rate at once if that is 0).
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

    /** A ZIP-only report in an area picked by the landfall weights, at a random ZIP within that area. */
    private Report randomReport() {
        double r = random.nextDouble();
        // Indexes 2 and up are the four inland areas: this relies on edwin and envision being first in AREAS.
        String areaId = r < 0.45 ? LANDFALL.get(0) : r < 0.75 ? LANDFALL.get(1)
                : Territory.AREAS.get(2 + random.nextInt(Territory.AREAS.size() - 2)).id();
        Territory.Area a = Territory.byId(areaId).orElseThrow();
        int zip = a.zipFrom() + random.nextInt(a.zipTo() - a.zipFrom() + 1);
        return new Report(String.format("%05d", zip), "", "", "");
    }
}
