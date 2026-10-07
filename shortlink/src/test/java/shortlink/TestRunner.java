package shortlink;

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
 */
public final class TestRunner {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Test { }

    static final List<Class<?>> CLASSES = List.of(
            JsonTest.class, LogTest.class, MetricsTest.class, HealthTest.class, ValidationTest.class,
            LinkStoreTest.class, EndToEndTest.class, ProcessTest.class);

    public static void main(String[] args) throws Exception {
        // Service logs would drown the report; keep them for failures only.
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        Log.redirect(new PrintStream(logs, true));
        long t0 = System.currentTimeMillis();
        int run = 0;
        List<String> failures = new ArrayList<>();
        for (Class<?> c : CLASSES) {
            invokeStatic(c, "beforeAll");
            List<Method> tests = Arrays.stream(c.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Test.class))
                    .sorted(Comparator.comparing(Method::getName))
                    .toList();
            for (Method m : tests) {
                run++;
                try {
                    m.setAccessible(true);
                    m.invoke(c.getDeclaredConstructor().newInstance());
                    System.out.println("  ok   " + c.getSimpleName() + "." + m.getName());
                } catch (InvocationTargetException e) {
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
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void invokeStatic(Class<?> c, String name) throws Exception {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                m.invoke(null);
            }
        }
    }
}
