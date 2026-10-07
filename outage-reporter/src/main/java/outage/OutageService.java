package outage;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * The domain without HTTP: taking reports, looking them up, and the figures
 * the business runs on. The web handlers, the storm generator and the tests
 * all go through here, so they exercise the same path.
 */
final class OutageService {

    /** An open outage more than this far past its ETR counts as overdue. */
    static final long OVERDUE_GRACE_MS = 30_000;

    /** The business view, as served on /health and over JMX. Synthetic-check tickets are left out. */
    record Kpis(
            long reportsTotal,
            long reportsLastMinute,
            long failedReportsLastMinute,
            int openOutages,
            long customersAffected,
            int awaitingTriage,
            double oldestOpenMinutes,
            int overdueOutages,
            boolean stormMode) { }

    private final TicketStore store;
    private final Config config;
    private final LongSupplier clock;
    private final LongAdder reportsTotal = new LongAdder();
    private final EventWindow reports;
    private final EventWindow failedReports;

    OutageService(TicketStore store, Config config, LongSupplier clockMs) {
        this.store = store;
        this.config = config;
        this.clock = clockMs;
        this.reports = new EventWindow(60, clockMs);
        this.failedReports = new EventWindow(60, clockMs);
    }

    TicketStore store() {
        return store;
    }

    Ticket report(Report r, Ticket.Source source) throws IOException {
        Territory.Area area = Territory.forZip(r.zip())
                .orElseThrow(() -> new IllegalArgumentException("not in the territory: " + r.zip()));
        Ticket.Source s = area == Territory.TEST_AREA ? Ticket.Source.TEST : source;
        Ticket t = store.create(s, r, area.id(), clock.getAsLong());
        if (t.counts()) {
            reportsTotal.increment();
            reports.add(1);
        }
        return t;
    }

    /** A customer tried to report and we answered 5xx: the business face of an error. */
    void reportFailed() {
        failedReports.add(1);
    }

    /** Case-insensitive, with or without the EPL- prefix, as people type it. */
    Optional<Ticket> ticket(String raw) {
        if (raw == null) return Optional.empty();
        String id = raw.trim().toUpperCase(Locale.ROOT);
        if (!id.startsWith(TicketStore.PREFIX)) id = TicketStore.PREFIX + id;
        return id.matches("EPL-[A-Z0-9]{6}") ? store.get(id) : Optional.empty();
    }

    /** Storm response is declared on volume alone: reports in the last minute at or over the threshold. */
    boolean stormMode() {
        return reports.sum() >= config.stormThresholdPerMinute();
    }

    Kpis kpis() {
        long now = clock.getAsLong();
        Map<String, Tally> byArea = tallyByArea(null, now);
        Tally total = new Tally();
        byArea.forEach((id, t) -> total.add(t, cap(id)));
        long oldest = total.oldestReportedAt;
        double oldestMinutes = oldest == Long.MAX_VALUE ? 0 : Math.round((now - oldest) / 6000.0) / 10.0;
        return new Kpis(reportsTotal.sum(), reports.sum(), failedReports.sum(), total.open, total.customers,
                total.awaiting, oldestMinutes, total.overdue, stormMode());
    }

    // ---- JSON views ------------------------------------------------------

