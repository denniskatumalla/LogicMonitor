package outage;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Tickets in memory, backed by an append-only log replayed on start:
 *
 * <pre>
 *   R &lt;tab&gt; id &lt;tab&gt; reportedAt &lt;tab&gt; source &lt;tab&gt; zip &lt;tab&gt; address &lt;tab&gt; phone &lt;tab&gt; notes
 *   S &lt;tab&gt; id &lt;tab&gt; status &lt;tab&gt; at &lt;tab&gt; etrAt &lt;tab&gt; customers &lt;tab&gt; etrRevisions
 * </pre>
 *
 * A ticket, and every change to it, becomes visible only after its record is
 * written, so anything a customer has been told survives a restart. Writes
 * are serialised on this object; reads never block.
 */
final class TicketStore implements Closeable {

    static final String FILE = "tickets.log";
    static final String PREFIX = "EPL-";
    // No 0/O or 1/I: ticket numbers get read out over the phone.
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int ID_LENGTH = 6;

    private final Path file;
    private final BooleanSupplier writesBlocked;
    private final ConcurrentHashMap<String, Ticket> all = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Ticket> open = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private BufferedWriter writer;
    private volatile boolean lastWriteOk = true;
    private int skippedOnLoad;

    private TicketStore(Path file, BooleanSupplier writesBlocked) {
        this.file = file;
        this.writesBlocked = writesBlocked;
    }

    static TicketStore open(Path dataDir, BooleanSupplier writesBlocked) throws IOException {
        Files.createDirectories(dataDir);
        TicketStore store = new TicketStore(dataDir.resolve(FILE), writesBlocked);
        store.load();
        store.writer = store.openWriter();
        return store;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (!replay(line.split("\t", -1))) {
                // Typically a line cut short by a crash mid-write.
                skippedOnLoad++;
            }
        }
    }

    private boolean replay(String[] f) {
        try {
            if (f.length == 8 && f[0].equals("R")) {
                Optional<Territory.Area> area = Territory.forZip(f[4]);
                if (area.isEmpty()) return false;
                Report r = new Report(f[4], f[5], f[6], f[7]);
                put(Ticket.reported(f[1], Ticket.Source.valueOf(f[3]), r, area.get().id(), Long.parseLong(f[2])));
                return true;
            }
            if (f.length == 7 && f[0].equals("S") && all.containsKey(f[1])) {
                put(all.get(f[1]).advance(Ticket.Status.valueOf(f[2]), Long.parseLong(f[3]),
                        Long.parseLong(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6])));
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // a bad number or enum name: falls through to skipped
        }
        return false;
    }

    int skippedOnLoad() {
        return skippedOnLoad;
    }

    synchronized Ticket create(Ticket.Source source, Report r, String areaId, long now) throws IOException {
        String id;
        do {
            id = newId();
        } while (all.containsKey(id));
        Ticket t = Ticket.reported(id, source, r, areaId, now);
        append(String.join("\t", "R", id, Long.toString(now), source.name(), r.zip(), r.address(), r.phone(), r.notes()));
        put(t);
        return t;
    }

    /** Persists a status change. Only the dispatcher calls this, so there is one writer per ticket. */
    synchronized void update(Ticket next) throws IOException {
        append(String.join("\t", "S", next.id(), next.status().name(), Long.toString(next.updatedAt()),
                Long.toString(next.etrAt()), Integer.toString(next.customersAffected()),
                Integer.toString(next.etrRevisions())));
        put(next);
    }

    private void put(Ticket t) {
        all.put(t.id(), t);
        if (t.open()) open.put(t.id(), t);
        else open.remove(t.id());
    }

    Optional<Ticket> get(String id) {
        return Optional.ofNullable(all.get(id));
    }

    /** Tickets not yet restored. A live view: iterating it never blocks writers. */
    Collection<Ticket> open() {
        return Collections.unmodifiableCollection(open.values());
    }

    int size() {
        return all.size();
    }

    /**
     * True if the store can take writes. After a failed write this reopens
     * the file, so the flag recovers without waiting for the next report.
     */
    synchronized boolean healthy() {
        if (writesBlocked.getAsBoolean()) return false;
        if (lastWriteOk) return true;
        try {
            closeWriter();
            writer = openWriter();
            lastWriteOk = true;
        } catch (IOException e) {
            Log.warn("store_reopen_failed", "file", file, "error", e.getMessage());
        }
        return lastWriteOk;
    }

    private void append(String record) throws IOException {
        try {
            if (writesBlocked.getAsBoolean()) throw new IOException("simulated store failure (chaos mode store)");
            if (writer == null) writer = openWriter();
            writer.write(record);
            writer.write('\n');
            writer.flush();
            lastWriteOk = true;
        } catch (IOException e) {
            lastWriteOk = false;
            throw e;
        }
    }

    private BufferedWriter openWriter() throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    private String newId() {
        char[] c = new char[ID_LENGTH];
        for (int i = 0; i < c.length; i++) c[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        return PREFIX + new String(c);
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // the writer is being replaced or the store is shutting down
        }
        writer = null;
    }

    @Override
    public synchronized void close() {
        closeWriter();
    }
}
