package outage;

import java.io.IOException;
import java.util.SplittableRandom;

import static outage.Assert.eq;
import static outage.Assert.ok;

/** The in-process storm generator, ticked every 200 ms on a manual clock. */
class StormSurgeTest {

    private static void run(DomainTest.Domain d, StormSurge storm, int ms) {
        for (int t = 0; t < ms; t += 200) {
            d.advance(200);
            storm.tick(d.now.get());
        }
    }

    private static DomainTest.Domain domain() throws IOException {
        return new DomainTest.Domain("OUTAGE_STORM_REPORTS_PER_SECOND", "10", "OUTAGE_STORM_RAMP_SECONDS", "10");
    }

    @TestRunner.Test
    void rampsToThePeakRateAndHolds() throws IOException {
        try (DomainTest.Domain d = domain()) {
            Chaos chaos = new Chaos();
            StormSurge storm = new StormSurge(d.service, chaos, d.config, new SplittableRandom(7));
            run(d, storm, 5000);
            eq("nothing while chaos is off", 0, d.store.size());
            chaos.set(Chaos.Mode.STORM);
            storm.tick(d.now.get());
            run(d, storm, 10_000);
            long ramp = d.service.kpis().reportsTotal();
            ok("ramp 0→10/s over 10 s ≈ 50 reports: " + ramp, ramp >= 48 && ramp <= 52);
            run(d, storm, 10_000);
            long total = d.service.kpis().reportsTotal();
            ok("then 10/s for 10 s ≈ 100 more: " + total, total >= 148 && total <= 152);
            eq("all at storm level", true, d.service.stormMode());
        }
    }

    @TestRunner.Test
    void stopsWhenTheModeChangesAndStartsAFreshRampNextTime() throws IOException {
        try (DomainTest.Domain d = domain()) {
            Chaos chaos = new Chaos();
            StormSurge storm = new StormSurge(d.service, chaos, d.config, new SplittableRandom(7));
            chaos.set(Chaos.Mode.STORM);
            storm.tick(d.now.get());
            run(d, storm, 20_000);
            chaos.set(Chaos.Mode.OFF);
            long stopped = d.store.size();
            run(d, storm, 5_000);
            eq(stopped, (long) d.store.size());
            chaos.set(Chaos.Mode.STORM);
            storm.tick(d.now.get());
            run(d, storm, 1_000);
            ok("a new storm ramps from zero again: " + (d.store.size() - stopped), d.store.size() - stopped <= 1);
        }
    }

    @TestRunner.Test
    void mostReportsLandOnTheCoastAndCountAsCustomerReports() throws IOException {
        try (DomainTest.Domain d = domain()) {
            Chaos chaos = new Chaos();
            StormSurge storm = new StormSurge(d.service, chaos, d.config, new SplittableRandom(7));
            chaos.set(Chaos.Mode.STORM);
            storm.tick(d.now.get());
            run(d, storm, 30_000);
            long coastal = d.store.open().stream().filter(t -> t.areaId().equals("edwin") || t.areaId().equals("envision")).count();
            int all = d.store.open().size();
            ok("about 75 % coastal: " + coastal + " of " + all, coastal > all * 0.6 && coastal < all * 0.9);
            ok("storm reports count in the business figures",
                    d.store.open().stream().allMatch(t -> t.source() == Ticket.Source.STORM && t.counts()));
            eq((long) all, d.service.kpis().reportsTotal());
        }
    }

    @TestRunner.Test
    void aBrokenStoreDropsTheSurgeInsteadOfQueueingIt() throws IOException {
        try (DomainTest.Domain d = domain()) {
            Chaos chaos = new Chaos();
            StormSurge storm = new StormSurge(d.service, chaos, d.config, new SplittableRandom(7));
            chaos.set(Chaos.Mode.STORM);
            storm.tick(d.now.get());
            d.blocked.set(true);
            run(d, storm, 20_000);
            eq(0, d.store.size());
            d.blocked.set(false);
            run(d, storm, 1_000);
            ok("no backlog of owed reports: " + d.store.size(), d.store.size() <= 10);
        }
    }
}
