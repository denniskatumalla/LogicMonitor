package shortlink;

import java.nio.file.Path;
import java.util.Map;

/** Runtime settings, read from SHORTLINK_* environment variables. */
public record Config(
        String bind,
        int port,
        String adminBind,
        int adminPort,
        String adminToken,
        int tlsPort,
        Path tlsKeystore,
        String tlsPassword,
        Path dataDir,
        String baseUrl,
        int chaosLatencyMs,
        int chaosErrorPercent,
        int degradedP95Ms,
        double degradedErrorPercent,
        int windowSeconds) {

    public static Config fromEnv(Map<String, String> env) {
        String keystore = env.getOrDefault("SHORTLINK_TLS_KEYSTORE", "");
        return new Config(
                env.getOrDefault("SHORTLINK_BIND", "0.0.0.0"),
                intOf(env, "SHORTLINK_PORT", 8080),
                env.getOrDefault("SHORTLINK_ADMIN_BIND", "127.0.0.1"),
                intOf(env, "SHORTLINK_ADMIN_PORT", 8081),
                env.getOrDefault("SHORTLINK_ADMIN_TOKEN", ""),
                intOf(env, "SHORTLINK_TLS_PORT", 8443),
                keystore.isBlank() ? null : Path.of(keystore),
                env.getOrDefault("SHORTLINK_TLS_PASSWORD", ""),
                Path.of(env.getOrDefault("SHORTLINK_DATA_DIR", "data")),
                env.getOrDefault("SHORTLINK_BASE_URL", ""),
                intOf(env, "SHORTLINK_CHAOS_LATENCY_MS", 1500),
                intOf(env, "SHORTLINK_CHAOS_ERROR_PERCENT", 50),
                intOf(env, "SHORTLINK_DEGRADED_P95_MS", 500),
                intOf(env, "SHORTLINK_DEGRADED_ERROR_PERCENT", 5),
                intOf(env, "SHORTLINK_WINDOW_SECONDS", 60));
    }

    boolean adminEnabled() {
        return !adminToken.isBlank();
    }

    boolean tlsEnabled() {
        return tlsKeystore != null;
    }

    private static int intOf(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + v + "'");
        }
    }
}
