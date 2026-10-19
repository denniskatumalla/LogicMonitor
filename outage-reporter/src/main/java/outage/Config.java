package outage;

import java.nio.file.Path;
import java.util.Map;

/** Runtime settings, read from OUTAGE_* environment variables. */
public record Config(
        // Public listener: pages, JSON API, form POST and /health.
        String bind,
        int port,
        // Admin listener for the chaos switch; only started when adminToken is set.
        String adminBind,
        int adminPort,
        String adminToken,
        // Optional HTTPS listener; only started when a PKCS12 keystore path is set.
        int tlsPort,
        Path tlsKeystore,
        String tlsPassword,
        // Directory holding the append-only ticket log.
        Path dataDir,
        // What the latency and errors chaos modes inject.
        int chaosLatencyMs,
        int chaosErrorPercent,
        // Thresholds over the sliding window at which /health reports DEGRADED.
        int degradedP95Ms,
        double degradedErrorPercent,
        // Length of that sliding window, in seconds.
        int windowSeconds,
        // Outage lifecycle model: typical restore time, triage capacity and delay,
        // and how often the dispatcher runs.
        int restoreMinutes,
        int triagePerMinute,
        int triageDelaySeconds,
        int dispatchIntervalMs,
        // Storm chaos mode: peak report rate and ramp-up, and the reports-per-minute
        // level at which storm response is declared.
        int stormReportsPerSecond,
        int stormRampSeconds,
        int stormThresholdPerMinute,
        // Optional footer line on every page.
        String demoCredit) {

    /**
     * Builds the settings from the given environment map (System.getenv() in
     * production, a plain map in tests). Unset or blank values fall back to the
     * defaults shown; a value that should be a number and is not fails startup.
     */
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

    /** The admin listener runs only with a token, so chaos can never be switched unauthenticated. */
    boolean adminEnabled() {
        return !adminToken.isBlank();
    }

    /** HTTPS is opt-in: on only when OUTAGE_TLS_KEYSTORE is set. */
    boolean tlsEnabled() {
        return tlsKeystore != null;
    }

    /** Integer setting, or the fallback when unset or blank; names the variable if it is not a number. */
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
