package outage;

import java.io.PrintStream;
import java.time.Instant;

/**
 * One key=value line per event on stdout. Under systemd that lands in
 * journald, and from there in rsyslog and LM Logs, where key=value pairs can
 * be parsed into fields without a custom pattern.
 */
final class Log {
    private Log() { }

    private static volatile PrintStream out = System.out;

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
