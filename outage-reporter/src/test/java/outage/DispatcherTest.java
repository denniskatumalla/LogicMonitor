package outage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import static outage.Assert.eq;
import static outage.Assert.ok;

/** The dispatcher on a manual clock with a seeded random source, one tick per simulated second. */
class DispatcherTest {

    private static Dispatcher dispatcher(DomainTest.Domain d) {
        return new Dispatcher(d.service, d.config, new SplittableRandom(42), d.now.get());
    }

    private static void run(DomainTest.Domain d, Dispatcher disp, int seconds) {
        for (int i = 0; i < seconds; i++) {
            d.advance(1000);
            disp.tick(d.now.get());
        }
    }

    @TestRunner.Test
    void aTicketGoesThroughEveryStatusInOrderWithAnEtr() throws IOException {
        try (DomainTest.Domain d = new DomainTest.Domain()) {
            Dispatcher disp = dispatcher(d);
            Ticket t = d.report("00012");
            run(d, disp, 9);
            eq("still within the triage delay", Ticket.Status.REPORTED, d.store.get(t.id()).orElseThrow().status());
            run(d, disp, 1);
            Ticket c = d.store.get(t.id()).orElseThrow();
            eq(Ticket.Status.CONFIRMED, c.status());
            long minutes = (c.etrAt() - c.confirmedAt()) / 60_000;
            ok("ETR 6–10 min out for an 8 min restore: " + minutes, minutes >= 6 && minutes <= 10);
            ok("impact known: " + c.customersAffected(), c.customersAffected() >= 1);

            List<Ticket.Status> seen = new ArrayList<>(List.of(Ticket.Status.REPORTED, Ticket.Status.CONFIRMED));
            for (int i = 0; i < 30 * 60 && d.store.get(t.id()).orElseThrow().open(); i++) {
                run(d, disp, 1);
                Ticket.Status s = d.store.get(t.id()).orElseThrow().status();
                if (s != seen.getLast()) seen.add(s);
            }
            eq(List.of(Ticket.Status.values()), seen);
            Ticket r = d.store.get(t.id()).orElseThrow();
            ok("crew assigned a quarter of the way to the ETR",
                    r.crewAssignedAt() >= c.confirmedAt() + (c.etrAt() - c.confirmedAt()) / 4);
            ok("restored at the (possibly revised) ETR, within a tick", r.restoredAt() - r.etrAt() < 1000);
            eq(0, d.service.kpis().openOutages());
        }
    }

    @TestRunner.Test
    void triageCapacityIsTheBottleneckAndWorksOldestFirst() throws IOException {
        try (DomainTest.Domain d = new DomainTest.Domain("OUTAGE_TRIAGE_PER_MINUTE", "60")) {
            Dispatcher disp = dispatcher(d);
            List<Ticket> reports = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                reports.add(d.report("000" + (10 + i % 60)));
                d.now.incrementAndGet();
            }
            run(d, disp, 10);
            eq("10 s of idle capacity banks only two ticks' worth", 28, d.service.kpis().awaitingTriage());
            run(d, disp, 10);
            eq("then one a second", 18, d.service.kpis().awaitingTriage());
            List<Ticket> confirmed = d.store.open().stream().filter(t -> t.status() != Ticket.Status.REPORTED)
                    .sorted(Comparator.comparingLong(Ticket::reportedAt)).toList();
            eq("the oldest twelve went first", reports.subList(0, 12).stream().map(Ticket::id).toList(),
                    confirmed.stream().map(Ticket::id).toList());
        }
    }

    @TestRunner.Test
    void stormResponseDoublesTheEtr() throws IOException {
        try (DomainTest.Domain d = new DomainTest.Domain("OUTAGE_STORM_THRESHOLD_PER_MINUTE", "1")) {
            Dispatcher disp = dispatcher(d);
            Ticket t = d.report("00012");
            ok(d.service.stormMode());
            run(d, disp, 10);
            Ticket c = d.store.get(t.id()).orElseThrow();
            long minutes = (c.etrAt() - c.confirmedAt()) / 60_000;
            ok("ETR 12–20 min out under storm response: " + minutes, minutes >= 12 && minutes <= 20);
        }
    }

    @TestRunner.Test
    void aboutOneEtrInFourSlipsWhenTheCrewIsAssigned() throws IOException {
        try (DomainTest.Domain d = new DomainTest.Domain("OUTAGE_TRIAGE_PER_MINUTE", "6000",
                "OUTAGE_STORM_THRESHOLD_PER_MINUTE", "1000")) {
            Dispatcher disp = dispatcher(d);
            for (int i = 0; i < 200; i++) d.report("00033");
            run(d, disp, 10);
            List<Ticket> confirmed = List.copyOf(d.store.open());
            ok("all confirmed together", confirmed.stream().allMatch(t -> t.status() == Ticket.Status.CONFIRMED));
            // Crews go out 1.5–2.5 min after confirmation; nothing is restored before 6 min.
            run(d, disp, 3 * 60);
            List<Ticket> assigned = List.copyOf(d.store.open());
            eq(200, assigned.size());
            ok("all assigned", assigned.stream().allMatch(t -> t.status() == Ticket.Status.CREW_ASSIGNED));
            long slipped = assigned.stream().filter(t -> t.etrRevisions() == 1).count();
            ok("~25 % of 200 slipped: " + slipped, slipped >= 30 && slipped <= 75);
            for (Ticket t : assigned) {
                Ticket before = confirmed.stream().filter(c -> c.id().equals(t.id())).findFirst().orElseThrow();
                long added = t.etrAt() - before.etrAt();
                long span = before.etrAt() - before.confirmedAt();
                if (t.etrRevisions() == 0) eq("unchanged ETR", 0L, added);
                else ok("slips by 25–50 %: " + added + " of " + span, added >= span / 4 - 1 && added <= span / 2 + 1);
            }
        }
    }

    @TestRunner.Test
    void aBrokenStorePausesTheDispatcherWithoutLosingWork() throws IOException {
        try (DomainTest.Domain d = new DomainTest.Domain()) {
            Dispatcher disp = dispatcher(d);
            Ticket t = d.report("00012");
            d.blocked.set(true);
            run(d, disp, 30);
            eq("nothing moves while writes fail", Ticket.Status.REPORTED, d.store.get(t.id()).orElseThrow().status());
            d.blocked.set(false);
            run(d, disp, 1);
            eq("picks up on the next tick", Ticket.Status.CONFIRMED, d.store.get(t.id()).orElseThrow().status());
        }
    }

    @TestRunner.Test
    void syntheticCheckTicketsCloseAtOnceWithoutUsingTriage() throws IOException {
        try (DomainTest.Domain d = new DomainTest.Domain("OUTAGE_TRIAGE_PER_MINUTE", "1")) {
            Dispatcher disp = dispatcher(d);
            Ticket test = d.report(Territory.TEST_ZIP);
            run(d, disp, 1);
            eq(Ticket.Status.RESTORED, d.store.get(test.id()).orElseThrow().status());
            eq(0, d.store.open().size());
        }
    }
}
