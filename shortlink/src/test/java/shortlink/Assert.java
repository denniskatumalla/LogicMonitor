package shortlink;

import java.util.Objects;

final class Assert {
    private Assert() { }

    static void eq(Object expected, Object actual) {
        eq("", expected, actual);
    }

    static void eq(String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(prefix(what) + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void eq(String what, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(prefix(what) + "expected <" + expected + "> ±" + tolerance + " but was <" + actual + ">");
        }
    }

    static void ok(String what, boolean condition) {
        if (!condition) throw new AssertionError(what);
    }

    static <T extends Throwable> T fails(Class<T> type, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String prefix(String what) {
        return what.isEmpty() ? "" : what + ": ";
    }
}
