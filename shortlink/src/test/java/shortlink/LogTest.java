package shortlink;

import static shortlink.Assert.eq;
import static shortlink.Assert.ok;

class LogTest {

    @TestRunner.Test
    void writesKeyValuePairsAndQuotesOnlyWhenNeeded() {
        String line = Log.format("INFO", "access", "path", "/abc", "status", 302, "ua", "curl/8 x", "q", "a=b", "e", "");
        ok(line, line.startsWith("ts="));
        String rest = line.substring(line.indexOf(" level="));
        eq(" level=INFO event=access path=/abc status=302 ua=\"curl/8 x\" q=\"a=b\" e=\"\"", rest);
    }

    @TestRunner.Test
    void neutralisesQuotesAndNewlinesSoOneEventIsOneLine() {
        String line = Log.format("WARN", "x", "error", "bad \"thing\"\nnext");
        eq(1L, line.lines().count());
        ok(line, line.endsWith("error=\"bad \\\"thing\\\"\\nnext\""));
    }
}
