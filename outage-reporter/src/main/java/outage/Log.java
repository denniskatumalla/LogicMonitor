package outage;

import java.io.PrintStream;
import java.time.Instant;

/**
 * One key=value line per event on stdout. Under systemd that lands in
 * journald, and from there in rsyslog and LM Logs, where key=value pairs can
 * be parsed into fields without a custom pattern.
 *
 * <p>Every line starts with ts, level and event, then the caller's pairs, e.g.
 * {@code ts=2026-10-09T15:05:40.118Z level=WARN event=chaos_mode_changed from=off to=storm}.
 * LM Logs searches can then filter on fields such as level=ERROR or
 * event=health_changed. No line carries a customer's address or phone number.
 */
final class Log {
    private Log() { }

    private static volatile PrintStream out = System.out;

    // The event name, then alternating key and value arguments:
    // Log.info("access", "path", path, "status", 200).
    static void info(String event, Object... kv) {
        emit("INFO", event, kv);
    }

    static void warn(String event, Object... kv) {
        emit("WARN", event, kv);
    }

    static void error(String event, Object... kv) {
        emit("ERROR", event, kv);
    }

    /** Tests redirect output here to keep the runner's console readable. */
    static void redirect(PrintStream stream) {
        out = stream;
    }

    // Builds one line. A trailing key with no value is dropped. Package-private
    // so LogTest can check the exact format.
    static String format(String level, String event, Object... kv) {
        StringBuilder sb = new StringBuilder(128)
                .append("ts=").append(Instant.now())
                .append(" level=").append(level)
                .append(" event=").append(event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=');
            value(sb, kv[i + 1]);
        }
        return sb.toString();
    }

    private static void emit(String level, String event, Object... kv) {
        out.println(format(level, event, kv));
    }

    // Writes a value bare when safe; otherwise quotes it, escaping quotes and
    // backslashes and turning newlines into a literal \n. One event is always one
    // line, and a value cannot inject an extra key=value pair.
    private static void value(StringBuilder sb, Object v) {
        String s = String.valueOf(v);
        boolean quote = s.isEmpty();
        for (int i = 0; i < s.length() && !quote; i++) {
            char c = s.charAt(i);
            quote = c <= ' ' || c == '"' || c == '=';
        }
        if (!quote) {
            sb.append(s);
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < ' ') sb.append(' ');
            else sb.append(c);
        }
        sb.append('"');
    }
}
