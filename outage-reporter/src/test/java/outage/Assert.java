package outage;

import java.util.Objects;

/**
 * The assertions the Java tests use, in place of JUnit's. Each failure throws
 * AssertionError with an expected/actual message, which TestRunner reports.
 * The optional first argument names the check, so a failure says which one.
 */
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

    // For floating-point figures that cannot be compared exactly.
    static void eq(String what, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(prefix(what) + "expected <" + expected + "> ±" + tolerance + " but was <" + actual + ">");
        }
    }

    static void ok(boolean condition) {
        ok("expected true", condition);
    }

    static void ok(String what, boolean condition) {
        if (!condition) throw new AssertionError(what);
    }

    // Passes only if r throws the given type. Returns the exception so its message can be checked.
    static <T extends Throwable> T fails(Class<T> type, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    // Like Runnable, but allowed to throw checked exceptions such as IOException.
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String prefix(String what) {
        return what.isEmpty() ? "" : what + ": ";
    }
}
