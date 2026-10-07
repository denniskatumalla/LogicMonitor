package shortlink;

import java.util.concurrent.CountDownLatch;

public final class Main {
    public static final String VERSION = "1.0.0";

    private Main() { }

    public static void main(String[] args) throws Exception {
        ShortlinkServer server;
        try {
            server = ShortlinkServer.start(Config.fromEnv(System.getenv()));
        } catch (Exception e) {
            // Non-zero exit lets systemd's Restart=on-failure retry.
            Log.error("startup_failed", "error", e.toString());
            System.exit(1);
            return;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "shutdown"));
        stopped.await();
    }
}
