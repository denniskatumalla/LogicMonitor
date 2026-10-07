package shortlink;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Links in memory, backed by an append-only log replayed on start:
 *
 * <pre>
 *   L &lt;tab&gt; code &lt;tab&gt; createdEpochMs &lt;tab&gt; url
 *   H &lt;tab&gt; code
 * </pre>
 *
 * A link is visible only after its L record is written, so a link that was
 * handed out always survives a restart. Hit records are best effort.
 */
final class LinkStore implements Closeable {

    record Link(String code, String url, long createdAt, long hits) { }

    private record Entry(String url, long createdAt, AtomicLong hits) { }

    static final String FILE = "links.log";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 7;

    private final Path file;
    private final BooleanSupplier writesBlocked;
    private final ConcurrentHashMap<String, Entry> links = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private BufferedWriter writer;
    private volatile boolean lastWriteOk = true;
    private int skippedOnLoad;

    private LinkStore(Path file, BooleanSupplier writesBlocked) {
        this.file = file;
        this.writesBlocked = writesBlocked;
    }

    static LinkStore open(Path dataDir, BooleanSupplier writesBlocked) throws IOException {
        Files.createDirectories(dataDir);
        LinkStore store = new LinkStore(dataDir.resolve(FILE), writesBlocked);
        store.load();
        store.writer = store.openWriter();
        return store;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            if (f.length == 4 && f[0].equals("L")) {
                try {
                    links.put(f[1], new Entry(f[3], Long.parseLong(f[2]), new AtomicLong()));
                    continue;
                } catch (NumberFormatException ignored) {
                    // falls through to skipped
                }
            } else if (f.length == 2 && f[0].equals("H") && links.containsKey(f[1])) {
                links.get(f[1]).hits().incrementAndGet();
                continue;
            }
            // Typically a line cut short by a crash mid-write.
            skippedOnLoad++;
        }
    }

    int skippedOnLoad() {
        return skippedOnLoad;
    }

    synchronized Link create(String url) throws IOException {
        String code;
        do {
            code = newCode();
        } while (links.containsKey(code));
        long now = System.currentTimeMillis();
        append("L\t" + code + "\t" + now + "\t" + url);
        links.put(code, new Entry(url, now, new AtomicLong()));
        return new Link(code, url, now, 0);
    }

    Optional<Link> get(String code) {
        Entry e = links.get(code);
        return e == null ? Optional.empty() : Optional.of(new Link(code, e.url(), e.createdAt(), e.hits().get()));
    }

    /** Looks up a code and counts the hit. A failed hit write is logged, not thrown. */
    Optional<Link> hit(String code) {
        Entry e = links.get(code);
        if (e == null) return Optional.empty();
        long hits = e.hits().incrementAndGet();
        try {
            synchronized (this) {
                append("H\t" + code);
            }
        } catch (IOException ex) {
            Log.warn("store_hit_not_persisted", "code", code, "error", ex.getMessage());
        }
        return Optional.of(new Link(code, e.url(), e.createdAt(), hits));
    }

    int size() {
        return links.size();
    }

    /**
     * True if the store can take writes. After a failed write this reopens
     * the file, so the flag recovers without waiting for the next create.
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

    private String newCode() {
        char[] c = new char[CODE_LENGTH];
        for (int i = 0; i < c.length; i++) c[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        return new String(c);
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
