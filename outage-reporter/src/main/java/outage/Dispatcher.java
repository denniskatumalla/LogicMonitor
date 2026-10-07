package outage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Moves tickets through their life on a timer, the way an outage management
 * system and its dispatchers would, compressed from hours into minutes:
 *
 * <ul>
 *   <li>REPORTED → CONFIRMED: oldest first, once a report is at least
 *       triageDelaySeconds old, and at most triagePerMinute of them. Sets the
 *       customers affected and the first ETR (doubled under storm response).
 *   <li>CONFIRMED → CREW_ASSIGNED: a quarter of the way to the ETR. One ETR
 *       in four slips here, by 25–50 %.
 *   <li>CREW_ASSIGNED → RESTORED: at the ETR.
 * </ul>
 *
 * Triage capacity is the deliberate bottleneck. Reports arriving faster than
 * it build a backlog (awaitingTriage), and that backlog is what the storm
 * alert watches. Runs on one thread and is the only writer of existing
 * tickets; a failed write pauses it until the store recovers.
 */
final class Dispatcher {

    static final double SLIP_CHANCE = 0.25;

    private final OutageService service;
    private final Config config;
    private final RandomGenerator random;
    private double budget;
    private long lastTick;
    private boolean paused;

    Dispatcher(OutageService service, Config config, RandomGenerator random, long startMs) {
        this.service = service;
        this.config = config;
        this.random = random;
        this.lastTick = startMs;
    }

    void tick(long now) {
        double perMs = config.triagePerMinute() / 60_000.0;
        // Unused capacity carries over for two ticks at most: an idle dispatcher can't bank a burst.
        double cap = Math.max(1, perMs * 2 * config.dispatchIntervalMs());
        budget = Math.min(budget + perMs * Math.max(0, now - lastTick), cap);
        lastTick = now;
        try {
            boolean storm = service.stormMode();
            List<Ticket> waiting = new ArrayList<>();
            for (Ticket t : service.store().open()) {
                switch (t.status()) {
                    case REPORTED -> {
                        if (!t.counts()) move(t.advance(Ticket.Status.RESTORED, now, 0, 0, 0));
                        else if (now - t.reportedAt() >= config.triageDelaySeconds() * 1000L) waiting.add(t);
                    }
                    case CONFIRMED -> {
                        if (now >= t.confirmedAt() + (t.etrAt() - t.confirmedAt()) / 4) assignCrew(t, now);
                    }
                    case CREW_ASSIGNED -> {
                        if (now >= t.etrAt()) {
                            move(t.advance(Ticket.Status.RESTORED, now, t.etrAt(), t.customersAffected(), t.etrRevisions()));
                        }
                    }
                    case RESTORED -> { }
                }
            }
            waiting.sort(Comparator.comparingLong(Ticket::reportedAt));
            for (Ticket t : waiting) {
                if (budget < 1) break;
                confirm(t, now, storm);
                budget--;
            }
            if (paused) {
                paused = false;
                Log.info("dispatcher_resumed");
            }
        } catch (IOException e) {
            if (!paused) {
                paused = true;
                Log.error("dispatcher_paused", "error", e.getMessage());
            }
        }
    }

    private void confirm(Ticket t, long now, boolean storm) throws IOException {
        double minutes = config.restoreMinutes() * (0.75 + 0.5 * random.nextDouble()) * (storm ? 2 : 1);
        long etr = now + Math.round(minutes * 60_000);
        move(t.advance(Ticket.Status.CONFIRMED, now, etr, customers(), 0));
    }

    private void assignCrew(Ticket t, long now) throws IOException {
        long etr = t.etrAt();
        int revisions = t.etrRevisions();
        if (random.nextDouble() < SLIP_CHANCE) {
            etr += Math.round((etr - t.confirmedAt()) * (0.25 + 0.25 * random.nextDouble()));
            revisions++;
        }
        move(t.advance(Ticket.Status.CREW_ASSIGNED, now, etr, t.customersAffected(), revisions));
    }

    private void move(Ticket next) throws IOException {
        service.store().update(next);
        if (next.source() == Ticket.Source.WEB) {
            Log.info("ticket_status", "id", next.id(), "status", next.status().key(), "area", next.areaId(),
                    "etr", OutageService.iso(next.etrAt()), "etrRevisions", next.etrRevisions(),
                    "customers", next.customersAffected());
        }
    }

    /** Most outages are one transformer; some a lateral; a few a whole feeder. */
    private int customers() {
        double r = random.nextDouble();
        if (r < 0.70) return 1 + random.nextInt(12);
        if (r < 0.95) return 20 + random.nextInt(131);
        return 300 + random.nextInt(2201);
    }
}
