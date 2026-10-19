package outage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A minimal JUnit stand-in, so the suite needs nothing beyond the JDK. Runs
 * every {@link Test} method of the listed classes on a fresh instance; a
 * method that throws fails. Optional static {@code beforeAll}/{@code afterAll}.
 * <p>
 * Why not JUnit: the project builds with only the JDK (javac and jar, no
 * Maven, Gradle or downloaded jars), and this runner covers what the suite
 * needs. Discovery is explicit: a class runs only if it is listed in
 * {@link #CLASSES}, and its tests run in name order. Prints ok or FAIL per
 * test, then exits 1 if anything failed, which stops run-tests.sh.
 */
public final class TestRunner {

    // Marks a test method. RUNTIME retention lets the runner find it by reflection.
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Test { }

    // A new test class runs only once it is added here.
    static final List<Class<?>> CLASSES = List.of(
            JsonTest.class, LogTest.class, MetricsTest.class, HealthTest.class, ValidationTest.class,
            TicketStoreTest.class, DomainTest.class, DispatcherTest.class, StormSurgeTest.class,
            EndToEndTest.class, PagesTest.class, ProcessTest.class);

    public static void main(String[] args) throws Exception {
        // Service logs would drown the report; keep them for failures only.
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        Log.redirect(new PrintStream(logs, true));
        long t0 = System.currentTimeMillis();
        int run = 0;
        List<String> failures = new ArrayList<>();
        for (Class<?> c : CLASSES) {
            // beforeAll and afterAll run once per class; an exception in either aborts the run.
            invokeStatic(c, "beforeAll");
            List<Method> tests = Arrays.stream(c.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Test.class))
                    .sorted(Comparator.comparing(Method::getName))
                    .toList();
            for (Method m : tests) {
                run++;
                try {
                    // Test methods are package-private, hence setAccessible. A new instance per
                    // test keeps instance fields from leaking between tests.
                    m.setAccessible(true);
                    m.invoke(c.getDeclaredConstructor().newInstance());
                    System.out.println("  ok   " + c.getSimpleName() + "." + m.getName());
                } catch (InvocationTargetException e) {
                    // The test threw (a failed assertion or any other exception): record it
                    // with the top of its stack trace and carry on.
                    failures.add(c.getSimpleName() + "." + m.getName());
                    System.out.println("  FAIL " + c.getSimpleName() + "." + m.getName());
                    System.out.println("       " + e.getCause());
                    Arrays.stream(e.getCause().getStackTrace()).limit(6)
                            .forEach(f -> System.out.println("         at " + f));
                }
            }
            invokeStatic(c, "afterAll");
        }
        System.out.printf("%d tests, %d failures, %dms%n", run, failures.size(), System.currentTimeMillis() - t0);
        if (!failures.isEmpty()) {
            System.out.println("--- service log ---");
            System.out.print(logs);
        }
        // A non-zero exit fails run-tests.sh, which runs under set -e.
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** Calls the class's own static method of that name, if it declares one. */
    private static void invokeStatic(Class<?> c, String name) throws Exception {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                m.invoke(null);
            }
        }
    }
}
