package outage;

import java.util.concurrent.CountDownLatch;

/**
 * Process entry point. Reads the configuration from the environment, starts
 * the server, and keeps the JVM alive until a shutdown signal (systemd stop,
 * SIGTERM, Ctrl-C), when a shutdown hook closes the server cleanly.
 */
public final class Main {
    public static final String VERSION = "1.0.0";

    private Main() { }

    // The listeners run on their own threads; main only starts them and waits.
    public static void main(String[] args) throws Exception {
        OutageServer server;
        try {
            server = OutageServer.start(Config.fromEnv(System.getenv()));
        } catch (Exception e) {
            // Non-zero exit lets systemd's Restart=on-failure retry.
            Log.error("startup_failed", "error", e.toString());
            System.exit(1);
            return;
        }
        // Parks main until the shutdown hook has finished closing the server, so the
        // JVM does not exit with a half-closed store.
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "shutdown"));
        stopped.await();
    }
}