    Map<String, Object> ticketJson(Ticket t) {
        Territory.Area area = Territory.byId(t.areaId()).orElse(Territory.TEST_AREA);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("status", t.status().key());
        m.put("statusLabel", t.status().label());
        m.put("zip", t.zip());
        m.put("area", Map.of("id", area.id(), "name", area.name()));
        m.put("address", t.address().isEmpty() ? null : t.address());
        m.put("phone", Report.maskPhone(t.phone()));
        m.put("reportedAt", iso(t.reportedAt()));
        m.put("updatedAt", iso(t.updatedAt()));
        m.put("etr", iso(t.etrAt()));
        m.put("etrRevisions", t.etrRevisions());
        m.put("customersAffected", t.status() == Ticket.Status.REPORTED ? null : t.customersAffected());
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Ticket.Status s : Ticket.Status.values()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("status", s.key());
            step.put("label", s.label());
            step.put("at", iso(t.at(s)));
            timeline.add(step);
        }
        m.put("timeline", timeline);
        return m;
    }

    /** Area-level picture for one ZIP. Never lists other customers' tickets. */
    Map<String, Object> zipJson(String zip) {
        Territory.Area area = Territory.forZip(zip).orElseThrow();
        Tally t = tallyByArea(zip, clock.getAsLong()).getOrDefault(area.id(), new Tally());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zip", zip);
        m.put("area", Map.of("id", area.id(), "name", area.name()));
        m.put("openOutages", t.open);
        m.put("customersAffected", Math.min(t.customers, area.customersServed()));
        m.put("awaitingConfirmation", t.awaiting);
        m.put("crewsAssigned", t.crews);
        m.put("nextEtr", iso(t.nextEtr));
        m.put("latestEtr", iso(t.latestEtr));
        m.put("stormMode", stormMode());
        return m;
    }

    Map<String, Object> areasJson() {
        long now = clock.getAsLong();
        Map<String, Tally> byArea = tallyByArea(null, now);
        Tally total = new Tally();
        List<Map<String, Object>> areas = new ArrayList<>();
        for (Territory.Area a : Territory.AREAS) {
            Tally t = byArea.getOrDefault(a.id(), new Tally());
            total.add(t, a.customersServed());
            long customers = Math.min(t.customers, a.customersServed());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("name", a.name());
            m.put("zips", a.zipRange());
            m.put("customersServed", a.customersServed());
            m.put("openOutages", t.open);
            m.put("customersAffected", customers);
            m.put("pctAffected", pct(customers, a.customersServed()));
            m.put("crewsAssigned", t.crews);
            m.put("nextEtr", iso(t.nextEtr));
            areas.add(m);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("customersServed", Territory.customersServed());
        totals.put("openOutages", total.open);
        totals.put("customersAffected", total.customers);
        totals.put("pctAffected", pct(total.customers, Territory.customersServed()));
        totals.put("crewsAssigned", total.crews);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", iso(now));
        m.put("stormMode", stormMode());
        m.put("totals", totals);
        m.put("areas", areas);
        return m;
    }

    // ---- aggregation -----------------------------------------------------

    /** Running totals over open tickets. Customers are summed per area, then capped at what the area serves. */
    private static final class Tally {
        int open;
        int awaiting;
        int crews;
        int overdue;
        long customers;
        long oldestReportedAt = Long.MAX_VALUE;
        long nextEtr = Long.MAX_VALUE;
        long latestEtr;

        void add(Ticket t, long now) {
            open++;
            customers += t.customersAffected();
            oldestReportedAt = Math.min(oldestReportedAt, t.reportedAt());
            switch (t.status()) {
                case REPORTED -> awaiting++;
                case CREW_ASSIGNED -> crews++;
                default -> { }
            }
            if (t.etrAt() > 0) {
                nextEtr = Math.min(nextEtr, t.etrAt());
                latestEtr = Math.max(latestEtr, t.etrAt());
                if (now > t.etrAt() + OVERDUE_GRACE_MS) overdue++;
            }
        }

        void add(Tally o, long customerCap) {
            open += o.open;
            awaiting += o.awaiting;
            crews += o.crews;
            overdue += o.overdue;
            customers += Math.min(o.customers, customerCap);
            oldestReportedAt = Math.min(oldestReportedAt, o.oldestReportedAt);
            nextEtr = Math.min(nextEtr, o.nextEtr);
            latestEtr = Math.max(latestEtr, o.latestEtr);
        }
    }

    private Map<String, Tally> tallyByArea(String zipOnly, long now) {
        Map<String, Tally> byArea = new HashMap<>();
        for (Ticket t : store.open()) {
            if (!t.counts() || (zipOnly != null && !zipOnly.equals(t.zip()))) continue;
            byArea.computeIfAbsent(t.areaId(), k -> new Tally()).add(t, now);
        }
        return byArea;
    }

    private static long cap(String areaId) {
        return Territory.byId(areaId).map(Territory.Area::customersServed).orElse(0);
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : Math.round(10_000.0 * part / whole) / 100.0;
    }

    /** ISO-8601 instant, or null for "not yet" (0) and "none" (Long.MAX_VALUE). */
    static String iso(long epochMs) {
        return epochMs <= 0 || epochMs == Long.MAX_VALUE ? null : Instant.ofEpochMilli(epochMs).toString();
    }
}
