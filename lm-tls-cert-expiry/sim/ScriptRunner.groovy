import java.util.regex.Matcher
import java.util.regex.Pattern

/** What one script execution produced. */
class ScriptResult {
    int exitCode
    String stdout
    String stderr
    boolean timedOut
    long millis
}

/**
 * Runs an embedded Groovy script the way the collector does: tokens replaced
 * in the source first, hostProps/instanceProps bound, no args, stdout
 * captured, the return value used as the exit code, and a hard timeout.
 */
class ScriptRunner {
    private static final Pattern TOKEN = ~/##([A-Za-z0-9._-]+)##/

    /**
     * Replaces ##NAME## with the matching value. Names are case-insensitive.
     * Unknown tokens are left as written -- an assumption about the collector,
     * and the reason both scripts avoid relying on tokens.
     */
    static String substitute(String source, Map<String, String> tokens) {
        Map<String, String> ci = Props.of(tokens)
        Matcher m = TOKEN.matcher(source)
        StringBuilder sb = new StringBuilder()
        int last = 0
        while (m.find()) {
            sb.append(source, last, m.start())
            String name = m.group(1)
            sb.append(ci.containsKey(name) && ci.get(name) != null ? ci.get(name) : m.group(0))
            last = m.end()
        }
        sb.append(source, last, source.length())
        return sb.toString()
    }

    /**
     * Executes the script with the given binding variables. An uncaught
     * exception counts as exit code 1; a null or non-numeric return as 0.
     */
    static ScriptResult run(String source, Map<String, Object> vars, int timeoutSeconds) {
        StringWriter out = new StringWriter()
        ByteArrayOutputStream err = new ByteArrayOutputStream()
        Binding binding = new Binding()
        vars.each { String k, Object v -> binding.setVariable(k, v) }
        binding.setVariable("out", new PrintWriter(out, true))

        Object[] returned = [null]
        Throwable[] thrown = [null]
        Thread worker = new Thread({
            try {
                returned[0] = new GroovyShell(binding).evaluate(source, "script.groovy")
            } catch (Throwable t) {
                thrown[0] = t
            }
        } as Runnable, "lmsim-script")
        worker.daemon = true

        // println goes to the bound `out`; System.out/err are swapped so that
        // printStackTrace and direct System.out writes are captured too.
        PrintStream origOut = System.out
        PrintStream origErr = System.err
        PrintStream errStream = new PrintStream(err, true, "UTF-8")
        PrintStream outStream = new PrintStream(new WriterOutputStream(out), true, "UTF-8")
        long start = System.nanoTime()
        try {
            System.setOut(outStream)
            System.setErr(errStream)
            worker.start()
            worker.join(timeoutSeconds * 1000L)
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }

        ScriptResult r = new ScriptResult()
        r.millis = (System.nanoTime() - start).intdiv(1_000_000L)
        r.timedOut = worker.alive
        if (r.timedOut) {
            worker.interrupt()
            r.exitCode = 1
            errStream.println("lmsim: script exceeded the ${timeoutSeconds}s timeout and was abandoned")
        } else if (thrown[0] != null) {
            r.exitCode = 1
            thrown[0].printStackTrace(errStream)
        } else {
            r.exitCode = (returned[0] instanceof Number) ? ((Number) returned[0]).intValue() : 0
        }
        r.stdout = out.toString()
        r.stderr = err.toString("UTF-8")
        return r
    }

    /** Adapts System.out writes onto the StringWriter that println uses. */
    private static class WriterOutputStream extends OutputStream {
        private final Writer target
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream()

        WriterOutputStream(Writer target) { this.target = target }

        @Override
        void write(int b) {
            buf.write(b)
            if (b == ('\n' as char)) flush()
        }

        @Override
        void flush() {
            target.write(buf.toString("UTF-8"))
            buf.reset()
        }
    }
}
