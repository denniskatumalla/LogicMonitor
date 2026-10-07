package outage;

import java.nio.file.Path;
import java.util.Map;

/** Runtime settings, read from OUTAGE_* environment variables. */
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
        int chaosLatencyMs,
        int chaosErrorPercent,
        int degradedP95Ms,
        double degradedErrorPercent,
        int windowSeconds,
        int restoreMinutes,
        int triagePerMinute,
        int triageDelaySeconds,
        int dispatchIntervalMs,
        int stormReportsPerSecond,
        int stormRampSeconds,
        int stormThresholdPerMinute,
        String demoCredit) {

    public static Config fromEnv(Map<String, String> env) {
        String keystore = env.getOrDefault("OUTAGE_TLS_KEYSTORE", "");
        return new Config(
                env.getOrDefault("OUTAGE_BIND", "0.0.0.0"),
                intOf(env, "OUTAGE_PORT", 8080),
                env.getOrDefault("OUTAGE_ADMIN_BIND", "127.0.0.1"),
                intOf(env, "OUTAGE_ADMIN_PORT", 8081),
                env.getOrDefault("OUTAGE_ADMIN_TOKEN", ""),
                intOf(env, "OUTAGE_TLS_PORT", 8443),
                keystore.isBlank() ? null : Path.of(keystore),
                env.getOrDefault("OUTAGE_TLS_PASSWORD", ""),
                Path.of(env.getOrDefault("OUTAGE_DATA_DIR", "data")),
                intOf(env, "OUTAGE_CHAOS_LATENCY_MS", 1500),
                intOf(env, "OUTAGE_CHAOS_ERROR_PERCENT", 50),
                intOf(env, "OUTAGE_DEGRADED_P95_MS", 500),
                intOf(env, "OUTAGE_DEGRADED_ERROR_PERCENT", 5),
                intOf(env, "OUTAGE_WINDOW_SECONDS", 60),
                intOf(env, "OUTAGE_RESTORE_MINUTES", 8),
                intOf(env, "OUTAGE_TRIAGE_PER_MINUTE", 180),
                intOf(env, "OUTAGE_TRIAGE_DELAY_SECONDS", 10),
                intOf(env, "OUTAGE_DISPATCH_INTERVAL_MS", 1000),
                intOf(env, "OUTAGE_STORM_REPORTS_PER_SECOND", 6),
                intOf(env, "OUTAGE_STORM_RAMP_SECONDS", 10),
                intOf(env, "OUTAGE_STORM_THRESHOLD_PER_MINUTE", 60),
                env.getOrDefault("OUTAGE_DEMO_CREDIT", "").strip());
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
