package shortlink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static shortlink.Assert.eq;
import static shortlink.Assert.fails;
import static shortlink.Assert.ok;

class LinkStoreTest {

    private final AtomicBoolean blocked = new AtomicBoolean();

    static Path tempDir() throws IOException {
        return Files.createTempDirectory("shortlink-test");
    }

    static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @TestRunner.Test
    void linksAndHitsSurviveARestart() throws IOException {
        Path dir = tempDir();
        try {
            String code;
            try (LinkStore s = LinkStore.open(dir, blocked::get)) {
                code = s.create("https://example.com/").code();
                ok("7 base62 chars: " + code, code.matches("[A-Za-z0-9]{7}"));
                s.hit(code);
                s.hit(code);
                eq(2L, s.get(code).orElseThrow().hits());
            }
            try (LinkStore s = LinkStore.open(dir, blocked::get)) {
                eq(1, s.size());
                LinkStore.Link l = s.get(code).orElseThrow();
                eq("https://example.com/", l.url());
                eq(2L, l.hits());
                eq(0, s.skippedOnLoad());
            }
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void skipsATornLastLineAndKeepsGoing() throws IOException {
        Path dir = tempDir();
        try {
            Path file = dir.resolve(LinkStore.FILE);
            Files.writeString(file, "L\tabc1234\t1700000000000\thttps://a.com/\nH\tabc1234\nH\tunknown\nL\tdef5678\t17000", StandardCharsets.UTF_8);
            try (LinkStore s = LinkStore.open(dir, blocked::get)) {
                eq(1, s.size());
                eq(1L, s.get("abc1234").orElseThrow().hits());
                eq("hit for an unknown code and the torn line", 2, s.skippedOnLoad());
                String code = s.create("https://b.com/").code();
                ok("appends after the torn line", s.get(code).isPresent());
            }
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void aFailedCreateLeavesNoUnpersistedLink() throws IOException {
        Path dir = tempDir();
        try (LinkStore s = LinkStore.open(dir, blocked::get)) {
            ok("healthy at start", s.healthy());
            blocked.set(true);
            fails(IOException.class, () -> s.create("https://example.com/"));
            eq(0, s.size());
            ok("unhealthy while writes fail", !s.healthy());
            blocked.set(false);
            ok("recovers without a new write", s.healthy());
            s.create("https://example.com/");
            eq(1, s.size());
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void hitsStillResolveWhenTheyCannotBePersisted() throws IOException {
        Path dir = tempDir();
        try (LinkStore s = LinkStore.open(dir, blocked::get)) {
            String code = s.create("https://example.com/").code();
            blocked.set(true);
            eq("https://example.com/", s.hit(code).orElseThrow().url());
            ok("unknown code", s.hit("nope").isEmpty());
        } finally {
            delete(dir);
        }
    }

    @TestRunner.Test
    void reportsUnhealthyWhenTheFileCannotBeWritten() throws IOException {
        Path dir = tempDir();
        LinkStore s = LinkStore.open(dir, blocked::get);
        try {
            Path file = dir.resolve(LinkStore.FILE);
            s.create("https://example.com/");
            // Replace the log with a directory: the next write and every reopen fail for real.
            s.close();
            Files.delete(file);
            Files.createDirectory(file);
            fails(IOException.class, () -> s.create("https://example.com/2"));
            ok("real I/O failure is visible", !s.healthy());
            Files.delete(file);
            Files.writeString(file, "", StandardOpenOption.CREATE);
            ok("and recovers once the file is writable", s.healthy());
        } finally {
            s.close();
            delete(dir);
        }
    }
}
