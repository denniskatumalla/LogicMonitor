package outage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static outage.Assert.eq;
import static outage.Assert.ok;

/** OutageService, Territory and EventWindow, on a manual clock. */
class DomainTest {

    /** A store and service on a temp dir with a clock the test moves. Shared with the dispatcher and storm tests. */
    static final class Domain implements AutoCloseable {
        final Path dir;
        final AtomicLong now = new AtomicLong(1_760_000_000_000L);
        final AtomicBoolean blocked = new AtomicBoolean();
        final Config config;
        final TicketStore store;
        final OutageService service;

        Domain(String... env) throws IOException {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i + 1 < env.length; i += 2) m.put(env[i], env[i + 1]);
            config = Config.fromEnv(m);
            dir = TicketStoreTest.tempDir();
            store = TicketStore.open(dir, blocked::get);
            service = new OutageService(store, config, now::get);
        }

        Ticket report(String zip) throws IOException {
            return service.report(new Report(zip, "", "", ""), Ticket.Source.WEB);
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }

        @Override
        public void close() throws IOException {
            store.close();
            TicketStoreTest.delete(dir);
        }
    }

    @TestRunner.Test
    void territoryIsSixFictionalAreasOfThreeMillionCustomers() {
        eq(6, Territory.AREAS.size());
        eq(3_000_000, Territory.customersServed());
        eq("harbor", Territory.forZip("00010").orElseThrow().id());
        eq("pinewood", Territory.forZip("00069").orElseThrow().id());
        ok("below the territory", Territory.forZip("00009").isEmpty());
        ok("above the territory", Territory.forZip("00070").isEmpty());
        ok("a real-world ZIP", Territory.forZip("70112").isEmpty());
        eq(Territory.TEST_AREA, Territory.forZip(Territory.TEST_ZIP).orElseThrow());
        eq("00010–00019", Territory.AREAS.getFirst().zipRange());
    }

    @TestRunner.Test
    void kpisCountCustomerReportsButNotSyntheticChecks() throws IOException {
        try (Domain d = new Domain()) {
            d.report("00012");
            d.report("00031");
            Ticket test = d.report(Territory.TEST_ZIP);
            eq(Ticket.Source.TEST, test.source());
            ok("stored like any other", d.store.get(test.id()).isPresent());
            OutageService.Kpis k = d.service.kpis();
            eq(2L, k.reportsTotal());
            eq(2L, k.reportsLastMinute());
            eq(2, k.openOutages());
            eq(2, k.awaitingTriage());
            eq("unconfirmed: impact not known yet", 0L, k.customersAffected());
            ok("not a storm", !k.stormMode());
        }
    }

    @TestRunner.Test
    void stormModeIsDeclaredOnReportVolume() throws IOException {
        try (Domain d = new Domain("OUTAGE_STORM_THRESHOLD_PER_MINUTE", "3")) {
            d.report("00012");
            d.report("00012");
            ok("2 < 3", !d.service.stormMode());
            d.report("00012");
            ok("3 reports in a minute", d.service.stormMode());
            d.advance(61_000);
            ok("rolls off after a minute", !d.service.stormMode());
            eq(0L, d.service.kpis().reportsLastMinute());
            eq("the lifetime counter keeps them", 3L, d.service.kpis().reportsTotal());
        }
    }

    @TestRunner.Test
    void ageAndOverdueComeFromOpenTickets() throws IOException {
        try (Domain d = new Domain()) {
            Ticket t = d.report("00012");
            d.advance(90_000);
            eq(1.5, d.service.kpis().oldestOpenMinutes());
            d.store.update(t.advance(Ticket.Status.CONFIRMED, d.now.get(), d.now.get() + 60_000, 10, 0));
            d.advance(60_000 + OutageService.OVERDUE_GRACE_MS);
            eq("within the grace period", 0, d.service.kpis().overdueOutages());
            d.advance(1);
            eq(1, d.service.kpis().overdueOutages());
            d.store.update(d.store.get(t.id()).orElseThrow().advance(Ticket.Status.RESTORED, d.now.get(), 0, 10, 0));
            OutageService.Kpis k = d.service.kpis();
            eq(0, k.openOutages());
            eq(0.0, k.oldestOpenMinutes());
            eq(0, k.overdueOutages());
        }
    }

    @TestRunner.Test
    void customersAffectedIsCappedAtWhatAnAreaServes() throws IOException {
        try (Domain d = new Domain()) {
            Ticket t = d.report("00051");
            d.store.update(t.advance(Ticket.Status.CONFIRMED, d.now.get(), d.now.get() + 1, 1_000_000, 0));
            eq(356_000L, d.service.kpis().customersAffected());
            Map<?, ?> riverbend = ((List<?>) d.service.areasJson().get("areas")).stream()
                    .map(a -> (Map<?, ?>) a).filter(a -> a.get("id").equals("riverbend")).findFirst().orElseThrow();
            eq(100.0, riverbend.get("pctAffected"));
        }
    }

    @TestRunner.Test
    void ticketLookupForgivesCaseAndAMissingPrefix() throws IOException {
        try (Domain d = new Domain()) {
            String id = d.report("00012").id();
            ok(d.service.ticket(id).isPresent());
            ok(d.service.ticket(" " + id.toLowerCase() + " ").isPresent());
            ok(d.service.ticket(id.substring(4)).isPresent());
            ok(d.service.ticket("EPL-../../x").isEmpty());
            ok(d.service.ticket(null).isEmpty());
        }
    }

    @TestRunner.Test
    void ticketViewMasksThePhoneAndHidesImpactUntilConfirmed() throws IOException {
        try (Domain d = new Domain()) {
            Ticket t = d.service.report(new Report("00012", "12 Bay St", "5550100123", ""), Ticket.Source.WEB);
            Map<String, Object> j = d.service.ticketJson(t);
            eq("•••-•••-0123", j.get("phone"));
            eq("reported", j.get("status"));
            eq(null, j.get("customersAffected"));
            eq(null, j.get("etr"));
            eq(4, ((List<?>) j.get("timeline")).size());
            eq(Map.of("id", "harbor", "name", "Harbor District"), j.get("area"));
        }
    }

    @TestRunner.Test
    void zipViewCountsOnlyThatZipAndNamesNoTickets() throws IOException {
        try (Domain d = new Domain()) {
            Ticket a = d.report("00012");
            d.report("00012");
            d.report("00013");
            d.store.update(a.advance(Ticket.Status.CONFIRMED, d.now.get(), d.now.get() + 600_000, 40, 0));
            Map<String, Object> z = d.service.zipJson("00012");
            eq(2, z.get("openOutages"));
            eq(1, z.get("awaitingConfirmation"));
            eq(40L, z.get("customersAffected"));
            eq(OutageService.iso(d.now.get() + 600_000), z.get("nextEtr"));
            ok("no ticket ids in a ZIP view", !z.toString().contains("EPL-"));
        }
    }

    @TestRunner.Test
    void eventWindowCountsTheLastNSeconds() {
        AtomicLong now = new AtomicLong(10_000);
        EventWindow w = new EventWindow(3, now::get);
        w.add(1);
        now.addAndGet(1000);
        w.add(2);
        now.addAndGet(1000);
        w.add(4);
        eq(7L, w.sum());
        now.addAndGet(1000);
        eq("the first second has left the window", 6L, w.sum());
        now.addAndGet(10_000);
        eq(0L, w.sum());
        w.add(1);
        eq("buckets are reused", 1L, w.sum());
    }
}
