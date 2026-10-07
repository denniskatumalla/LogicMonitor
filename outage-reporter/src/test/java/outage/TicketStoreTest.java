package outage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static outage.Assert.eq;
import static outage.Assert.fails;
import static outage.Assert.ok;

class TicketStoreTest {

    private final AtomicBoolean blocked = new AtomicBoolean();
    static final Report REPORT = new Report("00012", "12 Bay St", "5550100123", "bang from the pole");

    static Path tempDir() throws IOException {
        return Files.createTempDirectory("outage-test");
    }

    static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @TestRunner.Test
    void ticketsAndStatusChangesSurviveARestart() throws IOException {
        Path dir = tempDir();
        try {
            String id;
            try (TicketStore s = TicketStore.open(dir, blocked::get)) {
                Ticket t = s.create(Ticket.Source.WEB, REPORT, "edwin", 1_000);
                id = t.id();
                ok("EPL- and 6 unambiguous chars: " + id, id.matches("EPL-[2-9A-HJ-NP-Z]{6}"));
                s.update(t.advance(Ticket.Status.CONFIRMED, 2_000, 60_000, 42, 0));
                eq(1, s.open().size());
            }
            try (TicketStore s = TicketStore.open(dir, blocked::get)) {
                Ticket t = s.get(id).orElseThrow();
                eq(Ticket.Status.CONFIRMED, t.status());
                eq("12 Bay St", t.address());
                eq("bang from the pole", t.notes());
                eq(1_000L, t.reportedAt());
                eq(2_000L, t.confirmedAt());
                eq(60_000L, t.etrAt());
                eq(42, t.customersAffected());
                eq(0, s.skippedOnLoad());
                s.update(t.advance(Ticket.Status.RESTORED, 70_000, 60_000, 42, 0));
            }
            try (TicketStore s = TicketStore.open(dir, blocked::get)) {
                eq("restored tickets are kept but not open", 1, s.size());
                eq(0, s.open().size());
                eq(70_000L, s.get(id).orElseThrow().restoredAt());
            }
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void skipsATornLastLineAndKeepsGoing() throws IOException {
        Path dir = tempDir();
        try {
            Path file = dir.resolve(TicketStore.FILE);
            Files.writeString(file, String.join("\n",
                    "R\tEPL-AAAAAA\t1700000000000\tWEB\t00012\t\t\t",
                    "S\tEPL-AAAAAA\tCONFIRMED\t1700000060000\t1700000600000\t7\t0",
                    "S\tEPL-UNKNWN\tCONFIRMED\t1\t2\t3\t0",
                    "R\tEPL-BBBBBB\t1700000000000\tWEB\t70112\t\t\t",
                    "R\tEPL-CCCCCC\t17000"), StandardCharsets.UTF_8);
            try (TicketStore s = TicketStore.open(dir, blocked::get)) {
                eq(1, s.size());
                eq(Ticket.Status.CONFIRMED, s.get("EPL-AAAAAA").orElseThrow().status());
                eq("unknown ticket, ZIP outside the territory, torn line", 3, s.skippedOnLoad());
                Ticket t = s.create(Ticket.Source.WEB, REPORT, "edwin", 1);
                ok("appends after the torn line", s.get(t.id()).isPresent());
            }
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void aFailedWriteLeavesNothingHalfDone() throws IOException {
        Path dir = tempDir();
        try (TicketStore s = TicketStore.open(dir, blocked::get)) {
            Ticket t = s.create(Ticket.Source.WEB, REPORT, "edwin", 1);
            ok("healthy at start", s.healthy());
            blocked.set(true);
            fails(IOException.class, () -> s.create(Ticket.Source.WEB, REPORT, "edwin", 2));
            fails(IOException.class, () -> s.update(t.advance(Ticket.Status.CONFIRMED, 3, 4, 5, 0)));
            eq("no unpersisted ticket", 1, s.size());
            eq("no unpersisted status change", Ticket.Status.REPORTED, s.get(t.id()).orElseThrow().status());
            ok("unhealthy while writes fail", !s.healthy());
            blocked.set(false);
            ok("recovers without a new write", s.healthy());
            s.create(Ticket.Source.WEB, REPORT, "edwin", 6);
            eq(2, s.size());
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void reportsUnhealthyWhenTheFileCannotBeWritten() throws IOException {
        Path dir = tempDir();
        TicketStore s = TicketStore.open(dir, blocked::get);
        try {
            Path file = dir.resolve(TicketStore.FILE);
            s.create(Ticket.Source.WEB, REPORT, "edwin", 1);
            // Replace the log with a directory: the next write and every reopen fail for real.
            s.close();
            Files.delete(file);
            Files.createDirectory(file);
            fails(IOException.class, () -> s.create(Ticket.Source.WEB, REPORT, "edwin", 2));
            ok("real I/O failure is visible", !s.healthy());
            Files.delete(file);
            Files.writeString(file, "", StandardOpenOption.CREATE);
            ok("and recovers once the file is writable", s.healthy());
        } finally {
            s.close();
            delete(dir);
        }
    }

    @TestRunner.Test
    void concurrentReportsAllGetDistinctPersistedTickets() throws Exception {
        Path dir = tempDir();
        try {
            int threads = 8;
            int each = 50;
            try (TicketStore s = TicketStore.open(dir, blocked::get)) {
                Thread[] ts = new Thread[threads];
                for (int i = 0; i < threads; i++) {
                    ts[i] = Thread.ofVirtual().start(() -> {
                        for (int j = 0; j < each; j++) {
                            try {
                                s.create(Ticket.Source.WEB, REPORT, "edwin", 1);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    });
                }
                for (Thread t : ts) t.join();
                eq(threads * each, s.size());
            }
            try (TicketStore s = TicketStore.open(dir, blocked::get)) {
                eq("every line intact after interleaved writers", threads * each, s.size());
                eq(0, s.skippedOnLoad());
            }
        } finally {
            delete(dir);
        }
    }
}
